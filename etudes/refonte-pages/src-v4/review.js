// final visual review: each state on a fresh page load, captured from the page exactly as published (port 8766)
const {chromium}=require('playwright');
const ST=[
['r01_cfg_modele','S.cfg=1;S.tab=0'],
['r02_cfg_xpower','S.cfg=1;S.tab=0;S.ver=3;S.color=7;S.wheel=3;S.roof=1'],
['r03_cfg_livree','S.cfg=1;S.tab=2;S.liv=5'],
['r04_profil','S.vview=0'],
['r05_luxury_toit','S.ver=2;S.wheel=2;S.roof=1;S.color=5'],
['r06_arcenciel','S.color=11'],
['r07_charge_limite','S.page="ene";S.plug=1;S.chg=1;S.nat=1'],
['r08_v2l','S.page="ene";S.plug=1;S.v2lon=1'],
['r09_clim_froid_rhd','S.page="cli";S.out=31;S.rhd=1;S.defF=1'],
['r10_pneu','S.tyre=1'],
];
(async()=>{const b=await chromium.launch();let bad=0;
for(const [name,code] of ST){const p=await b.newPage({viewport:{width:1952,height:900}});
p.on('console',m=>{if(m.type()==='error'){bad++;console.log('ERR',name,m.text())}});p.on('pageerror',e=>{bad++;console.log('PAGEERR',name,e.message)});
await p.goto('http://127.0.0.1:8766/index.html');await p.waitForTimeout(600);
await p.evaluate(code+';render()');
await p.waitForFunction(()=>[...document.querySelectorAll('#scr canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000}).catch(()=>{bad++;console.log('TIMEOUT',name)});
await p.waitForTimeout(900);
await (await p.$('#sw')).screenshot({path:__dirname+'/shots/'+name+'.png'});await p.close();console.log('shot',name)}
console.log(bad?'PROBLEMS '+bad:'no errors');await b.close()})();
