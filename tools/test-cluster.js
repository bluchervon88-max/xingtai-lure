// 聚类算法的离线复刻与断言。
//
// 为什么必须做这件事：
//   真机上用 adb dump 读统计行，读到的是"某一时刻"的显示值 ——
//   回调顺序、布局时序、前台切换都会让读数失真。本次排查中就被这种陈旧读数
//   误导了好几轮（一会儿显示"聚成 1 处"，一会儿显示"73/73 聚成 1 处"）。
//   把算法在 Node 里逐字复刻并断言，才能得到确定的结论。
//
// ★ 必须与 SpotMapView.buildClusters() 与 GeoViewport 逐行对应，改一处就要同步另一处。
//
// 用法: node tools/test-cluster.js
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const j = JSON.parse(fs.readFileSync(path.join(root, 'assets', 'spots_seed.json'), 'utf8'));
const KM = 111.32;

// ---- 与 Java 一致的常量
const CELL_DP = 30;              // SpotMapView.CELL_DP
const PAD_LEFT_DP = 16, PAD_RIGHT_DP = 12, PAD_TOP_DP = 14, PAD_BOTTOM_DP = 30;
const TARGET_KM_AT_1X = 42;      // GeoViewport.TARGET_KM_AT_1X
const MIN_ZOOM = 0.25, MAX_ZOOM = 32;

// ---- 真机实测的控件尺寸（vivo V2505A: 1440x2099px, density 3.75）
const density = 3.75;
const viewW = 1440, viewH = 2099;
const plotW = viewW - (PAD_LEFT_DP + PAD_RIGHT_DP) * density;
const plotH = viewH - (PAD_TOP_DP + PAD_BOTTOM_DP) * density;

let pass = 0, fail = 0;
function check(desc, ok, detail) {
  if (ok) { pass++; console.log(`  PASS  ${desc}   ${detail ?? ''}`); }
  else { fail++; console.log(`  FAIL  ${desc}   ${detail ?? ''}`); }
}

/** 复刻 GeoViewport.pxPerLatDeg() */
function pxPerLatDeg(centerLat, zoom) {
  const cosLat = Math.cos(centerLat * Math.PI / 180);
  const targetLonDeg = TARGET_KM_AT_1X / (KM * cosLat);
  return plotW / (targetLonDeg * cosLat) * zoom;
}
const kmW = (deg, lat) => deg * KM * Math.cos(lat * Math.PI / 180);

/** 复刻 SpotMapView.buildClusters() */
function cluster(spots, centerLat, centerLon, zoom) {
  const cosLat = Math.cos(centerLat * Math.PI / 180);
  const pLat = pxPerLatDeg(centerLat, zoom);
  const pLon = pLat * cosLat;
  const sx = (lon) => plotW / 2 + (lon - centerLon) * pLon;
  const sy = (lat) => plotH / 2 - (lat - centerLat) * pLat;

  const cell = CELL_DP * density;
  const cols = Math.max(1, Math.floor(plotW / cell));
  const rows = Math.max(1, Math.floor(plotH / cell));
  const cw = plotW / cols, ch = plotH / rows;

  const buckets = new Map();
  let culled = 0;
  for (const s of spots) {
    if (s.lat == null || s.lon == null) continue;
    const x = sx(s.lon), y = sy(s.lat);
    if (x < -cell || x > plotW + cell || y < -cell || y > plotH + cell) { culled++; continue; }
    const col = Math.min(cols - 1, Math.max(0, Math.floor(x / cw)));
    const row = Math.min(rows - 1, Math.max(0, Math.floor(y / ch)));
    const k = row * cols + col;
    if (!buckets.has(k)) buckets.set(k, []);
    buckets.get(k).push(s);
  }
  return {
    clusters: [...buckets.values()].map((members) => ({ members })),
    cell, cols, rows, culled, pLat, pLon,
    visLat: plotH / pLat, visLon: plotW / pLon,
  };
}

const spots = j.spots.filter((s) => s.lat != null && s.lon != null);
const cLat = (Math.min(...spots.map((s) => s.lat)) + Math.max(...spots.map((s) => s.lat))) / 2;
const cLon = (Math.min(...spots.map((s) => s.lon)) + Math.max(...spots.map((s) => s.lon))) / 2;

console.log('=== 聚类算法复刻校验 ===\n');
console.log(`  数据集 ${spots.length} 个带坐标钓点，投影中心 ${cLat.toFixed(3)}°N ${cLon.toFixed(3)}°E`);
console.log(`  画框 ${plotW}x${plotH}px`);

// ---- 1) 1x 的视野与聚类
const r1 = cluster(spots, cLat, cLon, 1.0);
console.log(`\n  【1.0x】格子 ${r1.cell.toFixed(1)}px -> ${r1.cols}列 x ${r1.rows}行`);
console.log(`    可见 ${kmW(r1.visLon, cLat).toFixed(1)} km(东西)`
  + ` x ${(r1.visLat * KM).toFixed(1)} km(南北)`);
check('1x 东西宽度 ≈ 42 km（GeoViewport 的设计目标）',
  Math.abs(kmW(r1.visLon, cLat) - TARGET_KM_AT_1X) < 1.5,
  `${kmW(r1.visLon, cLat).toFixed(1)} km`);
check('1x 时不会把所有点并成一团', r1.clusters.length > 1,
  `聚成 ${r1.clusters.length} 处`);
check('1x 聚类数不超过有效点数', r1.clusters.length <= spots.length - r1.culled,
  `${r1.clusters.length} 处 / ${spots.length - r1.culled} 点`);

// ---- 2) 比例一致性（形状不变形）
console.log('\n  【比例一致性】两方向 m/px 必须相同');
let ratioOk = true;
for (const z of [MIN_ZOOM, 1, 4, 16, MAX_ZOOM]) {
  const pLat = pxPerLatDeg(cLat, z);
  const pLon = pLat * Math.cos(cLat * Math.PI / 180);
  const mY = 111320 / pLat;
  const mX = 111320 * Math.cos(cLat * Math.PI / 180) / pLon;
  if (Math.abs(mX - mY) / mY > 1e-9) ratioOk = false;
  console.log(`    ${String(z).padStart(5)}x  ${mY.toFixed(2).padStart(9)} m/px`);
}
check('所有缩放级别比例严格一致', ratioOk);

// ---- 3) 放大后格子像素不变、覆盖范围变小
const r8 = cluster(spots, cLat, cLon, 8.0);
console.log(`\n  【8.0x】格子 ${r8.cell.toFixed(1)}px（应与 1x 相同，固定在屏幕上）`);
check('格子像素尺寸不随缩放变化', Math.abs(r8.cell - r1.cell) < 0.01,
  `${r1.cell.toFixed(1)} vs ${r8.cell.toFixed(1)}`);
check('放大后视野同比缩小',
  Math.abs((r8.visLon / r1.visLon) - 1 / 8) < 0.01,
  `视野比 ${(r8.visLon / r1.visLon).toFixed(4)}（应 0.125）`);

// ---- 4) 最小缩放能纵览全市
const rMin = cluster(spots, cLat, cLon, MIN_ZOOM);
const wMin = kmW(rMin.visLon, cLat);
console.log(`\n  【${MIN_ZOOM}x = 全览】`);
check('全览时东西装得下钓点范围（164 km）', wMin >= 164 * 0.98,
  `可见 ${wMin.toFixed(1)} km`);
check('全览时南北装得下钓点范围（83 km）', rMin.visLat * KM >= 83 * 0.98,
  `可见 ${(rMin.visLat * KM).toFixed(1)} km`);

// ---- 5) 分桶不丢点
const total = r1.clusters.reduce((a, c) => a + c.members.length, 0);
const effective = spots.length - r1.culled;
console.log(`\n  分桶合计 ${total}（全量 ${spots.length}，剔除视野外 ${r1.culled}）`);
check('分桶不丢点也不重复', total === effective, `${total} vs ${effective}`);

// ---- 6) 同格内点必须真的靠得近
let maxIntraKm = 0;
for (const c of r1.clusters) {
  for (let i = 0; i < c.members.length; i++) {
    for (let k = i + 1; k < c.members.length; k++) {
      const a = c.members[i], b = c.members[k];
      const dx = (a.lon - b.lon) * KM * Math.cos((a.lat + b.lat) / 2 * Math.PI / 180);
      const dy = (a.lat - b.lat) * KM;
      maxIntraKm = Math.max(maxIntraKm, Math.hypot(dx, dy));
    }
  }
}
const cellKm = r1.cell / r1.pLat * KM;
console.log(`\n  格子物理尺寸 ${cellKm.toFixed(2)} km，同格内最远两点 ${maxIntraKm.toFixed(2)} km`);
check('同格内点距不超过格子对角线的量级',
  maxIntraKm <= cellKm * 1.5, `${maxIntraKm.toFixed(2)} km vs 阈值 ${(cellKm * 1.5).toFixed(2)} km`);

console.log(`\n结论: PASS=${pass}  FAIL=${fail}`);
process.exit(fail === 0 ? 0 : 1);
