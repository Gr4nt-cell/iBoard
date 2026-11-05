# Find each emoji on a sheet as a connected object (ignoring grey cast shadows) and save transparent PNGs.
import sys,os,cv2,numpy as np
from PIL import Image
src,dst,keys=sys.argv[1],sys.argv[2],sys.argv[3].split(',')
CH=float(os.environ.get('CH','10'));LMIN=float(os.environ.get('LMIN','165'))
os.makedirs(dst,exist_ok=True)
im=cv2.imread(src);H,W=im.shape[:2]
lab=cv2.cvtColor(im,cv2.COLOR_BGR2LAB).astype(np.float32)
bord=np.concatenate([lab[0],lab[-1],lab[:,0],lab[:,-1]]);bg=np.median(bord,0)
ch=np.hypot(lab[...,1]-bg[1],lab[...,2]-bg[2]);L=lab[...,0]
dist=np.sqrt((L-bg[0])**2*0.4+ch**2)
bgm=(dist<10)|((ch<CH)&(L>LMIN)&(L<=bg[0]+3))|((ch<float(os.environ.get('SCH','30')))&(L>105)&(L<180))
fg=(~bgm).astype(np.uint8)
fg=cv2.morphologyEx(fg,cv2.MORPH_OPEN,np.ones((5,5),np.uint8))
fg=cv2.morphologyEx(fg,cv2.MORPH_CLOSE,np.ones((9,9),np.uint8))
n,lbl,st,cen=cv2.connectedComponentsWithStats(fg,8)
cells={}
for j in range(1,n):
    if st[j,4]<1500: continue
    cx,cy=cen[j];c=(int(cy//(H/4)),int(cx//(W/4)))
    cells.setdefault(c,[]).append(j)
for i,k in enumerate(keys):
    if not k: continue
    c=(i//4,i%4);ids=cells.get(c,[])
    if not ids: print('missing',k);continue
    m=np.isin(lbl,ids).astype(np.uint8)
    # fill small holes only
    hn,hl,hs,_=cv2.connectedComponentsWithStats((1-m).astype(np.uint8),4)
    border=set(np.unique(np.concatenate([hl[0],hl[-1],hl[:,0],hl[:,-1]])))
    for h in range(1,hn):
        if h not in border and hs[h,4]<1200: m[hl==h]=1
    a=cv2.GaussianBlur(cv2.erode(m,np.ones((3,3),np.uint8)).astype(np.float32),(0,0),1.4)
    x,y,w,h=cv2.boundingRect(m);p=6
    x0,y0,x1,y1=max(0,x-p),max(0,y-p),min(W,x+w+p),min(H,y+h+p)
    rgb=cv2.cvtColor(im,cv2.COLOR_BGR2RGB)[y0:y1,x0:x1]
    out=np.dstack([rgb,(a[y0:y1,x0:x1]*255).clip(0,255).astype(np.uint8)])
    Image.fromarray(out).save(f'{dst}/{k}.png')
print('done')
