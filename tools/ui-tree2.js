// 严格按 index 与路径还原 uiautomator dump 的层级。
// 上一个版本靠 y 坐标猜层级，不可靠 —— 这次直接解析 XML 结构。
//
// 用法: node tools/ui-tree2.js <ui.xml>
const fs = require('fs');

const file = process.argv[2];
if (!file) { console.error('用法: node ui-tree2.js <ui.xml>'); process.exit(2); }
const xml = fs.readFileSync(file, 'utf8');

// 手写一个极小的 XML 解析：只关心 node 元素、属性、开闭标签
const out = [];
let i = 0;
const stack = [];

function readAttrs(s, start) {
  // 从 start 起读到 '>' 或 '/>'，解析 key="value"
  const attrs = {};
  let j = start;
  let inQ = false;
  let buf = '';
  for (; j < s.length; j++) {
    const c = s[j];
    if (c === '"') { inQ = !inQ; buf += c; continue; }
    if (!inQ && (c === '>' )) break;
    if (!inQ && c === '/' && s[j + 1] === '>') break;
    buf += c;
  }
  const re = /([\w:-]+)="([^"]*)"/g;
  let m;
  while ((m = re.exec(buf)) !== null) attrs[m[1]] = m[2];
  return { attrs, end: j };
}

console.log('层级  类型              边界                       文字 / 可点');
console.log('-'.repeat(100));

while (i < xml.length) {
  const lt = xml.indexOf('<', i);
  if (lt < 0) break;
  if (xml.startsWith('<?', lt) || xml.startsWith('<!--', lt)) {
    i = xml.indexOf('>', lt) + 1;
    continue;
  }
  const closing = xml[lt + 1] === '/';
  let j = closing ? lt + 2 : lt + 1;
  let k = j;
  while (k < xml.length && /[\w:.-]/.test(xml[k])) k++;
  const tag = xml.slice(j, k);

  if (closing) {
    stack.pop();
    i = xml.indexOf('>', k) + 1;
    continue;
  }

  const { attrs, end } = readAttrs(xml, k);
  const selfClose = xml[end] === '/' && xml[end + 1] === '>';
  const depth = stack.length;

  if (tag === 'node') {
    const cls = (attrs['class'] || '').split('.').pop();
    const text = attrs['text'] || '';
    const bounds = attrs['bounds'] || '';
    const clickable = attrs['clickable'] === 'true';
    console.log(
      String(depth).padEnd(5)
      + cls.padEnd(16)
      + bounds.padEnd(26)
      + (text ? '「' + text + '」' : '')
      + (clickable ? ' [可点]' : '')
    );
  } else {
    console.log(String(depth).padEnd(5) + tag.padEnd(16) + '(容器)');
  }

  if (!selfClose) stack.push(tag);
  i = end + 1;
}

console.log('\n最大深度: ' + stack.length);
