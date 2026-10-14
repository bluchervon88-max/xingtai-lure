// 取主要河道（线要素）。面状水体已经取过，这里补"河流中心线"，
// 让地图上能看出水系走向，而不是只有几个孤立的水库。
const https = require('https');
const fs = require('fs');

const BBOX = '36.75,113.75,37.95,115.85';
const QUERY = `
[out:json][timeout:180];
(
  way["waterway"="river"]["name"](${BBOX});
  way["waterway"="canal"]["name"](${BBOX});
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
  console.log('正在请求河道中心线…');
  try {
    const r = await post('data=' + encodeURIComponent(QUERY));
    console.log('HTTP ' + r.status + '，' + r.data.length + ' 字节');
    if (r.status !== 200) { console.log(r.data.slice(0, 400)); process.exit(1); }
    const json = JSON.parse(r.data);
    console.log('河道 ' + json.elements.length + ' 条');
    fs.writeFileSync(process.argv[2], r.data);
    const names = {};
    for (const e of json.elements) {
      const n = e.tags && e.tags.name;
      if (n) names[n] = (names[n] || 0) + 1;
    }
    console.log('主要河道:');
    for (const n of Object.keys(names).sort((a, b) => names[b] - names[a]).slice(0, 14)) {
      console.log('  ' + n + '  (' + names[n] + ' 段)');
    }
  } catch (e) {
    console.log('失败: ' + e.message);
    process.exit(1);
  }
})();