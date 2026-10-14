#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
校验自适应图标的位图资源（res/mipmap-*/ic_launcher_foreground.png）。

★ 为什么需要
   图标原来是矢量（res/drawable/ic_launcher_foreground.xml），由
   tools/validate-vector.js 校验路径语法与安全区。
   后来改用位图素材（取自 dsh-whale-widget 的 DSniang1.png），
   矢量文件被删除，但 run-tests.ps1 仍在检查那个不存在的文件，
   于是自检报"1 项未通过" —— 检查本身失效了，而不是代码有问题。

   位图图标要校验的东西和矢量完全不同，必须单独写：
     1. 每个密度档的文件是否存在
     2. 像素尺寸是否等于 108dp × 该密度倍数（自适应图标前景层的规格）
     3. 是否真有透明背景（前景层必须透明，否则会盖住背景层）
     4. 内容是否落在安全区（中心 66/108 = 61.1%）—— 越界会被启动器裁掉
     5. 内容是否居中

用法:
    python tools/validate-icon-assets.py [项目根目录]
退出码: 0 全部通过 / 1 有问题
"""
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from PIL import Image
except ImportError:
    print("需要 Pillow")
    sys.exit(1)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CANVAS_DP = 108
SAFE_DP = 66
SAFE_RATIO = SAFE_DP / CANVAS_DP          # 0.6111
ALPHA_THRESHOLD = 30
# 允许的尺寸误差（px）：取整造成的 1px 偏差是正常的
SIZE_TOL = 1
# 内容占画布的上限。安全区是 61.1%，留一点余量防止"刚好贴边"
MAX_CONTENT_RATIO = 0.62
# 居中偏差上限（占画布比例）
MAX_OFFCENTER = 0.06

# 必须存在的密度档。mdpi 起，xxxxhdpi 视体积取舍，这里只要求必需的。
REQUIRED = [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3)]
OPTIONAL = [('xxxhdpi', 4)]

problems = 0
checked = 0


def check_density(name, mult, required):
    global problems, checked
    d = os.path.join(ROOT, 'res', 'mipmap-' + name)
    p = os.path.join(d, 'ic_launcher_foreground.png')

    if not os.path.exists(p):
        if required:
            print('  [FAIL] 缺少必需密度 mipmap-%s' % name)
            problems += 1
        else:
            print('  [跳过] 可选密度 mipmap-%s 未提供（体积取舍）' % name)
        return

    checked += 1
    expect = int(round(CANVAS_DP * mult))
    im = Image.open(p)
    w, h = im.size

    if abs(w - expect) > SIZE_TOL or abs(h - expect) > SIZE_TOL:
        print('  [FAIL] mipmap-%-8s 尺寸 %dx%d，应为 %dx%d'
              % (name, w, h, expect, expect))
        problems += 1
        return

    rgba = im.convert('RGBA')

    # 透明背景检查：四角必须基本透明，否则会盖住背景层
    corners = [(0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1)]
    px = rgba.load()
    opaque_corners = sum(1 for (x, y) in corners if px[x, y][3] > ALPHA_THRESHOLD)
    if opaque_corners > 0:
        print('  [FAIL] mipmap-%-8s 四角有 %d 个不透明 —— 前景层必须透明背景'
              % (name, opaque_corners))
        problems += 1
    else:
        # 内容边界与安全区
        alpha = rgba.split()[3]
        box = alpha.point(lambda v: 255 if v > ALPHA_THRESHOLD else 0).getbbox()
        if not box:
            print('  [FAIL] mipmap-%-8s 整张图都是透明的（没有图形）' % name)
            problems += 1
            return
        cw, ch = box[2] - box[0], box[3] - box[1]
        rw, rh = cw / float(w), ch / float(h)
        cx = (box[0] + box[2]) / 2.0
        cy = (box[1] + box[3]) / 2.0
        offx = abs(cx - w / 2.0) / w
        offy = abs(cy - h / 2.0) / h

        bad = []
        if rw > MAX_CONTENT_RATIO:
            bad.append('横向占 %.1f%% > %.1f%%' % (rw * 100, MAX_CONTENT_RATIO * 100))
        if rh > MAX_CONTENT_RATIO:
            bad.append('纵向占 %.1f%% > %.1f%%' % (rh * 100, MAX_CONTENT_RATIO * 100))
        if offx > MAX_OFFCENTER:
            bad.append('横向偏心 %.1f%%' % (offx * 100))
        if offy > MAX_OFFCENTER:
            bad.append('纵向偏心 %.1f%%' % (offy * 100))

        if bad:
            print('  [FAIL] mipmap-%-8s %s' % (name, '；'.join(bad)))
            problems += 1
        else:
            print('  [ OK ] mipmap-%-8s %3dpx  内容 %dx%d (%.1f%%x%.1f%%)  居中偏差 %.1f%%/%.1f%%'
                  % (name, w, cw, ch, rw * 100, rh * 100, offx * 100, offy * 100))


def main():
    # ★ global 必须写在函数体**最前面**。
    #   Python 规定：同一个名字不能在赋值之后再声明为 global
    #   （否则报 "name 'problems' is assigned to before global declaration"）。
    global problems, checked

    print('  校验自适应图标位图资源（安全区 %.1f%%）' % (SAFE_RATIO * 100))
    for name, mult in REQUIRED:
        check_density(name, mult, True)
    for name, mult in OPTIONAL:
        check_density(name, mult, False)

    # 自适应图标描述文件必须引用 mipmap（而不是已删除的 drawable 矢量）
    ai = os.path.join(ROOT, 'res', 'mipmap-anydpi-v26', 'ic_launcher.xml')
    if not os.path.exists(ai):
        print('  [FAIL] 缺少 res/mipmap-anydpi-v26/ic_launcher.xml')
        problems += 1
    else:
        with open(ai, 'r', encoding='utf-8') as f:
            txt = f.read()
        if '@mipmap/ic_launcher_foreground' not in txt:
            print('  [FAIL] ic_launcher.xml 的前景未引用 @mipmap/ic_launcher_foreground')
            problems += 1
        elif '@drawable/ic_launcher_foreground' in txt:
            print('  [FAIL] ic_launcher.xml 仍引用已删除的 @drawable/ic_launcher_foreground')
            problems += 1
        else:
            print('  [ OK ] ic_launcher.xml 引用正确（@mipmap/ic_launcher_foreground）')

    # 反向检查：不应残留已废弃的矢量前景
    stale = os.path.join(ROOT, 'res', 'drawable', 'ic_launcher_foreground.xml')
    if os.path.exists(stale):
        print('  [WARN] res/drawable/ic_launcher_foreground.xml 仍然存在')
        print('         现在图标走位图，这个矢量文件若不被引用就是死资源，会白白进 APK')

    print('')
    if problems == 0:
        print('  检查 %d 个密度，全部通过' % checked)
        return 0
    print('  检查 %d 个密度，%d 个问题' % (checked, problems))
    return 1


if __name__ == '__main__':
    sys.exit(main())
