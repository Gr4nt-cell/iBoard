import sys,os,glob
from PIL import Image
from concurrent.futures import ProcessPoolExecutor
src,dst=sys.argv[1],sys.argv[2];os.makedirs(dst,exist_ok=True)
def q(f):
    im=Image.open(f).convert('RGBA')
    p=im.quantize(colors=256,method=Image.Quantize.FASTOCTREE,dither=Image.Dither.NONE)
    p.save(os.path.join(dst,os.path.basename(f)),optimize=True)
with ProcessPoolExecutor() as ex: list(ex.map(q,sorted(glob.glob(src+'/*.png')),chunksize=64))
