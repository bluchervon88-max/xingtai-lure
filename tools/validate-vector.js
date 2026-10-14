// 校验 Android Vector Drawable 的 pathData。
//
// 目的：启动图标是启动器显示的第一印象，路径写错在手机上就是一片空白，
//       而本机没有安卓设备可以直接看，所以用脚本把语法和数值先兜住。
//
// ★ 这里做过一次重写，原因是旧版只把 pathData 里所有数字一刀切地当成坐标，
//   于是把 SVG 的**相对偏移量**也当成了绝对坐标。结果是小眼睛那条圆路径
//   `M63,32 m-2.6,0 a2.6,2.6 0 1,0 5.2,0 ...` 里的 `-2.6` 被报成"坐标越界"，
//   而这个图标完全正确（圆心 63,32 半径 2.6，落在 108x108 里）。
//
//   危害不在于多一条日志，而在于**持续误报会让真问题被当成噪声忽略**。
//   所以现在按 SVG 规范解析：区分绝对/相对命令，把相对命令累加成绝对坐标，
//   只检查实际到达的点。
//
//   另外弧线命令 A/a 的参数不是坐标（是 rx ry 旋转 大弧标志 优弧标志 x y），
//   必须单独处理：半径不能为负、标志位只能是 0 或 1。
//
// 用法: node tools/validate-vector.js <file.xml> [...]
const fs = require('fs');

/** 各命令的参数个数（一组） */
const ARITY = { M: 2, L: 2, T: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, A: 7, Z: 0 };

let totalErrors = 0;

/**
 * 解析一条 pathData，返回 { errors, absolutePoints, arcs }
 *
 * absolutePoints —— 命令**实际到达**的绝对坐标（相对命令会被累加）
 * arcs           —— 弧线命令的参数，用于校验半径与标志位
 */
function parsePath(d) {
  const errors = [];
  const absolutePoints = [];
  const arcs = [];

  const tokens = d.match(/[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:[eE][-+]?\d+)?/g) || [];
  if (tokens.length === 0) {
    return { errors: ['pathData 为空'], absolutePoints, arcs };
  }

  let cur = null;            // 当前命令字母
  let args = [];             // 当前命令已读到的参数
  let x = 0;
  let y = 0;                 // 当前绝对位置
  let startX = 0;
  let startY = 0;            // 子路径起点（Z 用）
  let lastCmd = null;        // 用于把 M 后的多余坐标对当作 L

  const arityOf = (c) => ARITY[c.toUpperCase()];

  function handle() {
    if (cur === null) return;
    const up = cur.toUpperCase();
    const rel = cur !== up;
    const n = args.length;

    if (n === 0) {
      errors.push(`命令 ${cur} 后面没有参数`);
      return;
    }
    const step = arityOf(up);
    if (step === 0) return;
    if (n % step !== 0) {
      errors.push(`命令 ${cur} 的参数个数 ${n} 不是 ${step} 的整数倍`);
      return;
    }

    // 逐个坐标组处理（同一命令可以重复出现多组参数）
    for (let k = 0; k + step <= n; k += step) {
      const a = args.slice(k, k + step);
      switch (up) {
        case 'M':
          x = rel ? x + a[0] : a[0];
          y = rel ? y + a[1] : a[1];
          startX = x; startY = y;
          absolutePoints.push([x, y, 'M']);
          break;
        case 'L':
          x = rel ? x + a[0] : a[0];
          y = rel ? y + a[1] : a[1];
          absolutePoints.push([x, y, 'L']);
          break;
        case 'H':
          x = rel ? x + a[0] : a[0];
          absolutePoints.push([x, y, 'H']);
          break;
        case 'V':
          y = rel ? y + a[0] : a[0];
          absolutePoints.push([x, y, 'V']);
          break;
        case 'C': {   // x1 y1 x2 y2 x y
          const cx1 = rel ? x + a[0] : a[0], cy1 = rel ? y + a[1] : a[1];
          const cx2 = rel ? x + a[2] : a[2], cy2 = rel ? y + a[3] : a[3];
          x = rel ? x + a[4] : a[4];
          y = rel ? y + a[5] : a[5];
          absolutePoints.push([x, y, 'C'], [cx1, cy1, 'C控制点'], [cx2, cy2, 'C控制点']);
          break;
        }
        case 'S': {   // x2 y2 x y
          const cx2 = rel ? x + a[0] : a[0], cy2 = rel ? y + a[1] : a[1];
          x = rel ? x + a[2] : a[2];
          y = rel ? y + a[3] : a[3];
          absolutePoints.push([x, y, 'S'], [cx2, cy2, 'S控制点']);
          break;
        }
        case 'Q': {   // x1 y1 x y
          const cx1 = rel ? x + a[0] : a[0], cy1 = rel ? y + a[1] : a[1];
          x = rel ? x + a[2] : a[2];
          y = rel ? y + a[3] : a[3];
          absolutePoints.push([x, y, 'Q'], [cx1, cy1, 'Q控制点']);
          break;
        }
        case 'T':
          x = rel ? x + a[0] : a[0];
          y = rel ? y + a[1] : a[1];
          absolutePoints.push([x, y, 'T']);
          break;
        case 'A': {   // rx ry 旋转 大弧标志 优弧标志 x y
          const rx = a[0], ry = a[1], rot = a[2], large = a[3], sweep = a[4];
          arcs.push({ rx, ry, rot, large, sweep });
          if (rx < 0 || ry < 0) {
            errors.push(`弧线半径不能为负: ${cur}${a.slice(0, 2).join(',')}`);
          }
          if (large !== 0 && large !== 1) {
            errors.push(`弧线 large-arc-flag 必须是 0 或 1，实际 ${large}`);
          }
          if (sweep !== 0 && sweep !== 1) {
            errors.push(`弧线 sweep-flag 必须是 0 或 1，实际 ${sweep}`);
          }
          x = rel ? x + a[5] : a[5];
          y = rel ? y + a[6] : a[6];
          // 弧线端点一定落在圆周上，圆心在两端点连线的中垂线上，
          // 这里只记端点，圆的完整范围由 rx/ry 与端点共同决定（见下方 bounds 估算）
          absolutePoints.push([x, y, 'A'], [x - rx, y - ry, 'A包围'], [x + rx, y + ry, 'A包围']);
          break;
        }
        case 'Z':
          x = startX; y = startY;
          break;
        default:
          break;
      }
    }

    // M 之后的额外坐标组按 L 处理（SVG 规范）
    if (up === 'M' && n > 2) {
      cur = rel ? 'l' : 'L';
    }
    lastCmd = up;
  }

  let i = 0;
  while (i < tokens.length) {
    const t = tokens[i];
    if (/^[MmLlHhVvCcSsQqTtAaZz]$/.test(t)) {
      handle();
      cur = t;
      args = [];
      if (t === 'Z' || t === 'z') {
        cur = null;
      }
      i++;
      continue;
    }
    const v = Number(t);
    if (!Number.isFinite(v)) {
      errors.push(`无法解析的数字: ${t}`);
    }
    if (cur === null) {
      errors.push(`数字 ${t} 出现在任何命令之前`);
    } else {
      args.push(v);
    }
    i++;
  }
  handle();

  if (!/[Mm]/.test(d)) {
    errors.push('缺少 M（起始点）命令');
  }

  // 数值量级检查
  for (const tok of tokens) {
    if (/^-?\d/.test(tok) && Math.abs(Number(tok)) > 100000) {
      errors.push(`数值异常大: ${tok}`);
    }
  }

  return { errors, absolutePoints, arcs };
}

for (const file of process.argv.slice(2)) {
  if (!fs.existsSync(file)) {
    console.log('MISSING ' + file);
    totalErrors++;
    continue;
  }
  const xml = fs.readFileSync(file, 'utf8');
  const name = file.split(/[\\/]/).pop();

  const paths = [...xml.matchAll(/android:pathData\s*=\s*"([^"]*)"/g)].map((m) => m[1]);
  if (paths.length === 0) {
    console.log('⚠ ' + name + ' 没有找到 pathData');
    continue;
  }

  let fileErrors = 0;
  const allPoints = [];
  paths.forEach((d, idx) => {
    const { errors, absolutePoints } = parsePath(d);
    for (const e of errors) {
      console.log('❌ ' + name + ' path[' + idx + ']: ' + e);
      fileErrors++;
    }
    for (const p of absolutePoints) {
      allPoints.push({ x: p[0], y: p[1], kind: p[2], path: idx });
    }
  });

  // viewport / width / height
  const vpW = /android:viewportWidth\s*=\s*"([\d.]+)"/.exec(xml);
  const vpH = /android:viewportHeight\s*=\s*"([\d.]+)"/.exec(xml);
  const w = /android:width\s*=\s*"([\d.]+)dp"/.exec(xml);
  const h = /android:height\s*=\s*"([\d.]+)dp"/.exec(xml);
  if (!vpW || !vpH) {
    console.log('❌ ' + name + ' 缺少 viewportWidth/viewportHeight');
    fileErrors++;
  }
  if (!w || !h) {
    console.log('❌ ' + name + ' 缺少 width/height');
    fileErrors++;
  }

  // 越界检查
  //
  // ★ 分两类：命令**到达的点**越界是真问题（图形会被裁掉）；
  //   贝塞尔控制点越界通常无害（曲线本身仍在范围内），只提示不报错。
  //   旧版把两类混在一起、还把相对偏移量当坐标，导致持续误报。
  if (vpW && vpH) {
    const W = Number(vpW[1]);
    const H = Number(vpH[1]);

    const onCurve = allPoints.filter((p) => !p.kind.includes('控制点') && p.kind !== 'A包围');
    const softPoints = allPoints.filter((p) => p.kind.includes('控制点') || p.kind === 'A包围');

    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    for (const p of onCurve) {
      minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
      minY = Math.min(minY, p.y); maxY = Math.max(maxY, p.y);
    }

    const EPS = 0.5;   // 允许半个单位的溢出（抗锯齿边缘）
    const bad = onCurve.filter((p) => p.x < -EPS || p.x > W + EPS || p.y < -EPS || p.y > H + EPS);

    if (bad.length > 0) {
      console.log('❌ ' + name + ' 有 ' + bad.length + ' 个路径点超出 viewport '
        + W + 'x' + H + '（会被裁掉）:');
      for (const p of bad.slice(0, 6)) {
        console.log('     path[' + p.path + '] ' + p.kind + ' (' + p.x.toFixed(1)
          + ', ' + p.y.toFixed(1) + ')');
      }
      fileErrors++;
    } else if (onCurve.length) {
      console.log('   图形实际范围 x:' + minX.toFixed(1) + '~' + maxX.toFixed(1)
        + '  y:' + minY.toFixed(1) + '~' + maxY.toFixed(1)
        + '  （viewport ' + W + 'x' + H + '）');
      // 自适应图标的安全区：中心 66x66 是保证不被裁的部分，超出会按设备裁切
      const safe = 66;
      const cx = W / 2, cy = H / 2;
      const outsideSafe = onCurve.filter((p) =>
        p.x < cx - safe / 2 || p.x > cx + safe / 2 || p.y < cy - safe / 2 || p.y > cy + safe / 2);
      if (outsideSafe.length) {
        console.log('   提示: ' + outsideSafe.length + ' 个点落在自适应图标安全区（中心 '
          + safe + 'x' + safe + '）之外，某些启动器会裁切 —— 这是设计取舍，不算错误');
      }
    }

    // 控制点越界只提示
    const softBad = softPoints.filter((p) =>
      p.x < -EPS || p.x > W + EPS || p.y < -EPS || p.y > H + EPS);
    if (softBad.length) {
      console.log('   提示: ' + softBad.length + ' 个贝塞尔控制点/弧线包围盒超出 viewport，'
        + '曲线本身通常仍在范围内');
    }
  }

  if (fileErrors === 0) {
    console.log('✅ ' + name + '  路径合法（' + paths.length + ' 条 path，viewport '
      + (vpW ? vpW[1] : '?') + 'x' + (vpH ? vpH[1] : '?') + '）');
  }
  totalErrors += fileErrors;
}

console.log('');
console.log(totalErrors === 0 ? '全部通过' : ('共 ' + totalErrors + ' 个问题'));
process.exit(totalErrors > 0 ? 1 : 0);
