// 按文字查找节点并打印其位置；用于确认某个按钮/入口是否真的渲染出来了。
// 用法: node tools/ui-find.js <ui.xml> <关键词1> [关键词2 ...]
const fs = require('fs');

const file = process.argv[2];
const keys = process.argv.slice(3);
if (!file || !keys.length) {
  console.error('用法: node ui-find.js <ui.xml> <关键词...>');
  process.exit(2);
}

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
    text: (tm ? tm[1] : '').replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(+d)),
    clickable: /clickable="true"/.test(s),
  });
}

const dp = (px) => (px / 3.75).toFixed(0) + 'dp';

for (const k of keys) {
  const hits = nodes.filter((n) => n.text.includes(k));
  console.log(`\n关键词「${k}」命中 ${hits.length} 个节点:`);
  if (!hits.length) {
    console.log('  （没有找到 —— 该元素没有渲染出来）');
    continue;
  }
  for (const n of hits) {
    console.log(`  ${n.cls.padEnd(14)} y=${n.y1}-${n.y2} (h=${n.y2 - n.y1}px=${dp(n.y2 - n.y1)})`
      + ` x=${n.x1}-${n.x2}  「${n.text}」${n.clickable ? ' [可点]' : ''}`);
  }
}

// 顺带列出页头区域（y < 900）的所有节点，用于确认页头结构
console.log('\n页头区域 (y<900) 全部节点:');
for (const n of nodes.filter((n) => n.y1 < 900).sort((a, b) => a.y1 - b.y1)) {
  console.log(`  y=${String(n.y1).padStart(4)}  ${n.cls.padEnd(14)}`
    + ` h=${String(n.y2 - n.y1).padStart(4)}px`
    + `  ${n.text ? '「' + n.text + '」' : ''}${n.clickable ? ' [可点]' : ''}`);
}
