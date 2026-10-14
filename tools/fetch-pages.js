// 批量抓取钓点信息网页并保存到本地，供后续分析。
// 用 Node 内置 fetch，避免逐页手工试。
//
// 用法: node tools/fetch-pages.js <输出目录> <url1> [url2] ...
const fs = require('fs');
const path = require('path');

const outDir = process.argv[2];
const urls = process.argv.slice(3);

if (!outDir || urls.length === 0) {
  console.error('用法: node fetch-pages.js <输出目录> <url...>');
  process.exit(2);
}
fs.mkdirSync(outDir, { recursive: true });

const UA = 'Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) ' +
  'Chrome/120.0.0.0 Mobile Safari/537.36';

function safeName(url, i) {
  let base = url.replace(/^https?:\/\//, '').replace(/[^a-zA-Z0-9._-]/g, '_');
  if (base.length > 90) base = base.slice(0, 90);
  return String(i).padStart(2, '0') + '_' + base + '.html';
}

// 去掉标签，抽出可见文本，便于 grep 关键信息
function toText(html) {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, ' ')
    .replace(/<style[\s\S]*?<\/style>/gi, ' ')
    .replace(/<!--[\s\S]*?-->/g, ' ')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(p|div|li|tr|h[1-6]|td)>/gi, '\n')
    .replace(/<[^>]+>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#(\d+);/g, (m, d) => String.fromCharCode(Number(d)))
    .replace(/[ \t\u00a0]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

(async () => {
  let ok = 0, bad = 0;
  for (let i = 0; i < urls.length; i++) {
    const url = urls[i];
    const file = path.join(outDir, safeName(url, i));
    try {
      const ctrl = new AbortController();
      const timer = setTimeout(() => ctrl.abort(), 25000);
      const res = await fetch(url, {
        headers: {
          'User-Agent': UA,
          'Accept': 'text/html,application/xhtml+xml',
          'Accept-Language': 'zh-CN,zh;q=0.9',
        },
        signal: ctrl.signal,
        redirect: 'follow',
      });
      clearTimeout(timer);
      const body = await res.text();
      const text = toText(body);
      fs.writeFileSync(file.replace(/\.html$/, '.txt'), text, 'utf8');
      fs.writeFileSync(file, body, 'utf8');
      console.log('OK   ' + res.status + '  ' + text.length + ' chars  ' + url);
      ok++;
    } catch (e) {
      console.log('FAIL      ' + (e.message || e) + '  ' + url);
      bad++;
    }
  }
  console.log('\n成功 ' + ok + ' / 失败 ' + bad);
})();
