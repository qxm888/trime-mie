#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""薄荷五色键盘 v7 —— 工具栏 7 键 + 中英双 26 键 + 数字/符号重排 + 符号分类库。

生成 5 套（薄荷/粉/蓝/橙/紫），每套：
  主键盘 26 键（字母大写显示）、9/14/18 键、数字、键盘中心、英文 26 键、
  符号（英文/中文/数学/更多分类）、24 个符号分类页、主题选择页、布局选择页。

用法：
  python3 gen_kb_yaml_v7.py [工具栏图标目录的绝对路径]
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
OUT = HERE / "薄荷绿键盘-五色方案-v11.yaml"
TB = (Path("/storage/emulated/0/rime-mie/.mie/键盘背景/icons") if len(__import__("sys").argv) < 2
      else Path(__import__("sys").argv[1]))
TB = str(TB).rstrip("/")

TAGS = ["mint", "pink", "blue", "orange", "purple"]

PAL = {
    "mint": dict(bg="#F7F9F8", fn="#E6F2EA", accent="#7BC8A4", text="#3F5A4E", border="#B8D0C3", name="薄荷绿"),
    "pink": dict(bg="#FBF7F8", fn="#FCEBED", accent="#F4AEB8", text="#5E4B50", border="#E3C2C8", name="樱花粉"),
    "blue": dict(bg="#F7F9FB", fn="#E7F3FC", accent="#96CBF4", text="#43586B", border="#B9D4E6", name="浅天蓝"),
    "orange": dict(bg="#FBF8F5", fn="#FFF0E2", accent="#F7BD99", text="#6B564A", border="#E5C7AE", name="杏子橙"),
    "purple": dict(bg="#F9F7FC", fn="#F1E9FC", accent="#C9A6ED", text="#574B66", border="#D3BFE6", name="薰衣紫"),
}

WHITE = "#FFFFFF"
CLEAR = "#00000000"
HEIGHT = 52
SYM_H = 44
RADIUS = "6"

LETTERS = list("qwertyuiop") + list("asdfghjkl") + list("zxcvbnm")


SHIFT_ONCE = '__mie_action:{"send":"Shift_L"}'
CAPS_LOCK = '__mie_action:{"send":"Caps_Lock"}'
# 同文「大写态」用的是主题色板里的 on_key_back_color(#F1F3F5)，改成同一个色就没有单击变色了
SHIFT_BG = "#F1F3F5"


def commit(text: str) -> str:
    """直接把字符上屏，不受中英标点转换影响。"""
    return "__mie_action:" + json.dumps({"commit": text}, ensure_ascii=False, separators=(",", ":"))


def key(
    label: str,
    click: str | None,
    row: int,
    *,
    w: float = 10,
    h: int = HEIGHT,
    kind: str = "normal",
    long: str | None = None,
    icon: str | None = None,
    icon_size: int = 26,
    size: str = "20",
    swipe_up: str | None = None,
    swipe_down: str | None = None,
    pal: dict | None = None,
    border: bool = True,
    symbol: str | None = None,
    text_offset: tuple[float, float] | None = None,
) -> dict:
    pal = pal or PAL["mint"]
    bg = {"normal": WHITE, "fn": pal["fn"], "enter": pal["accent"], "ghost": CLEAR}.get(kind, WHITE)
    fg = WHITE if kind == "enter" else pal["text"]
    out: dict = {"label": label, "click": click} if click is not None else {"label": label}
    if long:
        out["long_click"] = long
        out["label_symbol"] = " "
    if swipe_up:
        out["swipe_up"] = swipe_up
    if swipe_down:
        out["swipe_down"] = swipe_down
    if symbol:
        out["label_symbol"] = symbol
    out["width"] = round(w, 3)
    out["key_back_color"] = bg
    out["key_text_color"] = fg
    out["key_text_size"] = size
    out["height"] = h
    out["mie"] = {"row": row}
    if icon:
        out["icon"] = icon
        out["icon_size"] = icon_size
    if text_offset:
        out["key_text_offset_x"] = float(text_offset[0])
        out["key_text_offset_y"] = float(text_offset[1])
    out["round_corner"] = RADIUS
    out["key_border"] = "0" if kind in ("enter", "ghost") or not border else "1"
    out["key_border_color"] = pal["border"]
    return out


def on_colors(k: dict, pal: dict) -> None:
    """锁定大写（长按 / 双击 Shift）时：回车色底 + 白图标，单击不变色。"""
    k["key_back_color"] = pal["fn"]
    k["key_on_back_color"] = pal["accent"]
    k["key_on_text_color"] = WHITE
    k["key_on_border_color"] = pal["accent"]


def surface(bg: str) -> dict:
    fx = {
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
    return {
        "keys": {"background": "", "radius": 6.0, "blur": False},
        "toolbar": {"background": "", "borderWidth": 0.0, "borderColor": "", "radius": 8.0, "blur": False},
        "bar": {"background": bg, "borderWidth": 0.0, "borderColor": "", "radius": 0.0, "blur": False},
        "linkToolbar": False,
        "effects": fx,
        "toolbarEffects": fx,
        "barEffects": fx,
    }


def toolbar(tag: str, pal: dict, lang_action: str) -> list[dict]:
    """工具栏：primary 固定在最左，其余按钮在同文里是「反向」渲染的，
    所以列表里要按显示顺序的倒序写。显示顺序：
    菜单 / 主题 / 布局 / 悬浮(长按单手) / 中英 / 剪贴板 / 收起
    """

    def btn(action: str, icon: str, *, bindings: dict | None = None, primary: bool = False,
            filled: bool = False) -> dict:
        item = {
            "action": action,
            "enabled": True,
            "icon": icon,
            "iconSize": 18,
            "width": 34,
            "height": 34,
            "appearance": {"keyBackColor": pal["fn"] if filled else CLEAR,
                           "roundCorner": "8", "keyBorder": "0"},
        }
        if primary:
            item["primary"] = True
        if bindings:
            item["bindings"] = bindings
        return item

    return [
        btn("__mie_menu", f"{TB}/tb_menu.png", primary=True, filled=True),
        btn("Hide", f"{TB}/tb_hide.png", filled=True),
        btn("clipboard", f"{TB}/tb_clipboard.png"),
        btn(lang_action, f"{TB}/tb_lang.png"),
        btn("__mie_floating_keyboard", f"{TB}/tb_onehand.png",
            bindings={"longClick": "__mie_one_hand"}, filled=True),
        btn(f"Keyboard_custom_{tag}_layouts", f"{TB}/tb_layout.png"),
        btn(f"Keyboard_custom_{tag}_themes", f"{TB}/tb_theme.png"),
    ]


def muted(hex_color: str) -> str:
    h = hex_color.lstrip("#")
    return "#99" + h if len(h) == 6 else hex_color


def floating_candidates(pal: dict) -> dict:
    """悬浮候选窗：白底 + 主题色细边框 + 小圆角；候选词用键面文字色，
    选中项用回车色做底、白字。"""
    return {
        "enabled": True,
        "panel": {
            "background": "#FFFFFF",
            "borderWidth": 1.0,
            "borderColor": pal["border"],
            "radius": 10.0,
            "blur": False,
        },
        "normal": {"radius": 8.0},
        "highlighted": {"background": pal["accent"], "radius": 8.0},
        "pager": {"background": "", "radius": 8.0},
        "colors": {
            "candidate": pal["text"],
            "highlight.candidate": "#FFFFFF",
            "label": pal["text"],
            "highlight.label": "#FFFFFF",
            "comment": muted(pal["text"]),
            "highlight.comment": "#EAF7F0",
            "arrow": pal["text"],
        },
        "elevation": 6,
        "itemElevation": 0,
    }


def keyboard(
    kb_id: str,
    name: str,
    tag: str,
    keys: list[dict],
    *,
    transform: str | None = None,
    ascii_mode: int | None = None,
    reset_ascii: bool = False,
    ascii_kb: str | None = None,
    secondary: str | None = None,
    lock: bool = False,
    lang_action: str | None = None,
    h: int | None = None,
    row_align: dict[int, str] | None = None,
) -> dict:
    pal = PAL[tag]
    out: dict = {"name": name}
    if transform:
        out["label_transform"] = transform
    if ascii_mode is not None:
        out["ascii_mode"] = ascii_mode
        out["reset_ascii_mode"] = reset_ascii
    if ascii_kb:
        out["ascii_keyboard"] = ascii_kb
    if lock:
        # 主键盘：被选中后“返回主键盘”回到它，而不是固定回 26 键。
        out["lock"] = True
    out["keyboard_back_color"] = pal["bg"]
    out["vertical_gap"] = 5
    out["horizontal_gap"] = 5
    out["keys"] = keys
    mie: dict = {
        "rowMargin": 1,
        "toolbarAlignment": "legacy",
        "toolbarHeightDp": 36,
        "toolbar": toolbar(tag, pal, lang_action or f"Keyboard_custom_{tag}_en"),
        "keyAppearance": {
            "keyBackColor": WHITE,
            "keyTextColor": pal["text"],
            "roundCorner": RADIUS,
            "keyBorder": "1",
            "keyBorderColor": pal["border"],
            "keyTextSize": "20",
        },
        "surfaceAppearance": surface(pal["bg"]),
        "floatingCandidates": floating_candidates(pal),
    }
    if row_align:
        mie["rowAlignments"] = row_align
    if secondary:
        mie["secondaryOf"] = secondary
    out["mie"] = mie
    return out


def bottom_main(tag: str, row: int, pal: dict) -> list[dict]:
    return [
        key("123", f"Keyboard_custom_{tag}_num", row, w=14, kind="fn", size="14",
            long=f"Keyboard_custom_{tag}_symbols", pal=pal),
        key(",", ",", row, w=12, size="18", long=commit("？"), pal=pal),
        key("空格", "space", row, w=45, kind="normal", size="14", pal=pal),
        key("。", ".", row, w=12, size="18", long=commit("！"), pal=pal,
            text_offset=(5, -5)),
        key("", "Return", row, w=17, kind="enter", icon="icons/enter_w.png", icon_size=24, pal=pal),
    ]


# ----------------------------------------------------------------- 主键盘 26
def kb_main(tag: str) -> dict:
    pal = PAL[tag]
    keys: list[dict] = []
    for i, ch in enumerate("qwertyuiop"):
        keys.append(key(ch.upper(), ch, 1, w=10, pal=pal))
    for ch in "asdfghjkl":
        keys.append(key(ch.upper(), ch, 2, w=10, pal=pal))
    k = key("", SHIFT_ONCE, 3, w=15, kind="fn", icon=f"icons/{tag}_shift.png", icon_size=24,
            pal=pal, long=CAPS_LOCK)
    on_colors(k, pal)
    keys.append(k)
    for ch in "zxcvbnm":
        keys.append(key(ch.upper(), ch, 3, w=10, pal=pal, long=commit("、") if ch == "m" else None))
    keys.append(key("", "BackSpace", 3, w=15, kind="fn", icon=f"icons/{tag}_delete.png", icon_size=26, pal=pal))
    keys += bottom_main(tag, 4, pal)
    return keyboard(f"custom_{tag}", f"{pal['name']}键盘", tag, keys, transform="uppercase",
                    ascii_mode=0, reset_ascii=True, ascii_kb=f"custom_{tag}_en",
                    row_align={2: "center"}, lock=True)


# ----------------------------------------------------------------- 9 / 14 / 18
def kb_9(tag: str) -> dict:
    """9 键：左侧四枚标点竖排，中间 3x3 T9，右侧退格/重输/换行，底部符号/123/空格/中英。"""
    pal = PAL[tag]
    keys: list[dict] = []
    keys.append(key("，", ",", 1, w=16, size="18", pal=pal))
    keys.append(key("@#", f"Keyboard_custom_{tag}_symbols", 1, w=23, kind="fn", size="18", pal=pal))
    for lbl, c1, c2, c3 in (("ABC", "a", "b", "c"), ("DEF", "d", "e", "f")):
        keys.append(key(lbl, c1, 1, w=23, size="18", long=c2, swipe_up=c3, pal=pal))
    keys.append(key("", "BackSpace", 1, w=15, kind="fn", icon=f"icons/{tag}_delete.png",
                    icon_size=26, pal=pal))
    keys.append(key("。", ".", 2, w=16, size="18", pal=pal))
    for lbl, c1, c2, c3 in (("GHI", "g", "h", "i"), ("JKL", "j", "k", "l"), ("MNO", "m", "n", "o")):
        keys.append(key(lbl, c1, 2, w=23, size="18", long=c2, swipe_up=c3, pal=pal))
    keys.append(key("重输", "Escape", 2, w=15, kind="fn", size="14", pal=pal))
    keys.append(key("？", "?", 3, w=16, size="18", pal=pal))
    for lbl, letters in (("PQRS", ("p", "q", "r", "s")), ("TUV", ("t", "u", "v")), ("WXYZ", ("w", "x", "y", "z"))):
        keys.append(key(lbl, letters[0], 3, w=23, size="18", long=letters[1],
                        swipe_up=letters[2], swipe_down=letters[3] if len(letters) > 3 else None, pal=pal))
    # 右下角“确定”分两格显示：上“确”下“定”，两键都是确定（Return）
    keys.append(key("确", "Return", 3, w=15, kind="enter", size="15", pal=pal))
    keys.append(key("！", "!", 4, w=16, h=44, size="18", pal=pal))
    keys.append(key("符号", f"Keyboard_custom_{tag}_symbols", 4, w=12, h=44, kind="fn", size="13", pal=pal))
    keys.append(key("123", f"Keyboard_custom_{tag}_num", 4, w=12, h=44, kind="fn", size="14", pal=pal))
    keys.append(key("空格", "space", 4, w=33, h=44, kind="normal", size="14", pal=pal))
    keys.append(key("中/英", "Mode_switch", 4, w=12, h=44, kind="fn", size="12", pal=pal))
    keys.append(key("定", "Return", 4, w=15, h=44, kind="enter", size="15", pal=pal))
    return keyboard(f"custom_{tag}_9", f"{pal['name']} · 9键", tag, keys,
                    ascii_mode=0, reset_ascii=True, secondary=f"custom_{tag}",
                    ascii_kb=f"custom_{tag}_en", lock=True)


def kb_14(tag: str) -> dict:
    pal = PAL[tag]
    rows = [["QW", "q", "w"], ["ER", "e", "r"], ["TY", "t", "y"], ["UI", "u", "i"], ["OP", "o", "p"]]
    keys = [key(lbl, c1, 1, w=20, size="18", long=c2, pal=pal) for lbl, c1, c2 in rows]
    rows = [["AS", "a", "s"], ["DF", "d", "f"], ["GH", "g", "h"], ["JK", "j", "k"], ["L", "l", None]]
    keys += [key(lbl, c1, 2, w=20, size="18", long=c2, pal=pal) for lbl, c1, c2 in rows]
    rows = [["ZX", "z", "x"], ["CV", "c", "v"], ["BN", "b", "n"], ["M", "m", None]]
    k = key("", SHIFT_ONCE, 3, w=round(100 / 6, 3), kind="fn",
            icon=f"icons/{tag}_shift.png", icon_size=22, pal=pal, long=CAPS_LOCK)
    on_colors(k, pal)
    keys.append(k)
    keys += [key(lbl, c1, 3, w=round(100 / 6, 3), size="18", long=c2, pal=pal) for lbl, c1, c2 in rows]
    keys.append(key("", "BackSpace", 3, w=round(100 / 6, 3), kind="fn",
                    icon=f"icons/{tag}_delete.png", pal=pal))
    keys += bottom_main(tag, 4, pal)
    return keyboard(f"custom_{tag}_14", f"{pal['name']} · 14键", tag, keys,
                    ascii_mode=0, reset_ascii=True, secondary=f"custom_{tag}",
                    ascii_kb=f"custom_{tag}_en", lock=True)


def kb_18(tag: str) -> dict:
    pal = PAL[tag]
    rows = [["Q", "q", None], ["WE", "w", "e"], ["RT", "r", "t"], ["Y", "y", None], ["U", "u", None],
            ["IO", "i", "o"], ["P", "p", None]]
    keys = [key(lbl, c1, 1, w=round(100 / 7, 3), size="18", long=c2, pal=pal) for lbl, c1, c2 in rows]
    rows = [["A", "a", None], ["SD", "s", "d"], ["FG", "f", "g"], ["H", "h", None], ["JK", "j", "k"], ["L", "l", None]]
    keys += [key(lbl, c1, 2, w=round(100 / 6, 3), size="18", long=c2, pal=pal) for lbl, c1, c2 in rows]
    rows = [["Z", "z", None], ["XC", "x", "c"], ["V", "v", None], ["BN", "b", "n"], ["M", "m", None]]
    k = key("", SHIFT_ONCE, 3, w=round(100 / 7, 3), kind="fn",
            icon=f"icons/{tag}_shift.png", icon_size=22, pal=pal, long=CAPS_LOCK)
    on_colors(k, pal)
    keys.append(k)
    keys += [key(lbl, c1, 3, w=round(100 / 7, 3), size="18", long=c2, pal=pal) for lbl, c1, c2 in rows]
    keys.append(key("", "BackSpace", 3, w=round(100 / 7, 3), kind="fn",
                    icon=f"icons/{tag}_delete.png", pal=pal))
    keys += bottom_main(tag, 4, pal)
    return keyboard(f"custom_{tag}_18", f"{pal['name']} · 18键", tag, keys,
                    ascii_mode=0, reset_ascii=True, secondary=f"custom_{tag}",
                    ascii_kb=f"custom_{tag}_en", lock=True)


# ----------------------------------------------------------------- 数字
def kb_num(tag: str) -> dict:
    pal = PAL[tag]
    keys: list[dict] = []
    grid = [["+", "1", "2", "3"], ["-", "4", "5", "6"], ["*", "7", "8", "9"], ["/", ".", "0", "="]]
    for r, row in enumerate(grid, start=1):
        for ch in row:
            kind = "fn" if ch in "+-*/=" else "normal"
            size = "22" if ch.isdigit() else "20"
            keys.append(key(ch, commit(ch), r, w=20, kind=kind, size=size, pal=pal))
        if r == 1:
            keys.append(key("", "BackSpace", 1, w=20, kind="fn", icon=f"icons/{tag}_delete.png", pal=pal))
        elif r == 2:
            keys.append(key("返回", f"Keyboard_custom_{tag}", 2, w=20, kind="fn", size="13", pal=pal))
        elif r == 3:
            keys.append(key("符号", f"Keyboard_custom_{tag}_symbols", 3, w=20, kind="fn", size="13", pal=pal))
        else:
            keys.append(key("", "Return", 4, w=20, kind="enter", icon="icons/enter_w.png", pal=pal))
    return keyboard(f"custom_{tag}_num", f"{pal['name']} · 数字", tag, keys,
                    ascii_mode=1, reset_ascii=True, secondary=f"custom_{tag}")


# ----------------------------------------------------------------- 键盘中心
def kb_hub(tag: str) -> dict:
    pal = PAL[tag]
    keys = [key("选择键盘", None, 1, w=100, h=36, kind="ghost", size="12", pal=pal)]
    for lbl, target in (("26键", f"custom_{tag}"), ("9键", f"custom_{tag}_9"),
                        ("14键", f"custom_{tag}_14"), ("18键", f"custom_{tag}_18")):
        keys.append(key(lbl, f"Keyboard_{target}", 2, w=25, kind="normal", size="17", pal=pal))
    for lbl, target in (("数字", f"Keyboard_custom_{tag}_num"), ("符号", f"Keyboard_custom_{tag}_symbols"),
                        ("表情", f"Keyboard_custom_{tag}_cat_emoji"), ("英文", f"Keyboard_custom_{tag}_en")):
        keys.append(key(lbl, target, 3, w=25, kind="normal", size="17", pal=pal))
    keys.append(key("返回", f"Keyboard_custom_{tag}", 4, w=100, kind="fn", size="15", pal=pal))
    return keyboard(f"custom_{tag}_hub", f"{pal['name']} · 键盘中心", tag, keys,
                    secondary=f"custom_{tag}", h=32)


# ----------------------------------------------------------------- 英文 26 键
def kb_en(tag: str) -> dict:
    pal = PAL[tag]
    keys: list[dict] = []
    for ch in "qwertyuiop":
        keys.append(key(ch, ch, 1, w=10, pal=pal))
    for ch in "asdfghjkl":
        keys.append(key(ch, ch, 2, w=10, pal=pal))
    k = key("", SHIFT_ONCE, 3, w=15, kind="fn", icon=f"icons/{tag}_shift.png",
            icon_size=24, pal=pal, long=CAPS_LOCK)
    on_colors(k, pal)
    keys.append(k)
    for ch in "zxcvbnm":
        keys.append(key(ch, ch, 3, w=10, pal=pal))
    keys.append(key("", "BackSpace", 3, w=15, kind="fn", icon=f"icons/{tag}_delete.png",
                    icon_size=26, pal=pal))
    keys.append(key("123", f"Keyboard_custom_{tag}_num", 4, w=14, kind="fn", size="14",
                    long=f"Keyboard_custom_{tag}_symbols", pal=pal))
    keys.append(key(",", ",", 4, w=12, size="18", pal=pal))
    keys.append(key("space", "space", 4, w=45, size="13", pal=pal))
    keys.append(key(".", ".", 4, w=12, size="18", pal=pal))
    keys.append(key("", "Return", 4, w=17, kind="enter", icon="icons/enter_w.png", icon_size=24, pal=pal))
    return keyboard(f"custom_{tag}_en", f"{pal['name']} · 英文", tag, keys,
                    ascii_mode=1, reset_ascii=True, secondary=f"custom_{tag}",
                    lang_action=f"Keyboard_custom_{tag}", row_align={2: "center"})


# ----------------------------------------------------------------- 符号页
def sym_keys(items: list[str], *, cols: int, rows: int, h: int, size: str, pal: dict,
             row_start: int = 1, nav: tuple[str, str] | None = None) -> list[dict]:
    keys: list[dict] = []
    cells = list(items)
    if nav:
        cells = cells[: cols * rows - 2] + [nav[0], nav[1]]
    for i, ch in enumerate(cells):
        r = row_start + i // cols
        keys.append(key(ch, commit(ch) if not ch.startswith("Keyboard_") else ch, r,
                        w=round(100 / cols, 3), h=h, size=size, pal=pal))
    return keys


def bottom_sym(tag: str, row: int, pal: dict) -> list[dict]:
    return [
        key("返回", f"Keyboard_custom_{tag}", row, w=20, kind="fn", size="13", pal=pal),
        key("英文", f"Keyboard_custom_{tag}_symbols", row, w=20, kind="fn", size="13", pal=pal),
        key("中文", f"Keyboard_custom_{tag}_symbols_cn", row, w=20, kind="fn", size="13", pal=pal),
        key("数学", f"Keyboard_custom_{tag}_symbols_math", row, w=20, kind="fn", size="13", pal=pal),
        key("更多", f"Keyboard_custom_{tag}_symbols_more", row, w=20, kind="fn", size="13", pal=pal),
    ]


def bottom_cat(tag: str, row: int, pal: dict, back: str) -> list[dict]:
    return [
        key("返回", f"Keyboard_{back}", row, w=20, kind="fn", size="13", pal=pal),
        key("更多", f"Keyboard_custom_{tag}_symbols_more", row, w=20, kind="fn", size="13", pal=pal),
        key("空格", "space", row, w=20, kind="normal", size="13", pal=pal),
        key("删除", "BackSpace", row, w=20, kind="fn", size="13", pal=pal),
        key("回车", "Return", row, w=20, kind="enter", size="13", pal=pal),
    ]


SYMBOLS = json.loads((HERE / "symbols.json").read_text(encoding="utf-8"))

ASCII_SYMBOLS = [chr(c) for c in range(33, 127) if not chr(c).isalnum()]
ASCII_SYMBOLS.remove("`")


def kb_symbols(tag: str, kind: str) -> dict:
    pal = PAL[tag]
    cols, rows, h, size = 8, 4, SYM_H, "18"
    if kind == "en":
        items = ASCII_SYMBOLS[:31] + ["⌫"]
        keys = sym_keys(items, cols=cols, rows=rows, h=h, size=size, pal=pal)
        keys[-1]["click"] = "BackSpace"
        keys[-1]["label"] = ""
        keys[-1]["icon"] = f"icons/{tag}_delete.png"
        keys[-1]["icon_size"] = 22
        keys[-1]["key_back_color"] = pal["fn"]
        keys += bottom_sym(tag, 5, pal)
        return keyboard(f"custom_{tag}_symbols", f"{pal['name']} · 符号", tag, keys,
                        secondary=f"custom_{tag}")
    if kind == "cn":
        items = SYMBOLS["chinese"] + ["｜", "／", "＼", "＋"]
        keys = sym_keys(items[:32], cols=cols, rows=rows, h=h, size=size, pal=pal)
        keys += bottom_sym(tag, 5, pal)
        return keyboard(f"custom_{tag}_symbols_cn", f"{pal['name']} · 中文符号", tag, keys,
                        secondary=f"custom_{tag}")
    if kind == "math":
        items = SYMBOLS["math"]
        keys = sym_keys(items[:32], cols=cols, rows=rows, h=h, size=size, pal=pal)
        keys += bottom_sym(tag, 5, pal)
        return keyboard(f"custom_{tag}_symbols_math", f"{pal['name']} · 数学符号", tag, keys,
                        secondary=f"custom_{tag}")
    raise ValueError(kind)


CATEGORIES = [
    ("emoji", "表情", "emoji"),
    ("emoji_full", "全emoji", "emoji_full"),
    ("math", "数学", None),
    ("english", "英文", None),
    ("chinese", "中文", None),
    ("common", "常用", "common"),
    ("symbollist", "符号表", "symbollist"),
    ("list", "列表", "list"),
    ("ids", "IDS", "ids"),
    ("special", "特殊", "special"),
    ("pinyin", "拼音", "pinyin"),
    ("latin", "拉丁", "latin"),
    ("kana", "假名", "kana"),
    ("greek", "希腊", "greek"),
    ("russian", "俄语", "russian"),
    ("korean", "韩文", "korean"),
    ("phonetic", "音标", "phonetic"),
    ("unit", "单位", "unit"),
    ("kaomoji", "颜文字", "kaomoji"),
    ("combo", "组合", "combo"),
    ("arrows", "箭头", "arrows"),
    ("geometry", "几何", "geometry"),
    ("weather", "天气", "weather"),
    ("currency", "货币", "currency"),
]

CAT_TARGET = {
    "math": "symbols_math",
    "english": "symbols",
    "chinese": "symbols_cn",
}


def kb_more(tag: str) -> dict:
    pal = PAL[tag]
    keys: list[dict] = []
    row = 1
    col = 0
    for i, (cid, label, _src) in enumerate(CATEGORIES):
        target = CAT_TARGET.get(cid, f"cat_{cid}")
        keys.append(key(label, f"Keyboard_custom_{tag}_{target}", row, w=20, h=46, size="14", pal=pal))
        col += 1
        if col == 5:
            col = 0
            row += 1
    if col:
        last = [k for k in keys if k["mie"]["row"] == row]
        for k in last:
            k["width"] = round(100 / len(last), 3)
    keys.append(key("返回", f"Keyboard_custom_{tag}_symbols", 6, w=20, kind="fn", size="13", pal=pal))
    keys.append(key("更多", f"Keyboard_custom_{tag}_symbols_more", 6, w=20, kind="fn", size="13", pal=pal))
    keys.append(key("空格", "space", 6, w=20, kind="normal", size="13", pal=pal))
    keys.append(key("删除", "BackSpace", 6, w=20, kind="fn", size="13", pal=pal))
    keys.append(key("回车", "Return", 6, w=20, kind="enter", size="13", pal=pal))
    return keyboard(f"custom_{tag}_symbols_more", f"{pal['name']} · 更多符号", tag, keys,
                    secondary=f"custom_{tag}")


def _cat_ids(tag: str, cid: str, n: int) -> list[str]:
    base = f"custom_{tag}_cat_{cid}"
    return [base if i == 0 else f"{base}_p{i + 1}" for i in range(n)]


def kb_category(tag: str, cid: str, label: str, src: str) -> dict[str, dict]:
    """分类页：8 列 × 5 行，超过一页时最后两个格子是 ‹ ›（最多 3 页）。"""
    pal = PAL[tag]
    if cid == "kaomoji":
        cols, rows, h, size = 4, 5, 52, "11"
    else:
        cols, rows, h, size = 8, 5, 40, "18"
    items = SYMBOLS[src]
    cap = cols * rows
    per = cap - 2 if len(items) > cap else cap
    chunks = [items[i:i + per] for i in range(0, min(len(items), per * 3), per)]
    ids = _cat_ids(tag, cid, len(chunks))
    out: dict[str, dict] = {}
    for idx, chunk in enumerate(chunks):
        keys: list[dict] = []
        for i, ch in enumerate(chunk):
            keys.append(key(ch, commit(ch), 1 + i // cols, w=round(100 / cols, 3), h=h,
                            size=size, pal=pal))
        last_row = 1 + max(0, len(chunk) - 1) // cols
        if len(chunks) > 1:
            # 翻页键紧跟在符号后面，不满一页时不会留大片空白
            used = 1 + max(0, len(chunk) - 1) // cols
            rem = len(chunk) % cols
            nav_row = used if (rem and rem + 2 <= cols) else used + 1
            prev_id = ids[idx - 1] if idx > 0 else ids[idx]
            next_id = ids[idx + 1] if idx + 1 < len(chunks) else ids[idx]
            keys.append(key("‹", f"Keyboard_{prev_id}", nav_row, w=round(100 / cols, 3), h=h,
                            kind="fn", size="20", pal=pal))
            keys.append(key("›", f"Keyboard_{next_id}", nav_row, w=round(100 / cols, 3), h=h,
                            kind="fn", size="20", pal=pal))
            last_row = nav_row
        keys += bottom_cat(tag, last_row + 1, pal, f"custom_{tag}")
        suffix = f" {idx + 1}/{len(chunks)}" if len(chunks) > 1 else ""
        out[ids[idx]] = keyboard(ids[idx], f"{pal['name']} · {label}{suffix}", tag, keys,
                                 secondary=f"custom_{tag}")
    return out


# ----------------------------------------------------------------- 主题 / 布局选择
def kb_themes(tag: str) -> dict:
    pal = PAL[tag]
    keys = [key("选择主题（26键主键盘）", None, 1, w=100, h=36, kind="ghost", size="12", pal=pal)]
    for r, other in enumerate(TAGS, start=2):
        op = PAL[other]
        k = key(f"{op['name']}键盘", f"Keyboard_custom_{other}", r, w=100, h=48, size="16", kind="fn", pal=pal)
        k["key_back_color"] = op["fn"]
        k["key_border_color"] = op["border"]
        k["key_text_color"] = op["text"]
        if other == tag:
            k["label"] = f"{op['name']}键盘（当前）"
        keys.append(k)
    keys.append(key("返回", f"Keyboard_custom_{tag}", 7, w=100, h=48, kind="fn", size="15", pal=pal))
    return keyboard(f"custom_{tag}_themes", f"{pal['name']} · 主题", tag, keys,
                    secondary=f"custom_{tag}", h=48)


def kb_layouts(tag: str) -> dict:
    pal = PAL[tag]
    keys = [key("选择布局", None, 1, w=100, h=36, kind="ghost", size="12", pal=pal)]
    for r, (lbl, target) in enumerate((("26键 主键盘", f"custom_{tag}"), ("9键", f"custom_{tag}_9"),
                                       ("18键", f"custom_{tag}_18"), ("14键", f"custom_{tag}_14")), start=2):
        keys.append(key(lbl, f"Keyboard_{target}", r, w=100, h=50, size="16", kind="fn", pal=pal))
    keys.append(key("返回", f"Keyboard_custom_{tag}", 6, w=100, h=50, kind="fn", size="15", pal=pal))
    return keyboard(f"custom_{tag}_layouts", f"{pal['name']} · 布局", tag, keys,
                    secondary=f"custom_{tag}", h=50)


# ----------------------------------------------------------------- main
def build() -> dict:
    kbs: dict[str, dict] = {}
    for tag in TAGS:
        kbs[f"custom_{tag}"] = kb_main(tag)
        kbs[f"custom_{tag}_9"] = kb_9(tag)
        kbs[f"custom_{tag}_14"] = kb_14(tag)
        kbs[f"custom_{tag}_18"] = kb_18(tag)
        kbs[f"custom_{tag}_num"] = kb_num(tag)
        kbs[f"custom_{tag}_hub"] = kb_hub(tag)
        kbs[f"custom_{tag}_en"] = kb_en(tag)
        kbs[f"custom_{tag}_symbols"] = kb_symbols(tag, "en")
        kbs[f"custom_{tag}_symbols_cn"] = kb_symbols(tag, "cn")
        kbs[f"custom_{tag}_symbols_math"] = kb_symbols(tag, "math")
        for cid, label, src in CATEGORIES:
            if src is None:
                continue
            kbs.update(kb_category(tag, cid, label, src))
        kbs[f"custom_{tag}_symbols_more"] = kb_more(tag)
        kbs[f"custom_{tag}_themes"] = kb_themes(tag)
        kbs[f"custom_{tag}_layouts"] = kb_layouts(tag)
    return kbs


def main() -> None:
    kbs = build()
    doc = {"mie": {"format": "trime-custom-keyboards-yaml", "version": 1}, "preset_keyboards": kbs}
    header = (
        "# 同文输入法咩版自定义键盘 —— 薄荷五色 v7\n"
        "# 工具栏 7 键：菜单 / 主题 / 布局 / 悬浮(长按单手) / 中英 / 剪贴板 / 收起\n"
        "# 26 键主键盘字母大写显示；英文键盘为独立 26 键（小写 + Shift/Caps）\n"
        f"# 工具栏图标目录：{TB}\n"
    )
    OUT.write_text(header + yaml.safe_dump(doc, allow_unicode=True, sort_keys=False,
                                           default_flow_style=False, width=1200), encoding="utf-8")
    print(f"written {OUT} ({OUT.stat().st_size} bytes, {len(kbs)} keyboards)")


if __name__ == "__main__":
    main()
