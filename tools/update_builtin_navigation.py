#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Complete all stock theme choosers and use remembered-main return bindings."""
from pathlib import Path
import yaml,copy
r=Path(__file__).resolve().parents[1]/'app/src/main/res/raw'
class Dumper(yaml.SafeDumper):
 def ignore_aliases(self,data):return True
paths=['mie_builtin_keyboards.yaml','mie_builtin_extra.yaml','mie_builtin_effects.yaml']
files={n:yaml.safe_load((r/n).read_text()) for n in paths}
allkb={k:v for data in files.values() for k,v in data['preset_keyboards'].items()}
ids=['custom_mint','custom_pink','custom_blue','custom_orange','custom_purple','custom_porcelain','custom_obsidian','custom_champagne','custom_frost']
# Preserve the old stock pages for a conservative, exact upgrade comparison.
legacy={id:v for id,v in allkb.items() if id.endswith('_themes')}
legacy_path=r/'mie_builtin_chooser_legacy.yaml'
if not legacy_path.exists():legacy_path.write_text(yaml.dump({'preset_keyboards':legacy},Dumper=Dumper,allow_unicode=True,sort_keys=False))
for data in files.values():
 for id,kb in data['preset_keyboards'].items():
  parent=kb['mie'].get('secondaryOf','')
  for key in kb['keys']:
   if key.get('label')=='返回' and key.get('click')=='Keyboard_'+parent:key['click']='Keyboard_default'
  if not id.endswith('_themes'):continue
  owner=id.removesuffix('_themes')
  header=copy.deepcopy(kb['keys'][0]);header.update(label='选择键盘主题',height=30);header['mie']['row']=1
  ret=copy.deepcopy(kb['keys'][-1]);ret['mie']['row']=5
  prototype=copy.deepcopy(kb['keys'][1]);buttons=[]
  for i,target in enumerate(ids):
   source=allkb[target];app=source['mie']['keyAppearance'];key=copy.deepcopy(prototype)
   key.update(label=source['name'].removesuffix('键盘')+(' ✓' if target==owner else ''),click='Keyboard_'+target,width=33.333,height=52,key_text_size='14',key_back_color=app['keyBackColor'],key_text_color=app['keyTextColor'],key_border_color=app['keyBorderColor'])
   key['mie']['row']=2+i//3;buttons.append(key)
  kb['keys']=[header]+buttons+[ret]
for name,data in files.items():(r/name).write_text(yaml.dump(data,Dumper=Dumper,allow_unicode=True,sort_keys=False,width=140))
print('Nine theme choosers updated; labelled return actions use remembered main.')
