import numpy as np,cv2
from PIL import Image
def lab(rgb): return cv2.cvtColor(rgb.astype(np.uint8),cv2.COLOR_RGB2LAB).astype(np.float32)
# reference skin model from pure-skin AURA hands/faces
def model():
    px=[]
    for k in ['1f44d','1f44b','1f91a','1f44a','1f590']:
        try: a=np.array(Image.open(f'figgen/cut/{k}.png').convert('RGBA'))
        except Exception: continue
        m=a[...,3]>230;L=lab(a[...,:3])[m]
        hsv=cv2.cvtColor(a[...,:3],cv2.COLOR_RGB2HSV)[m]
        keep=(hsv[:,0]>=12)&(hsv[:,0]<=34)&(hsv[:,1]>70)
        px.append(L[keep])
    P=np.concatenate(px);return P
P=model()
mu=P[:,1:].mean(0);cov=np.cov(P[:,1:].T);icov=np.linalg.inv(cov)
def skin_mask(a,th=3.0):
    L=lab(a[...,:3]);d=L[...,1:]-mu
    md=np.sqrt(np.einsum('...i,ij,...j->...',d,icov,d))
    m=(md<th)&(a[...,3]>10)&(L[...,0]>60)
    return m,md
if __name__=='__main__':
    print('mu ab',mu,'L range',np.percentile(P[:,0],[5,50,95]))
    import sys
    out=[]
    for k in sys.argv[1:]:
        a=np.array(Image.open(f'figgen/cut/{k}.png').convert('RGBA'))
        m,_=skin_mask(a)
        v=a.copy();v[m]=[255,0,255,255]
        out.append(np.hstack([a,v]))
    im=Image.fromarray(np.vstack([np.pad(o,((0,0),(0,512-o.shape[1]),(0,0))) for o in out]))
    bg=Image.new('RGBA',im.size,(40,40,60,255));bg.alpha_composite(im);bg.convert('RGB').save('tone/mask.png')
