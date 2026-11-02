#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Stock toolbar icons: authored SVG, common 24-unit grid, restrained round strokes."""
from pathlib import Path
import argparse, io, subprocess, zipfile
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1];RAW=ROOT/'app/src/main/res/raw';SVG=ROOT/'tools/icons';SVG.mkdir(exist_ok=True)
paths={
'menu':'<g fill="currentColor" stroke="none"><rect x="3" y="3" width="7.5" height="7.5" rx="1.4"/><rect x="13.5" y="3" width="7.5" height="7.5" rx="1.4"/><rect x="3" y="13.5" width="7.5" height="7.5" rx="1.4"/><rect x="13.5" y="13.5" width="7.5" height="7.5" rx="1.4"/></g>',
 'theme':'<path d="M7.2 3.5 1.8 6.8l2.8 5 2.2-1.4V21h10.4V10.4l2.2 1.4 2.8-5-5.4-3.3c-.7 1.8-2.4 2.8-4.8 2.8S7.9 5.3 7.2 3.5Z"/>',
 'layout':'<rect x="3" y="4" width="18" height="16" rx="2.3"/><path d="M7 8h1m3 0h6M7 12h1m3 0h6M7 16h1m3 0h6"/>',
 'float':'<path d="M12 2v5m-2-3 2-2 2 2M5 5H2m2-2L2 5l2 2m15-2h3m-2-2 2 2-2 2"/><rect x="3" y="10" width="18" height="11" rx="2.2"/><path d="M6.5 13.5h.2m3.4 0h.2m3.4 0h.2m3.4 0h.2M7 17.5h10"/>',
 'onehand':'<rect x="3" y="5" width="18" height="14" rx="2.3"/><path d="M6.5 9h3m4 0h4m-11 4h3m4 0h4M8 16h8"/>',
 'clipboard':'<rect x="5" y="5.5" width="14" height="15.5" rx="2"/><rect x="8.5" y="3" width="7" height="5" rx="1.3" /><path d="M9 12h6m-6 4h6"/>',
 'lang':'<path d="M3 6h9v7H3zM7.5 3v14M15 20l3.3-12 3.3 12M16 16h4.5"/>',
 'hide':'<rect x="3" y="3.5" width="18" height="12.5" rx="2"/><path d="M6.5 7.5h.2m3.4 0h.2m3.4 0h.2m3.4 0h.2M7 12h10m-9 7 4 3 4-3"/>',
 'emoji':'<circle cx="12" cy="12" r="9"/><path d="M7.7 14.7c2.2 2.8 6.4 2.8 8.6 0"/><path d="M8.5 8.8v1.1m7-1.1v1.1"/>',
 'switch':'<path d="M4 8h15m-4-4 4 4-4 4M20 16H5m4-4-4 4 4 4"/>',
 'symbols':'<path d="M9 3 7 21M17 3l-2 18M4 9h17M3 15h17"/>',
}
icons={}
for name,body in paths.items():
 # No text/font glyphs: geometry remains consistent on every device and font.
 text=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" width="144" height="144" color="#665B61" fill="none" stroke="currentColor" stroke-width="1.65" stroke-linecap="round" stroke-linejoin="round">{body}</svg>'
 path=SVG/f'mie2_tb_{name}.svg';path.write_text(text+'\n')
 png=subprocess.check_output(['rsvg-convert','-w','288','-h','288',str(path)])
 image=Image.open(io.BytesIO(png)).convert('RGBA').resize((144,144),Image.Resampling.LANCZOS)
 # Clipboard clip uses background-colour-free interior, keeping truly transparent assets.
 buffer=io.BytesIO();image.save(buffer,format='PNG',optimize=True);icons[f'mie2_tb_{name}.png']=buffer.getvalue()
archive=RAW/'mie_builtin_icons.zip'
with zipfile.ZipFile(archive) as z:retained={n:z.read(n) for n in z.namelist() if not n.startswith(('tb_','mie2_tb_'))}
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for name,data in sorted((retained|icons).items()):
  info=zipfile.ZipInfo(name,date_time=(2026,11,2,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)
preview=Image.new('RGBA',(880,240),'#F8F3F4');draw=ImageDraw.Draw(preview)
for i,(name,data) in enumerate(icons.items()):
 image=Image.open(io.BytesIO(data)).resize((50,50),Image.Resampling.LANCZOS);x=25+(i%6)*144;y=24+(i//6)*112
 preview.alpha_composite(image,(x+32,y));draw.text((x,y+62),name.removeprefix('mie2_tb_').removesuffix('.png'),fill='#665B61')
parser=argparse.ArgumentParser();parser.add_argument('--preview',type=Path,default=ROOT/'build/builtin-toolbar-preview.png');args=parser.parse_args()
args.preview.parent.mkdir(parents=True,exist_ok=True)
preview.convert('RGB').save(args.preview)
print('Generated',len(icons),'new toolbar SVG/PNG icons; retained key assets:',len(retained))
