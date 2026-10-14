// 把启动图标的前景图层重新排布到自适应图标的安全区内。
//
// ★ 为什么需要这个脚本
//   Android 自适应图标的前景图层是 108x108dp，但只有中心 66x66dp（坐标 21~87）
//   是**保证可见**的，其余部分会被启动器按形状裁掉（圆形、方形、水滴形…）。
//
//   原图标的图形横跨 x 2.5~105.2，左右各超出安全区约 18.5dp。
//   实测推演：在常见的圆形蒙版下，水波两端距圆心 51.6（半径只有 36）必被裁掉，
//   鱼钩顶部距圆心 43.9 也多半被裁 —— 桌面上看到的是"断掉的鱼和残缺的水波"。
//
//   这个问题一直存在，但 197 项测试全绿也没抓到，因为没有任何测试检查图标几何。
//   是修 validate-vector.js 的误报时才顺带发现的。
//
// 做法：解析 pathData，按缩放比 S 绕中心缩放，再平移到安全区中心。
//       对弧线命令 A/a 要特殊处理：半径要一起缩放，标志位与旋转角不动。
//
// 用法: node tools/fit-icon.js
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
const src = path.join(root, 'res/drawable/ic_launcher_foreground.xml');

const VIEWPORT = 108;
const SAFE = 66;                  // 保证可见的安全区边长
const SAFE_MIN = (VIEWPORT - SAFE) / 2;   // 21
const SAFE_MAX = SAFE_MIN + SAFE;         // 87

/** 解析 pathData 成 token 列表（命令字母与数字交替） */
function tokenize(d) {
  return d.match(/[MmLlHhVvCcSsQqTtAaZz]|-?\d*\.?\d+(?:[eE][-+]?\d+)?/g) || [];
}

/**
 * 对一条 pathData 施加「绕 (cx,cy) 缩放 s 倍，再平移 (dx,dy)」。
 *
 * 关键：不是所有数字都是坐标。
 *   · A/a 的参数是 rx ry 旋转 大弧标志 优弧标志 x y —— 只有 rx,ry 和 x,y 需要变换
 *   · 旋转角、标志位必须原样保留（缩放一个标志位会把 0 变成 1）
 */
function transformPath(d, s, dx, dy) {
  const toks = tokenize(d);
  const out = [];
  let i = 0;
  let cmd = null;

  /** 变换一个绝对坐标点 */
  const pt = (v) => Number((v * s).toFixed(3));
  /** 变换一个相对偏移（只缩放，不平移） */
  const off = (v) => Number((v * s).toFixed(3));

  while (i < toks.length) {
    const t = toks[i];
    if (/^[MmLlHhVvCcSsQqTtAaZz]$/.test(t)) {
      cmd = t;
      out.push(t);
      i++;
      continue;
    }
    const up = (cmd || '').toUpperCase();
    const rel = cmd !== up;

    switch (up) {
      case 'M':
      case 'L':
      case 'T': {
        // x y
        const x = Number(toks[i]);
        const y = Number(toks[i + 1]);
        out.push(rel ? off(x) : pt(x + (rel ? 0 : 0)));
        out.push(rel ? off(y) : pt(y));
        if (!rel) {
          // 绝对坐标还要加平移量
          out[out.length - 2] = Number((x * s + dx).toFixed(3));
          out[out.length - 1] = Number((y * s + dy).toFixed(3));
        }
        i += 2;
        break;
      }
      case 'H': {
        const x = Number(toks[i]);
        out.push(rel ? off(x) : Number((x * s + dx).toFixed(3)));
        i += 1;
        break;
      }
      case 'V': {
        const y = Number(toks[i]);
        out.push(rel ? off(y) : Number((y * s + dy).toFixed(3)));
        i += 1;
        break;
      }
      case 'C': {
        // x1 y1 x2 y2 x y
        for (let k = 0; k < 3; k++) {
          const x = Number(toks[i + k * 2]);
          const y = Number(toks[i + k * 2 + 1]);
          out.push(rel ? off(x) : Number((x * s + dx).toFixed(3)));
          out.push(rel ? off(y) : Number((y * s + dy).toFixed(3)));
        }
        i += 6;
        break;
      }
      case 'S':
      case 'Q': {
        // x1 y1 x y  /  x2 y2 x y
        for (let k = 0; k < 2; k++) {
          const x = Number(toks[i + k * 2]);
          const y = Number(toks[i + k * 2 + 1]);
          out.push(rel ? off(x) : Number((x * s + dx).toFixed(3)));
          out.push(rel ? off(y) : Number((y * s + dy).toFixed(3)));
        }
        i += 4;
        break;
      }
      case 'A': {
        // rx ry 旋转 大弧标志 优弧标志 x y
        const rx = Number(toks[i]);
        const ry = Number(toks[i + 1]);
        const rot = Number(toks[i + 2]);
        const large = Number(toks[i + 3]);
        const sweep = Number(toks[i + 4]);
        const x = Number(toks[i + 5]);
        const y = Number(toks[i + 6]);
        // 半径只缩放，绝不加平移
        out.push(off(rx), off(ry));
        // 旋转角与标志位原样保留
        out.push(rot, large, sweep);
        out.push(rel ? off(x) : Number((x * s + dx).toFixed(3)));
        out.push(rel ? off(y) : Number((y * s + dy).toFixed(3)));
        i += 7;
        break;
      }
      default:
        // Z/z 或未知：原样带过
        out.push(t);
        i++;
        break;
    }
  }

  // 用逗号/空格密度适中地拼回去
  return out.join(' ').replace(/\s+/g, ' ').trim();
}

// ---------------------------------------------------------------- 主流程

if (!fs.existsSync(src)) {
  console.error('找不到 ' + src);
  process.exit(1);
}

const xml = fs.readFileSync(src, 'utf8');
const paths = [...xml.matchAll(/android:pathData\s*=\s*"([^"]*)"/g)];

// 计算当前图形范围（复用 validate-vector 的解析思路：只看命令到达的点）
function boundsOf(pathDatas) {
  let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
  for (const d of pathDatas) {
    const toks = tokenize(d);
    let i = 0, cmd = null;
    let x = 0, y = 0, sx = 0, sy = 0;
    const note = (px, py) => {
      minX = Math.min(minX, px); maxX = Math.max(maxX, px);
      minY = Math.min(minY, py); maxY = Math.max(maxY, py);
    };
    const handle = (args) => {
      const up = (cmd || '').toUpperCase();
      const rel = cmd !== up;
      const st = { M: 2, L: 2, T: 2, H: 1, V: 1, C: 6, S: 4, Q: 4, A: 7, Z: 0 }[up] || 0;
      if (!st) return;
      for (let k = 0; k + st <= args.length; k += st) {
        const a = args.slice(k, k + st);
        if (up === 'M') { x = rel ? x + a[0] : a[0]; y = rel ? y + a[1] : a[1]; sx = x; sy = y; note(x, y); }
        else if (up === 'L') { x = rel ? x + a[0] : a[0]; y = rel ? y + a[1] : a[1]; note(x, y); }
        else if (up === 'H') { x = rel ? x + a[0] : a[0]; note(x, y); }
        else if (up === 'V') { y = rel ? y + a[0] : a[0]; note(x, y); }
        else if (up === 'C') { x = rel ? x + a[4] : a[4]; y = rel ? y + a[5] : a[5]; note(x, y); }
        else if (up === 'S' || up === 'Q') { x = rel ? x + a[2] : a[2]; y = rel ? y + a[3] : a[3]; note(x, y); }
        else if (up === 'T') { x = rel ? x + a[0] : a[0]; y = rel ? y + a[1] : a[1]; note(x, y); }
        else if (up === 'A') {
          const rx = a[0], ry = a[1];
          x = rel ? x + a[5] : a[5]; y = rel ? y + a[6] : a[6];
          note(x, y);
          // 圆弧可能鼓出端点之外，把包围盒也算进去（保守估计）
          note(Math.min(x, x - rx), Math.min(y, y - ry));
          note(Math.max(x, x + rx), Math.max(y, y + ry));
        }
      }
    };
    let args = [];
    while (i < toks.length) {
      const t = toks[i];
      if (/^[MmLlHhVvCcSsQqTtAaZz]$/.test(t)) {
        handle(args); cmd = t; args = [];
        if (t === 'Z' || t === 'z') cmd = null;
        i++;
      } else {
        args.push(Number(t)); i++;
      }
    }
    handle(args);
  }
  return { minX, maxX, minY, maxY };
}

const original = paths.map((m) => m[1]);
const b0 = boundsOf(original);
console.log('  原图形范围: x ' + b0.minX.toFixed(1) + '~' + b0.maxX.toFixed(1)
  + '  y ' + b0.minY.toFixed(1) + '~' + b0.maxY.toFixed(1));
console.log('  安全区:     x ' + SAFE_MIN + '~' + SAFE_MAX + '  y ' + SAFE_MIN + '~' + SAFE_MAX);

const gw = b0.maxX - b0.minX;
const gh = b0.maxY - b0.minY;
// 留一点内边距，让图形不贴着安全区边缘
const PADDING = 2;
const avail = SAFE - PADDING * 2;
const scale = Math.min(avail / gw, avail / gh);

// 缩放后的中心对齐到安全区中心
const gcx = (b0.minX + b0.maxX) / 2;
const gcy = (b0.minY + b0.maxY) / 2;
const target = VIEWPORT / 2;
const dx = target - gcx * scale;
const dy = target - gcy * scale;

console.log('');
console.log('  缩放比: ' + scale.toFixed(4));
console.log('  平移量: dx=' + dx.toFixed(2) + '  dy=' + dy.toFixed(2));
console.log('');

// 逐条变换
const out = [];
original.forEach((d, i) => {
  out.push({ idx: i, d: transformPath(d, scale, dx, dy) });
});

const b1 = boundsOf(out.map((o) => o.d));
console.log('  变换后范围: x ' + b1.minX.toFixed(1) + '~' + b1.maxX.toFixed(1)
  + '  y ' + b1.minY.toFixed(1) + '~' + b1.maxY.toFixed(1));
const inside = b1.minX >= SAFE_MIN - 0.5 && b1.maxX <= SAFE_MAX + 0.5
  && b1.minY >= SAFE_MIN - 0.5 && b1.maxY <= SAFE_MAX + 0.5;
console.log('  是否落在安全区内: ' + (inside ? '✅ 是' : '❌ 否'));
console.log('');

// 生成新 XML：保留原有注释与颜色，只替换 pathData
let newXml = xml;
let cursor = 0;
let result = '';
for (const m of paths) {
  result += newXml.slice(cursor, m.index);
  result += 'android:pathData="' + out.find((o) => o.idx === paths.indexOf(m)).d + '"';
  cursor = m.index + m[0].length;
}
result += newXml.slice(cursor);

const dest = path.join(root, 'res/drawable/ic_launcher_foreground.xml');

// ★ 备份绝不能放在 res/ 里。
//   res/ 是整体打包进 APK 的目录，aapt2 连 `ic_launcher_foreground.xml.bak`
//   这种名字都会收进去（实测确实进了 APK）。备份统一放工程根的 _backup/。
const backupDir = path.join(root, '_backup');
const backup = path.join(backupDir, 'ic_launcher_foreground.xml.orig');
if (!fs.existsSync(backup)) {
  fs.mkdirSync(backupDir, { recursive: true });
  fs.writeFileSync(backup, xml, 'utf8');
  console.log('  原文件已备份到 _backup/ic_launcher_foreground.xml.orig');
}
fs.writeFileSync(dest, result, 'utf8');
console.log('  已写入 ' + path.relative(root, dest));

process.exit(inside ? 0 : 1);
