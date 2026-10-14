// 解析 uiautomator dump 出的界面 XML，列出节点树与边界。
// 目的：当界面上"少了一块"时，靠 bounds 就能判断是没创建、还是被挤到屏幕外。
//
// 用法: node tools/parse-ui-dump.js <ui.xml>
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('用法: node parse-ui-dump.js <ui.xml>');
  process.exit(2);
}
const xml = fs.readFileSync(file, 'utf8');

// uiautomator 的 XML 里节点是自闭合标签，用正则逐个抽属性最省事
const nodes = [];
const tagRe = /<(\w+)([^>]*?)\/?>/g;
let m;
while ((m = tagRe.exec(xml)) !== null) {
  const tag = m[1];
  const attrs = m[2];
  const get = (k) => {
    const r = new RegExp(k + '="([^"]*)"').exec(attrs);
    return r ? r[1] : '';
  };
  nodes.push({
    tag,
    text: get('text'),
    cls: get('class').split('.').pop(),
    bounds: get('bounds'),
    clickable: get('clickable') === 'true',
    id: get('resource-id'),
    pkg: get('package'),
  });
}

function parseBounds(b) {
  const r = /\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]/.exec(b || '');
  if (!r) return null;
  const [x1, y1, x2, y2] = [ +r[1], +r[2], +r[3], +r[4] ];
  return { x1, y1, x2, y2, w: x2 - x1, h: y2 - y1 };
}

// 屏幕尺寸：取根节点和所有节点里最大的右下角
let maxX = 0, maxY = 0;
for (const n of nodes) {
  const b = parseBounds(n.bounds);
  if (b) { maxX = Math.max(maxX, b.x2); maxY = Math.max(maxY, b.y2); }
}
console.log('屏幕尺寸（按节点推算）: ' + maxX + ' x ' + maxY + '\n');

console.log('节点总数: ' + nodes.length + '\n');
console.log('缩进'.padEnd(4) + '类型'.padEnd(20) + '边界'.padEnd(28) + '大小'.padEnd(14) + '文字/可点');
console.log('-'.repeat(110));

let depth = 0;
let prevY1 = null;
const suspicious = [];

for (let i = 0; i < nodes.length; i++) {
  const n = nodes[i];
  const b = parseBounds(n.bounds);
  if (!b) continue;

  // 推断层级：y1 变小说明回到了上层（粗略）
  if (prevY1 !== null && b.y1 < prevY1) depth = Math.max(0, depth - 1);
  prevY1 = b.y1;

  const pad = ' '.repeat(Math.min(depth, 12) * 2);
  const size = b.w + 'x' + b.h;
  const extra = (n.text ? '"' + n.text + '"' : '') + (n.clickable ? ' [可点]' : '');

  console.log(pad + n.tag.padEnd(18) + (b.x1 + ',' + b.y1 + ' → ' + b.x2 + ',' + b.y2).padEnd(28)
    + size.padEnd(14) + extra);

  // 标记可疑：高度为 0 或超出屏幕
  if (b.h === 0 || b.w === 0) suspicious.push(n.cls + ' 尺寸为 0  ' + n.bounds);
  if (b.y2 > maxY || b.x2 > maxX) suspicious.push(n.cls + ' 超出屏幕  ' + n.bounds);
}

console.log('\n=== 可疑节点 ===');
if (suspicious.length === 0) {
  console.log('  无');
} else {
  suspicious.forEach((s) => console.log('  ⚠ ' + s));
}

// 关键问题：屏幕下半部分有没有东西
const bottomHalf = nodes.filter((n) => {
  const b = parseBounds(n.bounds);
  return b && b.y1 > maxY * 0.5 && b.h > 0;
});
console.log('\n=== 屏幕下半部分（y > ' + Math.round(maxY * 0.5) + '）的节点数: '
  + bottomHalf.length + ' ===');
bottomHalf.slice(0, 12).forEach((n) => {
  console.log('  ' + n.cls + '  ' + n.bounds + '  ' + (n.text || ''));
});
