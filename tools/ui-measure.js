// 界面分区测量：把 uiautomator dump 按屏幕纵向分区统计，并检查可点区域的触摸尺寸。
//
// 用途：把"割裂""看不到""误触"这类主观描述，变成可判断的数字：
//   · 每个区域占多少像素/多少 dp
//   · 可点控件的触摸高度是否达到 48dp（Android 触摸目标规范）
//   · 底部区域有没有内容（标签栏是否真的渲染了）
//
// 用法: node tools/ui-measure.js <ui.xml> [densityDpi]
const fs = require('fs');

const file = process.argv[2];
const dpi = Number(process.argv[3] || 600);
if (!file) { console.error('用法: node ui-measure.js <ui.xml> [dpi]'); process.exit(2); }

const px2dp = (px) => (px * 160 / dpi);
const f = (n) => Math.round(n);

const xml = fs.readFileSync(file, 'utf8');
const nodes = [];
const re = /<node\b([^>]*?)\/?>/g;
let m;
while ((m = re.exec(xml)) !== null) {
  const a = m[1];
  const get = (k) => { const r = new RegExp('\\s' + k + '="([^"]*)"').exec(a); return r ? r[1] : ''; };
  const b = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(get('bounds'));
  if (!b) continue;
  nodes.push({
    cls: get('class').split('.').pop(),
    text: get('text'),
    id: get('resource-id'),
    clickable: get('clickable') === 'true',
    scrollable: get('scrollable') === 'true',
    x1: +b[1], y1: +b[2], x2: +b[3], y2: +b[4],
    w: +b[3] - +b[1], h: +b[4] - +b[2],
  });
}

let screenH = 0, screenW = 0;
for (const n of nodes) { screenH = Math.max(screenH, n.y2); screenW = Math.max(screenW, n.x2); }

console.log(`屏幕 ${screenW}x${screenH}px  =  ${f(px2dp(screenW))}x${f(px2dp(screenH))}dp  (${dpi}dpi)`);
console.log(`节点 ${nodes.length} 个\n`);

// ---- 纵向分区：每 10% 一段，看内容分布
console.log('纵向内容分布（每格 10% 屏高）:');
console.log('-'.repeat(72));
for (let i = 0; i < 10; i++) {
  const lo = screenH * i / 10, hi = screenH * (i + 1) / 10;
  const inBand = nodes.filter((n) => n.y1 >= lo && n.y1 < hi && n.h > 0 && n.text);
  const bar = '#'.repeat(Math.min(40, inBand.length));
  const sample = inBand.slice(0, 3).map((n) => n.text).join(' / ');
  console.log(
    `${String(i * 10).padStart(3)}%-${String(i * 10 + 10).padStart(3)}%  y=${String(f(lo)).padStart(4)}-${String(f(hi)).padStart(4)}`
    + `  ${String(inBand.length).padStart(2)}个文字节点 ${bar}`
    + (sample ? '  ' + sample.slice(0, 40) : '')
  );
}

// ---- 底部区域明细（标签栏/导航栏在这里）
const bottomStart = screenH * 0.88;
console.log(`\n底部区域明细（y > ${f(bottomStart)}px）:`);
console.log('-'.repeat(72));
const bottom = nodes.filter((n) => n.y2 > bottomStart).sort((a, b) => a.y1 - b.y1);
for (const n of bottom) {
  console.log(`  ${n.cls.padEnd(16)} [${n.x1},${n.y1}][${n.x2},${n.y2}]  ${n.w}x${n.h}px = ${f(px2dp(n.w))}x${f(px2dp(n.h))}dp`
    + (n.text ? `  「${n.text}」` : '') + (n.clickable ? ' [可点]' : ''));
}

// ---- 触摸目标审计：可点控件是否够大
console.log('\n可点控件触摸尺寸审计（Android 规范建议 >= 48dp）:');
console.log('-'.repeat(72));
const clickables = nodes.filter((n) => n.clickable && n.h > 0);
const tooSmall = [];
for (const n of clickables) {
  const hDp = px2dp(n.h), wDp = px2dp(n.w);
  if (hDp < 48) {
    tooSmall.push({ n, hDp, wDp });
  }
}
console.log(`  可点控件共 ${clickables.length} 个，其中高度不足 48dp 的 ${tooSmall.length} 个:`);
tooSmall.slice(0, 20).forEach(({ n, hDp, wDp }) => {
  console.log(`    ⚠ ${n.cls} ${f(wDp)}x${f(hDp)}dp  「${(n.text || '').slice(0, 22)}」  y=${n.y1}-${n.y2}`);
});

// ---- 关键结论
console.log('\n关键结论:');
const tabCandidates = nodes.filter((n) => n.clickable && n.y1 > screenH * 0.9);
console.log(`  屏幕最后 10% 内的可点控件: ${tabCandidates.length} 个`
  + (tabCandidates.length === 0 ? '   ← 标签栏可能没渲染或不可点！' : ''));
if (tabCandidates.length) {
  const heights = tabCandidates.map((n) => px2dp(n.h));
  console.log(`  其中最高 ${f(Math.max(...heights))}dp，最低 ${f(Math.min(...heights))}dp`);
}
const mapLike = nodes.filter((n) => n.cls.includes('Map') || (n.text || '').includes('钓点分布'));
console.log(`  分布图相关节点: ${mapLike.length} 个`);
