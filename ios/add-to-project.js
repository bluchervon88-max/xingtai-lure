/**
 * 往 Xcode 工程文件里登记新文件。
 *
 * ★ 为什么要有这个脚本
 *
 *   `project.pbxproj` 是手写的（开发机是 Windows，没有 Xcode 可以图形化添加文件）。
 *   加一个文件要同时改**四处**，漏掉任何一处都不会立刻报错：
 *
 *     PBXBuildFile        ── 构建项，把「文件」和「被哪个 build phase 使用」连起来
 *     PBXFileReference    ── 文件引用，记录路径
 *     PBXGroup.children   ── 分组树，决定 Xcode 里显示在哪
 *     PBXSourcesBuildPhase.files / PBXResourcesBuildPhase.files
 *                         ── 真正决定「编译它」还是「打包它」
 *
 *   漏了第四处最危险：文件在工程树里看得见、在磁盘上也存在，
 *   但 **Xcode 根本不编译它**。构建成功、产物正常，代码却没进去，
 *   直到运行时才崩 —— 而且崩的地方看起来与那个文件毫无关系。
 *
 *   这个脚本保证四处一次写全；`tools/validate-pbxproj.py` 负责反查验证。
 *
 * ★ 踩过的坑：不能只用字符串 indexOf 找分组
 *
 *   第一版拿 `CC...003 /* Core *\/ = {\n\t\t\tisa = PBXGroup;\n\t\t\tchildren = (\n`
 *   当锚点，结果插错了位置，生成出语法非法的 pbxproj。
 *
 *   原因：`CC...003 /* Core *\/` 这个串在文件里出现**两次** ——
 *   一次是**定义**（后面跟 `= {`），一次是**引用**（上层分组的 children 里）。
 *   引用那处后面恰好接着另一个分组的 `children = (`，
 *   于是「把整个锚点当子串找」在**错误的位置**也匹配上了。
 *
 *   现在改成按**行**定位：先找到内容等于 `CC...003 /* Core *\/ = {` 的那一行，
 *   再从它往下找第一个 `children = (`。行级比较不会再撞到引用。
 *
 * ★ ID 分配约定（与 project.pbxproj 开头的说明一致）
 *
 *   AA__ = PBXFileReference    BB__ = PBXBuildFile
 *   同一文件的两者用**相同后 20 位**，便于人工核对。
 *
 * 用法:
 *   node ios/add-to-project.js
 */
const fs = require('fs');
const path = require('path');

const PROJ = path.join(__dirname, 'XtLure.xcodeproj', 'project.pbxproj');

/**
 * 要登记的文件。
 *   id       后 20 位（AA__ 与 BB__ 共用）
 *   name     文件名
 *   group    分组显示名       groupId  分组 ID
 *   phase    'compile' 编译 | 'resource' 打包
 *   type     lastKnownFileType
 */
const ENTRIES = [
  { id: '000000000000000000000F', name: 'Basemap.swift',
    group: 'Core', groupId: 'CC0000000000000000000003',
    phase: 'compile', type: 'sourcecode.swift' },
  { id: '0000000000000000000010', name: 'basemap_water.txt',
    group: 'Resources', groupId: 'CC0000000000000000000004',
    phase: 'resource', type: 'text' },
  { id: '0000000000000000000011', name: 'basemap_river.txt',
    group: 'Resources', groupId: 'CC0000000000000000000004',
    phase: 'resource', type: 'text' },
  { id: '0000000000000000000012', name: 'basemap_boundary.txt',
    group: 'Resources', groupId: 'CC0000000000000000000004',
    phase: 'resource', type: 'text' },
  { id: '0000000000000000000013', name: 'SystemTimeZone.swift',
    group: 'Core', groupId: 'CC0000000000000000000003',
    phase: 'compile', type: 'sourcecode.swift' },
  { id: '0000000000000000000014', name: 'ViewTree.swift',
    group: 'Core', groupId: 'CC0000000000000000000003',
    phase: 'compile', type: 'sourcecode.swift' },
];

/** build phase 的 ID 与显示名 */
const PHASE = {
  compile: { id: 'DD0000000000000000000020', name: 'Sources', isa: 'PBXSourcesBuildPhase' },
  resource: { id: 'DD0000000000000000000022', name: 'Resources', isa: 'PBXResourcesBuildPhase' },
};

let lines = fs.readFileSync(PROJ, 'utf8').split('\n');
let changed = 0;

/** 找到内容等于 defLine 的那一行（trim 后比较），返回行号 */
function findDefLine(lines, defLine) {
  return lines.findIndex((l) => l.trim() === defLine);
}

/**
 * 在某个对象的子列表开头插入一行。
 *
 * @param defLine     该对象定义那一行的内容（trim 后精确匹配），例如 `CC...003 /* Core *\/ = {`
 * @param listOpen    子列表开始那一行的内容，例如 `children = (`
 * @param newLine     要插入的行（含缩进）
 */
function insertIntoList(defLine, listOpen, newLine) {
  const defIdx = findDefLine(lines, defLine);
  if (defIdx < 0) {
    throw new Error('找不到定义行: ' + defLine);
  }
  // 幂等：列表里已经有这一行就不重复插
  if (lines.some((l) => l.trim() === newLine.trim())) {
    return false;
  }
  // 从定义行往下找列表开始（限制几行，避免越界跑到别的对象里）
  for (let i = defIdx; i < Math.min(lines.length, defIdx + 8); i++) {
    if (lines[i].trim() === listOpen) {
      lines.splice(i + 1, 0, newLine);
      changed++;
      return true;
    }
  }
  throw new Error('在 ' + defLine + ' 之后找不到 ' + listOpen);
}

/** 在某个标记行之前插入一行 */
function insertBeforeMarker(marker, newLine) {
  const idx = findDefLine(lines, marker);
  if (idx < 0) {
    throw new Error('找不到标记: ' + marker);
  }
  if (lines.some((l) => l.trim() === newLine.trim())) {
    return false;
  }
  lines.splice(idx, 0, newLine);
  changed++;
  return true;
}

// ★ 自检 ID 长度：AA/BB 后面必须是 22 位十六进制，合计 24 位。
//   pbxproj 要求对象 ID 恰好 24 位，写短了 Xcode 可能不认 ——
//   实测过一次：写成 20 位（合计 22），文件能被 plist 解析器读出来，
//   但 validate-pbxproj.py 报了 'ID 不是 24 位十六进制'。
//   与其等着校验器抓，不如这里先拦住。
for (const e of ENTRIES) {
  if (e.id.length !== 22) {
    console.error('  [FAIL] ID 长度错误: ' + e.id + ' 是 ' + e.id.length + ' 位，应为 22 位');
    console.error('         前缀 AA/BB 再加 22 位才等于 pbxproj 要求的 24 位。');
    process.exit(1);
  }
}

const added = [];
const skipped = [];

for (const e of ENTRIES) {
  const fileRef = 'AA' + e.id;
  const buildFile = 'BB' + e.id;
  const p = PHASE[e.phase];
  let did = false;

  // 1) PBXBuildFile —— 插到 section 结束标记之前
  did = insertBeforeMarker('/* End PBXBuildFile section */',
    `\t\t${buildFile} /* ${e.name} in ${p.name} */ = {isa = PBXBuildFile; fileRef = ${fileRef} /* ${e.name} */; };`
  ) || did;

  // 2) PBXFileReference
  did = insertBeforeMarker('/* End PBXFileReference section */',
    `\t\t${fileRef} /* ${e.name} */ = {isa = PBXFileReference; lastKnownFileType = ${e.type}; path = ${e.name}; sourceTree = "<group>"; };`
  ) || did;

  // 3) 分组 children
  did = insertIntoList(
    `${e.groupId} /* ${e.group} */ = {`,
    'children = (',
    `\t\t\t\t${fileRef} /* ${e.name} */,`
  ) || did;

  // 4) build phase 的 files 列表
  did = insertIntoList(
    `${p.id} /* ${p.name} */ = {`,
    'files = (',
    `\t\t\t\t${buildFile} /* ${e.name} in ${p.name} */,`
  ) || did;

  (did ? added : skipped).push(e.name);
}

fs.writeFileSync(PROJ, lines.join('\n'), 'utf8');

console.log('');
if (added.length) {
  console.log('  已登记: ' + added.join(', '));
}
if (skipped.length) {
  console.log('  已在工程中（跳过）: ' + skipped.join(', '));
}
console.log('  共修改 ' + changed + ' 处');
console.log('');
console.log('  ⚠ 登记完必须跑 tools/validate-pbxproj.py 验证 ——');
console.log('    这个脚本只负责写，不负责证明写对了。');
