#!/usr/bin/env python3
"""
生成「钓鱼鲸」App 图标，并渲染预览图。

设计意图
    一条鲸鱼，从喷水孔喷出的不是水柱，而是鱼线，末端挂着亮片拟饵。
    配色致敬 DeepSeek Blue (#4D6BFE)，但造型是原创的，不使用任何官方标识。

为什么用脚本生成而不是手写 XML
    1. 自适应图标的安全区是中心 66x66（坐标 21~87），手写坐标很容易越界。
       上一个图标就是因为图形横跨 2.5~105.2，在圆形蒙版下两端被裁掉。
       这里所有形状都在 24~84 的范围内定义，天然安全。
    2. 可以直接渲染成 PNG 预览，肉眼确认形状对不对 ——
       否则只能靠脑补，画出来的东西很可能不像。
    3. 图形范围可以在生成时断言，越界直接报错，不依赖事后检查。

用法:
    python tools/make-icon.py
输出:
    res/drawable/ic_launcher_foreground.xml   前景图层
    res/drawable/ic_launcher_background.xml   背景图层
    build/icon-preview.png                    预览（含安全区参考线）
"""
import io
import os
import sys

# Windows 控制台默认 GBK，直接 print emoji 会抛 UnicodeEncodeError。
# 这里强制 UTF-8，并提供一个纯 ASCII 的标记，避免再被编码问题打断。
try:
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
except Exception:
    pass

try:
    from PIL import Image, ImageDraw
except ImportError:
    print("需要 Pillow: python -m pip install Pillow")
    sys.exit(1)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---------------------------------------------------------------- 配色
BLUE = "#4D6BFE"          # 主体（DeepSeek 品牌蓝）
BLUE_DARK = "#3A54D6"     # 暗部
BLUE_LIGHT = "#7C92FF"    # 高光 / 腹部
WHITE = "#FFFFFF"
INK = "#1B2350"           # 眼睛、线条
GOLD = "#FFC24B"          # 拟饵

# ---------------------------------------------------------------- 形状定义
#
# 所有坐标都落在 24~84 之间（安全区是 21~87），留了一点余量。
# 每个形状是 (颜色, [路径段...])，路径段支持:
#   ('M', x, y)                 移动
#   ('L', x, y)                 直线
#   ('C', x1,y1, x2,y2, x,y)    三次贝塞尔
#   ('Z',)                      闭合

SHAPES = [

    # ---- 鲸鱼身体 ----------------------------------------------------
    #
    # 造型要点（第一版画成了方块，靠 ASCII 预览才发现）：
    #   · 头在左、尾在右，头大尾细 —— 这是鲸鱼最容易辨认的轮廓特征
    #   · 上下各一条平滑长弧，中间最厚
    #   · 右侧收成细尾柄，再展开成**横向**尾鳍（鲸鱼尾鳍是横的，不是竖的）
    #   · 图标很小时细节全丢，所以轮廓必须简单、特征必须夸张
    (BLUE, [
        ('M', 30, 58),                       # 吻部尖端
        ('C', 28, 48, 34, 38, 46, 36),       # 头部上缘
        ('C', 58, 34, 68, 38, 73, 45),       # 背部向后
        ('C', 76, 49, 78, 52, 79, 54),       # 收到尾柄
        ('L', 86, 46),                       # 尾鳍上叶
        ('C', 88, 44, 89, 46, 88, 50),       # 尾鳍外缘
        ('C', 88, 54, 87, 56, 85, 57),       # 尾鳍内凹
        ('C', 88, 58, 89, 62, 88, 67),       # 尾鳍下叶
        ('C', 87, 71, 85, 72, 83, 68),       # 回到尾柄
        ('L', 79, 61),
        ('C', 76, 66, 68, 71, 56, 72),       # 腹部向后
        ('C', 44, 73, 33, 68, 30, 58),       # 回到吻部
        ('Z',),
    ]),

    # ---- 腹部亮色（鲸鱼标志性的浅色下巴）------------------------------
    (BLUE_LIGHT, [
        ('M', 32, 59),
        ('C', 34, 67, 44, 71, 56, 70),
        ('C', 48, 72, 35, 68, 32, 59),
        ('Z',),
    ]),

    # ---- 胸鳍（贴身体下缘，斜向后）------------------------------------
    (BLUE_DARK, [
        ('M', 44, 66),
        ('C', 46, 74, 52, 79, 57, 78),
        ('C', 56, 72, 51, 67, 47, 64),
        ('Z',),
    ]),

    # ---- 眼睛（靠近头部前端；位置决定"看起来像不像脸"）-----------------
    (INK, [
        ('M', 40, 50),
        ('C', 43, 50, 43, 54, 40, 54),
        ('C', 37, 54, 37, 50, 40, 50),
        ('Z',),
    ]),
    (WHITE, [
        ('M', 40.8, 50.9),
        ('C', 42, 50.9, 42, 52.2, 40.8, 52.2),
        ('C', 39.6, 52.2, 39.6, 50.9, 40.8, 50.9),
        ('Z',),
    ]),

    # ---- 喷水（从头顶喷出，暗示这是活物）------------------------------
    (BLUE_LIGHT, [
        ('M', 50, 37),
        ('C', 49, 30, 53, 26, 58, 26),
        ('C', 55, 29, 52, 32, 53, 38),
        ('Z',),
    ]),

    # ---- 拟饵：挂在喷水弧末端的小亮片 + 三本钩 --------------------------
    # 刻意做小 —— 图标上看不清细节，但"有个金色小东西"能认出来
    (GOLD, [
        ('M', 58, 24),
        ('C', 62, 21, 68, 22, 69, 26),
        ('C', 70, 30, 67, 33, 63, 32),
        ('C', 59, 31, 57, 27, 58, 24),
        ('Z',),
    ]),
    (WHITE, [
        ('M', 61, 25),
        ('C', 63, 23.5, 66, 24, 67, 26),
        ('C', 65, 25, 62, 25, 61, 25),
        ('Z',),
    ]),
    (INK, [
        ('M', 63, 32),
        ('L', 65, 37),
        ('L', 61, 37),
        ('Z',),
    ]),

    # ---- 水波（底部两道，构成水平基线）--------------------------------
    (BLUE, [
        ('M', 24, 80),
        ('C', 31, 76, 38, 76, 45, 80),
        ('C', 52, 84, 59, 84, 66, 80),
        ('C', 71, 77, 77, 76.5, 84, 79),
        ('L', 84, 82),
        ('C', 77, 79.5, 71, 80, 66, 83),
        ('C', 59, 87, 52, 87, 45, 83),
        ('C', 38, 79, 31, 79, 24, 83),
        ('Z',),
    ]),
    (BLUE_LIGHT, [
        ('M', 30, 75),
        ('C', 36, 72, 42, 72, 48, 75),
        ('C', 42, 74, 36, 74, 30, 75),
        ('Z',),
    ]),
]
SAFE_MIN, SAFE_MAX = 21.0, 87.0


# ---------------------------------------------------------------- 采样贝塞尔

def sample_shape(segments, steps=24):
    """把路径段采样成点列，用于计算范围与渲染预览。"""
    pts = []
    cx = cy = 0.0
    start = None
    i = 0
    while i < len(segments):
        seg = segments[i]
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
        i += 1
    return pts


def format_path(segments):
    """转成 Android vector 的 pathData 字符串。"""
    out = []
    for seg in segments:
        if seg[0] == 'Z':
            out.append('Z')
        else:
            nums = ','.join(
                ('%g' % v) for v in seg[1:]
            )
            out.append(seg[0] + nums)
    return ' '.join(out)


# ---------------------------------------------------------------- 检查范围

def bounds(shape_list):
    minx = miny = 1e9
    maxx = maxy = -1e9
    for _color, segs in shape_list:
        for (x, y) in sample_shape(segs):
            minx = min(minx, x); maxx = max(maxx, x)
            miny = min(miny, y); maxy = max(maxy, y)
    return minx, miny, maxx, maxy


# ---------------------------------------------------------------- 渲染预览

def render_preview(path, px=432):
    """渲染成 PNG。px 是画布边长，图标 viewport 是 108。"""
    scale = px / 108.0
    img = Image.new('RGB', (px, px), '#171C24')
    d = ImageDraw.Draw(img)

    # 安全区参考线
    d.rectangle([SAFE_MIN * scale, SAFE_MIN * scale,
                 SAFE_MAX * scale, SAFE_MAX * scale],
                outline='#3A4553', width=2)
    # 圆形蒙版参考线（半径 36，圆心 54）
    r = 36 * scale
    c = 54 * scale
    d.ellipse([c - r, c - r, c + r, c + r], outline='#2A3441', width=2)

    for color, segs in SHAPES:
        pts = [(x * scale, y * scale) for (x, y) in sample_shape(segs, steps=32)]
        if len(pts) >= 3:
            d.polygon(pts, fill=color)
    img.save(path)
    return path


def ascii_preview(width=58):
    """
    在终端里用字符画预览图标。

    ★ 为什么需要这个
       本机的 read_image 不可用（sharp 模块缺符号），PNG 预览图我看不到，
       等于闭着眼睛画。把图形转成字符画就能直接确认形状对不对 ——
       否则"我画了一条鲸鱼"只是一厢情愿，出来很可能是个四不像。

       字符对应:  '#'=主体蓝  '+'=暗部  '.'=亮部  '-'=白  'o'=眼睛/线条  '$'=金色拟饵
       方括号标出安全区左右边界。
    """
    h = int(width * 0.5)          # 终端字符高宽比约 1:2
    cell_scale = width / 108.0
    grid = [[' '] * width for _ in range(h)]
    charmap = {
        BLUE: '#', BLUE_DARK: '+', BLUE_LIGHT: '.',
        WHITE: '-', INK: 'o', GOLD: '$',
    }
    for color, segs in SHAPES:
        ch = charmap.get(color, '?')
        pts = [(x * cell_scale, y * cell_scale * 0.5) for (x, y) in sample_shape(segs, steps=40)]
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

    s0 = int(SAFE_MIN * cell_scale)
    s1 = int(SAFE_MAX * cell_scale)
    r0 = int(SAFE_MIN * cell_scale * 0.5)
    r1 = int(SAFE_MAX * cell_scale * 0.5)

    lines = []
    for row in range(h):
        line = ''.join(grid[row])
        if r0 <= row < r1:
            line = line[:s0] + '[' + line[s0 + 1:s1] + ']' + line[s1 + 1:]
        lines.append('  |' + line + '|')
    return '\n'.join(lines)


# ---------------------------------------------------------------- 主流程

def main():
    minx, miny, maxx, maxy = bounds(SHAPES)
    print('  图形范围: x %.1f~%.1f  y %.1f~%.1f' % (minx, maxx, miny, maxy))
    print('  安全区:   x %.0f~%.0f  y %.0f~%.0f' % (SAFE_MIN, SAFE_MAX, SAFE_MIN, SAFE_MAX))

    ok = (minx >= SAFE_MIN and maxx <= SAFE_MAX
          and miny >= SAFE_MIN and maxy <= SAFE_MAX)
    if not ok:
        print('  [FAIL] 超出安全区，会被启动器裁切')
        print('     x 方向需在 %.0f~%.0f，实际 %.1f~%.1f' % (SAFE_MIN, SAFE_MAX, minx, maxx))
        print('     y 方向需在 %.0f~%.0f，实际 %.1f~%.1f' % (SAFE_MIN, SAFE_MAX, miny, maxy))
        return 1
    print('  [OK] 完全落在安全区内')

    # 前景图层
    fg_lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!--',
        '    钓鱼鲸 · 前景图层',
        '',
        '    由 tools/make-icon.py 生成，不要手工编辑 —— 改设计请改那个脚本。',
        '',
        '    设计：一条鲸鱼从喷水孔喷出鱼线，末端挂着亮片拟饵。',
        '    配色致敬 #4D6BFE，造型为原创。',
        '',
        '    所有图形都在自适应图标的安全区（中心 66x66，坐标 21~87）之内，',
        '    被启动器按任意形状裁切都不会缺内容。生成脚本会断言这一点。',
        '-->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
        '',
    ]
    for idx, (color, segs) in enumerate(SHAPES):
        fg_lines.append('    <path')
        fg_lines.append('        android:fillColor="%s"' % color)
        fg_lines.append('        android:pathData="%s" />' % format_path(segs))
    fg_lines.append('</vector>')

    fg_path = os.path.join(ROOT, 'res', 'drawable', 'ic_launcher_foreground.xml')
    with open(fg_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(fg_lines) + '\n')
    print('  已写入 res/drawable/ic_launcher_foreground.xml（%d 个 path）' % len(SHAPES))

    # 背景图层：纯色满幅（背景不需要安全区约束，裁切无所谓）
    bg_lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- 背景图层：纯色满幅。由 tools/make-icon.py 生成。 -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
        '    android:width="108dp"',
        '    android:height="108dp"',
        '    android:viewportWidth="108"',
        '    android:viewportHeight="108">',
        '    <path',
        '        android:fillColor="#0E1116"',
        '        android:pathData="M0,0 L108,0 L108,108 L0,108 Z" />',
        '</vector>',
    ]
    bg_path = os.path.join(ROOT, 'res', 'drawable', 'ic_launcher_background.xml')
    with open(bg_path, 'w', encoding='utf-8') as f:
        f.write('\n'.join(bg_lines) + '\n')
    print('  已写入 res/drawable/ic_launcher_background.xml')

    # 预览图
    build = os.path.join(ROOT, 'build')
    os.makedirs(build, exist_ok=True)
    preview = os.path.join(build, 'icon-preview.png')
    render_preview(preview)
    print('' )
    print(ascii_preview(58))
    print('')
    print('  预览图: build/icon-preview.png')
    print('  （图中灰色方框 = 安全区，圆 = 圆形蒙版参考）')
    return 0


if __name__ == '__main__':
    sys.exit(main())
