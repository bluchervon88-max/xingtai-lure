// 按真实嵌套层级打印 uiautomator dump 的节点树。
// 用简易标签扫描（dump 的 XML 结构规整，且全是自闭合标签），不引入 XML 库。
//
// 用法: node tools/ui-tree.js <ui.xml>
const fs = require('fs');

const file = process.argv[2];
if (!file) {
  console.error('用法: node ui-tree.js <ui.xml>');
  process.exit(2);
}
const xml = fs.readFileSync(file, 'utf8');

const tokenRe = /<(\/?)([\w:]+)((?:\s+[\w:-]+="[^"]*")*)\s*(\/?)>/g;
let depth = 0;
let m;
const lines = [];

function attr(attrs, k) {
  const r = new RegExp('\\s' + k + '="([^"]*)"').exec(attrs);
  return r ? r[1] : '';
}

while ((m = tokenRe.exec(xml)) !== null) {
  const closing = m[1] === '/';
  const tag = m[2];
  const attrs = m[3];
  const selfClose = m[4] === '/';

  if (closing) {
    depth = Math.max(0, depth - 1);
    continue;
  }

  const cls = attr(attrs, 'class').split('.').pop();
  const text = attr(attrs, 'text');
  const bounds = attr(attrs, 'bounds');
  const clickable = attr(attrs, 'clickable') === 'true';
  const scrollable = attr(attrs, 'scrollable') === 'true';
  const id = attr(attrs, 'resource-id').replace('android:id/', '@');

  const flags = [];
  if (clickable) flags.push('可点');
  if (scrollable) flags.push('可滚');
  if (id) flags.push(id);

  lines.push(
    '  '.repeat(Math.min(depth, 14))
    + cls.padEnd(16)
    + bounds.padEnd(24)
    + (text ? '「' + text + '」' : '')
    + (flags.length ? '  [' + flags.join(' ') + ']' : '')
  );

  if (!selfClose && tag !== 'hierarchy') {
    depth++;
  } else if (tag === 'hierarchy') {
    depth++;
  }
}

console.log('节点树（缩进=层级）:\n');
console.log('  '.repeat(0) + '类型'.padEnd(16) + '边界'.padEnd(24) + '文字');
console.log('-'.repeat(100));
lines.forEach((l) => console.log(l));

// 统计
const nodeCount = (xml.match(/<node /g) || []).length;
console.log('\n节点总数: ' + nodeCount);
console.log('含文字的节点: ' + (xml.match(/text="[^"]+"/g) || []).length);
