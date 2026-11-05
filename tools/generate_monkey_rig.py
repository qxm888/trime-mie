#!/usr/bin/env python3
"""Refine the user's GLM monkey into a layered, editable UR hero with a matching arm."""
from pathlib import Path
import xml.etree.ElementTree as ET
from generate_pet_art import path,ell,xml,svg
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'tools/pets/ur';OUT.mkdir(exist_ok=True)
def p(d,c,stroke=None,w=1): return path(d,c,stroke,w)
def e(x,y,rx,ry,c,stroke=None,w=1):return p(ell(x,y,rx,ry),c,stroke,w)
def shade(d,a,b,c,ink='#805b40',w=.8):
 v=p(d,b,ink,w);v['gradient']=(a,b,c);return v
back=[
 p('M43,82 Q29,83 22,112 Q40,125 64,119 Q90,125 108,110 Q101,81 83,81 Z','#b94546','#7c343b',1),
 p('M30,109 Q65,126 100,108','none','#e9bd70',1.5),
 p('M90,107 Q117,113 116,87 Q115,76 105,81 Q99,85 108,91','none','#82583d',5),
 p('M103,26 L112,117','none','#685044',5),p('M103,26 L112,117','none','#c58c3e',3),
 p('M102,26 L103,39 M111,104 L112,117','none','#ffe1a2',6),
]
body=[shade(ell(64,98,25,21),'#687386','#3e495e','#202b3c','#293246'),
 e(49,115,12,6,'#273146','#b89a68'),e(79,115,12,6,'#273146','#b89a68'),
 p('M41,113 Q49,109 58,113 M70,113 Q79,109 87,113','none','#dcc393',1.5),
 shade('M44,87 Q64,80 84,87 L82,105 Q64,114 46,105 Z','#f9df9e','#cfaa62','#967040'),
 p('M48,91 L80,91 M48,96 L80,96 M49,101 L79,101','none','#95713e',1),
 p('M55,85 L57,105 M65,84 L65,108 M75,85 L73,105','none','#ffedbc',.75),
 p('M40,103 Q64,111 88,103 L87,110 Q64,116 41,110 Z','#b94343','#873c3d'),
 e(64,109,4,4,'#f5d289','#a47b45'),e(64,109,1.5,1.5,'#aa4243')]
front=[
 e(29,59,10,12,'#a5764c','#805b40'),e(99,59,10,12,'#a5764c','#805b40'),e(29,59,6,8,'#e1af83'),e(99,59,6,8,'#e1af83'),
 shade('M28,57 Q26,34 45,28 Q45,20 52,17 L56,27 Q64,14 72,18 L70,27 Q100,28 101,56 Q105,84 84,91 Q64,101 44,91 Q24,83 28,57 Z','#d7b482','#ad7d4c','#805c3c'),
 shade('M35,54 Q37,44 47,46 Q57,45 64,54 Q71,45 81,46 Q91,44 94,54 L94,74 Q91,89 64,91 Q37,89 34,74 Z','#fff0d2','#f3d4ae','#d4ac86','#aa805d',.65),
 p('M30,43 Q64,34 98,43 L97,48 Q64,40 31,48 Z','#e5bf68','#a77c38',.8),
 p('M52,44 Q49,36 55,35 Q61,34 64,40 Q67,34 73,35 Q79,36 76,44','none','#ffdf91',2),
 p('M31,47 Q36,41 40,41 M88,41 Q93,42 97,47','none','#fff0c0',.8),
 p('M40,85 Q44,91 43,98 L57,91 L64,96 L73,89 L85,94 L82,84 Q65,96 40,85 Z','#ba4749','#8d383d',.8),
]
arm=[shade('M3,3 Q8,-1 13,3 L12,13 Q8,16 4,13 Z','#d8bd79','#8e7447','#5c4b35','#63513b',.6),
 p('M4,5 L12,5 M4,9 L12,9','none','#f4db99',.7),
 shade('M4,12 Q8,11 12,12 L11,21 L5,21 Z','#69788b','#404d62','#263447','#253145',.6),
 p('M4.5,18 L11.5,18 L11,21 L5,21 Z','#ecc67e','#ae854a',.5),
 p('M5,21 Q8,19 11,21 L12,23 Q13,25 11.7,25.3 L10.5,24.4 L10.5,27 Q10.5,28.2 9.5,28.2 Q8.8,28.2 8.8,27 L8.8,28.2 Q8.8,29 7.8,29 Q7,29 7,28 L7,27.5 Q6.7,28.5 5.8,28 Q5.3,27.6 5.3,26.7 L4.6,26.5 Q3.8,26 4,25 Z','#e0b88b','#8d6548',.45),
 p('M6,23 Q8,22 10,23 M7,24 L7,26 M8.8,24 L8.8,26','none','#bf916b',.4)]
xml(back,'pet_monkey_back');xml(body,'pet_monkey_body');xml(front,'pet_monkey_front')
xml(arm,'pet_monkey_arm')
f=ROOT/'app/src/main/res/drawable/pet_monkey_arm.xml';tree=ET.parse(f);r=tree.getroot();ns='{http://schemas.android.com/apk/res/android}'
for k,v in [('width','16dp'),('height','30dp'),('viewportWidth','16'),('viewportHeight','30')]:r.set(ns+k,v)
f.write_text('<!-- SPDX-License-Identifier: GPL-3.0-or-later -->\n'+ET.tostring(r,encoding='unicode')+'\n')
# Static master includes matching folded/rest arms; runtime arms are independent model layers.
face=[e(48,64,4.5,6.1,'#6b422a'),e(80,64,4.5,6.1,'#6b422a'),e(46.5,61.5,1.5,1.9,'#ffffff'),e(78.5,61.5,1.5,1.9,'#ffffff'),e(64,76,3.2,1.8,'#9d694d'),p('M57,82 Q64,88 71,82','none','#704c3b',1.6)]
xml(back+body+front+face,'pet_monkey');(OUT/'monkey-refined.svg').write_text(svg(back+body+front+face))
(OUT/'monkey-arm.svg').write_text(svg(arm).replace('viewBox="0 0 128 128"','viewBox="0 0 16 30"'))
