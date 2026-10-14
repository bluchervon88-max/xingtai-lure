#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
往 Xcode 工程里加一个 UI 测试 target。

★ 为什么需要它

  「诊断能不能用」这件事，本地完全验证不了 —— 开发机是 Windows、没有 UIKit。
  而它恰恰是整条 iOS 路线里最关键的一环：**诊断是开发者唯一能"看见"设备的途径**。
  如果它在真机上写不出文件，用户装完什么都拿不到，又要白等一轮。

  CI 上有 macOS 和 iOS 模拟器。加一个 UI 测试 target 之后，
  就能在模拟器上**真正运行 App**，验证 App 能启动、诊断文件确实被写出、
  报告内容结构正确。这些一旦验证过，用户拿不到数据的可能性就大幅下降。

★ 为什么用脚本生成，而不是手写进 pbxproj

  加一个 target 要同时改十几处，漏任何一处都不会立刻报错。
  脚本化之后这些引用由代码保证一致，再由 tools/validate-pbxproj.py 反查验证。

★ 踩过的坑：ID 冲突

  第一版给测试源文件分配了 `BB...0013`，而那个 ID 已经被
  `SystemTimeZone.swift` 占用了。后果是 `SystemTimeZone.swift in Sources`
  那一行被**静默覆盖** —— 引用还在、对象没了。

  这类错误极隐蔽：Xcode 可能报一个看不出原因的错误，也可能
  静默地不编译那个文件（构建成功、产物正常、代码没进去，运行时才崩）。

  所以脚本现在**先检查要用的 ID 有没有被占用**，占用就直接失败退出。

用法:
    python tools/add-uitest-target.py
"""
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROJ = os.path.join(ROOT, "ios", "XtLure.xcodeproj", "project.pbxproj")

APP_TARGET = "DD0000000000000000000001"
APP_GROUP = "CC0000000000000000000002"

# 新对象的 ID。沿用工程约定：前缀 + 22 位十六进制。
# 分配前已核对：AA/BB 已用到 ...0014，DD 已用到 ...0033，均不冲突。
IDS = {
    "productRef": "AA0000000000000000000015",
    "srcRef":     "AA0000000000000000000016",
    "srcBuild":   "BB0000000000000000000015",
    "group":      "CC0000000000000000000006",
    "target":     "DD0000000000000000000050",
    "sources":    "DD0000000000000000000051",
    "frameworks": "DD0000000000000000000052",
    "dep":        "DD0000000000000000000053",
    "proxy":      "DD0000000000000000000054",
    "cfgList":    "DD0000000000000000000055",
    "cfgDebug":   "DD0000000000000000000056",
    "cfgRelease": "DD0000000000000000000057",
}

TARGET_NAME = "XtLureUITests"
SRC_FILE = "DiagnosticsUITests.swift"


def main():
    text = open(PROJ, encoding="utf-8").read()

    if TARGET_NAME in text:
        print("")
        print("  工程里已经有 %s，跳过（脚本是幂等的）" % TARGET_NAME)
        return 0

    occupied = set(re.findall(r"\b([A-F0-9]{24})\b", text))
    clash = {k: v for k, v in IDS.items() if v in occupied}
    if clash:
        print("  [FAIL] 下列 ID 已被工程占用，请改用别的：")
        for k, v in sorted(clash.items()):
            print("      %-12s %s" % (k, v))
        return 1

    m = re.search(r"(\w{24}) /\* Project object \*/ = \{", text)
    if not m:
        m = re.search(r"rootObject = (\w{24})", text)
    if not m:
        print("  [FAIL] 找不到 PBXProject 的 ID")
        return 1
    project_id = m.group(1)

    if APP_TARGET not in text:
        print("  [FAIL] 找不到 App target %s" % APP_TARGET)
        return 1

    print("")
    print("  PBXProject ID = %s" % project_id)
    print("  App target    = %s" % APP_TARGET)

    state = {"text": text}

    def insert_before(marker, block):
        idx = state["text"].find(marker)
        if idx < 0:
            raise SystemExit("  [FAIL] 找不到标记: " + marker)
        state["text"] = state["text"][:idx] + block + state["text"][idx:]

    def ensure_section(name, anchor="/* Begin PBXGroup section */"):
        """确保某个 section 存在；不存在就在 anchor 之前创建。

        ★ 为什么需要这一步

          原来的工程只有**一个** target（App），所以 pbxproj 里
          根本没有 `PBXContainerItemProxy` 和 `PBXTargetDependency` 这两个 section
          —— 只有"多个 target 之间存在依赖"时才需要它们。

          平时这些 section 由 Xcode 在图形界面里添加目标时自动生成，
          但本项目的开发机是 Windows、工程文件是**手写**的，
          所以要自己把空 section 建出来，否则插入无处可插。
        """
        begin = "/* Begin %s section */" % name
        end = "/* End %s section */" % name
        if begin in state["text"]:
            return
        block = begin + "\n" + end + "\n\n"
        idx = state["text"].find(anchor)
        if idx < 0:
            raise SystemExit("  [FAIL] 创建 %s section 时找不到锚点" % name)
        state["text"] = state["text"][:idx] + block + state["text"][idx:]
        print("    创建空 section: %s" % name)

    ensure_section("PBXContainerItemProxy")
    ensure_section("PBXTargetDependency")

    def fmt(tpl):
        return tpl.format(pr=IDS["productRef"], sr=IDS["srcRef"],
                          sb=IDS["srcBuild"], g=IDS["group"],
                          t=IDS["target"], s=IDS["sources"],
                          fw=IDS["frameworks"], d=IDS["dep"],
                          p=IDS["proxy"], cl=IDS["cfgList"],
                          cd=IDS["cfgDebug"], cr=IDS["cfgRelease"],
                          proj=project_id, app=APP_TARGET,
                          T=TARGET_NAME, F=SRC_FILE)

    # ---- 文件引用
    insert_before("/* End PBXFileReference section */", fmt(
        "\t\t{pr} /* {T}.xctest */ = {{isa = PBXFileReference; "
        "explicitFileType = wrapper.cfbundle; includeInIndex = 0; "
        "path = {T}.xctest; sourceTree = BUILT_PRODUCTS_DIR; }};\n"
        "\t\t{sr} /* {F} */ = {{isa = PBXFileReference; "
        "lastKnownFileType = sourcecode.swift; path = {F}; "
        "sourceTree = \"<group>\"; }};\n"))

    # ---- 构建项
    insert_before("/* End PBXBuildFile section */", fmt(
        "\t\t{sb} /* {F} in Sources */ = {{isa = PBXBuildFile; "
        "fileRef = {sr} /* {F} */; }};\n"))

    # ---- 分组
    insert_before("/* End PBXGroup section */", fmt(
        "\t\t{g} /* {T} */ = {{\n"
        "\t\t\tisa = PBXGroup;\n"
        "\t\t\tchildren = (\n"
        "\t\t\t\t{sr} /* {F} */,\n"
        "\t\t\t);\n"
        "\t\t\tpath = {T};\n"
        "\t\t\tsourceTree = \"<group>\";\n"
        "\t\t}};\n"))

    anchor = "\t\t\t\t%s /* XtLure */,\n" % APP_GROUP
    if anchor not in state["text"]:
        print("  [FAIL] 主分组里找不到 App 源码分组 %s" % APP_GROUP)
        return 1
    state["text"] = state["text"].replace(
        anchor, anchor + "\t\t\t\t%s /* %s */,\n" % (IDS["group"], TARGET_NAME), 1)

    # ---- 编译阶段
    insert_before("/* End PBXSourcesBuildPhase section */", fmt(
        "\t\t{s} /* Sources */ = {{\n"
        "\t\t\tisa = PBXSourcesBuildPhase;\n"
        "\t\t\tbuildActionMask = 2147483647;\n"
        "\t\t\tfiles = (\n"
        "\t\t\t\t{sb} /* {F} in Sources */,\n"
        "\t\t\t);\n"
        "\t\t\trunOnlyForDeploymentPostprocessing = 0;\n"
        "\t\t}};\n"))

    # ---- 链接阶段（XCTest 是系统框架，UI 测试 bundle 需要这个阶段）
    insert_before("/* End PBXFrameworksBuildPhase section */", fmt(
        "\t\t{fw} /* Frameworks */ = {{\n"
        "\t\t\tisa = PBXFrameworksBuildPhase;\n"
        "\t\t\tbuildActionMask = 2147483647;\n"
        "\t\t\tfiles = (\n"
        "\t\t\t);\n"
        "\t\t\trunOnlyForDeploymentPostprocessing = 0;\n"
        "\t\t}};\n"))

    # ---- 依赖
    #
    # ★ containerPortal 必须指向 **PBXProject 的 ID**，不是 target 的 ID。
    #   指向 target 会让 Xcode 报很难懂的 "Unable to find target dependency"。
    insert_before("/* End PBXContainerItemProxy section */", fmt(
        "\t\t{p} /* PBXContainerItemProxy */ = {{\n"
        "\t\t\tisa = PBXContainerItemProxy;\n"
        "\t\t\tcontainerPortal = {proj} /* Project object */;\n"
        "\t\t\tproxyType = 1;\n"
        "\t\t\tremoteGlobalIDString = {app};\n"
        "\t\t\tremoteInfo = XtLure;\n"
        "\t\t}};\n"))

    insert_before("/* End PBXTargetDependency section */", fmt(
        "\t\t{d} /* PBXTargetDependency */ = {{\n"
        "\t\t\tisa = PBXTargetDependency;\n"
        "\t\t\ttarget = {app} /* XtLure */;\n"
        "\t\t\ttargetProxy = {p} /* PBXContainerItemProxy */;\n"
        "\t\t}};\n"))

    # ---- target 本体
    insert_before("/* End PBXNativeTarget section */", fmt(
        "\t\t{t} /* {T} */ = {{\n"
        "\t\t\tisa = PBXNativeTarget;\n"
        "\t\t\tbuildConfigurationList = {cl} /* Build configuration list for "
        "PBXNativeTarget \"{T}\" */;\n"
        "\t\t\tbuildPhases = (\n"
        "\t\t\t\t{s} /* Sources */,\n"
        "\t\t\t\t{fw} /* Frameworks */,\n"
        "\t\t\t);\n"
        "\t\t\tbuildRules = (\n"
        "\t\t\t);\n"
        "\t\t\tdependencies = (\n"
        "\t\t\t\t{d} /* PBXTargetDependency */,\n"
        "\t\t\t);\n"
        "\t\t\tname = {T};\n"
        "\t\t\tproductName = {T};\n"
        "\t\t\tproductReference = {pr} /* {T}.xctest */;\n"
        "\t\t\tproductType = \"com.apple.product-type.bundle.ui-testing\";\n"
        "\t\t}};\n"))

    # 缩进必须精确匹配：targets 列表的子项是 4 个 tab，
    # 收尾的 ); 是 3 个 tab，对象结束的 }; 是 2 个 tab。
    # 写错缩进会让 find() 失败 —— 这是好事，至少不会插错位置。
    # ⚠ 这里必须用 .format() 而不是 % 格式化。
    #
    #   Python 里 % 的优先级**高于**相邻字符串字面量的隐式拼接，
    #   所以 `"a%s" "b%s" % X` 只会把 X 套进**最后一个** "b%s"，
    #   前面的 %s 原样留着 —— 锚点里就多了一个字面的 "%s"，永远匹配不上。
    #   实测踩过这个坑，报错是"找不到 PBXProject 的 targets 列表"，
    #   看起来像工程文件的问题，实际是脚本自己的语法陷阱。
    targets_anchor = ("\t\t\t\t{app} /* XtLure */,\n"
                      "\t\t\t);\n"
                      "\t\t}};\n"
                      "/* End PBXProject section */").format(app=APP_TARGET)
    if targets_anchor not in state["text"]:
        print("  [FAIL] 找不到 PBXProject 的 targets 列表")
        return 1
    state["text"] = state["text"].replace(targets_anchor, fmt(
        "\t\t\t\t{app} /* XtLure */,\n"
        "\t\t\t\t{t} /* {T} */,\n"
        "\t\t\t);\n"
        "\t\t}};\n"
        "/* End PBXProject section */"), 1)

    # ---- 构建配置
    #
    # ★ 三个签名设置全关：CI 上没有证书，Xcode 一旦想签名就会失败。
    settings = (
        "\t\t\t\tCODE_SIGNING_ALLOWED = NO;\n"
        "\t\t\t\tCODE_SIGNING_REQUIRED = NO;\n"
        "\t\t\t\tCODE_SIGN_IDENTITY = \"\";\n"
        "\t\t\t\tCURRENT_PROJECT_VERSION = 1;\n"
        "\t\t\t\tGENERATE_INFOPLIST_FILE = YES;\n"
        "\t\t\t\tIPHONEOS_DEPLOYMENT_TARGET = 15.0;\n"
        "\t\t\t\tMARKETING_VERSION = 1.0;\n"
        "\t\t\t\tPRODUCT_BUNDLE_IDENTIFIER = \"com.xtlure.app.uitests\";\n"
        "\t\t\t\tPRODUCT_NAME = \"$(TARGET_NAME)\";\n"
        "\t\t\t\tSWIFT_VERSION = 5.0;\n"
        "\t\t\t\tTARGETED_DEVICE_FAMILY = 1;\n"
        "\t\t\t\tTEST_TARGET_NAME = XtLure;\n"
    )
    insert_before("/* End XCBuildConfiguration section */",
        "\t\t%s /* Debug */ = {\n"
        "\t\t\tisa = XCBuildConfiguration;\n"
        "\t\t\tbuildSettings = {\n"
        "%s"
        "\t\t\t};\n"
        "\t\t\tname = Debug;\n"
        "\t\t};\n"
        "\t\t%s /* Release */ = {\n"
        "\t\t\tisa = XCBuildConfiguration;\n"
        "\t\t\tbuildSettings = {\n"
        "%s"
        "\t\t\t};\n"
        "\t\t\tname = Release;\n"
        "\t\t};\n"
        % (IDS["cfgDebug"], settings, IDS["cfgRelease"], settings))

    insert_before("/* End XCConfigurationList section */",
        "\t\t%s /* Build configuration list for PBXNativeTarget \"%s\" */ = {\n"
        "\t\t\tisa = XCConfigurationList;\n"
        "\t\t\tbuildConfigurations = (\n"
        "\t\t\t\t%s /* Debug */,\n"
        "\t\t\t\t%s /* Release */,\n"
        "\t\t\t);\n"
        "\t\t\tdefaultConfigurationIsVisible = 0;\n"
        "\t\t\tdefaultConfigurationName = Release;\n"
        "\t\t};\n"
        % (IDS["cfgList"], TARGET_NAME, IDS["cfgDebug"], IDS["cfgRelease"]))

    open(PROJ, "w", encoding="utf-8", newline="").write(state["text"])
    print("")
    print("  已写入 %s" % os.path.relpath(PROJ, ROOT))
    print("  新增 12 个对象，target 名 %s" % TARGET_NAME)
    print("")
    print("  ⚠ 接着必须跑 tools/validate-pbxproj.py 验证引用完整性")
    return 0


if __name__ == "__main__":
    sys.exit(main())
