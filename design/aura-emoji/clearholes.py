# clear large enclosed near-white regions (holes) in cut PNGs; keeps small white details like salt
import sys,numpy as np,cv2
from PIL import Image
for k in sys.argv[1:]:
    src=np.array(Image.open(f'gem/c2/{k}.jpg').convert('RGB')).astype(float)
    p=f'gem/k2/{k}.png';a=np.array(Image.open(p).convert('RGBA')).astype(float)
    mn=src.min(2);mx=src.max(2)
    wh=((mn>232)&((mx-mn)<16)).astype(np.uint8)
    n,l,st,_=cv2.connectedComponentsWithStats(wh,4)
    m=np.zeros_like(wh)
    for j in range(1,n):
        if st[j,4]>150: m[l==j]=1
    m=cv2.dilate(m,np.ones((3,3),np.uint8))
    a[...,3]=np.where(m>0,0,a[...,3])
    Image.fromarray(a.astype('uint8')).save(p)
