const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch(), p=await b.newPage({viewport:{width:390,height:800},deviceScaleFactor:2,hasTouch:true,isMobile:true});
p.on('console',m=>{if(m.type()==='error')console.log('ERR',m.text())});p.on('pageerror',e=>console.log('PAGEERR',e.message));
await p.goto('http://127.0.0.1:8765/preview.html'); await p.waitForTimeout(2200);
await p.screenshot({path:__dirname+'/shots/m_first.png'});
console.log(await p.evaluate(()=>{const sw=document.getElementById('sw');return JSON.stringify({cls:sw.className,sl:sw.scrollLeft,sw:sw.scrollWidth,cw:sw.clientWidth,ch:sw.clientHeight,doc:document.documentElement.scrollWidth+'/'+innerWidth,car:(document.querySelector('#scr canvas[data-lp=cfg]')||{}).width})}));
// long press with a finger (touch): hold 650 ms on the car
const r=await p.$eval('#scr canvas[data-lp=cfg]',n=>{const r=n.getBoundingClientRect();return [Math.min(innerWidth-40,Math.max(40,r.left+r.width*.5)),r.top+r.height*.5]});
const cdp=await p.context().newCDPSession(p);
await cdp.send('Input.dispatchTouchEvent',{type:'touchStart',touchPoints:[{x:r[0],y:r[1]}]}); await p.waitForTimeout(650); await cdp.send('Input.dispatchTouchEvent',{type:'touchEnd',touchPoints:[]}); await p.waitForTimeout(700);
console.log('touch long press → menu', await p.evaluate('S.cfg'));
await p.evaluate(()=>document.getElementById('sw').scrollIntoView({block:'start'})); await p.waitForTimeout(600);
await p.screenshot({path:__dirname+'/shots/m_cfg.png'});
await b.close()})();
