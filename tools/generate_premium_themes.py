#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate five complete premium families, using current stock geometry/navigation.
Run from any directory. Does not rewrite existing families, bindings or editor preferences.
"""
import copy,json,yaml
from pathlib import Path
from premium.materials import ROOT,SPECS,asset,write_assets
RAW=ROOT/'app/src/main/res/raw'
class Dumper(yaml.SafeDumper):
 def ignore_aliases(self,data):return True

def effects(s,toolbar=False):
 return dict(shadow=dict(enabled=not toolbar,color=s['shadow'],blur=s['shadowBlur'],offsetX=.3,offsetY=s['shadowY'],spread=-.5),
   innerShadow=dict(enabled=False),gradient=dict(enabled=False),borderGradient=dict(enabled=not toolbar,
   startColor='#B1E8DBAD' if s['id']=='celadon' else '#8CFFFFFF' if s['id']!='titanium' else '#817995B4',endColor='#16415666' if s['id']!='inkweave' else '#70555F59',angle=90,width=.65),
   textShadow=dict(enabled=False),gloss=False,noise=False,edge3d=False,
   pressScale=.9 if not toolbar else 1.5,pressSink=s['sink'] if not toolbar else .25,pressDarken=4 if s['id']!='titanium' else 9,
   ripple=False,animationMs=95)

def generate(artwork=True):
 if artwork:write_assets()
 core=yaml.safe_load((RAW/'mie_builtin_keyboards.yaml').read_text())
 extra=yaml.safe_load((RAW/'mie_builtin_extra.yaml').read_text())
 current=yaml.safe_load((RAW/'mie_builtin_effects.yaml').read_text())
 source={k:v for data in (core,extra) for k,v in data['preset_keyboards'].items() if k=='custom_pink' or k.startswith('custom_pink_')}
 output={k:v for k,v in current['preset_keyboards'].items() if not any(k=='custom_'+s['id'] or k.startswith('custom_'+s['id']+'_') for s in SPECS)}
 for s in SPECS:
  root='custom_'+s['id']
  for id,kb in source.items():
   new=json.loads(json.dumps(kb,ensure_ascii=False).replace('custom_pink',root).replace('樱花粉',s['name']))
   target=id.replace('custom_pink',root,1);new['name']=s['name']+'键盘' if id=='custom_pink' else new['name']
   new['keyboard_back_color']=asset(s,'背景')
   app=new['mie']['keyAppearance'];app.update(keyBackColor=asset(s,'键面'),keyTextColor=s['ink'],keySymbolColor=s['muted'],keyBorderColor=s['border'],keyBorder='0',roundCorner=str(s['radius']),symbolTextSize='9',hintTextSize='9')
   app.update(symbolInsetX='8' if s['id']!='cloudsea' else '9',symbolInsetY='4',hintInsetX='6',hintInsetY='4')
   mie=new['mie'];mie['toolbarHeightDp']=40;mie['toolbarBackground']={'type':'IMAGE','image':asset(s,'工具栏')};mie['unifiedBackground']=True
   mie['options']={**mie.get('options',{}),'symbolAlignment':'right','hintAlignment':'center'}
   cfg=mie['surfaceAppearance']
   cfg.update(keys=dict(radius=s['radius'],blur=False),toolbar=dict(radius=10,blur=False,borderWidth=0),
      bar=dict(radius=18,borderWidth=.55,borderColor=s['border'],blur=False),linkToolbar=False,retainToolbarBackground=True,
      effects=effects(s),toolbarEffects=effects(s,True),barEffects=dict(gradient=dict(enabled=False),shadow=dict(enabled=False)),keyboardCorners=[16,16,0,0])
   for item in mie['toolbar']:
    name=Path(item['icon']).stem.removeprefix('mie2_tb_').removeprefix('tb_')
    item['icon']=asset(s,'tb_'+name);item['iconSize']=19;item['width']=36;item['height']=36
    if name=='lang':item['action']='Mode_switch'
    if name=='theme':item['action']='__mie_theme_picker'
    item['appearance']={'keyBackColor':s['face'] if name in ('menu','float','hide') else '#00000000','roundCorner':'10','keyBorder':'0','iconTint':''}
   for index,key in enumerate(new['keys']):
    if not key.get('click') and not key.get('label') and not key.get('icon'):continue
    click=key.get('click','');row=key.get('mie',{}).get('row',1)
    kind='功能键' if not click else '强调键' if click=='Return' else '空格' if click=='space' else '功能键' if (click in ('Shift_L','Shift_R','BackSpace','Mode_switch','Escape') or click.startswith('Keyboard_') or key.get('label')=='返回') else '键面'
    # Horizontal keycap artwork avoids stretching a narrow cap onto the T9/14/18-key layouts.
    bg=kind+('-横' if kind!='空格' and key.get('width',10)>=18 else '')
    if s['id']=='cloudsea' and kind=='键面':
     alpha=click.lower() if len(click)==1 else ''
     glaze='开心果' if alpha in 'asdfgh' else '杏桃' if alpha in 'jkl' else '淡薰衣草' if alpha in 'zxc' else '海盐蓝' if alpha in 'vbnm' else ''
     if not glaze and row==2:glaze='开心果' if index%3!=2 else '杏桃'
     if not glaze and row==3:glaze='淡薰衣草' if index%3==0 else '海盐蓝'
     if glaze:bg=glaze+('-横' if key.get('width',10)>=18 else '')
    ink=s['accentInk'] if kind=='强调键' else '#F2F6F0' if s['id']=='inkweave' and kind=='功能键' else s['ink']
    key.update(key_back_color=asset(s,bg),key_text_color=ink,key_symbol_color=s['muted'] if not(s['id']=='inkweave' and kind=='功能键') else '#C3CDC5',
       round_corner=str(s['radius']),key_border='0',key_border_color=s['border'],hilited_key_text_color=ink,key_on_back_color=s['accent'],key_on_text_color=s['accentInk'])
    if key.get('icon'):key['icon_tint']=ink
   # The helper route uses the same dynamic layout/color picker, not an obsolete fixed nine-theme grid.
   if target.endswith('_themes'):
    prototype=copy.deepcopy(new['keys'][1]);prototype.update(label='选择布局与配色',click='__mie_theme_picker',width=100,height=56);prototype['mie']['row']=1
    back=copy.deepcopy(new['keys'][-1]);back.update(label='返回',click='Keyboard_default',width=100,height=44);back['mie']['row']=2
    new['keys']=[prototype,back]
   floating=mie['floatingCandidates']
   floating.update(panel=dict(background=s['floatPanel'],borderColor=s['border'],borderWidth=.6,radius=14,blur=False),
      panelEffects=dict(gradient=dict(enabled=True,startColor='#12FFFFFF',endColor='#03000000',angle=90),gloss=True,glossStrength=5),
      normal=dict(radius=9),highlighted=dict(background=s['accent'],radius=9),pager=dict(background='',radius=8),
      ball=dict(background=s['face'],borderWidth=.7,borderColor=s['border'],radius=24),shadowColor=s['shadow'],elevation=5)
   floating['colors']={"candidate":s['ink'],"label":s['muted'],"comment":s['muted'],"text":s['ink'],"arrow":s['ink'],"ball":s['ink'],
      **{'highlight.'+role:s['accentInk'] for role in ('candidate','label','comment')},'highlight.text':s['ink']}
   output[target]=new
 current['preset_keyboards']=output
 (RAW/'mie_builtin_effects.yaml').write_text('# 完整内置特效键盘：轻量基础四套 + 精雕材质五套\n'+yaml.dump(current,Dumper=Dumper,allow_unicode=True,sort_keys=False,width=140))
 print('New families:',len(SPECS),'pages per family:',len(source),'total effect pages:',len(output))
if __name__=='__main__':generate()
