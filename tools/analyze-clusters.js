// 核算聚类：把钓点与聚类格子对齐，判断"聚成 N 处"是否符合数据分布。
//
// 用途：地图上显示"视野内 M 点聚成 N 处"时，判断 N 是否合理 ——
//   数据本身就集中在市区，那 N 小是正常的；如果数据分散却聚成 1 处，才是算法问题。
//
// 用法: node tools/analyze-clusters.js [视野南] [视野北] [视野西] [视野东]
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const j = JSON.parse(fs.readFileSync(path.join(root, 'assets', 'spots_seed.json'), 'utf8'));
const spots = j.spots.filter((s) => s.lat != null && s.lon != null);

// 默认取真机实测到的初始视野
const vLatMin = Number(process.argv[2] || 36.95);
const vLatMax = Number(process.argv[3] || 37.50);
const vLonMin = Number(process.argv[4] || 114.55);
const vLonMax = Number(process.argv[5] || 115.00);

const cenLat = (vLatMin + vLatMax) / 2;
const cosLat = Math.cos(cenLat * Math.PI / 180);
const KM = 111.32;

// 真机实测：地图控件 1440x2099px，内边距后绘图区 1335x2078px
const plotW = 1335;
const plotH = 2078;
const density = 3.75;
const CELL_DP = 26;
const cell = CELL_DP * density;             // zoom=1 时的格子像素

const cols = Math.max(1, Math.floor(plotW / cell));
const rows = Math.max(1, Math.floor(plotH / cell));

const inView = spots.filter((s) => s.lat >= vLatMin && s.lat <= vLatMax
  && s.lon >= vLonMin && s.lon <= vLonMax);

console.log(`  视野 ${vLatMin}~${vLatMax}°N  ${vLonMin}~${vLonMax}°E`);
console.log(`  总钓点 ${spots.length} 个，视野内 ${inView.length} 个`);
console.log('');
console.log(`  聚类格子 ${cell.toFixed(1)}px（${CELL_DP}dp）`
  + ` -> ${cols} 列 x ${rows} 行 = ${cols * rows} 格`);
const cellKmW = (vLonMax - vLonMin) / cols * KM * cosLat;
const cellKmH = (vLatMax - vLatMin) / rows * KM;
console.log(`  每格覆盖约 ${cellKmW.toFixed(2)} km(东西) x ${cellKmH.toFixed(2)} km(南北)`);
console.log('');

// 按格子统计
const grid = new Map();
const cw = plotW / cols;
const ch = plotH / rows;
for (const s of inView) {
  const x = (s.lon - vLonMin) / (vLonMax - vLonMin) * plotW;
  const y = (vLatMax - s.lat) / (vLatMax - vLatMin) * plotH;
  const c = Math.min(cols - 1, Math.max(0, Math.floor(x / cw)));
  const r = Math.min(rows - 1, Math.max(0, Math.floor(y / ch)));
  const k = `${r},${c}`;
  grid.set(k, (grid.get(k) || 0) + 1);
}

const counts = [...grid.values()].sort((a, b) => b - a);
console.log(`  实际占用格子数 = ${grid.size}  -> 界面上应显示"聚成 ${grid.size} 处"`);
console.log(`  各格点数（降序）: ${counts.slice(0, 15).join(', ')}`
  + (counts.length > 15 ? ` …共 ${counts.length} 格` : ''));
console.log('');

// 距离分析：最远的两个视野内钓点
let maxDist = 0, pair = null;
for (let i = 0; i < inView.length; i++) {
  for (let k = i + 1; k < inView.length; k++) {
    const dx = (inView[i].lon - inView[k].lon) * KM * cosLat;
    const dy = (inView[i].lat - inView[k].lat) * KM;
    const d = Math.hypot(dx, dy);
    if (d > maxDist) { maxDist = d; pair = [inView[i], inView[k]]; }
  }
}
if (pair) {
  console.log(`  视野内最远两点相距 ${maxDist.toFixed(1)} km`
    + `（${pair[0].name} ↔ ${pair[1].name}）`);
}
console.log(`  格子的物理尺寸 ${cellKmW.toFixed(2)} x ${cellKmH.toFixed(2)} km，`
  + `${grid.size === 1 ? '只有 1 格有点 => 数据高度集中，聚成 1 处是正常的' : `占用 ${grid.size} 格 => 聚成 ${grid.size} 处合理`}`);
