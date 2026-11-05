import sys,numpy as np
from PIL import Image
ks=sys.argv[2:];rows=[]
for b in ks:
    p=b.split('_');row=[Image.open(f'figgen/cut/{b}.png').convert('RGBA')]+[Image.open(f"tone/out/{'_'.join([p[0],t]+p[1:])}.png") for t in ['1f3fb','1f3fc','1f3fd','1f3fe','1f3ff']]
    rows.append(np.hstack([np.array(r.resize((110,110))) for r in row]))
im=Image.fromarray(np.vstack(rows));bg=Image.new('RGBA',im.size,(235,235,240,255));bg.alpha_composite(im);bg.convert('RGB').save(sys.argv[1])
