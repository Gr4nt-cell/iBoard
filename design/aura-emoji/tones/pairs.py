import sys,os,json,numpy as np
sys.path.insert(0,'tone')
from PIL import Image
import recolor as R
from gen import best
from concurrent.futures import ProcessPoolExecutor
STAND={'1faf1_200d_1faf2':'1f91d','1f469_200d_1f91d_200d_1f469':'1f46d','1f469_200d_1f91d_200d_1f468':'1f46b','1f468_200d_1f91d_200d_1f468':'1f46c',
 '1f9d1_200d_2764_200d_1f48b_200d_1f9d1':'1f48f','1f9d1_200d_2764_200d_1f9d1':'1f491',
 '1f9d1_200d_1f430_200d_1f9d1':'1f46f','1f468_200d_1f430_200d_1f468':'1f46f_200d_2642','1f469_200d_1f430_200d_1f469':'1f46f_200d_2640',
 '1f9d1_200d_1faef_200d_1f9d1':'1f93c','1f468_200d_1faef_200d_1f468':'1f93c_200d_2642','1f469_200d_1faef_200d_1f469':'1f93c_200d_2640'}
cache={}
def prep(base):
    src=STAND.get(base,base)
    a=np.array(Image.open(f'figgen/cut/{src}.png').convert('RGBA'))
    res,_=best(a,src)
    al=res[0];xs=np.where(al>0.5)[1]
    cx=float(np.median(xs)) if len(xs) else a.shape[1]/2
    return a,res,cx
def work(job):
    k,base,(t1,t2)=job
    a,res,cx=prep(base)
    W=a.shape[1];x=np.arange(W)[None,:].repeat(a.shape[0],0)
    w=1/(1+np.exp((x-cx)/3.0))     # 1 on the left, 0 on the right
    left=R.recolor(a,t1,res,weight=w.astype(np.float32))
    out=R.recolor(left,t2,res,weight=(1-w).astype(np.float32))
    Image.fromarray(out).save(f'tone/out/{k}.png');return k
if __name__=='__main__':
    jobs=json.load(open('tone/pairs.json'))
    with ProcessPoolExecutor() as ex: r=list(ex.map(work,jobs,chunksize=10))
    print('pairs',len(r))
