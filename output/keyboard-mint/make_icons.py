#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""工具栏图标：能用原图的直接用原图（颜色 92,92,94，和 App 自带一致），
只把「菜单（四宫格）」「收起（箭头）」按同样的颜色/笔画自绘。"""
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
OUT = HERE / "icons"
SRC = Path("/home/dovahkiin/Documents/靓企鹅图标")
GRAY = (92, 92, 94)  # 和 App 原有图标完全一致
SIZE = 148
W = 11  # 笔画粗细（原图约 10-11px / 148px）


def copy_icon(src: Path, dst: Path) -> None:
    im = Image.open(src).convert("RGBA")
    im = im.crop(im.getbbox() or (0, 0, im.width, im.height))
    scale = (SIZE - 4) / max(im.width, im.height)
    im = im.resize((max(1, int(im.width * scale)), max(1, int(im.height * scale))), Image.LANCZOS)
    canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    canvas.alpha_composite(im, ((SIZE - im.width) // 2, (SIZE - im.height) // 2))
    canvas.save(dst)


def draw_menu(dst: Path) -> None:
    """四宫格（功能菜单），和 App 的九宫格菜单同款。"""
    im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    m, gap = 12, 14
    cell = (SIZE - 2 * m - gap) // 2
    for i in range(2):
        for j in range(2):
            x = m + i * (cell + gap)
            y = m + j * (cell + gap)
            d.rounded_rectangle([x, y, x + cell, y + cell], radius=18,
                                outline=(*GRAY, 255), width=W)
    im.save(dst)


def draw_hide(dst: Path) -> None:
    """向下的箭头（收起键盘）。"""
    im = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    pts = [(32, 50), (74, 98), (116, 50)]
    d.line(pts, fill=(*GRAY, 255), width=W, joint="curve")
    for x, y in pts:
        d.ellipse([x - W // 2, y - W // 2, x + W // 2, y + W // 2], fill=(*GRAY, 255))
    im.save(dst)


if __name__ == "__main__":
    tmp = Image.open(HERE / "icons" / "tb_switch.png")
    tmp.load()
    copy_icon(SRC / "主题.png", OUT / "tb_theme.png")
    if Path("/tmp/opencode/tb_switch.bak.png").exists():
        copy_icon(Path("/tmp/opencode/tb_switch.bak.png"), OUT / "tb_layout.png")
    else:
        copy_icon(HERE / "icons" / "tb_switch.png", OUT / "tb_layout.png")
    copy_icon(HERE / "icons" / "tb_float.png", OUT / "tb_onehand.png")
    draw_menu(OUT / "tb_menu.png")
    draw_hide(OUT / "tb_hide.png")
    print("icons ok")
