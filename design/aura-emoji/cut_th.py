# white-background removal for Figma AI emoji previews -> transparent PNG
import sys,os,glob,numpy as np,cv2
from PIL import Image
src,dst=sys.argv[1],sys.argv[2];os.makedirs(dst,exist_ok=True)
for f in sorted(glob.glob(src+'/*.jpg')):
    im=cv2.imread(f);h,w=im.shape[:2]
    lab=cv2.cvtColor(im,cv2.COLOR_BGR2LAB).astype(np.float32)
    bord=np.concatenate([lab[0],lab[-1],lab[:,0],lab[:,-1]]);bgc=np.median(bord,axis=0)
    dist=np.sqrt((lab[...,0]-bgc[0])**2*0.4+(lab[...,1]-bgc[1])**2+(lab[...,2]-bgc[2])**2)
    near=(dist<float(os.environ.get("TH","30"))).astype(np.uint8)
    # background = near-white region connected to the border
    mask=np.zeros((h+2,w+2),np.uint8);bg=near.copy()
    ff=np.zeros_like(near)
    n,lab2=cv2.connectedComponents(near)
    border=set(np.unique(np.concatenate([lab2[0],lab2[-1],lab2[:,0],lab2[:,-1]])))-{0}
    bgm=np.isin(lab2,list(border))
    fg=(~bgm).astype(np.uint8)
    fg=cv2.morphologyEx(fg,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
    nn,l3,st,_=cv2.connectedComponentsWithStats(fg,8)
    if nn>1: fg=(l3==1+np.argmax(st[1:,4])).astype(np.uint8)|((st[l3,4]>st[1:,4].max()*float(os.environ.get("MINFRAC","0.05")))&(l3>0)).astype(np.uint8)
    fg=cv2.morphologyEx(fg,cv2.MORPH_CLOSE,np.ones((5,5),np.uint8))
    # holes stay filled (eyes etc.)
    fill=fg.copy(); hn,hl,hs,_=cv2.connectedComponentsWithStats((1-fg).astype(np.uint8),4)
    for j in range(1,hn):
        if j in set(np.unique(np.concatenate([hl[0],hl[-1],hl[:,0],hl[:,-1]]))): continue
        hole=hl==j
        if hs[j,4]<250 or np.median(dist[hole])>=30: fill[hole]=1
    alpha=cv2.GaussianBlur(cv2.erode(fill,np.ones((3,3),np.uint8)).astype(np.float32),(0,0),0.9)
    # soften: semi-transparent where pixel is near-white at the rim (soft shadow / antialias)
    rim=(cv2.dilate(fill,np.ones((7,7),np.uint8))-cv2.erode(fill,np.ones((7,7),np.uint8)))>0
    a=alpha*np.where(rim,np.clip(dist/float(os.environ.get("RIM","22")),0,1),1)
    rgb=cv2.cvtColor(im,cv2.COLOR_BGR2RGB)
    out=np.dstack([rgb,(a*255).clip(0,255).astype(np.uint8)])
    Image.fromarray(out).save(os.path.join(dst,os.path.basename(f)[:-4]+'.png'))
print('cut',len(glob.glob(dst+'/*.png')))
