// 用 Node 内置模块解析 PNG 并采样像素，判断界面各区域实际画了什么。
//
// 为什么需要：本机 read_image 的 sharp 原生模块加载失败，看不了图；
// 而"某个区域到底是背景色还是有内容"用像素采样比肉眼看更确定。
//
// 用法: node tools/png-probe.js <png> [行号...]
//   不带行号则按屏幕高度均分采样若干行，输出每行的颜色分布。
const fs = require('fs');
const zlib = require('zlib');

const file = process.argv[2];
if (!file) { console.error('用法: node png-probe.js <png> [row...]'); process.exit(2); }
const buf = fs.readFileSync(file);

if (buf.readUInt32BE(0) !== 0x89504e47) { console.error('不是 PNG'); process.exit(1); }

let off = 8;
let width = 0, height = 0, bitDepth = 0, colorType = 0;
const idat = [];
while (off < buf.length) {
  const len = buf.readUInt32BE(off);
  const type = buf.toString('ascii', off + 4, off + 8);
  const data = buf.subarray(off + 8, off + 8 + len);
  if (type === 'IHDR') {
    width = data.readUInt32BE(0);
    height = data.readUInt32BE(4);
    bitDepth = data[8];
    colorType = data[9];
  } else if (type === 'IDAT') {
    idat.push(data);
  } else if (type === 'IEND') {
    break;
  }
  off += 12 + len;
}

console.log(`图像: ${width}x${height}  bitDepth=${bitDepth} colorType=${colorType}`);
if (bitDepth !== 8) { console.error('只支持 8 位深'); process.exit(1); }
const channels = colorType === 6 ? 4 : colorType === 2 ? 3 : colorType === 0 ? 1 : 0;
if (!channels) { console.error('不支持的颜色类型 ' + colorType); process.exit(1); }

const raw = zlib.inflateSync(Buffer.concat(idat));
const stride = width * channels;
const px = Buffer.alloc(height * stride);

// 反 PNG 滤波器
for (let y = 0; y < height; y++) {
  const ft = raw[y * (stride + 1)];
  const line = raw.subarray(y * (stride + 1) + 1, y * (stride + 1) + 1 + stride);
  const cur = px.subarray(y * stride, (y + 1) * stride);
  const prev = y > 0 ? px.subarray((y - 1) * stride, y * stride) : null;
  for (let x = 0; x < stride; x++) {
    const a = x >= channels ? cur[x - channels] : 0;
    const b = prev ? prev[x] : 0;
    const c = (prev && x >= channels) ? prev[x - channels] : 0;
    let v = line[x];
    switch (ft) {
      case 0: break;
      case 1: v = (v + a) & 0xff; break;
      case 2: v = (v + b) & 0xff; break;
      case 3: v = (v + ((a + b) >> 1)) & 0xff; break;
      case 4: {
        const p = a + b - c;
        const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
        const pr = (pa <= pb && pa <= pc) ? a : (pb <= pc ? b : c);
        v = (v + pr) & 0xff; break;
      }
      default: throw new Error('未知滤波类型 ' + ft);
    }
    cur[x] = v;
  }
}

function hex(r, g, b) {
  return '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('');
}

// 采样某一行，统计颜色直方图
function probeRow(y) {
  const counts = new Map();
  const cur = y * stride;
  for (let x = 0; x < width; x++) {
    const i = cur + x * channels;
    const key = hex(px[i], px[i + 1], px[i + 2]);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  const sorted = [...counts.entries()].sort((a, b) => b[1] - a[1]).slice(0, 4);
  return sorted.map(([c, n]) => `${c}×${n}`).join('  ');
}

const rows = process.argv.slice(3).map(Number).filter((n) => !isNaN(n));
const sampleRows = rows.length ? rows
  : Array.from({ length: 16 }, (_, i) => Math.floor((i + 0.5) * height / 16));

console.log('\n行       y      该行主要颜色');
console.log('-'.repeat(78));
for (const y of sampleRows) {
  if (y < 0 || y >= height) continue;
  console.log(String(y).padEnd(8) + String(y).padEnd(8) + probeRow(y));
}

// 附加：统计整幅图里非背景色的像素占比，判断"有没有画出东西"
console.log('\n提示：本 App 深色主题的背景是 #0e1116，卡片是 #171c24，主色 #2fb6a8');
