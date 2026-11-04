// gemoji 3D restyle: per-shape light→dark gradients, soft contact shadow, gloss and bottom shading.
const fs=require('fs'),path=require('path');
const [,,srcDir,outDir,overrideDir]=process.argv;
fs.mkdirSync(outDir,{recursive:true});
const hex=h=>{h=h.replace('#','');if(h.length===3)h=h.split('').map(c=>c+c).join('');return [0,2,4].map(i=>parseInt(h.substr(i,2),16));};
const toHex=a=>'#'+a.map(v=>Math.max(0,Math.min(255,Math.round(v))).toString(16).padStart(2,'0')).join('');
const mix=(a,b,t)=>a.map((v,i)=>v+(b[i]-v)*t);
const light=c=>toHex(mix(c,[255,255,255],0.42));
const dark=c=>{const lum=(0.3*c[0]+0.59*c[1]+0.11*c[2])/255;return toHex(mix(c,[40*c[0]/255,20*c[1]/255,60*c[2]/255+10],lum<0.15?0.0:0.36));};
function restyle(svg,name){
  const m=svg.match(/<svg[^>]*viewBox="([^"]+)"[^>]*>([\s\S]*)<\/svg>/);
  if(!m) throw new Error('no viewBox '+name);
  let [x,y,w,h]=m[1].trim().split(/[\s,]+/).map(Number);let body=m[2];
  const grads=new Map();let n=0;const P='q'+name.replace(/\W/g,'')+'_';
  // namespace existing ids
  body=body.replace(/id="([^"]+)"/g,`id="${P}$1"`).replace(/url\(#([^)]+)\)/g,`url(#${P}$1)`).replace(/href="#([^"]+)"/g,`href="#${P}$1"`);
  body=body.replace(/fill="(#[0-9a-fA-F]{3,6})"/g,(all,c)=>{
    const key=c.toLowerCase();if(!grads.has(key))grads.set(key,P+'g'+(n++));
    return `fill="url(#${grads.get(key)})"`;});
  let defs='';
  for(const [c,id] of grads){const rgb=hex(c);defs+=`<linearGradient id="${id}" x1="0.25" y1="0" x2="0.6" y2="1"><stop offset="0" stop-color="${light(rgb)}"/><stop offset="0.45" stop-color="${c}"/><stop offset="1" stop-color="${dark(rgb)}"/></linearGradient>`;}
  const W=Math.max(w,h);const pad=W*0.05;
  const vb=`${x-pad} ${y-pad*0.6} ${w+2*pad} ${h+2*pad}`;
  defs+=`<filter id="${P}wh" x="-10%" y="-10%" width="120%" height="120%"><feColorMatrix type="matrix" values="0 0 0 0 1 0 0 0 0 1 0 0 0 0 1 0 0 0 1 0"/></filter>`+
   `<filter id="${P}sh" x="-20%" y="-20%" width="140%" height="150%"><feGaussianBlur in="SourceAlpha" stdDeviation="${W*0.022}"/><feOffset dy="${W*0.03}"/><feComponentTransfer><feFuncA type="linear" slope="0.30"/></feComponentTransfer></filter>`+
   `<mask id="${P}m" maskUnits="userSpaceOnUse" x="${x-pad}" y="${y-pad}" width="${w+2*pad}" height="${h+2*pad}"><use href="#${P}art" filter="url(#${P}wh)"/></mask>`+
   `<radialGradient id="${P}gl" cx="0.34" cy="0.17" r="0.42"><stop offset="0" stop-color="#fff" stop-opacity="0.85"/><stop offset="0.35" stop-color="#fff" stop-opacity="0.38"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>`+`<linearGradient id="${P}sn" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#fff" stop-opacity="0.30"/><stop offset="0.42" stop-color="#fff" stop-opacity="0.06"/><stop offset="0.43" stop-color="#fff" stop-opacity="0"/></linearGradient>`+`<radialGradient id="${P}rl" cx="0.62" cy="0.92" r="0.32"><stop offset="0" stop-color="#fff" stop-opacity="0.28"/><stop offset="1" stop-color="#fff" stop-opacity="0"/></radialGradient>`+`<linearGradient id="${P}bs" x1="0" y1="0" x2="0" y2="1"><stop offset="0.5" stop-color="#2a0f3a" stop-opacity="0"/><stop offset="1" stop-color="#2a0f3a" stop-opacity="0.22"/></linearGradient>`;
  const r=`x="${x}" y="${y}" width="${w}" height="${h}"`;
  return `<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="${vb}" width="160" height="160"><defs>${defs}<g id="${P}art">${body}</g></defs>`+
    `<use href="#${P}art" filter="url(#${P}sh)"/><use href="#${P}art"/><g mask="url(#${P}m)"><rect ${r} fill="url(#${P}bs)"/><rect ${r} fill="url(#${P}sn)"/><rect ${r} fill="url(#${P}rl)"/><rect ${r} fill="url(#${P}gl)"/></g></svg>`;
}
const files=fs.readdirSync(srcDir).filter(f=>f.endsWith('.svg'));
const ov=overrideDir?new Set(fs.readdirSync(overrideDir)):new Set();
let cnt=0;
for(const f of files){
  const cps=path.basename(f,'.svg').split('-').filter(c=>c.toLowerCase()!=='fe0f').map(c=>parseInt(c,16).toString(16).padStart(4,'0'));
  const out='emoji_u'+cps.join('_')+'.svg';
  const src=ov.has(f)?path.join(overrideDir,f):path.join(srcDir,f);
  fs.writeFileSync(path.join(outDir,out),restyle(fs.readFileSync(src,'utf8'),cps.join('_')));cnt++;
}
console.log('restyled',cnt,'overrides',[...ov].filter(f=>files.includes(f)).length);
