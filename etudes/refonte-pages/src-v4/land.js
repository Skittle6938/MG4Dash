const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch();
for(const [w,h,name] of [[844,390,'land'],[390,800,'port']]){
const p=await b.newPage({viewport:{width:w,height:h},deviceScaleFactor:2,hasTouch:true,isMobile:true});
p.on('console',m=>{if(m.type()==='error')console.log('ERR',m.text())});p.on('pageerror',e=>console.log('PAGEERR',e.message));
await p.goto('http://127.0.0.1:8765/preview.html'); await p.waitForTimeout(2200);
console.log(name, await p.evaluate(()=>{const sw=document.getElementById('sw');const h=document.querySelector('.panhint');return JSON.stringify({cls:sw.className,sl:sw.scrollLeft,sw:sw.scrollWidth,cw:sw.clientWidth,ch:sw.clientHeight,doc:document.documentElement.scrollWidth+'/'+innerWidth,hint:getComputedStyle(h).display,zoomg:getComputedStyle(document.getElementById('zoomg')).display})}));
await p.evaluate(()=>document.getElementById('sw').scrollIntoView({block:'start'})); await p.waitForTimeout(500);
await p.screenshot({path:__dirname+'/shots/m_'+name+'.png'});
await p.evaluate('S.cfg=1;render()'); await p.waitForTimeout(1500);
await p.screenshot({path:__dirname+'/shots/m_'+name+'_cfg.png'});
await p.close()}
await b.close()})();
