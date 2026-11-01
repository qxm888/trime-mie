#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Refine stock surfaces and generate complete effect keyboard families."""
from pathlib import Path
import copy,json,yaml

ROOT=Path(__file__).resolve().parents[1]
RAW=ROOT/'app/src/main/res/raw'
class NoAlias(yaml.SafeDumper):
    def ignore_aliases(self,data): return True

def effects(**changes):
    value=dict(shadow={'enabled':False},innerShadow={'enabled':False},gradient={'enabled':False},borderGradient={'enabled':False},textShadow={'enabled':False},
               gloss=False,noise=False,edge3d=False,pressScale=1.2,pressSink=.35,pressDarken=6,ripple=False,animationMs=90)
    value.update(changes)
    return value

core_path=RAW/'mie_builtin_keyboards.yaml'
extra_path=RAW/'mie_builtin_extra.yaml'
core=yaml.safe_load(core_path.read_text());extra=yaml.safe_load(extra_path.read_text())
# Gentle elevation and short feedback, using the stock palettes throughout.
for data in [core,extra]:
 for kb in data['preset_keyboards'].values():
    cfg=kb['mie']['surfaceAppearance']
    border=kb['mie']['keyAppearance'].get('keyBorderColor','#BCC7CF')[-6:]
    cfg['effects']=effects(gradient={'enabled':True,'startColor':'#16FFFFFF','endColor':'#00000000','angle':90,'opacity':100},
                           edge3d=True,edgeDepth=.6,edgeColor='#18'+border)
    cfg['toolbarEffects']=effects(pressScale=1.5,pressSink=.25)

specs=[
 dict(id='porcelain',name='月白·瓷光',bg='#ECEAF0',face='#FAF9FC',soft='#E8E2F0',ink='#51465D',border='#D7CFDF',accent='#806A94',
      effects=effects(shadow=dict(enabled=True,color='#2351405F',blur=3,offsetX=0,offsetY=1,spread=-.5),
                      innerShadow=dict(enabled=True,color='#1251405F',blur=2,offsetX=0,offsetY=1),
                      gradient=dict(enabled=True,startColor='#38FFFFFF',endColor='#08685588',angle=90,opacity=100),
                      gloss=True,glossStrength=8,edge3d=True,edgeDepth=1,edgeColor='#266B5A7A',pressScale=1.4,pressSink=.7)),
 dict(id='obsidian',name='曜石·微光',bg='#18242D',face='#24333F',soft='#304957',ink='#E9F3F6',border='#45606F',accent='#2D7181',
      effects=effects(shadow=dict(enabled=True,color='#2254BEC9',blur=4,offsetX=0,offsetY=0,spread=-1),
                      gradient=dict(enabled=True,startColor='#214A8192',endColor='#08000000',angle=110,opacity=100),
                      borderGradient=dict(enabled=True,startColor='#8C88C8D6',endColor='#22334E62',angle=90,width=.8),
                      gloss=True,glossStrength=7,pressScale=1.6,pressSink=.4,ripple=True,rippleColor='#247ED5E0')),
 dict(id='champagne',name='暮金·缎光',bg='#302B27',face='#463C34',soft='#554638',ink='#F1E6D4',border='#806D55',accent='#896334',
      effects=effects(innerShadow=dict(enabled=True,color='#3B120D09',blur=3,offsetX=0,offsetY=1),
                      gradient=dict(enabled=True,startColor='#3DAD9265',endColor='#0A1D1308',angle=115,opacity=100),
                      borderGradient=dict(enabled=True,startColor='#A6C4A775',endColor='#22685532',angle=95,width=.8),
                      gloss=True,glossStrength=5,edge3d=True,edgeDepth=1.1,edgeColor='#66170F09',pressScale=1.1,pressSink=.7)),
 dict(id='frost',name='雾凇·半透',bg='backgrounds/frost.png',face='#88FFFFFF',soft='#A8DEEEF3',ink='#304F61',border='#ADFFFFFF',accent='#507F97',
      effects=effects(shadow=dict(enabled=True,color='#18354F61',blur=2,offsetX=0,offsetY=.6,spread=-.5),
                      gradient=dict(enabled=True,startColor='#22FFFFFF',endColor='#00000000',angle=90,opacity=100),
                      borderGradient=dict(enabled=True,startColor='#C4FFFFFF',endColor='#5582ACBA',angle=90,width=.7),
                      gloss=True,glossStrength=6,pressScale=1.2,pressSink=.35,ripple=True,rippleColor='#227CAFC3'))]

base={**core['preset_keyboards'],**extra['preset_keyboards']}
premium={}
for spec in specs:
 for id,kb in base.items():
    if not (id=='custom_pink' or id.startswith('custom_pink_')):continue
    raw=json.dumps(kb,ensure_ascii=False).replace('custom_pink','custom_'+spec['id']).replace('樱花粉',spec['name'])
    raw=raw.replace('#FBF7F8',spec['bg']).replace('#FCEBED',spec['soft']).replace('#5E4B50',spec['ink']).replace('#E3C2C8',spec['border']).replace('#F4AEB8',spec['accent'])
    new=json.loads(raw)
    if id=='custom_pink':new['name']=spec['name']+'键盘'
    for key in new['keys']:
        if key.get('key_back_color')=='#FFFFFF':key['key_back_color']=spec['face']
        if key.get('icon'):key['icon_tint']=key.get('key_text_color',spec['ink'])
    appearance=new['mie']['keyAppearance']
    appearance.update(keyBackColor=spec['face'],keyTextColor=spec['ink'],keyBorderColor=spec['border'])
    for entry in new['mie']['toolbar']:
        entry.setdefault('appearance',{})['iconTint']=spec['ink']
    floating=new['mie'].get('floatingCandidates',{})
    if floating:
        floating['panel'].update(background=spec['face'],borderColor=spec['border'])
        floating['colors'].update(comment='#CC'+spec['ink'][1:], **{'highlight.comment':'#E6FFFFFF'})
    cfg=new['mie']['surfaceAppearance']
    cfg['effects']=spec['effects']
    cfg['toolbarEffects']=effects(gradient=dict(enabled=True,startColor='#16FFFFFF',endColor='#00000000',angle=90,opacity=100),pressScale=1.5,pressSink=.4)
    cfg['barEffects']=effects(pressScale=0,pressSink=0,pressDarken=0,gradient=dict(enabled=True,startColor='#0FFFFFFF',endColor='#00000000',angle=90,opacity=100))
    if spec['id']=='frost':
        new['mie']['unifiedBackground']=True
        cfg.update(blurRadius=12,glassOpacity=65)
        cfg['keys'].update(blur=True,radius=8)
        cfg['toolbar'].update(blur=True,radius=8)
        cfg['bar'].update(background='#18FFFFFF',blur=True)
        floating.get('panel',{}).update(background='#EDF5F7',borderColor='#B8D0DA')
        appearance['roundCorner']='8'
        for key in new['keys']:key['round_corner']=8
    premium[id.replace('custom_pink','custom_'+spec['id'],1)]=new

for path,data in [(core_path,core),(extra_path,extra)]:
    path.write_text('# 内置五色键盘；轻量层次与按压反馈\n'+yaml.dump(data,Dumper=NoAlias,allow_unicode=True,sort_keys=False,width=140))
(RAW/'mie_builtin_effects.yaml').write_text('# 月白瓷光、曜石微光、暮金缎光、雾凇半透：四套完整特效键盘\n'+yaml.dump({'mie':core['mie'],'preset_keyboards':premium},Dumper=NoAlias,allow_unicode=True,sort_keys=False,width=140))
print('Stock surfaces refined; effect families generated:',len(premium))
