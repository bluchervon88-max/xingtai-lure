#!/usr/bin/env node
/**
 * 检查 LinearLayout.LayoutParams(0, 0, weight) 这类写法。
 *
 * ★ 这个 bug 的真实案例
 *   记录作钓的表单里，所有输入框都不可见：界面上只见左边的标签和右边的
 *   「选择」按钮，中间是空的。诊断输出给出了铁证：
 *
 *     LinearLayout 1186x84 子=2
 *       - TextView 255x54  text='目标鱼'  w=-2 weight=0.0     ← 标签正常
 *       - EditText 931x0   text=''       w=0  weight=1.0     ← 宽 931 高 0
 *
 *   根因：横向 LinearLayout 里 weight 分配的是**主轴（宽度）**，
 *   传进去的 height=0 不会因为 weight 而被撑开 —— 高度就永远是 0，
 *   控件因此完全不可见（也不出现在 uiautomator dump 里，所以很难查）。
 *
 *   正确写法是高度传 WRAP_CONTENT：
 *     new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
 *
 * ★ 但 height=0 有两种情况：
 *   1. 内容是**弹性占位**（new View(...)，只用来把后面的元素推到右边）→ 无害
 *   2. 内容是**真实控件**（TextView / EditText / 任何有内容的子类）→ 会被压成不可见
 *   所以这个脚本不能一刀切，要逐条判断父容器方向与控件类型。
 *
 * 用法: node tools/check-layoutparams.js
 * 退出码: 0 无真实问题 / 1 有真实问题
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const SRC = path.join(ROOT, 'src');

const RE = /LinearLayout\.LayoutParams\(\s*0\s*,\s*0\s*,\s*([\d.]+)f?\s*\)/g;

let realBugs = 0;
let spacers = 0;

/** 收集 java 文件 */
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.java')) out.push(p);
  }
  return out;
}

/**
 * 判断某个 View 变量是不是"弹性占位"。
 *
 * 占位的特征：变量声明处直接 new View(...)，且没有设置文本等可见内容。
 * 例如： new View(c)  /  new View(this)  /  spacer()
 */
function isSpacerVar(line, content) {
  // addView(new View(...), ...) —— 直接内联创建
  if (/addView\(\s*new\s+View\s*\(/.test(line)) return true;
  // setLayoutParams(new LayoutParams(0,0,1f)) 且变量名含 spacer / gap / fill
  if (/setLayoutParams\(/.test(line) && /\b(spacer|gap|fill|strut)\b/i.test(line)) return true;

  // 变量式：找到该变量最近的声明，看是不是 new View(...)
  const m = line.match(/addView\(\s*(\w+)\s*,/);
  if (m) {
    const v = m[1];
    if (/^(spacer|gap|fill|strut)$/i.test(v)) return true;
    const decl = new RegExp('\\b(?:View|android\\.view\\.View)\\s+' + v + '\\s*=\\s*([^;]+);');
    const dm = content.match(decl);
    if (dm) {
      const rhs = dm[1];
      if (/new\s+View\s*\(/.test(rhs)) return true;
      if (/=\s*\w+\(\)/.test(rhs) && /spacer/i.test(rhs)) return true;
    }
  }
  return false;
}

/** 判断该行所在语句的父容器方向：横向 -> 会被压扁 */
function parentOrientation(content, index) {
  // 向前找最近的变量声明，试图判断 addView 的目标是 row 还是 column
  const before = content.slice(Math.max(0, index - 4000), index);
  const m = [...before.matchAll(/addView\(\s*(\w+)\s*,/g)];
  return m.length ? m[m.length - 1][1] : null;
}

for (const file of walk(SRC)) {
  const content = fs.readFileSync(file, 'utf8');
  const name = path.basename(file);
  const lines = content.split('\n');

  // Ui.java 里的 weightedSpacer() 是**刻意**提供 0 高度的弹性占位，
  // 它就是这个检查的"白名单实现"，跳过它自身。
  const isUiHelper = name === 'Ui.java';

  RE.lastIndex = 0;
  let m;
  while ((m = RE.exec(content)) !== null) {
    const lineNo = content.slice(0, m.index).split('\n').length;
    const line = lines[lineNo - 1] || '';

    // 注释行不算（文档里会引用这个反面写法）
    const trimmed = line.trim();
    if (trimmed.startsWith('*') || trimmed.startsWith('//') || trimmed.startsWith('/*')) {
      continue;
    }
    if (isUiHelper && trimmed.startsWith('return new LinearLayout.LayoutParams')) {
      continue;
    }

    const spacer = isSpacerVar(line, content);
    if (spacer) {
      spacers++;
      continue;
    }
    realBugs++;
    console.log('  [问题] ' + name + ':' + lineNo);
    console.log('         ' + line.trim());
  }
}

console.log('');
console.log('  LinearLayout.LayoutParams(0, 0, weight) 用法统计：');
console.log('    弹性占位（无害）  ' + spacers + ' 处');
console.log('    真实控件（会不可见） ' + realBugs + ' 处');

// ---------------------------------------------------------------- 反向检查
//
// ★ 另一半的坑：弹性占位被误改成 WRAP_CONTENT 高度。
//   弹性占位（new View() 只用来把后面的元素推到右边）的高度**必须**为 0。
//   一旦给它 WRAP_CONTENT，它所在的那一行会按内容撑高 ——
//   实测过：MainActivity.spacer() 被误改后，顶栏吃掉整屏 3025px，
//   内容区被压成 0，真机上表现为"整个屏幕只剩标题和一个悬浮按钮"。
//
//   所以两个方向都要查：内容控件不能用 0 高度，占位不能用非 0 高度。
const spacerMisuse = [];
for (const file of walk(SRC)) {
  const content = fs.readFileSync(file, 'utf8');
  const name = path.basename(file);
  const lines = content.split('\n');

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];

    // 形式 a：内联 new View(...) 却用了 Ui.weighted()（应为 weightedSpacer）
    if (/Ui\.weighted\(/.test(line)) {
      const up = lines.slice(Math.max(0, i - 3), i + 1).join(' ');
      if (/new\s+View\s*\(/.test(up)) {
        spacerMisuse.push(name + ':' + (i + 1) + '  ' + line.trim());
      }
      continue;
    }

    // 形式 b：setLayoutParams(new LayoutParams(0,0,1f)) —— 只有在
    // **所在方法明确是"造占位"**时才算问题。判断依据是方法名或变量名里
    // 有 spacer/fill/gap 语义；否则这一行本身就是正确写法（占位就该 0 高度），
    // 之前误报 MainActivity.spacer() 就是因为它其实是正确的。
    const mm = line.match(/(\w+)\.setLayoutParams\(new LinearLayout\.LayoutParams\(0, 0,/);
    if (mm) {
      const v = mm[1];
      if (/\b(spacer|gap|fill|strut)\b/i.test(v)) {
        spacerMisuse.push(name + ':' + (i + 1) + '  ' + line.trim());
      }
    }
  }
}

console.log('');
console.log('  弹性占位误用非 0 高度：' + spacerMisuse.length + ' 处');
for (const s of spacerMisuse) {
  console.log('    [问题] ' + s);
}

console.log('');
if (realBugs > 0 || spacerMisuse.length > 0) {
  if (realBugs > 0) {
    console.log('  内容控件的 height 应改为 WRAP_CONTENT（用 Ui.weighted(n)），');
    console.log('  否则在横向 LinearLayout 里高度会被压成 0，控件完全不可见。');
  }
  if (spacerMisuse.length > 0) {
    console.log('  弹性占位的 height 必须保持 0（用 Ui.weightedSpacer()），');
    console.log('  否则它会把所在行撑高，极端情况下整个内容区被挤没。');
  }
  process.exit(1);
}
console.log('  全部通过');
process.exit(0);
