// loads the page exactly as published (downloaded index.html + the same images) and checks it
const {chromium}=require('playwright');
(async()=>{const b=await chromium.launch();let bad=0;
for(const [w,h,mob,name] of [[1600,900,false,'pub_desk'],[390,800,true,'pub_phone']]){
const p=await b.newPage({viewport:{width:w,height:h},deviceScaleFactor:mob?2:1,hasTouch:mob,isMobile:mob});
p.on('console',m=>{if(m.type()==='error'){bad++;console.log('ERR',m.text())}});p.on('pageerror',e=>{bad++;console.log('PAGEERR',e.message)});
p.on('requestfailed',r=>{bad++;console.log('REQFAIL',r.url())});p.on('response',r=>{if(r.status()>=400){bad++;console.log('HTTP',r.status(),r.url())}});
await p.goto('http://127.0.0.1:8766/index.html');await p.waitForTimeout(2500);
const ok=await p.waitForFunction(()=>[...document.querySelectorAll('#scr canvas[data-car]')].every(c=>c.dataset.ok===c.dataset.car),null,{timeout:60000}).then(()=>1).catch(()=>0);
console.log(name,'cars painted',ok, await p.evaluate(()=>JSON.stringify({title:document.title,doc:document.documentElement.scrollWidth+'/'+innerWidth,view:S.vview,hint:(document.querySelector('#scr .hint')||{}).textContent,pan:document.querySelector('.panhint').textContent.slice(0,60)})));
await p.evaluate(()=>document.getElementById('sw').scrollIntoView({block:'center'}));await p.waitForTimeout(400);
await p.screenshot({path:__dirname+'/shots/'+name+'.png'});
for(const pg of ['ene','cli']){await p.evaluate(`S.page="${pg}";render()`);await p.waitForTimeout(1200);await p.screenshot({path:__dirname+'/shots/'+name+'_'+pg+'.png'})}
await p.close()}
console.log(bad?'PROBLEMS '+bad:'PUBLISHED PAGE OK');await b.close()})();
