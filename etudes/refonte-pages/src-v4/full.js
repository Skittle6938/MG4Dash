// usage: node full.js <viewport width> <out.png> [dpr] — scrolls through the page so that the gallery paints, then captures the whole page
const {chromium}=require('playwright');
(async()=>{const vw=+process.argv[2], b=await chromium.launch(), p=await b.newPage({viewport:{width:vw,height:vw<700?780:900},deviceScaleFactor:+(process.argv[4]||1),hasTouch:vw<700,isMobile:vw<700});
p.on('console',m=>{if(m.type()==='error')console.log('ERR',m.text())});p.on('pageerror',e=>console.log('PAGEERR',e.message));
p.on('response',r=>{if(r.status()>=400)console.log('HTTP',r.status(),r.url())});
const t0=Date.now(); await p.goto('http://127.0.0.1:8765/preview.html');
await p.waitForFunction(()=>[...document.querySelectorAll('#scr canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000}); console.log('stage painted after',Date.now()-t0,'ms');
const H=await p.evaluate(()=>document.documentElement.scrollHeight);
for(let y=0;y<H;y+=600){await p.evaluate(y=>window.scrollTo(0,y),y);await p.waitForTimeout(250)}
const t1=Date.now(); await p.waitForFunction(()=>[...document.querySelectorAll('.gal canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:120000}).catch(()=>console.log('TIMEOUT gallery'));
console.log('gallery painted after',Date.now()-t1,'ms more;',await p.evaluate(()=>document.querySelectorAll('.gal canvas').length),'tiles');
await p.evaluate(()=>window.scrollTo(0,0)); await p.waitForTimeout(300);
console.log('scrollWidth/innerWidth',await p.evaluate(()=>document.documentElement.scrollWidth+'/'+innerWidth), 'pan', await p.evaluate(()=>document.getElementById('sw').className));
await p.screenshot({path:process.argv[3],fullPage:true}); await b.close()})();
