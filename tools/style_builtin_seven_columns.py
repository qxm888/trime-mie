#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Apply the five built-in palettes to seven-column function keys.

Keep the previous templates once for a conservative upgrade of unedited keys.
Geometry, labels on character keys, gestures, and toolbar contents stay intact.
"""
from pathlib import Path
import copy
import re
import yaml

class NoAliasDumper(yaml.SafeDumper):
    def ignore_aliases(self, data):
        return True

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'app/src/main/res/raw'
PATH = RAW / 'mie_builtin_keyboards.yaml'
text = PATH.read_text()
data = yaml.safe_load(text)
layouts = data['preset_keyboards']
ids = [name for name in layouts if re.fullmatch(r'custom_(mint|pink|blue|orange|purple)_7_[12]', name)]
legacy = RAW / 'mie_builtin_seven_legacy.yaml'
if not legacy.exists():
    old = {'mie': copy.deepcopy(data['mie']), 'preset_keyboards': {name: copy.deepcopy(layouts[name]) for name in ids}}
    legacy.write_text('# V8 七列模板，仅用于升级时识别未编辑的按键\n' + yaml.safe_dump(old, allow_unicode=True, sort_keys=False, width=140))

for name in ids:
    color = name.split('_')[1]
    main = layouts[f'custom_{color}']
    def root_key(action):
        return next(key for key in main['keys'] if key.get('click') == action)
    references = {
        'Shift_L': next(key for key in main['keys'] if key.get('icon', '').endswith('_shift.png')),
        'BackSpace': root_key('BackSpace'),
        'Return': root_key('Return'),
        f'Keyboard_custom_{color}_num': root_key(f'Keyboard_custom_{color}_num'),
    }
    for key in layouts[name]['keys']:
        source = references.get(key.get('click'))
        if source is None:
            continue
        for field in ['key_back_color', 'key_text_color', 'key_border', 'key_border_color',
                      'round_corner', 'key_text_size', 'key_on_back_color', 'key_on_text_color',
                      'key_on_border_color', 'icon', 'icon_size']:
            if field in source:
                key[field] = source[field]
        if source.get('icon'):
            key['label'] = ''
    # Rewrite only this layout, leaving every other template byte-for-byte intact.
    block = yaml.dump({name: layouts[name]}, Dumper=NoAliasDumper, allow_unicode=True, sort_keys=False, width=140)
    block = '\n'.join('  ' + line if line else '' for line in block.rstrip().splitlines()) + '\n'
    pattern = re.compile(r'^  ' + re.escape(name) + r':\n.*?(?=^  custom_|\Z)', re.M | re.S)
    text = pattern.sub(lambda _: block, text, count=1)
PATH.write_text(text)
print('Updated seven-column palettes:', len(ids))
