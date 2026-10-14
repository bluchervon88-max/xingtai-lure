// 找出解码时未能精确消费完自身字符的几何。
// 这种"差几个字符"的问题会让地图画出错误的形状，必须定位到具体哪一条。
//
// 用法: node tools/probe-bad-geom.js
const fs = require('fs');
const path = require('path');
const { decodeInt, makeCursor } = require('./basemap-codec');

const root = path.join(__dirname, '..');

for (const f of ['basemap_water.txt', 'basemap_river.txt', 'basemap_boundary.txt']) {
  const lines = fs.readFileSync(path.join(root, 'assets', f), 'utf8').split('\n');
  let bad = 0;
  for (let i = 2; i < lines.length; i++) {
    const line = lines[i];
    if (!line) continue;
    const bar = line.indexOf('|');
    const body = bar < 0 ? line : line.substring(0, bar);
    const meta = bar < 0 ? '' : line.substring(bar + 1);
    const c = makeCursor(body);
    const n = decodeInt(body, c);
    if (!(n > 0 && n < 20000)) {
      console.log(`  ${f} 第${i + 1}行 点数异常 n=${n}  元数据="${meta}"`);
      bad++;
      continue;
    }
    for (let k = 0; k < n * 2; k++) decodeInt(body, c);
    if (c.i !== body.length) {
      console.log(`  ${f} 第${i + 1}行 未消费完: 读了 ${c.i}/${body.length} 字符, n=${n}, 元数据="${meta}"`);
      console.log(`     开头 60: "${body.slice(0, 60)}"`);
      console.log(`     结尾 30: "${body.slice(-30)}"`);
      bad++;
    }
  }
  console.log(`  ${f}: 异常 ${bad} 条`);
}
