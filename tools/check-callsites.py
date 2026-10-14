#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
检查 UIKit 代码里对核心 API 的调用是否与真实签名一致。

★ 为什么需要这个脚本

  开发机是 Windows，**没有 UIKit**。所以 `ios/XtLure/*.swift` 这些界面文件
  只能在本地做 `swiftc -parse`（**语法**检查），做不了 `-typecheck`。
  而"语法对、类型错"正好是一大类错误。

  实测踩到：`MainViewController.swift` 里调用 `GeoViewport` 时参数标签写反了 ——

    have 'centerLat:centerLon:widthPx:heightPx:zoom:'
    expected 'width:height:zoom:centerLat:centerLon:'

  本地语法检查完全看不出问题（括号逗号都对），一路推到 GitHub Actions 的
  macOS runner 上才报出来。而 macOS runner 是**贵额度**（私有仓库按 10 倍计费），
  一轮来回要等好几分钟。

  同一处还错了两件事：`width` 是 Float 却传了整型字面量、
  `pxPerLonDeg` 是属性却写成了方法调用 `pxPerLonDeg()`。
  这三处都是"签名不匹配"，而签名是可以静态比对的。

★ 这个脚本做什么

  1. 从 `ios/Sources/XtLureCore/*.swift` 解析出**公开 API 签名**：
     · struct / enum 的 init 参数标签与类型
     · 静态方法、实例方法的参数标签
     · 属性名（用来抓"属性被当成方法调用"）
  2. 扫描 `ios/XtLure/*.swift` 里的调用点
  3. 比对参数标签、参数个数、属性/方法误用

  **这不是一个完整的类型检查器**，做不到 Swift 编译器的精度。
  但它能抓住"参数标签写错""属性当方法调""传错类型"这三类，
  而这三类恰好是"本地验不出、CI 才报错"的主要来源。

★ 明确不做的
  · 不检查 UIKit 自身的 API 用法（那需要真的 UIKit）
  · 不做类型推断，只看字面量形态与声明的粗略匹配
  目标是"把能在本地发现的错误拦在本地"，不是取代编译器。

用法:
    python tools/check-callsites.py
退出码: 0 通过 / 1 有问题
"""
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CORE_DIR = os.path.join(ROOT, "ios", "Sources", "XtLureCore")
UI_DIR = os.path.join(ROOT, "ios", "XtLure")

problems = []
warnings = []


# ---------------------------------------------------------------- 解析核心 API

class Api:
    """从核心文件里解析出来的 API 描述"""

    def __init__(self):
        # 类型名集合（struct / enum / class）
        self.types = set()
        # {类型名: [(标签元组, 参数类型元组), ...]}
        self.inits = {}
        # {类型名: {方法名: [(标签元组, 参数类型元组), ...]}}
        self.static_funcs = {}
        self.instance_funcs = {}
        # {类型名: {属性名: 类型}}
        self.properties = {}
        # 类型的静态属性：{类型名: {属性名}}
        self.static_props = {}


def strip_comments(text):
    """去掉 // 行注释与 /* */ 块注释（字符串内的不动）"""
    out = []
    i = 0
    n = len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            if c == "\\" and i + 1 < n:
                out.append(text[i:i + 2])
                i += 2
                continue
            if c == '"':
                in_str = False
            out.append(c)
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
            continue
        if text.startswith("/*", i):
            j = text.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def split_params(s):
    """把参数列表按顶层逗号切开（忽略括号/尖括号内的逗号）"""
    parts = []
    depth = 0
    cur = ""
    for ch in s:
        if ch in "([<":
            depth += 1
        elif ch in ")]>":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(cur)
            cur = ""
        else:
            cur += ch
    if cur.strip():
        parts.append(cur)
    return [p.strip() for p in parts if p.strip()]


def parse_param(p):
    """解析一个参数，返回 (标签, 类型, 是否有默认值)。

    没有标签时返回 (None, 类型, 有默认值)。

    ★「有默认值」这个信息是必须的：Swift 允许省略带默认值的参数，
      所以调用点的标签集合**不需要**与声明完全相等，只要：
        · 给出的标签都在声明里，且顺序正确
        · 没有默认值的参数都给了
      第一版没考虑这点，把 `ViewNode(...)` 省略尾部默认参数判成了错误 ——
      **误报**。误报比不报更糟：它会训练人忽略这个检查。
    """
    # 形式：`name: Type` / `_ name: Type` / `_ name: Type = 默认值`
    head, sep, _ = p.partition("=")
    has_default = bool(sep)
    head = head.strip()
    m = re.match(r"^(_\s+)?(\w+)\s*:\s*(.+)$", head)
    if not m:
        return None, None, has_default
    explicit_underscore = bool(m.group(1))
    name = m.group(2)
    typ = m.group(3).strip()
    if explicit_underscore:
        return None, typ, has_default        # `_ name: Type` 调用时不写标签
    return name, typ, has_default


def labels_ok(given, decl_labels, decl_defaults):
    """调用点给出的标签是否与某个声明相容。

    相容的定义（按 Swift 的调用规则）：
      1. 给出的标签都能在声明里按顺序找到（顺序不能乱、不能跳过后又回头）
      2. 声明里**没有默认值**的参数，调用时必须给出
    """
    # 1) 顺序检查：给出的标签必须是声明标签序列的子序列（按顺序）
    it = iter(decl_labels)
    for g in given:
        if not any(d == g for d in it):
            return False
    # 2) 必填检查：无默认值的标签必须都出现
    for lbl, has_def in zip(decl_labels, decl_defaults):
        if lbl is None:
            continue          # `_ name:` 形式，没有标签可查
        if not has_def and lbl not in given:
            return False
    return True


def parse_core():
    api = Api()
    if not os.path.isdir(CORE_DIR):
        return api
    for fn in sorted(os.listdir(CORE_DIR)):
        if not fn.endswith(".swift"):
            continue
        text = strip_comments(open(os.path.join(CORE_DIR, fn), encoding="utf-8").read())

        # 类型声明
        for m in re.finditer(r"public\s+(?:final\s+)?(?:struct|enum|class)\s+(\w+)", text):
            api.types.add(m.group(1))

        # 逐个类型块解析（用嵌套深度找出块的范围）
        for tm in re.finditer(r"public\s+(?:final\s+)?(?:struct|enum|class)\s+(\w+)[^{]*\{",
                              text):
            tname = tm.group(1)
            start = tm.end()
            depth = 1
            i = start
            while i < len(text) and depth > 0:
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                i += 1
            body = text[start:i - 1]

            api.inits.setdefault(tname, [])
            api.static_funcs.setdefault(tname, {})
            api.instance_funcs.setdefault(tname, {})
            api.properties.setdefault(tname, {})
            api.static_props.setdefault(tname, set())

            # init
            for im in re.finditer(r"public\s+init\s*\(([^)]*)\)", body):
                params = [parse_param(p) for p in split_params(im.group(1))]
                labels = tuple(l for (l, _, _) in params)
                types = tuple(t for (_, t, _) in params)
                defaults = tuple(d for (_, _, d) in params)
                api.inits[tname].append((labels, types, defaults))

            # 静态方法
            for fm in re.finditer(r"public\s+static\s+func\s+(\w+)\s*\(([^)]*)\)", body):
                params = [parse_param(p) for p in split_params(fm.group(2))]
                labels = tuple(l for (l, _, _) in params)
                types = tuple(t for (_, t, _) in params)
                api.static_funcs[tname].setdefault(fm.group(1), []).append((labels, types))

            # 实例方法
            for fm in re.finditer(r"public\s+func\s+(\w+)\s*\(([^)]*)\)", body):
                params = [parse_param(p) for p in split_params(fm.group(2))]
                labels = tuple(l for (l, _, _) in params)
                types = tuple(t for (_, t, _) in params)
                first = fm.group(1)
                if first == "init":
                    continue
                api.instance_funcs[tname].setdefault(first, []).append((labels, types))

            # 实例属性
            for pm in re.finditer(r"public\s+(?:var|let)\s+(\w+)\s*(?::\s*([^={]+))?", body):
                name = pm.group(1)
                typ = (pm.group(2) or "").strip()
                api.properties[tname][name] = typ

            # 静态属性
            for pm in re.finditer(r"public\s+static\s+(?:var|let)\s+(\w+)", body):
                api.static_props[tname].add(pm.group(1))

    return api


# ---------------------------------------------------------------- 检查调用点

def type_of_literal(arg):
    """粗略判断一个字面量的类型，用于和声明比对。判不准返回 None"""
    a = arg.strip()
    if re.fullmatch(r"-?\d+", a):
        return "Int"
    if re.fullmatch(r"-?\d+\.\d+", a):
        return "Double"
    if a in ("true", "false"):
        return "Bool"
    if a.startswith('"'):
        return "String"
    return None


def check_call_site(api, fname, text, problems):
    """扫描一个 UI 文件里的调用点"""
    # 1) 属性被当成方法调用：`xxx.pxPerLatDeg()`
    for tname in api.types:
        for pname in api.properties.get(tname, {}):
            pat = re.compile(r"\." + re.escape(pname) + r"\s*\(\s*\)")
            for m in pat.finditer(text):
                # 只报"核心类型确实有这个属性"的情况
                line = text[:m.start()].count("\n") + 1
                problems.append(
                    "%s:%d  `%s` 是属性不是方法，不该写成 `%s()`"
                    % (fname, line, pname, pname))

    # 2) 初始化调用：`TypeName(` 且不是 `TypeName.`（避免匹配静态成员访问）
    for tname in api.types:
        pat = re.compile(r"\b" + re.escape(tname) + r"\s*\(")
        for m in pat.finditer(text):
            # 跳过 `TypeName.staticCall(` 里的情况
            after = text[m.end():]
            # 取出实参列表的标签
            depth = 1
            i = m.end()
            while i < len(text) and depth > 0:
                if text[i] == "(":
                    depth += 1
                elif text[i] == ")":
                    depth -= 1
                i += 1
            args = text[m.end():i - 1]
            if not args.strip():
                given = ()
            else:
                given = tuple(
                    (re.match(r"^(\w+)\s*:", a).group(1)
                     if re.match(r"^(\w+)\s*:", a) else None)
                    for a in split_params(args))

            decls = api.inits.get(tname, [])
            if not decls:
                continue
            if not any(labels_ok(given, d[0], d[2]) for d in decls if len(d) >= 3):
                line = text[:m.start()].count("\n") + 1
                want = " / ".join(
                    "(" + ", ".join(
                        ("%s:" % l) + ("" if dd else " ★必填")
                        for l, dd in zip(d[0], d[2])) + ")"
                    for d in decls if len(d) >= 3)
                have = "(" + ", ".join(
                    "%s:" % g if g else "_" for g in given) + ")"
                problems.append(
                    "%s:%d  %s 初始化参数标签不匹配\n        实际: %s\n        期望: %s"
                    % (fname, line, tname, have, want))

            # 类型粗略比对：声明是 Float 却传整数字面量
            for d in decls:
                if len(d[1]) != len(given):
                    continue
                for idx, (decl_type, arg) in enumerate(zip(d[1], split_params(args))):
                    lit = type_of_literal(arg)
                    if lit is None or not decl_type:
                        continue
                    base = decl_type.replace("?", "").strip()
                    if base == "Float" and lit == "Int":
                        line = text[:m.start()].count("\n") + 1
                        problems.append(
                            "%s:%d  %s 第 %d 个参数声明为 Float，传了整数字面量 `%s`"
                            % (fname, line, tname, idx + 1, arg.strip()))
                    break


def parse_app_types():
    """解析界面层**自己**的类型（例如 `Diag`）及其静态成员。

    ★ 为什么要加这一层

      原来的检查只对照核心层 API，抓不到界面层内部的引用错误。实测踩到：

        重构 Diagnostics.swift 时把 `deviceModel()` 移进了 ViewTree，
        但 AppDelegate 和 MainViewController 还在调 `Diag.deviceModel()`
        -> `error: type 'Diag' has no member 'deviceModel'`

      本地语法检查看不出（`Diag.deviceModel()` 语法完全合法），
      一路推到 CI 的 macOS runner 才报出来。而这类错误纯属本地就能查的 ——
      只要知道 `Diag` 有哪些成员。

    只统计**顶层**类型（顶格的 enum/class/struct/extension），
    因为嵌套类型的成员用法（`Outer.Inner.x`）模式不同，误判风险高。
    """
    types = {}          # {类型名: {"static": set(), "all": set()}}
    if not os.path.isdir(UI_DIR):
        return types
    for fn in sorted(os.listdir(UI_DIR)):
        if not fn.endswith(".swift"):
            continue
        text = strip_comments(open(os.path.join(UI_DIR, fn), encoding="utf-8").read())

        # 顶层类型声明（行首无缩进）
        for m in re.finditer(r"(?m)^(?:public\s+|internal\s+|final\s+)*"
                             r"(?:enum|class|struct|extension)\s+(\w+)", text):
            types.setdefault(m.group(1), {"static": set(), "all": set()})

        # 逐个顶层类型块，收集成员
        for tm in re.finditer(r"(?m)^(?:public\s+|internal\s+|final\s+)*"
                              r"(?:enum|class|struct)\s+(\w+)[^{]*\{", text):
            tname = tm.group(1)
            start = tm.end()
            depth = 1
            i = start
            while i < len(text) and depth > 0:
                if text[i] == "{":
                    depth += 1
                elif text[i] == "}":
                    depth -= 1
                i += 1
            body = text[start:i - 1]
            types.setdefault(tname, {"static": set(), "all": set()})
            # static 成员才是"类型 API"，报错时要突出显示这些
            for mm in re.finditer(r"\bstatic\s+(?:func|var|let)\s+(\w+)", body):
                types[tname]["static"].add(mm.group(1))
                types[tname]["all"].add(mm.group(1))
            # 函数/属性里声明的局部变量也会被收进来（用于"合法"判定，避免误报），
            # 但它们不是类型 API，报错时不作为"可用成员"展示
            for mm in re.finditer(r"\b(?:func|var|let)\s+(\w+)", body):
                types[tname]["all"].add(mm.group(1))
    return types


def check_app_internal_refs(app_types, problems):
    """检查界面层各文件里 `TypeName.member` 的引用是否真实存在。

    这是上面那个 `Diag.deviceModel` 错误的直接防护。
    """
    if not os.path.isdir(UI_DIR):
        return
    # Swift 语言自带的"成员"，不是类型声明的成员。漏掉它们会误报。
    #   .self  —— 元类型（`SceneDelegate.self`、`[weak self]` 那套）
    #   .Type / .Protocol —— 元类型与协议类型
    #   .init  —— 构造器引用
    LANGUAGE_MEMBERS = {"self", "Type", "Protocol", "init", "alloc", "deinit"}
    for fn in sorted(os.listdir(UI_DIR)):
        if not fn.endswith(".swift"):
            continue
        text = strip_comments(open(os.path.join(UI_DIR, fn), encoding="utf-8").read())
        for tname, info in app_types.items():
            members = info["all"]
            if not members:
                continue
            for m in re.finditer(r"\b" + re.escape(tname) + r"\.(\w+)", text):
                member = m.group(1)
                if member in LANGUAGE_MEMBERS:
                    continue
                # 类型自身的声明行与 extension 不算引用
                line_start = text.rfind("\n", 0, m.start()) + 1
                line = text[line_start:text.find("\n", m.start())]
                stripped = line.strip()
                if stripped.startswith(("enum ", "class ", "struct ", "extension ",
                                        "public enum ", "public class ",
                                        "public struct ", "final class ",
                                        "public final class ")):
                    continue
                if member not in members:
                    line_no = text[:m.start()].count("\n") + 1
                    # 只展示 static 成员 —— 那才是类型的 API。
                    # 局部变量名虽然也在 all 里（收集它们是为了避免误报），
                    # 但作为"可用成员"展示只会干扰阅读：真正要找的
                    # `rawDeviceIdentifier` / `deviceDescription` 会被
                    # `bt` / `bytes` / `contentH` 之类淹没。
                    api_members = sorted(info["static"])
                    problems.append(
                        "%s:%d  `%s` 没有成员 `%s`\n"
                        "        可用成员: %s"
                        % (fn, line_no, tname, member,
                           ", ".join(api_members) if api_members
                           else "（该类型没有 static 成员）"))


def main():
    # ---------------------------------------------------------------- 自测
    #
    # ★ 为什么检查器自己也要有测试
    #
    #   这个脚本的第一版有个**误报**：不认 Swift 的默认参数，
    #   把 `ViewNode(...)` 省略尾部默认参数判成了错误。
    #   而修误报很容易顺手修成**漏报**（放宽到什么都通过）——
    #   那样它就变成一个永远说"通过"的摆设，比没有还糟。
    #
    #   所以用几个已知案例把判定逻辑钉住：真正该报的必须报，合法的必须放行。
    #   其中第一条就是实测踩到的那个 CI 失败，它必须一直被抓住。
    cases = [
        # (说明, 调用点标签, 声明标签, 声明默认值, 期望结论)
        ("实测踩到的真实错误：GeoViewport 参数标签顺序写反",
         ("centerLat", "centerLon", "widthPx", "heightPx", "zoom"),
         ("width", "height", "zoom", "centerLat", "centerLon"),
         (False, False, False, False, False), False),
        ("正确调用（全部参数给齐）",
         ("width", "height", "zoom", "centerLat", "centerLon"),
         ("width", "height", "zoom", "centerLat", "centerLon"),
         (False, False, False, False, False), True),
        ("正确调用（省略尾部有默认值的参数）—— 第一版误报的就是这种",
         ("typeName", "x", "y", "width", "height", "hidden", "transparent"),
         ("typeName", "x", "y", "width", "height", "hidden",
          "transparent", "scrollContentHeight", "text",
          "isContentBearing", "children"),
         (False, False, False, False, False, True,
          True, True, True, True, True), True),
        ("漏了必填参数",
         ("typeName", "x"), ("typeName", "x", "y"),
         (False, False, False), False),
        ("标签顺序颠倒",
         ("y", "x"), ("x", "y"), (False, False), False),
        ("多给了声明里没有的标签",
         ("width", "height", "zoom", "nonsense"),
         ("width", "height", "zoom"), (False, False, False), False),
    ]
    st_bad = []
    for desc, given, decl, defaults, want in cases:
        if labels_ok(given, decl, defaults) != want:
            st_bad.append("%s（期望 %s）" % (desc, want))
    print("")
    if st_bad:
        print("  [FAIL] 检查器自身的判定逻辑有问题，它给出的结论不可信：")
        for b in st_bad:
            print("    " + b)
        return 1
    print("  检查器自测通过（%d 个案例：该报的报，合法的放行）" % len(cases))

    api = parse_core()
    print("")
    print("  从核心层解析出:")
    print("    类型 %d 个: %s" % (len(api.types), ", ".join(sorted(api.types))))
    init_total = sum(len(v) for v in api.inits.values())
    fn_total = sum(len(fs) for v in api.instance_funcs.values() for fs in v.values())
    fn_total += sum(len(fs) for v in api.static_funcs.values() for fs in v.values())
    prop_total = sum(len(v) for v in api.properties.values())
    print("    初始化器 %d 个 / 方法 %d 个 / 属性 %d 个" % (init_total, fn_total, prop_total))

    if not os.path.isdir(UI_DIR):
        print("  找不到界面目录: %s" % UI_DIR)
        return 1

    ui_files = [f for f in sorted(os.listdir(UI_DIR)) if f.endswith(".swift")]
    print("")
    print("  检查 %d 个界面文件:" % len(ui_files))
    for fn in ui_files:
        text = strip_comments(open(os.path.join(UI_DIR, fn), encoding="utf-8").read())
        before = len(problems)
        check_call_site(api, fn, text, problems)
        print("    %-28s %s" % (fn, "✅" if len(problems) == before else "⚠ 有 %d 个问题"
                                % (len(problems) - before)))

    # 界面层自身的类型引用（例如 `Diag.xxx`）
    app_types = parse_app_types()
    named = {k: v["static"] for k, v in app_types.items() if v["static"]}
    print("")
    print("  界面层自定义类型 %d 个: %s" % (
        len(named), ", ".join(sorted(named))))
    before = len(problems)
    check_app_internal_refs(app_types, problems)
    print("  界面层内部引用检查: %s"
          % ("✅" if len(problems) == before else "⚠ 有 %d 个问题" % (len(problems) - before)))

    print("")
    if problems:
        for p in problems:
            print("  [FAIL] " + p)
        print("")
        print("  共 %d 个问题" % len(problems))
        print("")
        print("  ★ 这些是「语法检查看不出、只有真编译器才报」的错误。")
        print("    本地拦下来能省掉一轮 macOS CI（贵额度，来回好几分钟）。")
        return 1

    print("  界面代码对核心 API 的调用与声明一致 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
