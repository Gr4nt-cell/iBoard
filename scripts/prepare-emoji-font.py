"""Index supported emoji sequences and extract a few font glyphs for design previews.
Requires fonttools and uharfbuzz. No glyphs or font tables are modified.
"""
from pathlib import Path
import json,uharfbuzz as hb
from fontTools.ttLib import TTFont
root=Path(__file__).resolve().parents[1];assets=root/'app/src/main/assets'
path=assets/'fonts/ios-style.ttf';data=path.read_bytes();face=hb.Face(data);font=hb.Font(face);tt=TTFont(path)
def shape(s):
 b=hb.Buffer();b.add_str(s);b.guess_segment_properties();hb.shape(font,b);return [g.codepoint for g in b.glyph_infos]
def key(s):return '_'.join(format(ord(c),'x') for c in s if ord(c)!=0xfe0f)
sequences=set()
lines=(assets/'emoji.tsv').read_text().splitlines()
for line in lines:
 p=line.split('\t')
 if len(p)<4:continue
 sequences.add(p[1])
 if p[2]=='1':
  for tone in ['🏻','🏼','🏽','🏾','🏿']:sequences.add(p[1][0]+tone+p[1][1:].lstrip('\ufe0f'))
supported={key(s) for s in sequences if (g:=shape(s)) and all(i!=0 for i in g)}
(assets/'fonts/supported_emoji.txt').write_text('\n'.join(sorted(supported))+'\n')
out=root/'design/emoji';out.mkdir(parents=True,exist_ok=True)
for emoji in ['😀','😃','😍','🥰','😎','😂','👍','❤️','🎉','🔥','🇬🇭','🙏']:
 glyphs=shape(emoji)
 if len(glyphs)==1 and glyphs[0] and 'CBDT' in tt:
  name=tt.getGlyphOrder()[glyphs[0]]
  for strike in tt['CBDT'].strikeData:
   if name in strike:
    (out/(key(emoji)+'.png')).write_bytes(strike[name].imageData);break
report={'fontFamily':tt['name'].getDebugName(1),'glyphs':len(tt.getGlyphOrder()),'sequencesChecked':len(sequences),'supportedSequenceKeys':len(supported),'unsupported':sorted(s for s in sequences if key(s) not in supported)}
(root/'design/emoji-coverage.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
print({k:v for k,v in report.items() if k!='unsupported'})
