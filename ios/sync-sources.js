#!/usr/bin/env node
/**
 * 把仓库里"单一来源"的 Swift 文件同步进 iOS 工程目录。
 *
 * ★ 为什么需要这一步
 *
 *   代码有两个存放位置，各有各的道理：
 *
 *     ios/Sources/XtLureCore/     ← 逻辑层的**单一来源**。
 *                                   放这里是因为它不依赖 UIKit，
 *                                   能在 Windows 上用 swiftc 直接编译和跑测试
 *                                   （见 ios/swift-test.ps1）。Windows 上没有
 *                                   Xcode 工程的概念，靠 swiftc 手动编。
 *
 *     ios/XtLure/Core/            ← Xcode 工程**按这个路径找文件**。
 *                                   pbxproj 里的 path 是相对路径，
 *                                   xcodebuild 不认识 XtLureCore 那种目录。
 *
 *   如果两边各留一份手改，早晚不同步 —— 改了 Windows 侧跑测试是绿的，
 *   但 CI 编译的是另一份旧代码，出来的 App 是旧的。这类"看起来都对"的
 *   问题最难查。所以：**XtLureCore 是唯一可手改的地方，XtLure/Core 是生成物。**
 *
 * ★ 这条规则由 CI 强制
 *   GitHub Actions 在编译前会先跑这个脚本，然后检查 git 是否有差异 ——
 *   有差异说明有人直接改了生成物，或者忘了同步，构建直接失败。
 *
 * 用法:
 *   node ios/sync-sources.js         同步
 *   node ios/sync-sources.js --check 只检查是否需要同步（CI 用，有差异则退出码 1）
 */
const fs = require('fs');
const path = require('path');

const IOS = __dirname;
const SRC_DIR = path.join(IOS, 'Sources', 'XtLureCore');
const DST_DIR = path.join(IOS, 'XtLure', 'Core');

/** 需要同步的文件（顺序无关，Xcode 靠 pbxproj 里的引用决定编译顺序） */
const FILES = [
  'CoordinateTransform.swift',
  'SunMoon.swift',
  'GeoViewport.swift',
  'FishingSpot.swift',
  'CatchRecord.swift',
  'Exporter.swift',
  'Basemap.swift',
];

const BANNER_PREFIX = '// ★ 本文件是生成物，请勿直接修改。';

/**
 * 统一换行为 LF。
 *
 * ★ 为什么必须显式做这件事
 *
 *   CI 里有一个检查：跑完同步后 `git status` 必须没有差异 ——
 *   用来防止"有人改了生成物"或"忘了跑同步"。
 *
 *   但如果生成物在 Windows 上是 CRLF、在 macOS/Linux 上是 LF，
 *   这个检查就会**因为换行符而失败**，而且报错信息只显示"文件有改动"，
 *   看起来像是内容不一致，极难排查。
 *
 *   所以这里无条件写成 LF，让生成物与平台的检出配置无关：
 *   `.gitattributes` 也规定了仓库内一律 LF，两边对齐。
 */
function toLF(text) {
  return text.replace(/\r\n/g, '\n').replace(/\r/g, '\n');
}

function bannerFor(name) {
  return [
    '// ★ 本文件是生成物，请勿直接修改。',
    '//',
    '// 单一来源: ios/Sources/XtLureCore/' + name,
    '// 同步方式: node ios/sync-sources.js',
    '//',
    '// 为什么要有这份副本：Xcode 工程按固定相对路径找文件，而逻辑层的单一来源',
    '// 放在 XtLureCore 是为了能在 Windows 上用 swiftc 直接编译测试。',
    '// 直接改这份副本会在下次同步时被覆盖 —— 请改单一来源。',
    '',
  ].join('\n');
}

const checkOnly = process.argv.includes('--check');

if (!fs.existsSync(SRC_DIR)) {
  console.error('找不到源目录: ' + SRC_DIR);
  process.exit(1);
}
fs.mkdirSync(DST_DIR, { recursive: true });

let missing = [];
let copied = 0;
let stale = [];

for (const name of FILES) {
  const from = path.join(SRC_DIR, name);
  const to = path.join(DST_DIR, name);

  if (!fs.existsSync(from)) {
    missing.push(name);
    continue;
  }

  const content = toLF(bannerFor(name) + fs.readFileSync(from, 'utf8'));
  const existing = fs.existsSync(to) ? fs.readFileSync(to, 'utf8') : null;

  if (existing === content) {
    continue;                       // 已是最新
  }

  if (checkOnly) {
    stale.push(name);
  } else {
    fs.writeFileSync(to, content, 'utf8');
    copied++;
  }
}

// 反向检查：目标目录里有没有多余的文件（可能对应已删除的源文件，会让 Xcode 编译到孤儿代码）
const extra = fs.existsSync(DST_DIR)
  ? fs.readdirSync(DST_DIR).filter((f) => f.endsWith('.swift') && !FILES.includes(f))
  : [];

/**
 * 检查 `ios/swift-test.ps1` 的编译列表与 FILES 是否一致。
 *
 * ★ 为什么需要这个检查
 *
 *   同一个"有哪些逻辑层文件"的事实被写在**两个地方**：
 *     · 本文件的 FILES        —— 决定同步哪些文件到 Xcode 工程
 *     · swift-test.ps1 的 $sources —— 决定契约测试编译哪些文件
 *
 *   两份清单手工维护，一定会漂移。实测踩过：新增 Basemap.swift 后
 *   只更新了 FILES，忘了 swift-test.ps1，
 *   于是契约测试报 `cannot find 'Basemap' in scope` ——
 *   错误信息指向测试文件，而真正的原因是清单漏了一项。
 *
 *   漂移的两种后果都不好：
 *     · 测试清单少了文件 -> 该模块**根本没被测到**，但测试仍然是绿的（更隐蔽）
 *     · 测试清单多了文件 -> 编译报错（容易被发现，反倒安全）
 *
 *   这里在同步时顺手比对一下，两种漂移都能提前发现。
 */
function checkTestScriptList() {
    const p = path.join(IOS, 'swift-test.ps1');
    if (!fs.existsSync(p)) {
        return { ok: false, note: '找不到 ios/swift-test.ps1' };
    }
    const text = fs.readFileSync(p, 'utf8');
    const missing = [];
    const wrongPath = [];
    for (const name of FILES) {
        // 该文件必须在 $sources 里以 XtLureCore 的路径出现
        const want = 'Sources\\XtLureCore\\' + name;
        if (!text.includes(want)) {
            missing.push(name);
        }
    }
    // 反向：$sources 里有没有引用已经不存在的 XtLureCore 文件
    const listed = [...text.matchAll(/Sources\\XtLureCore\\([\w.]+\.swift)/g)]
        .map((m) => m[1]);
    for (const l of listed) {
        if (!FILES.includes(l)) {
            wrongPath.push(l);
        }
    }
    return { ok: missing.length === 0 && wrongPath.length === 0,
             missing, wrongPath };
}

console.log('');
if (missing.length) {
  console.log('  缺少源文件: ' + missing.join(', '));
  console.log('  （这些文件应该由逻辑层移植产出，见 ios/Sources/XtLureCore/）');
}
if (extra.length) {
  console.log('  目标目录有多余文件: ' + extra.join(', '));
  console.log('  （源里已没有，但副本还在 —— 会被 Xcode 一起编译，需要删除）');
}

// swift-test.ps1 的清单一致性
const ts = checkTestScriptList();
if (ts.ok) {
  console.log('  swift-test.ps1 的编译清单与同步清单一致 ✅');
} else {
  console.log('');
  console.log('  ⚠ ios/swift-test.ps1 的编译清单与同步清单不一致：');
  if (ts.missing && ts.missing.length) {
    console.log('     少了: ' + ts.missing.join(', '));
    console.log('     -> 这些模块**根本没被契约测试覆盖**，但测试仍然是绿的');
  }
  if (ts.wrongPath && ts.wrongPath.length) {
    console.log('     多了: ' + ts.wrongPath.join(', '));
    console.log('     -> 这些文件已不在 XtLureCore 里，编译会报找不到');
  }
  if (ts.note) {
    console.log('     ' + ts.note);
  }
}

const testListOk = ts.ok;

if (checkOnly) {
  if (stale.length || missing.length || extra.length || !testListOk) {
    console.log('  需要同步的文件: ' + (stale.join(', ') || '无'));
    console.log('  → 运行 node ios/sync-sources.js 后提交');
    process.exit(1);
  }
  console.log('  iOS 源码已同步，无差异');
  process.exit(0);
}

console.log('  同步完成: ' + copied + ' 个文件更新，' + (FILES.length - copied - missing.length) + ' 个已是最新');
console.log('  源目录: ' + path.relative(path.join(IOS, '..'), SRC_DIR));
console.log('  目标:   ' + path.relative(path.join(IOS, '..'), DST_DIR));
process.exit(missing.length || extra.length || !testListOk ? 1 : 0);
