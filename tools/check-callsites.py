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
    """解析一个参数，返回 (标签, 类型)。没有标签时返回 (None, 类型)"""
    # 形式：`name: Type` / `_ name: Type` / `_ name: Type = 默认值`
    p = p.split("=")[0].strip()
    m = re.match(r"^(_\s+)?(\w+)\s*:\s*(.+)$", p)
    if not m:
        return None, None
    explicit_underscore = bool(m.group(1))
    name = m.group(2)
    typ = m.group(3).strip()
    if explicit_underscore:
        return None, typ        # `_ name: Type` 调用时不写标签
    return name, typ


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
                labels = tuple(l for (l, _) in params)
                types = tuple(t for (_, t) in params)
                api.inits[tname].append((labels, types))

            # 静态方法
            for fm in re.finditer(r"public\s+static\s+func\s+(\w+)\s*\(([^)]*)\)", body):
                params = [parse_param(p) for p in split_params(fm.group(2))]
                labels = tuple(l for (l, _) in params)
                types = tuple(t for (_, t) in params)
                api.static_funcs[tname].setdefault(fm.group(1), []).append((labels, types))

            # 实例方法
            for fm in re.finditer(r"public\s+func\s+(\w+)\s*\(([^)]*)\)", body):
                params = [parse_param(p) for p in split_params(fm.group(2))]
                labels = tuple(l for (l, _) in params)
                types = tuple(t for (_, t) in params)
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
            if any(g == d[0] for (d) in decls for g in [given]):
                pass
            if not any(given == d[0] for d in decls):
                line = text[:m.start()].count("\n") + 1
                want = " / ".join(
                    "(" + ", ".join("%s:" % l for l in d[0]) + ")" for d in decls)
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


def main():
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
