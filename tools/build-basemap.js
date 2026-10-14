// 用一个共享的几何编码模块，把 OSM 数据编译成 App 可离线渲染的顶点流。
//
// 用法:
//   node tools/build-basemap.js <输入.json> <输出.txt> <water|boundary|river>
//
// ★ 为什么自己做底图，而不是接商用地图 SDK
//   高德 / 百度 / 腾讯的 SDK 都需要申请 API Key + INTERNET 权限 + 联网环境，
//   而本 App 的核心卖点是"不申请网络权限、离线可用"。所以把真实地理数据
//   编译进 APK，本地矢量绘制 —— 体积小两个数量级，缩放也不会糊。
//   数据来源 OpenStreetMap（ODbL 许可），已在关于页署名。
const fs = require('fs');
const { encodePath } = require('./basemap-codec');

const src = process.argv[2];
const out = process.argv[3];
const kind = process.argv[4];
if (!src || !out || !kind) {
  console.error('用法: node build-basemap.js <输入.json> <输出.txt> <water|boundary|river>');
  process.exit(2);
}

/** 坐标量化到 1e-5 度（约 1 米精度） */
const Q = 1e5;

/** 道格拉斯-普克简化容差（度） */
const TOL = { water: 3e-5, boundary: 8e-5, river: 6e-5 }[kind];

/** 面积下限（平方度）：更小的水体在屏幕上就是一个点，留着只会拖慢绘制 */
const MIN_AREA = { water: 2e-5, boundary: 0, river: 0 }[kind];

/** 最少顶点数 */
const MIN_PTS = { water: 4, boundary: 4, river: 6 }[kind];

/** 邢台市下辖的区县（其余区县仅作地理参照，App 端画得更淡） */
const XINGTAI = new Set(['信都区', '襄都区', '南和区', '任泽区', '沙河市', '临城县',
  '内丘县', '柏乡县', '隆尧县', '宁晋县', '巨鹿县', '新河县', '广宗县', '平乡县',
  '威县', '清河县', '临西县', '南宫市', '邢台县']);

function simplify(pts, tol) {
  if (pts.length < 3) return pts;
  const keep = new Uint8Array(pts.length);
  keep[0] = 1;
  keep[pts.length - 1] = 1;
  const stack = [[0, pts.length - 1]];
  const tol2 = tol * tol;
  while (stack.length) {
    const [a, b] = stack.pop();
    if (b - a < 2) continue;
    const [ax, ay] = pts[a];
    const [bx, by] = pts[b];
    const dx = bx - ax, dy = by - ay;
    const len2 = dx * dx + dy * dy;
    let maxD = -1, maxI = -1;
    for (let i = a + 1; i < b; i++) {
      const [px, py] = pts[i];
      let d;
      if (len2 === 0) {
        d = (px - ax) ** 2 + (py - ay) ** 2;
      } else {
        let t = ((px - ax) * dx + (py - ay) * dy) / len2;
        t = Math.max(0, Math.min(1, t));
        d = (px - ax - t * dx) ** 2 + (py - ay - t * dy) ** 2;
      }
      if (d > maxD) { maxD = d; maxI = i; }
    }
    if (maxD > tol2) {
      keep[maxI] = 1;
      stack.push([a, maxI], [maxI, b]);
    }
  }
  const res = [];
  for (let i = 0; i < pts.length; i++) if (keep[i]) res.push(pts[i]);
  return res;
}

function ringArea(pts) {
  let s = 0;
  for (let i = 0, n = pts.length; i < n; i++) {
    const [x1, y1] = pts[i];
    const [x2, y2] = pts[(i + 1) % n];
    s += x1 * y2 - x2 * y1;
  }
  return Math.abs(s) / 2;
}

/** 把一串经纬点量化、去重、按需去掉闭合重复点，返回整数点列 */
function quantize(geometry, close) {
  const pts = [];
  for (const p of geometry) {
    const x = Math.round(p.lon * Q);
    const y = Math.round(p.lat * Q);
    const last = pts[pts.length - 1];
    if (!last || last[0] !== x || last[1] !== y) pts.push([x, y]);
  }
  if (close && pts.length >= 2) {
    const a = pts[0], b = pts[pts.length - 1];
    if (a[0] === b[0] && a[1] === b[1]) pts.pop();
  }
  return pts;
}

const raw = JSON.parse(fs.readFileSync(src, 'utf8'));
const lines = [];
let shapes = 0, ptsTotal = 0, skippedSmall = 0, skippedBad = 0;

/**
 * 统一的数据行格式： `类型|几何|local|标注y|标注x|名称`
 * 第一段类型字符让解码端不必猜元数据有几段 ——
 * 之前水系是 3 段、边界是 5 段，解码时容易把名称错位。
 */
function row(type, pts, local, labelY, labelX, name) {
  return type + '|' + encodePath(pts) + '|' + (local ? 1 : 0) + '|'
    + (labelY === null ? '' : labelY) + '|' + (labelX === null ? '' : labelX)
    + '|' + (name || '');
}

if (kind === 'water') {
  lines.push('XTLUREMAP1', 'water');
  for (const e of raw.elements) {
    const geoms = [];
    if (e.type === 'way' && e.geometry) {
      geoms.push({ g: e.geometry, name: (e.tags && e.tags.name) || '' });
    } else if (e.type === 'relation' && e.members) {
      const nm = (e.tags && (e.tags.name || e.tags['name:zh'])) || '';
      for (const m of e.members) {
        if ((m.role === 'outer' || m.role === '') && m.geometry) {
          geoms.push({ g: m.geometry, name: nm });
        }
      }
    }
    for (const item of geoms) {
      let pts = quantize(item.g, true);
      if (pts.length < MIN_PTS) { skippedBad++; continue; }
      if (ringArea(pts) < MIN_AREA * Q * Q) { skippedSmall++; continue; }
      pts = simplify(pts, TOL * Q);
      if (pts.length < 3) { skippedSmall++; continue; }
      // 标注点取该环质心
      let cx = 0, cy = 0;
      for (const p of pts) { cx += p[0]; cy += p[1]; }
      lines.push(row('W', pts, false, Math.round(cy / pts.length),
        Math.round(cx / pts.length), item.name));
      shapes++;
      ptsTotal += pts.length;
    }
  }
} else if (kind === 'boundary') {
  lines.push('XTLUREBOUND1', 'boundary');
  for (const e of raw.elements) {
    const name = (e.tags && (e.tags.name || e.tags['name:zh'])) || '';
    if (!name || !e.members) continue;
    const isLocal = XINGTAI.has(name);
    let first = true;
    for (const m of e.members) {
      if (m.role !== 'outer' && m.role !== '') continue;
      if (!m.geometry) continue;
      let pts = quantize(m.geometry, true);
      if (pts.length < MIN_PTS) { skippedBad++; continue; }
      pts = simplify(pts, TOL * Q);
      if (pts.length < 3) { skippedSmall++; continue; }

      if (first) {
        // 只在第一个环上带名称与标注点（质心），其余环留空
        let cx = 0, cy = 0;
        for (const p of pts) { cx += p[0]; cy += p[1]; }
        lines.push(row('B', pts, isLocal, Math.round(cy / pts.length),
          Math.round(cx / pts.length), name));
        first = false;
      } else {
        lines.push(row('B', pts, isLocal, null, null, ''));
      }
      shapes++;
      ptsTotal += pts.length;
    }
  }
} else if (kind === 'river') {
  lines.push('XTLURERIVER1', 'river');
  for (const e of raw.elements) {
    if (e.type !== 'way' || !e.geometry) { skippedBad++; continue; }
    const name = (e.tags && e.tags.name) || '';
    const isCanal = (e.tags && e.tags.waterway === 'canal');
    let pts = quantize(e.geometry, false);
    if (pts.length < MIN_PTS) { skippedBad++; continue; }
    pts = simplify(pts, TOL * Q);
    if (pts.length < 3) { skippedSmall++; continue; }
    // 河道用 local 位携带"是否渠道"，标注点取中点
    const mid = Math.floor(pts.length / 2);
    lines.push(row('R', pts, isCanal, pts[mid][1], pts[mid][0], name));
    shapes++;
    ptsTotal += pts.length;
  }
} else {
  console.error('未知类型: ' + kind);
  process.exit(2);
}

const text = lines.join('\n');
fs.writeFileSync(out, text, 'utf8');
console.log(`${kind}: 输出 ${shapes} 个几何, 顶点 ${ptsTotal}, `
  + `过小丢弃 ${skippedSmall}, 无效丢弃 ${skippedBad}, `
  + `文件 ${(text.length / 1024).toFixed(0)} KB`);
