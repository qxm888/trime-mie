#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Original procedural material artwork. No text baked into keys; no reference artwork copied.
Authoring dependencies only: numpy + Pillow. Output files are ordinary shared PNG assets.
"""
import io, json, math, re, subprocess, zipfile
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageChops
ROOT=Path(__file__).resolve().parents[2]
SPECS=json.loads((Path(__file__).parent/'palettes.json').read_text())
PREFIX='内置材质/精雕一辑'

def rgb(s):return np.array([int(s[i:i+2],16) for i in (1,3,5)],dtype=np.float32)
def rgba(s,a=255):return tuple(int(v) for v in rgb(s))+(a,)
def asset(s,name):return f'{PREFIX}/{s["name"]}/{name}.png'
def image(array):return Image.fromarray(np.clip(array,0,255).astype('uint8')).convert('RGBA')
def gradient(size,a,b):
 w,h=size;t=np.linspace(0,1,h)[:,None,None]
 return image(np.broadcast_to(rgb(a)*(1-t)+rgb(b)*t,(h,w,3)))
def soft_spot(im,xy,r,color,alpha=90):
 w,h=im.size;x,y=np.meshgrid(np.arange(w),np.arange(h));weight=np.exp(-((x-xy[0])**2+(y-xy[1])**2)/(2*r*r))
 layer=Image.new('RGBA',im.size,color);layer.putalpha(Image.fromarray((weight*alpha).astype('uint8')));im.alpha_composite(layer)
def svg_layer(size,body):
 w,h=size
 raw=f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 1200 800" preserveAspectRatio="none">{body}</svg>'
 return Image.open(io.BytesIO(subprocess.check_output(['rsvg-convert'],input=raw.encode()))).convert('RGBA')
def waves(im,s,accent=False):
 # Original sculpted wave forms, deliberately outside the main label zone.
 w,h=im.size
 colors=['#E8F7FF','#92C6E5','#C7E6ED'] if s['id']=='cloudsea' else ['#C5F4F4','#7DA4EB','#B3A0E1']
 body=f'''<defs><linearGradient id="a" x2="1" y2="1"><stop stop-color="{colors[0]}"/><stop offset=".55" stop-color="{colors[1]}"/><stop offset="1" stop-color="{colors[2]}"/></linearGradient></defs>'''
 if accent:
  body+='''<path d="M0 0H1200V110C990 36 720 180 0 190Z" fill="url(#a)"/>
  <path d="M0 180C360 190 720 20 1200 110" fill="none" stroke="#F6FFFF" stroke-opacity=".7" stroke-width="6"/>'''
 else:
  body+='''<path d="M0 625C240 475 350 820 710 650S1080 580 1200 680V800H0Z" fill="url(#a)"/>
  <path d="M0 625C240 475 350 820 710 650S1080 580 1200 680" fill="none" stroke="#FFFFFF" stroke-opacity=".65" stroke-width="4"/>'''
 im.alpha_composite(svg_layer((w,h),body))

def face(s,kind='normal',size=(256,384),tint=None):
 w,h=size;x,y=np.meshgrid(np.linspace(0,1,w),np.linspace(0,1,h))
 base=rgb(tint or s['accent' if kind=='accent' else 'soft' if kind=='function' else 'face'])
 # Soft bevel normals, a directional diffuse field and a broad specular lobe.
 edge=np.minimum.reduce([x,1-x,y,1-y]);bevel=np.exp(-edge/.027)
 directional=(.72-x*.65-y*.75)
 shine=np.exp(-((x-.22)**2/.15+(y-.14)**2/.14))
 dark=s['id']=='titanium' or s['id']=='inkweave' and kind in ('accent','function')
 strength=18 if dark else 23
 shade=directional*strength+shine*(7 if dark else 12)
 shade+=bevel*((.5-x)*32+(.5-y)*24)
 arr=np.broadcast_to(base,(h,w,3)).copy()+shade[:,:,None]
 rng=np.random.default_rng(613)
 arr+=rng.normal(0,.36,(h,w,1))
 if s['id']=='titanium':
  grain=(np.sin(y*h*2.1)*.7+np.sin(y*h*.37)*.35)[:,:,None];arr+=grain
 if s['id']=='inkweave':
  weave=((np.sin(x*w*math.pi/3.6)+np.sin(y*h*math.pi/3.6))*1.1)[:,:,None]
  arr+=weave
  checker=(((np.floor(x*w/14)+np.floor(y*h/14))%2)*2-1)[:,:,None]
  arr+=checker*(3.5 if kind=='space' else 2.0)
 if s['id']=='cloudsea':arr+=np.exp(-((x-.28)**2/.07+(y-.22)**2/.08))[:,:,None]*9
 im=image(arr)
 if s['id']=='celadon':
  # Sparse, translucent glaze crackle; seeded and static, not visual noise per frame.
  detail=Image.new('RGBA',im.size);d=ImageDraw.Draw(detail);rr=np.random.default_rng(72)
  for _ in range(9 if kind!='space' else 14):
   xx,yy=rr.uniform(0,w),rr.uniform(0,h)
   pts=[(xx,yy)]
   for j in range(3):xx+=rr.uniform(-20,20);yy+=rr.uniform(15,32);pts.append((xx,yy))
   d.line(pts,fill=(88,133,113,24),width=1)
  im.alpha_composite(detail)
 if s['id']=='inkweave':
  detail=Image.new('RGBA',im.size);d=ImageDraw.Draw(detail);gap=11;inset=12
  col=(241,246,239,135) if dark else (94,110,100,68)
  # Straight stitching inside the key rim, never crossing its central label.
  for xx in range(inset+10,w-inset-8,gap):
   d.line((xx,inset,xx+4,inset),fill=col,width=1);d.line((xx,h-inset-1,xx+4,h-inset-1),fill=col,width=1)
  for yy in range(inset+10,h-inset-8,gap):
   d.line((inset,yy,inset,yy+4),fill=col,width=1);d.line((w-inset-1,yy,w-inset-1,yy+4),fill=col,width=1)
  im.alpha_composite(detail)
 if kind=='space' and s['id'] in ('silver','titanium','cloudsea'):
  waves(im,s,accent=s['id']!='cloudsea')
  if s['id']=='cloudsea':
   im.alpha_composite(svg_layer(size,'''<g fill="#86BDD3" opacity=".45" transform="translate(7 12)"><ellipse cx="174" cy="630" rx="115" ry="68"/><ellipse cx="127" cy="593" rx="57" ry="61"/><ellipse cx="195" cy="569" rx="68" ry="71"/></g><g fill="#F6FCFC"><ellipse cx="174" cy="620" rx="115" ry="68"/><ellipse cx="127" cy="583" rx="57" ry="61"/><ellipse cx="195" cy="559" rx="68" ry="71"/></g><g fill="#4F6E82"><circle cx="150" cy="630" r="9"/><circle cx="195" cy="630" r="9"/></g><path d="M167 645Q173 654 180 645" stroke="#6A8792" stroke-width="5" fill="none"/>'''))
 if kind=='accent' and s['id'] in ('silver','titanium'):
  # Small luminous reflected corner, keeping the return glyph quiet and legible.
  im.alpha_composite(svg_layer(size,'''<defs><linearGradient id="r"><stop stop-color="#8CDCE7"/><stop offset=".58" stop-color="#A4ACE7"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient></defs><path d="M760 0H1200V260Q1090 44 760 0" fill="url(#r)" opacity=".8"/>'''))
 return im

def backdrop(s):
 w,h=1200,820;im=gradient((w,h),s['background'],s['background'])
 if s['id']=='silver':
  soft_spot(im,(250,30),420,'#FAFEFF',110);soft_spot(im,(1100,800),350,'#A9BCD3',50)
  im.alpha_composite(svg_layer(im.size,'''<path d="M0 610Q850 870 1200 390V800H0Z" fill="#C3CCD7" opacity=".24"/><path d="M0 610Q850 870 1200 390" fill="none" stroke="#FCFEFF" stroke-width="2" opacity=".7"/>'''))
 elif s['id']=='titanium':
  soft_spot(im,(100,120),470,'#627994',35);soft_spot(im,(1130,850),350,'#316773',28)
  im.alpha_composite(svg_layer(im.size,'''<path d="M-150 600Q600 100 1300 440" stroke="#6F8CA4" stroke-opacity=".15" stroke-width="1.4" fill="none"/><path d="M0 800L1200 100V800Z" fill="#15212F" opacity=".28"/>'''))
 elif s['id']=='cloudsea':
  soft_spot(im,(130,80),440,'#EFFAFF',100);waves(im,s)
  im.alpha_composite(svg_layer(im.size,'''<g fill="#F6FBF9" opacity=".65"><ellipse cx="1080" cy="55" rx="88" ry="22"/><ellipse cx="1025" cy="52" rx="33" ry="27"/><ellipse cx="1068" cy="39" rx="40" ry="33"/><ellipse cx="1115" cy="49" rx="37" ry="26"/></g><path d="M0 790Q450 600 900 800H0Z" fill="#E9C8A7" opacity=".3"/>'''))
 elif s['id']=='inkweave':
  xx,yy=np.meshgrid(np.arange(w),np.arange(h));a=np.broadcast_to(rgb(s['background']),(h,w,3)).copy()
  a+=(((xx//5)%2+(yy//5)%2-1)*1.25)[:,:,None];im=image(a)
  soft_spot(im,(20,0),600,'#FFFFFF',35)
 elif s['id']=='celadon':
  soft_spot(im,(80,50),550,'#F5F8E8',70);soft_spot(im,(1180,720),450,'#9EBCAF',45)
  im.alpha_composite(svg_layer(im.size,'''<path d="M730 800Q790 540 1200 650" fill="none" stroke="#91B6A3" stroke-width="4" opacity=".18"/><g fill="#8BB19B" opacity=".13"><path d="M890 695Q840 580 1030 610Q1010 675 890 695Z"/><path d="M1000 660Q1070 560 1190 630Q1130 690 1000 660Z"/></g>'''))
 return im

def icon(s,name):
 # Existing original geometric icon, bevel-lit on a shared semantic grid.
 src=(ROOT/f'tools/icons/mie2_tb_{name}.svg').read_text()
 ink=s['ink'];light='#FAFFFF' if s['id']!='titanium' else '#ADC8E6'
 def render(color):
  text=re.sub(r'color="#[0-9A-Fa-f]+"',f'color="{color}"',src)
  return Image.open(io.BytesIO(subprocess.check_output(['rsvg-convert','-w','144','-h','144'],input=text.encode()))).convert('RGBA')
 result=Image.new('RGBA',(160,160));shadow=render('#000000');shadow.putalpha(shadow.getchannel('A').point(lambda p:int(p*.18)))
 result.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(1.4)),(9,11))
 result.alpha_composite(render(light),(7,6));result.alpha_composite(render(ink),(8,8))
 return result

def generate_assets():
 out={}
 for s in SPECS:
  for kind,name in [('normal','键面'),('function','功能键'),('accent','强调键')]:
   out[asset(s,name)]=face(s,kind)
   out[asset(s,name+'-横')]=face(s,kind,(768,384))
  out[asset(s,'空格')]=face(s,'space',(1152,384))
  out[asset(s,'背景')]=backdrop(s)
  bar=face(s,'normal',(1200,140),tint=s['background']);
  if s['id']=='cloudsea':waves(bar,s)
  out[asset(s,'工具栏')]=bar
  # Sea has deliberate alternating clay glazes rather than a uniformly recolored board.
  if s['id']=='cloudsea':
   for name,tint in [('开心果','#E1EDB3'),('杏桃','#F3D2B1'),('淡薰衣草','#DED9EC'),('海盐蓝','#B6DDEC')]:
    out[asset(s,name)]=face(s,'normal',tint=tint);out[asset(s,name+'-横')]=face(s,'normal',(768,384),tint=tint)
  for name in ['menu','theme','layout','float','onehand','clipboard','lang','hide','emoji','switch','symbols']:
   out[asset(s,'tb_'+name)]=icon(s,name)
 return out

def write_assets():
 out=generate_assets();path=ROOT/'app/src/main/res/raw/mie_premium_materials.zip'
 with zipfile.ZipFile(path,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
  for name,im in sorted(out.items()):
   b=io.BytesIO();im.convert('RGBA' if '/tb_' in name else 'RGB').save(b,format='PNG',optimize=True)
   entry=zipfile.ZipInfo(name,date_time=(2026,11,3,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;z.writestr(entry,b.getvalue())
 # Human-readable manifest is also used by the installer to check missing files cheaply.
 manifest=ROOT/'app/src/main/res/raw/mie_premium_materials_manifest.txt'
 manifest.write_text('\n'.join(sorted(out))+'\n')
 print('Original material PNGs:',len(out),'ZIP bytes:',path.stat().st_size)
 return out
if __name__=='__main__':write_assets()
