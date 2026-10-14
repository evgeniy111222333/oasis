(function(){
const made=[], textures=[];
Undo.initEdit({elements:made,textures,outliner:true,uv_mode:true});
Project.texture_width=128; Project.texture_height=128; Project.box_uv=false;
Project.model_identifier='carver_mallet'; Project.visible_box=[3,3,1];
const canvas=document.createElement('canvas');canvas.width=canvas.height=128;
const ctx=canvas.getContext('2d');ctx.imageSmoothingEnabled=false;
let seed=91827;function rnd(){seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;}
function col(rgb,d=0){return '#'+rgb.map(v=>Math.max(0,Math.min(255,Math.round(v+d))).toString(16).padStart(2,'0')).join('');}
function rect(x,y,w,h,c){ctx.fillStyle=c;ctx.fillRect(x,y,w,h);}
rect(0,0,128,128,'#2e2923');
function wood(x,y,w,h,vertical=false,base=[91,63,40]){
  rect(x,y,w,h,col(base));
  let W=vertical?h:w,H=vertical?w:h;
  for(let row=0;row<H;){let rh=2+Math.floor(rnd()*2);for(let px=-5;px<W;){let len=4+Math.floor(rnd()*13),tone=(rnd()-.5)*24;
    let sx=Math.max(0,px),ex=Math.min(W,px+len),hh=Math.min(rh,H-row);
    if(ex>sx){if(vertical)rect(x+row,y+sx,hh,ex-sx,col(base,tone));else rect(x+sx,y+row,ex-sx,hh,col(base,tone));}px+=len;
  }row+=rh;}
  if(vertical){rect(x,y,1,h,col(base,20));rect(x+w-1,y,1,h,col(base,-22));}
  else{rect(x,y,w,1,col(base,21));rect(x,y+h-1,w,1,col(base,-20));}
}
wood(0,0,56,32);wood(0,32,56,32);wood(0,64,56,24,false,[103,73,46]);wood(0,88,56,24,false,[80,54,34]);
wood(56,0,24,32,true,[94,65,42]);wood(80,0,24,32,true,[88,59,37]);
wood(64,32,12,32,true,[99,69,43]);wood(76,32,12,32,true,[90,60,36]);wood(88,32,12,32,true,[94,64,39]);
wood(96,88,16,12,true,[80,53,32]);wood(0,112,56,4,false,[115,83,53]);
function metal(x,y,w,h,base=[69,69,66]){
 rect(x,y,w,h,col(base));
 for(let j=0;j<h;j+=3)for(let i=0;i<w;i+=2)rect(x+i,y+j,Math.min(2,w-i),Math.min(3,h-j),col(base,(rnd()-.5)*17));
 rect(x,y,1,h,col(base,27));rect(x+1,y,1,h,col(base,5));rect(x+w-1,y,1,h,col(base,-25));
 rect(x,y,w,1,col(base,31));rect(x,y+h-1,w,1,col(base,-24));
}
metal(56,32,8,32);metal(56,64,8,24);metal(104,0,8,32);metal(104,32,8,32,[81,80,75]);
metal(64,88,16,12);metal(80,88,16,12,[52,48,40]);
rect(112,0,8,8,'#9b7839');rect(113,1,6,6,'#c5a35c');rect(114,2,4,4,'#b89955');
rect(112,0,8,1,'#e2c781');rect(112,0,1,8,'#ceaf68');rect(119,1,1,7,'#725622');rect(113,7,7,1,'#80632e');rect(113,1,3,1,'#dcc37d');
const texture=new Texture({name:'carver_mallet.png',id:'0'}).fromDataURL(canvas.toDataURL());texture.add(false);textures.push(texture);
const root=new Group({name:'mallet',origin:[0,6,0]}).init();
const groups={};function group(name){return groups[name]||(groups[name]=new Group({name,origin:[0,6,0]}).addTo(root).init());}
const axes=['x','y','z'], faces={east:[0,1],west:[0,-1],up:[1,1],down:[1,-1],south:[2,1],north:[2,-1]};
const all=r=>Object.fromEntries(['x','-x','y','-y','z','-z'].map(k=>[k,r]));
const woodUV={z:[0,0,56,32],'-z':[0,32,56,64],y:[0,64,56,88],'-y':[0,88,56,112],x:[56,0,80,32],'-x':[80,0,104,32]};
const steelUV=all([104,0,112,32]);
const strapUV={...steelUV,z:[56,32,64,64],'-z':[56,32,64,64],y:[56,64,64,88],'-y':[56,64,64,88]};
const handleUV={...all([64,32,76,64]),x:[76,32,88,64],'-x':[88,32,100,64]};
function cube(name,c,s,uv,g,bounds,rotation){
 const from=c.map((v,i)=>v-s[i]/2),to=c.map((v,i)=>v+s[i]/2);
 const q=new Cube({name,from,to,origin:c,rotation:rotation||[0,0,0],box_uv:false,autouv:0}).addTo(group(g)).init();made.push(q);
 for(const [f,[a,sign]] of Object.entries(faces)){
   const r=uv[(sign<0?'-':'')+axes[a]];let uvrect=r.slice();
   if(bounds&&!rotation){
     let horizontal=a===0?2:0,vertical=a===1?2:1;
     let bmin=bounds.c.map((v,i)=>v-bounds.s[i]/2);
     let u0=(from[horizontal]-bmin[horizontal])/bounds.s[horizontal],u1=(to[horizontal]-bmin[horizontal])/bounds.s[horizontal];
     let v0=(from[vertical]-bmin[vertical])/bounds.s[vertical],v1=(to[vertical]-bmin[vertical])/bounds.s[vertical];
     if(a!==1)[v0,v1]=[1-v1,1-v0];
     uvrect=[r[0]+u0*(r[2]-r[0]),r[1]+v0*(r[3]-r[1]),r[0]+u1*(r[2]-r[0]),r[1]+v1*(r[3]-r[1])];
   }
   q.faces[f].uv=uvrect;q.faces[f].texture=texture.uuid;
 }
 return q;
}
function bevel(name,c,s,b,uv,g,edgeUV){
 const bounds={c,s};
 for(let a=0;a<3;a++){let ss=s.map(v=>v-2*b);ss[a]=s[a];cube(name+' / core '+axes[a],c,ss,uv,g,bounds);}
 for(let a=0;a<3;a++)for(let p of [-1,1])for(let q of [-1,1]){
   let others=[0,1,2].filter(i=>i!==a),cc=c.slice(),ss=[b*Math.SQRT2,b*Math.SQRT2,b*Math.SQRT2],rot=[0,0,0];
   cc[others[0]]+=p*(s[others[0]]/2-b);cc[others[1]]+=q*(s[others[1]]/2-b);ss[a]=s[a]-2*b;rot[a]=45;
   cube(name+' / bevel '+axes[a]+p+q,cc,ss,edgeUV||uv,g,null,rot);
 }
 for(let x of [-1,1])for(let y of [-1,1])for(let z of [-1,1]){
   let cc=c.map((v,i)=>v+[x,y,z][i]*(s[i]/2-b));
   cube(name+' / corner '+x+y+z,cc,[b*.66,b*.66,b*.66],edgeUV||uv,g);
 }
}
const woodEdge=all([2,113,54,115]),steelEdge=all([106,34,110,62]);
bevel('Oak head',[0,15,0],[14,7.6,6],.32,woodUV,'head',woodEdge);
for(const [x,label] of [[-4.8,'left'],[4.8,'right']]){
 bevel(label+' iron hoop',[x,15,0],[2,8.2,6.5],.13,strapUV,'hoops',steelEdge);
 for(const z of [-3.34,3.34])for(const y of [13.2,16.7])cube(label+' brass rivet '+y+' '+z,[x,y,z],[.82,.86,.22],all([112,0,120,8]),'rivets');
 for(const z of [-1.75,1.75])cube(label+' top brass rivet '+z,[x,19.18,z],[.82,.16,.82],all([112,0,120,8]),'rivets');
}
bevel('Neck socket',[0,10.48,0],[3.5,1.4,3.35],.13,all([80,88,96,100]),'socket',all([82,90,94,98]));
bevel('Walnut handle',[0,6.35,0],[2.65,7.35,2.65],.14,handleUV,'handle',all([65,33,67,63]));
bevel('Wooden pommel',[0,1.52,0],[3.25,2.4,3.15],.18,all([96,88,112,100]),'pommel',all([97,89,110,99]));
bevel('Iron ferrule',[0,2.32,0],[3.55,2.12,3.4],.12,all([64,88,80,100]),'pommel',steelEdge);
for(const a of [0,2])for(const sign of [-1,1]){let c=[0,2.26,0],s=[.82,.86,.82];c[a]=sign*(a===0?1.82:1.75);s[a]=.19;cube('Pommel pin '+a+' '+sign,c,s,all([112,0,120,8]),'rivets');}
Undo.finishEdit('Build reference mallet with 128px atlas',{elements:made,textures:[texture],outliner:true,uv_mode:true});
Canvas.updateAll();
return {cubes:made.length,texture:[texture.width,texture.height],bones:Group.all.length};
})();
