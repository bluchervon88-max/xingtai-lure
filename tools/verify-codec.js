// 底图编码的往返校验。
//
// 编码是自制格式，一旦编解码不一致，地图会画出乱七八糟的形状且很难看出原因，
// 所以这里对真实生成的文件做一次完整解码，确认每个几何都能精确消费完自己的字节。
//
// 用法: node tools/verify-codec.js
const fs = require('fs');
const path = require('path');
const { encodeInt, decodeInt, makeCursor } = require('./basemap-codec');

const root = path.join(__dirname, '..');

// ---- 1) 整数编解码往返
let bad = 0;
for (const v of [0, 1, -1, 31, 32, -32, 255, 1000, -1000, 123456, -123456, 9999999]) {
  const s = encodeInt(v);
  const c = makeCursor(s);
  const back = decodeInt(s, c);
  const consumed = c.i === s.length;
  if (back !== v || !consumed) {
    console.log(`  ✗ ${v} -> "${s}" -> ${back} (消费 ${c.i}/${s.length})`);
    bad++;
  }
}
console.log(bad === 0
  ? '  ✅ 整数往返全部正确（含边界值 0 / ±1 / 31 / 32）'
  : `  ❌ ${bad} 个失败`);

// ---- 2) 真实文件：几何是否都能精确解码完
//
// 行格式： `类型|顶点流|local|标注y|标注x|名称`
// 表头：  第一行文件标识，第二行图层名
// 之前写死 skip=2 把边界文件的 'boundary' 表头当成数据解码，报了假失败，
// 所以这里按内容识别表头，不依赖行号。
const files = ['basemap_water.txt', 'basemap_river.txt', 'basemap_boundary.txt'];

let allOk = true;
for (const name of files) {
  const p = path.join(root, 'assets', name);
  const lines = fs.readFileSync(p, 'utf8').split('\n');
  let ok = 0, fail = 0, pts = 0, minLat = 90, maxLat = -90, minLon = 180, maxLon = -180;
  const badLines = [];
  const typeCounts = {};

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (!line) continue;
    const f = line.split('|');
    // 表头或格式不符的行跳过（表头没有 '|' 或第一段不是单字母类型）
    if (f.length < 2 || f[0].length !== 1 || !'WBR'.includes(f[0])) {
      continue;
    }
    typeCounts[f[0]] = (typeCounts[f[0]] || 0) + 1;

    const body = f[1];
    const c = makeCursor(body);
    const n = decodeInt(body, c);
    if (!(n > 0 && n < 20000)) {
      fail++;
      badLines.push(i + 1);
      continue;
    }

    let px = 0, py = 0;
    for (let k = 0; k < n; k++) {
      px += decodeInt(body, c);
      py += decodeInt(body, c);
      const lon = px / 1e5, lat = py / 1e5;
      if (lat < minLat) minLat = lat;
      if (lat > maxLat) maxLat = lat;
      if (lon < minLon) minLon = lon;
      if (lon > maxLon) maxLon = lon;
    }
    if (c.i === body.length) ok++; else { fail++; badLines.push(i + 1); }
    pts += n;
  }

  const good = fail === 0 && ok > 0;
  allOk = allOk && good;
  console.log(`  ${good ? '✅' : '❌'} ${name}: 精确解码 ${ok} 个几何`
    + `, 失败 ${fail}, 顶点 ${pts}`
    + `  类型 ${JSON.stringify(typeCounts)}`
    + (badLines.length ? `  异常行: ${badLines.slice(0, 8).join(',')}` : ''));
  console.log(`      经纬范围 lat ${minLat.toFixed(3)}~${maxLat.toFixed(3)}`
    + `  lon ${minLon.toFixed(3)}~${maxLon.toFixed(3)}`);
  // 邢台市范围约 lat 36.75~37.95, lon 113.75~115.85，越界说明解码错位
  const inRange = minLat > 35 && maxLat < 39 && minLon > 112 && maxLon < 117;
  console.log(`      范围合理性: ${inRange ? '✅ 在邢台及周边' : '❌ 越界，可能解码错位'}`);
  if (!inRange) allOk = false;
}

console.log('\n' + (allOk ? '编码校验全部通过' : '编码校验存在问题'));
process.exit(allOk ? 0 : 1);
