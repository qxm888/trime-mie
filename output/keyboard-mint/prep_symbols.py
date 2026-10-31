#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""从 Rime symbols.yaml / wanxiang_symbols.yaml / tongwenfeng 主题里抽出分类符号，输出 symbols.json。"""
from __future__ import annotations

import json
import re
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
D = HERE / "data"


def load_yaml(text: str):
    # Rime 符号表里有 ", ," 这种空项，PyYAML 会报错，先清掉
    text = re.sub(r",\s*,", ",", text)
    text = re.sub(r"\[\s*,", "[", text)
    return yaml.safe_load(text)


def rime_symbols(path: Path) -> dict[str, list[str]]:
    doc = load_yaml(path.read_text(encoding="utf-8")) or {}
    table = doc.get("symbol_table") or doc.get("punctuator") or {}
    out: dict[str, list[str]] = {}
    for key, value in (table.get("symbols") or {}).items():
        items = value if isinstance(value, list) else [value]
        out[key] = [str(x) for x in items if str(x).strip()]
    return out


W = rime_symbols(D / "wanxiang_symbols.yaml")
M = rime_symbols(D / "symbols-mie.yaml")


def pick(cat: str, *fallbacks: str) -> list[str]:
    for key in (cat, *fallbacks):
        if W.get(key):
            return W[key]
        if M.get(key):
            return M[key]
    return []


def uniq(items) -> list[str]:
    seen: set[str] = set()
    out = []
    for item in items:
        item = str(item)
        if not item or item in seen:
            continue
        seen.add(item)
        out.append(item)
    return out


theme = load_yaml((D / "tongwenfeng.trime.yaml").read_text(encoding="utf-8")) or {}
liquid = ((theme.get("preset_color_schemes") or {}) and {}) or theme.get("liquid_keyboard") or {}
liquid = theme.get("liquid_keyboard") or {}


def liquid_keys(name: str) -> list[str]:
    entry = liquid.get(name) or {}
    keys = entry.get("keys")
    if isinstance(keys, str):
        return [ch for ch in keys if not ch.isspace()]
    if isinstance(keys, list):
        return [str(k) for k in keys]
    return []


def chars(text: str) -> list[str]:
    return [ch for ch in text if not ch.isspace()]


EN_SYMBOLS = chars('@#&$"\'<>%_`^\\{}()[]:~+-*/=|!?;:,')
CN_SYMBOLS = chars("，。？！：；、“”‘’《》【】（）「」『』—…·～￥％＃＠")
COMMON = chars("★☆♥♦♠♣♪♫→←↑↓✓✗℃€$％‰©®™§¶†‡•…※√∞≈≠≤≥±×÷")

COMBINING = [chr(c) for c in range(0x0300, 0x0334)]

LIST = chars(
    "①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳"
    "⑴⑵⑶⑷⑸⑹⑺⑻⑼⑽"
    "⒈⒉⒊⒋⒌⒍⒎⒏⒐⒑"
    "❶❷❸❹❺❻❼❽❾❿"
    "•◦▪▫■□●○◆◇▶▷★☆"
)
LATIN = chars(
    "ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖØŒÙÚÛÜÝÞß"
    "àáâãäåæçèéêëìíîïðñòóôõöøœùúûüýþÿ"
)
MATH = chars(
    "+−×÷=≠≈≡≤≥≮≯≪≫±∓∞∑∏√∛∫∬∮∂∇∈∉⊂⊃⊆⊇∪∩∧∨¬∀∃∅∴∵∠⊥∥°′″‰"
)
PHONETIC = chars(
    "iːɪeæɑɒɔʊuʌəɜɐɚɛɵøɘɤɯyʏœɶaɪaʊeɪoʊɔɪɪəeəʊə"
    "ˈˌːˑʰʲˠˤ̩̯̪̺̻̼̃θðʃʒŋɲɳɴʂʐɕʑɦɹɻɾɽɫʎʍɥʜʡʔ"
)

CATS: dict[str, list[str]] = {
    "emoji": uniq(pick("/bq")),
    "emoji_full": chars(liquid_keys("emoji_full")[0] if False else "".join(liquid_keys("emoji_full"))),
    "math": MATH,
    "english": EN_SYMBOLS,
    "chinese": CN_SYMBOLS,
    "common": COMMON,
    "symbollist": uniq(pick("/fh")),
    "list": LIST,
    "ids": [k for k in liquid_keys("ids") if k] or chars("⿰⿱⿲⿳⿴⿵⿶⿷⿸⿹⿺⿻"),
    "special": [k for k in liquid_keys("symbol") if k] or uniq(pick("/dn")),
    "pinyin": uniq(pick("/py")),
    "latin": LATIN,
    "kana": uniq(pick("/jm")),
    "greek": uniq(pick("/xl") + pick("/xld")),
    "russian": uniq(pick("/ey") + pick("/eyd")),
    "korean": uniq(pick("/hwq") + pick("/hwh")),
    "phonetic": PHONETIC,
    "unit": uniq(pick("/dw")),
    "kaomoji": uniq(liquid_keys("yanwenzi")),
    "combo": COMBINING,
    "arrows": uniq(pick("/jt")),
    "geometry": uniq(pick("/jh")),
    "weather": uniq(pick("/tq")),
    "currency": uniq(pick("/hb")),
}

if __name__ == "__main__":
    for name, items in CATS.items():
        print(f"{name:12s} {len(items):4d}  {''.join(items[:14])}")
    (HERE / "symbols.json").write_text(json.dumps(CATS, ensure_ascii=False, indent=0), encoding="utf-8")
    print("written symbols.json")
