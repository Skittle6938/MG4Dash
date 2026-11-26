// usage: node shots.js '<json [[name, js code], ...]>' [viewport width] [full]
// Each state: runs the code, re-renders, waits until every car canvas is painted, then captures the 1920×720 stage.
const {chromium}=require('playwright');
(async()=>{const vw=+(process.argv[3]||1532), b=await chromium.launch(), p=await b.newPage({viewport:{width:vw,height:900},deviceScaleFactor:+(process.env.DPR||1)});
p.on('console',m=>{if(m.type()==='error')console.log('ERR',m.text())});p.on('pageerror',e=>console.log('PAGEERR',e.message));
p.on('requestfailed',r=>console.log('REQFAIL',r.url()));
p.on('response',r=>{if(r.status()>=400)console.log('HTTP',r.status(),r.url())});
await p.goto('http://127.0.0.1:8765/preview.html');await p.waitForTimeout(700);
const ready=()=>p.waitForFunction(()=>[...document.querySelectorAll('#scr canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000}).catch(e=>console.log('TIMEOUT cars'));
const states=JSON.parse(process.argv[2]);
for(const [name,code] of states){await p.evaluate(code+';render()');await ready();await p.waitForTimeout(+(process.env.WAIT||450));
  const ov=await p.evaluate(()=>{const out=[];document.querySelectorAll('#scr .card,#scr .st,#scr .chip,#scr .opt,#scr .glass,#scr .col,#scr .seg>div,#scr .hb').forEach(n=>{if(n.scrollWidth>n.clientWidth+1||n.scrollHeight>n.clientHeight+1)out.push((n.className+' «'+n.textContent.trim().slice(0,28)+'» '+n.scrollWidth+'>'+n.clientWidth+' '+n.scrollHeight+'>'+n.clientHeight))});
    const pg=document.querySelector('#scr .pg');if(pg){const R=pg.getBoundingClientRect();pg.querySelectorAll('.card,.st,.chip,.hint,.co,.bh,.hs,.glass,.hb').forEach(n=>{const r=n.getBoundingClientRect();if(r.width&&(r.right>R.right+1||r.bottom>R.bottom+1||r.left<R.left-1||r.top<R.top-1))out.push('OUT '+n.className+' «'+n.textContent.trim().slice(0,24)+'»')})}return out});
  if(ov.length)console.log('OVERFLOW',name,ov.slice(0,8).join(' | '));
  const el=await p.$('#sw');await el.screenshot({path:__dirname+'/shots/'+name+'.png'});console.log('shot',name)}
if(process.argv[4]){await p.screenshot({path:__dirname+'/shots/full_'+vw+'.png',fullPage:true});console.log('full', await p.evaluate(()=>document.documentElement.scrollWidth+'/'+innerWidth))}
await b.close()})();
