#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验手写的 Xcode 工程文件（project.pbxproj）。

★ 为什么需要这个脚本

  开发机是 Windows，没有 Xcode，无法用图形界面建工程，`project.pbxproj`
  是**手写**的。而 pbxproj 有个很坑的特性：

    它内部靠 24 位十六进制 ID 互相引用，写错了不一定报错 ——
    Xcode 可能**静默漏编某个文件**：构建成功、产物正常，
    但那部分代码根本没进去，直到运行时才崩，而且崩的地方看起来毫不相干。

  所以必须有一道自动检查。

★ 为什么自己写 OpenStep plist 解析器

  一开始想用 Python 标准库的 `plistlib`，结果它对 pbxproj 一律报 "Invalid file"。
  实测发现 **`plistlib` 只支持 XML 和二进制两种格式，根本不支持 OpenStep**
  （pbxproj 用的就是 OpenStep 格式）。

  自己写反而更合适：能给出**行号级**的错误定位，
  plistlib 只会说一句 "Invalid file"，对排查没有帮助。

  本解析器支持 OpenStep plist 的这个子集：
    · 字典 `{ key = value; ... }`（键可以是裸标识符或带引号字符串）
    · 数组 `( a, b, c )`
    · 带引号字符串（含转义）
    · 无引号字符串（裸 token）
    · `/* ... */` 块注释 与 `//` 行注释

用法:
    python tools/validate-pbxproj.py
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
PROJ_DIR = os.path.join(ROOT, "ios", "XtLure.xcodeproj")
# ★ pbxproj 里的相对路径是相对 `.xcodeproj` **所在的目录**，
#   不是相对 `.xcodeproj` 本身。mainGroup 的 sourceTree 是 "<group>" 且没有 path，
#   所以从它往下的路径都从工程目录（ios/）起算。
#   一开始搞错了基准，导致磁盘检查误报 13 个"文件不存在"。
PROJ_BASE = os.path.dirname(PROJ_DIR)
PBXPROJ = os.path.join(PROJ_DIR, "project.pbxproj")
SCHEME = os.path.join(PROJ_DIR, "xcshareddata", "xcschemes", "XtLure.xcscheme")

problems = []
warnings = []


class ParseError(Exception):
    pass


class OpenStepParser:
    """极简 OpenStep plist 解析器。带行列号，便于定位手写文件的语法错误。"""

    def __init__(self, text):
        self.s = text
        self.i = 0

    def line_of(self, pos=None):
        pos = self.i if pos is None else pos
        return self.s.count("\n", 0, pos) + 1

    def err(self, msg):
        return ParseError("第 %d 行: %s" % (self.line_of(), msg))

    def skip_ws(self):
        """跳过空白与注释"""
        while self.i < len(self.s):
            c = self.s[self.i]
            if c in " \t\r\n":
                self.i += 1
            elif self.s.startswith("//", self.i):
                j = self.s.find("\n", self.i)
                self.i = len(self.s) if j < 0 else j
            elif self.s.startswith("/*", self.i):
                j = self.s.find("*/", self.i + 2)
                if j < 0:
                    raise self.err("块注释没有闭合的 */")
                self.i = j + 2
            else:
                return

    def parse_value(self):
        self.skip_ws()
        if self.i >= len(self.s):
            raise self.err("文件意外结束，缺少值")
        c = self.s[self.i]
        if c == "{":
            return self.parse_dict()
        if c == "(":
            return self.parse_array()
        if c == '"':
            return self.parse_quoted()
        return self.parse_bare()

    def parse_dict(self):
        self.i += 1                       # 吃掉 {
        result = {}
        while True:
            self.skip_ws()
            if self.i >= len(self.s):
                raise self.err("字典没有闭合的 }")
            if self.s[self.i] == "}":
                self.i += 1
                return result

            key = self.parse_quoted() if self.s[self.i] == '"' else self.parse_bare()
            self.skip_ws()
            if self.i >= len(self.s) or self.s[self.i] != "=":
                raise self.err("键 %r 之后缺少 =" % key)
            self.i += 1
            result[key] = self.parse_value()

            self.skip_ws()
            if self.i < len(self.s) and self.s[self.i] == ";":
                self.i += 1
            elif self.i < len(self.s) and self.s[self.i] == "}":
                # 最后一个条目允许省略分号
                pass
            else:
                raise self.err("键 %r 的值之后缺少分号" % key)

    def parse_array(self):
        self.i += 1                       # 吃掉 (
        result = []
        while True:
            self.skip_ws()
            if self.i >= len(self.s):
                raise self.err("数组没有闭合的 )")
            if self.s[self.i] == ")":
                self.i += 1
                return result
            result.append(self.parse_value())
            self.skip_ws()
            if self.i < len(self.s) and self.s[self.i] == ",":
                self.i += 1
            elif self.i < len(self.s) and self.s[self.i] == ")":
                pass
            else:
                raise self.err("数组元素之后缺少逗号")

    def parse_quoted(self):
        self.i += 1                       # 吃掉开引号
        buf = []
        while self.i < len(self.s):
            c = self.s[self.i]
            if c == "\\":
                if self.i + 1 >= len(self.s):
                    raise self.err("字符串以反斜杠结尾")
                nxt = self.s[self.i + 1]
                buf.append({"n": "\n", "t": "\t", '"': '"', "\\": "\\"}.get(nxt, nxt))
                self.i += 2
                continue
            if c == '"':
                self.i += 1
                return "".join(buf)
            buf.append(c)
            self.i += 1
        raise self.err("字符串没有闭合的引号")

    def parse_bare(self):
        start = self.i
        while self.i < len(self.s):
            c = self.s[self.i]
            if c in " \t\r\n=;,(){}\"" or self.s.startswith("//", self.i) \
                    or self.s.startswith("/*", self.i):
                break
            self.i += 1
        if self.i == start:
            raise self.err("无法识别的字符 %r" % self.s[self.i])
        return self.s[start:self.i]


def rel(p):
    return os.path.relpath(p, ROOT)


def main():
    global problems

    if not os.path.exists(PBXPROJ):
        print("  [FAIL] 找不到 " + rel(PBXPROJ))
        return 1

    with open(PBXPROJ, "r", encoding="utf-8") as f:
        text = f.read()

    # ---- 0. ★ 重复对象 ID 检查（必须在解析**之前**做）
    #
    #   pbxproj 的 objects 是一个大字典。如果两个对象用了同一个 ID，
    #   解析成 Python 字典时**后者会静默覆盖前者** ——
    #   而 pbxproj 里对象的排列是字母序的，被覆盖掉的那个就不存在了。
    #
    #   实测踩到：加 UI 测试 target 时给测试源文件分配了 BB...0013，
    #   那个 ID 已经被 SystemTimeZone.swift 占用了。结果是
    #   `SystemTimeZone.swift in Sources` 那一行被覆盖掉 ——
    #   引用还在，但对象没了。
    #
    #   这种错误的表现极隐蔽：Xcode 可能报一个看不出原因的错误，
    #   也可能**静默地不编译那个文件**（构建成功、产物正常、代码没进去，
    #   直到运行时才崩）。所以必须在解析前用文本级扫描抓出来。
    #
    #   注意：同一个 ID 在文件里正常会出现多次（定义一次 + 被引用多次），
    #   所以必须把"定义"和"引用"区分开。二者在文本上长得很像：
    #
    #     定义（两处都是）：
    #       \t\tAA0000000000000000000013 /* X */ = {     <- 顶层对象，2 个 tab
    #
    #     引用 —— 有两种写法：
    #       \t\t\t\tAA0000000000000000000013 /* X */,   <- 数组元素，逗号结尾
    #       \t\t\t\t\tDD0000000000000000000001 = {       <- 字典元素，缩进更深
    #
    #   ★ 最后那种是踩过的坑：`PBXTargetDependency.targetProxy` 是个**字典**，
    #     里面用 `ID = { ... }` 形式引用另一个对象，语法上与定义一模一样。
    #     第一版只按"行首是 ID 且后面跟 = {"判定，把这种引用也算成了定义 ——
    #     **误报**，而且报的是工程里本来就存在的合法写法。
    #
    #   可靠的区分方式是缩进深度：顶层对象的定义固定是 2 个 tab。
    #   这不是"聪明"的办法，但 pbxproj 的格式就是固定的，
    #   而且比写一个完整的结构解析器更难出错。
    id_defs = re.findall(r"(?m)^\t\t([A-Fa-f0-9]{24})\s*(?:/\*.*?\*/)?\s*=\s*\{", text)
    seen_def = {}
    for oid in id_defs:
        seen_def[oid] = seen_def.get(oid, 0) + 1
    dupes = {k: v for k, v in seen_def.items() if v > 1}
    if dupes:
        for oid, cnt in sorted(dupes.items()):
            # 把被重复定义的行都找出来，方便定位
            locs = [i + 1 for i, line in enumerate(text.split("\n"))
                    if re.match(r"^\t\t%s\s*(?:/\*.*?\*/)?\s*=\s*\{" % oid, line)]
            problems.append("对象 ID 重复定义 %d 次: %s（第 %s 行）\n"
                            "        → 解析成字典时后者会覆盖前者，"
                            "被覆盖的对象等于不存在"
                            % (cnt, oid, ", ".join(str(x) for x in locs)))
        print("  ⚠ 发现 %d 个重复的对象 ID" % len(dupes))
    else:
        print("  对象 ID 唯一性: %d 个定义，无重复 ✅" % len(id_defs))

    try:
        plist = OpenStepParser(text).parse_value()
    except ParseError as e:
        print("  [FAIL] pbxproj 语法错误: %s" % e)
        return 1

    objects = plist.get("objects", {})
    root_id = plist.get("rootObject")
    print("  pbxproj 解析成功: %d 个对象, %d 行" % (len(objects), text.count("\n") + 1))

    # ---- 1. rootObject 有效性
    if root_id not in objects:
        problems.append("rootObject 指向不存在的对象: %s" % root_id)
    elif objects[root_id].get("isa") != "PBXProject":
        problems.append("rootObject 不是 PBXProject，而是 %s"
                        % objects[root_id].get("isa"))
    else:
        print("  rootObject -> PBXProject ✅")

    # ---- 2. 引用完整性
    ref_kinds = {
        "PBXBuildFile": ["fileRef"],
        "PBXGroup": ["children"],
        "PBXProject": ["mainGroup", "productRefGroup", "targets",
                       "buildConfigurationList"],
        "PBXNativeTarget": ["buildConfigurationList", "buildPhases",
                            "productReference"],
        "PBXSourcesBuildPhase": ["files"],
        "PBXResourcesBuildPhase": ["files"],
        "PBXFrameworksBuildPhase": ["files"],
        "XCConfigurationList": ["buildConfigurations"],
    }

    dangling = 0
    for oid, obj in objects.items():
        if not isinstance(obj, dict):
            continue
        for key in ref_kinds.get(obj.get("isa"), []):
            val = obj.get(key)
            if val is None:
                continue
            for it in (val if isinstance(val, list) else [val]):
                if isinstance(it, str) and it not in objects:
                    problems.append("%s(%s).%s 引用了不存在的对象 %s"
                                    % (obj.get("isa"), oid[:8], key, it[:8]))
                    dangling += 1
    print("  悬空引用: %d 个" % dangling)

    # ---- 3. ID 长度（pbxproj 要求 24 位十六进制）
    bad_ids = [oid for oid in objects if not re.fullmatch(r"[0-9A-F]{24}", oid)]
    if bad_ids:
        for b in bad_ids[:5]:
            problems.append("对象 ID 不是 24 位十六进制: %r" % b)
    else:
        print("  对象 ID 格式: 全部 24 位十六进制 ✅")

    # ---- 4. Sources 阶段的引用链
    #
    # ★ 重复检查必须**按 target 分别做**，不能把所有 target 的编译列表合起来看。
    #
    #   这个工程有两个 target：App（XtLure）和 UI 测试（XtLureUITests）。
    #   它们各自有独立的 Sources 阶段，同一个文件在两个 target 里各出现一次
    #   是**完全正常**的（逻辑层代码 App 要编、测试也要编）。
    #
    #   第一版把所有 target 的 fileRef 收进一个列表再找重复，
    #   于是把这种正常情况报成了"同一文件被重复加入编译列表" —— **误报**。
    #   误报比不报更糟：它会训练人忽略这个检查。
    targets = [o for o in objects.values()
               if isinstance(o, dict) and o.get("isa") == "PBXNativeTarget"]
    compiled_refs = []
    target_names = {t.get("name", "?"): oid for oid, t in objects.items()
                    if isinstance(t, dict) and t.get("isa") == "PBXNativeTarget"}
    used_in_targets = {}      # fileRef -> [target 名...]

    for t in targets:
        tname = t.get("name", "?")
        local = []
        for ph_id in t.get("buildPhases", []):
            ph = objects.get(ph_id, {})
            if ph.get("isa") != "PBXSourcesBuildPhase":
                continue
            for bf_id in ph.get("files", []):
                bf = objects.get(bf_id, {})
                fr = bf.get("fileRef")
                if fr not in objects:
                    problems.append("PBXBuildFile %s 的 fileRef 缺失/无效" % bf_id[:8])
                else:
                    local.append(fr)
                    compiled_refs.append(fr)
                    used_in_targets.setdefault(fr, []).append(tname)
        # 同一个 target **内部**重复才是错误
        for fr in set(local):
            if local.count(fr) > 1:
                problems.append("target %s 内部把同一文件重复加入了编译列表: %s"
                                % (tname, objects.get(fr, {}).get("path", fr[:8])))

    print("  参与编译的源文件: %d 个引用（%d 个不同文件，分布在 %d 个 target）"
          % (len(compiled_refs), len(set(compiled_refs)), len(targets)))

    # ---- 5. 文件在磁盘上是否真的存在
    #
    # ★ 这里必须用 posixpath 而不是 os.path。
    #
    #   pbxproj 是给 macOS 用的，路径一律用正斜杠 `/`。
    #   而这个校验脚本跑在 Windows 上，os.path.join 会拼出反斜杠 `\`，
    #   于是明明存在的文件会被判定为"不存在" —— 一开始就是这么误报了 13 个。
    #
    #   真实的坑在于：如果 Windows 上不小心把反斜杠写进了 pbxproj，
    #   本机检查可能"通过"，但推到 CI 上 macOS 找不到文件。
    #   所以这里还额外检查 pbxproj 里有没有混入反斜杠。
    import posixpath

    def resolve_path(obj_id, seen=None):
        seen = seen or set()
        if obj_id in seen:
            return None
        seen.add(obj_id)
        obj = objects.get(obj_id)
        if not isinstance(obj, dict):
            return None
        name = obj.get("path") or obj.get("name")
        if not name:
            return None
        parent = None
        for pid, pobj in objects.items():
            if isinstance(pobj, dict) and pobj.get("isa") == "PBXGroup" \
                    and obj_id in (pobj.get("children") or []):
                parent = pid
                break
        if parent is None:
            return name
        pbase = resolve_path(parent, seen)
        return posixpath.join(pbase, name) if pbase else name

    checked = missing = 0
    backslash_paths = []
    for oid, obj in objects.items():
        if not isinstance(obj, dict) or obj.get("isa") != "PBXFileReference":
            continue
        if not obj.get("path") or obj.get("sourceTree") == "BUILT_PRODUCTS_DIR":
            continue
        if "\\" in obj["path"]:
            backslash_paths.append(obj["path"])
        full = resolve_path(oid)
        if not full:
            continue
        checked += 1
        # 检查时在 Windows 上要把 / 换回 \ 才能问文件系统；
        # 基准是工程目录（ios/）而不是 .xcodeproj 本身。
        disk = os.path.join(PROJ_BASE, full.replace("/", os.sep))
        if not os.path.exists(disk):
            problems.append("源码文件在磁盘上不存在: ios/%s" % full)
            missing += 1
    if backslash_paths:
        for b in backslash_paths:
            problems.append("pbxproj 的 path 里混入了反斜杠（macOS 上会找不到文件）: %r" % b)
    print("  磁盘文件检查: %d 个（缺失 %d）" % (checked, missing))

    # ---- 6. scheme 与 target 一致性
    if os.path.exists(SCHEME):
        with open(SCHEME, "r", encoding="utf-8") as f:
            scheme_text = f.read()
        ids = set(re.findall(r'BlueprintIdentifier\s*=\s*"([^"]+)"', scheme_text))
        target_ids = {oid for oid, o in objects.items()
                      if isinstance(o, dict) and o.get("isa") == "PBXNativeTarget"}
        if not ids:
            problems.append("scheme 里没有 BlueprintIdentifier —— xcodebuild 找不到目标")
        for i in ids:
            if i not in target_ids:
                problems.append("scheme 引用的目标 ID 不存在: %s" % i)
        if ids and not [x for x in ids if x not in target_ids]:
            print("  scheme 目标 ID: %s ✅" % ", ".join(sorted(ids)))
    else:
        warnings.append("找不到共享 scheme: %s" % rel(SCHEME))
        warnings.append("  没有它 xcodebuild -scheme 会失败（CI 上会挂）")

    # ---- 7. 构建配置
    for t in targets:
        cl = objects.get(t.get("buildConfigurationList"), {})
        cfgs = [objects.get(c, {}).get("name")
                for c in cl.get("buildConfigurations", [])]
        if "Release" not in cfgs:
            warnings.append("目标 %s 缺少 Release 配置（CI 用 Release 构建）"
                            % t.get("name"))

    # ---- 8. ★ 漏登记检查：磁盘上的文件有没有被工程收录
    #
    # 这段是这套校验里**最有价值**的一条。
    #
    # 工程文件是手写的，加一个源文件要同时改四处：
    #   PBXBuildFile / PBXFileReference / PBXGroup.children / Sources 阶段
    # 漏掉任何一处都不会立刻报错：
    #   · 漏了 Sources 阶段 -> Xcode 根本不编译它
    #   -> 构建成功、产物正常，但那段代码没进去，**直到运行时才崩**，
    #      而且崩溃位置看起来与漏掉的文件毫无关系
    #   · 漏了 Resources 阶段 -> 资源没打进 App，运行时找不到文件
    #
    # 所以这里反过来查：磁盘上应该被收录的文件，工程里有没有？
    #
    # 判定"应该被收录"的规则（刻意保守，宁可漏报也不误报）：
    #   · ios/XtLure/*.swift        在根目录的 Swift 文件 -> 必须编译
    #   · ios/XtLure/Core/*.swift   逻辑层副本        -> 必须编译
    #   · ios/XtLure/Resources/*    资源              -> 必须打包
    # 子目录里未来若有故意不收录的文件，在下面的 IGNORE 里说明。
    IGNORE = {
        "Info.plist",            # 由 INFOPLIST_FILE 构建设置引用，不走 Resources 阶段
    }

    app_dir = os.path.join(PROJ_BASE, "XtLure")
    compiled_paths = set()
    for fr in compiled_refs:
        p = resolve_path(fr)
        if p:
            compiled_paths.add(p)
    resource_paths = set()
    for oid, obj in objects.items():
        if not isinstance(obj, dict) or obj.get("isa") != "PBXResourcesBuildPhase":
            continue
        for bf_id in obj.get("files", []):
            fr = objects.get(bf_id, {}).get("fileRef")
            p = resolve_path(fr) if fr else None
            if p:
                resource_paths.add(p)

    unregistered = []
    for rel_dir, must_be in [("", "compile"), ("Core", "compile"),
                             ("Resources", "resource")]:
        d = os.path.join(app_dir, rel_dir) if rel_dir else app_dir
        if not os.path.isdir(d):
            continue
        for name in sorted(os.listdir(d)):
            full = os.path.join(d, name)
            if not os.path.isfile(full) or name in IGNORE:
                continue
            rel = ("XtLure/" + rel_dir + "/" + name) if rel_dir else ("XtLure/" + name)
            rel = rel.replace("//", "/")
            if must_be == "compile" and name.endswith(".swift"):
                if rel not in compiled_paths:
                    unregistered.append((rel, "没有被加入编译（Sources 阶段）"))
            elif must_be == "resource":
                if rel not in resource_paths:
                    unregistered.append((rel, "没有被加入打包（Resources 阶段）"))

    for rel, why in unregistered:
        problems.append("文件存在但工程没收录：%s —— %s" % (rel, why))
    if unregistered:
        print("  ⚠ 漏登记 %d 个文件（这些文件不会被编译/打包，但不会报错）"
              % len(unregistered))
    else:
        print("  漏登记检查：磁盘上的源文件与资源都已被工程收录 ✅")

    print("")
    for w in warnings:
        print("  [警告] " + w)
    if problems:
        print("")
        for p in problems:
            print("  [FAIL] " + p)
        print("")
        print("  共 %d 个问题" % len(problems))
        return 1

    print("  pbxproj 结构校验通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
