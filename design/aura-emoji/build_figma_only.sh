set -e
cd "$(dirname "$0")"
N=../src/noto-emoji
export PYTHONPATH=$PWD/../src/nototools:$PWD/$N:$PWD/$N/third_party/color_emoji
: # cut images are kept in ../figgen/cut (batch 4 cut with oa/cut_holes.py)
rm -rf fo_png fo_q && mkdir fo_png
python3 - <<'PY'
import glob,os
from PIL import Image
for f in glob.glob('../figgen/cut/*.png'):
    k=os.path.basename(f)[:-4];img=Image.open(f).convert('RGBA');bb=img.getchannel('A').getbbox();img=img.crop(bb)
    s=154/max(img.size);img=img.resize((round(img.width*s),round(img.height*s)),Image.LANCZOS)
    c=Image.new('RGBA',(170,160),(0,0,0,0));c.alpha_composite(img,((170-img.width)//2,(160-img.height)//2))
    c.save('fo_png/emoji_u'+'_'.join(x.zfill(4) for x in k.split('_'))+'.png')
print('glyphs',len(os.listdir('fo_png')))
PY
node squant.js fo_png fo_q >/dev/null
python3 - <<'PY'
import re
t=open('aura.tmpl.ttx.tmpl').read()
t=re.sub(r'(<namerecord nameID="0"[^>]*>\s*)[^<]*?(\s*</namerecord>)',r'\1Copyright 2026 iBoard.\2',t,count=1)
t=re.sub(r'(<namerecord nameID="13"[^>]*>\s*)[^<]*?(\s*</namerecord>)',r'\1AURA Emoji for iBoard. Every glyph generated for iBoard with AI (Figma AI, OpenArt Nano Banana 2) in the AURA style.\2',t,count=1)
open('aura_fo.tmpl.ttx.tmpl','w').write(t)
PY
python3 $N/add_glyphs.py -f aura_fo.tmpl.ttx.tmpl -o aura_fo.tmpl.ttx -d fo_q >/dev/null 2>&1
rm -f aura_fo.tmpl.ttf aura-emoji-fo.ttf
ttx -q aura_fo.tmpl.ttx
python3 $N/third_party/color_emoji/emoji_builder.py -S -V aura_fo.tmpl.ttf aura-emoji-fo.ttf fo_q/emoji_u | tail -1
python3 - <<'PY'
import os
keys=sorted('_'.join('%x'%int(c,16) for c in f[7:-4].split('_')) for f in os.listdir('fo_q') if f.startswith('emoji_u'))
open('supported_emoji_fo.txt','w').write('\n'.join(keys)+'\n');print('supported',len(keys),'font',os.path.getsize('aura-emoji-fo.ttf'))
PY

python3 fix_vs.py aura-emoji-fo.ttf aura-emoji-fo.ttf emoji-test.txt
