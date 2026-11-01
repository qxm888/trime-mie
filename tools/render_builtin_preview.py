#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Render a labelled design preview from shipped YAML and icons (not a device screenshot).
Optional authoring dependencies: PyYAML, Pillow and a CJK font.
"""
import argparse,io,math,zipfile
from pathlib import Path
import yaml
from PIL import Image,ImageDraw,ImageFont,ImageFilter
ROOT=Path(__file__).resolve().parents[1]

def rgba(value):
    raw=value.lstrip('#')
    if len(raw)==8:return tuple(int(raw[i:i+2],16) for i in [2,4,6,0])
    return tuple(int(raw[i:i+2],16) for i in [0,2,4])+(255,)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('output');parser.add_argument('--effects',action='store_true');parser.add_argument('--id');args=parser.parse_args()
    raw=ROOT/'app/src/main/res/raw'
    data=yaml.safe_load((raw/('mie_builtin_effects.yaml' if args.effects else 'mie_builtin_keyboards.yaml')).read_text())['preset_keyboards']
    ids=[args.id] if args.id else ['custom_porcelain','custom_obsidian','custom_champagne','custom_frost'] if args.effects else ['custom_mint','custom_pink','custom_blue','custom_orange','custom_purple']
    fontfile=Path('/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc')
    if not fontfile.exists():fontfile=Path('/usr/share/fonts/noto-cjk/NotoSerifCJK-Regular.ttc')
    font=ImageFont.truetype(str(fontfile),25);title=ImageFont.truetype(str(fontfile),28);small=ImageFont.truetype(str(fontfile),17)
    labels={'custom_porcelain':'柔和高光 · 瓷面层次 · 轻按下沉','custom_obsidian':'冷色描边 · 石墨微光 · 按压波纹','custom_champagne':'香槟描边 · 暖调渐变 · 缎面质感','custom_frost':'雾蓝底纹 · 半透键面 · 轻盈磨砂'}
    h=425 if args.effects else 395
    canvas=Image.new('RGBA',(900,h*len(ids)+60),'#E6E9EE');draw=ImageDraw.Draw(canvas)
    def rounded(box,radius,fill,outline=None,width=1):
        left,top,right,bottom=map(round,box)
        layer=Image.new('RGBA',(right-left+1,bottom-top+1))
        ImageDraw.Draw(layer).rounded_rectangle((0,0,layer.width-1,layer.height-1),radius=radius,fill=fill,outline=outline,width=width)
        canvas.alpha_composite(layer,(left,top))
    icons=zipfile.ZipFile(raw/'mie_builtin_icons.zip')
    def icon(name,size,tint=None):
        found=next((n for n in icons.namelist() if n.endswith(name.rsplit('/',1)[-1])),None)
        if found is None:return None
        image=Image.open(io.BytesIO(icons.read(found))).convert('RGBA');image.thumbnail((size,size))
        if tint:
            colored=Image.new('RGBA',image.size,rgba(tint));colored.putalpha(image.getchannel('A'));image=colored
        return image
    for position,id in enumerate(ids):
        kb=data[id];oy=position*h;ink=kb['mie']['keyAppearance']['keyTextColor']
        draw.text((34,oy+14),kb['name'],font=title,fill='#344152')
        if args.effects:draw.text((350,oy+25),labels[id],font=small,fill='#556273')
        top=oy+62
        backing=kb['keyboard_back_color']
        if backing.startswith('#'):
            rounded((24,top,876,top+h-80),radius=18,fill=rgba(backing))
        else:
            image=Image.open(raw/'mie_frost_background.png').convert('RGBA').resize((852,h-80))
            mask=Image.new('L',image.size);ImageDraw.Draw(mask).rounded_rectangle((0,0,image.width-1,image.height-1),radius=18,fill=255)
            image.putalpha(mask);canvas.alpha_composite(image,(24,top))
        # Stock button icons and backgrounds, matching the real toolbar's grouping.
        toolbar=kb['mie']['toolbar'];tx=38
        for button in toolbar:
            bw=44;back=button.get('appearance',{}).get('keyBackColor','#00000000')
            if rgba(back)[3]:rounded((tx,top+8,tx+bw,top+48),radius=8,fill=rgba(back))
            image=icon(button.get('icon',''),24,button.get('appearance',{}).get('iconTint'))
            if image:canvas.alpha_composite(image,(int(tx+(bw-image.width)/2),int(top+28-image.height/2)))
            tx+=104
        groups={}
        for key in kb['keys']:groups.setdefault(key['mie']['row'],[]).append(key)
        effect=kb['mie']['surfaceAppearance']['effects']
        for row,keys in enumerate(groups.values()):
            align=kb['mie'].get('rowAlignments',{}).get(str(row+1),kb['mie'].get('rowAlignments',{}).get(row+1,'left'))
            spare=826*(100-sum(key.get('width',10) for key in keys))/100
            x=37+(spare/2 if align=='center' else spare if align=='right' else 0);y=top+60+row*65
            for key in keys:
                width=826*key.get('width',10)/100
                if key.get('click'):
                    box=(x+3,y,x+width-3,y+58);radius=8
                    shadow=effect.get('shadow',{})
                    if shadow.get('enabled'):
                        layer=Image.new('RGBA',canvas.size);ld=ImageDraw.Draw(layer)
                        off=shadow.get('offsetY',0);ld.rounded_rectangle((box[0],box[1]+off,box[2],box[3]+off),radius=radius,fill=rgba(shadow['color']))
                        canvas.alpha_composite(layer.filter(ImageFilter.GaussianBlur(shadow.get('blur',3))))
                    rounded(box,radius=radius,fill=rgba(key['key_back_color']),outline=rgba(key.get('key_border_color','#00000000')) if int(key.get('key_border',0)) else None,width=1)
                    mask=Image.new('L',(int(width-6),58));ImageDraw.Draw(mask).rounded_rectangle((0,0,mask.width-1,57),radius=radius,fill=255)
                    gradient=effect.get('gradient',{})
                    if gradient.get('enabled'):
                        a=rgba(gradient.get('startColor','#16FFFFFF'));b=rgba(gradient.get('endColor','#00000000'))
                        layer=Image.new('RGBA',mask.size);ld=ImageDraw.Draw(layer)
                        for line in range(58):
                            t=line/57;c=tuple(round(a[i]*(1-t)+b[i]*t) for i in range(4));ld.line((0,line,layer.width,line),fill=c)
                        alpha=layer.getchannel('A');from PIL import ImageChops;layer.putalpha(ImageChops.multiply(alpha,mask));canvas.alpha_composite(layer,(int(x+3),int(y)))
                    if effect.get('gloss'):
                        draw.line((box[0]+radius,box[1]+2,box[2]-radius,box[1]+2),fill=rgba('#35FFFFFF'),width=1)
                    image=icon(key.get('icon',''),27,key.get('icon_tint')) if key.get('icon') else None
                    if image:canvas.alpha_composite(image,(int(x+(width-image.width)/2),int(y+(58-image.height)/2)))
                    else:
                        value=key.get('label','');bounds=draw.textbbox((0,0),value,font=font)
                        draw.text((x+(width-bounds[2])/2,y+(58-(bounds[3]-bounds[1]))/2-bounds[1]),value,font=font,fill=rgba(key['key_text_color']))
                x+=width
    draw.text((25,canvas.height-38),'根据模板与现有特效配置生成的设计示意；非手机截图，实际呈现以设备为准。',font=small,fill='#586170')
    Path(args.output).parent.mkdir(parents=True,exist_ok=True);canvas.convert('RGB').save(args.output)
if __name__=='__main__':main()
