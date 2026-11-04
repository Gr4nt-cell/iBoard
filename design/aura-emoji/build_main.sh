set -e
cd "$(dirname "$0")"
S=..
N=../src/noto-emoji
export PYTHONPATH=$PWD/../src/nototools:$PWD/$N:$PWD/$N/third_party/color_emoji
(cd ../figgen && rm -rf cut && python3 cut.py raw cut)
rm -rf m_png m_q m_fix_svg m_fix_png && mkdir -p m_png m_fix_svg
cp l_png/*.png m_png/
# undo the AURA-image overlay left in l_png: re-render those glyphs from the shaded SVGs
python3 - <<'PY'
import glob,os,shutil
for f in glob.glob('../aurafix/clean/*.png'):
    k=os.path.basename(f)[:-4];n='emoji_u'+'_'.join(c.zfill(4) for c in k.split('_'))
    if os.path.exists(f'l_svg/{n}.svg'): shutil.copy(f'l_svg/{n}.svg',f'm_fix_svg/{n}.svg')
PY
node render_lumo.js m_fix_svg m_fix_png 170 160 >/dev/null && cp m_fix_png/*.png m_png/
python3 - <<'PY'
import io,glob,os,json,sys
sys.path.insert(0,'../aurafluent')
from grade import aura_grade
from PIL import Image
def place(img,box):
    bb=img.getchannel('A').getbbox();img=img.crop(bb)
    s=box/max(img.size);img=img.resize((max(1,round(img.width*s)),max(1,round(img.height*s))),Image.LANCZOS)
    c=Image.new('RGBA',(170,160),(0,0,0,0));c.alpha_composite(img,((170-img.width)//2,(160-img.height)//2));return c
def fname(cps): return 'emoji_u'+'_'.join(c.lower().zfill(4) for c in cps if c.lower()!='fe0f')+'.png'
def graded(p):
    b=io.BytesIO();place(Image.open(p).convert('RGBA'),150).save(b,'PNG');return Image.open(io.BytesIO(aura_grade(b.getvalue())))
tones={'Light':'1f3fb','Medium-Light':'1f3fc','Medium':'1f3fd','Medium-Dark':'1f3fe','Dark':'1f3ff'}
F='../src/fluentui-emoji/assets';nf=0
for d in sorted(os.listdir(F)):
    try: meta=json.load(open(f'{F}/{d}/metadata.json'))
    except Exception: continue
    base=meta['unicode'].split()
    p=glob.glob(f'{F}/{d}/3D/*.png') or glob.glob(f'{F}/{d}/Default/3D/*.png')
    if p: graded(p[0]).save('m_png/'+fname(base)); nf+=1
    for folder,code in tones.items():
        p=glob.glob(f'{F}/{d}/{folder}/3D/*.png')
        sk=[s.split() for s in meta.get('unicodeSkintones',[]) if code in s.split()]
        if p and sk: graded(p[0]).save('m_png/'+fname(sk[0])); nf+=1
na=0
for f in glob.glob('../figgen/cut/*.png'):
    k=os.path.basename(f)[:-4]; place(Image.open(f).convert('RGBA'),154).save('m_png/'+fname(k.split('_'))); na+=1
print('fluent graded',nf,'figma AURA-style',na,'total',len(os.listdir('m_png')))
PY
node squant.js m_png m_q
python3 - <<'PY'
t=open('lumo.tmpl.ttx.tmpl').read()
t=t.replace('Lumo Emoji','AURA Emoji').replace('LumoEmoji-Regular','AURAEmoji-Regular')
import re
t=re.sub(r'(<namerecord nameID="0"[^>]*>\s*)[^<]*?(\s*</namerecord>)',r'\1Copyright 2026 iBoard. Fluent Emoji 3D artwork Copyright (c) Microsoft Corporation (MIT License). Some artwork adapted from Twemoji, Copyright 2020 Twitter, Inc and other contributors (CC BY 4.0).\2',t,count=1)
t=re.sub(r'(<namerecord nameID="13"[^>]*>\s*)[^<]*?(\s*</namerecord>)',r'\1AURA Emoji for iBoard. Most-used emoji generated for iBoard in Figma (AURA style). Other glyphs: Microsoft Fluent Emoji 3D (MIT License) colour-graded to the AURA style; remaining glyphs adapted from Twemoji (CC BY 4.0) with 3D shading.\2',t,count=1)
open('aura.tmpl.ttx.tmpl','w').write(t)
PY
python3 $N/add_glyphs.py -f aura.tmpl.ttx.tmpl -o aura.tmpl.ttx -d m_q >/dev/null 2>&1
rm -f aura.tmpl.ttf aura-emoji.ttf
ttx -q aura.tmpl.ttx
python3 $N/third_party/color_emoji/emoji_builder.py -S -V aura.tmpl.ttf aura-emoji.ttf m_q/emoji_u | tail -1
python3 - <<'PY'
import os
keys=sorted('_'.join('%x'%int(c,16) for c in f[7:-4].split('_')) for f in os.listdir('m_q') if f.startswith('emoji_u'))
open('supported_emoji.txt','w').write('\n'.join(keys)+'\n');print('supported',len(keys))
from fontTools.ttLib import TTFont
f=TTFont('aura-emoji.ttf');print(f['name'].getDebugName(1),len(f.getGlyphOrder()),os.path.getsize('aura-emoji.ttf'))
PY
