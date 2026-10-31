#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""薄荷绿五色键盘 v2：扁平外观 + 清羽全套次级键盘（5 色 × 8 布局）。

用法:
  python3 gen_kb_yaml_v2.py [工具栏图标绝对路径目录]

默认图标目录 = /storage/emulated/0/rime-mie/.mie/键盘背景/icons
键位图标使用相对路径 icons/...，与设备无关。
"""
from __future__ import annotations

import copy
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
TB_BASE = (sys.argv[1] if len(sys.argv) > 1 else "/storage/emulated/0/rime-mie/.mie/键盘背景/icons").rstrip("/")
MINT_YAML = HERE / "薄荷绿键盘-五色方案.yaml"
QINGYU_YAML = Path("/home/dovahkiin/opencode/trime-kb/output/清羽.trime.yaml")
OUT = HERE / "薄荷绿键盘-五色方案.yaml"

PALETTES = [
    ("mint", "薄荷绿", "#F0F4EF", "#E2F2E7", "#72C99B", "#455B50"),
    ("pink", "樱花粉", "#FCF5F6", "#FCEBED", "#F4AEB8", "#68565B"),
    ("blue", "浅天蓝", "#F0F6FB", "#E7F3FC", "#96CBF4", "#4D6275"),
    ("orange", "杏子橙", "#FCF7F0", "#FFF0E2", "#F7BD99", "#725C4F"),
    ("purple", "薰衣紫", "#F7F3FC", "#F1E9FC", "#C9A6ED", "#61566F"),
]

# 清羽布局 ID → (短名, 行高, 圆角)
SECONDARIES = [
    ("custom_9", "9键", 52, 14),
    ("custom_14", "14键", 52, 14),
    ("custom_18", "18键", 52, 14),
    ("custom_num", "数字", 52, 14),
    ("custom_symbols", "符号", 46, 14),
    ("custom_emoji", "表情", 46, 14),
    ("custom_hub", "键盘中心", None, 12),
]

# 清羽原配色 → 语义
Q_WHITE = "#FFFFFFFF"
Q_FUNC = "#FFE8ECF1"
Q_ACCENT = "#FF4F7CFF"
Q_ACCENT_SOFT = "#FFEEF3FF"
Q_TEXT = "#FF4B5563"
Q_MUTED = "#FF6B7280"
Q_MUTED2 = "#FF8A94A6"
Q_TRANS = "#00000000"

MINT_TAGS = [
    ("custom_mint", "薄荷绿"),
    ("custom_pink", "樱花粉"),
    ("custom_blue", "浅天蓝"),
    ("custom_orange", "杏子橙"),
    ("custom_purple", "薰衣紫"),
]


def muted(hex_color: str) -> str:
    """给 6 位色加 66% 透明度（#AARRGGBB）。"""
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
        "pressDarken": 8,
        "ripple": False,
    }


def surface(kb_bg: str, radius: int) -> dict:
    return {
        "keys": {"background": "", "borderWidth": 0.0, "borderColor": "", "radius": float(radius), "blur": False},
        "toolbar": {"background": "", "borderWidth": 0.0, "borderColor": "", "radius": 12.0, "blur": False},
        "bar": {"background": kb_bg, "borderWidth": 0.0, "borderColor": "", "radius": 0.0, "blur": False},
        "linkToolbar": False,
        "effects": effects(),
        "toolbarEffects": effects(),
        "barEffects": effects(),
    }


def toolbar(tag: str, fn_bg: str) -> list[dict]:
    def btn(action: str, icon: str) -> dict:
        return {
            "action": action,
            "enabled": True,
            "icon": f"{TB_BASE}/{icon}",
            "iconSize": 30,
            "appearance": {"keyBackColor": fn_bg, "roundCorner": "12", "keyBorder": "0"},
        }
    return [
        btn(f"Keyboard_custom_{tag}_hub", "tb_switch.png"),
        btn(f"Keyboard_custom_{tag}_symbols", "tb_symbols.png"),
        btn("clipboard_window", "tb_clipboard.png"),
        btn("Mode_switch", "tb_lang.png"),
        btn("__mie_toggle_floating_keyboard", "tb_float.png"),
    ]


def recolor_key(key: dict, tag: str, fn_bg: str, enter_bg: str, icon_color: str, white: str = "#FFFFFF") -> None:
    """把清羽/薄荷模板里的颜色与图标替换为当前配色。"""
    bg = key.get("key_back_color", Q_WHITE)
    if bg in (Q_FUNC, "#E2F2E7"):
        key["key_back_color"] = fn_bg
    elif bg in (Q_ACCENT, "#72C99B"):
        key["key_back_color"] = enter_bg
    elif bg == Q_WHITE:
        key["key_back_color"] = white
    elif bg == Q_TRANS:
        key["key_back_color"] = Q_TRANS
    else:
        key["key_back_color"] = white

    fg = key.get("key_text_color")
    if fg in (Q_TEXT, Q_ACCENT):
        key["key_text_color"] = icon_color
    elif fg in (Q_MUTED, Q_MUTED2):
        key["key_text_color"] = muted(icon_color)
    elif fg == "#FFFFFFFF":
        key["key_text_color"] = "#FFFFFF"
    elif fg:
        key["key_text_color"] = icon_color

    icon = key.get("icon")
    if isinstance(icon, str) and icon:
        icon = icon.replace("mint_", f"{tag}_")
        key["icon"] = icon


def retarget(value: str, tag: str) -> str:
    if not isinstance(value, str) or not value.startswith("Keyboard_custom_"):
        return value
    tail = value[len("Keyboard_custom_"):]
    mapping = {
        "main": "",
        "9": "_9", "14": "_14", "18": "_18",
        "num": "_num", "symbols": "_symbols", "emoji": "_emoji", "hub": "_hub",
    }
    suffix = mapping.get(tail, "_" + tail)
    return f"Keyboard_custom_{tag}{suffix}"


def replace_bindings(key: dict, tag: str) -> None:
    for field in ("click", "long_click", "double_click", "lazy_double_click",
                  "swipe_up", "swipe_down", "swipe_left", "swipe_right",
                  "composing", "has_menu", "paging", "combo", "ascii"):
        if field in key and isinstance(key[field], str):
            key[field] = retarget(key[field], tag)


def icon_for(label: str, action: str | None, tag: str, status: str = "normal") -> tuple[str | None, str | None]:
    """把 ⇧/⌫/↵ 换成各配色图标。返回 (icon, label)。"""
    if label == "⇧" or action == "Shift_L":
        return f"icons/{tag}_shift.png", ""
    if label == "⌫" or action == "BackSpace":
        return f"icons/{tag}_delete.png", ""
    if label == "↵" or action == "Return":
        return "icons/enter_w.png", ""
    return None, None


def bottom_row(tag: str, fn_bg: str, enter_bg: str, icon_color: str) -> list[dict]:
    """主键盘 / 9 / 14 / 18 共用的 6 键底行，保持薄荷设计的细标点。"""
    return [
        {"label": "123", "width": 14, "height": 52, "click": f"Keyboard_custom_{tag}_num",
         "key_back_color": fn_bg, "key_text_color": icon_color, "key_text_size": "14"},
        {"label": ",", "width": 8, "height": 52, "click": ",", "long_click": "、", "label_symbol": " ",
         "key_back_color": fn_bg, "key_text_color": icon_color, "key_text_size": "18"},
        {"label": "空格", "width": 44, "height": 52, "click": "space", "long_click": "Mode_switch", "label_symbol": " ",
         "key_back_color": "#FFFFFF", "key_text_color": muted(icon_color), "key_text_size": "14"},
        {"label": "。", "width": 8, "height": 52, "click": "。", "long_click": "？", "label_symbol": " ",
         "key_back_color": "#FFFFFF", "key_text_color": icon_color, "key_text_size": "18"},
        {"label": "⌨", "width": 12, "height": 52, "click": f"Keyboard_custom_{tag}_hub",
         "key_back_color": fn_bg, "key_text_color": icon_color, "key_text_size": "18"},
        {"label": "", "width": 14, "height": 52, "click": "Return", "icon": "icons/enter_w.png",
         "icon_size": 28, "key_back_color": enter_bg, "key_text_color": "#FFFFFF"},
    ]


def build_main(template: dict, tag: str, cn: str, kb_bg: str, fn_bg: str, enter_bg: str, icon_color: str) -> dict:
    kb = copy.deepcopy(template)
    kb["name"] = f"{cn}键盘"
    kb["keyboard_back_color"] = kb_bg
    kb["vertical_gap"] = 6
    kb["horizontal_gap"] = 6
    mie = kb.setdefault("mie", {})
    mie["toolbarAlignment"] = "legacy"
    mie["rowMargin"] = 2
    mie["keyAppearance"] = {
        "keyBackColor": "#FFFFFF", "keyTextColor": icon_color,
        "roundCorner": "14", "keyBorder": "0",
    }
    mie["surfaceAppearance"] = surface(kb_bg, 14)
    mie["toolbar"] = toolbar(tag, fn_bg)
    mie.pop("secondaryOf", None)

    rows: dict[int, list[dict]] = {}
    for key in kb["keys"]:
        row = key["mie"]["row"]
        recolor_key(key, tag, fn_bg, enter_bg, icon_color)
        replace_bindings(key, tag)
        rows.setdefault(row, []).append(key)

    # 底行统一换成 6 键（123 / ，/ 空格 / 。/ ⌨ / ↵）
    rows[4] = bottom_row(tag, fn_bg, enter_bg, icon_color)
    # 重新组装 keys
    new_keys: list[dict] = []
    for row in sorted(rows):
        for key in rows[row]:
            key.setdefault("height", 52)
            key["height"] = 52
            key["mie"] = {"row": row}
            new_keys.append(key)
    for key in new_keys:
        if key.get("long_click"):
            key["label_symbol"] = " "
    kb["keys"] = new_keys
    return kb


def build_secondary(src: dict, src_id: str, tag: str, cn: str, short: str,
                    kb_bg: str, fn_bg: str, enter_bg: str, icon_color: str,
                    default_height: int | None, radius: int) -> dict:
    kb = copy.deepcopy(src)
    kb["name"] = f"{cn} · {short}"
    kb["keyboard_back_color"] = kb_bg
    kb["vertical_gap"] = 6
    kb["horizontal_gap"] = 6
    mie = kb.setdefault("mie", {})
    mie["toolbarAlignment"] = "legacy"
    mie["rowMargin"] = 2
    mie["secondaryOf"] = f"custom_{tag}"
    mie["keyAppearance"] = {
        "keyBackColor": "#FFFFFF", "keyTextColor": icon_color,
        "roundCorner": str(radius), "keyBorder": "0",
    }
    mie["surfaceAppearance"] = surface(kb_bg, radius)
    mie["toolbar"] = toolbar(tag, fn_bg)

    for key in kb["keys"]:
        recolor_key(key, tag, fn_bg, enter_bg, icon_color)
        replace_bindings(key, tag)
        label = key.get("label", "")
        action = key.get("click")
        icon, new_label = icon_for(label, action, tag)
        if icon:
            key["icon"] = icon
            key["icon_size"] = 28
            key["label"] = new_label
        # 圆角
        if key.get("round_corner") != "0":
            key["round_corner"] = str(radius)
        # 行高
        if src_id == "custom_hub":
            if key["mie"]["row"] == 1:
                key["height"] = 34
            else:
                key["height"] = 52
        elif default_height:
            key["height"] = default_height

        # Hub 的 26 键按钮用实色强调
        if src_id == "custom_hub" and label == "26键":
            key["key_back_color"] = enter_bg
            key["key_text_color"] = "#FFFFFF"

    # 9 键网格里右上那个回车改成「符号」，只保留底行一个回车
    if src_id == "custom_9":
        for key in kb["keys"]:
            if key.get("click") == "Return" and key.get("width", 0) >= 20:
                key["label"] = "符号"
                key["click"] = f"Keyboard_custom_{tag}_symbols"
                key["key_back_color"] = fn_bg
                key["key_text_color"] = icon_color
                key["key_text_size"] = "14"
                key.pop("icon", None)
                key.pop("icon_size", None)
                break

    # 9/14/18 的底行最后替换成主键盘的 6 键细标点底行（避免被再次重定向）
    if src_id in ("custom_9", "custom_14", "custom_18"):
        keys = [k for k in kb["keys"] if k["mie"]["row"] != 4]
        for k in bottom_row(tag, fn_bg, enter_bg, icon_color):
            k["mie"] = {"row": 4}
            keys.append(k)
        kb["keys"] = keys

    # 统一：长按功能保留，但用空格 label_symbol 压掉键顶自动标签
    for key in kb["keys"]:
        if key.get("long_click"):
            key["label_symbol"] = " "
    return kb


def main() -> None:
    mint_doc = yaml.safe_load(MINT_YAML.read_text(encoding="utf-8"))
    main_template = mint_doc["preset_keyboards"]["custom_mint"]
    qingyu_doc = yaml.safe_load(QINGYU_YAML.read_text(encoding="utf-8"))
    qingyu = qingyu_doc["preset_keyboards"]

    out: dict[str, dict] = {}
    for tag, cn, kb_bg, fn_bg, enter_bg, icon_color in PALETTES:
        out[f"custom_{tag}"] = build_main(main_template, tag, cn, kb_bg, fn_bg, enter_bg, icon_color)
        for src_id, short, height, radius in SECONDARIES:
            dst_id = f"custom_{tag}" + ("" if src_id == "custom_main" else "_" + src_id.replace("custom_", ""))
            out[dst_id] = build_secondary(
                qingyu[src_id], src_id, tag, cn, short,
                kb_bg, fn_bg, enter_bg, icon_color, height, radius,
            )

    doc = {
        "mie": {"format": "trime-custom-keyboards-yaml", "version": 1},
        "preset_keyboards": out,
    }
    header = (
        "# 同文输入法咩版自定义键盘 —— 薄荷五色 · 扁平外观 + 清羽次级键盘\n"
        "# 导入：设置 → 键盘 → 布局管理与按键编辑 → 右上菜单 → 导入布局\n"
        f"# 工具栏图标：{TB_BASE}/tb_*.png\n"
        "# 键位图标：相对路径 icons/...（放在 .mie/键盘背景/icons/）\n"
        "# 每色 8 个布局：主键盘 + 9键 / 14键 / 18键 / 数字 / 符号 / 表情 / 键盘中心\n"
        "# 次级键盘的「⌨」进键盘中心；中心与次级键盘的「返回」回各自主键盘\n"
        "# 已写入独立扁平外观：关闭阴影/高光/3D/渐变，只保留轻按下反馈\n"
    )
    text = header + yaml.safe_dump(doc, allow_unicode=True, sort_keys=False, default_flow_style=False, width=1200)
    OUT.write_text(text, encoding="utf-8")
    print(f"written {OUT} ({len(text)} bytes, {len(out)} keyboards)")


if __name__ == "__main__":
    main()
