# Clear flat pure-white areas enclosed by skin (gaps between fingers/arms that the cutter kept).
import os,sys,json,numpy as np,cv2
from PIL import Image
sys.path.insert(0,'tone')
from skin import lab
def fix(a,dry=False):
    rgb=a[...,:3].astype(int);al=a[...,3]
    mn=rgb.min(2);mx=rgb.max(2)
    wh=((mn>=239)&(mx-mn<12)&(al>200)).astype(np.uint8)
    n,l,st,_=cv2.connectedComponentsWithStats(wh,4)
    L=lab(a[...,:3]);g=cv2.cvtColor(a[...,:3],cv2.COLOR_RGB2GRAY)
    tot=(al>200).sum();out=np.zeros(al.shape,bool)
    for j in range(1,n):
        ar=st[j,4]
        if ar<40: continue
        r=l==j
        if g[r].std()>1.8: continue
        ring=cv2.dilate(r.astype(np.uint8),np.ones((7,7),np.uint8)).astype(bool)&~cv2.dilate(r.astype(np.uint8),np.ones((3,3),np.uint8)).astype(bool)
        ring&=al>128
        if ring.sum()<10: continue
        ra=L[...,1][ring];rb=L[...,2][ring];rl=L[...,0][ring]
        skin=((rb>160)&(ra>125)&(ra<165)&(rl>90)).mean()
        if skin<0.4: continue
        out|=r
    if out.any():
        m=cv2.dilate(out.astype(np.uint8),np.ones((3,3),np.uint8)).astype(bool)
        # soft edge: fade pixels on the border that are still whitish
        edge=m&~out&(mn>=225)
        a=a.copy();a[...,3][out]=0;a[...,3][edge]=(a[...,3][edge]*0.35).astype(np.uint8)
    return a,int(out.sum())
if __name__=='__main__':
    d=sys.argv[1];dry='--dry' in sys.argv
    changed=[]
    for f in sorted(os.listdir(d)):
        if not f.endswith('.png'): continue
        a=np.array(Image.open(f'{d}/{f}').convert('RGBA'))
        b,n=fix(a)
        if n:
            changed.append((f[:-4],n))
            if not dry: Image.fromarray(b).save(f'{d}/{f}')
    json.dump(changed,open('gem/holes_fixed.json','w'));print('changed',len(changed))
