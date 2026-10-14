// 给 .ps1 加 UTF-8 BOM，让 Windows PowerShell 5.1 正确按 UTF-8 解析中文。
// 用法: node fix-bom.js <file.ps1> [...]
const fs = require('fs');

const BOM = Buffer.from([0xEF, 0xBB, 0xBF]);
let changed = 0;

for (const f of process.argv.slice(2)) {
  if (!fs.existsSync(f)) {
    console.log('MISSING ' + f);
    continue;
  }
  let buf = fs.readFileSync(f);
  // 去掉可能存在的重复 BOM
  while (buf.length >= 3 && buf[0] === 0xEF && buf[1] === 0xBB && buf[2] === 0xBF) {
    buf = buf.subarray(3);
  }
  // 校验是合法 UTF-8
  const text = buf.toString('utf8');
  if (text.includes('\uFFFD')) {
    console.log('WARN  ' + f + ' 不是合法 UTF-8，跳过节流');
  }
  fs.writeFileSync(f, Buffer.concat([BOM, buf]));
  changed++;
  console.log('BOM   ' + f + '  (' + (buf.length + 3) + ' bytes)');
}

console.log('done, ' + changed + ' file(s)');
