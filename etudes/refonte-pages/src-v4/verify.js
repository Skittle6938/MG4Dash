// Automated checks of the mock-up page: every state of the three pages, overflow, long press, gallery, errors.
const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch(), ctx=await b.newContext({viewport:{width:1532,height:900}}), p=await ctx.newPage(); let errs=0;
p.on('console',m=>{if(m.type()==='error'){errs++;console.log('ERR',m.text())}});p.on('pageerror',e=>{errs++;console.log('PAGEERR',e.message)});
p.on('requestfailed',r=>{errs++;console.log('REQFAIL',r.url())}); p.on('response',r=>{if(r.status()>=400){errs++;console.log('HTTP',r.status(),r.url())}});
await p.goto('http://127.0.0.1:8765/preview.html'); await p.waitForTimeout(900);
const ready=()=>p.waitForFunction(()=>[...document.querySelectorAll('#scr canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000}).catch(()=>{errs++;console.log('TIMEOUT cars')});
const set=async code=>{await p.evaluate(code+';render()');await ready();await p.waitForTimeout(60)};
const overflow=()=>p.evaluate(()=>{const out=[];document.querySelectorAll('#scr .card,#scr .st,#scr .chip,#scr .opt,#scr .glass,#scr .col,#scr .seg>div,#scr .hb,#scr .hs,#scr .bh,#scr .hint').forEach(n=>{if(n.scrollWidth>n.clientWidth+1||n.scrollHeight>n.clientHeight+1)out.push(n.className+' «'+n.textContent.trim().slice(0,26)+'» '+n.scrollWidth+'>'+n.clientWidth+' '+n.scrollHeight+'>'+n.clientHeight)});
  const pg=document.querySelector('#scr .pg');if(pg){const R=pg.getBoundingClientRect();pg.querySelectorAll('.card,.st,.chip,.hint,.co,.bh,.hs,.glass,.hb,.opt,.pop').forEach(n=>{const r=n.getBoundingClientRect();if(r.width&&(r.right>R.right+1||r.bottom>R.bottom+1||r.left<R.left-1||r.top<R.top-1))out.push('OUT '+n.className+' «'+n.textContent.trim().slice(0,24)+'»')})}
  // text boxes that collide inside the centre column (car callouts, hints, pills)
  const boxes=[...document.querySelectorAll('#scr .co,#scr .hint,#scr .bh,#scr .hs')].map(n=>[n,n.getBoundingClientRect()]);
  for(let i=0;i<boxes.length;i++)for(let j=i+1;j<boxes.length;j++){const a=boxes[i][1],c=boxes[j][1];if(a.width&&c.width&&a.left<c.right-2&&c.left<a.right-2&&a.top<c.bottom-2&&c.top<a.bottom-2)out.push('HIT «'+boxes[i][0].textContent.trim().slice(0,18)+'» × «'+boxes[j][0].textContent.trim().slice(0,18)+'»')}
  return out});
let n=0; const chk=async(name,code)=>{await set(code);const o=await overflow();n++;if(o.length){errs++;console.log('OVERFLOW',name,'→',o.slice(0,6).join(' | '))}};
const reset='Object.assign(S,{page:"veh",cur:0,ver:0,color:4,roof:0,wheel:1,liv:0,rhd:0,cfg:0,tab:1,hero:0,side:0,mode:1,regen:3,pedal:0,steer:1,brake:0,esave:0,lane:2,aeb:2,oldfw:0,tyre:0,temp:5,fan:4,flow:[1,1,0],on:1,auto:0,ac:0,eco:0,recirc:1,defF:0,defR:0,linkDef:1,linkEco:0,nolink:0,out:9,plug:0,chg:0,v2lon:0,nat:0,limit:3,amps:3,sched:1,finish:0,v2l:2,period:0,heatB:0,soc:76,vview:1})';
// ── Véhicule: modes × old firmware × tyre × lane/aeb values × every version
for(const vv of [0,1])for(const old of [0,1])for(const mode of [0,1,2,3,4])for(const tyre of [0,1])await chk(`veh view${vv} old${old} mode${mode} tyre${tyre}`,`${reset};S.vview=${vv};S.oldfw=${old};S.mode=${mode};S.tyre=${tyre};S.pedal=${mode%2};S.lane=${mode%4};S.aeb=${mode%3};S.steer=${mode%3};S.brake=${(mode+1)%3}`);
for(const vv of [0,1])for(let v=0;v<4;v++)for(const roof of [0,1])await chk(`veh view${vv} ver${v} roof${roof}`,`${reset};S.vview=${vv};act("ver:${v}");S.roof=${roof};S.liv=${v*3+1};S.color=${v*2+1}`);
// ── Couleurs et style: 3 tabs × 2 heroes, every paint and every livery once
for(const tab of [0,1,2])for(const hero of [0,1])await chk(`cfg tab${tab} hero${hero}`,`${reset};S.cfg=1;S.tab=${tab};S.hero=${hero}`);
const np=await p.evaluate('PAINTS.length'), nl=await p.evaluate('LIVS.length');
for(let i=0;i<np;i++)await chk(`cfg paint${i}`,`${reset};S.cfg=1;S.tab=1;S.color=${i};S.ver=${i%4};S.wheel=${i%4};S.roof=${i%2}`);
for(let i=0;i<nl;i++)await chk(`cfg liv${i}`,`${reset};S.cfg=1;S.tab=2;S.liv=${i};S.color=${(i*3)%np};S.hero=${i%2}`);
// ── Énergie: plug × charge × V2L × native limit × heat × limit value × soc
for(const nat of [0,1])for(const st of ['','S.plug=1','S.plug=1;S.chg=1','S.plug=1;S.v2lon=1'])for(const heat of [0,1])for(const lim of [0,3,5])for(const soc of [8,76,100])await chk(`ene nat${nat} [${st}] heat${heat} lim${lim} soc${soc}`,`${reset};S.page="ene";S.nat=${nat};${st};S.heatB=${heat};S.limit=${lim};S.soc=${soc};S.v2l=${lim};S.sched=${heat};S.period=${lim%3}`);
// ── Climatisation: on/off × auto × temp ends × flows × defrost × rhd × nolink × outside
for(const rhd of [0,1])for(const nolink of [0,1])for(const st of ['','S.on=0','S.auto=1','S.temp=1;S.out=31','S.temp=17','S.defF=1;S.defR=1','S.flow=[1,1,1];S.fan=11','S.flow=[0,0,1];S.fan=1;S.recirc=0','S.side=1','S.ac=1;S.eco=1;S.recirc=2;S.linkEco=1'])await chk(`cli rhd${rhd} nolink${nolink} [${st}]`,`${reset};S.page="cli";S.rhd=${rhd};S.nolink=${nolink};${st}`);
// ── current screens
for(const pg of ['veh','ene','cli'])await chk('cur '+pg,`${reset};S.page="${pg}";S.cur=1`);
console.log('states checked',n);
// ── every clickable thing on every page, once (monkey)
let clicks=0; for(const pre of ['S.page="veh"','S.page="veh";S.mode=4','S.page="veh";S.cfg=1;S.tab=0','S.page="veh";S.cfg=1;S.tab=1','S.page="veh";S.cfg=1;S.tab=2','S.page="ene"','S.page="ene";S.plug=1;S.nat=1','S.page="ene";S.plug=1;S.v2lon=1','S.page="cli"','S.page="cli";S.side=1']){
  await set(reset+';'+pre); const acts=await p.evaluate(()=>[...new Set([...document.querySelectorAll('#scr [data-a]')].map(n=>n.dataset.a))]);
  for(const a of acts){await p.evaluate(([r,pre,a])=>{eval(r+';'+pre);render();act(a)},[reset,pre,a]);clicks++} await ready()}
console.log('actions fired',clicks);
// ── demo buttons under the stage (real clicks)
for(const pg of ['veh','ene','cli']){await set(reset+`;S.page="${pg}"`); const k=await p.$$eval('#demo button',b=>b.length); for(let i=0;i<k;i++){await (await p.$$('#demo button'))[i].click(); await p.waitForTimeout(40)} await ready()}
// ── long press with the mouse
await set(reset); await p.evaluate(()=>document.getElementById('sw').scrollIntoView({block:'center'})); await p.waitForTimeout(200);
const centre=async sel=>{const r=await p.$eval(sel,n=>{const r=n.getBoundingClientRect();return [r.left+r.width*.5,r.top+r.height*.45]});return r};
let [x,y]=await centre('#scr canvas[data-lp=cfg]');
await p.mouse.move(x,y); await p.mouse.down(); await p.waitForTimeout(150); await p.mouse.up(); await p.waitForTimeout(80);
console.log('short click on the car → menu', await p.evaluate('S.cfg'), '(expected 0), view', await p.evaluate('S.vview'), '(expected 0: switched to profile)');
[x,y]=await centre('#scr canvas[data-lp=cfg]'); await p.mouse.move(x,y);
await p.mouse.down(); await p.waitForTimeout(250); await p.mouse.move(x+40,y+5); await p.waitForTimeout(400); await p.mouse.up(); await p.waitForTimeout(80);
console.log('press then slide → menu', await p.evaluate('S.cfg'), '(expected 0)');
await p.mouse.move(x,y); await p.mouse.down(); await p.waitForTimeout(650); await p.mouse.up(); await p.waitForTimeout(150);
console.log('long press on the car → menu', await p.evaluate('S.cfg'), '(expected 1)');
await p.click('#scr [data-a="cfg"].hb'); await p.waitForTimeout(80); console.log('« Terminé » → menu', await p.evaluate('S.cfg'), '(expected 0)');
await set(reset+';S.page="ene"'); [x,y]=await centre('#scr .lp[data-lp=cfg]'); await p.mouse.move(x,y-40); await p.mouse.down(); await p.waitForTimeout(650); await p.mouse.up(); await p.waitForTimeout(150);
console.log('long press on the x-ray car → menu', await p.evaluate('S.cfg+" page "+S.page'), '(expected 1)');
await set(reset+';S.page="cli"'); const cab=await p.$eval('#scr .cab',n=>{const r=n.getBoundingClientRect();return [r.left+r.width*.5,r.top+r.height*.2]});
await p.mouse.move(cab[0],cab[1]); await p.mouse.down(); await p.waitForTimeout(650); await p.mouse.up(); await p.waitForTimeout(150);
console.log('long press on the dashboard → popup', await p.evaluate('S.side'), '(expected 1); flow after it', await p.evaluate('S.flow.join("")'), '(expected 110)');
await p.click('#scr .pop [data-a="rhd:1"]'); await p.waitForTimeout(100); console.log('choose right-hand drive → rhd', await p.evaluate('S.rhd+" side "+S.side'), '(expected 1 side 0)');
// short click on the windscreen zone toggles that flow
await set(reset+';S.page="cli"'); await p.mouse.click(cab[0],cab[1]); await p.waitForTimeout(100); console.log('tap the windscreen → flow', await p.evaluate('S.flow.join("")'), '(expected 111)');
// long press on a glass card must not open the popup
const gl=await centre('#scr .glass'); await p.mouse.move(gl[0],gl[1]); await p.mouse.down(); await p.waitForTimeout(650); await p.mouse.up(); await p.waitForTimeout(100); console.log('long press on a card → popup', await p.evaluate('S.side'), '(expected 0)');
// ── air canvas is painted
await set(reset+';S.page="cli"'); await p.waitForTimeout(300);
console.log('air canvas lit pixels', await p.evaluate(()=>{const c=document.getElementById('air'),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<d.length;i+=4)if(d[i]>8)n++;return n}));
await set(reset+';S.page="cli";S.on=0'); await p.waitForTimeout(200);
console.log('air canvas when off', await p.evaluate(()=>{const c=document.getElementById('air'),d=c.getContext('2d').getImageData(0,0,c.width,c.height).data;let n=0;for(let i=3;i<d.length;i+=4)if(d[i]>8)n++;return n}), '(expected 0)');
// ── gallery
await p.evaluate(()=>document.getElementById('galB').scrollIntoView({block:'center'})); await p.waitForTimeout(500);
await p.waitForFunction(()=>[...document.querySelectorAll('#galB canvas')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000});
await p.click('#galB button:nth-child(4)'); await p.waitForTimeout(900);
console.log('gallery tile → ', await p.evaluate('PAINTS[S.color].n+" / "+VERS[S.ver].n+" / page "+S.page'), 'stage top in view:', await p.evaluate(()=>{const r=document.getElementById('sw').getBoundingClientRect();return r.top>=-5&&r.bottom<=innerHeight+5}));
console.log('page scrolls sideways:', await p.evaluate(()=>document.documentElement.scrollWidth>innerWidth));
console.log(errs?('PROBLEMS: '+errs):'ALL CLEAR'); await b.close()})();
