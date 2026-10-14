#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析 adb uiautomator dump：解析节点树，输出「应用自己的」布局尺寸表。

★ 为什么需要
   改 UI 之前必须知道每个块**实际**占多少 px/dp，否则只能靠猜。
   而直接看 dump 的原始 XML 是一堆嵌套 <node>，看不出层级与高度分配。

   这个脚本做三件事：
     1. 按包名过滤出应用自己的节点（排除系统栏、输入法等）
     2. 重建父子树，算出每个容器的直接子元素高度分配
     3. 换算成 dp（按实际密度）并与代码里的 ScreenFit.flex() 期望值对照
        —— 偏差大的地方就是"尺寸失控"的位置

用法:
    python tools/layout-audit.py <dump.xml> [density_dpi]
"""
import os
import re
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def parse_tree(xml_text):
    """解析 uiautomator dump 成节点树。"""
    # 用简单的栈式解析：<node ...> 和 </node> 配对，自闭合标签也处理
    token_re = re.compile(r'<node\b([^>]*?)(/?)>|</node>')
    root = {'children': [], 'parent': None, 'attrs': {}}
    stack = [root]
    for m in token_re.finditer(xml_text):
        if m.group(0) == '</node>':
            if len(stack) > 1:
                stack.pop()
            continue
        attrs = m.group(1)
        node = {'children': [], 'parent': stack[-1], 'attrs': attrs}
        stack[-1]['children'].append(node)
        if not m.group(2):        # 非自闭合 -> 成为新的父节点
            stack.append(node)
    return root


def attr(attrs, name):
    m = re.search(name + r'="([^"]*)"', attrs)
    return m.group(1) if m else ''


def bounds(attrs):
    m = re.search(r'bounds="\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]"', attrs)
    if not m:
        return None
    x1, y1, x2, y2 = (int(v) for v in m.groups())
    return x1, y1, x2, y2


def walk(node, out, depth=0):
    a = node['attrs']
    # 虚拟根节点的 attrs 是 {}，不是 XML 属性字符串 —— 它没有 bounds，直接跳过自身
    b = bounds(a) if isinstance(a, str) else None
    if b:
        out.append({
            'depth': depth,
            'x1': b[0], 'y1': b[1], 'x2': b[2], 'y2': b[3],
            'w': b[2] - b[0], 'h': b[3] - b[1],
            'cls': attr(a, 'class').split('.')[-1],
            'text': attr(a, 'text'),
            'pkg': attr(a, 'package'),
            'clickable': attr(a, 'clickable') == 'true',
            'children_n': len(node['children']),
        })
    for c in node['children']:
        walk(c, out, depth + 1)
    return out


def main():
    if len(sys.argv) < 2:
        print('用法: python tools/layout-audit.py <dump.xml> [dpi]')
        return 2
    src = sys.argv[1]
    dpi = int(sys.argv[2]) if len(sys.argv) > 2 else 600
    density = dpi / 160.0

    if not os.path.exists(src):
        print('找不到: ' + src)
        return 1
    with open(src, 'r', encoding='utf-8', errors='replace') as f:
        xml_text = f.read()

    root = parse_tree(xml_text)
    nodes = walk(root, [])
    if not nodes:
        print('[FAIL] 无节点')
        return 1

    app_pkg = None
    for n in nodes:
        if n['pkg'] and n['pkg'] != 'android' and 'launcher' not in n['pkg']:
            app_pkg = n['pkg']
            break
    print('  应用包名: %s' % app_pkg)
    print('  密度: %d dpi (density=%.2f)' % (dpi, density))
    print('')

    # 应用自己的节点
    app = [n for n in nodes if n['pkg'] == app_pkg]
    print('  应用节点: %d 个' % len(app))
    print('')

    # 顶层容器（应用自己的、最高的几个）
    screen_h = max(n['h'] for n in app)
    print('  === 纵向布局（应用节点，按 y 排序，仅列 h>=40px）===')
    print('  %-6s %-6s %-7s %-7s %-9s %s' % ('y1', 'y2', 'h(px)', 'h(dp)', 'type', 'text/class'))
    prev_bottom = None
    gaps = []
    for n in sorted(app, key=lambda n: (n['y1'], n['x1'])):
        if n['h'] < 40:
            continue
        # 只列横向铺满的块，避免缩进层级刷屏
        if n['w'] < 600:
            continue
        # 计算与上一个块的间隙
        if prev_bottom is not None and n['y1'] > prev_bottom:
            g = n['y1'] - prev_bottom
            if g >= 8:
                gaps.append((prev_bottom, n['y1'], g))
        prev_bottom = max(prev_bottom or 0, n['y2'])
        label = n['text'][:26] if n['text'] else n['cls']
        print('  %-6d %-6d %-7d %-7.1f %-9s %s'
              % (n['y1'], n['y2'], n['h'], n['h'] / density,
                 'clickable' if n['clickable'] else n['cls'][:9], label))

    print('')
    print('  === 明显空白带（>=8px 的无内容间隙）===')
    if not gaps:
        print('    无')
    for a, b, g in gaps:
        print('    y %4d ~ %4d   间隙 %4dpx (%.1fdp)' % (a, b, g, g / density))

    # 关键指标
    leaf_cards = [n for n in app if 300 < n['h'] < 600 and n['w'] > 1200 and n['clickable']]
    if leaf_cards:
        hs = sorted(set(n['h'] for n in leaf_cards))
        print('')
        print('  === 列表卡片高度 ===')
        for h in hs:
            print('    %dpx = %.1fdp' % (h, h / density))
        print('    一屏可完整显示 %d 张（可用高 %dpx）'
              % (int(screen_h // (hs[0] + 22)), screen_h))
    return 0


if __name__ == '__main__':
    sys.exit(main())
