# usage: cutsheet.py sheet.png NN  -> gem/cells/<key>.jpg (256px) for the sheet's emoji, by grid position
import sys,json,os
from PIL import Image
p,n=sys.argv[1],int(sys.argv[2])
S=json.load(open('kit/sheets.json'))[n-1]
im=Image.open(p).convert('RGB');W,H=im.size;cw,ch=W/4,H/4
# Gemini puts a grey sparkle watermark near the bottom-right corner: find grey pixels there and paint over them
import numpy as np,cv2
a=cv2.cvtColor(np.asarray(im),cv2.COLOR_RGB2BGR);lab=cv2.cvtColor(a,cv2.COLOR_BGR2LAB).astype(float)
bx=int(W*0.12);by=int(H*0.12)
reg=lab[H-by:,W-bx:];chroma=np.hypot(reg[...,1]-128,reg[...,2]-128)
m=((chroma<10)&(reg[...,0]>110)&(reg[...,0]<238)).astype(np.uint8)*255
m=cv2.dilate(m,np.ones((7,7),np.uint8))
full=np.zeros(lab.shape[:2],np.uint8);full[H-by:,W-bx:]=m
if full.any() and not os.environ.get('NOWM'): a=cv2.inpaint(a,full,7,cv2.INPAINT_TELEA);im=Image.fromarray(cv2.cvtColor(a,cv2.COLOR_BGR2RGB))
print('watermark px',int((full>0).sum()))
os.makedirs('gem/cells',exist_ok=True)
for i,(k,e,name) in enumerate(S['items']):
    x,y=(i%4)*cw,(i//4)*ch
    im.crop((round(x+cw*0.01),round(y+ch*0.01),round(x+cw*0.99),round(y+ch*0.99))).resize((256,256),Image.LANCZOS).save(f'gem/cells/{k}.jpg',quality=97)
print('cells',len(S['items']))
