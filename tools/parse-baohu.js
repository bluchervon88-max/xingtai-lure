// 从「爆护工具箱」钓点列表页抽取结构化数据。
// 页面结构：<article class="fishing-place-card" data-lat data-lng> 内含
//   h3.place-name / p.place-address / p.place-distance / a[href=/fishing/spot/UUID] / img
//
// 用法: node tools/parse-baohu.js <列表页html> [输出json]
const fs = require('fs');

const src = process.argv[2];
const outJson = process.argv[3];

if (!src) {
  console.error('用法: node parse-baohu.js <列表页html> [输出json]');
  process.exit(2);
}

const html = fs.readFileSync(src, 'utf8');
const items = [];

const cardRe = /<article[^>]*class="[^"]*fishing-place-card[^"]*"[^>]*data-lat="([\d.-]+)"[^>]*data-lng="([\d.-]+)"[^>]*>([\s\S]*?)<\/article>/g;

let m;
while ((m = cardRe.exec(html)) !== null) {
  const lat = parseFloat(m[1]);
  const lng = parseFloat(m[2]);
  const block = m[3];

  const name = pick(block, /<h3[^>]*class="[^"]*place-name[^"]*"[^>]*>([\s\S]*?)<\/h3>/);
  const address = pick(block, /<p[^>]*class="[^"]*place-address[^"]*"[^>]*>([\s\S]*?)<\/p>/);
  const distText = pick(block, /<p[^>]*class="[^"]*place-distance[^"]*"[^>]*>([\s\S]*?)<\/p>/);
  const link = (/href="(\/fishing\/spot\/[^"]+)"/.exec(block) || [])[1] || '';
  const img = (/<img[^>]*src="([^"]+)"/.exec(block) || [])[1] || '';

  const distMatch = /([\d.]+)\s*公里/.exec(distText);

  if (name) {
    items.push({
      name,
      lat,
      lng,
      address: address.replace(/^📍\s*/, '').trim(),
      distanceKm: distMatch ? parseFloat(distMatch[1]) : null,
      url: link ? 'https://www.baohugongjuxiang.cn' + link : '',
      image: img,
    });
  }
}

function pick(block, re) {
  const r = re.exec(block);
  if (!r) return '';
  return String(r[1])
    .replace(/<[^>]+>/g, '')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/\s+/g, ' ')
    .trim();
}

console.log('抽取到 ' + items.length + ' 条\n');
console.log('名称'.padEnd(24, ' ') + '坐标'.padEnd(22, ' ') + '距离'.padEnd(9, ' ') + '地址');
console.log('-'.repeat(100));
for (const it of items) {
  console.log(
    it.name.padEnd(24, ' ') +
    (it.lat.toFixed(4) + ',' + it.lng.toFixed(4)).padEnd(22, ' ') +
    (it.distanceKm != null ? it.distanceKm + 'km' : '-').padEnd(9, ' ') +
    (it.address || '(无)')
  );
}

// 邢台市行政范围（含全部县市区，留少量余量）
const XT = { latMin: 36.72, latMax: 37.60, lonMin: 113.70, lonMax: 115.95 };
const inside = items.filter((i) => i.lat >= XT.latMin && i.lat <= XT.latMax
  && i.lng >= XT.lonMin && i.lng <= XT.lonMax);

console.log('\n坐标落在邢台市范围内的：' + inside.length + ' / ' + items.length);
const outside = items.filter((i) => !inside.includes(i));
if (outside.length) {
  console.log('范围外（需要判断是否属于邢台）：');
  for (const i of outside) {
    console.log('  ' + i.name + '  ' + i.lat + ',' + i.lng + '  ' + (i.address || ''));
  }
}

console.log('\n带图片的：' + items.filter((i) => i.image).length + ' 条');

if (outJson) {
  fs.writeFileSync(outJson, JSON.stringify(items, null, 2), 'utf8');
  console.log('已写入 ' + outJson);
}
