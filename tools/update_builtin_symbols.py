#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
from pathlib import Path
import yaml,json,copy,re
root=Path(__file__).resolve().parents[1]
raw=root/'app/src/main/res/raw'
def action(category):return '__mie_action:'+json.dumps(dict(send='FUNCTION',command='__mie_symbols',option=category,label='表情' if category=='emoji' else '表情与符号'),ensure_ascii=False,separators=(',',':'))
# Preserve the old templates solely to safely retire unmodified legacy pages on upgrade.
legacy=raw/'mie_builtin_legacy_symbols.yaml'
if not legacy.exists():legacy.write_text((raw/'mie_builtin_extra.yaml').read_text())
for file in ['mie_builtin_keyboards.yaml','mie_builtin_extra.yaml']:
 path=raw/file;data=yaml.safe_load(path.read_text());keyboards=data['preset_keyboards']
 if file=='mie_builtin_extra.yaml':
  keyboards={id:kb for id,kb in keyboards.items() if '_cat_' not in id};data['preset_keyboards']=keyboards
 for id,kb in keyboards.items():
  toolbar=kb['mie']['toolbar']
  if not any(x.get('icon','').endswith('tb_emoji.png') for x in toolbar):
   index=next(i for i,v in enumerate(toolbar) if v.get('icon','').endswith('tb_lang.png'))
   button=copy.deepcopy(toolbar[index]);button.update(action=action('emoji'),icon='icons/tb_emoji.png');button.pop('bindings',None)
   toolbar.insert(index+1,button) # remaining buttons render right-to-left
  for key in kb['keys']:
   for field in ['click','long_click','swipe_up','swipe_down','swipe_left','swipe_right']:
    value=key.get(field,'')
    match=re.fullmatch(r'Keyboard_custom_(?:mint|pink|blue|orange|purple)_(cat_(\w+?)(?:_p\d+)?|symbols(?:_cn|_math|_more)?)',value)
    if match:key[field]=action(match[2] or {'symbols':'english','symbols_cn':'chinese','symbols_math':'math','symbols_more':'common'}[match[1]])
 path.write_text('# 内置五色键盘；表情与完整符号库使用可滑动面板\n'+yaml.safe_dump(data,allow_unicode=True,sort_keys=False,width=140))
