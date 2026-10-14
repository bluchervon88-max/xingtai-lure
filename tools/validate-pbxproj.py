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
    targets = [o for o in objects.values()
               if isinstance(o, dict) and o.get("isa") == "PBXNativeTarget"]
    compiled_refs = []
    for t in targets:
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
                    compiled_refs.append(fr)

    dup = [x for x in set(compiled_refs) if compiled_refs.count(x) > 1]
    for d in dup:
        problems.append("同一文件被重复加入编译列表: %s"
                        % objects.get(d, {}).get("path", d[:8]))
    print("  参与编译的源文件: %d 个（去重后 %d）"
          % (len(compiled_refs), len(set(compiled_refs))))

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
