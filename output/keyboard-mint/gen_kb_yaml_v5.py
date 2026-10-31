#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""薄荷五色键盘 v5：按用户效果图还原。

- 近白键盘底 + 白色键 + 细薄荷描边
- 小圆角（6dp）、键高 36dp（符号/表情 32dp）
- 顶部工具栏：左侧键盘中心 + 右侧 符号/切换/剪贴板/中英/悬浮
- 主键盘底行 5 键：123 / , / 空格 / 。/ ↵（⌨ 移到顶部左侧）
- 保留长按功能，用 label_symbol=" " 隐藏键顶自动标签
"""
from __future__ import annotations

import copy
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
TB_BASE = (sys.argv[1] if len(sys.argv) > 1 else "/storage/emulated/0/rime-mie/.mie/键盘背景/icons").rstrip("/")
IN = HERE / "薄荷绿键盘-五色方案-v4.yaml"
OUT = HERE / "薄荷绿键盘-五色方案-v5.yaml"

# tag: (近白底, 功能键底, 回车底, 文字色, 描边)
PALETTES = {
    "mint":   ("#F7F9F8", "#E6F2EA", "#7BC8A4", "#3F5A4E", "#D7E6DD"),
    "pink":   ("#FBF7F8", "#FCEBED", "#F4AEB8", "#5E4B50", "#EAD4D8"),
    "blue":   ("#F7F9FB", "#E7F3FC", "#96CBF4", "#43586B", "#D3E4F0"),
    "orange": ("#FBF8F5", "#FFF0E2", "#F7BD99", "#6B564A", "#EFDCCB"),
    "purple": ("#F9F7FC", "#F1E9FC", "#C9A6ED", "#574B66", "#E0D3EE"),
}

WHITE = "#FFFFFF"
TRANS = "#00000000"


def muted(hex_color: str) -> str:
    h = hex_color.lstrip("#")
    return "#99" + h if len(h) == 6 else hex_color


def effects() -> dict:
    return {
        "shadow": {"enabled": False},
        "innerShadow": {"enabled": False},
        "gradient": {"enabled": False},
        "borderGradient": {"enabled": False},
        "textShadow": {"enabled": False},
        "gloss": False,
        "noise": False,
        "edge3d": False,
        "pressScale": 0.0,
        "pressSink": 0.0,
        "pressDarken": 6,
        "ripple": False,
    }


def surface(bg: str, radius: float) -> dict:
    return {
        "keys": {"background": "", "borderWidth": 0.0, "borderColor": "", "radius": radius, "blur": False},
        "toolbar": {"background": "", "borderWidth": 0.0, "borderColor": "", "radius": 8.0, "blur": False},
        "bar": {"background": bg, "borderWidth": 0.0, "borderColor": "", "radius": 0.0, "blur": False},
        "linkToolbar": False,
        "effects": effects(),
        "toolbarEffects": effects(),
        "barEffects": effects(),
    }


def toolbar(tag: str, fn_bg: str) -> list[dict]:
    def btn(action: str, icon: str, bg: str) -> dict:
        return {
            "action": action,
            "enabled": True,
            "icon": icon,
            "iconSize": 20,
            "width": 30,
            "height": 30,
            "appearance": {"keyBackColor": bg, "roundCorner": "8", "keyBorder": "0"},
        }
    return [
        # 左侧：键盘中心（primary 会放在最左边）
        {**btn(f"Keyboard_custom_{tag}_hub", "⌨", fn_bg), "primary": True},
        # 右侧：符号 / 切换 / 剪贴板 / 中英 / 悬浮
        btn(f"Keyboard_custom_{tag}_symbols", f"{TB_BASE}/tb_symbols.png", fn_bg),
        btn("Keyboard_switch", f"{TB_BASE}/tb_switch.png", TRANS),
        btn("clipboard_window", f"{TB_BASE}/tb_clipboard.png", TRANS),
        btn("Mode_switch", f"{TB_BASE}/tb_lang.png", TRANS),
        btn("__mie_toggle_floating_keyboard", f"{TB_BASE}/tb_float.png", fn_bg),
    ]


def set_font(key: dict, size: str) -> None:
    key["key_text_size"] = size


def set_icon(key: dict, size: int = 18) -> None:
    if key.get("icon"):
        key["icon_size"] = size
        key["key_border"] = "0" if key.get("click") == "Return" or "enter_w" in str(key.get("icon")) else "1"


def transform_key(key: dict, kid: str, tag: str, pal: tuple, row: int, max_row: int) -> None:
    bg_near, fn_bg, enter_bg, icon, border = pal
    label = key.get("label", "")
    click = key.get("click", "")
    is_enter = click == "Return" or "enter_w" in str(key.get("icon"))
    is_title = bg_near and key.get("key_back_color") == TRANS and label == "选择键盘"

    # 颜色：功能键 / 回车 / 白键 / 透明
    if is_enter or label == "26键":
        key["key_back_color"] = enter_bg
        key["key_text_color"] = WHITE
    elif is_title:
        key["key_back_color"] = TRANS
        key["key_text_color"] = muted(icon)
    elif key.get("key_back_color") in (WHITE, TRANS):
        key["key_back_color"] = key.get("key_back_color", WHITE)
        key["key_text_color"] = muted(icon) if label == "空格" else icon
    else:
        key["key_back_color"] = fn_bg
        key["key_text_color"] = icon

    # 底部标点：, 用白键（效果图里逗号是白底细描边）
    if row == max_row and label in (",", "，"):
        key["label"] = ","
        key["key_back_color"] = WHITE
        key["key_text_color"] = icon
    if row == max_row and label in ("。", "．"):
        key["label"] = "。"
        key["key_back_color"] = WHITE
        key["key_text_color"] = icon

    # 圆角 / 描边
    key["round_corner"] = "0" if is_title else ("8" if kid.endswith("_hub") else "4")
    if is_enter or is_title:
        key["key_border"] = "0"
    else:
        key["key_border"] = "1"
    key["key_border_color"] = border

    # 字号
    if key.get("icon"):
        set_icon(key)
        return
    if kid.endswith("_hub"):
        set_font(key, "11" if label == "选择键盘" else "14")
    elif label == "123":
        set_font(key, "12")
    elif label in (",", "。"):
        set_font(key, "14")
    elif label == "空格":
        set_font(key, "12")
    elif label == "返回" or label == "符号":
        set_font(key, "11")
    elif label == "选择键盘":
        set_font(key, "11")
    elif label in ("26键", "9键", "14键", "18键", "数字", "符号", "表情"):
        set_font(key, "14")
    elif kid.endswith("_9"):
        set_font(key, "13")
    elif len(label) > 1 and label.isascii():
        set_font(key, "14")
    elif label and label[0].isdigit():
        set_font(key, "16" if len(label) == 1 else "14")
    elif kid.endswith("_symbols"):
        set_font(key, "14")
    elif kid.endswith("_emoji"):
        set_font(key, "18")
    elif kid.endswith(("_14", "_18")):
        set_font(key, "14")
    else:
        set_font(key, "15")


def main() -> None:
    doc = yaml.safe_load(IN.read_text(encoding="utf-8"))
    kbs = doc["preset_keyboards"]

    for kid, kb in kbs.items():
        tag = kid.split("_")[1]
        if tag not in PALETTES:
            continue
        pal = PALETTES[tag]
        bg_near, fn_bg, enter_bg, icon, border = pal
        is_hub = kid.endswith("_hub")

        kb["keyboard_back_color"] = bg_near
        kb["vertical_gap"] = 4
        kb["horizontal_gap"] = 4
        mie = kb.setdefault("mie", {})
        mie["rowMargin"] = 0
        mie["toolbarAlignment"] = "legacy"
        mie["toolbarHeightDp"] = 30
        mie["toolbar"] = toolbar(tag, fn_bg)
        mie["keyAppearance"] = {
            "keyBackColor": WHITE,
            "keyTextColor": icon,
            "roundCorner": "8" if is_hub else "4",
            "keyBorder": "1",
            "keyBorderColor": border,
            "keyTextSize": "15",
        }
        mie["surfaceAppearance"] = surface(bg_near, 8.0 if is_hub else 4.0)

        rows: dict[int, list[dict]] = {}
        for key in kb["keys"]:
            rows.setdefault(key["mie"]["row"], []).append(key)
        max_row = max(rows) if rows else 1

        # 移除底行 ⌨（主/9/14/18/符号/表情），数字页的 ⌨ 改成「符号」
        new_keys: list[dict] = []
        for key in kb["keys"]:
            label = key.get("label", "")
            click = key.get("click", "")
            if label == "⌨" and click.endswith("_hub"):
                if kid.endswith("_num"):
                    key["label"] = "符号"
                    key["click"] = f"Keyboard_custom_{tag}_symbols"
                    key["key_text_size"] = "11"
                    key["key_back_color"] = fn_bg
                    key["key_text_color"] = icon
                    new_keys.append(key)
                continue
            new_keys.append(key)
        kb["keys"] = new_keys

        # 重新分行并改高度 / 字体 / 颜色
        rows = {}
        for key in kb["keys"]:
            rows.setdefault(key["mie"]["row"], []).append(key)
        max_row = max(rows) if rows else 1
        for row, keys in rows.items():
            if is_hub:
                h = 32 if row == 1 else 36
            elif kid.endswith(("_symbols", "_emoji")):
                h = 32
            else:
                h = 36
            for key in keys:
                key["height"] = h
                transform_key(key, kid, tag, pal, row, max_row)
                if key.get("long_click"):
                    key["label_symbol"] = " "

        # 底行宽度：主/9/14/18 = 14/8/56/8/14；符号/表情 = 18/52/15/15
        if max_row in rows:
            bottom = [k for k in rows[max_row] if k.get("label") not in ("⌨",)]
            labels = [k.get("label") for k in bottom]
            if labels[:1] == ["123"] and "空格" in labels and len(bottom) == 5:
                for k, w in zip(bottom, [14, 8, 56, 8, 14]):
                    k["width"] = w
            elif labels[:1] == ["123"] and "返回" in labels and len(bottom) == 4:
                for k, w in zip(bottom, [18, 52, 15, 15]):
                    k["width"] = w

        # 主键盘 / 9 / 14 / 18 底行：确保 5 键且宽度和 100
        if not is_hub and not kid.endswith(("_symbols", "_emoji", "_num")):
            bottom = [k for k in kb["keys"] if k["mie"]["row"] == max_row]
            if len(bottom) == 5:
                for k, w in zip(bottom, [14, 8, 56, 8, 14]):
                    k["width"] = w

    out = {
        "mie": {"format": "trime-custom-keyboards-yaml", "version": 1},
        "preset_keyboards": kbs,
    }
    header = (
        "# 同文输入法咩版自定义键盘 —— 薄荷五色 · 按效果图还原\n"
        "# 白色键 + 细薄荷描边 + 小圆角；顶部左侧键盘中心，右侧工具组\n"
        f"# 工具栏图标：{TB_BASE}/tb_*.png\n"
    )
    OUT.write_text(header + yaml.safe_dump(out, allow_unicode=True, sort_keys=False, default_flow_style=False, width=1200), encoding="utf-8")
    print(f"written {OUT} ({OUT.stat().st_size} bytes, {len(kbs)} keyboards)")


if __name__ == "__main__":
    main()
