#!/usr/bin/env python3
"""Illustration assembly preview, not an Android/device screenshot."""
from pathlib import Path
from base64 import b64encode
import math,re
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'tools/pets/ur'
def data(p):return 'data:image/png;base64,'+b64encode(p.read_bytes()).decode()
res=ROOT/'app/src/main/res/drawable-nodpi'
base=data(res/'pet_xiaomie_rig_base.png');arm=data(res/'pet_xiaomie_arm.png')
monkey=(OUT/'monkey-refined.svg').read_text().replace('<svg ','<svg width="128" height="128" ',1);ma=(OUT/'monkey-arm.svg').read_text()
def girl(left=20,right=-20):
 body=f'<svg x="-4" width="128" height="128" viewBox="0 0 623 631" preserveAspectRatio="none"><image href="{base}" width="1246" height="1263"/></svg>'
 for x,angle,mirror in [(53,left,False),(75.5,right,True)]:
  body+=f'<g transform="translate({x} 69.5) rotate({angle}) rotate({-3.28 if mirror else 3.28}) scale({-1 if mirror else 1} 1)"><svg x="-2.98" y="-.35" width="6.22" height="23.3" viewBox="245 280 323 1210" preserveAspectRatio="none"><image href="{arm}" width="887" height="1774"/></svg></g>'
 return body
def hero(left=20,right=-20):
 body=monkey
 for x,a,m in [(43,left,False),(85,right,True)]:body+=f'<g transform="translate({x} 91) rotate({a}) scale({-1 if m else 1} 1) translate(-8 -3)"><svg width="16" height="30" viewBox="0 0 16 30">'+re.sub(r'^<svg[^>]*>|</svg>$','',ma)+'</svg></g>'
 return body
parts=['<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="880"><rect width="1000" height="880" fill="#f3f1ed"/>']
for i,(label,art) in enumerate([('小咩 · 同画风衣袖与人手',girl()),('猴哥 · 金箍、披风与战甲',hero())]):
 x=20+i*490;parts +=[f'<rect x="{x}" y="20" width="470" height="390" rx="24" fill="#fffdf9"/><g transform="translate({x+75} 35) scale(2.5)">',art,'</g>',f'<text x="{x+235}" y="390" font-family="sans-serif" text-anchor="middle" font-size="20" fill="#574b49">{label}</text>']
for i,kind in enumerate(['girl','monkey']):
 parts.append(f'<g transform="translate({40+i*490} 430) scale(2)"><rect x="4" y="92" width="204" height="94" rx="9" fill="#e2e1dc" stroke="#bcbfb8"/>')
 for r,chars in enumerate(['QWERTYUIOP','ASDFGHJKL','ZXCVBNM']):
  for j,c in enumerate(chars):
   x=10+(10-len(chars))*9.6+j*19.2;y=98+r*12
   parts.append(f'<rect x="{x}" y="{y}" width="17" height="10" rx="2" fill="#fffdf7" stroke="#c5c7bc" stroke-width=".5"/><text x="{x+8.5}" y="{y+7}" text-anchor="middle" font-family="sans-serif" font-size="6" fill="#65716b">{c}</text>')
 # Far key: character hops to the front surface with original model proportions.
 scale=84/128;targetX=185 if i==0 else 20;targetY=139
 sx=75.5 if i==0 else 43;sy=69.5 if i==0 else 91;length=(22.74 if i==0 else 26)*scale
 bx=max(-64,min(64,targetX-(64+sx*scale)));dx=targetX-(64+bx+sx*scale)
 by=targetY-(24+sy*scale)-math.sqrt(max(0,length*length-dx*dx))
 angle=math.degrees(math.atan2(-dx/scale,math.sqrt(max(0,length*length-dx*dx))/scale))
 parts.append(f'<circle cx="{targetX}" cy="{targetY}" r="3" fill="#d9ad67"/><g transform="translate({64+bx} {24+by}) scale({scale})">'+(girl(right=angle) if i==0 else hero(left=angle))+'</g></g>')
parts.append('<text x="500" y="855" font-family="sans-serif" text-anchor="middle" font-size="16" fill="#82736d">素材装配示意 · 上方原比例，下方远键落点 · 非真机截图</text></svg>')
(OUT/'ur332-preview.svg').write_text(''.join(parts))
