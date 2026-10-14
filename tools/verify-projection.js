// 独立复算 SpotMapView 的投影数学，确认「比例」与「缩放」都正确。
//
// 为什么单独验：
//   "地图比例不对"这类问题在截图上看不出量级 —— 差 25% 的横向拉伸肉眼很难判断。
//   但算出来一目了然：只要两个方向的地面米数/像素数不同，就一定是变形的。
//
// ★ 本脚本必须与 SpotMapView 的公式逐字对应，否则验的是另一套东西。
//   曾经因为脚本里的基准公式和 Java 不一致（脚本按"装下市域"、Java 按"40km 默认视野"），
//   脚本一路报"全部通过"，而真机行为完全不是那么回事。
//   下面把 Java 的公式抄成函数，改动 Java 时必须同步这里。
//
// 用法: node tools/verify-projection.js
const D2R = Math.PI / 180;
const EARTH = 111320;

// ---- 真机实测的布局参数（vivo V2505A / 384x845dp / density 3.75）
const density = 3.75;
const plotW = 1440 - (16 + 12) * density;   // 绘图区宽（GeoViewport 用绘图区尺寸）
const plotH = 2099 - (14 + 30) * density;

// ---- SpotMapView 的常量
const INITIAL_ZOOM = 1.0;
const MIN_ZOOM = 0.25;
const MAX_ZOOM = 32.0;
const TARGET_KM = 42.0;      // basePxPer0_01() 里的目标视野宽度

// ---- 数据范围（73 个钓点的外接框）
const dMinLat = 36.85, dMaxLat = 37.60, dMinLon = 113.85, dMaxLon = 115.70;

// 投影中心 = 钓点外接框中心（fitToData 就是这么定的）
const centerLat = (dMinLat + dMaxLat) / 2;
const centerLon = (dMinLon + dMaxLon) / 2;
const cosLat = Math.cos(centerLat * D2R);

const dataLat = dMaxLat - dMinLat;
const dataLon = dMaxLon - dMinLon;

/**
 * 复刻 basePxPer0_01()：
 *   targetDeg = TARGET_KM / (111.32 × cosLat)      // 40km 对应多少度经度
 *   pxPerLat(每度) = plotW / (targetDeg × cosLat)  // 让 40km 铺满画框宽
 *   返回「每 0.01° 纬度」的像素数 = pxPerLat / 100
 */
function basePxPer0_01() {
  const targetDeg = TARGET_KM / (111.32 * cosLat);
  const pxPerLat = plotW / (targetDeg * cosLat);
  return pxPerLat / 100;
}

const BASE = basePxPer0_01();

console.log(`  画框 ${plotW}x${plotH}px，投影中心 ${centerLat.toFixed(3)}°N / ${centerLon.toFixed(3)}°E`);
console.log(`  cos(中心纬度) = ${cosLat.toFixed(4)}`);
console.log(`  基准比例 = ${BASE.toFixed(3)} px / 0.01°纬度（每度纬度 ${(BASE * 100).toFixed(0)} px）`);
console.log('');

function atZoom(z) {
  const pxPerLat = BASE * 100 * z;            // 每度纬度
  const pxPerLon = pxPerLat * cosLat;         // 每度经度
  return {
    z, pxPerLat, pxPerLon,
    mPerPx: EARTH / pxPerLat,
    mPerPxY: EARTH / pxPerLat,
    mPerPxX: (EARTH * cosLat) / pxPerLon,
    visLat: plotH / pxPerLat,
    visLon: plotW / pxPerLon,
  };
}
const km = (deg, isLon) => (deg * EARTH * (isLon ? cosLat : 1) / 1000);

let pass = 0, fail = 0;
function check(desc, ok, detail) {
  if (ok) { pass++; console.log(`    ✅ ${desc}   ${detail ?? ''}`); }
  else { fail++; console.log(`    ❌ ${desc}   ${detail ?? ''}`); }
}

// ---- 1) 比例一致性（形状不变形）—— 这是"比例不对"的核心判据
console.log('  【比例一致性】两个方向的地面米数/像素必须相同');
let ratioOk = true;
for (const z of [MIN_ZOOM, 1, 2, 8, 16, MAX_ZOOM]) {
  const s = atZoom(z);
  const diff = Math.abs(s.mPerPxX - s.mPerPxY) / s.mPerPxY;
  if (diff >= 1e-9) ratioOk = false;
  console.log(`    ${String(z).padStart(4)}x  ${s.mPerPx.toFixed(2).padStart(9)} m/px`
    + `   x/y 差异 ${diff.toExponential(1)}%`);
}
check('所有缩放级别比例严格一致', ratioOk);
console.log('');

// ---- 2) 视野范围表
console.log('  【各缩放级别的视野】1x = 东西约 42 km（看清一个县）');
console.log('    缩放    南北km    东西km   1km对应像素');
for (const z of [MIN_ZOOM, 1, 2, 4, 8, 16, MAX_ZOOM]) {
  const s = atZoom(z);
  console.log(`    ${String(z).padStart(4)}x  ${km(s.visLat, false).toFixed(1).padStart(8)}`
    + `  ${km(s.visLon, true).toFixed(1).padStart(8)}`
    + `  ${(1000 / s.mPerPx).toFixed(1).padStart(10)}`);
}
console.log('');

// ---- 3) 1x 视野宽度必须等于设计目标
const s1 = atZoom(1);
const width1 = km(s1.visLon, true);
console.log('  【1x 视野宽度】');
check('1x 东西宽度 = 42 km（设计目标）', Math.abs(width1 - TARGET_KM) < 1.0,
  `${width1.toFixed(1)} km`);
console.log('');

// ---- 4) 最小缩放要能纵览全市
const sMin = atZoom(MIN_ZOOM);
const widthMin = km(sMin.visLon, true);
const heightMin = km(sMin.visLat, false);
console.log('  【最小缩放 0.25x = 全览】');
check('0.25x 东西方向装得下钓点范围', widthMin >= km(dataLon, true),
  `可见 ${widthMin.toFixed(1)} km / 需要 ${km(dataLon, true).toFixed(1)} km`);
check('0.25x 南北方向装得下钓点范围', heightMin >= km(dataLat, false),
  `可见 ${heightMin.toFixed(1)} km / 需要 ${km(dataLat, false).toFixed(1)} km`);
console.log('');

// ---- 5) 最大缩放要能分辨相邻钓点
const sMax = atZoom(MAX_ZOOM);
const nearestKm = 2.5;      // 市区内两个钓点的典型间距
const gapPx = (nearestKm * 1000) / sMax.mPerPx;
console.log('  【最大缩放 32x】');
console.log(`    视野 ${km(sMax.visLat, false).toFixed(2)} km x ${km(sMax.visLon, true).toFixed(2)} km`);
console.log(`    1 px = ${sMax.mPerPx.toFixed(1)} m`);
check(`相距 ${nearestKm} km 的两点在屏上能分开（>40px）`, gapPx > 40,
  `${gapPx.toFixed(0)} px`);
check('32x 视野宽度不小于 0.5 km', km(sMax.visLon, true) > 0.5,
  `${km(sMax.visLon, true).toFixed(2)} km`);
check('0.25x 视野宽度不超过 400 km（不会缩成一小块）', widthMin < 400,
  `${widthMin.toFixed(1)} km`);
console.log('');

// ---- 6) 旧写法对比（记录问题根因，防止改回去）
console.log('  【对比：旧写法（两方向各自拉满画框，不做 cos 修正）】');
const oldPxLat = plotH / (dataLat * 1.25);
const oldPxLon = plotW / (dataLon * 1.25);
const oldY = EARTH / oldPxLat;
const oldX = (EARTH * cosLat) / oldPxLon;
const oldRatio = oldX / oldY;
console.log(`    x方向 ${oldX.toFixed(1)} m/px   y方向 ${oldY.toFixed(1)} m/px`
  + `   相差 ${((oldRatio - 1) * 100).toFixed(1)}%`);
check('旧写法确实存在形变（记录问题根因）', Math.abs(oldRatio - 1) > 0.05,
  `横向被拉伸 ${oldRatio.toFixed(3)} 倍`);

console.log('');
console.log(`投影校验: PASS=${pass}  FAIL=${fail}`);
process.exit(fail === 0 ? 0 : 1);
