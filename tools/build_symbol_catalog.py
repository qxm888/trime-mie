#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""Build compact shared data, preserving emoji sequences and all source items (no page cap)."""
from pathlib import Path
import json,re,hashlib
ROOT=Path(__file__).resolve().parents[1]
DATA=ROOT/'tools/data/unicode'
old=json.loads((ROOT/'output/keyboard-mint/symbols.json').read_text())
unicode={}
for line in (DATA/'UnicodeData.txt').read_text().splitlines():
 parts=line.split(';'); unicode[int(parts[0],16)]=(parts[1],parts[2])
categories=[]
def category(id,name,items,wide=False):
 seen=set();values=[]
 for item in items:
  if not item or item in seen:continue
  seen.add(item);values.append(item)
 categories.append(dict(id=id,name=name,wide=wide,items=values))
def chars(*ranges,cats=None):
 return [chr(cp) for a,b in ranges for cp in range(a,b+1) if cp in unicode and (cats(unicode[cp][1]) if cats else unicode[cp][1][0] not in 'CZ')]
def merged(id,name,*ranges,extra=(),cats=None):category(id,name,old.get(id,[])+list(extra)+chars(*ranges,cats=cats))
group_names={'Smileys & Emotion':('emoji','笑脸与情感'),'People & Body':('people','人物与手势'),'Component':('components','肤色与发型'),'Animals & Nature':('animals','动物与自然'),'Food & Drink':('food','食物与饮品'),'Travel & Places':('travel','旅行与地点'),'Activities':('activities','活动与运动'),'Objects':('objects','物品与工具'),'Symbols':('emoji_symbols','标志与图案'),'Flags':('flags','旗帜')}
groups={k:[] for k in group_names};all_emoji=[]
for line in (DATA/'emoji-test.txt').read_text().splitlines():
 if line.startswith('# group: '): group=line[9:];continue
 match=re.match(r'^([0-9A-F ]+)\s*;\s*(fully-qualified|component)\s*#',line)
 if match:
  value=''.join(chr(int(c,16)) for c in match[1].split());groups[group].append(value);all_emoji.append(value)
for group,items in groups.items():
 id,name=group_names[group];base=[v for v in items if not any(0x1F3FB<=ord(c)<=0x1F3FF for c in v)]
 if group=='Component':base=items
 category(id,name,base)
 variants=[v for v in items if v not in set(base)]
 if variants:category(id+'_tones',name+' · 肤色组合',variants)
category('emoji_full','全部 Emoji',all_emoji)
category('common','常用符号',old['common']+list('✓✔✕✖✗✘✅❎❌❤♡♥★☆⭐✨※＊•·…⋯—–～〜©®™℠℗§¶'))
merged('english','英文标点',(0x21,0x2F),(0x3A,0x40),(0x5B,0x60),(0x7B,0x7E))
merged('chinese','中文与全角标点',(0x3000,0x303F),(0xFE10,0xFE1F),(0xFE30,0xFE4F),(0xFF01,0xFF65))
merged('punctuation','通用标点与括号',(0x2000,0x206F),(0x2E00,0x2E7F))
merged('math','数学运算与集合',(0x2200,0x22FF),(0x27C0,0x27EF),(0x2980,0x29FF),(0x2A00,0x2AFF))
merged('arrows','方向与箭头',(0x2190,0x21FF),(0x27F0,0x27FF),(0x2900,0x297F),(0x2B00,0x2BFF),(0x1F800,0x1F8FF))
merged('geometry','几何图形',(0x25A0,0x25FF))
merged('box','制表线与方块',(0x2500,0x259F),(0x1FB00,0x1FBFF))
merged('list','序号与圈字',(0x2460,0x24FF),(0x2776,0x2793),(0x3200,0x32FF),(0x1F100,0x1F1AD))
merged('numbers','上下标与分数',(0x2070,0x209F),(0x2150,0x218F),extra=list('⁰¹²³⁴⁵⁶⁷⁸⁹₀₁₂₃₄₅₆₇₈₉'))
merged('currency','货币',extra=[chr(cp) for cp,(_,c) in unicode.items() if c=='Sc'])
merged('unit','单位与度量',(0x2100,0x214F),(0x3300,0x33FF))
merged('symbollist','杂项与技术符号',(0x2300,0x23FF),(0x2400,0x245F),(0x2600,0x26FF),(0x2700,0x27BF))
merged('music','音乐与乐谱',(0x1D100,0x1D1FF),extra=list('♩♪♫♬♭♮♯'))
merged('games','棋类与纸牌',(0x1F000,0x1F02F),(0x1F0A0,0x1F0FF),(0x1FA00,0x1FA6F))
merged('braille','盲文点字',(0x2800,0x28FF))
merged('ids','汉字结构与部首',(0x2FF0,0x2FFF),(0x2E80,0x2EFF),(0x2F00,0x2FDF),extra=['㇯'])
merged('pinyin','拼音声调',extra=list('āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜüńňǹḿêếề')+['ê̄','ê̌'])
merged('bopomofo','注音符号',(0x3100,0x312F),(0x31A0,0x31BF))
merged('latin','拉丁字母',(0x00C0,0x024F),(0x1E00,0x1EFF),cats=lambda c:c[0]=='L')
merged('kana','平假名与片假名',(0x3040,0x30FF),(0x31F0,0x31FF))
merged('greek','希腊字母',(0x0370,0x03FF),(0x1F00,0x1FFF))
merged('russian','西里尔字母',(0x0400,0x052F))
merged('korean','韩文字母',(0x1100,0x11FF),(0x3130,0x318F),(0xA960,0xA97F),(0xD7B0,0xD7FF))
merged('phonetic','国际音标',(0x0250,0x02FF),(0x1D00,0x1DBF))
merged('combo','组合附加符号',(0x0300,0x036F),(0x1AB0,0x1AFF),(0x1DC0,0x1DFF),(0x20D0,0x20FF),(0xFE20,0xFE2F))
category('kaomoji','颜文字',old['kaomoji'],wide=True)
category('weather','天气',old['weather'])
category('special','特殊符号',old['special'])
# Preserve every legacy non-emoji entry, even when no longer first-choice ordering.
byid={c['id']:c for c in categories}
for key,items in old.items():
 if key not in ('emoji','emoji_full'):
  assert set(items)<=set(byid[key]['items']),key
assert len(all_emoji)==len(set(all_emoji))
result=dict(version=1,emojiVersion=re.search(r'# Version: (.+)',(DATA/'emoji-test.txt').read_text())[1],categories=categories)
output=ROOT/'app/src/main/res/raw/mie_symbol_catalog.json';output.write_text(json.dumps(result,ensure_ascii=False,separators=(',',':'))+'\n')
report=dict(emojiVersion=result['emojiVersion'],emojiSequences=len(all_emoji),categories=len(categories),uniqueEntries=len(set(v for c in categories for v in c['items'])),categoryCounts={c['id']:len(c['items']) for c in categories},sources={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [DATA/'emoji-test.txt',DATA/'UnicodeData.txt']})
(ROOT/'tools/data/unicode/catalog-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:v for k,v in report.items() if k not in ('categoryCounts','sources')},ensure_ascii=False))
