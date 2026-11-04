import io,numpy as np,cv2
from PIL import Image
def aura_grade(data):
    im=Image.open(io.BytesIO(data)).convert('RGBA');W,H=im.size
    arr=np.array(im).astype(np.float32)/255;rgb,a=arr[...,:3],arr[...,3]
    if a.max()==0: return data
    # pastel -> rich: S-curve contrast, darker midtones, strong saturation, warm tilt
    rgb=np.clip(rgb,0,1)**1.06
    rgb=1/(1+np.exp(-(rgb-0.5)*4.6));lo,hi=1/(1+np.exp(2.3)),1/(1+np.exp(-2.3));rgb=(rgb-lo)/(hi-lo)
    hsv=cv2.cvtColor((rgb*255).clip(0,255).astype(np.uint8),cv2.COLOR_RGB2HSV).astype(np.float32)
    h=hsv[...,0]
    # pull magenta/purple accents (Fluent mouths) toward deep red-brown like AURA
    mag=((h>135)&(h<170)&(hsv[...,1]>70)).astype(np.float32)
    hsv[...,0]=np.where(mag>0,(h+18)%180,h);hsv[...,2]*=np.where(mag>0,0.7,1)
    sat=hsv[...,1];hsv[...,1]=np.clip(sat*(1+0.32*np.clip((sat-35)/60,0,1)),0,255)
    rgb=cv2.cvtColor(hsv.clip(0,255).astype(np.uint8),cv2.COLOR_HSV2RGB).astype(np.float32)/255
    rgb[...,0]*=1.04;rgb[...,2]*=0.93
    # sculpted depth + crisp specular
    m=(a>0.3).astype(np.uint8);d=cv2.distanceTransform(m,cv2.DIST_L2,5);R=max(d.max(),1)
    ys,xs=np.nonzero(m);yy,xx=np.mgrid[0:H,0:W]
    edge=np.clip(1-d/(R*0.5),0,1)**1.5*0.24
    low=np.clip((yy-(ys.min()+0.4*(ys.max()-ys.min())))/(0.6*(ys.max()-ys.min())+1),0,1)*0.13
    rgb*= (1-(edge+low)*m)[...,None]
    cx=xs.min()+0.34*(xs.max()-xs.min());cy=ys.min()+0.18*(ys.max()-ys.min());r=0.42*max(xs.max()-xs.min(),ys.max()-ys.min())
    g=np.clip(1-np.hypot((xx-cx)/1.25,yy-cy)/r,0,1)**2.4*0.55*a
    rgb=rgb+(1-rgb)*g[...,None]
    out=np.dstack([np.clip(rgb,0,1),a])
    art=Image.fromarray((out*255).astype(np.uint8),'RGBA')
    s=0.93;art=art.resize((round(W*s),round(H*s)),Image.LANCZOS)
    ox,oy=(W-art.width)//2,int((H-art.height)*0.3)
    sh=np.zeros((H,W),np.float32);sh[oy+int(H*0.04):oy+int(H*0.04)+art.height,ox:ox+art.width]=np.array(art)[...,3]/255
    sh=cv2.GaussianBlur(sh,(0,0),H*0.03)*0.35
    res=Image.fromarray(np.dstack([np.zeros((H,W,3),np.uint8),(sh*255).astype(np.uint8)]));res.alpha_composite(art,(ox,oy))
    b=io.BytesIO();res.save(b,'PNG');return b.getvalue()
