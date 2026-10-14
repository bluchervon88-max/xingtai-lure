// 检测相邻卡片的边界是否重叠，以及 FAB 是否与标签栏相撞。
// 这类问题在截图上看不明显，但会让界面"看起来乱"。
//
// 用法: node tools/ui-overlap.js <ui.xml>
const fs = require('fs');

const file = process.argv[2];
if (!file) { console.error('用法: node ui-overlap.js <ui.xml>'); process.exit(2); }
const xml = fs.readFileSync(file, 'utf8');

const re = /<node[^>]*>/g;
const nodes = [];
let m;
while ((m = re.exec(xml)) !== null) {
  const s = m[0];
  const bm = /bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"/.exec(s);
  if (!bm) continue;
  const tm = /text="([^"]*)"/.exec(s);
  const cm = /class="([^"]*)"/.exec(s);
  nodes.push({
    x1: +bm[1], y1: +bm[2], x2: +bm[3], y2: +bm[4],
    cls: cm ? cm[1].replace(/.*\./, '') : '?',
    text: tm ? tm[1] : '',
    clickable: /clickable="true"/.test(s),
  });
}

const dp = (px) => (px / 3.75).toFixed(0) + 'dp';

// ---- 找出所有可点的顶层卡片（含垂钓园/水库等名称的容器）
const cards = nodes
  .filter((n) => n.clickable && n.y2 - n.y1 > 60 && n.x2 - n.x1 > 900)
  .sort((a, b) => a.y1 - b.y1);

console.log(`找到 ${cards.length} 个可点卡片容器\n`);
console.log('  序号   y范围           高度     左色条?  是否与上一个重叠');
console.log('  ' + '-'.repeat(70));
let overlaps = 0;
for (let i = 0; i < cards.length; i++) {
  const n = cards[i];
  const prev = cards[i - 1];
  let note = '';
  if (prev && n.y1 < prev.y2) {
    note = `⚠ 与上一个重叠 ${prev.y2 - n.y1}px`;
    overlaps++;
  }
  console.log('  ' + String(i + 1).padEnd(6)
    + String(n.y1 + '-' + n.y2).padEnd(15)
    + String(n.y2 - n.y1 + 'px').padEnd(9)
    + String(dp(n.y2 - n.y1)).padEnd(8)
    + note);
}
console.log(`\n重叠的卡片: ${overlaps} 处 ${overlaps === 0 ? '✅' : '⚠'}`);

// ---- FAB 与标签栏是否相撞
const fab = nodes.find((n) => n.clickable && n.text === '＋');
const tabItems = nodes.filter((n) => n.clickable && n.y1 > 2800 && n.y2 - n.y1 > 100);
if (fab) {
  console.log(`\n悬浮按钮 ＋: y=${fab.y1}-${fab.y2} (${fab.y2 - fab.y1}px = ${dp(fab.y2 - fab.y1)})`);
  const tabTop = tabItems.length ? Math.min(...tabItems.map((n) => n.y1)) : null;
  if (tabTop !== null) {
    console.log(`标签项顶边  : y=${tabTop}`);
    if (fab.y2 > tabTop) {
      console.log(`  ⚠ 悬浮按钮与标签栏重叠 ${fab.y2 - tabTop}px —— 会挡住标签`);
    } else {
      console.log(`  ✅ 悬浮按钮在标签栏上方，间距 ${tabTop - fab.y2}px (${dp(tabTop - fab.y2)})`);
    }
  }
} else {
  console.log('\n未找到悬浮按钮');
}

// ---- 列表首行内容（确认页头是否渲染）
const head = nodes.filter((n) => n.y1 < 1200 && n.text).slice(0, 14);
console.log('\n页头与前几张卡片可见文字:');
for (const n of head) console.log(`  y=${n.y1}  ${n.text}`);
