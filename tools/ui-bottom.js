// 检查界面底部区域（标签栏所在），列出节点尺寸与可点性。
// 用于验证"底部 UI 是否完整可见、触摸目标是否够大"。
//
// 用法: node tools/ui-bottom.js <ui.xml> [起始y]
const fs = require('fs');

const file = process.argv[2];
const fromY = Number(process.argv[3] || 2900);
if (!file) { console.error('用法: node ui-bottom.js <ui.xml> [起始y]'); process.exit(2); }

const xml = fs.readFileSync(file, 'utf8');
const re = /<node[^>]*>/g;
const nodes = [];
let m;
while ((m = re.exec(xml)) !== null) {
  const s = m[0];
  const bm = /bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/.exec(s);
  if (!bm) continue;
  const y1 = Number(bm[2]);
  const y2 = Number(bm[4]);
  if (y2 < fromY) continue;
  const tm = /text="([^"]*)"/.exec(s);
  const cm = /class="([^"]*)"/.exec(s);
  nodes.push({
    y1, y2, h: y2 - y1,
    cls: cm ? cm[1].replace(/.*\./, '') : '?',
    text: tm ? tm[1] : '',
    clickable: /clickable="true"/.test(s),
  });
}

// 屏幕高度取所有节点的最大 y2
let maxY = 0;
const re2 = /bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/g;
let m2;
while ((m2 = re2.exec(xml)) !== null) {
  maxY = Math.max(maxY, Number(m2[4]));
}

console.log(`屏幕坐标空间高 = ${maxY}px`);
console.log(`列出 y2 >= ${fromY} 的节点（按 y 排序）:\n`);
console.log('  y范围           高度       dp     类型            文字 / 可点');
console.log('  ' + '-'.repeat(84));
for (const n of nodes.sort((a, b) => a.y1 - b.y1)) {
  console.log('  ' + String(n.y1 + '-' + n.y2).padEnd(15)
    + String(n.h + 'px').padEnd(10)
    + String((n.h / 3.75).toFixed(0) + 'dp').padEnd(8)
    + n.cls.padEnd(16)
    + n.text
    + (n.clickable ? '  [可点]' : ''));
}

const clickables = nodes.filter((n) => n.clickable && n.h > 0);
console.log(`\n底部可点控件 ${clickables.length} 个`);
if (clickables.length) {
  const hs = clickables.map((n) => n.h / 3.75);
  console.log(`触摸高度 ${Math.min(...hs).toFixed(0)}~${Math.max(...hs).toFixed(0)}dp`
    + (Math.min(...hs) >= 44 ? '  ✅ 达标' : '  ⚠ 偏小'));
  const bottom = Math.max(...nodes.map((n) => n.y2));
  console.log(`底部最后像素 y=${bottom}，屏幕高 ${maxY} → `
    + (bottom >= maxY - 2 ? '✅ 贴住屏幕下沿（完整可见）' : `空出 ${maxY - bottom}px`));
}
