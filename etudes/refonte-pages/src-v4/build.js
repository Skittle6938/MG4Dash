// Assemble the v3 mock-up page: template + paint engine (with car.json inlined) + air flows + pages.
const fs=require('fs');const d=__dirname, rd=f=>fs.readFileSync(d+'/'+f,'utf8');
const paint=rd('paint.js').replace('/*__META__*/null',()=>rd('car.json').trim());
let t=rd('page.tpl.html').replace('/*__PAINT__*/',()=>paint).replace('/*__AIR__*/',()=>rd('air.js')).replace('/*__APP__*/',()=>rd('app.js'));
if(/<\/script>/i.test(paint+rd('air.js')+rd('app.js')))throw new Error('script end tag inside the code');
fs.writeFileSync(d+'/../mq/maquettes.html',t);
fs.writeFileSync(d+'/../mq/preview.html','<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover"><style>:root{color-scheme:light}body{margin:0;font:14px system-ui}img{max-width:100%}[hidden]{display:none!important}</style></head><body>'+t+'</body></html>');
console.log('built',t.length,'bytes');
