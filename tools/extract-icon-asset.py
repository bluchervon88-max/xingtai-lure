#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把一张 PNG 适配成 Android 自适应图标的前景图层（方案 A：用现成素材）。

★ 关键问题：素材直接当图标用会被裁掉一大截
   自适应图标的前景图层是 108x108dp，但只有中心 66x66dp（坐标 21~87，
   即画布的 61.1%）是**保证可见**的，其余会被启动器按形状裁掉。

   实测 DSniang1.png：内容占画布 92.6% x 98.4%，且内容中心 (327,310)
   偏离画布中心 (305,305)。直接放上去的结果是：边缘和下巴被切掉。

   所以这里做三件事：
     1. 按实际内容边界（alpha 阈值）裁剪，去掉四周空白
     2. 等比缩放到安全区，并居中到画布中心
     3. 按 Android 各密度导出，放到 mipmap-* 目录

用法:
    python tools/extract-icon-asset.py <源图片> [--bg 颜色] [--name 名字]
输出:
    res/mipmap-{m,h,xh,xxh,xxxh}dpi/ic_launcher_foreground.png
    build/asset-icon-preview.png
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

# 自适应图标：画布 108dp，安全区中心 66dp
CANVAS_DP = 108
SAFE_DP = 66
SAFE_RATIO = SAFE_DP / CANVAS_DP          # 0.6111
# 再留一点内边距，避免图形紧贴安全区边缘显得局促
INNER_MARGIN = 0.94

# Android 密度档位（相对 mdpi 的倍数）
DENSITIES = [
    ('mdpi', 1),
    ('hdpi', 1.5),
    ('xhdpi', 2),
    ('xxhdpi', 3),
    ('xxxhdpi', 4),
]

ALPHA_THRESHOLD = 30      # 低于此值视为透明背景


def content_bbox(im, threshold=ALPHA_THRESHOLD):
    """按 alpha 阈值求真实内容边界（不含半透明阴影外的空白）。"""
    alpha = im.split()[3]
    mask = alpha.point(lambda v: 255 if v > threshold else 0)
    return mask.getbbox()


def ascii_preview(im, width=54):
    """终端字符画预览，用于在无法看图的环境下核对结果。"""
    h = max(1, int(im.height * (width / im.width) * 0.5))
    small = im.convert('RGBA').resize((width, h), Image.LANCZOS)
    px = small.load()
    ramp = ' .:-=+*#%@'
    lines = []
    for y in range(h):
        row = []
        for x in range(width):
            r, g, b, a = px[x, y]
            if a < 30:
                row.append(' ')
                continue
            lum = (0.299 * r + 0.587 * g + 0.114 * b) / 255.0
            row.append(ramp[min(len(ramp) - 1, int(lum * len(ramp)))])
        lines.append('  |' + ''.join(row) + '|')
    return '\n'.join(lines)


def main():
    if len(sys.argv) < 2:
        print('用法: python tools/extract-icon-asset.py <源图片> [--bg #RRGGBB]')
        return 2

    src = sys.argv[1]
    bg = '#0E1116'
    if '--bg' in sys.argv:
        bg = sys.argv[sys.argv.index('--bg') + 1]

    if not os.path.exists(src):
        print('找不到源文件: ' + src)
        return 1

    im = Image.open(src).convert('RGBA')
    print('  源图: %s  %dx%d' % (os.path.basename(src), im.width, im.height))

    box = content_bbox(im)
    if not box:
        print('  [FAIL] 整张图都是透明的，没有内容')
        return 1
    print('  内容边界: %s  尺寸 %dx%d' % (box, box[2] - box[0], box[3] - box[1]))

    content = im.crop(box)
    cw, ch = content.size

    # 目标：内容填满安全区（再乘内边距），居中放在 108dp 画布上
    safe_px_ratio = SAFE_RATIO * INNER_MARGIN
    # 108dp 画布下，内容最长边应占 safe_px_ratio
    scale = safe_px_ratio / max(cw / CANVAS_DP, ch / CANVAS_DP)
    target_w = cw * scale
    target_h = ch * scale
    print('  缩放比: %.4f' % scale)
    print('  缩放后内容: %.1f x %.1f dp（画布 %d dp，安全区 %.1f dp）'
          % (target_w, target_h, CANVAS_DP, SAFE_DP))

    ok = target_w <= SAFE_DP + 0.5 and target_h <= SAFE_DP + 0.5
    print('  是否落在安全区内: %s' % ('是' if ok else '否 —— 仍会被裁'))
    if not ok:
        return 1

    # 逐密度导出
    out_dirs = []
    for name, mult in DENSITIES:
        size = int(round(CANVAS_DP * mult))
        canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        w = max(1, int(round(target_w * mult)))
        h = max(1, int(round(target_h * mult)))
        piece = content.resize((w, h), Image.LANCZOS)
        # 居中粘贴
        canvas.paste(piece, ((size - w) // 2, (size - h) // 2), piece)

        d = os.path.join(ROOT, 'res', 'mipmap-' + name)
        os.makedirs(d, exist_ok=True)
        dest = os.path.join(d, 'ic_launcher_foreground.png')
        canvas.save(dest, 'PNG', optimize=True)
        out_dirs.append((name, size, os.path.getsize(dest)))

    for name, size, nbytes in out_dirs:
        print('  mipmap-%-8s %3dpx  %6.1f KB' % (name, size, nbytes / 1024))

    # 预览：按最终效果合成（含圆形蒙版示意）
    build = os.path.join(ROOT, 'build')
    os.makedirs(build, exist_ok=True)
    prev_px = 432
    prev = Image.new('RGBA', (prev_px, prev_px), bg)
    canvas = Image.new('RGBA', (prev_px, prev_px), (0, 0, 0, 0))
    w = int(round(target_w / CANVAS_DP * prev_px))
    h = int(round(target_h / CANVAS_DP * prev_px))
    piece = content.resize((w, h), Image.LANCZOS)
    canvas.paste(piece, ((prev_px - w) // 2, (prev_px - h) // 2), piece)
    prev = Image.alpha_composite(prev, canvas)

    d = ImageDraw.Draw(prev)
    s0 = SAFE_RATIO and int((CANVAS_DP - SAFE_DP) / 2 / CANVAS_DP * prev_px)
    s1 = prev_px - s0
    d.rectangle([s0, s0, s1, s1], outline='#3A4553', width=2)
    r = int(SAFE_DP / 2 / CANVAS_DP * prev_px)
    c = prev_px // 2
    d.ellipse([c - r, c - r, c + r, c + r], outline='#2A3441', width=2)
    prev.convert('RGB').save(os.path.join(build, 'asset-icon-preview.png'))

    print('')
    print('  最终效果预览（方框 = 安全区，圆 = 圆形蒙版）:')
    print('')
    print(ascii_preview(prev.convert('RGBA'), 54))
    print('')
    print('  预览图: build/asset-icon-preview.png')
    print('')
    print('  ★ 提醒：本素材来自 dsh-whale-widget 插件，其 PROVENANCE.md 明确写着')
    print('    assets/** 不适用 MIT、不授予再许可、来源为不可考的 AI 生成图。')
    print('    请自行确认使用是否合适。')
    return 0


if __name__ == '__main__':
    sys.exit(main())
