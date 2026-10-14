// 底图顶点编码。
//
// ★ 为什么用 base-32 + 续位标记
//   最初想用 36 进制变长整数，但那样解码端**无法定界** ——
//   字符串里没有分隔符时，读到一个字符无法判断它是"独立的一个数"还是"更长的数的一部分"。
//
//   改成 base-32 + 续位标记后定界明确：
//     · 每位 5 位数（0~31）映射为字符 0x30+d，即 '0'~'O'（全是 ASCII 可打印字符）
//     · 除最后一位外，字符再 |0x20，得到 'P'~'o'
//       —— 因为 d <= 31，0x30+d <= 0x4F，置位后 <= 0x6F，始终落在可打印 ASCII 区间
//     · 解码时看字符码是否 >= 0x50 即可判断"后面还有"
//
//   顶点差值通常很小，一般 1~2 个字符。值映射：非负 v -> 2v；负数 v -> 2|v|+1。

const BASE = 32;
/** 数值 0~31 的基字符码起点 */
const CHAR_BASE = 0x30;
/** 续位标记：字符码加上它 */
const CONTINUATION = 0x20;

function encodeInt(n) {
  let u = n < 0 ? (-n * 2 + 1) : (n * 2);
  const digits = [];
  do {
    digits.push(u & 31);
    u = Math.floor(u / BASE);
  } while (u > 0);

  let s = '';
  for (let i = 0; i < digits.length; i++) {
    const isLast = i === digits.length - 1;
    s += String.fromCharCode(CHAR_BASE + digits[i] + (isLast ? 0 : CONTINUATION));
  }
  return s;
}

function decodeInt(s, pos) {
  let value = 0;
  let shift = 0;
  while (pos.i < s.length) {
    const code = s.charCodeAt(pos.i++);
    const more = code >= CHAR_BASE + CONTINUATION;
    value += (code - CHAR_BASE - (more ? CONTINUATION : 0)) * Math.pow(BASE, shift);
    shift++;
    if (!more) break;
  }
  // 与 encodeInt 严格互逆：
  //   非负 v -> 2v   =>  value 为偶数时 v = value/2
  //   负数 v -> 2|v|+1 = -2v+1  =>  value 为奇数时 v = -(value-1)/2
  return (value & 1) === 1 ? -((value - 1) / 2) : (value / 2);
}

/** 把一个顶点序列编成字符串：点数 + 各点的增量 */
function encodePath(points) {
  let s = encodeInt(points.length);
  let px = 0, py = 0;
  for (const [x, y] of points) {
    s += encodeInt(x - px) + encodeInt(y - py);
    px = x; py = y;
  }
  return s;
}

function makeCursor(s) {
  return { i: 0, s };
}

module.exports = { encodeInt, decodeInt, encodePath, makeCursor };
