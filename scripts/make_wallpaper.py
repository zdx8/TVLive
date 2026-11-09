#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 TVLive 主页壁纸（1920×1080，暗色暖棕 bloom）。

风格对标 Windows 11 Bloom 的暗色版：不是照片，而是「一束暖光在深色
空间里扩散」的抽象场。用于 Apple TV 式磁贴主页的整屏背景。

做法：多层高斯模糊椭圆叠加（近似 bloom），再压一层径向暗角，
最后轻微降对比 + 加颗粒，避免大屏上出现色带。

输出：app/src/main/res/drawable-nodpi/wallpaper.jpg（quality=90）

替换为真实壁纸：把任意 1920×1080 图片覆盖该文件即可，布局用
centerCrop，不会变形。
"""
import os
from PIL import Image, ImageDraw, ImageFilter, ImageEnhance

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, ".."))
OUT = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi", "wallpaper.jpg")

W, H = 1920, 1080
BASE = (7, 6, 9)  # 近黑带一点冷紫，让暖光更暖


def radial_bloom(size, center, radii, color, peak_alpha):
    """单个柔光椭圆：内部实心 + 大半径模糊，模拟光晕衰减。"""
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    cx, cy = center
    rx, ry = radii
    d.ellipse([cx - rx, cy - ry, cx + rx, cy + ry], fill=color + (peak_alpha,))
    return layer.filter(ImageFilter.GaussianBlur(min(rx, ry) * 0.55))


def vignette(img, strength=0.62):
    """径向暗角：中心不变，四周压暗。"""
    mask = Image.new("L", img.size, 0)
    d = ImageDraw.Draw(mask)
    cx, cy = img.size[0] / 2, img.size[1] / 2
    max_r = (cx ** 2 + cy ** 2) ** 0.5
    steps = 160
    for i in range(steps, 0, -1):
        r = max_r * i / steps
        v = int(255 * (1 - strength * (i / steps) ** 2.2))
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=v)
    mask = mask.filter(ImageFilter.GaussianBlur(40))
    dark = Image.new("RGB", img.size, (0, 0, 0))
    return Image.composite(img, dark, mask)


def main():
    img = Image.new("RGBA", (W, H), BASE + (255,))

    # 光层：暖棕主光（右下偏中）+ 冷灰补光（左上）+ 一点橙核
    layers = [
        ((W * 0.61, H * 0.43), (W * 0.60, H * 0.64), (82, 45, 22), 232),
        ((W * 0.57, H * 0.39), (W * 0.34, H * 0.38), (136, 76, 34), 196),
        ((W * 0.53, H * 0.35), (W * 0.17, H * 0.19), (178, 101, 47), 138),
        ((W * 0.22, H * 0.16), (W * 0.30, H * 0.34), (30, 34, 46), 90),
        ((W * 0.86, H * 0.80), (W * 0.28, H * 0.30), (52, 28, 14), 120),
    ]
    for center, radii, color, alpha in layers:
        img = Image.alpha_composite(img, radial_bloom((W, H), center, radii, color, alpha))

    out = vignette(img.convert("RGB"), strength=0.54)
    out = ImageEnhance.Contrast(out).enhance(0.96)
    out = ImageEnhance.Color(out).enhance(1.04)

    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    out.save(OUT, quality=90, optimize=True, progressive=True)
    print(f"wallpaper -> {os.path.relpath(OUT, ROOT)}  "
          f"{os.path.getsize(OUT) / 1024:.0f} KB  {out.size[0]}x{out.size[1]}")


if __name__ == "__main__":
    main()
