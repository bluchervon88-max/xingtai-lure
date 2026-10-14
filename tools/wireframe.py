#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 adb uiautomator dump 的 XML 渲染成 HTML 线框图，再截图分析。

★ 为什么需要
   判断"UI 割裂、留白不合理"这类版面问题，看 adb 的 bounds 数字是看不出来的
   （一串 [45,719][229,868] 无法形成整体印象），而本机 read_image 又不可用。

   解法：把 dump 里的节点按真实坐标画成带色块的 HTML，用 headless 浏览器截图，
   再用 analyze-screenshot 转成字符画 —— 这样我就能"看见"版面结构：
   哪块大、哪块小、哪里空、视觉重心在哪。

   比直接看手机截图更好的地方：能按节点类型上色，
   卡片/按钮/文字/图的分布一目了然。

用法:
    python tools/wireframe.py <dump.xml> [输出目录]
输出:
    build/wireframe.html     线框图（带真实坐标）
    build/wireframe.png      截图（由调用方用浏览器生成）
"""
import os
import re
import sys
import html

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 按控件类别分配颜色，便于一眼看出结构
COLORS = {
    'card':    '#2A4A6A',   # 大块容器
    'button':  '#8A6A2A',   # 可点击
    'text':    '#3A5A3A',   # 文本
    'image':   '#5A3A5A',   # 图片/图标
    'input':   '#2A5A5A',   # 输入框
    'other':   '#333A44',
}


def parse_nodes(xml_text):
    """从 uiautomator dump 提取节点：bounds + 属性。"""
    nodes = []
    # 每个 node 标签
    for m in re.finditer(r'<node\b([^>]*?)/?>', xml_text):
        attrs = m.group(1)
        b = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', attrs)
        if not b:
            continue
        x1, y1, x2, y2 = (int(v) for v in b.groups())
        if x2 <= x1 or y2 <= y1:
            continue

        def attr(name):
            mm = re.search(name + r'="([^"]*)"', attrs)
            return mm.group(1) if mm else ''

        nodes.append({
            'x1': x1, 'y1': y1, 'x2': x2, 'y2': y2,
            'w': x2 - x1, 'h': y2 - y1,
            'text': attr('text'),
            'cls': attr('class').split('.')[-1],
            'clickable': attr('clickable') == 'true',
            'scrollable': attr('scrollable') == 'true',
        })
    return nodes


def classify(n, screen_w, screen_h):
    """判断节点类型，用于上色。"""
    area = n['w'] * n['h']
    screen_area = screen_w * screen_h
    ratio = area / screen_area if screen_area else 0

    # 很大的块 -> 容器/卡片
    if ratio > 0.03 and not n['text']:
        return 'card'
    if n['text'] and n['clickable']:
        return 'button'
    if n['text']:
        return 'text'
    if n['cls'] in ('ImageView', 'Image'):
        return 'image'
    if n['cls'] in ('EditText',):
        return 'input'
    if n['clickable']:
        return 'button'
    return 'other'


def main():
    if len(sys.argv) < 2:
        print('用法: python tools/wireframe.py <dump.xml> [输出目录]')
        return 2
    src = sys.argv[1]
    outdir = sys.argv[2] if len(sys.argv) > 2 else os.path.join(ROOT, 'build')
    if not os.path.exists(src):
        print('找不到: ' + src)
        return 1

    with open(src, 'r', encoding='utf-8', errors='replace') as f:
        xml_text = f.read()

    nodes = parse_nodes(xml_text)
    if not nodes:
        print('[FAIL] 没解析出节点')
        return 1

    # 屏幕尺寸取根节点
    root = max(nodes, key=lambda n: n['w'] * n['h'])
    SW, SH = root['w'], root['h']
    print('  屏幕: %dx%d，节点 %d 个' % (SW, SH, len(nodes)))

    # 统计各类型面积占比
    from collections import defaultdict
    by_kind = defaultdict(lambda: [0, 0])
    for n in nodes:
        k = classify(n, SW, SH)
        by_kind[k][0] += n['w'] * n['h']
        by_kind[k][1] += 1
    total_area = float(SW * SH)
    print('  各类别面积占比:')
    for k, (a, c) in sorted(by_kind.items(), key=lambda kv: -kv[1][0]):
        print('    %-8s %5.1f%%  (%d 个)' % (k, 100.0 * a / total_area, c))

    # 生成 HTML（按真实像素缩放）
    scale = 0.5
    parts = [
        '<!DOCTYPE html><html><head><meta charset="utf-8"><style>',
        'body{margin:0;background:#0A0D12;}',
        '#screen{position:relative;width:%dpx;height:%dpx;background:#0E1116;'
        'font:9px/1.1 monospace;overflow:hidden;}' % (SW * scale, SH * scale),
        '.n{position:absolute;box-sizing:border-box;border:1px solid rgba(255,255,255,.18);'
        'overflow:hidden;color:#fff;padding:1px;white-space:nowrap;}',
    ]
    for k, c in COLORS.items():
        parts.append('.%s{background:%s;}' % (k, c))
    parts.append('</style></head><body><div id="screen">')

    for n in nodes:
        k = classify(n, SW, SH)
        label = html.escape(n['text'][:28]) if n['text'] else n['cls'][:20]
        # 太小的块不画标签，避免糊成一团
        show = n['h'] * scale > 11 and n['w'] * scale > 26
        style = ('left:%.1fpx;top:%.1fpx;width:%.1fpx;height:%.1fpx;'
                 % (n['x1'] * scale, n['y1'] * scale, n['w'] * scale, n['h'] * scale))
        parts.append('<div class="n %s" style="%s">%s</div>'
                     % (k, style, label if show else ''))

    parts.append('</div></body></html>')

    os.makedirs(outdir, exist_ok=True)
    dest = os.path.join(outdir, 'wireframe.html')
    with open(dest, 'w', encoding='utf-8') as f:
        f.write('\n'.join(parts))
    print('  已写入 ' + os.path.relpath(dest, ROOT))

    # 同时输出各节点按 y 排序的清单，便于和字符画对照
    print('')
    print('  节点清单（按 y 排序，仅列高度 > 40px 的块）:')
    for n in sorted(nodes, key=lambda n: (n['y1'], n['x1'])):
        if n['h'] < 40:
            continue
        k = classify(n, SW, SH)
        print('    y=%4d~%4d  h=%4d  %-8s %s'
              % (n['y1'], n['y2'], n['h'], k,
                 (n['text'][:30] if n['text'] else n['cls'])))
    return 0


if __name__ == '__main__':
    sys.exit(main())
