// 取邢台市各区县行政边界（OSM admin_level 6 = 县级）。
// 用途：让自制地图有行政参照，用户能看出"这个钓点在哪个县"。
const https = require('https');
const fs = require('fs');

const BBOX = '36.75,113.75,37.95,115.85';
const QUERY = `
[out:json][timeout:180];
(
  relation["boundary"="administrative"]["admin_level"="6"](${BBOX});
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
  console.log('正在请求邢台区县行政边界…');
  try {
    const r = await post('data=' + encodeURIComponent(QUERY));
    console.log('HTTP ' + r.status + '，' + r.data.length + ' 字节');
    if (r.status !== 200) { console.log(r.data.slice(0, 400)); process.exit(1); }
    const json = JSON.parse(r.data);
    console.log('边界要素 ' + json.elements.length + ' 个');
    fs.writeFileSync(process.argv[2], r.data);
    for (const e of json.elements) {
      const n = e.tags && (e.tags.name || e.tags['name:zh']);
      if (n) console.log('  ' + n + '  成员 ' + (e.members ? e.members.length : 0));
    }
  } catch (e) {
    console.log('失败: ' + e.message);
    process.exit(1);
  }
})();