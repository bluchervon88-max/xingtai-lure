/**
 * 极简静态服务器 + 打卡记录。
 *
 * 用途有两个：
 *   1. 本地开发预览（localhost 属于安全上下文，GPS / Service Worker 都能用）
 *   2. iOS 存储实测：把打卡记录落到服务端，这样即使手机的本地存储被清空，
 *      我也能在服务端看到"它曾经来过"——本地日志被清就什么都查不到了。
 *
 * 用法:
 *   node web/serve.js                 默认 8080 端口，监听所有网卡
 *   node web/serve.js 3000            指定端口
 *
 * 手机访问（需与电脑同一 WiFi）:
 *   http://<电脑局域网IP>:8080/
 *   ★ 注意：非 localhost 的 HTTP 不是安全上下文，GPS 会被浏览器拒绝。
 *     开发时可以接受（用不到 GPS），要给朋友用时必须走 HTTPS。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const os = require('os');

const PORT = Number(process.argv[2] || 8080);
const ROOT = __dirname;
const LOG_DIR = path.join(ROOT, 'checkins');

fs.mkdirSync(LOG_DIR, { recursive: true });

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.txt': 'text/plain; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.svg': 'image/svg+xml',
  '.webmanifest': 'application/manifest+json; charset=utf-8',
  '.ico': 'image/x-icon',
};

function lanIPs() {
  const out = [];
  const nets = os.networkInterfaces();
  for (const name of Object.keys(nets)) {
    for (const n of nets[name] || []) {
      if (n.family === 'IPv4' && !n.internal) out.push(n.address);
    }
  }
  return out;
}

/** API：记录一次打卡。手机端把状态 POST 过来，落成服务端日志 */
function handleCheckin(req, res) {
  let body = '';
  req.on('data', (c) => {
    body += c;
    if (body.length > 1e6) req.destroy();   // 防滥用
  });
  req.on('end', () => {
    let payload;
    try {
      payload = JSON.parse(body || '{}');
    } catch (e) {
      res.writeHead(400, { 'Content-Type': 'application/json' });
      res.end('{"ok":false,"error":"bad json"}');
      return;
    }
    const ua = req.headers['user-agent'] || '';
    const line = JSON.stringify({
      serverTime: new Date().toISOString(),
      ip: req.socket.remoteAddress,
      userAgent: ua,
      ...payload,
    });
    const file = path.join(LOG_DIR, 'checkins.jsonl');
    fs.appendFileSync(file, line + '\n', 'utf8');
    console.log(`  [打卡] ${new Date().toLocaleTimeString()}  `
      + `phase=${payload.phase || '?'}  `
      + `markerExists=${payload.markerExists}  `
      + `persisted=${payload.persisted}  `
      + `days=${payload.daysSinceFirst}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end('{"ok":true}');
  });
}

/** API：读取全部打卡记录（方便直接在浏览器里看汇总） */
function handleCheckinList(req, res) {
  const file = path.join(LOG_DIR, 'checkins.jsonl');
  const lines = fs.existsSync(file)
    ? fs.readFileSync(file, 'utf8').split('\n').filter(Boolean)
    : [];
  res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify({ count: lines.length, records: lines.map((l) => JSON.parse(l)) }, null, 2));
}

const server = http.createServer((req, res) => {
  const url = decodeURIComponent((req.url || '/').split('?')[0]);

  if (url === '/api/checkin' && req.method === 'POST') {
    handleCheckin(req, res);
    return;
  }
  if (url === '/api/checkins') {
    handleCheckinList(req, res);
    return;
  }

  let filePath = path.join(ROOT, url === '/' ? 'index.html' : url);
  // 防目录穿越
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403);
    res.end('forbidden');
    return;
  }
  // 目录请求 -> index.html
  if (fs.existsSync(filePath) && fs.statSync(filePath).isDirectory()) {
    filePath = path.join(filePath, 'index.html');
  }
  if (!fs.existsSync(filePath)) {
    res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
    res.end('404 not found: ' + url);
    return;
  }

  const ext = path.extname(filePath).toLowerCase();
  res.writeHead(200, {
    'Content-Type': MIME[ext] || 'application/octet-stream',
    // 开发期禁用缓存，改完刷新就能看到
    'Cache-Control': 'no-store',
  });
  fs.createReadStream(filePath).pipe(res);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log('');
  console.log('  邢台路亚 · 网页版开发服务器');
  console.log(`  本机:   http://localhost:${PORT}/`);
  for (const ip of lanIPs()) {
    console.log(`  局域网: http://${ip}:${PORT}/   （手机同 WiFi 可访问）`);
  }
  console.log('');
  console.log('  打卡记录: ' + path.join(LOG_DIR, 'checkins.jsonl'));
  console.log('  汇总查看: http://localhost:' + PORT + '/api/checkins');
  console.log('');
  console.log('  提示：非 localhost 的 HTTP 不是安全上下文，GPS 会被浏览器拒绝。');
  console.log('        开发期不影响；给朋友用必须走 HTTPS。');
  console.log('');
});
