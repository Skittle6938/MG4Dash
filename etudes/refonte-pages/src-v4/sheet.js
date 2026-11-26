// usage: node sheet.js <out.png> <view> <scale> <cols> '<json list of [label, {body,paint,liv,roof,wheel}]>' [bg]
const fs=require('fs'),{chromium}=require('playwright');
const [out,view,sc,cols,list,bg]=process.argv.slice(2);
const js=fs.readFileSync(__dirname+'/paint.js','utf8').replace('/*__META__*/null',()=>fs.readFileSync(__dirname+'/car.json','utf8'));
const html=`<!doctype html><meta charset="utf-8"><body style="margin:0;background:${bg||'#0A0D13'};color:#9aa;font:13px sans-serif"><div id="g" style="display:grid;grid-template-columns:repeat(${cols},1fr);gap:6px;padding:6px"></div><script>${js}
const L=${list};const g=document.getElementById('g');
g.innerHTML=L.map(([n,o])=>'<div><canvas data-car="'+PS.str('${view}',${sc},o)+'" style="width:100%;display:block"></canvas><div style="text-align:center">'+n+'</div></div>').join('');
const t0=performance.now();PS.mount(g);
const iv=setInterval(()=>{if([...g.querySelectorAll('canvas')].every(c=>c.dataset.ok)){clearInterval(iv);document.title='done '+Math.round(performance.now()-t0)}},100);
</script>`;
fs.writeFileSync(__dirname+'/../mq/_sheet.html',html);
(async()=>{const b=await chromium.launch();const p=await b.newPage({viewport:{width:+(process.env.VW||1600),height:900}});
p.on('console',m=>{if(m.type()==='error')console.log('ERR',m.text())});p.on('pageerror',e=>console.log('PAGEERR',e.message));
await p.goto('http://127.0.0.1:8765/_sheet.html');await p.waitForFunction('document.title.startsWith("done")',null,{timeout:120000});
console.log(await p.title());await p.screenshot({path:out,fullPage:true});await b.close()})();
