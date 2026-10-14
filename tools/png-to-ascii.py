#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 PNG 渲染成终端彩色字符画。

★ 为什么需要这个
   本机的 read_image 不可用（sharp 模块缺符号），我看不到任何图片。
   与其凭猜测描述一张图，不如把它转成字符画 ——
   这样"这张图是什么样的"就变成可核验的事实，而不是我的猜测。

   用 ANSI 24 位真彩色上色，终端里能看出颜色分布；
   同时给出亮度字符画（不依赖终端配色）以便在任何环境下阅读。

用法:
    python tools/png-to-ascii.py <图片路径> [宽度]
"""
import os
import sys

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow")
    sys.exit(1)

# 亮度 -> 字符（由暗到亮）
RAMP = " .:-=+*#%@"


def load_rgba(path, width):
    im = Image.open(path).convert("RGBA")
    # 按宽度等比缩放（终端字符高约为宽的 2 倍，纵向压缩一半）
    h = max(1, int(im.height * (width / im.width) * 0.5))
    im = im.resize((width, h), Image.LANCZOS)
    return im


def render(path, width=64, mode="color"):
    im = load_rgba(path, width)
    px = im.load()
    w, h = im.size

    lines = []
    for y in range(h):
        parts = []
        for x in range(w):
            r, g, b, a = px[x, y]
            if a < 30:
                parts.append("  " if mode == "color" else " ")
                continue
            if mode == "color":
                # 亮度决定字符，颜色用 ANSI 真彩色
                lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                ch = RAMP[min(len(RAMP) - 1, int(lum * len(RAMP)))]
                parts.append("\x1b[38;2;%d;%d;%dm%s\x1b[0m" % (r, g, b, ch, ch))
            else:
                # 纯色版本的蓝色通道做亮度：蓝越亮字越密
                lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
                parts.append(RAMP[min(len(RAMP) - 1, int(lum * len(RAMP)))])
        lines.append("".join(parts))
    return lines


def main():
    if len(sys.argv) < 2:
        print("用法: python tools/png-to-ascii.py <图片> [宽度] [--mono]")
        return 2
    path = sys.argv[1]
    width = int(sys.argv[2]) if len(sys.argv) > 2 and sys.argv[2].isdigit() else 64
    mono = "--mono" in sys.argv

    if not os.path.exists(path):
        print("找不到文件: " + path)
        return 1

    im = Image.open(path)
    print("  文件: %s" % os.path.basename(path))
    print("  原始尺寸: %dx%d  模式: %s" % (im.width, im.height, im.mode))
    print("  字符画宽度: %d 列" % width)
    print("")

    for line in render(path, width, "mono" if mono else "color"):
        print("  |" + line + "|")
    return 0


if __name__ == "__main__":
    sys.exit(main())
