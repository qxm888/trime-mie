#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Preview original premium PNGs and shipped layout geometry; not an Android screenshot."""
import argparse,io,json,math,zipfile
from pathlib import Path
import yaml
from PIL import Image,ImageDraw,ImageFont,ImageFilter,ImageChops
from premium.materials import ROOT,SPECS,asset
RAW=ROOT/'app/src/main/res/raw'
FONT='/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc'
def font(size):return ImageFont.truetype(FONT,round(size))
def color(value):
 raw=value.lstrip('#')
 if len(raw)==8:return tuple(int(raw[i:i+2],16) for i in (2,4,6,0))
 return tuple(int(raw[i:i+2],16) for i in (0,2,4))+(255,)
def text(d,xy,value,size,fill,anchor='mm'):
 # Center visible glyph, including full-width Chinese punctuation.
 f=font(size);box=d.textbbox((0,0),value,font=f)
 x,y=xy
 if anchor=='mm':x-=(box[0]+box[2])/2;y-=(box[1]+box[3])/2
 elif anchor=='rt':x-=box[2];y-=box[1]
 else:y-=box[1]
 d.text((round(x),round(y)),value,font=f,fill=fill)
def key_mask(size,radius):
 mask=Image.new('L',size);ImageDraw.Draw(mask).rounded_rectangle((0,0,size[0]-1,size[1]-1),radius=radius,fill=255);return mask

def annotation(d,px,y,bw,bh,value,size,fill,radius,inset_x,inset_y,top=True):
 # Match the native display-area rule: ink bounds, independent insets, round-cap clearance.
 f=font(size);box=d.textbbox((0,0),value,font=f);iw=box[2]-box[0];ih=box[3]-box[1]
 left=px+inset_x;right=px+bw-inset_x
 ink_y=y+inset_y if top else y+bh-inset_y-ih
 distance=max(0,min(ink_y-y,y+bh-(ink_y+ih),radius))
 clearance=radius-math.sqrt(max(0,radius*radius-(radius-distance)**2))
 left=max(left,px+clearance);right=min(right,px+bw-clearance)
 if iw>right-left:
  f=font(size*max(.1,(right-left)/max(1,iw)));box=d.textbbox((0,0),value,font=f);iw=box[2]-box[0];ih=box[3]-box[1]
  ink_y=y+inset_y if top else y+bh-inset_y-ih
 ink_x=right-iw if top else (left+right-iw)/2
 d.text((round(ink_x-box[0]),round(ink_y-box[1])),value,font=f,fill=fill)

def main():
 parser=argparse.ArgumentParser();parser.add_argument('output',type=Path);parser.add_argument('--layout',default='');args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
 data=yaml.safe_load((RAW/'mie_builtin_effects.yaml').read_text())['preset_keyboards']
 defaults=json.loads((RAW/'mie_builtin_bindings.json').read_text())
 z=zipfile.ZipFile(RAW/'mie_premium_materials.zip');icons=zipfile.ZipFile(RAW/'mie_builtin_icons.zip')
 def bitmap(path):
  if path in z.namelist():return Image.open(io.BytesIO(z.read(path))).convert('RGBA')
  name=path.rsplit('/',1)[-1]
  if name in icons.namelist():return Image.open(io.BytesIO(icons.read(name))).convert('RGBA')
  return None
 cards=[]
 for spec in SPECS:
  kb=data['custom_'+spec['id']+args.layout];w=1200;height=1090
  card=Image.new('RGBA',(w,height),'#F5F5F1');d=ImageDraw.Draw(card)
  d.rectangle((0,0,w,155),fill=spec['floatPanel'])
  text(d,(38,28),spec['name'],43,spec['ink'],'lt');text(d,(40,93),spec['subtitle'],23,spec['muted'],'lt')
  bg=bitmap(kb['keyboard_back_color']).resize((w,885),Image.Resampling.LANCZOS);card.alpha_composite(bg,(0,155))
  toolbar=bitmap(asset(spec,'工具栏')).resize((w-32,104),Image.Resampling.LANCZOS);toolbar.putalpha(key_mask(toolbar.size,49));card.alpha_composite(toolbar,(16,175))
  d=ImageDraw.Draw(card)
  toolbar=kb['mie']['toolbar'];display=[b for b in toolbar if b.get('primary')]+list(reversed([b for b in toolbar if not b.get('primary')]))
  for i,button in enumerate(display):
   ic=bitmap(button['icon']);ic.thumbnail((61,61),Image.Resampling.LANCZOS)
   x=28+(w-56)*(i+.5)/8
   card.alpha_composite(ic,(round(x-ic.width/2),round(228-ic.height/2)))
  rows={}
  for key in kb['keys']:rows.setdefault(key['mie']['row'],[]).append(key)
  labels={k['bindings'].get('click',''):k for row in defaults.get(args.layout,defaults[''])['rows'] for k in row['keys']}
  for ri,keys in enumerate(rows.values()):
   total=sum(k.get('width',10) for k in keys);spare=(w-16)*(100-total)/100
   alignments=kb['mie'].get('rowAlignments',{})
   align=alignments.get(str(ri+1),alignments.get(ri+1,'left'));x=8+(spare/2 if align=='center' else spare if align=='right' else 0)
   y=302+ri*179
   for key in keys:
    cell=(w-16)*key.get('width',10)/100;bw=max(1,round(cell-15));bh=151;px=round(x+7.5);radius=spec['radius']*3
    if not key.get('click'):x+=cell;continue
    blur=spec['shadowBlur']*3;pad=round(blur*2+7)
    shadow=Image.new('RGBA',(bw+pad*2,bh+pad*2));sd=ImageDraw.Draw(shadow)
    sd.rounded_rectangle((pad,pad,pad+bw,pad+bh),radius=radius,fill=color(spec['shadow']))
    shadow=shadow.filter(ImageFilter.GaussianBlur(blur*.7));card.alpha_composite(shadow,(px-pad+1,round(y-pad+spec['shadowY']*3)))
    face=bitmap(key['key_back_color']).resize((bw,bh),Image.Resampling.LANCZOS);mask=key_mask(face.size,radius);face.putalpha(mask);card.alpha_composite(face,(px,y))
    d=ImageDraw.Draw(card)
    # Same hairline border direction as the native border-gradient effect.
    rim=Image.new('RGBA',face.size);rd=ImageDraw.Draw(rim)
    for line in range(2):rd.rounded_rectangle((line,line,bw-1-line,bh-1-line),radius=radius,outline=color('#5CFFFFFF' if spec['id']!='titanium' else '#60859BB8'),width=1)
    card.alpha_composite(rim,(px,y))
    d=ImageDraw.Draw(card);label=key.get('label','');ink=key['key_text_color']
    ic=bitmap(key.get('icon','')) if key.get('icon') else None
    if ic:
     alpha=ic.getchannel('A');tint=Image.new('RGBA',ic.size,color(key.get('icon_tint',ink)));tint.putalpha(alpha);tint.thumbnail((64,64),Image.Resampling.LANCZOS)
     card.alpha_composite(tint,(round(px+(bw-tint.width)/2),round(y+(bh-tint.height)/2)))
    else:text(d,(px+bw/2,y+bh/2),label,51 if len(label)==1 else 35,ink)
    definition=labels.get(key.get('click',''),{});profile=definition.get('appearance',{})
    symbol=profile.get('labelSymbol') or definition.get('bindings',{}).get('longClick','')
    area=kb['mie']['keyAppearance']
    if symbol and (profile.get('labelSymbol') or len(symbol)==1):annotation(d,px,y,bw,bh,symbol,25,key.get('key_symbol_color',spec['muted']),radius,float(area['symbolInsetX'])*3,float(area['symbolInsetY'])*3)
    if profile.get('hint'):annotation(d,px,y,bw,bh,profile['hint'],25,key.get('key_symbol_color',spec['muted']),radius,float(area['hintInsetX'])*3,float(area['hintInsetY'])*3,False)
    x+=cell
  d=ImageDraw.Draw(card);text(d,(40,1056),'原创材质 · 完整内置布局 · 设计预览，非真机截图',19,'#64716D','lt')
  path=args.output/(spec['name']+'.png');card.convert('RGB').save(path,optimize=True);cards.append(card)
 # Two-column contact sheet with a compact material identity panel in the sixth slot.
 sw,sh=840,763;gap=24;sheet=Image.new('RGB',(sw*2+gap*3,sh*3+gap*4),'#E6E8E8')
 for i,card in enumerate(cards):sheet.paste(card.convert('RGB').resize((sw,sh),Image.Resampling.LANCZOS),(gap+(i%2)*(sw+gap),gap+(i//2)*(sh+gap)))
 panel=Image.new('RGB',(sw,sh),'#EDF0EC');pd=ImageDraw.Draw(panel)
 text(pd,(58,70),'精雕材质 · 五款新作',34,'#34483F','lt')
 y=180
 for s in SPECS:
  pd.rounded_rectangle((58,y,95,y+37),radius=12,fill=s['background'],outline=s['muted'])
  text(pd,(119,y+2),s['name'],28,'#3B4C45','lt');text(pd,(119,y+49),s['subtitle'],20,'#69766E','lt');y+=107
 text(pd,(58,705),'素材与布局配置一致 · 字符仍实时绘制',20,'#69766E','lt')
 sheet.paste(panel,(gap+sw+gap,gap+2*(sh+gap)));sheet.save(args.output/'五套高级主题总览.jpg',quality=95,subsampling=0)
 print(args.output/'五套高级主题总览.jpg')
if __name__=='__main__':main()
