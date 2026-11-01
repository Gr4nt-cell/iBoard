const fs=require('fs');
const layouts=JSON.parse(fs.readFileSync('design/layouts.json','utf8'));
const themes={light:{background:'#F8F5FC',surface:'#FFFFFF',ink:'#1C2033',muted:'#666C83',accent:'#007AFF',rim:'#FFFFFF',glow1:'#ECD2EA',glow2:'#D2DCF3'},dark:{background:'#141A2E',surface:'#DDE8FF',ink:'#FFFFFF',muted:'#B4BED3',accent:'#0A84FF',rim:'#DAE8FF',glow1:'#2B1E3A',glow2:'#141A2E'}};
const icons=Object.fromEntries(fs.readdirSync('design/icons').filter(n=>n.endsWith('.svg')).map(n=>[n.slice(0,-4),fs.readFileSync('design/icons/'+n,'utf8')]));
const emoji=Object.fromEntries(fs.readdirSync('design/emoji').filter(n=>n.endsWith('.png')).map(n=>[n.slice(0,-4),fs.readFileSync('design/emoji/'+n).toString('base64')]));
const scene={name:'iBoard / Offline Liquid Glass',w:2048,h:1810,children:[]};
const box=(p,name,x,y,w,h,fill,r=0,alpha=1)=>{const n={name,x,y,w,h,fill,r,alpha,children:[]};p.children.push(n);return n;};
const label=(p,value,x,y,size=14,color='ink',w=300,align='LEFT',weight='Regular')=>{const n={kind:'text',name:value,value,x,y,w,h:size*1.35,size,color,align,weight};p.children.push(n);return n;};
const icon=(p,key,x,y,size=24,color='ink')=>{const n={kind:'icon',name:'Icon / '+key,key,x,y,w:size,h:size,color};p.children.push(n);return n;};
const glyph=(p,key,x,y,size=32)=>{const n={kind:'emoji',name:'Emoji / '+key,key,x,y,w:size,h:size};p.children.push(n);return n;};
const glass=(p,name,x,y,w,h,r=12,alpha=.6)=>{const n=box(p,name,x,y,w,h,'surface',r,alpha);n.glass=true;return n;};
const map={SHIFT:'shift',BACKSPACE:'backspace',RETURN:'return',EMOJI:'emoji',GLOBE:'globe',MIC:'mic',CLIPBOARD:'clipboard',SETTINGS:'settings'};
function keys(frame,data,offset=0,mode='light'){
 const bg=box(frame,'Glass keyboard',0,offset,data.width,data.height,'glow1',24);bg.theme=mode;bg.gradient=['glow1','background','glow2'];bg.clip=true;
 const strip=glass(bg,'Prediction strip',8,3,data.width-16,40,20,mode==='dark'?.08:.26);strip.layout='HORIZONTAL';strip.gap=0;
 for(let i=0;i<3;i++)label(strip,['tomorrow','tonight','soon'][i],i*strip.w/3,9,16,'ink',strip.w/3,'CENTER',i===0?'Medium':'Regular');
 for(const c of data.capsules||[]){
  const controls=data.keys.filter(k=>k.utility&&k.x>=c.x-.1&&k.x+k.w<=c.x+c.w+.1);
  const bar=glass(bg,'Shared utility capsule',c.x,c.y,c.w,c.h,c.h*.42,mode==='dark'?.18:.55);
  bar.component='Toolbar / Capsule / '+controls.map(k=>k.type).join('-');bar.layout='HORIZONTAL';bar.gap=6;
  for(const k of controls){const n=box(bar,'Key / utility / '+k.type,k.x-c.x,0,k.w,c.h,null);n.component='Key / utility / '+k.type;
   icon(n,map[k.type],(k.w-22)/2,(c.h-22)/2,22,'ink');}
 }
 for(const k of data.keys){if(k.utility)continue;const utility=false;let x=k.x,y=k.y,w=k.w,h=k.h,r=12;
  const n=glass(bg,'Key / '+k.type,x,y,w,h,r,mode==='dark'?.13:.60);n.component='Key / '+(utility?'utility / ':'')+k.type;
  if(k.type==='RETURN'){n.fill='accent';n.alpha=1;}
  if(k.type==='SPACE'){box(n,'Space glint',w*.32,h-5,w*.36,1,'rim',1,.5);continue;}
  const color=k.type==='RETURN'?'#FFFFFF':'ink';
  if(map[k.type])icon(n,map[k.type],(w-22)/2,(h-22)/2,22,color);
  else label(n,k.label.length===1?k.label.toUpperCase():k.label,0,(h-(k.label.length>1?16:22)*1.35)/2,k.label.length>1?16:22,color,w,'CENTER');
 }
 return bg;
}
function keyboardScreen(x,y,name,theme,data,header=92){const f=box(scene,name,x,y,data.width,data.height+header,'background',28);f.theme=theme;f.clip=true;label(f,'iBoard',18,14,18,'ink',data.width-36,'LEFT','Medium');label(f,'Offline. Personal. Beautiful.',18,42,12,'muted',data.width-36);keys(f,data,header,theme);return f;}
const portrait=layouts.find(n=>n.name==='Portrait'),pad=layouts.find(n=>n.name==='Number pad');
keyboardScreen(24,154,'01 / Portrait · Light','light',portrait);keyboardScreen(438,154,'02 / Portrait · Dark','dark',portrait);keyboardScreen(852,154,'03 / Number pad','light',pad);
const e=box(scene,'04 / Bundled emoji font',1266,154,390,portrait.height+92,'background',28);e.theme='light';e.clip=true;label(e,'Your emoji, built in.',18,18,22,'ink',354,'LEFT','Medium');label(e,'Attached font · no downloads',18,51,12,'muted',354);const ep=box(e,'Emoji keyboard',0,92,390,portrait.height,'glow1',24);ep.gradient=['glow1','background','glow2'];const search=glass(ep,'Search Emoji',10,12,370,42,21,.5);label(search,'Search Emoji',16,12,14,'muted',330);label(ep,'SMILEYS & PEOPLE',16,72,11,'muted',350,'LEFT','Medium');const emojis=Object.keys(emoji);for(let i=0;i<40;i++)glyph(ep,emojis[i%emojis.length],19+(i%8)*46,108+Math.floor(i/8)*46,32);const tools=glass(ep,'Emoji tools',105,portrait.height-48,180,40,20,.5);icon(tools,'globe',14,9,22);label(tools,'ABC',62,10,14,'ink',60,'CENTER','Medium');icon(tools,'backspace',143,9,22);
keyboardScreen(24,720,'05 / Tablet · Landscape split','light',layouts.find(n=>n.name==='Tablet landscape'),92);
keyboardScreen(1232,720,'06 / Tablet · Portrait','dark',layouts.find(n=>n.name==='Tablet portrait'),92);
keyboardScreen(24,1270,'07 / Phone · Landscape split','light',layouts.find(n=>n.name==='Phone landscape'),72);
label(scene,'iBoard / Liquid Glass',24,24,38,'#FFFFFF',1950,'LEFT','Medium');label(scene,'One offline keyboard. Your attached emoji font. Room for every screen.',26,82,16,'#BEC8DE',1900);
label(scene,'Design reference · keys and bounds exported from production layouts · system blur depends on Android device support',24,1745,14,'#BEC8DE',1990);
// Settings uses the same glass surfaces and theme palette as the keyboard.
const settingsSections=[
 ['Appearance',[
 ['Appearance','Automatic','nav'],['Background','Blossom','nav'],['Background Opacity','58%','slider'],['Key Style','Liquid Glass','nav'],['Return Key Color','Blue','nav']]],
 ['Offline intelligence',[
 ['Offline Dictation','Whisper Base · 6 languages','switch','mic'],['Microphone Access','Off · Tap the mic beside the globe','nav','mic'],['Word Prediction','On-device · Trained context model','nav','settings']]],
 ['Emoji', [['Emoji Style','iOS-style (Built-in)','nav','emoji'],['Import Emoji Font…','Import a local .ttf file','nav','emoji']]],
 ['Keys', [['Key Height','100%','slider'],['Letter Size','100%','slider'],['Corner Roundness','12','slider'],['Uppercase Labels','','switch'],['Number Row','','switch'],['Character Preview','','switch'],['Label on Space Bar','','switch']]],
 ['Icon Capsule', [['Icon Capsule','','switch'],['Buttons on Suggestion Bar','','switch']]],
 ['Typing', [['Auto-Capitalization','','switch'],['Auto-Correction','','switch'],['“.” Shortcut','','switch'],['Learn My Words','Personal vocabulary stays on this phone','switch'],['Haptic Feedback','','switch'],['Key Sounds','','switch'],['Reset Learned Words','','nav']]],
 ['Languages', [['Keyboards','English · Español · Français','nav','globe']]],
 ['Clipboard', [['Clipboard History','Keeps your last 40 copies','switch','clipboard']]],
 ['Privacy', [['Privacy Policy','Optional mic · Audio is never saved','nav']]],
 ['App', [['Hide App Icon','','switch']]],
];
function settingsScreen(x,name,mode,width=390){
 const screen=box(scene,name,x,1900,width,3450,'background',28);screen.theme=mode;screen.clip=true;screen.gradient=['glow1','background','glow2'];
 const inset=Math.max(16,(width-688)/2),w=width-inset*2;
 label(screen,'9:41',inset,20,14,'ink',w,'LEFT','Medium');label(screen,'Settings',inset+4,70,34,'ink',w,'LEFT','Bold');label(screen,'Your keyboard, beautifully personal.',inset+4,119,15,'muted',w);
 const hero=glass(screen,'Keyboard setup',inset,162,w,174,26,mode==='dark'?.16:.75);hero.component='Settings/Hero';
 icon(hero,'settings',18,23,38,'accent');label(hero,'iBoard Keyboard',74,22,21,'ink',w-90,'LEFT','Medium');label(hero,'Ready to type',74,52,14,'muted',w-90);
 label(hero,'Enable iBoard',18,91,16,'accent',w-36,'LEFT','Medium');label(hero,'Switch to iBoard',18,137,16,'accent',w-36,'LEFT','Medium');
 const tryCard=glass(screen,'Try keyboard',inset,354,w,80,26,mode==='dark'?.12:.6);label(tryCard,'Try iBoard here…',18,23,17,'muted',w-36);
 let y=464;
 for(const [title,rows] of settingsSections){
  label(screen,title.toUpperCase(),inset+16,y,12,'muted',w-32,'LEFT','Medium');y+=30;
  const card=glass(screen,title,inset,y,w,rows.reduce((n,row)=>n+(row[2]==='slider'?96:68),0),26,mode==='dark'?.16:.75);card.layout='VERTICAL';card.gap=0;
  let ry=0;
  for(const [title,value,kind,key='settings'] of rows){
   const h=kind==='slider'?96:68;const row=box(card,title,0,ry,w,h,null);row.component='Settings/'+kind+'/'+key;row.layout='HORIZONTAL';
   const badge=glass(row,'Icon',16,17,34,34,9,.28);icon(badge,key,7,7,20,'accent');
   label(row,title,64,value?13:23,16,'ink',w-142,'LEFT','Medium');label(row,value||'',64,37,12,'muted',w-152);
   if(kind==='switch'){
    const on=!['Uppercase Labels','Key Sounds','Hide App Icon','Buttons on Suggestion Bar'].includes(title);
    const sw=box(row,'Toggle '+(on?'On':'Off'),w-66,19,50,30,on?'accent':'muted',15,on?1:.25);
    box(sw,'Thumb',on?23:3,3,24,24,'#FFFFFF',12);
   }else if(kind==='slider'){
    const track=box(row,'Slider',64,76,w-94,4,'muted',2,.25);box(row,'Value',64,76,(w-94)*.58,4,'accent',2);box(row,'Thumb',64+(w-94)*.58-11,66,24,24,'#FFFFFF',12);
   }else label(row,'›',w-35,17,25,'muted',20,'CENTER');
   if(ry)box(row,'Inset separator',64,0,w-80,.6,'rim',0,.35);
   ry+=h;
  }
  y+=card.h+32;
 }
 label(screen,'iBoard · Offline by design',inset,y+4,13,'muted',w,'CENTER');
 return screen;
}
settingsScreen(24,'08 / Settings · Light','light');settingsScreen(438,'09 / Settings · Dark','dark');settingsScreen(852,'10 / Settings · Tablet','light',768);
scene.h=5400;

const esc=s=>String(s).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&apos;'}[c]));let defs=[],seq=0;
function render(n,mode='light'){mode=n.theme||mode;const c=v=>themes[mode][v]||v;let out=`<g transform="translate(${n.x||0},${n.y||0})">`;
 if(n.clip){const id='clip'+ ++seq;defs.push(`<clipPath id="${id}"><rect width="${n.w}" height="${n.h}" rx="${n.r||0}"/></clipPath>`);out+=`<g clip-path="url(#${id})">`;}
 if(n.kind==='text'){out+=`<text x="${n.align==='CENTER'?n.w/2:0}" y="${n.size}" text-anchor="${n.align==='CENTER'?'middle':'start'}" font-family="Roboto,Arial,sans-serif" font-size="${n.size}" font-weight="${n.weight==='Medium'?500:400}" fill="${c(n.color)}">${esc(n.value)}</text>`;}
 else if(n.kind==='icon'){out+=icons[n.key].replace('<svg ',`<svg fill="${c(n.color)}" `).replace('width="24"',`width="${n.w}"`).replace('height="24"',`height="${n.h}"`);}
 else if(n.kind==='emoji')out+=`<image width="${n.w}" height="${n.h}" href="data:image/png;base64,${emoji[n.key]}"/>`;
 else{
  let fill=n.fill?c(n.fill):'none';if(n.gradient){const id='bg'+ ++seq;defs.push(`<linearGradient id="${id}" x2="1" y2="1">${n.gradient.map((v,i)=>`<stop offset="${i/(n.gradient.length-1)}" stop-color="${c(v)}"/>`).join('')}</linearGradient>`);fill=`url(#${id})`;}
  out+=`<rect width="${n.w}" height="${n.h}" rx="${n.r||0}" fill="${fill}" opacity="${n.alpha??1}" ${n.glass?'filter="url(#depth)"':''}/>`;
  if(n.glass)out+=`<rect x=".5" y=".5" width="${n.w-1}" height="${n.h-1}" rx="${n.r||0}" fill="url(#${mode==='dark'?'dark':'light'}Sheen)" stroke="${c('rim')}" stroke-opacity="${mode==='dark'?.22:.75}" stroke-width=".8"/>`;
  for(const child of n.children)out+=render(child,mode);
 }
 if(n.clip)out+='</g>';return out+'</g>';}
const body=render(scene);fs.writeFileSync('design/liquid-glass-reference.svg',`<svg xmlns="http://www.w3.org/2000/svg" width="2048" height="5400" viewBox="0 0 2048 5400"><defs><filter id="depth" x="-30%" y="-30%" width="160%" height="170%"><feDropShadow dx="0" dy="1.5" stdDeviation="1.8" flood-color="#312B55" flood-opacity=".14"/></filter><linearGradient id="lightSheen" x2="0" y2="1"><stop stop-color="white" stop-opacity=".46"/><stop offset=".6" stop-color="white" stop-opacity=".02"/><stop offset="1" stop-color="#DAEEFF" stop-opacity=".20"/></linearGradient><linearGradient id="darkSheen" x2="0" y2="1"><stop stop-color="white" stop-opacity=".10"/><stop offset=".6" stop-color="white" stop-opacity="0"/><stop offset="1" stop-color="#A6D5FF" stop-opacity=".08"/></linearGradient>${defs.join('')}</defs><rect width="2048" height="5400" fill="#101626"/>${body}</svg>`);
const source=require('./import-template.cjs')({themes,icons,scene,emoji});
fs.writeFileSync('design/figma-liquid-glass.js',source);
console.log('Generated 10 keyboard and settings references from production geometry and the attached font.');
