const sharp=require('sharp');const fs=require('fs'),path=require('path');
const [,,src,dst]=process.argv;fs.mkdirSync(dst,{recursive:true});
const files=fs.readdirSync(src).filter(f=>f.endsWith('.png'));let tot=0,i=0;
(async()=>{const work=async()=>{while(i<files.length){const f=files[i++];
  const b=await sharp(path.join(src,f)).png({palette:true,quality:100,colours:256,dither:1.0,effort:10,compressionLevel:9}).toBuffer();
  fs.writeFileSync(path.join(dst,f),b);tot+=b.length;}};
 await Promise.all(Array.from({length:8},work));console.log('files',files.length,'MB',(tot/1e6).toFixed(1),'avg',Math.round(tot/files.length));})();
