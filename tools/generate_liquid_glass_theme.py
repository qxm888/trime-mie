#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Complete liquid-glass stock family; native caps keep live blur and editing available."""
import io
import json
import zipfile
from pathlib import Path
import yaml
from premium.liquid_glass import BACKGROUND, backdrop

ROOT = Path(__file__).resolve().parents[1]
RAW = ROOT / 'app/src/main/res/raw'

class Dumper(yaml.SafeDumper):
    def ignore_aliases(self, data): return True

def generate():
    source = yaml.safe_load((RAW / 'mie_builtin_effects.yaml').read_text())['preset_keyboards']
    pages = {}
    for page_id, page in source.items():
        if page_id != 'custom_cloudsea' and not page_id.startswith('custom_cloudsea_'): continue
        kb = json.loads(json.dumps(page, ensure_ascii=False).replace('custom_cloudsea', 'custom_liquid_glass').replace('云汐·软屿', '液态玻璃'))
        kb['name'] = kb['name'].replace('液态玻璃键盘', '液态玻璃')
        kb['keyboard_back_color'] = BACKGROUND
        mie = kb['mie']
        mie['toolbarBackground'] = {'type': 'TRANSPARENT'}
        mie['unifiedBackground'] = True
        app = mie['keyAppearance']
        app.update(keyBackColor='#62FFFFFF', keyTextColor='#17221E', keySymbolColor='#34443C', keyBorderColor='#BEFFFFFF',
                   keyBorder='0', roundCorner='13', symbolTextSize='9', hintTextSize='9')
        for key in kb['keys']:
            if not key.get('click') and not key.get('label') and not key.get('icon'): continue
            action = key.get('click', '')
            functional = action.startswith('Keyboard_') or action in ('BackSpace', 'Return', 'Mode_switch') or key.get('icon')
            color = '#806DAFBE' if action == 'Return' else '#78FFFFFF' if functional else '#6AFFFFFF'
            key.update(key_back_color=color, key_text_color='#17221E', key_symbol_color='#34443C', round_corner='13', key_border='0',
                       key_border_color='#BEFFFFFF', hilited_key_back_color='#B8F4FDFF', hilited_key_text_color='#153B50',
                       key_on_back_color='#A276BDD3', key_on_text_color='#153B50', key_on_border_color='#E6FFFFFF')
            if key.get('icon'): key['icon_tint'] = '#17221E'
        for button in mie['toolbar']:
            name = Path(button['icon']).stem.removeprefix('tb_')
            button['icon'] = f'icons/mie2_tb_{name}.png'
            button['iconSize'] = 19
            button['appearance'] = {'keyBackColor': '#40FFFFFF' if name in ('menu', 'hide', 'float') else '#00000000',
                                    'roundCorner': '11', 'keyBorder': '0', 'iconTint': '#17221E'}
        effects = dict(
            shadow=dict(enabled=True, color='#20313831', blur=1.8, offsetX=0, offsetY=.7, spread=-.4),
            innerShadow=dict(enabled=True, color='#12434236', blur=1, offsetX=0, offsetY=.4),
            gradient=dict(enabled=True, startColor='#2FFFFFFF', endColor='#06FFFFFF', angle=90),
            borderGradient=dict(enabled=True, startColor='#E6FFFFFF', endColor='#50FFFFFF', angle=65, width=.65),
            textShadow=dict(enabled=False), gloss=True, glossStrength=5, noise=False, edge3d=False,
            pressScale=2, pressSink=.6, pressDarken=0, ripple=True, rippleColor='#3679CDE9', animationMs=100)
        mie['surfaceAppearance'] = dict(
            keys=dict(radius=13), toolbar=dict(radius=11, borderWidth=0),
            bar=dict(radius=17, borderWidth=.6, borderColor='#BFFFFFFF', background='#45FFFFFF', blur=False),
            linkToolbar=False, retainToolbarBackground=True, keyOpacity=100, keyBlur=0, blurRadius=12,
            blurFrameRate=10, blurSaturation=120, glassOpacity=50,
            backdrop=dict(enabled=True, systemBlur=True, strength=40, imageAlpha=100, imageOpacity=8, fallbackImageOpacity=100),
            gradient=dict(enabled=True, start='#70FFFFFF', center='#66FFFFFF', end='#5CFFFFFF', direction=0),
            effects=effects,
            toolbarEffects=dict(pressScale=2, pressSink=.3, animationMs=100),
            barEffects=dict(gradient=dict(enabled=True, startColor='#38FFFFFF', endColor='#04FFFFFF', angle=90)),
            keyboardCorners=[24, 24, 0, 0], keyboardBorderColor='#BFFFFFFF', keyboardBorderWidth=.6, keyboardBorderGlow=35)
        floating = mie['floatingCandidates']
        floating.update(panel=dict(background='#B3F5F6F0', borderColor='#D9FFFFFF', borderWidth=.65, radius=16, blur=True),
                        normal=dict(background='#00000000', radius=10), highlighted=dict(background='#85FFFFFF', borderColor='#BFFFFFFF', borderWidth=.5, radius=10),
                        ball=dict(background='#B3F5F6F0', borderWidth=.65, borderColor='#D9FFFFFF', radius=24),
                        shadowColor='#30313831', elevation=4, blurRadius=16, glassOpacity=15,
                        panelEffects=dict(gradient=dict(enabled=True, startColor='#20FFFFFF', endColor='#02FFFFFF', angle=90), borderGradient=dict(enabled=True, startColor='#ECFFFFFF', endColor='#42FFFFFF', angle=65, width=.65), gloss=True, glossStrength=5))
        floating['colors'] = {role: '#17221E' if role not in ('label', 'comment', 'highlight.label', 'highlight.comment') else '#34443C'
                              for role in floating['colors']}
        pages[page_id.replace('custom_cloudsea', 'custom_liquid_glass', 1)] = kb
    assert len(pages) == 15
    for page_id, kb in list(pages.items()):
        live = json.loads(json.dumps(kb, ensure_ascii=False).replace('custom_liquid_glass', 'custom_liquid_glass_live'))
        live['name'] = live['name'].replace('液态玻璃', '液态玻璃·动态', 1)
        cfg = live['mie']['surfaceAppearance']
        cfg['backdrop'].update(publicWindow=True, imageOpacity=0)
        cfg['keyboardCorners'] = [24, 24, 24, 24]
        # The system blurs behind the whole floating panel; its own colour remains translucent.
        live['mie']['floatingCandidates']['panel']['blur'] = False
        pages[page_id.replace('custom_liquid_glass', 'custom_liquid_glass_live', 1)] = live
    (RAW / 'mie_builtin_glass.yaml').write_text('# 液态玻璃：原生半透明键帽、实时背景磨砂与完整十五页。由 tools/generate_liquid_glass_theme.py 生成。\n' +
        yaml.dump({'mie': {'format': 'trime-custom-keyboards-yaml', 'version': 1}, 'preset_keyboards': pages},
                  Dumper=Dumper, allow_unicode=True, sort_keys=False, width=140))
    # Preserve every existing byte; repair on upgrade is driven by the asset manifest.
    pack = RAW / 'mie_premium_materials.zip'
    with zipfile.ZipFile(pack) as z: assets = {item.filename: z.read(item) for item in z.infolist()}
    png = io.BytesIO(); backdrop().save(png, format='PNG', optimize=True); assets[BACKGROUND] = png.getvalue()
    with zipfile.ZipFile(pack, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, data in sorted(assets.items()):
            info = zipfile.ZipInfo(name, date_time=(2026, 11, 3, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, data)
    (RAW / 'mie_premium_materials_manifest.txt').write_text('\n'.join(sorted(assets)) + '\n')
    print(f'Liquid glass: {len(pages)} pages; {len(assets)} shared assets; background {len(png.getvalue())} bytes')

if __name__ == '__main__': generate()
