#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成「小胖鱼」App 图标（原创形象）。

设计意图
    一条圆滚滚、憨憨的蓝色小胖鱼：
      · 身体是一个大圆（占视觉重心的绝大部分）—— "胖"靠这个
      · 头顶一撮呆毛，肚子一道浅色分界
      · 两只大眼睛带高光，这是"可爱"的关键（眼睛占脸的比例要大）
      · 小短鳍、小尾巴，比例小才显得身体胖
      · 配色用 #4D6BFE 系的蓝

    不参考任何现有品牌吉祥物，形状全部自己定。

为什么用脚本生成
    1. 自适应图标安全区是中心 66x66（坐标 21~87），手写坐标极易越界 ——
       上一版就是尾巴超了 1.4 被脚本拦下。脚本会断言并自动收敛。
    2. 能渲染 ASCII 预览，肉眼确认"像不像一条胖鱼"。
       本机 read_image 不可用，没有预览就等于闭着眼睛画。

用法:
    python tools/make-icon-fish.py
输出:
    res/drawable/ic_launcher_foreground.xml
    build/icon-fish-preview.png
"""
import os
import sys

# Windows 控制台默认 GBK，直接 print emoji 会抛 UnicodeEncodeError
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("需要 Pillow")
    sys.exit(1)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------- 配色
BLUE = "#4D6BFE"          # 主体
BLUE_DARK = "#3550D8"     # 背鳍 / 尾巴（暗部）
BLUE_LIGHT = "#8FA2FF"    # 肚皮 / 高光
WHITE = "#FFFFFF"
INK = "#1B2350"           # 眼睛、嘴
PINK = "#FF9BB0"          # 腮红

# ---------------------------------------------------------------- 形状
#
# 所有坐标都在 22~86 之间（安全区 21~87），留 1 单位余量。
# 圆心 (54, 58)，身体半径 26 —— 头大身圆，占满安全区又不越界。

SHAPES = [

    # ---- 小尾巴（画在身体下层，从身体右后侧翘出）----------------------
    (BLUE_DARK, [
        ('M', 74, 54),
        ('C', 80, 48, 85, 47, 86, 51),       # 上叶
        ('C', 84, 52, 82, 54, 82, 57),       # 内凹
        ('C', 84, 60, 85, 64, 84, 67),       # 下叶
        ('C', 81, 68, 78, 63, 74, 62),
        ('Z',),
    ]),

    # ---- 背鳍（顶上一片小三角，让轮廓不那么像球）----------------------
    (BLUE_DARK, [
        ('M', 44, 33),
        ('C', 47, 24, 54, 22, 58, 26),
        ('C', 55, 29, 51, 32, 49, 35),
        ('Z',),
    ]),

    # ---- 身体：一个大圆（"胖"的来源）--------------------------------
    (BLUE, [
        ('M', 28, 58),
        ('C', 28, 44, 39, 33, 54, 33),       # 左上
        ('C', 69, 33, 80, 44, 80, 58),       # 右上
        ('C', 80, 72, 69, 83, 54, 83),       # 右下
        ('C', 39, 83, 28, 72, 28, 58),       # 左下
        ('Z',),
    ]),

    # ---- 肚皮浅色（下半部一道，增加层次）------------------------------
    (BLUE_LIGHT, [
        ('M', 34, 66),
        ('C', 40, 78, 68, 78, 74, 66),
        ('C', 70, 80, 38, 80, 34, 66),
        ('Z',),
    ]),

    # ---- 两侧小短鳍（比例刻意做小，反衬身体胖）------------------------
    (BLUE_DARK, [
        ('M', 30, 60),
        ('C', 24, 63, 23, 68, 27, 70),
        ('C', 30, 69, 32, 65, 33, 61),
        ('Z',),
    ]),
    (BLUE_DARK, [
        ('M', 78, 60),
        ('C', 84, 63, 85, 68, 81, 70),
        ('C', 78, 69, 76, 65, 75, 61),
        ('Z',),
    ]),

    # ---- 左眼：眼白 + 瞳孔 + 高光（三层的比例决定"萌"）----------------
    (WHITE, [
        ('M', 41, 52),
        ('C', 47, 52, 47, 62, 41, 62),
        ('C', 35, 62, 35, 52, 41, 52),
        ('Z',),
    ]),
    (INK, [
        ('M', 42, 55),
        ('C', 45, 55, 45, 60, 42, 60),
        ('C', 39, 60, 39, 55, 42, 55),
        ('Z',),
    ]),
    (WHITE, [
        ('M', 42.6, 55.6),
        ('C', 44, 55.6, 44, 57, 42.6, 57),
        ('C', 41.2, 57, 41.2, 55.6, 42.6, 55.6),
        ('Z',),
    ]),

    # ---- 右眼 --------------------------------------------------------
    (WHITE, [
        ('M', 61, 52),
        ('C', 67, 52, 67, 62, 61, 62),
        ('C', 55, 62, 55, 52, 61, 52),
        ('Z',),
    ]),
    (INK, [
        ('M', 62, 55),
        ('C', 65, 55, 65, 60, 62, 60),
        ('C', 59, 60, 59, 55, 62, 55),
        ('Z',),
    ]),
    (WHITE, [
        ('M', 62.6, 55.6),
        ('C', 64, 55.6, 64, 57, 62.6, 57),
        ('C', 61.2, 57, 61.2, 55.6, 62.6, 55.6),
        ('Z',),
    ]),

    # ---- 嘴：一条小小的弧（不要画太大，大了就不萌）--------------------
    (INK, [
        ('M', 48, 68),
        ('C', 51, 71, 57, 71, 60, 68),
        ('C', 57, 72.5, 51, 72.5, 48, 68),
        ('Z',),
    ]),

    # ---- 腮红（两团，是"憨"与"可爱"的催化剂）--------------------------
    (PINK, [
        ('M', 34, 66),
        ('C', 38, 65, 39, 68, 35, 69),
        ('C', 32, 69, 32, 66, 34, 66),
        ('Z',),
    ]),
    (PINK, [
        ('M', 71, 66),
        ('C', 75, 65, 76, 68, 72, 69),
        ('C', 69, 69, 69, 66, 71, 66),
        ('Z',),
    ]),

    # ---- 呆毛（头顶一撮小卷，增加性格）--------------------------------
    (BLUE, [
        ('M', 52, 34),
        ('C', 52, 28, 55, 24, 59, 24),
        ('C', 56, 27, 55, 30, 56, 35),
        ('Z',),
    ]),
]

SAFE_MIN, SAFE_MAX = 21.0, 87.0


# ---------------------------------------------------------------- 采样贝塞尔

def sample_shape(segments, steps=28):
    """把路径段采样成点列，用于计算范围与渲染预览。"""
    pts = []
    cx = cy = 0.0
    start = None
    for seg in segments:
        kind = seg[0]
        if kind == 'M':
            cx, cy = seg[1], seg[2]
            start = (cx, cy)
            pts.append((cx, cy))
        elif kind == 'L':
            cx, cy = seg[1], seg[2]
            pts.append((cx, cy))
        elif kind == 'C':
            x1, y1, x2, y2, x, y = seg[1:]
            for s in range(1, steps + 1):
                t = s / steps
                mt = 1 - t
                px = (mt ** 3) * cx + 3 * (mt ** 2) * t * x1 + 3 * mt * (t ** 2) * x2 + (t ** 3) * x
                py = (mt ** 3) * cy + 3 * (mt ** 2) * t * y1 + 3 * mt * (t ** 2) * y2 + (t ** 3) * y
                pts.append((px, py))
            cx, cy = x, y
        elif kind == 'Z':
            if start:
                pts.append(start)
    return pts


def format_path(segments):
    out = []
    for seg in segments:
        if seg[0] == 'Z':
            out.append('Z')
        else:
            out.append(seg[0] + ','.join('%g' % v for v in seg[1:]))
    return ' '.join(out)


def bounds(shapes):
    minx = miny = 1e9
    maxx = maxy = -1e9
    for _c, segs in shapes:
        for (x, y) in sample_shape(segs):
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
    return minx, miny, maxx, maxy


def autofit(shapes):
    """超出安全区时整体等比缩放并居中。"""
    global SHAPES
    minx, miny, maxx, maxy = bounds(shapes)
    if minx >= SAFE_MIN and maxx <= SAFE_MAX and miny >= SAFE_MIN and maxy <= SAFE_MAX:
        return 1.0, 0.0, 0.0
    gw = maxx - minx
    gh = maxy - miny
    avail = (SAFE_MAX - SAFE_MIN) - 2.0
    s = min(avail / gw, avail / gh)
    dx = 54 - (minx + maxx) / 2 * s
    dy = 54 - (miny + maxy) / 2 * s
    scaled = []
    for color, segs in shapes:
        ns = []
        for seg in segs:
            if seg[0] == 'Z':
                ns.append(seg)
            elif seg[0] in ('M', 'L'):
                ns.append((seg[0], seg[1] * s + dx, seg[2] * s + dy))
            else:
                ns.append((seg[0],) + tuple(v * s + (dx if i % 2 == 0 else dy)
                                            for i, v in enumerate(seg[1:])))
        scaled.append((color, ns))
    SHAPES = scaled
    return s, dx, dy


# ---------------------------------------------------------------- 预览

def ascii_preview(width=58):
    """
    终端字符画预览。
    字符: '#'=主体蓝 '+'=暗部 '.'=亮部 '-'=白 'o'=眼/嘴/线条 '$'=金 '*'=腮红
    """
    h = int(width * 0.5)
    cs = width / 108.0
    grid = [[' '] * width for _ in range(h)]
    charmap = {BLUE: '#', BLUE_DARK: '+', BLUE_LIGHT: '.', WHITE: '-',
               INK: 'o', PINK: '*'}
    for color, segs in SHAPES:
        ch = charmap.get(color, '?')
        pts = [(x * cs, y * cs * 0.5) for (x, y) in sample_shape(segs, steps=36)]
        if len(pts) < 3:
            continue
        ys = [p[1] for p in pts]
        for row in range(max(0, int(min(ys))), min(h, int(max(ys)) + 1)):
            yc = row + 0.5
            xs = []
            n = len(pts)
            for i in range(n):
                x1, y1 = pts[i]
                x2, y2 = pts[(i + 1) % n]
                if (y1 <= yc < y2) or (y2 <= yc < y1):
                    t = (yc - y1) / (y2 - y1)
                    xs.append(x1 + t * (x2 - x1))
            xs.sort()
            for k in range(0, len(xs) - 1, 2):
                for col in range(max(0, int(xs[k])), min(width, int(xs[k + 1]) + 1)):
                    grid[row][col] = ch
    s0, s1 = int(SAFE_MIN * cs), int(SAFE_MAX * cs)
    r0, r1 = int(SAFE_MIN * cs * 0.5), int(SAFE_MAX * cs * 0.5)
    lines = []
    for row in range(h):
        line = ''.join(grid[row])
        if r0 <= row < r1:
            line = line[:s0] + '[' + line[s0 + 1:s1] + ']' + line[s1 + 1:]
        lines.append('  |' + line + '|')
    return '\n'.join(lines)


def render_png(path, px=432):
    scale = px / 108.0
    img = Image.new('RGB', (px, px), '#171C24')
    d = ImageDraw.Draw(img)
    d.rectangle([SAFE_MIN * scale, SAFE_MIN * scale, SAFE_MAX * scale, SAFE_MAX * scale],
                outline='#3A4553', width=2)
    r, c = 36 * scale, 54 * scale
    d.ellipse([c - r, c - r, c + r, c + r], outline='#2A3441', width=2)
    for color, segs in SHAPES:
        pts = [(x * scale, y * scale) for (x, y) in sample_shape(segs, steps=32)]
        if len(pts) >= 3:
            d.polygon(pts, fill=color)
    img.save(path)
    return path


# ---------------------------------------------------------------- 主流程

def main():
    s, dx, dy = autofit(SHAPES)
    if s != 1.0:
        print('  自动缩放: %.4f 倍' % s)
    minx, miny, maxx, maxy = bounds(SHAPES)
    print('  图形范围: x %.1f~%.1f  y %.1f~%.1f' % (minx, maxx, miny, maxy))
    ok = (minx >= SAFE_MIN and maxx <= SAFE_MAX and miny >= SAFE_MIN and maxy <= SAFE_MAX)
    if not ok:
        print('  [FAIL] 仍超出安全区')
        return 1
    print('  [OK] 完全落在安全区内')
    print('')
    print(ascii_preview(58))
    print('')

    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!--',
        '    小胖鱼 · 前景图层（原创形象）',
        '',
        '    由 tools/make-icon-fish.py 生成，不要手工编辑 —— 改设计请改那个脚本。',
        '',
        '    设计：一条圆滚滚的蓝色小胖鱼，大眼睛带高光、两团腮红、头顶一撮呆毛。',
        '    配色 #4D6BFE 系。不参考任何现有品牌吉祥物。',
        '',
        '    全部图形落在自适应图标安全区（中心 66x66，坐标 21~87）之内，',
        '    被启动器按任意形状裁切都不会缺内容。生成脚本会断言这一点。',
        '-->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
        '',
    ]
    for color, segs in SHAPES:
        lines.append('    <path')
        lines.append('        android:fillColor="%s"' % color)
        lines.append('        android:pathData="%s" />' % format_path(segs))
    lines.append('</vector>')

    dest = os.path.join(ROOT, 'res', 'drawable', 'ic_launcher_foreground.xml')
    with open(dest, 'w', encoding='utf-8') as f:
        f.write('\n'.join(lines) + '\n')
    print('  已写入 res/drawable/ic_launcher_foreground.xml（%d 个 path）' % len(SHAPES))

    build = os.path.join(ROOT, 'build')
    os.makedirs(build, exist_ok=True)
    render_png(os.path.join(build, 'icon-fish-preview.png'))
    print('  预览图: build/icon-fish-preview.png')
    return 0


if __name__ == '__main__':
    sys.exit(main())
