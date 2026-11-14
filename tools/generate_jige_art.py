#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Original layered vector companion based on the user's yellow chick reference.
Run from any directory. Exports Android vectors and an editable SVG portrait.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
from html import escape
ROOT=Path(__file__).resolve().parents[1]
ART=ROOT/'docs/artwork/jige';ART.mkdir(parents=True,exist_ok=True)
DRAW=ROOT/'app/src/main/res/drawable'
A='http://schemas.android.com/apk/res/android';ET.register_namespace('android',A)
DARK='#19191D';YELLOW='#FFD04A';ORANGE='#F6A52B'
def p(d,fill,stroke=DARK,w=2.2):return dict(d=d,fill=fill,stroke=stroke,**{'stroke-width':str(w),'stroke-linejoin':'round','stroke-linecap':'round'})
body=[
 p('M44,102 Q64,98 85,102 L88,111 Q64,120 40,111 Z',YELLOW),
 p('M40,78 Q62,71 88,80 L91,106 Q67,118 37,106 Z','#18191D'),
 p('M44,81 L43,103 Q66,112 86,104','none','#D8D9DE',2),
 p('M53,98 L78,98 L80,110 Q65,115 51,110 Z','#111216',DARK,1.5),
 p('M44,81 L55,99 L57,110','none','#FFFFFF',4.2),
 p('M86,81 L76,99 L74,110','none','#FFFFFF',4.2),
 p('M52,97 L57,97 L57,101 L52,101 Z','#C8CAD1',DARK,.6),
 p('M74,97 L79,97 L79,101 L74,101 Z','#C8CAD1',DARK,.6),
 p('M40,79 Q49,81 48,94 Q47,98 39,99 L35,96 Q43,94 42,86 L36,84 Z','#FFFFFF',DARK,1.7),
 p('M87,80 Q78,81 80,94 Q81,97 88,98 L92,95 Q84,93 86,85 L91,84 Z','#FFFFFF',DARK,1.7),
 p('M29,48 C27,26 46,17 65,20 C83,14 104,30 102,52 C106,73 90,84 67,83 C43,84 26,71 29,48 Z',YELLOW),
 p('M33,53 Q28,61 35,69 Q42,74 46,67 L45,57 Z','#EF5D48','none',0),
 p('M92,55 Q101,56 100,66 Q98,74 91,68 Z','#EF5D48','none',0),
 p('M34,76 Q60,82 95,75 Q101,75 98,83 Q74,91 35,84 Q29,81 34,76 Z','#101115'),
 p('M36,85 Q64,91 94,84 L91,90 Q66,94 37,89 Z','#1C1D23',DARK,1.7),
]
hair=[
 p('M64,19 C48,9 30,16 24,30 L21,40 L27,43 L26,52 L42,53 L46,39 L47,52 L57,50 L62,34 L65,23 L71,24 L74,40 L86,49 L97,50 L97,43 L103,43 C105,27 88,11 74,16 L67,19 Z','#BEC0C3',DARK,2.8),
 p('M61,20 Q43,16 31,35 L30,46','none','#E5E5E3',3.1),
 p('M57,24 Q47,32 45,43 M39,30 L35,48 M80,22 Q92,29 94,40','none','#E7E7E4',1.7),
 p('M52,29 L49,46 M86,28 L89,44 M66,24 L62,36','none','#92949A',1.3),
]
wing=[p('M41,85 Q30,81 24,89 Q17,98 23,103 Q32,108 43,98 Z',YELLOW),p('M39,84 Q32,82 31,87 L33,98 L41,100 Q48,91 39,84 Z','#202127'),p('M32,85 Q40,87 41,96','none','#FFFFFF',2)]
feet=[p('M47,109 Q49,106 57,110 L58,118 Q56,124 43,122 Q36,121 39,116 Z',ORANGE),p('M73,110 Q80,106 83,112 L91,118 Q92,123 80,123 L71,120 Z',ORANGE)]
eyes=[p('M51,52 a10,12 0 1,0 20,0 a10,12 0 1,0 -20,0','#FFFFF4',DARK,1.6),p('M74,53 a9,11 0 1,0 18,0 a9,11 0 1,0 -18,0','#FFFFF4',DARK,1.6),p('M63,54 a3.5,5.5 0 1,0 7,0 a3.5,5.5 0 1,0 -7,0',DARK,'none',0),p('M83,55 a3,5 0 1,0 6,0 a3,5 0 1,0 -6,0',DARK,'none',0)]
beak=[p('M64,67 Q76,55 87,66 Q89,73 76,76 Q65,75 64,67 Z',ORANGE),p('M65,68 Q76,71 86,67','none',DARK,1.6),p('M69,64 Q76,61 81,64','none','#FFE392',1.5)]
def paths(items):return ''.join('<path '+' '.join(f'{k}="{escape(v)}"' for k,v in x.items())+'/>' for x in items)
def vector(name,items):
 root=ET.Element('vector',{f'{{{A}}}width':'128dp',f'{{{A}}}height':'128dp',f'{{{A}}}viewportWidth':'128',f'{{{A}}}viewportHeight':'128'})
 keys={'d':'pathData','fill':'fillColor','stroke':'strokeColor','stroke-width':'strokeWidth','stroke-linecap':'strokeLineCap','stroke-linejoin':'strokeLineJoin'}
 for x in items:
  ET.SubElement(root,'path',{f'{{{A}}}{keys[k]}':('#00000000' if v=='none' else v) for k,v in x.items()})
 ET.indent(root);(DRAW/f'pet_jige_{name}.xml').write_text('<!-- SPDX-License-Identifier: GPL-3.0-or-later -->\n'+ET.tostring(root,encoding='unicode')+'\n')
for name,items in [('body',body),('hair',hair),('wing',wing)]:vector(name,items)
svg='<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128">'+paths(feet+wing)+'<g transform="translate(128 0) scale(-1 1)">'+paths(wing)+'</g>'+paths(body+eyes+beak+hair)+'</svg>'
(ART/'jige.svg').write_text(svg)
print('Generated three Android layers and docs/artwork/jige/jige.svg')
