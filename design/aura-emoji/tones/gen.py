import sys,os,numpy as np,cv2
sys.path.insert(0,'tone')
from PIL import Image
import recolor as R
from skin import lab
from concurrent.futures import ProcessPoolExecutor
os.makedirs('tone/out',exist_ok=True)
def yellow_left(img):
    L=lab(img[...,:3]);op=img[...,3]>200
    y=op&(L[...,0]>150)&(np.hypot((L[...,1]-135.5)*1.5,L[...,2]-203.5)<14)
    return y.sum()/max(op.sum(),1)
def best(a,b):
    opts=[]
    for kw in (None,dict(yr=16,amin=128,th=6)):
        if kw is None: res=R.skin_alpha(a,key=b)
        else:
            gs=R.groups(a,**kw)
            if not gs: continue
            H,W=a.shape[:2];al=np.zeros((H,W),np.float32);rm=np.zeros((H,W,3),np.float32)
            for m,ref in gs:
                if b in R.LIMIT: m=m.copy();m[int(H*R.LIMIT[b]):]=False
                x=np.clip(cv2.GaussianBlur(m.astype(np.float32),(0,0),0.8)*1.3,0,1);u=x>al;al=np.maximum(al,x);rm[u]=ref
            res=(al,rm)
        if res is None: continue
        t=R.recolor(a,'1f3fe',res)
        opts.append((yellow_left(t),-float(res[0].mean()),res))
        if kw is None and opts[-1][0]<=0.03: break
    if not opts: return None,1.0
    opts.sort(key=lambda o:(round(o[0],3),o[1]))
    return opts[0][2],opts[0][0]
def work(b):
    a=np.array(Image.open(f'figgen/cut/{b}.png').convert('RGBA'))
    res,left=best(a,b)
    for t in R.TONES:
        parts=b.split('_');k='_'.join([parts[0],t]+parts[1:])
        Image.fromarray(R.recolor(a,t,res) if res is not None else a).save(f'tone/out/{k}.png')
    return b,(0.0 if res is None else float(res[0].mean())),round(left,3)
if __name__=='__main__' and 'pairs' not in sys.argv[0]:
    bases=[l.strip() for l in open('tone/bases.txt') if l.strip()]
    if len(sys.argv)>1: bases=sys.argv[1:]
    with ProcessPoolExecutor() as ex: res=list(ex.map(work,bases))
    print('done',len(res),'low',[r for r in res if r[1]<0.01],'yellow-left',[r for r in res if r[2]>0.03])
