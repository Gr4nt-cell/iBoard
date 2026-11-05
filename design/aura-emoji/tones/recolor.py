import numpy as np,cv2,sys
from PIL import Image
sys.path.insert(0,'tone')
from seg import regions
from skin import lab
REF=np.array([135.5,203.5])
TONES={'1f3fb':(249,220,196),'1f3fc':(232,188,148),'1f3fd':(201,145,104),'1f3fe':(156,107,70),'1f3ff':(94,66,50)}
def tlab(rgb): return lab(np.array([[rgb]],np.uint8))[0,0]
YEL=tlab((255,204,42))
LIMIT={'1f937':0.62}
def grow(a,L,keep,ref,rad,minL):
    d=np.hypot((L[...,1]-ref[0])*1.5,L[...,2]-ref[1])
    skinish=(d<rad)&(L[...,0]>minL)&(a[...,3]>10)
    for _ in range(3):
        keep|=cv2.dilate(keep.astype(np.uint8),np.ones((3,3),np.uint8)).astype(bool)&skinish
    keep|=cv2.morphologyEx(keep.astype(np.uint8),cv2.MORPH_CLOSE,np.ones((5,5),np.uint8)).astype(bool)&skinish
    return keep
def groups(a,yr=10,amin=131,th=10):
    """Skin groups: (mask, reference Lab). Warm skin-coloured regions are clustered by colour;
    each cluster is recoloured relative to its own colour (AURA yellow uses the reference yellow)."""
    L=lab(a[...,:3])
    l,comps,m=regions(a,th=th)
    cand=[c for c in comps if c['area']>40 and c['mL']>100 and c['mb']>165 and amin<=c['ma']<=155]
    cand.sort(key=lambda c:-c['area'])
    clusters=[]
    for c in cand:
        for cl in clusters:
            if np.hypot((c['ma']-cl['a'])*1.5,c['mb']-cl['b'])<10:
                cl['ids'].append(c['id']);cl['area']+=c['area'];break
        else: clusters.append(dict(a=c['ma'],b=c['mb'],ids=[c['id']],area=c['area']))
    out=[];allm=np.zeros(l.shape,bool)
    isy=lambda cl:np.hypot((cl['a']-REF[0])*1.5,cl['b']-REF[1])<yr
    yc=[cl for cl in clusters if isy(cl) and cl['area']>=150]
    oc=[cl for cl in clusters if not isy(cl) and cl['area']>=150 and cl['a']>=134]
    for cl in yc:
        mk=grow(a,L,np.isin(l,cl['ids']),np.array([cl['a'],cl['b']]),13,90)&~allm
        if mk.sum()>=150: allm|=mk;out.append((mk,YEL.copy()))
    if yc: touch=cv2.dilate(allm.astype(np.uint8),np.ones((9,9),np.uint8)).astype(bool)
    for i,cl in enumerate(oc):
        base=np.isin(l,cl['ids'])
        if yc and not (base&touch).any(): continue
        if not yc and i>0: break
        mk=grow(a,L,base,np.array([cl['a'],cl['b']]),12,90)&~allm
        if mk.sum()<150: continue
        allm|=mk
        sel=mk&(a[...,3]>200)
        ref=np.array([np.percentile(L[...,0][sel],75),L[...,1][sel].mean(),L[...,2][sel].mean()])
        out.append((mk,ref))
    return out

def skin_alpha(a,key=None):
    gs=groups(a)
    if sum(int(m.sum()) for m,_ in gs)<0.012*a.shape[0]*a.shape[1]:
        gs2=groups(a,yr=16,amin=128,th=6)
        if sum(int(m.sum()) for m,_ in gs2)>sum(int(m.sum()) for m,_ in gs): gs=gs2
    if not gs: return None
    H,W=a.shape[:2];alpha=np.zeros((H,W),np.float32);refmap=np.zeros((H,W,3),np.float32)
    for m,ref in gs:
        if key in LIMIT: m=m.copy();m[int(H*LIMIT[key]):]=False
        al=np.clip(cv2.GaussianBlur(m.astype(np.float32),(0,0),0.8)*1.3,0,1)
        upd=al>alpha;alpha=np.maximum(alpha,al);refmap[upd]=ref
    return alpha,refmap
def recolor(a,tone,res=None,weight=None):
    if res is None: res=skin_alpha(a)
    if res is None: return a.copy()
    alpha,ref=res
    if weight is not None: alpha=alpha*weight
    L=lab(a[...,:3]);T=tlab(TONES[tone])
    refL=np.maximum(ref[...,0],1)
    k=np.hypot(T[1]-128,T[2]-128)/np.maximum(np.hypot(ref[...,1]-128,ref[...,2]-128),1)
    out=L.copy()
    out[...,0]=np.clip(L[...,0]*(T[0]/refL),0,255)
    out[...,1]=T[1]+(L[...,1]-ref[...,1])*k
    out[...,2]=T[2]+(L[...,2]-ref[...,2])*k
    rgb=cv2.cvtColor(np.clip(out,0,255).astype(np.uint8),cv2.COLOR_LAB2RGB).astype(np.float32)
    r=a.astype(np.float32).copy()
    r[...,:3]=r[...,:3]*(1-alpha[...,None])+rgb*alpha[...,None]
    return r.astype(np.uint8)
