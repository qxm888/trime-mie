#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Generate the static original fog backdrop. Optional authoring dependency: Pillow."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFilter
import math
p=Path(__file__).resolve().parents[1]/'app/src/main/res/raw/mie_frost_background.png'
w,h=1080,620
im=Image.new('RGB',(w,h));pixels=im.load()
# Static diffuse fog banks: no continuous animation or expensive per-frame textures.
spots=[(.12,.18,.55,(175,202,218)),(.78,.32,.48,(205,223,230)),(.45,.92,.5,(166,199,204)),(.93,.93,.35,(207,211,228))]
for y in range(h):
 for x in range(w):
  weights=[math.exp(-((x/w-sx)**2+(y/h-sy)**2)/(r*r*.5)) for sx,sy,r,c in spots]
  total=sum(weights)
  pixels[x,y]=tuple(round(sum(a*spot[3][i] for a,spot in zip(weights,spots))/total) for i in range(3))
layer=Image.new('RGBA',im.size);d=ImageDraw.Draw(layer)
d.arc((-300,-110,1280,750),12,176,fill=(255,255,255,80),width=70)
d.arc((-180,-210,1320,660),15,172,fill=(243,254,255,76),width=26)
layer=layer.filter(ImageFilter.GaussianBlur(18));im=Image.alpha_composite(im.convert('RGBA'),layer).convert('RGB');im.save(p,optimize=True)
print(p,p.stat().st_size)
