import numpy as np,cv2,sys
from PIL import Image
sys.path.insert(0,'tone')
from skin import skin_mask,lab
def regions(a,th=3.0):
    m,md=skin_mask(a,th)
    rgb=a[...,:3]
    g=cv2.cvtColor(rgb,cv2.COLOR_RGB2GRAY)
    e=cv2.Canny(cv2.GaussianBlur(g,(3,3),0),30,80)
    e=cv2.dilate(e,np.ones((2,2),np.uint8))
    core=(m&(e==0)).astype(np.uint8)
    n,l,st,cen=cv2.connectedComponentsWithStats(core,4)
    L=lab(rgb)
    comps=[]
    for j in range(1,n):
        if st[j,4]<15: continue
        r=l==j
        # holes inside the component's filled outline (eyes, mouth)
        cm=r.astype(np.uint8);cnts,_=cv2.findContours(cm,cv2.RETR_CCOMP,cv2.CHAIN_APPROX_SIMPLE)
        fill=np.zeros_like(cm);cv2.drawContours(fill,cnts,-1,1,-1)
        holes=int(fill.sum()-cm.sum())
        comps.append(dict(id=j,area=int(st[j,4]),holes=holes,mL=float(L[...,0][r].mean()),ma=float(L[...,1][r].mean()),mb=float(L[...,2][r].mean()),
            cx=float(cen[j,0]),cy=float(cen[j,1]),bbox=tuple(st[j,:4])))
    return l,comps,m
if __name__=='__main__':
    for k in sys.argv[1:]:
        a=np.array(Image.open(f'figgen/cut/{k}.png').convert('RGBA'))
        l,c,m=regions(a)
        c.sort(key=lambda x:-x['area'])
        print(k,[ (x['area'],x['holes'],round(x['mL']),round(x['ma'],1),round(x['mb'],1),round(x['cy'])) for x in c[:6]])
