#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Update managed toolbar assets, dynamic style pickers and stock T9 geometry."""
from pathlib import Path
import yaml
RAW=Path(__file__).resolve().parents[1]/'app/src/main/res/raw'
class Dumper(yaml.SafeDumper):
 def ignore_aliases(self,data):return True
for filename in ['mie_builtin_keyboards.yaml','mie_builtin_extra.yaml','mie_builtin_effects.yaml']:
 p=RAW/filename;data=yaml.safe_load(p.read_text())
 for id,kb in data['preset_keyboards'].items():
  for item in kb['mie']['toolbar']:
   if item.get('icon','').startswith('icons/tb_'):item['icon']=item['icon'].replace('icons/tb_','icons/mie2_tb_',1)
   if item.get('action')=='__mie_floating_keyboard' and item.get('icon')=='icons/mie2_tb_onehand.png':item['icon']='icons/mie2_tb_float.png'
   if item.get('icon')=='icons/mie2_tb_theme.png' and item['action'].endswith('_themes'):item['action']='__mie_theme_picker'
  for key in kb['keys']:
   if key.get('icon','').startswith('icons/tb_'):key['icon']=key['icon'].replace('icons/tb_','icons/mie2_tb_',1)
  if id.endswith('_9'):
   bottom=[k for k in kb['keys'] if k['mie']['row']==4]
   if [k.get('width') for k in bottom]==[16,12,47,12,13]:bottom[2]['width']=45;bottom[4]['width']=15
 p.write_text(yaml.dump(data,Dumper=Dumper,allow_unicode=True,sort_keys=False,width=140))
print('Stock toolbars now use dynamic picker/new icons; T9 right column aligned.')
