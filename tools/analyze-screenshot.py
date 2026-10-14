#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析手机截图：主色统计 + 亮度字符画 + 布局分区估算。

★ 为什么需要
   本机 read_image 不可用（sharp 模块缺符号），我看不到截图。
   而"UI 好不好看"如果只靠 adb dump 的文字和 bounds 数字，
   是判断不出视觉问题的 —— 之前就是因为这个，UI 改了好几轮都不对。

   这个脚本把截图变成三样能读的东西：
     1. 主色统计  —— 看清配色是否协调、有没有大片突兀色块
     2. 字符画    —— 看清版面结构与卡片边界
     3. 纵向亮度曲线 —— 量化"哪里密、哪里空"，判断留白是否合理

用法:
    python tools/analyze-screenshot.py <截图.png> [字符画宽度]
"""
import os
import sys
from collections import Counter

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow")
    sys.exit(1)

RAMP = " .:-=+*#%@"


def hexc(c):
    return "#%02X%02X%02X" % c[:3]


def analyze(path, width=72):
    im = Image.open(path).convert("RGB")
    W, H = im.size
    print("  尺寸: %dx%d" % (W, H))

    # ---- 主色统计（量化到 16 级，降低噪声）----
    small = im.resize((W // 4, H // 4), Image.LANCZOS)
    px = small.load()
    cnt = Counter()
    for y in range(small.height):
        for x in range(small.width):
            r, g, b = px[x, y]
            cnt[(r // 16 * 16, g // 16 * 16, b // 16 * 16)] += 1
    total = small.width * small.height
    print("  主色（前 8）:")
    for c, n in cnt.most_common(8):
        bar = "#" * int(30.0 * n / total)
        print("    %s  %5.1f%%  %s" % (hexc(c), 100.0 * n / total, bar))

    # ---- 亮度字符画 ----
    h = max(1, int(H * (width / W) * 0.5))
    sm = im.resize((width, h), Image.LANCZOS)
    sp = sm.load()
    print("")
    print("  亮度字符画（暗=空格，亮=@）:")
    rows = []
    for y in range(h):
        line = []
        for x in range(width):
            r, g, b = sp[x, y]
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            line.append(RAMP[min(len(RAMP) - 1, int(lum * len(RAMP)))])
        rows.append("".join(line))
    for i, line in enumerate(rows):
        print("  %2d|%s|" % (i, line))

    # ---- 纵向亮度/方差曲线：判断"哪里密、哪里空" ----
    print("")
    print("  纵向内容密度（每行的亮度标准差，越高说明该行内容越丰富）:")
    for y in range(h):
        vals = []
        for x in range(width):
            r, g, b = sp[x, y]
            vals.append((0.299 * r + 0.587 * g + 0.114 * b) / 255.0)
        mean = sum(vals) / len(vals)
        var = sum((v - mean) ** 2 for v in vals) / len(vals)
        sd = var ** 0.5
        bar = "#" * int(sd * 90)
        # 标出对应的像素行，便于和 bounds 对照
        pix_y = int(y / h * H)
        print("    y=%4d  sd=%.3f  %s" % (pix_y, sd, bar))
    return 0


def main():
    if len(sys.argv) < 2:
        print("用法: python tools/analyze-screenshot.py <截图.png> [宽度]")
        return 2
    path = sys.argv[1]
    if not os.path.exists(path):
        print("找不到: " + path)
        return 1
    width = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 72
    return analyze(path, width)


if __name__ == "__main__":
    sys.exit(main())
