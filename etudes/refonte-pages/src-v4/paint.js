/* ═══ MG4 paint shop ═══
   The car is a set of 3D renders (Blender, Cycles) of MG's own configurator model, corrected to the official dimensions.
   For each finish (solid, metallic, matte, chrome) two renders exist: W (paint albedo 0.8) and K (albedo 0).
   Any paint colour c (linear light) is then  K + c/0.8 × (W − K),  exact for one bounce of light.
   Satin (Hunter Green) is a mix of the matte and the metallic renders, which is what a satin paint is.
   A position pass and a normal pass (one 3D point per pixel) let liveries be painted on the body in 3D, so the
   same livery is right in every view. Wheels are separate layers, and so are the matt black sills of the versions
   other than the XPower (the base renders carry the XPower's gloss black ones). Nothing here is drawn by hand. */
const PS=(()=>{
const META=/*__META__*/null, DIR='img/', WREF=0.8, FIN=['solid','metal','matte','chrome','satin'], MIXF={satin:['matte','metal',.6]};
const YF=-1.273, YR=1.462, RT=0.3345;                       // axles (m), tyre radius
const lut=new Float32Array(256); for(let i=0;i<256;i++){const c=i/255; lut[i]=c<=.04045?c/12.92:Math.pow((c+.055)/1.055,2.4)}
const ON=16383, olut=new Uint8Array(ON+1); for(let i=0;i<=ON;i++){const c=i/ON; olut[i]=Math.round(255*(c<=.0031308?c*12.92:1.055*Math.pow(c,1/2.4)-.055))}
const hex=h=>[1,3,5].map(i=>lut[parseInt(h.substr(i,2),16)]);
const clamp=(v,a,b)=>v<a?a:v>b?b:v, ss=(a,b,x)=>{let t=(x-a)/(b-a); t=t<0?0:t>1?1:t; return t*t*(3-2*t)}, fract=x=>x-Math.floor(x);

/* ── noise ── */
function h3(x,y,z){let n=Math.imul(x,374761393)^Math.imul(y,668265263)^Math.imul(z,1440662683); n=Math.imul(n^(n>>>13),1274126177); return ((n^(n>>>16))>>>0)/4294967296}
function vn(x,y,z){const xi=Math.floor(x),yi=Math.floor(y),zi=Math.floor(z); let u=x-xi,v=y-yi,w=z-zi; u=u*u*(3-2*u); v=v*v*(3-2*v); w=w*w*(3-2*w);
  const a=h3(xi,yi,zi),b=h3(xi+1,yi,zi),c=h3(xi,yi+1,zi),d=h3(xi+1,yi+1,zi),e=h3(xi,yi,zi+1),f=h3(xi+1,yi,zi+1),g=h3(xi,yi+1,zi+1),h=h3(xi+1,yi+1,zi+1);
  const p=a+(b-a)*u,q=c+(d-c)*u,r=e+(f-e)*u,s=g+(h-g)*u,t=p+(q-p)*v,k=r+(s-r)*v; return t+(k-t)*w}
function fbm(x,y,z,o){let a=.5,s=0,n=0; for(let i=0;i<o;i++){s+=a*vn(x,y,z); n+=a; x=x*2.03+11.3; y=y*2.03+4.7; z=z*2.03+7.9; a*=.5} return s/n}
const WO=[0,0,0];                                              // worley: d1, d2, hash of the nearest cell
function worley(x,y,z){const xi=Math.floor(x),yi=Math.floor(y),zi=Math.floor(z); let d1=9,d2=9,id=0;
  for(let k=-1;k<=1;k++)for(let j=-1;j<=1;j++)for(let i=-1;i<=1;i++){const cx=xi+i,cy=yi+j,cz=zi+k,h=h3(cx,cy,cz);
    const px=cx+h,py=cy+fract(h*57.31),pz=cz+fract(h*913.7),dx=px-x,dy=py-y,dz=pz-z,d=dx*dx+dy*dy+dz*dz;
    if(d<d1){d2=d1;d1=d;id=h}else if(d<d2)d2=d}
  WO[0]=Math.sqrt(d1);WO[1]=Math.sqrt(d2);WO[2]=id}
const HS=[0,0,0];
function hsv(h,s,v){h=fract(h)*6; const i=h|0,f=h-i,p=v*(1-s),q=v*(1-s*f),t=v*(1-s*(1-f)); let r,g,b;
  if(i===0){r=v;g=t;b=p}else if(i===1){r=q;g=v;b=p}else if(i===2){r=p;g=v;b=t}else if(i===3){r=p;g=q;b=v}else if(i===4){r=t;g=p;b=v}else{r=v;g=p;b=q}
  HS[0]=Math.pow(r,2.2);HS[1]=Math.pow(g,2.2);HS[2]=Math.pow(b,2.2)}
const ramp=stops=>{const st=stops.map(([t,h])=>[t,hex(h)]); return (t,s)=>{let i=1; while(i<st.length-1&&t>st[i][0])i++; const a=st[i-1],b=st[i]; let k=(t-a[0])/(b[0]-a[0]); k=k<0?0:k>1?1:k; k=k*k*(3-2*k);
  s.r=a[1][0]+(b[1][0]-a[1][0])*k; s.g=a[1][1]+(b[1][1]-a[1][1])*k; s.b=a[1][2]+(b[1][2]-a[1][2])*k}};
const put=(s,c,t)=>{s.r+=(c[0]-s.r)*t; s.g+=(c[1]-s.g)*t; s.b+=(c[2]-s.b)*t};
const C=Object.fromEntries(Object.entries({white:'#F4F4F2',black:'#0B0B0D',red:'#D8261C',blue:'#1B4AA0',yellow:'#F5C800',cyan:'#00B8F0',magenta:'#E6007E',gold:'#FFD400',teal:'#12B3B8',navy:'#123C8A',olive:'#243018',khaki:'#6B5A36',soot:'#0E0F0C',brown:'#1A120A',frBlue:'#0A3D91',frRed:'#E1000F',cream:'#F1EFE6'}).map(([k,v])=>[k,hex(v)]));

/* ── decals: flat artwork drawn in the plane of the car's side (y along the car, z up), in metres ── */
const DW=2048, DK=DW/4.6, DH=Math.round(1.7*DK), decals={};
function decal(name,draw){ if(decals[name])return decals[name]; const c=document.createElement('canvas'); c.width=DW; c.height=DH; const g=c.getContext('2d',{willReadFrequently:true});
  g.setTransform(DK,0,0,-DK,2.3*DK,1.7*DK); draw(g); return decals[name]={d:g.getImageData(0,0,DW,DH).data}}
const DS=[0,0,0,0];
function dsample(dc,y,z){const fx=(y+2.3)*DK-.5, fy=(1.7-z)*DK-.5, x0=Math.floor(fx), y0=Math.floor(fy); if(x0<0||y0<0||x0>=DW-1||y0>=DH-1){DS[3]=0;return}
  const u=fx-x0,v=fy-y0,d=dc.d,i=(y0*DW+x0)*4,j=i+DW*4, w00=(1-u)*(1-v),w10=u*(1-v),w01=(1-u)*v,w11=u*v;
  const a=d[i+3]*w00+d[i+7]*w10+d[j+3]*w01+d[j+7]*w11; DS[3]=a/255; if(a<1)return;
  for(let k=0;k<3;k++)DS[k]=lut[Math.round((d[i+k]*d[i+3]*w00+d[i+4+k]*d[i+7]*w10+d[j+k]*d[j+3]*w01+d[j+4+k]*d[j+7]*w11)/a)]}
function flamePath(g){ // hot-rod flames licking back from the front wheel arch
  const lick=(y0,za,zb,y1,z1,bend)=>{const zm=(za+zb)/2,L=y1-y0; g.moveTo(y0,za);
    g.bezierCurveTo(y0+L*.30,za+bend*.9, y0+L*.55,z1+bend*1.5, y1,z1); g.bezierCurveTo(y0+L*.62,z1-bend*.35, y0+L*.40,zb-bend*.9, y0+L*.16,zb+ (zm-zb)*.5); g.quadraticCurveTo(y0+L*.05,zb,y0,zb); g.closePath()};
  g.beginPath(); g.moveTo(-.86,.36); g.lineTo(-.86,.98); g.lineTo(-.60,.96); g.lineTo(-.60,.36); g.closePath();
  lick(-.70,.985,.86,.62,.93,.03); lick(-.70,.88,.74,1.05,.74,-.035); lick(-.70,.76,.62,.34,.66,.03); lick(-.70,.64,.50,.86,.50,-.03); lick(-.70,.52,.37,.10,.42,.025)}
const DECAL={
 num:g=>{g.fillStyle='#F4F4F2'; g.beginPath(); g.arc(-.42,.665,.245,0,7); g.fill(); g.lineWidth=.022; g.strokeStyle='#0B0B0D'; g.beginPath(); g.arc(-.42,.665,.222,0,7); g.stroke();
   g.save(); g.setTransform(1,0,0,1,0,0); g.fillStyle='#0B0B0D'; g.font=`italic 700 ${Math.round(.34*DK)}px "Saira Semi Condensed",Arial,sans-serif`; g.textAlign='center'; g.textBaseline='middle'; g.fillText('4',(2.3-.42-.014)*DK,(1.7-.665+.012)*DK); g.restore()},
 flame:g=>{const gr=g.createLinearGradient(-.86,0,1.05,0); gr.addColorStop(0,'#FFE94A'); gr.addColorStop(.28,'#FFB400'); gr.addColorStop(.6,'#FF6A00'); gr.addColorStop(1,'#D8140E');
   g.lineJoin='round'; flamePath(g); g.lineWidth=.03; g.strokeStyle='#8E0B08'; g.stroke(); g.fillStyle=gr; g.fill()},
 bolt:g=>{const p=[[-1.06,1.0],[-.10,.80],[-.40,.715],[.60,.565],[.30,.50],[1.28,.335],[.02,.60],[.32,.675],[-.64,.83],[-.36,.905]]; g.beginPath(); p.forEach(([y,z],i)=>i?g.lineTo(y,z):g.moveTo(y,z)); g.closePath();
   g.lineJoin='miter'; g.lineWidth=.045; g.strokeStyle='#0B0B0D'; g.stroke(); g.fillStyle='#FFD400'; g.fill()}};

/* ── paints. Official MG4 colours first (France, 2022-2024), then the fun ones. f = finish index in FIN ── */
const rainbow=s=>{hsv(.86*(s.y+2.145)/4.29+.05*(s.z-.7),.93,1); s.r=HS[0];s.g=HS[1];s.b=HS[2]};
const camRamp=ramp([[0,'#0FD3B0'],[.3,'#1C6BFF'],[.55,'#7A2BFF'],[.8,'#FF2D95'],[1,'#FFB02E']]);
const sunRamp=ramp([[0,'#2A0845'],[.28,'#8E1558'],[.54,'#E8304A'],[.78,'#FF8A1F'],[1,'#FFD84D']]);
const candyRamp=ramp([[0,'#FF4FA3'],[.36,'#C77DFF'],[.68,'#4CC9F0'],[1,'#3DF5C8']]);
const nebRamp=ramp([[.32,'#02041A'],[.47,'#14104A'],[.60,'#4A1A80'],[.74,'#A0247A']]);
const PAINTS=[
 {id:'white',n:'Dover White',hexc:'#E9EBEA',f:0},
 {id:'black',n:'Pebble Black',hexc:'#101114',f:1},
 {id:'silver',n:'Medal Silver',hexc:'#B9BDC2',f:1},
 {id:'grey',n:'Andes Grey',hexc:'#5A6069',f:1},
 {id:'blue',n:'Brighton Blue',hexc:'#008CC8',f:0},
 {id:'red',n:'Diamond Red',hexc:'#B40C12',f:1},
 {id:'orange',n:'Fizzy Orange',hexc:'#F0641A',f:1},
 {id:'green',n:'Hunter Green',hexc:'#7C9277',f:4,note:'satiné'},
 {id:'gold',n:'Or massif',fun:1,c:[1,.70,.26],f:3,sw:'linear-gradient(135deg,#FFF1B0,#E2A520 45%,#8A5A0A 70%,#F6CF5A)'},
 {id:'chrome',n:'Chrome',fun:1,c:[.93,.94,.96],f:3,sw:'linear-gradient(135deg,#FFFFFF,#9AA3AE 45%,#3B424C 62%,#E4E8EE)'},
 {id:'rainbow',n:'Arc-en-ciel',fun:1,c:[.55,.5,.5],f:1,fx:rainbow,sw:'linear-gradient(90deg,#FF2A2A,#FFB300,#F4F000,#22D04A,#12B8F0,#3A4BFF,#A02BFF)'},
 {id:'cameleon',n:'Caméléon',fun:1,c:[.1,.4,.7],f:1,sw:'conic-gradient(from 200deg,#0FD3B0,#1C6BFF,#7A2BFF,#FF2D95,#FFB02E,#0FD3B0)',
   fx:s=>camRamp(clamp(1.35*(1-s.f)+.2*(.5+.5*Math.sin(1.15*s.y+2.3*s.z)),0,1),s)},
 {id:'galaxy',n:'Galaxie',fun:1,c:[.03,.02,.12],f:0,sw:'radial-gradient(circle at 30% 30%,#B0257A,#3B1470 45%,#03061C 80%)',
   fx:s=>{const n=fbm(s.x*1.5+3,s.y*1.5,s.z*1.5,4),m=fbm(s.x*2.6+40,s.y*2.6,s.z*2.6+9,3); nebRamp(n,s); put(s,[.01,.16,.26],ss(.54,.76,m)*.75);
     worley(s.x*13,s.y*13,s.z*13); if(WO[2]>.3){const q=fract(WO[2]*31.7),k=ss(.05+.17*q*q,0,WO[0])*(.5+3*q); s.er+=k;s.eg+=k*.95;s.eb+=k*.82}}},
 {id:'sunset',n:'Coucher de soleil',fun:1,c:[.7,.15,.1],f:1,sw:'linear-gradient(0deg,#2A0845,#8E1558 30%,#E8304A 55%,#FF8A1F 78%,#FFD84D)',
   fx:s=>sunRamp(clamp((s.z-.16)/1.22+.10*(s.y/2.1),0,1),s)},
 {id:'candy',n:'Barbe à papa',fun:1,c:[.6,.35,.75],f:1,sw:'linear-gradient(120deg,#FF4FA3,#C77DFF 40%,#4CC9F0 72%,#3DF5C8)',
   fx:s=>candyRamp(clamp(.5+.16*s.y+.5*(s.z-.7)+.5*(fbm(s.x*1.1,s.y*1.1+5,s.z*1.1,2)-.5),0,1),s)},
 {id:'carbon',n:'Carbone forgé',fun:1,c:[.03,.03,.032],f:0,sw:'conic-gradient(from 20deg,#16181B,#3A3D42,#0C0D0F,#2A2C30,#111214,#34373B,#16181B)',
   fx:s=>{worley(s.x*24,s.y*24,s.z*24); const h=WO[2],g=.007+.05*h*h+.035*fract(h*71.3)*Math.pow(s.f,3); s.r=g;s.g=g;s.b=g*1.04}},
];
PAINTS.forEach(p=>{if(p.hexc)p.c=hex(p.hexc)});

/* ── liveries: painted over whatever paint is chosen. s.k = contrast colour (white on dark paints, black on light ones) ── */
const side=s=>ss(.30,.55,Math.abs(s.nx));
const band=(v,a,b,aa)=>ss(a-aa,a+aa,v)*(1-ss(b-aa,b+aa,v));
const cells=(s,sz,fn)=>{let a=s.y,b=s.z,c=0; if(s.nz>.62){b=s.x;c=1}else if(Math.abs(s.ny)>.75){a=s.x;c=2} const ia=Math.floor(a/sz),ib=Math.floor(b/sz); fn(ia,ib,c,a/sz-ia-.5,b/sz-ib-.5)};
const FL=[1.0,1.75,1.25,1.95,1.45], FO=hex('#7E0A08'), FT={r:0,g:0,b:0}, flRamp=ramp([[0,'#FFEC55'],[.3,'#FFBE0A'],[.62,'#FF7300'],[1,'#E3200F']]);
const LIVS=[
 {id:'none',n:'Aucune'},
 {id:'rally',n:'Rallye',dec:['num'],fx:s=>{const d=(s.y-.42)-(s.z-.6)*.62,a=s.aa,m=1-ss(.72,.9,s.nz)*0;
   put(s,C.cyan,band(d,0,.15,a)*m); put(s,C.magenta,band(d,.19,.34,a)*m); put(s,C.gold,band(d,.38,.53,a)*m);
   dsample(decals.num,s.y,s.z); if(DS[3]>0)put(s,DS,DS[3]*side(s)*(s.x>0?1:0))}},
 {id:'flames',n:'Flammes',fx:s=>{const w=side(s); if(w<=0||s.y<-1)return; const dw=Math.hypot(s.y-YF,s.z-RT); let m=0,mi=0,tt=0;
   for(let k=0;k<5;k++){const L=FL[k], t=(s.y+.98)/L; if(t<0||t>1)continue; const zc=.40+.125*k+.045*Math.sin(5.2*t*L+k*1.9)+.07*t*(k-1.2)*.6, hw=.076*Math.sqrt(Math.min(1,t/.06))*Math.pow(1-t,.75)*(1+.22*Math.sin(8*t*L+k)), d=Math.abs(s.z-zc);
     const a=1-ss(hw-s.aa,hw+s.aa,d), b=1-ss(hw-.018-s.aa,hw-.018+s.aa,d); if(a>m){m=a;tt=t} if(b>mi)mi=b}
   m*=ss(.45,.465,dw); if(m<=0)return; put(s,FO,m*w); flRamp(tt,FT); put(s,[FT.r,FT.g,FT.b],mi*m*w)}},
 {id:'bolt',n:'Éclair',dec:['bolt'],fx:s=>{dsample(decals.bolt,s.y,s.z); if(DS[3]>0)put(s,DS,DS[3]*side(s))}},
 {id:'check',n:'Damier',fx:s=>{const g=ss(-.35,1.75,s.y); if(g<=.02)return; cells(s,.115,(i,j,c,u,v)=>{const m=Math.max(Math.abs(u),Math.abs(v)),h=.5*g,a=s.aa/.115; put(s,(i+j)&1?C.black:C.white,1-ss(h-a,h+a,m))})}},
 {id:'pixels',n:'Pixels',fx:s=>{const t=Math.pow(ss(-.5,2.0,s.y),1.25)*.96; cells(s,.082,(i,j,c,u,v)=>{const h=h3(i,j,c+7); if(h>t)return; const q=fract(h*91.7), m=Math.max(Math.abs(u),Math.abs(v)), a=s.aa/.082;
   put(s,q<.34?s.k:q<.52?C.cyan:q<.68?C.magenta:q<.82?C.gold:[s.r*.45,s.g*.45,s.b*.45],1-ss(.46-a,.46+a,m))})}},
 {id:'tricolore',n:'Tricolore',fx:s=>{const d=(s.y+.15)+(s.z-.6)*.62,a=s.aa; put(s,C.frBlue,band(d,-.27,-.09,a)); put(s,C.white,band(d,-.09,.09,a)); put(s,C.frRed,band(d,.09,.27,a))}},
 {id:'camo',n:'Camouflage',fx:s=>{const q=2.3,n1=fbm(s.x*q+7,s.y*q,s.z*q,3),n2=fbm(s.x*q+31,s.y*q+9,s.z*q,3),n3=fbm(s.x*q*1.3,s.y*q*1.3+50,s.z*q*1.3,2);
   put(s,C.khaki,ss(.54,.555,n2)); put(s,C.olive,ss(.55,.565,n1)); put(s,C.soot,ss(.63,.645,n3))}},
 {id:'leopard',n:'Léopard',fx:s=>{worley(s.x*6.4,s.y*6.4,s.z*6.4); const br=vn(s.x*15,s.y*15,s.z*15), r=WO[0]/6.4*(1+.7*(vn(s.x*11+5,s.y*11,s.z*11)-.5)), a=s.aa, R=.026+.012*WO[2];
   put(s,[s.r*.6,s.g*.46,s.b*.36],1-ss(R-a,R+a,r)); put(s,C.brown,band(r,R,R+.02+.012*WO[2],a)*ss(.36,.44,br))}},
 {id:'zebra',n:'Zèbre',fx:s=>{const v=Math.sin(17*(s.y*.95+s.z*.33)+11*(fbm(s.x*1.1,s.y*1.1,s.z*1.1+3,2)-.5))+.9*(fbm(s.x*3,s.y*3+8,s.z*3,2)-.5); put(s,C.black,ss(.05,.2,v))}},
 {id:'ladybug',n:'Coccinelle',fx:s=>{cells(s,.33,(i,j,c,u,v)=>{const h=h3(i,j,c+3),du=u-(h-.5)*.3,dv=v-(fract(h*57.3)-.5)*.3,r=Math.hypot(du,dv)*.33,R=.07+.035*fract(h*13.7); put(s,C.black,1-ss(R-s.aa,R+s.aa,r))})}},
 {id:'cow',n:'Vache',fx:s=>{const n=fbm(s.x*1.55+13,s.y*1.55,s.z*1.55+2,3); put(s,C.black,ss(.545,.56,n))}},
];

/* ── loading: every image is read once into typed arrays, at full size or reduced (thumbnails) ── */
const imgs={}, packs={};
const loadImg=n=>imgs[n]||(imgs[n]=new Promise((ok,ko)=>{const im=new Image(); im.onload=()=>ok(im); im.onerror=()=>{delete imgs[n]; ko(new Error('image '+n))}; im.src=DIR+n}));
function px(im,w,h,smooth,box,sc){const c=document.createElement('canvas'); c.width=w; c.height=h; const g=c.getContext('2d',{willReadFrequently:true}); g.imageSmoothingEnabled=!!smooth; if(smooth)g.imageSmoothingQuality='high';
  if(box)g.drawImage(im,box[0]*sc,box[1]*sc,(box[2]-box[0])*sc,(box[3]-box[1])*sc); else g.drawImage(im,0,0,w,h); return g.getImageData(0,0,w,h).data}
function pack(view,body,sc){const k=view+body+'@'+sc; if(packs[k])return packs[k]; const m=META[view],w=Math.round(m.w*sc),h=Math.round(m.h*sc);
  return packs[k]={view,body,sc,w,h,n:w*h,m,fin:{},wh:{},pre:(view==='side'?'s':'q')+body,aa:(view==='side'?.0026:.0034)/sc}}
async function needFin(p,f){if(p.fin[f])return; const mx=MIXF[f];
  if(mx){await Promise.all([needFin(p,mx[0]),needFin(p,mx[1])]); if(p.fin[f])return; const a=p.fin[mx[0]],b=p.fin[mx[1]],t=mx[2],o={};
    for(const k of 'WK'){const x=a[k],y=b[k],d=new Uint8ClampedArray(x.length); for(let i=0;i<x.length;i++)d[i]=(i&3)===3?x[i]:olut[(lut[x[i]]*(1-t)+lut[y[i]]*t)*ON|0]; o[k]=d}
    p.fin[f]=o; return}
  const [W,K]=await Promise.all(['W','K'].map(t=>loadImg(`${p.pre}_${f}${t}.webp`))); p.fin[f]={W:px(W,p.w,p.h,1),K:px(K,p.w,p.h,1)}}
async function needAlpha(p){if(p.A)return; const d=px(await loadImg(p.pre+'_a.png'),p.w,p.h,1),A=new Uint8Array(p.n); for(let i=0;i<p.n;i++)A[i]=d[i*4]; p.A=A}
async function needPos(p){if(p.P)return; const [a,b,c]=await Promise.all(['p1','p2','n'].map(t=>loadImg(`${p.pre}_${t}.png`)));
  const d1=px(a,p.w,p.h,0),d2=px(b,p.w,p.h,0),dn=px(c,p.w,p.h,0),P=new Float32Array(p.n*3),N=new Int8Array(p.n*3),V=new Uint8Array(p.n),lo=META.pos.lo,sz=META.pos.size;
  for(let i=0,j=0,k=0;i<p.n;i++,j+=4,k+=3){if(dn[j+3]<128)continue; V[i]=1;
    P[k]=lo[0]+sz[0]*((d1[j+2]<<8|d2[j+2])/65535); P[k+1]=lo[1]+sz[1]*((d1[j]<<8|d1[j+1])/65535); P[k+2]=lo[2]+sz[2]*((d2[j]<<8|d2[j+1])/65535);
    N[k]=dn[j]-128; N[k+1]=dn[j+1]-128; N[k+2]=dn[j+2]-128}
  p.P=P;p.N=N;p.V=V}
async function needRoof(p){if(p.R)return; const box=p.m.roof[p.body]; const R=new Uint8Array(p.n); if(box){const d=px(await loadImg(p.pre+'_roof.png'),p.w,p.h,1,box,p.sc); for(let i=0;i<p.n;i++)R[i]=d[i*4]} p.R=R}
async function needWheel(p,t){if(!p.wh[t])p.wh[t]=await loadImg(`${p.pre[0]}_wh_${t}.webp`)}
async function needTrim(p){if(p.tr===undefined)p.tr=p.m.trim?await loadImg(`${p.pre[0]}_trim.webp`):null}

/* ── the compositor ── */
function compose(p,cfg){const {w,h,n,A}=p, im=new ImageData(w,h), o=im.data, paint=cfg.paint, liv=cfg.liv, f0=paint.f;
  const bas=FIN.map(f=>p.fin[f]), B0=bas[f0], c0=paint.c, cr=c0[0]/WREF, cg=c0[1]/WREF, cb=c0[2]/WREF, roof=cfg.roof&&p.R, KS=roof?p.fin.solid.K:null, R=p.R;
  const lum=.2126*c0[0]+.7152*c0[1]+.0722*c0[2], shade=paint.fx||liv.fx;
  if(!shade){const W=B0.W,K=B0.K;
    for(let i=0,j=0;i<n;i++,j+=4){const a=A[i]; if(!a)continue; const kr=lut[K[j]],kg=lut[K[j+1]],kb=lut[K[j+2]];
      let r=kr+cr*(lut[W[j]]-kr), g=kg+cg*(lut[W[j+1]]-kg), b=kb+cb*(lut[W[j+2]]-kb);
      if(roof&&R[i]){const t=R[i]/255; r+=(lut[KS[j]]-r)*t; g+=(lut[KS[j+1]]-g)*t; b+=(lut[KS[j+2]]-b)*t}
      o[j]=olut[r>=1?ON:r<=0?0:r*ON|0]; o[j+1]=olut[g>=1?ON:g<=0?0:g*ON|0]; o[j+2]=olut[b>=1?ON:b<=0?0:b*ON|0]; o[j+3]=a}
  }else{const P=p.P,N=p.N,V=p.V,cam=p.m.cam,cx=cam[0],cy=cam[1],cz=cam[2], s={x:0,y:0,z:0,nx:0,ny:0,nz:0,f:0,r:0,g:0,b:0,fin:0,er:0,eg:0,eb:0,aa:p.aa,k:lum>.30?C.black:C.white}, pfx=paint.fx, lfx=liv.fx;
    for(let i=0,j=0,k=0;i<n;i++,j+=4,k+=3){const a=A[i]; if(!a)continue; let r,g,b,er=0,eg=0,eb=0,Bx=B0;
      if(V[i]){const x=P[k],y=P[k+1],z=P[k+2],nx=N[k]/127,ny=N[k+1]/127,nz=N[k+2]/127,vx=cx-x,vy=cy-y,vz=cz-z,f=(nx*vx+ny*vy+nz*vz)/Math.sqrt(vx*vx+vy*vy+vz*vz);
        s.x=x;s.y=y;s.z=z;s.nx=nx;s.ny=ny;s.nz=nz;s.f=f<0?0:f>1?1:f;s.r=c0[0];s.g=c0[1];s.b=c0[2];s.fin=f0;s.er=0;s.eg=0;s.eb=0;
        if(pfx)pfx(s); if(lfx)lfx(s); r=s.r/WREF;g=s.g/WREF;b=s.b/WREF;er=s.er;eg=s.eg;eb=s.eb;Bx=bas[s.fin]||B0}
      else{r=cr;g=cg;b=cb}
      const W=Bx.W,K=Bx.K,kr=lut[K[j]],kg=lut[K[j+1]],kb=lut[K[j+2]];
      r=kr+r*(lut[W[j]]-kr)+er; g=kg+g*(lut[W[j+1]]-kg)+eg; b=kb+b*(lut[W[j+2]]-kb)+eb;
      if(roof&&R[i]){const t=R[i]/255; r+=(lut[KS[j]]-r)*t; g+=(lut[KS[j+1]]-g)*t; b+=(lut[KS[j+2]]-b)*t}
      o[j]=olut[r>=1?ON:r<=0?0:r*ON|0]; o[j+1]=olut[g>=1?ON:g<=0?0:g*ON|0]; o[j+2]=olut[b>=1?ON:b<=0?0:b*ON|0]; o[j+3]=a}}
  const c=document.createElement('canvas'); c.width=w; c.height=h; const g=c.getContext('2d'); g.putImageData(im,0,0);
  if(cfg.trim&&p.tr){const b=p.m.trim,sc=p.sc; g.imageSmoothingQuality='high'; g.drawImage(p.tr,b[0]*sc,b[1]*sc,(b[2]-b[0])*sc,(b[3]-b[1])*sc)}
  if(cfg.wheel){const b=p.m.wheels[cfg.wheel],sc=p.sc; g.imageSmoothingQuality='high'; g.drawImage(p.wh[cfg.wheel],b[0]*sc,b[1]*sc,(b[2]-b[0])*sc,(b[3]-b[1])*sc)}
  return c}

/* ── cache + mounting on the page's <canvas data-car="view|scale|body|paint|livery|roof|wheel|matt sills"> ── */
const cache=new Map(), last={};
const keyOf=(view,sc,cfg)=>[view,sc,cfg.body,cfg.paint.id,cfg.liv.id,cfg.roof?1:0,cfg.wheel||'',cfg.trim?1:0].join('|');
async function get(view,sc,cfg){const k=keyOf(view,sc,cfg); let c=cache.get(k); if(c){cache.delete(k);cache.set(k,c);return c}
  const p=pack(view,cfg.body,sc), fins=new Set([FIN[cfg.paint.f]]); if(cfg.roof)fins.add('solid'); (cfg.liv.fins||[]).forEach(f=>fins.add(f));
  (cfg.liv.dec||[]).forEach(d=>decal(d,DECAL[d]));
  await Promise.all([needAlpha(p),...[...fins].map(f=>needFin(p,f)),(cfg.paint.fx||cfg.liv.fx)?needPos(p):0,cfg.roof?needRoof(p):0,cfg.wheel?needWheel(p,cfg.wheel):0,cfg.trim?needTrim(p):0]);
  c=cache.get(k); if(c)return c; c=compose(p,cfg); cache.set(k,c);
  let big=0,small=0; for(const [kk,v] of [...cache].reverse()){if(v.width>700){if(++big>7)cache.delete(kk)}else if(++small>140)cache.delete(kk)}
  return c}
const parse=str=>{const [view,sc,body,pi,li,roof,wheel,trim]=str.split('|'); return {view,sc:+sc,cfg:{body:+body,paint:PAINTS[+pi],liv:LIVS[+li],roof:+roof,wheel,trim:+trim||0}}};
const queue=[]; let busy=false;
async function pump(){if(busy)return; busy=true; while(queue.length){const cv=queue.shift(); if(!cv.isConnected)continue; const want=cv.dataset.car,{view,sc,cfg}=parse(want);
    try{const c=await get(view,sc,cfg); if(cv.isConnected&&cv.dataset.car===want)blit(cv,c)}catch(e){console.error(e)}
    if(sc<1)await new Promise(r=>setTimeout(r,0))}
  busy=false}
function blit(cv,c){if(cv.width!==c.width||cv.height!==c.height){cv.width=c.width;cv.height=c.height} const g=cv.getContext('2d'); g.clearRect(0,0,cv.width,cv.height); g.drawImage(c,0,0); cv.dataset.ok=cv.dataset.car; if(cv.dataset.slot)last[cv.dataset.slot]=c}
function mount(root){root.querySelectorAll('canvas[data-car]').forEach(cv=>{const {view,sc,cfg}=parse(cv.dataset.car), c=cache.get(keyOf(view,sc,cfg));
    if(c){blit(cv,c);return} const old=cv.dataset.slot&&last[cv.dataset.slot]; if(old){cv.width=old.width;cv.height=old.height;cv.getContext('2d').drawImage(old,0,0)}
    if(cv.dataset.slot)queue.unshift(cv);else queue.push(cv)}); pump();
  root.querySelectorAll('canvas[data-wheel]').forEach(async cv=>{const t=cv.dataset.wheel,m=META.side,im=await loadImg('s_wh_'+t+'.webp'),b=m.wheels[t],a=m.an.front,r=a.ry*.84,S=cv.width,g=cv.getContext('2d');
    g.clearRect(0,0,S,S); g.save(); g.beginPath(); g.arc(S/2,S/2,S/2,0,7); g.clip(); g.fillStyle='#050608'; g.fillRect(0,0,S,S); g.imageSmoothingQuality='high'; g.drawImage(im,a.c[0]-r-b[0],a.c[1]-r-b[1],2*r,2*r,0,0,S,S); g.restore()})}
const str=(view,sc,o)=>[view,sc,o.body,o.paint,o.liv,o.roof?1:0,o.wheel||'',o.trim?1:0].join('|');
return {META,PAINTS,LIVS,mount,get,str,hex,YF,YR}})();
