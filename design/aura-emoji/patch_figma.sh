set -e
cd "$(dirname "$0")"
N=../src/noto-emoji
export PYTHONPATH=$PWD/../src/nototools:$PWD/$N:$PWD/$N/third_party/color_emoji
SRC=${1:-../figgen/cut2}
rm -rf p_new p_newq && mkdir p_new
python3 - "$SRC" <<'PY'
import sys,glob,os
from PIL import Image
for f in glob.glob(sys.argv[1]+'/*.png'):
    k=os.path.basename(f)[:-4];img=Image.open(f).convert('RGBA');bb=img.getchannel('A').getbbox();img=img.crop(bb)
    s=154/max(img.size);img=img.resize((round(img.width*s),round(img.height*s)),Image.LANCZOS)
    c=Image.new('RGBA',(170,160),(0,0,0,0));c.alpha_composite(img,((170-img.width)//2,(160-img.height)//2))
    c.save('p_new/emoji_u'+'_'.join(x.zfill(4) for x in k.split('_'))+'.png')
PY
node squant.js p_new p_newq >/dev/null
cp p_newq/*.png m_q/ && cp p_new/*.png m_png/
python3 $N/add_glyphs.py -f aura.tmpl.ttx.tmpl -o aura.tmpl.ttx -d m_q >/dev/null 2>&1
rm -f aura.tmpl.ttf aura-emoji.ttf
ttx -q aura.tmpl.ttx
python3 $N/third_party/color_emoji/emoji_builder.py -S -V aura.tmpl.ttf aura-emoji.ttf m_q/emoji_u | tail -1
ls -la aura-emoji.ttf
