const {chromium}=require('playwright');const fs=require('fs'),path=require('path');
const [,,inDir,outDir,W='136',H='128']=process.argv;fs.mkdirSync(outDir,{recursive:true});
(async()=>{const b=await chromium.launch();const p=await b.newPage();await p.setContent('<canvas id=c></canvas>');
const files=fs.readdirSync(inDir).filter(f=>f.endsWith('.svg'));
for(let i=0;i<files.length;i+=50){
  const batch=files.slice(i,i+50).map(f=>[f,fs.readFileSync(path.join(inDir,f),'utf8')]);
  const res=await p.evaluate(async([batch,W,H])=>{const c=document.getElementById('c');c.width=W;c.height=H;const x=c.getContext('2d');const out=[];
    for(const [f,s] of batch){const img=new Image();img.src='data:image/svg+xml;base64,'+btoa(unescape(encodeURIComponent(s)));
      try{await img.decode();}catch(e){out.push([f,null]);continue;}
      x.clearRect(0,0,W,H);x.filter='saturate(1.2)';x.drawImage(img,(W-H)/2,0,H,H);out.push([f,c.toDataURL('image/png').split(',')[1]]);}
    return out;},[batch,+W,+H]);
  for(const [f,d] of res){if(!d){console.log('FAIL',f);continue;}fs.writeFileSync(path.join(outDir,f.replace('.svg','.png')),Buffer.from(d,'base64'));}
}
await b.close();console.log('rendered',files.length);})();
