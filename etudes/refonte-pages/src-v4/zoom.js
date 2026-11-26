// zoomed shots of the car area at 2x for a close look at wheels and sills
const {chromium}=require('playwright');
const ALL=[
['z_lux_profil','S.ver=2;S.color=0;S.wheel=2;S.vview=0'],
['z_std_profil','S.ver=0;S.color=5;S.wheel=1;S.vview=0'],
['z_std16_profil','S.ver=0;S.color=3;S.wheel=0;S.vview=0'],
['z_xp_profil','S.ver=3;S.color=7;S.wheel=3;S.roof=1;S.vview=0'],
];
const want=process.argv.slice(2); const ST=want.length?ALL.filter(s=>want.some(w=>s[0].startsWith(w))):ALL;
(async()=>{const b=await chromium.launch();
for(const [name,code] of ST){const p=await b.newPage({viewport:{width:1952,height:900},deviceScaleFactor:2});
await p.goto('http://127.0.0.1:8765/preview.html');await p.waitForTimeout(500);
await p.evaluate(code+';render()');
await p.waitForFunction(()=>[...document.querySelectorAll('#scr canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000}).catch(()=>console.log('TIMEOUT',name));
await p.waitForTimeout(900);
const bb=await (await p.$('#sw')).boundingBox();
await p.screenshot({path:__dirname+'/shots/'+name+'.png',clip:{x:bb.x+bb.width*0.27,y:bb.y+bb.height*0.33,width:bb.width*0.50,height:bb.height*0.47}});await p.close();console.log('shot',name)}
await b.close()})();
