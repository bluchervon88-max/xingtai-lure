// 从 Overpass API 取邢台市的水系轮廓。
// 邢台市范围：约 lon 113.75~115.85, lat 36.75~37.95
const https = require('https');
const fs = require('fs');

const BBOX = '36.75,113.75,37.95,115.85';
// 只取有面积的水体：水库、湖泊、河流（relation 才是完整水体，way 多为片段）
const QUERY = `
[out:json][timeout:180];
(
  way["natural"="water"](${BBOX});
  relation["natural"="water"](${BBOX});
  way["landuse"="reservoir"](${BBOX});
  relation["landuse"="reservoir"](${BBOX});
  way["waterway"="riverbank"](${BBOX});
);
out geom;
`;

function post(body) {
  return new Promise((resolve, reject) => {
    const req = https.request({
      hostname: 'overpass-api.de',
      path: '/api/interpreter',
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-www-form-urlencoded',
        'Content-Length': Buffer.byteLength(body),
        'User-Agent': 'xtlure-offline-map/1.0',
      },
    }, (res) => {
      let data = '';
      res.on('data', (c) => { data += c; });
      res.on('end', () => resolve({ status: res.statusCode, data }));
    });
    req.on('error', reject);
    req.write(body);
    req.end();
  });
}

(async () => {
  console.log('正在向 Overpass API 请求邢台水系数据…');
  console.log('查询范围: ' + BBOX);
  try {
    const r = await post('data=' + encodeURIComponent(QUERY));
    console.log('HTTP ' + r.status + '，收到 ' + r.data.length + ' 字节');
    if (r.status !== 200) {
      console.log('返回内容前 500 字:');
      console.log(r.data.slice(0, 500));
      process.exit(1);
    }
    const json = JSON.parse(r.data);
    console.log('要素数: ' + (json.elements ? json.elements.length : 0));
    fs.writeFileSync(process.argv[2], r.data);
    console.log('已保存到 ' + process.argv[2]);
    if (json.elements) {
      const types = {};
      for (const e of json.elements) {
        const k = e.type + '/' + ((e.tags && (e.tags.natural || e.tags.landuse || e.tags.waterway)) || '?');
        types[k] = (types[k] || 0) + 1;
      }
      console.log('分类统计:');
      for (const k of Object.keys(types).sort()) console.log('  ' + k + ': ' + types[k]);
    }
  } catch (e) {
    console.log('请求失败: ' + e.message);
    process.exit(1);
  }
})();