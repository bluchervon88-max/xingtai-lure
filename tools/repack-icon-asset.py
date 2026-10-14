#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
重新导出图标 PNG，并用调色板量化压缩体积。

★ 为什么要压缩
   每密度位图会让 APK 明显变大（本次从 277 KB 涨到 441 KB，图标占 169 KB）。
   而这个 App 的一个卖点就是体积小（不依赖任何第三方库、离线可用）。

   实测该素材在 xxxhdpi 下量化到 64 色只要 13.4 KB，而原图 71.1 KB。

★ 为什么敢用 64 色
   不是凭感觉，而是算 RMSE（均方根误差）：
   量化前后逐像素比较，误差足够小才采用。卡通角色图色块大、渐变少，
   通常 64 色已经看不出差别；如果是照片就不敢这么压。
   脚本会把误差打印出来，超阈值直接放弃量化、保留原图。

用法:
    python tools/repack-icon-asset.py <源图片>
输出:
    res/mipmap-*/ic_launcher_foreground.png（量化压缩后）
"""
import os
import sys

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

try:
    from PIL import Image, ImageChops
except ImportError:
    print("需要 Pillow")
    sys.exit(1)

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CANVAS_DP = 108
SAFE_DP = 66
SAFE_RATIO = SAFE_DP / CANVAS_DP
INNER_MARGIN = 0.94
ALPHA_THRESHOLD = 30
DENSITIES = [('mdpi', 1), ('hdpi', 1.5), ('xhdpi', 2), ('xxhdpi', 3), ('xxxhdpi', 4)]

# 量化色数候选：从少到多，取第一个满足误差要求的
PALETTE_CANDIDATES = [32, 64, 128, 256]
# RMSE 上限（0~255）。卡通图用 3.0 很保守 —— 照片通常需要 5 以上才敢用。
RMSE_LIMIT = 3.0

BG = (14, 17, 22)      # #0E1116，与图标背景一致


def rmse(a, b):
    """两个同尺寸 RGB 图的均方根误差。"""
    diff = ImageChops.difference(a, b)
    h = diff.histogram()
    total = 0
    count = 0
    for band in range(3):
        for i in range(256):
            n = h[band * 256 + i]
            total += n * (i ** 2)
            count += n
    return (total / count) ** 0.5 if count else 0.0


def main():
    if len(sys.argv) < 2:
        print('用法: python tools/repack-icon-asset.py <源图片>')
        return 2
    src = sys.argv[1]
    if not os.path.exists(src):
        print('找不到: ' + src)
        return 1

    im = Image.open(src).convert('RGBA')
    box = im.split()[3].point(lambda v: 255 if v > ALPHA_THRESHOLD else 0).getbbox()
    if not box:
        print('[FAIL] 全透明')
        return 1
    content = im.crop(box)
    cw, ch = content.size
    scale = (SAFE_RATIO * INNER_MARGIN) / max(cw / CANVAS_DP, ch / CANVAS_DP)
    target_w, target_h = cw * scale, ch * scale
    print('  源图内容 %dx%d -> 缩放后 %.1f x %.1f dp（安全区 %.0f dp）'
          % (cw, ch, target_w, target_h, SAFE_DP))

    total_before = 0
    total_after = 0
    failed = False

    for name, mult in DENSITIES:
        size = int(round(CANVAS_DP * mult))
        w = max(1, int(round(target_w * mult)))
        h = max(1, int(round(target_h * mult)))

        # 先把内容合成到深色底上，得到"最终视觉"的 RGB 图
        # （半透明边缘合成后再量化，避免半透明区域被单独量化出杂色）
        piece = content.resize((w, h), Image.LANCZOS)
        canvas = Image.new('RGBA', (size, size), (0, 0, 0, 0))
        canvas.paste(piece, ((size - w) // 2, (size - h) // 2), piece)
        flat = Image.new('RGB', (size, size), BG)
        flat.paste(canvas, (0, 0), canvas)

        dest_dir = os.path.join(ROOT, 'res', 'mipmap-' + name)
        os.makedirs(dest_dir, exist_ok=True)
        dest = os.path.join(dest_dir, 'ic_launcher_foreground.png')
        before = os.path.getsize(dest) if os.path.exists(dest) else 0

        # 逐个色数试，取第一个误差达标且体积最小的
        chosen = None
        for ncol in PALETTE_CANDIDATES:
            q = flat.quantize(colors=ncol, method=Image.FASTOCTREE)
            back = q.convert('RGB')
            err = rmse(flat, back)
            if err <= RMSE_LIMIT:
                chosen = (ncol, q, err)
                break
            print('    %-9s %3d 色误差 %.2f 超过上限 %.1f，继续加色'
                  % (name, ncol, err, RMSE_LIMIT))

        if chosen is None:
            # 全部候选都超标 -> 保留未量化版本
            flat.save(dest, 'PNG', optimize=True)
            err, ncol = -1, -1
        else:
            ncol, q, err = chosen
            # 转回 RGBA 以保留 alpha 通道（自适应图标前景需要透明背景）
            rgba = q.convert('RGBA')
            # 把"深色底"的区域重新变透明：用原 canvas 的 alpha 做遮罩
            alpha = canvas.split()[3]
            rgba.putalpha(alpha)
            rgba.save(dest, 'PNG', optimize=True)

        after = os.path.getsize(dest)
        total_before += before
        total_after += after
        err_txt = ('%.2f' % err) if err >= 0 else '保留原图'
        print('  %-9s %3dpx  %6.1f KB -> %6.1f KB  %3s 色  RMSE %s'
              % (name, size, before / 1024, after / 1024,
                 ncol if ncol > 0 else '-', err_txt))

    print('')
    print('  合计 %.1f KB -> %.1f KB（省 %.1f KB，%.0f%%）'
          % (total_before / 1024, total_after / 1024,
             (total_before - total_after) / 1024,
             100.0 * (total_before - total_after) / max(1, total_before)))
    return 0 if not failed else 1


if __name__ == '__main__':
    sys.exit(main())
