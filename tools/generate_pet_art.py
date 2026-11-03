#!/usr/bin/env python3
"""Regenerate 36 layered cartoon pets from the user's editable GLM vector sources."""
from pathlib import Path
import json, re, math
from xml.etree import ElementTree as ET
from xml.sax.saxutils import escape
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'tools/pets'
DRW=ROOT/'app/src/main/res/drawable'
ROSTER=json.loads((SRC/'source/species.json').read_text())
A='http://schemas.android.com/apk/res/android';AA='http://schemas.android.com/aapt'
ET.register_namespace('android',A);ET.register_namespace('aapt',AA)
def blend(color,other,t):
    a=[int(color[i:i+2],16) for i in (1,3,5)];b=[int(other[i:i+2],16) for i in (1,3,5)]
    return '#'+''.join(f'{round(x+(y-x)*t):02x}' for x,y in zip(a,b))
def ell(x,y,rx,ry): return f'M{x-rx:g},{y:g} a{rx:g},{ry:g} 0 1,0 {rx*2:g},0 a{rx:g},{ry:g} 0 1,0 {-rx*2:g},0'
def path(d,fill,stroke=None,width=1):
    o={'d':d,'fill':fill}
    if stroke: o.update(stroke=stroke,**{'stroke-width':str(width),'stroke-linecap':'round','stroke-linejoin':'round'})
    return o
def face(kind,expression='calm'):
    out=[]
    def p(d,c,stroke=None,w=1): out.append(path(d,c,stroke,w))
    def e(x,y,rx,ry,c): p(ell(x,y,rx,ry),c)
    dark='#3e3439';rose='#e2a3a8';cream='#fff9f0'
    frog=kind=='frog';eyeY=31.5 if frog else 64;xs=(46,82) if frog else (48,80)
    for i,x in enumerate(xs):
        if frog: e(x,30,5.5,6.5,'#fffdf5')
        wink=(expression=='wink' and i==1) or (expression=='cheeky' and i==0)
        closed=expression in ('smile','delighted')
        if expression=='love':
            p(f'M{x},{eyeY+4.8} C{x-9.6},{eyeY-.96} {x-3.36},{eyeY-8.16} {x},{eyeY-2.16} C{x+3.36},{eyeY-8.16} {x+9.6},{eyeY-.96} {x},{eyeY+4.8}', '#c98092')
        elif wink or closed:
            p(f'M{x-4},{eyeY+1} Q{x},{eyeY-5} {x+4},{eyeY+1}','none',dark,2.1)
        elif expression=='focused':
            e(x,eyeY+1,4.2,4.4,dark);e(x-1.3,eyeY-.8,1.1,1.5,'#ffffff')
            p(f'M{x-5},{eyeY-6} L{x+4},{eyeY-5}','none',dark,1.3)
        else:
            e(x,eyeY,3.1 if frog else 4.5,7.1 if expression=='surprised' else 4.1 if frog else 6.1,dark)
            e(x-.5,eyeY+2,2 if frog else 3,1.8,'#6e5052')
            e(x-1.5,eyeY-2,1.1 if frog else 1.6,1.5 if frog else 2,'#ffffff')
            e(x+1.3,eyeY+2.5,.7,.8,'#fff1d9')
    for x in (39,89):
        e(x,74 if kind in ('frog','jellyfish') else 77,7,3.5,rose)
        p(f'M{x-3},76 L{x-2},79 M{x+1},76 L{x+2},79','none','#edc1bd',.9)
    beaks=kind in ('bird','duck','penguin','crane','phoenix')
    mouthY=74 if kind=='jellyfish' else 78 if frog else 85 if kind in ('pig','cow','koala') else 83
    if kind=='pig':
        e(60,73,1.8,2.6,'#b77c74');e(68,73,1.8,2.6,'#b77c74')
    elif kind=='cow':
        e(57,74,2.2,2.8,'#bb8a84');e(71,74,2.2,2.8,'#bb8a84')
    elif not beaks and kind not in ('frog','jellyfish','koala'):
        p('M60.5,76 Q64,73.3 67.5,76 Q67,79 64,80 Q61,79 60.5,76',dark)
        e(62.5,75.5,1.2,.6,'#9e7880')
    if expression in ('delighted','cheeky','surprised'):
        e(64,mouthY,4 if expression=='surprised' else 6,5 if expression=='surprised' else 5.2,dark)
        e(64,mouthY+2.5,3.2,2,'#e9a6b0')
        if expression=='cheeky': e(66,mouthY+5,3,3,'#e9a6b0')
    elif not beaks:
        p(f'M57,{mouthY-1} Q64,{mouthY+5} 71,{mouthY-1}','none',dark,1.7)
    else: p('M59,80 Q64,83 69,80','none',dark,1.2)
    return out

def layers(sp):
    kind=sp['id'];root=ET.parse(SRC/'source/svg'/f'{kind}.svg').getroot()
    items=[dict(n.attrib) for n in root if n.tag.endswith('path')]
    firstEye=next((i for i,p in enumerate(items) if ('a3.7,5.4' in p['d'] and p.get('fill')=='#46505A')),None)
    if kind=='frog': firstEye=next(i for i,p in enumerate(items) if p['d'].startswith('M40.5,30'))
    assert firstEye is not None,kind
    items=items[1:firstEye] # Ground shadow is rendered separately from the animated character.
    if kind=='whale': cut=1
    elif kind=='jellyfish': cut=0
    else: cut=next(i for i,p in enumerate(items) if p['d'].startswith('M32,88 a32,24'))
    back,body=items[:cut],items[cut:]
    if kind=='ninefox':
        # Keep GLM's golden palette and forehead jewel, but give the nine-tailed fox
        # a readable fan of nine soft pointed tails and proper fox ears.
        back=[]
        for i,angle in enumerate((-102,-76,-51,-26,0,26,51,76,102)):
            a=math.radians(angle);dx=math.sin(a);dy=-math.cos(a);px=-dy;py=dx
            tip=(64+dx*59,88+dy*72)
            start=(64+px*6,94+py*6);end=(64-px*6,94-py*6)
            c1=(64+dx*40+px*18,88+dy*52+py*18)
            c2=(64+dx*40-px*18,88+dy*52-py*18)
            d=f'M{start[0]:.2f},{start[1]:.2f} Q{c1[0]:.2f},{c1[1]:.2f} {tip[0]:.2f},{tip[1]:.2f} Q{c2[0]:.2f},{c2[1]:.2f} {end[0]:.2f},{end[1]:.2f} Z'
            back.append(path(d,'#E8B25E' if i%2==0 else '#D9A24E'))
        back += [path('M30,47 Q25,33 30,16 Q47,22 51,35 Z','#E8B25E'),path('M77,35 Q81,22 98,16 Q103,33 98,47 Z','#E8B25E'),path('M32,36 L33,23 L43,34 Z','#F7D6BC'),path('M85,34 L95,23 L96,36 Z','#F7D6BC')]
        body.append(path('M30,73 Q40,72 47,82 Q56,78 64,86 Q72,78 81,82 Q88,72 98,73 Q89,94 64,94 Q39,94 30,73 Z','#F7E7C8'))
    return back,body

def styled(p):
    p=dict(p);fill=p.get('fill','none')
    if fill!='none' and fill.startswith('#'):
        p['gradient']=(blend(fill,'#fff9f1',.40),fill,blend(fill,'#625665',.23))
        # Line weight is deliberate at the small keyboard scale, with soft colored ink.
        p['stroke']=blend(fill,'#53414b',.48);p['stroke-width']='1.25';p['stroke-linecap']='round';p['stroke-linejoin']='round'
    return p

def xml(paths,name):
    root=ET.Element('vector',{f'{{{A}}}width':'128dp',f'{{{A}}}height':'128dp',f'{{{A}}}viewportWidth':'128',f'{{{A}}}viewportHeight':'128'})
    if not paths: paths=[path('M0,0 L0,0','#00000000')]
    for i,p in enumerate(paths):
        attrs={f'{{{A}}}pathData':p['d'],f'{{{A}}}fillColor':p.get('fill','none') if p.get('fill')!='none' else '#00000000'}
        for k,v in [('stroke','strokeColor'),('stroke-width','strokeWidth'),('stroke-linecap','strokeLineCap'),('stroke-linejoin','strokeLineJoin')]:
            if k in p: attrs[f'{{{A}}}{v}']=p[k]
        node=ET.SubElement(root,'path',attrs)
        if 'gradient' in p:
            node.attrib.pop(f'{{{A}}}fillColor')
            attr=ET.SubElement(node,f'{{{AA}}}attr',{'name':'android:fillColor'})
            g=ET.SubElement(attr,'gradient',{f'{{{A}}}type':'linear',f'{{{A}}}startX':'30',f'{{{A}}}startY':'24',f'{{{A}}}endX':'90',f'{{{A}}}endY':'113'})
            for offset,col in zip(('0','0.5','1'),p['gradient']): ET.SubElement(g,'item',{f'{{{A}}}offset':offset,f'{{{A}}}color':col})
    ET.indent(root);(DRW/f'{name}.xml').write_text('<!-- SPDX-License-Identifier: GPL-3.0-or-later -->\n'+ET.tostring(root,encoding='unicode')+'\n')

def svg(paths):
    defs=[];body=[]
    for i,p in enumerate(paths):
        p=dict(p)
        if 'gradient' in p:
            g=p.pop('gradient');gid=f'g{i}'
            defs.append(f'<linearGradient id="{gid}" x1="30" y1="24" x2="90" y2="113" gradientUnits="userSpaceOnUse">'+''.join(f'<stop offset="{t}" stop-color="{col}"/>' for t,col in zip(('0','.5','1'),g))+'</linearGradient>')
            p['fill']=f'url(#{gid})'
        body.append('<path '+ ' '.join(f'{k}="{escape(v)}"' for k,v in p.items())+'/>')
    return '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128"><defs>'+''.join(defs)+'</defs>'+''.join(body)+'</svg>'

def export():
    sheets=[]
    for sp in ROSTER:
        kind=sp['id'];back,body=layers(sp);back=list(map(styled,back));body=list(map(styled,body))
        # Subtle forehead reflection and cheek tufts create a hand-inked cartoon finish.
        if kind not in ('frog','jellyfish'):
            body.extend([path('M36,63 Q33,69 36,73 M92,63 Q95,69 92,73','none',blend(sp['color'],'#fff9f1',.7),1.4)])
        xml(back,f'pet_{kind}_back');xml(body,f'pet_{kind}_body')
        full=back+body+face(kind)
        xml(full,f'pet_{kind}');(SRC/f'{kind}.svg').write_text(svg(full))
        sheets.append((sp,full))
    parts=['<svg xmlns="http://www.w3.org/2000/svg" width="1452" height="1516"><rect width="1452" height="1516" rx="28" fill="#f3f1ee"/>']
    for i,(sp,paths) in enumerate(sheets):
        x=24+(i%6)*238;y=20+(i//6)*248
        art=svg(paths).replace('viewBox="0 0 128 128"','viewBox="0 0 128 128" x="'+str(x+24)+'" y="'+str(y+4)+'" width="184" height="184"')
        # Nested SVG owns its gradient ids so each portrait stays self-contained.
        art=re.sub(r'g(\d+)',lambda m:f'{sp["id"]}g{m[1]}',art)
        parts.append(f'<rect x="{x}" y="{y}" width="220" height="232" rx="24" fill="#fffcf7"/>'+art+f'<text x="{x+110}" y="{y+206}" text-anchor="middle" font-family="sans-serif" font-size="19" fill="#50434b">{sp["name"]}</text><text x="{x+110}" y="{y+223}" text-anchor="middle" font-family="sans-serif" font-size="11" fill="#9c8d92">{sp["rarityLabel"]}</text>')
    parts.append('</svg>');(SRC/'catalog.svg').write_text(''.join(parts))
    demo=['<svg xmlns="http://www.w3.org/2000/svg" width="1152" height="468"><rect width="1152" height="468" rx="28" fill="#f3f1ee"/>']
    cat=next(sp for sp in ROSTER if sp['id']=='cat');bk,bd=layers(cat);base=list(map(styled,bk+bd))
    for i,(expr,label) in enumerate(zip(('calm','smile','wink','focused','surprised','delighted','cheeky','love'),('乖巧','微笑','眨眼','专注','惊讶','开心','吐舌','比心'))):
        x=18+(i%4)*284;y=12+(i//4)*226
        art=svg(base+face('cat',expr)).replace('viewBox="0 0 128 128"',f'viewBox="0 0 128 128" x="{x+44}" y="{y}" width="184" height="184"')
        art=re.sub(r'g(\d+)',lambda m:f'e{i}g{m[1]}',art)
        demo.append(f'<rect x="{x}" y="{y}" width="264" height="214" rx="24" fill="#fffcf7"/>'+art+f'<text x="{x+132}" y="{y+201}" text-anchor="middle" font-family="sans-serif" font-size="18" fill="#50434b">{label}</text>')
    demo.append('</svg>');(SRC/'expressions.svg').write_text(''.join(demo))
    # Generated lookup keeps every resource explicitly referenced after R8 shrinking.
    kt=['/* SPDX-License-Identifier: GPL-3.0-or-later */','// Generated by tools/generate_pet_art.py.','package com.osfans.trime.ui.pets','import com.osfans.trime.R','internal object PetArtwork {','    fun back(id:String)=when(id) {']
    for sp in ROSTER: kt.append(f'        "{sp["id"]}" -> R.drawable.pet_{sp["id"]}_back')
    kt+=['        else -> R.drawable.pet_sheep_back','    }','    fun body(id:String)=when(id) {']
    for sp in ROSTER: kt.append(f'        "{sp["id"]}" -> R.drawable.pet_{sp["id"]}_body')
    kt+=['        else -> R.drawable.pet_sheep_body','    }','}']
    (ROOT/'app/src/main/java/com/osfans/trime/ui/pets/PetArtwork.kt').write_text('\n'.join(kt)+'\n')
if __name__=='__main__': export()
