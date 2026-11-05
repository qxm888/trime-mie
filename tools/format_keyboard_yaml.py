#!/usr/bin/env python3
"""Format bundled keyboard YAML as native Trime sections and one mapping per key.
Run after layout generators. This changes presentation only, not IDs, actions or metadata.
"""
from pathlib import Path
import re
import sys
import yaml

class Key(dict): pass
class Dumper(yaml.SafeDumper):
    def ignore_aliases(self,data): return True
Dumper.add_representer(Key,lambda d,v:d.represent_mapping('tag:yaml.org,2002:map',v,flow_style=True))
def format_file(path):
    doc=yaml.safe_load(path.read_text())
    for keyboard in doc.get('preset_keyboards',{}).values():
        if isinstance(keyboard,dict) and isinstance(keyboard.get('keys'),list):
            keyboard['keys']=[Key(k) if isinstance(k,dict) else k for k in keyboard['keys']]
    content=yaml.dump(doc,Dumper=Dumper,allow_unicode=True,sort_keys=False,width=100000)
    lines=['# 同文咩版内置布局 · UTF-8', '# 参考夜澜听雨十列主题：分区说明、每键一行、按行留白。', '# width 为百分比，height 为行高；mie.row 是编辑器行号。', '# 修改绑定和符号请保留原 ID、导航、配色、mie 元数据。','']
    row=None
    for line in content.splitlines():
        if line.startswith('  custom_'):
            name=doc['preset_keyboards'][line.strip()[:-1]].get('name','')
            lines.extend(['',f'  # ── {name} ──']);row=None
        elif line=='preset_keyboards:': lines.extend(['','# ── 键盘布局 ──'])
        if line.lstrip().startswith('- {'):
            m=re.search(r'row: (\d+)',line)
            if m and m[1]!=row:
                row=m[1];lines.extend(['','    # 第 '+row+' 行'])
        lines.append(line)
    path.write_text('\n'.join(lines)+'\n')
if __name__=='__main__':
    raw=Path(__file__).resolve().parents[1]/'app/src/main/res/raw'
    for path in map(Path,sys.argv[1:]) if len(sys.argv)>1 else [raw/n for n in ('mie_builtin_keyboards.yaml','mie_builtin_extra.yaml','mie_builtin_effects.yaml')]: format_file(path)
