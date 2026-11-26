// review of the 26/11 corrections: each state on a fresh page load (local preview, port 8765)
const {chromium}=require('playwright');
const ALL=[
['a01_std_q34','S.ver=0;S.color=5;S.wheel=1'],
['a02_std_profil','S.ver=0;S.color=5;S.wheel=1;S.vview=0'],
['a03_xp_q34','S.ver=3;S.color=7;S.wheel=3;S.roof=1'],
['a04_xp_profil','S.ver=3;S.color=7;S.wheel=3;S.roof=1;S.vview=0'],
['a05_lux_profil','S.ver=2;S.color=0;S.wheel=2;S.vview=0'],
['a06_lux_q34','S.ver=2;S.color=0;S.wheel=2'],
['a07_std16_profil','S.ver=0;S.color=3;S.wheel=0;S.vview=0'],
['a08_std16_q34','S.ver=0;S.color=3;S.wheel=0'],
['a09_ene_std','S.page="ene"'],
['a10_ene_xp','S.page="ene";S.ver=3;S.color=7;S.wheel=3;S.roof=1;S.plug=1;S.chg=1'],
['a11_cli_lhd','S.page="cli"'],
['a12_cli_rhd','S.page="cli";S.rhd=1;S.out=31'],
['a13_cfg_modele','S.cfg=1;S.tab=0'],
['a14_cfg_couleur','S.cfg=1;S.tab=1;S.ver=3;S.color=7;S.wheel=3;S.roof=1'],
['a15_cfg_livree','S.cfg=1;S.tab=2'],
];
const want=process.argv.slice(2); const ST=want.length?ALL.filter(s=>want.some(w=>s[0].startsWith(w))):ALL;
(async()=>{const b=await chromium.launch();let bad=0;
for(const [name,code] of ST){const p=await b.newPage({viewport:{width:1952,height:900}});
p.on('console',m=>{if(m.type()==='error'){bad++;console.log('ERR',name,m.text())}});p.on('pageerror',e=>{bad++;console.log('PAGEERR',name,e.message)});
await p.goto('http://127.0.0.1:8765/preview.html');await p.waitForTimeout(500);
await p.evaluate(code+';render()');
await p.waitForFunction(()=>[...document.querySelectorAll('#scr canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000}).catch(()=>{bad++;console.log('TIMEOUT',name)});
await p.waitForTimeout(900);
await (await p.$('#sw')).screenshot({path:__dirname+'/shots/'+name+'.png'});await p.close();console.log('shot',name)}
console.log(bad?'PROBLEMS '+bad:'no errors');await b.close()})();
