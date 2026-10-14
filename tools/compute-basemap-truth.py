#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
独立算出底图数据的真值，用于生成/核对跨语言契约。

★ 为什么要"独立"算一遍

  Java 和 Swift 两侧都从同一份 .txt 解码。如果直接用 Java 的结果去写期望值，
  那么"契约测试通过"只证明**Swift 和 Java 一致**，
  不证明**两者都解对了**。两边可能用同样的方式理解错格式。

  所以这里用第三种实现（Python）按格式说明独立解一遍。
  三个实现互相印证，比两个互相印证强得多。

  这个脚本的产物会写进 testdata/vectors.json 的 basemap 段，
  然后 Java 和 Swift 两侧都断言自己算出来的是同一组值。

用法:
    python tools/compute-basemap-truth.py          输出真值
    python tools/compute-basemap-truth.py --json   输出可直接粘贴的 JSON 片段
"""
import json
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

SCALE = 1e5
CHAR_BASE = 0x30
CONTINUATION = 0x20
BASE = 32


def read_int(s, i):
    """
    读一个变长整数，返回 (值, 新位置)。

    格式（与 tools/basemap-codec.js 的 decodeInt 对应）：
      · 逐字符读，字符码 >= 0x50 表示"后面还有"
      · 每位是 5 位二进制，低位在前
      · 值的最低位是符号位：奇数 -> -(v-1)/2，偶数 -> v/2

    ★ 符号映射这里最容易写错。编码端写的是 2|v|+1，
      解码端如果写成 -(value+1)/2，所有负数都会差 1。
      tools/verify-codec.js 的往返测试就是为抓这个而留的。
    """
    value = 0
    pow_ = 1
    while i < len(s):
        code = ord(s[i])
        i += 1
        more = code >= CHAR_BASE + CONTINUATION
        digit = code - CHAR_BASE - (CONTINUATION if more else 0)
        value += digit * pow_
        pow_ *= BASE
        if not more:
            break
    return (-((value - 1) // 2) if (value & 1) else (value // 2)), i


def decode_line(line):
    """解码一行，返回 dict 或 None。"""
    parts = line.split("|", 5)
    if len(parts) < 2:
        return None
    typ = parts[0][0] if parts[0] else "?"
    body = parts[1]

    c = 0
    n, c = read_int(body, c)
    if n <= 0 or n > 20000:
        return None

    lats = []
    lons = []
    px = py = 0
    for _ in range(n):
        d, c = read_int(body, c)
        px += d
        e, c = read_int(body, c)
        py += e
        lons.append(px / SCALE)
        lats.append(py / SCALE)

    shape = {
        "type": typ,
        "closed": typ != "R",
        "isBoundary": typ == "B",
        "n": n,
        "lats": lats,
        "lons": lons,
    }
    if len(parts) >= 3:
        shape["local"] = parts[2] == "1"
    if len(parts) >= 4 and parts[3]:
        shape["labelLat"] = int(parts[3]) / SCALE
    else:
        shape["labelLat"] = lats[0]
    if len(parts) >= 5 and parts[4]:
        shape["labelLon"] = int(parts[4]) / SCALE
    else:
        shape["labelLon"] = lons[0]
    if len(parts) >= 6:
        shape["name"] = parts[5]
    return shape


def is_alpha(s):
    return len(s) > 0 and all(("a" <= ch <= "z") or ("A" <= ch <= "Z") for ch in s)


def load_file(path, want_closed):
    shapes = []
    vertices = 0
    bad = 0
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n").rstrip("\r")
            if not line:
                continue
            if line.startswith("XTLURE") or ("|" not in line and is_alpha(line)):
                continue
            s = decode_line(line)
            if s and len(s["lats"]) >= 2:
                shapes.append(s)
                vertices += len(s["lats"])
            else:
                bad += 1
    return shapes, vertices, bad


def compute():
    result = {}
    for key, fname in [("water", "basemap_water.txt"),
                       ("river", "basemap_river.txt"),
                       ("boundary", "basemap_boundary.txt")]:
        p = os.path.join(ROOT, "assets", fname)
        shapes, vertices, bad = load_file(p, key != "river")
        result[key] = {"count": len(shapes), "vertices": vertices,
                       "badLines": bad, "shapes": shapes}
    return result


def main():
    data = compute()
    print("")
    total_v = 0
    for k in ["water", "river", "boundary"]:
        d = data[k]
        total_v += d["vertices"]
        closed = sum(1 for s in d["shapes"] if s["closed"])
        named = sum(1 for s in d["shapes"] if s.get("name"))
        print("  %-9s 几何 %4d  顶点 %6d  闭合面 %4d  有名称 %3d  坏行 %d"
              % (k, d["count"], d["vertices"], closed, named, d["badLines"]))
    print("  %-9s 顶点合计 %d" % ("", total_v))

    # 全局经纬度范围
    min_la = min_lo = 1e9
    max_la = max_lo = -1e9
    for k in data:
        for s in data[k]["shapes"]:
            for la, lo in zip(s["lats"], s["lons"]):
                min_la = min(min_la, la); max_la = max(max_la, la)
                min_lo = min(min_lo, lo); max_lo = max(max_lo, lo)
    print("")
    print("  经纬度范围: %.5f ~ %.5f °N   %.5f ~ %.5f °E"
          % (min_la, max_la, min_lo, max_lo))

    # 取第一个水体几何的前 4 个顶点作为"逐点核对"样本 ——
    # 只看计数不够，计数对但坐标错位是可能的
    w0 = data["water"]["shapes"][0]
    print("")
    print("  样本（水系第 1 个几何）: 顶点 %d, closed=%s, name=%r"
          % (w0["n"], w0["closed"], w0.get("name", "")))
    for i in range(min(4, w0["n"])):
        print("    顶点%d: lat=%.5f lon=%.5f" % (i, w0["lats"][i], w0["lons"][i]))

    if "--json" in sys.argv:
        out = {
            "water": {"shapes": data["water"]["count"],
                      "vertices": data["water"]["vertices"]},
            "river": {"shapes": data["river"]["count"],
                      "vertices": data["river"]["vertices"]},
            "boundary": {"shapes": data["boundary"]["count"],
                         "vertices": data["boundary"]["vertices"]},
            "totalVertices": total_v,
            "bounds": [round(min_la, 4), round(min_lo, 4),
                       round(max_la, 4), round(max_lo, 4)],
            "sample": {"shapeIndex": 0, "vertexCount": w0["n"],
                       "closed": w0["closed"],
                       "vertices": [[round(w0["lats"][i], 5), round(w0["lons"][i], 5)]
                                    for i in range(min(4, w0["n"]))]},
        }
        print("")
        print(json.dumps(out, ensure_ascii=False, indent=6))
    return 0


if __name__ == "__main__":
    sys.exit(main())
