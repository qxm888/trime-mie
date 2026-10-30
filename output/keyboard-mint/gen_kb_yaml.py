#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成咩版自定义键盘 YAML（GPT 薄荷绿设计，五色方案）。

用法: python3 gen_kb_yaml.py [工具栏图标绝对路径所在目录]
默认工具栏图标目录 = /storage/emulated/0/rime/.mie/键盘背景/icons
键位图标使用相对路径 icons/...，与设备无关。
"""
import sys

TB_BASE = sys.argv[1] if len(sys.argv) > 1 else "/storage/emulated/0/rime/.mie/键盘背景/icons"

PALETTES = [
    ("mint",   "薄荷绿", "#F0F4EF", "#E2F2E7", "#72C99B", "#455B50"),
    ("pink",   "樱花粉", "#FCF5F6", "#FCEBED", "#F4AEB8", "#68565B"),
    ("blue",   "浅天蓝", "#F0F6FB", "#E7F3FC", "#96CBF4", "#4D6275"),
    ("orange", "杏子橙", "#FCF7F0", "#FFF0E2", "#F7BD99", "#725C4F"),
    ("purple", "薰衣紫", "#F7F3FC", "#F1E9FC", "#C9A6ED", "#61566F"),
]

def q(s):  # 带引号标量
    return '"' + s.replace('\\', '\\\\').replace('"', '\\"') + '"'

def keyboard_yml(kid, tag, label, kb_bg, fn_bg, enter_bg, icon_color):
    tb = TB_BASE.rstrip('/')
    L = []
    A = L.append
    A(f'  {kid}:')
    A(f'    name: {q(label)}')
    A(f'    keyboard_back_color: {q(kb_bg)}')
    A(f'    vertical_gap: 6')
    A(f'    horizontal_gap: 6')
    A(f'    mie:')
    A(f'      toolbarAlignment: {q("legacy")}')
    A(f'      rowMargin: 2')
    A(f'      keyAppearance:')
    A(f'        keyBackColor: {q("#FFFFFF")}')
    A(f'        keyTextColor: {q(icon_color)}')
    A(f'        roundCorner: 14')
    A(f'        keyBorder: 0')
    # 工具栏：左上切换键盘 + 右上 符号/剪贴板/中英/悬浮
    def btn(action, icon, size=30):
        A(f'      - action: {q(action)}')
        A(f'        enabled: true')
        A(f'        icon: {q(tb + "/" + icon)}')
        A(f'        iconSize: {size}')
        A(f'        appearance:')
        A(f'          keyBackColor: {q(fn_bg)}')
        A(f'          roundCorner: 12')
        A(f'          keyBorder: 0')
    A(f'      toolbar:')
    btn("menu_keyboard", "tb_switch.png")
    btn("liquid_keyboard", "tb_symbols.png")
    btn("clipboard_window", "tb_clipboard.png")
    btn("Mode_switch", "tb_lang.png")
    btn("__mie_toggle_floating_keyboard", "tb_float.png")
    # 键区
    A(f'    keys:')
    def key(label, width, row, click=None, back=None, icon=None, isize=28, offset=None, extra_ap=None):
        A(f'      - label: {q(label)}')
        A(f'        width: {width}')
        A(f'        height: 60')
        if click:
            A(f'        click: {q(click)}')
        if back:
            A(f'        key_back_color: {q(back)}')
        if icon:
            A(f'        icon: {q(icon)}')
            A(f'        icon_size: {isize}')
            if offset:
                A(f'        icon_offset_x: {offset[0]}')
                A(f'        icon_offset_y: {offset[1]}')
        if extra_ap:
            for k2, v2 in extra_ap.items():
                A(f'        {k2}: {v2}')
        A(f'        mie:')
        A(f'          row: {row}')
    for i, ch in enumerate("qwertyuiop"):
        key(ch, 10, 1, click=ch)
    for i, ch in enumerate("asdfghjkl"):
        key(ch, round(100 / 9, 6), 2, click=ch)
    key("", 15, 3, click="Shift_L", back=fn_bg, icon=f"icons/{tag}_shift.png")
    for ch in "zxcvbnm":
        key(ch, 10, 3, click=ch)
    key("", 15, 3, click="BackSpace", back=fn_bg, icon=f"icons/{tag}_delete.png")
    key("123", 15, 4, click="liquid_keyboard", back=fn_bg)
    key(",", 5, 4, click=",", back=fn_bg)
    key("空格", 60, 4, click="space")
    key("。", 5, 4, click="。")
    key("", 15, 4, click="Return", back=enter_bg, icon="icons/enter_w.png",
        extra_ap={"key_text_color": "#FFFFFF"})
    return "\n".join(L)

out = []
out.append("# 同文输入法咩版自定义键盘 —— GPT 薄荷绿设计（五色方案）")
out.append("# 导入：设置 → 键盘 → 布局管理与按键编辑 → 右上菜单 → 导入自定义布局")
out.append("# 图标：icons/ 文件夹需复制到  rime/.mie/键盘背景/  下（与本文件同发布）")
out.append("# 工具栏图标使用绝对路径；若 rime 目录不在 /storage/emulated/0/rime，")
out.append("# 请用 gen_kb_yaml.py 重新生成或手动替换各 toolbar 图标路径。")
out.append("# 工具栏动作：功能菜单 / 符号键盘 / 剪贴板 / 中英切换 / 悬浮键盘开关")
out.append("mie:")
out.append('  format: "trime-custom-keyboards-yaml"')
out.append("  version: 1")
out.append("preset_keyboards:")
for kid, label, kb_bg, fn_bg, enter_bg, icon_color in [
    (f"custom_{tag}", f"{cn}键盘", bg, fn, en, ic) for tag, cn, bg, fn, en, ic in PALETTES
]:
    tag, cn, bg, fn, en, ic = PALETTES[0] if False else (kid.replace("custom_", ""), label, kb_bg, fn_bg, enter_bg, icon_color)
    out.append(keyboard_yml(kid, tag, label, bg, fn, en, ic))
    out.append("")
open("薄荷绿键盘-五色方案.yaml", "w", encoding="utf-8").write("\n".join(out) + "\n")
print("written 薄荷绿键盘-五色方案.yaml")
