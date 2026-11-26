/* ═══ Air flows over the cabin picture ═══
   Each stream is a ribbon that follows a Bézier curve placed on a real outlet of the photo (coordinates in the
   2400×900 source image) and widens along its course. Waves of light travel along the ribbons: their colour says
   heating or cooling, their speed and strength follow the fan. */
const Air=(()=>{
const K=1808/2400, OY=-20, CW=1808, CH=644, N=16;
let raf=0, state=null, t0=0;
const lines={face:[],screen:[],feet:[]};
const bez=(p,n=20)=>{const o=[]; for(let i=0;i<=n;i++){const t=i/n,u=1-t,a=u*u*u,b=3*u*u*t,c=3*u*t*t,d=t*t*t; o.push([(a*p[0]+b*p[2]+c*p[4]+d*p[6])*K,(a*p[1]+b*p[3]+c*p[5]+d*p[7])*K+OY])} return o};
function add(k,p,w0,w1,v){const P=bez(p),n=P.length-1,L=[],R=[];
  for(let i=0;i<=n;i++){const a=P[Math.max(0,i-1)],b=P[Math.min(n,i+1)],dx=b[0]-a[0],dy=b[1]-a[1],d=Math.hypot(dx,dy)||1,t=i/n,w=(w0+(w1-w0)*Math.pow(t,1.35))/2; L.push([P[i][0]-dy/d*w,P[i][1]+dx/d*w]); R.push([P[i][0]+dy/d*w,P[i][1]-dx/d*w])}
  const path=new Path2D(), core=new Path2D(); L.forEach((q,i)=>i?path.lineTo(q[0],q[1]):path.moveTo(q[0],q[1])); for(let i=n;i>=0;i--)path.lineTo(R[i][0],R[i][1]); path.closePath();
  P.forEach((q,i)=>i?core.lineTo(q[0],q[1]):core.moveTo(q[0],q[1]));
  lines[k].push({path,core,a:P[0],b:P[n],v,ph:(lines[k].length*.37)%1})}
// face: the four outlets of the slim vent band, each one fanning out towards the occupant it serves
[[452,612,560,900,170],[1135,620,905,905,170],[1300,620,1530,905,170],[1930,630,1790,900,170]].forEach(([x,y,tx,ty,sp])=>{
  for(let i=0;i<5;i++){const ex=tx+(i-2)*sp/2, ey=ty-Math.abs(i-2)*10; add('face',[x+(i-2)*8,y,x+(i-2)*14+(ex-x)*.10,y+60,ex-(ex-x)*.25,ey-150,ex,ey],3,46,.85+.07*i)}});
// windscreen: along the top of the dashboard, rising on the glass and opening out
for(let i=0;i<12;i++){const x=690+i*100, dx=(x-1240)*.34, y=392+Math.abs(x-1240)*.012; add('screen',[x,y,x+dx*.12,y-90,x+dx*.65,y-190,x+dx,y-300-((i*7)%3)*18],5,58,.8+.05*(i%4))}
// feet: under the dashboard, down into both footwells
[[640,726],[1500,706]].forEach(([x0,y0])=>{for(let i=0;i<5;i++){const x=x0+i*86, dx=(i-2)*34; add('feet',[x,y0,x+dx*.2,y0+50,x+dx*.8,y0+120,x+dx,y0+196],5,50,.8+.06*i)}});
const GLOW={face:[[452,612,64],[1135,622,54],[1300,622,54],[1930,632,64]],screen:[[760,392,100],[1000,388,100],[1240,386,100],[1480,388,100],[1720,392,100]],feet:[[812,736,120],[1672,716,120]]};
const comet=d=>d<.42?(1-d/.42)*(1-d/.42):0;
function frame(now){const cv=state&&state.cv; if(!cv||!cv.isConnected){raf=0;return} const g=cv.getContext('2d'), s=state, t=(now-t0)/1000;
  g.setTransform(1,0,0,1,0,0); g.clearRect(0,0,CW,CH); if(s.rhd)g.setTransform(-1,0,0,1,CW,0);
  g.globalCompositeOperation='lighter'; g.lineCap='round'; g.lineJoin='round';
  const [r,gg,b]=s.heat?[255,140,36]:[36,200,255], I=s.power, sp=.14+.42*I;
  for(const k of ['screen','face','feet']){if(!s.flow[k])continue; const boost=k==='screen'?(s.defrost?1.5:1.15):1, A=(.3+.7*I)*boost;
    for(const [x,y,R] of GLOW[k]){const cx=x*K, cy=y*K+OY, rr=R*K*(1+.25*I), gr=g.createRadialGradient(cx,cy,0,cx,cy,rr); gr.addColorStop(0,`rgba(${r},${gg},${b},${Math.min(1,.42*A)})`); gr.addColorStop(1,`rgba(${r},${gg},${b},0)`); g.fillStyle=gr; g.fillRect(cx-rr,cy-rr,rr*2,rr*2)}
    for(const L of lines[k]){const h=s.still?(.62+L.ph*.3):(t*sp*L.v+L.ph)%1, gr=g.createLinearGradient(L.a[0],L.a[1],L.b[0],L.b[1]), gc=g.createLinearGradient(L.a[0],L.a[1],L.b[0],L.b[1]);
      for(let i=0;i<=N;i++){const u=i/N, e=Math.min(1,u/.1)*Math.pow(1-u,1.15), d1=((h-u)%1+1)%1, d2=((h+.5-u)%1+1)%1, c=comet(d1)+comet(d2), a=e*A*(.10+.5*c);
        gr.addColorStop(u,`rgba(${r},${gg},${b},${Math.min(1,a).toFixed(3)})`); gc.addColorStop(u,`rgba(255,255,255,${Math.min(1,e*A*(.05+.5*c)).toFixed(3)})`)}
      g.fillStyle=gr; g.fill(L.path); g.strokeStyle=gc; g.lineWidth=1.6; g.stroke(L.core)}}
  g.globalCompositeOperation='source-over';
  raf=s.still?0:requestAnimationFrame(frame)}
function start(cv,o){cancelAnimationFrame(raf); raf=0; state={cv,...o,still:matchMedia('(prefers-reduced-motion:reduce)').matches||o.still}; if(!t0)t0=performance.now();
  if(!o.power){const g=cv.getContext('2d'); g.setTransform(1,0,0,1,0,0); g.clearRect(0,0,CW,CH); return}
  if(state.still)frame(performance.now()); else raf=requestAnimationFrame(frame)}
const stop=()=>{cancelAnimationFrame(raf); raf=0; state=null};
return {start,stop,K,OY}})();
