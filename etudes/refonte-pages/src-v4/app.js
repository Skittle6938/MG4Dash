/* ═══ The three pages (1920×720 car screen) ═══ */
const {PAINTS,LIVS}=PS, M_S=PS.META.side, M_Q=PS.META.q34||PS.META.side;
const S={page:'veh',cur:0,ver:0,color:4,roof:0,wheel:1,liv:0,rhd:0,cfg:0,tab:1,hero:0,side:0,mode:1,regen:3,pedal:0,steer:1,brake:0,esave:0,lane:2,aeb:2,oldfw:0,tyre:0,
 temp:5,fan:4,flow:[1,1,0],on:1,auto:0,ac:0,eco:0,recirc:1,defF:0,defR:0,linkDef:1,linkEco:0,nolink:0,out:9,plug:0,chg:0,v2lon:0,nat:0,limit:3,amps:3,sched:1,finish:0,v2l:2,period:0,heatB:0,soc:76,zoom:1,vview:1};
const PAGES=[['veh','Véhicule'],['ene','Énergie'],['cli','Climatisation']];
const VERS=[{n:'Standard',body:0,wh:1,roof:0,trim:1,sub:'Sans becquet<br>16″ ou 17″'},{n:'Comfort',body:0,wh:1,roof:0,trim:1,sub:'Sans becquet<br>17″'},{n:'Luxury',body:1,wh:2,roof:0,trim:1,sub:'Double aileron<br>17″ ou 18″'},{n:'XPower',body:1,wh:3,roof:1,trim:0,sub:'Double aileron<br>18″, toit noir'}];
const WHEELS=[{id:'steel16',n:'16″ tôle',sub:'Enjoliveur noir, rare',d:'enjoliveur noir des toutes premières Standard'},{id:'aero17',n:'17″ alliage',sub:'Enjoliveur bi-ton',d:'enjoliveur bi-ton'},{id:'lux18',n:'18″ alliage',sub:'Enjoliveur Luxury',d:'enjoliveur de la Luxury'},{id:'xp18',n:'18″ XPower',sub:'Étriers orange',d:'étriers orange'}];
const MODES=['Éco','Normal','Sport','Neige','Perso'],STEER=['Légère','Normale','Sport'],BRAKE=['Confort','Normale','Sport'],LANE=['Désactivé','Alerte','Assistance','Urgence'],AEB=['Désactivé','Alerte','Alerte + freinage'];
const AMPS=['6 A','8 A','16 A','Max'], LIMITS=[50,60,70,80,90,100], V2L=[20,30,40,50,60,70,80];
const NOTES={
 veh:{idea:'La vraie voiture au centre, la conduite à gauche, l\'état à droite.',li:['<b>La voiture est un rendu 3D</b> calculé à partir du modèle du configurateur MG, à la bonne version, la bonne couleur et les bonnes jantes. Les bas de caisse sont noir mat, sauf sur la XPower.','<b>Toucher la voiture</b> la fait passer du profil au trois-quarts. <b>Appui long</b> : le menu « Couleurs et style » s\'ouvre, avec trois onglets (modèle, couleur, livrée).','<b>Pressions des pneus</b> accrochées aux roues. Un pneu à vérifier passe en rouge.','<b>Voitures plus anciennes</b> : sans direction, pédale de frein ni économie d\'énergie, les cartes se réorganisent sans trou.']},
 ene:{idea:'La batterie se lit et se règle sur la voiture.',li:['<b>Vue transparente</b> calculée en 3D : la batterie est à sa vraie place, sous le plancher, et se remplit selon la charge.','<b>Réchauffage batterie</b> posé juste au-dessus de la batterie. Actif, la batterie s\'entoure d\'orange.','<b>Limite de charge</b> : elle n\'apparaît que sur les voitures qui l\'appliquent elles-mêmes. Les deux mises en page sont prévues, sans rien décaler.','<b>Câble branché</b> : la prise s\'allume à l\'arrière gauche et le courant circule jusqu\'à la batterie.']},
 cli:{idea:'On règle l\'air en touchant le vrai tableau de bord.',li:['<b>Image officielle MG</b> de l\'intérieur de la MG4, vue depuis les places arrière. L\'écran central montre l\'accueil de MG4Dash.','<b>Les flux d\'air partent des vraies bouches</b> : bandeau d\'aérateurs, bas du pare-brise, dessous de la planche. Orange quand ça chauffe, bleu quand ça refroidit, plus vifs quand la ventilation monte.','<b>Pare-brise, Visage, Pieds</b> : trois repères posés chacun à la hauteur de sa sortie. On peut aussi toucher la zone elle-même.','<b>Appui long sur l\'image</b> : conduite à gauche ou à droite. Les réglages du conducteur passent de son côté.']}};
const IC={gear:'M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z',
 dev:'M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z',
 call:'M6.62 10.79c1.44 2.83 3.76 5.14 6.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z',
 bolt:'M7 2v11h3v9l7-12h-4l4-8z',
 car:'M18.92 6.01C18.72 5.42 18.16 5 17.5 5h-11c-.66 0-1.21.42-1.42 1.01L3 12v8c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-1h12v1c0 .55.45 1 1 1h1c.55 0 1-.45 1-1v-8l-2.08-5.99zM6.5 16c-.83 0-1.5-.67-1.5-1.5S5.67 13 6.5 13s1.5.67 1.5 1.5S7.33 16 6.5 16zm11 0c-.83 0-1.5-.67-1.5-1.5s.67-1.5 1.5-1.5 1.5.67 1.5 1.5-.67 1.5-1.5 1.5zM5 11l1.5-4.5h11L19 11H5z',
 home:'M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z',
 power:'M13 3h-2v10h2V3zm4.83 2.17l-1.42 1.42C17.99 7.86 19 9.81 19 12c0 3.87-3.13 7-7 7s-7-3.13-7-7c0-2.19 1.01-4.14 2.58-5.42L6.17 5.17C4.23 6.82 3 9.26 3 12c0 4.97 4.03 9 9 9s9-4.03 9-9c0-2.74-1.23-5.18-3.17-6.83z',
 flame:'M13.5.67s.74 2.65.74 4.8c0 2.06-1.35 3.73-3.41 3.73-2.07 0-3.63-1.67-3.63-3.73l.03-.36C5.21 7.51 4 10.62 4 14c0 4.42 3.58 8 8 8s8-3.58 8-8C20 8.61 17.41 3.8 13.5.67zM11.71 19c-1.78 0-3.22-1.4-3.22-3.14 0-1.62 1.05-2.76 2.81-3.12 1.77-.36 3.6-1.21 4.62-2.58.39 1.29.59 2.65.59 4.04 0 2.65-2.15 4.8-4.8 4.8z'};
const ico=(n,s=34,f='#A0AEC0')=>`<svg viewBox="0 0 24 24" width="${s}" height="${s}" style="fill:${f};flex:none"><path d="${IC[n]}"/></svg>`;
const line=(d,s=24)=>`<svg viewBox="0 0 24 24" width="${s}" height="${s}" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">${d}</svg>`;
const AIR={scr:line('<path d="M3,8.5C8.5,5 15.5,5 21,8.5L18.5,19H5.5Z"/><path d="M9,16.5c-1.2,-1.6 1.2,-2.4 0,-4s1.2,-2.4 0,-4M12,16.5c-1.2,-1.6 1.2,-2.4 0,-4s1.2,-2.4 0,-4M15,16.5c-1.2,-1.6 1.2,-2.4 0,-4s1.2,-2.4 0,-4"/>'),
 face:line('<circle cx="16" cy="5.2" r="2.8"/><path d="M16,9.8v5.7h-5v6"/><path d="M2.5,5.2h6.5M6.4,2.6L9,5.2L6.4,7.8"/>'),
 feet:line('<circle cx="16" cy="5.2" r="2.8"/><path d="M16,9.8v5.7h-5v6"/><path d="M2,20.4h5.6M5,17.8L7.6,20.4L5,23"/>'),
 rear:line('<rect x="3" y="6" width="18" height="12" rx="3"/><path d="M8,15c-1,-1.3 1,-2 0,-3.3s1,-2 0,-3.3M12,15c-1,-1.3 1,-2 0,-3.3s1,-2 0,-3.3M16,15c-1,-1.3 1,-2 0,-3.3s1,-2 0,-3.3"/>',20)};
const touch=`<svg viewBox="0 0 24 24" width="18" height="18" style="flex:none"><circle cx="12" cy="12" r="4.2" fill="currentColor"/><circle cx="12" cy="12" r="9.4" fill="none" stroke="currentColor" stroke-width="1.8" opacity=".55"/></svg>`;

/* ── small builders ── */
const gauge=(n,v,a,c,st='')=>`<div class="g" style="--c:${c};${st}">${Array.from({length:n},(_,i)=>`<span class="${i<v?'on':''}" data-a="${a}:${i+1}"></span>`).join('')}</div>`;
const chips=(list,sel,a,cls='',gap=8,st='')=>`<div class="row" style="gap:${gap}px">${list.map((t,i)=>`<div class="chip nw ${cls} ${i===sel?'on':''}" data-a="${a}:${i}" style="${st}">${t}</div>`).join('')}</div>`;
const tg=(on,c='var(--energy)')=>`<i class="tg ${on?'on':''}" style="--c:${c}"></i>`;
const km=()=>Math.round(S.soc*3.737);
const tLabel=()=>S.temp===1?'LO':S.temp===17?'HI':(16+S.temp)+'°';
const tNum=()=>S.temp===1?-99:S.temp===17?99:16+S.temp;
const isHeat=()=>tNum()>S.out;
const modeTxt=()=>!S.on?'Clim arrêtée':isHeat()?'Chauffage':'Refroidissement';
const tCol=()=>isHeat()?'var(--heat)':'var(--cold)';
const fanTxt=()=>!S.on?'0':S.auto?'AUTO':S.fan;
function mix(a,b,t){const p=h=>[1,3,5].map(i=>parseInt(h.substr(i,2),16));const x=p(a),y=p(b);return '#'+x.map((v,i)=>Math.round(v+(y[i]-v)*t).toString(16).padStart(2,'0')).join('')}
function hdr(title,right){return `<div class="hd"><h4>${title}</h4>${right}</div>`}
const hdVeh=()=>hdr('Véhicule',`<div class="hb">Son ›</div><div class="hb">Réglages SAIC ›</div><div class="hb pri">Ouvrir MG4 Control ›</div>`);
const hdEne=()=>hdr('Énergie',`<div class="hb lnk">Appli Énergie d'origine ›</div>`);
const hdCli=()=>hdr('Climatisation',`<div class="sub" style="font-size:19px;margin-right:10px">Extérieur ${S.out}° · <span style="color:${S.on?tCol():'var(--off)'}">${modeTxt()}</span></div><div class="rb" data-a="tog:on" style="width:60px;height:60px">${ico('power',30,S.on?'#2979FF':'#A0AEC0')}</div>`);
const carCfg=(x={})=>({body:VERS[S.ver].body,paint:S.color,liv:S.liv,roof:S.roof,wheel:WHEELS[S.wheel].id,trim:VERS[S.ver].trim,...x});
const carName=()=>PAINTS[S.color].n+(PAINTS[S.color].note?' '+PAINTS[S.color].note:'')+(S.roof?' · toit noir':'')+(S.liv?' · livrée '+LIVS[S.liv].n:'');
let stageK=1;
const pickSc=(cssW,srcW)=>{const eff=cssW*stageK*(window.devicePixelRatio||1); for(const s of [.125,.25,.5])if(srcW*s>=eff*.96)return s; return 1};
/* the car: a canvas the paint shop fills; optional mirror image on the floor (profile view only) */
function carStage(x,y,w,{lp='',a='',slot='main',refl=1,view='side',o={}}={}){
  const M=PS.META[view],h=w*M.h/M.w,gy=M.an.front.ground[1]/M.h*h,sc=pickSc(w,M.w),c=carCfg(o);
  return (refl?`<canvas class="cv refl" data-car="${PS.str(view,Math.max(.125,sc/2),c)}" data-slot="${slot}r" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px;transform-origin:50% ${gy}px"></canvas>`:'')
    +`<canvas class="cv ${lp?'lp':''}" ${lp?`data-lp="${lp}"`:''} ${a?`data-a="${a}"`:''} data-car="${PS.str(view,sc,c)}" data-slot="${slot}" style="left:${x}px;top:${y}px;width:${w}px;height:${h}px"></canvas>`}
const swatch=(p,sz=44)=>`<i class="sw ${p.f===2?'mat':''}" style="width:${sz}px;height:${sz}px;background:${p.sw?p.sw:`radial-gradient(circle at 32% 26%,rgba(255,255,255,${p.f===2?.16:.6}) 0,rgba(255,255,255,0) 30%),radial-gradient(circle at 50% 45%,${p.hexc},${mix(p.hexc,'#000000',.55)})`}"></i>`;

/* ── charts ── */
function ring(sz,th,parts){const r=(sz-th)/2,c=2*Math.PI*r;let o=0,s=`<svg width="${sz}" height="${sz}" viewBox="0 0 ${sz} ${sz}" style="flex:none"><g transform="rotate(-90 ${sz/2} ${sz/2})">`;
  parts.forEach(([p,k])=>{s+=`<circle cx="${sz/2}" cy="${sz/2}" r="${r}" fill="none" stroke="${k}" stroke-width="${th}" stroke-dasharray="${c*p/100-3} ${c}" stroke-dashoffset="${-c*o/100}"/>`;o+=p});
  return s+`</g><text x="50%" y="54%" text-anchor="middle" fill="#fff" font-size="${sz*.19}" font-family="Roboto,sans-serif">${parts[0][0]} %</text></svg>`}
function curve(w,h){const v=[17,17.4,17.8,17.2,17.6,16.9,16.4,16.8,17.3,17.9,18.4,18.2,18.6,18.1,17.2,16.3,15.4,14.6,14,13.4,13.2,13.6],mx=20,X=i=>(i/(v.length-1)*w).toFixed(1),Y=n=>(h-n/mx*h).toFixed(1);
  const ln=v.map((n,i)=>`${i?'L':'M'}${X(i)},${Y(n)}`).join('');
  return `<svg viewBox="0 0 ${w} ${h}" width="${w}" height="${h}" style="display:block;overflow:visible"><defs><linearGradient id="areaG" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="#00E676" stop-opacity=".32"/><stop offset="1" stop-color="#00E676" stop-opacity="0"/></linearGradient></defs><path d="M0,${Y(10)}H${w}M0,${h}H${w}M0,1H${w}" stroke="#2A3142"/><path d="${ln}L${w},${h}L0,${h}Z" fill="url(#areaG)"/><path d="${ln}" fill="none" stroke="#00E676" stroke-width="3"/><path d="M0,${Y(15.2)}H${w}" stroke="#00D2FF" stroke-dasharray="7 7"/>
  <text x="4" y="14" fill="#8A94A6" font-size="14" font-family="Roboto,sans-serif">20</text><text x="4" y="${+Y(10)-5}" fill="#8A94A6" font-size="14" font-family="Roboto,sans-serif">10</text>
  <rect x="${w-62}" y="${+Y(13.6)-34}" width="62" height="28" rx="14" fill="#2979FF"/><text x="${w-31}" y="${+Y(13.6)-14}" fill="#fff" font-size="18" font-family="Roboto,sans-serif" text-anchor="middle">13,6</text></svg>`}
const chgStatus=()=>S.v2lon?'Décharge V2L · 1,2 kW':!S.plug?'Câble débranché':S.chg?'Charge en cours · fin dans 2 h 10':'Branché · en attente';
const chgAction=()=>!S.plug?'CÂBLE DÉBRANCHÉ':S.chg?'ARRÊTER LA CHARGE':'CHARGER MAINTENANT';
const regenTxt=()=>S.pedal?'Une pédale':['','Faible','Moyen','Fort','Adaptatif'][S.regen];
const stepper=(l,v)=>`<div class="row" style="justify-content:space-between;margin-top:10px"><span class="sub" style="font-size:19px">${l}</span><div class="stp"><div class="rb">−</div><b>${v}</b><div class="rb">+</div></div></div>`;

/* ═════ VÉHICULE ═════ */
function P_veh(){
 const perso=S.mode===4, q=S.vview&&PS.META.q34?1:0, M=q?M_Q:M_S, A=M.an, W=956, cw=q?1196:952, k=cw/M.w, cx=q?(W-1606*k)/2:(W-cw)/2, top=q?42-78*k:58, ch=cw*M.h/M.w;
 const fx=cx+A.front.c[0]*k, rx=Math.min(W-104,cx+A.rear.c[0]*k), wy=top+A.front.c[1]*k, gF=top+A.front.ground[1]*k, gR=top+A.rear.ground[1]*k, wrx=A.front.rx*k, wry=A.front.ry*k;
 const st=(l,v,o={})=>`<div class="st ${o.cls||''} ${o.dis?'dis':''}" ${o.a?`data-a="${o.a}"`:''}>${o.dot!==undefined?`<i class="dot ${o.dot?'on':''}"></i>`:''}<div class="sl">${l}</div><div class="sv">${v}</div>${o.sub?`<div class="ss">${o.sub}</div>`:''}</div>`;
 const bar=(l,v,p)=>`<div class="row" style="gap:12px;margin-top:9px"><span class="sub" style="font-size:16px;width:92px">${l}</span><div class="pb"><i style="width:${p}%;background:var(--action)"></i></div><span style="font-size:16px;width:96px;text-align:right">${v}</span></div>`;
 const tv=(p,t,bad)=>`<div><b style="${bad?'color:var(--danger)':''}">${p}<small> bar</small></b><span style="${bad?'color:var(--danger)':''}">${t}</span></div>`;
 const tyre=(x,y,t,a,b)=>`<div class="co" style="left:${x}px;top:${y+40}px"><span class="lb" style="font-size:12px">${t}</span><div class="row" style="gap:16px;margin-top:5px">${a}<i class="vd"></i>${b}</div></div>`;
 return hdVeh()+`<div class="bd" style="grid-template-columns:412px 1fr 412px">
 <div class="col" style="padding:0 0 10px 12px;gap:10px">
  <div class="card">
   <div class="lb">Mode de conduite</div>
   <div class="seg" style="margin:12px 0 ${S.oldfw?2:10}px">${MODES.map((m,i)=>`<div class="${i===S.mode?'on':''}" data-a="mode:${i}">${m}</div>`).join('')}</div>
   ${S.oldfw?'':`<div class="row" style="gap:10px;align-items:stretch">
    ${st('Direction',STEER[S.steer],{cls:'in',dis:!perso,a:perso?'cyc:steer:3':'',sub:perso?'Toucher pour changer':'Réglable en mode Perso'})}
    ${st('Pédale de frein',BRAKE[S.brake],{cls:'in',dis:!perso,a:perso?'cyc:brake:3':'',sub:perso?'Toucher pour changer':'Réglable en mode Perso'})}
   </div>`}
  </div>
  <div class="card">
   <div class="row" style="justify-content:space-between"><div class="lb">Récupération d'énergie</div><div style="font-size:19px;color:var(--energy)">${regenTxt()}</div></div>
   <div style="margin:12px 0 12px">${gauge(4,S.pedal?4:S.regen,'regen','var(--energy)','--h:22px')}</div>
   <div class="row" style="justify-content:space-between;height:38px" data-a="tog:pedal"><span class="sub" style="font-size:18px">Conduite à une pédale</span>${tg(S.pedal)}</div>
   ${S.oldfw?'':`<div class="row" style="justify-content:space-between;height:38px" data-a="tog:esave"><span class="sub" style="font-size:18px">Économie d'énergie</span>${tg(S.esave)}</div>`}
  </div>
  <div class="lb" style="margin:6px 0 -2px 4px">Aides à la conduite</div>
  <div style="display:grid;grid-template-columns:1fr 1fr;grid-auto-rows:1fr;gap:10px;flex:1;min-height:0">
   ${st('Maintien de voie',LANE[S.lane],{a:'cyc:lane:4',dot:S.lane>0})}
   ${st('Freinage d\'urgence',AEB[S.aeb],{a:'cyc:aeb:3',dot:S.aeb>0})}
   ${st('Alerte de survitesse','Activée',{dot:1})}
   ${st('Bip limite de vitesse','Désactivé',{dot:0})}
  </div>
 </div>
 <div style="position:relative;overflow:hidden">
  <div style="position:absolute;left:0;right:0;top:4px;text-align:${q?'left':'center'};padding-left:${q?8:0}px"><div class="lb">MG4 ${VERS[S.ver].n}</div><div class="sub" style="font-size:20px;margin-top:5px">${carName()}</div></div>
  <div class="floor" style="top:${q?gR-20:gF-62}px;${q?'height:170px':''}"></div>
  ${carStage(cx,top,cw,{lp:'cfg',a:'vview',slot:q?'mainq':'main',refl:!q,view:q?'q34':'side'})}
  ${S.tyre?`<div class="pulse" style="position:absolute;left:${fx-wrx-9}px;top:${wy-wry-9}px;width:${2*wrx+18}px;height:${2*wry+18}px;border-radius:50%;border:3px solid var(--danger);box-shadow:0 0 26px var(--danger),inset 0 0 22px rgba(255,82,82,.4);pointer-events:none"></div>`:''}
  ${tyre(fx,gF,'Pneus avant',tv(S.tyre?'1,92':'2,52',S.tyre?'gauche · À vérifier':'gauche · 21 °C',S.tyre),tv('2,48','droit · 22 °C'))}${tyre(rx,gR,'Pneus arrière',tv('2,72','gauche · 21 °C'),tv('2,76','droit · 22 °C'))}
  <div class="hint" data-a="cfg" style="position:absolute;left:50%;bottom:14px;transform:translateX(-50%)">${touch}Toucher la voiture : autre vue<span style="color:var(--line)">|</span>Appui long : couleurs et style</div>
 </div>
 <div class="col" style="padding:0 12px 10px 0;gap:10px">
  <div class="card">
   <div class="lb">Autonomie</div>
   <div class="row" style="align-items:baseline;margin:6px 0 10px"><div class="big" style="font-size:56px">${km()}<span style="font-size:24px"> km</span></div><div style="font-size:30px;font-weight:300;color:var(--energy);margin-left:auto">${S.soc} %</div></div>
   ${gauge(20,Math.round(S.soc/5),'x','var(--energy)','pointer-events:none')}
   <div class="row" style="justify-content:space-between;margin-top:10px;font-size:17px"><span class="sub">Conso moyenne</span><span>13,6 kWh/100 km</span></div>
  </div>
  <div class="card">
   <div class="row" style="justify-content:space-between"><div class="lb">Aujourd'hui</div><div style="color:var(--action);font-size:17px">Trajets ›</div></div>
   <div class="row" style="align-items:baseline;gap:12px;margin-top:6px"><div class="big" style="font-size:40px">85,7<span style="font-size:21px"> km</span></div><div style="margin-left:auto;text-align:right"><div style="font-size:18px">4 trajets</div><div class="sub" style="font-size:15px;margin-top:3px">dernier 13:12 – 13:58</div></div></div>
   <div class="tl"><i style="left:32.1%;width:2.3%"></i><i style="left:36.8%;width:1%"></i><i style="left:51.4%;width:1.5%"></i><i style="left:55%;width:3.2%"></i></div>
   <div class="row dim" style="justify-content:space-between;font-size:13px;margin-top:5px"><span>0 h</span><span>6 h</span><span>12 h</span><span>18 h</span><span>24 h</span></div>
  </div>
  <div style="display:grid;grid-template-columns:1fr 1fr;gap:10px">${st('Odomètre','18 364 km',{cls:'cp'})}${st('Vitesse','0 km/h',{cls:'cp'})}${st('Extérieur',S.out+' °C',{cls:'cp'})}${st('Charge',chgStatus().split(' · ')[0],{cls:'cp',dot:S.chg})}</div>
  <div class="card" style="flex:1;min-height:0;padding-top:14px;padding-bottom:12px">
   <div class="row" style="justify-content:space-between"><div class="lb">Autoradio</div><div style="color:var(--action);font-size:17px">Détail par appli ›</div></div>
   ${bar('Processeur','23 %',23)}${bar('Mémoire','2,1 / 3,7 Go',57)}
  </div>
 </div></div>`}

/* ── « Couleurs et style » (long press on the car) ── */
function cfgOv(){
 const p=PAINTS[S.color], w=WHEELS[S.wheel], sec=(t,body,mt=0)=>`<div style="margin-top:${mt}px"><div class="lb" style="margin-bottom:9px">${t}</div>${body}</div>`;
 const tabs=['Modèle','Couleur','Livrée'];
 let body='';
 if(S.tab===0)body=sec('Version',`<div class="g4">${VERS.map((v,i)=>`<div class="opt ${i===S.ver?'on':''}" data-a="ver:${i}" style="padding:12px 14px"><div style="font-size:20px;color:#fff;font-weight:500">${v.n}</div><small style="font-size:14px;line-height:1.3;margin-top:5px">${v.sub}</small></div>`).join('')}</div>`)
  +sec('Jantes',`<div class="g4">${WHEELS.map((x,i)=>`<div class="opt ${i===S.wheel?'on':''}" data-a="wheel:${i}" style="text-align:center;padding:12px 8px 11px"><canvas data-wheel="${x.id}" width="184" height="184" style="width:92px;height:92px;display:block;margin:0 auto 8px;pointer-events:none"></canvas><div style="font-size:17px;color:#fff">${x.n}</div><small style="font-size:13px">${x.sub}</small></div>`).join('')}</div>`,18)
  +sec('Toit',`<div class="row" style="gap:10px"><div class="chip nw ${!S.roof?'on':''}" data-a="roof:0" style="flex:none;width:230px">Couleur carrosserie</div><div class="chip nw ${S.roof?'on':''}" data-a="roof:1" style="flex:none;width:170px">Toit noir</div><div class="dim" style="font-size:14px;line-height:1.35;margin-left:8px">De série sur la XPower et sur<br>les premières Luxury (2022)</div></div>`,18);
 else if(S.tab===1){const tile=(q,i)=>`<div class="opt row ${i===S.color?'on':''}" data-a="color:${i}" style="gap:11px;padding:8px 10px;height:62px">${swatch(q)}<div style="font-size:15px;line-height:1.2;color:${i===S.color?'#fff':'var(--sub)'}">${q.n}${q.note?`<small style="font-size:12px">${q.note} · XPower</small>`:''}</div></div>`;
  body=sec('Couleurs MG4',`<div class="g4">${PAINTS.map((q,i)=>q.fun?'':tile(q,i)).join('')}</div>`)+sec('Coloris fun',`<div class="g4">${PAINTS.map((q,i)=>q.fun?tile(q,i):'').join('')}</div>`,18)}
 else body=sec('Livrée · sur '+p.n,`<div class="g4" style="gap:9px">${LIVS.map((l,i)=>`<div class="opt ${i===S.liv?'on':''}" data-a="liv:${i}" style="text-align:center;padding:9px 4px 7px"><canvas class="cvt" data-car="${PS.str('side',pickSc(166,M_S.w),carCfg({liv:i}))}" style="width:166px;height:${166*M_S.h/M_S.w}px"></canvas><div style="margin-top:3px;font-size:15px">${l.n}</div></div>`).join('')}</div>`);
 const hero=S.hero?carStage(18,206,972,{slot:'heroS'}):carStage(-84,104,1176,{slot:'heroQ',view:'q34',refl:0});
 return `<div class="ov">
  <div style="position:relative">
   <div style="position:absolute;left:38px;top:26px"><div class="lb">Ma voiture</div><div class="big" style="font-size:52px;margin-top:10px">MG4 ${VERS[S.ver].n}</div><div class="sub" style="font-size:21px;margin-top:10px">${carName()}</div><div class="dim" style="font-size:17px;margin-top:6px">Jantes ${w.n} · ${w.d}</div></div>
   <div class="floor" style="top:${S.hero?500:478}px;left:3%;right:3%;height:${S.hero?150:190}px"></div>
   ${hero}
   <div class="seg" style="position:absolute;left:50%;bottom:20px;transform:translateX(-50%);width:300px"><div class="${!S.hero?'on':''}" data-a="hero:0" style="height:42px">Trois-quarts</div><div class="${S.hero?'on':''}" data-a="hero:1" style="height:42px">Profil</div></div>
  </div>
  <div class="col" style="padding:18px 22px 16px 24px;border-left:1px solid rgba(255,255,255,.06);background:rgba(11,14,21,.72)">
   <div class="row" style="justify-content:space-between"><div style="font-size:25px;font-weight:500">Couleurs et style</div><div class="hb pri" data-a="cfg" style="padding:10px 24px">Terminé</div></div>
   <div class="seg" style="margin:14px 0 16px">${tabs.map((t,i)=>`<div class="${i===S.tab?'on':''}" data-a="tab:${i}">${t}</div>`).join('')}</div>
   ${body}
   <div style="flex:1"></div>
   <div class="row" style="gap:12px"><div class="chip nw" data-a="lucky" style="flex:none;width:190px;height:50px;font-size:16px">Au hasard</div><div class="chip nw" data-a="stock" style="flex:none;width:190px;height:50px;font-size:16px">D'origine</div><div class="dim" style="font-size:14px;line-height:1.35;flex:1;text-align:right">${p.fun?'Coloris imaginaire, pour le plaisir.':p.f===4?'Peinture satinée, réservée à la XPower chez MG.':p.f===2?'Peinture mate.':p.f===1?'Peinture métallisée du catalogue MG4.':'Peinture opaque du catalogue MG4.'}</div></div>
  </div></div>`}

/* ═════ ÉNERGIE ═════ */
function eneArt(){ // drawn over the transparent render, in its own pixels (1840×680)
 const A=M_S.an,b=A.battery,x0=b[0][0],x1=b[1][0],y0=b[0][1],y1=b[2][1],w=x1-x0,h=y1-y0,soc=S.soc/100,lim=S.nat&&LIMITS[S.limit]<100?LIMITS[S.limit]/100:0,n=20,col=S.v2lon?'#FF9100':'#00E676',heat=S.heatB,P=A.port?[(A.port[0][0]+A.port[1][0])/2,(A.port[0][1]+A.port[1][1])/2]:[1580,285];
 let s=`<svg viewBox="0 0 1840 680" style="position:absolute;inset:0;width:100%;height:100%;overflow:visible;pointer-events:none"><defs><filter id="gl" x="-20%" y="-150%" width="140%" height="400%"><feGaussianBlur stdDeviation="9"/></filter></defs>`;
 if(heat)s+=`<rect x="${x0-10}" y="${y0-10}" width="${w+20}" height="${h+20}" rx="16" fill="#FF9100" opacity=".55" filter="url(#gl)" class="pulse"/>`;
 s+=`<rect x="${x0-7}" y="${y0-7}" width="${w+14}" height="${h+14}" rx="12" fill="rgba(5,8,13,.86)" stroke="${heat?'#FF9100':'rgba(150,195,255,.6)'}" stroke-width="${heat?4:2.5}"/>`;
 s+=`<rect x="${x0}" y="${y0}" width="${w*soc}" height="${h}" rx="5" fill="${col}" opacity=".38" filter="url(#gl)"/>`;
 for(let i=0;i<n;i++){const cx=x0+i*w/n+2.5,cw=w/n-5,f=Math.max(0,Math.min(1,(soc-i/n)*n)),over=lim&&i/n>=lim-1e-6; s+=`<rect x="${cx}" y="${y0}" width="${cw}" height="${h}" rx="4" fill="${over?'#141A24':'#1D2636'}"/>`;
   if(f>0)s+=`<rect x="${cx}" y="${y0}" width="${cw*f}" height="${h}" rx="4" fill="${col}"/>`; else if(S.chg&&(!lim||!over)&&i===Math.floor(soc*n+1e-6))s+=`<rect class="pulse" x="${cx}" y="${y0}" width="${cw}" height="${h}" rx="4" fill="${col}"/>`}
 if(lim){const lx=x0+w*lim; s+=`<path d="M${lx},${y0-22}V${y1+12}" stroke="#fff" stroke-width="4" stroke-linecap="round"/><text x="${lx}" y="${y1+52}" text-anchor="middle" fill="#fff" font-size="29" font-family="Roboto,sans-serif">limite ${LIMITS[S.limit]} %</text>`}
 if(heat)for(let i=0;i<7;i++){const hx=x0+w*(i+.5)/7; s+=`<path class="pulse" style="animation-delay:${-i*.23}s" d="M${hx},${y0-16}c-13,-13 13,-22 0,-36s13,-22 0,-36" fill="none" stroke="#FF9100" stroke-width="4.5" stroke-linecap="round"/>`}
 // charge port (rear left wing), cable, and the path down to the pack
 const live=S.chg||S.v2lon, pc=S.v2lon?'#FF9100':S.chg?'#00E676':'#8FB8FF';
 s+=`<circle cx="${P[0]}" cy="${P[1]}" r="17" fill="rgba(5,8,13,.8)" stroke="${S.plug?pc:'rgba(150,195,255,.6)'}" stroke-width="3.5"/><path d="M${P[0]-3},${P[1]-10}l-6,11h6l-3,9l10,-13h-6l4,-7z" fill="${S.plug?pc:'rgba(150,195,255,.75)'}"/>`;
 if(S.plug){const cab=`M${P[0]+17},${P[1]}c70,4 96,60 110,150s40,150 130,196`, inn=`M${P[0]},${P[1]+17}V${y0-60}Q${P[0]},${(y0+y1)/2} ${P[0]-90},${(y0+y1)/2}H${x1+9}`;
   s+=`<path d="${cab}" fill="none" stroke="#0A0E15" stroke-width="15" stroke-linecap="round"/><path d="${cab}" fill="none" stroke="${pc}" stroke-width="5" stroke-linecap="round" opacity="${live?.55:.8}"/><path d="${inn}" fill="none" stroke="${pc}" stroke-width="4" opacity=".4"/>`;
   if(live)s+=`<path class="fl ${S.v2lon?'':'rev'}" d="${cab}" fill="none" stroke="${pc}" stroke-width="7" stroke-linecap="round" stroke-dasharray="20 48"/><path class="fl ${S.v2lon?'rev':''}" d="${inn}" fill="none" stroke="${pc}" stroke-width="6" stroke-linecap="round" stroke-dasharray="20 48"/>`}
 return s+'</svg>'}
function v2lArt(){const on=S.v2lon,c=on?'#FF9100':'#4A5366',w=Math.round(66*S.soc/100);
 return `<svg viewBox="0 0 330 76" width="330" height="76" style="display:block"><rect x="6" y="18" width="78" height="40" rx="9" fill="none" stroke="${c}" stroke-width="3"/><rect x="85" y="30" width="7" height="16" rx="3" fill="${c}"/><rect x="12" y="24" width="${w}" height="28" rx="5" fill="${on?'#FF9100':'#2F3A4F'}"/>
 <path d="M106,38H236" stroke="#232B3B" stroke-width="5" stroke-linecap="round"/>${on?'<path class="fl" d="M106,38H236" stroke="#FF9100" stroke-width="5" stroke-linecap="round" stroke-dasharray="20 48"/>':''}
 <circle cx="282" cy="38" r="33" fill="${on?'rgba(255,145,0,.12)':'none'}" stroke="${c}" stroke-width="3"/><circle cx="268" cy="38" r="5.5" fill="${c}"/><circle cx="296" cy="38" r="5.5" fill="${c}"/><path d="M282,10v8M282,58v8" stroke="${c}" stroke-width="4" stroke-linecap="round"/></svg>`}
function P_ene(){
 const A=M_S.an,W=910,cw=880,cx=(W-cw)/2,k=cw/M_S.w,top=92,ch=cw*M_S.h/M_S.w,gy=top+A.front.ground[1]*k,bx=cx+(A.battery[0][0]+A.battery[1][0])/2*k,by=top+A.battery[0][1]*k,py=top+392*k;
 const heatE=S.heatB, parts=heatE?[[72,'#00E676'],[14,'#00D2FF'],[8,'#B388FF'],[6,'#FF9100']]:[[77,'#00E676'],[16,'#00D2FF'],[7,'#FF9100']];
 const leg=(heatE?[['Conduite','11,0 kWh · 72 %','#00E676'],['Climatisation','2,1 kWh · 14 %','#00D2FF'],['Réchauffage batterie','1,2 kWh · 8 %','#B388FF'],['Accessoires','0,9 kWh · 6 %','#FF9100']]:[['Conduite','11,0 kWh · 77 %','#00E676'],['Climatisation','2,3 kWh · 16 %','#00D2FF'],['Accessoires','1,0 kWh · 7 %','#FF9100']]).map(([l,v,c])=>`<div class="lg"><i style="background:${c}"></i><span>${l}</span>${v}</div>`).join('');
 const lim=LIMITS[S.limit], pow=S.v2lon?'Sortie 230 V · 5,2 A':S.chg?'Puissance 6,8 kW · entrée AC 230 V · 29,6 A':S.plug?'Prête à charger':'';
 const act=S.v2lon?`<div class="chip heat on" style="height:60px;font-size:18px;letter-spacing:.04em;pointer-events:none">La voiture alimente la prise extérieure · arrêt automatique à ${V2L[S.v2l]} %</div>`
  :`<div class="row" style="gap:10px"><div class="chip ${!S.plug?'dis':S.chg?'heat on':'on'}" style="height:60px;font-size:19px;letter-spacing:.08em" data-a="charge">${chgAction()}</div>${S.plug?`<div class="chip nw" style="flex:none;height:60px;padding:0 18px;font-size:16px">DÉVERROUILLER LE PISTOLET</div>`:''}</div>`;
 const left=Math.max(0,S.soc-V2L[S.v2l]), kwh=left*.51, info=(v,l)=>`<div class="st cp" style="flex:1;height:54px;padding:6px 14px;border-radius:14px"><div class="sl" style="font-size:13px">${l}</div><div class="sv" style="margin-top:2px;font-size:18px">${v}</div></div>`;
 const cur=S.v2lon?`<div><div class="lb" style="font-size:13px;margin-bottom:6px">Avant l'arrêt automatique</div><div class="row" style="gap:8px">${info(left+' %','Batterie utilisable')}${info('≈ '+kwh.toFixed(0)+' kWh','Énergie disponible')}${info(left>0?'≈ '+Math.round(kwh/1.2)+' h':'0 h','À cette puissance')}</div></div>`:`<div><div class="lb" style="font-size:13px;margin-bottom:6px">Courant de charge AC</div>${chips(AMPS,S.amps,'amps','sm')}</div>`;
 const limB=`<div><div class="row" style="justify-content:space-between;margin-bottom:6px"><span class="lb" style="font-size:13px">Limite de charge</span><span class="dim" style="font-size:13px">${lim>=100?'Pas de limite':'La voiture s\'arrête à '+lim+' %'}</span></div>${chips(LIMITS.map(l=>l+' %'),S.limit,'limit','sm',6)}</div>`;
 return hdEne()+`<div class="bd" style="grid-template-columns:400px 1fr 470px">
 <div class="col" style="padding:0 0 10px 12px;gap:10px">
  <div class="card"><div class="lb" style="margin-bottom:10px">Charge programmée</div>
   <div class="row" style="gap:10px"><div class="chip ${S.sched?'on':''}" data-a="tog:sched" style="font-size:16px;letter-spacing:.03em">PROGRAMMATION</div><div class="chip sm ${S.finish?'on':''} ${S.sched?'':'dis'}" data-a="tog:finish">FINIR JUSQU'À LA LIMITE</div></div>
   ${stepper('Début','22:30')}${stepper('Fin','06:00')}</div>
  <div class="card col" style="flex:1;min-height:0"><div class="lb">V2L · prise extérieure</div>
   <div class="sub" style="font-size:17px;margin:8px 0 12px;min-height:42px;color:${S.v2lon?'var(--heat)':'var(--sub)'}">${S.v2lon?'Décharge en cours · 1,2 kW<br>arrêt automatique à '+V2L[S.v2l]+' %':'Branchez un adaptateur V2L sur la prise de charge'}</div>
   <div class="dim" style="font-size:16px;margin-bottom:6px">Arrêt de la décharge à</div>${chips(V2L.map(String),S.v2l,'v2l','sm',6)}
   <div style="flex:1;min-height:0;display:flex;flex-direction:column;justify-content:center;align-items:center;gap:8px">${v2lArt()}<div style="font-size:${S.v2lon?26:17}px;font-weight:${S.v2lon?300:400};color:${S.v2lon?'#fff':'var(--off)'}">${S.v2lon?'1,2 kW<span style="font-size:16px;color:var(--sub)"> · 230 V · 5,2 A</span>':'Sortie coupée'}</div></div>
   <div class="chip ${S.v2lon?'heat on':'dis'}" data-a="v2ldemo" style="flex:none;height:56px;letter-spacing:.08em">${S.v2lon?'ARRÊTER LE V2L':'LANCER LE V2L'}</div></div>
 </div>
 <div style="position:relative">
  <div class="row" style="position:absolute;left:0;right:0;top:-8px;height:96px;justify-content:center;align-items:center;gap:24px"><div class="big" style="font-size:92px">${S.soc} %</div><div><div style="font-size:30px">${km()} km</div><div style="font-size:19px;color:${S.v2lon?'var(--heat)':S.chg?'var(--energy)':'var(--sub)'}">${chgStatus()}</div>${pow?`<div class="dim" style="font-size:15px;margin-top:2px">${pow}</div>`:''}</div></div>
  <div class="floor" style="top:${gy-58}px"></div>
  <div class="lp" data-lp="cfg" style="position:absolute;left:${cx}px;top:${top}px;width:${cw}px;height:${ch}px"><img class="ghost" alt="" src="img/s${VERS[S.ver].body}_ghost.webp">${eneArt()}</div>
  <div style="position:absolute;left:${bx-1}px;top:${py+26}px;width:2px;height:${by-py-34}px;background:${S.heatB?'var(--heat)':'#4A5670'}"></div>
  <div class="bh ${S.heatB?'on':''}" data-a="tog:heatB" style="left:${bx}px;top:${py}px">${ico('flame',22,S.heatB?'#FF9100':'#A0AEC0')}<span>Réchauffage batterie</span>${tg(S.heatB,'var(--heat)')}</div>
  <div class="hint" style="position:absolute;left:${bx}px;top:${gy+(S.nat&&lim<100?40:14)}px;transform:translateX(-50%)">Batterie 96 % · santé estimée<span style="color:var(--line)">|</span><span style="color:var(--action)">Réglages de la batterie ›</span></div>
  <div style="position:absolute;left:70px;right:70px;bottom:10px;display:grid;grid-template-columns:${S.nat&&!S.v2lon?'1.25fr 1fr':'1fr'};gap:12px 18px;align-items:end">
   <div style="grid-column:1/-1">${act}</div>${S.nat&&!S.v2lon?limB:''}${cur}
  </div>
 </div>
 <div class="col" style="padding:0 12px 10px 0">
  <div class="card col" style="flex:1;min-height:0"><div class="lb" style="margin-bottom:10px">Consommation</div>
   ${chips(['Depuis la recharge','Trajet en cours','12 derniers mois'],S.period,'period','sm',6,'font-size:14px;padding:0 2px')}
   <div class="row" style="margin-top:16px;gap:18px;align-items:flex-end"><div class="big" style="font-size:42px">18,4 kWh</div><div><div class="lb" style="font-size:13px">Récup</div><div style="font-size:22px;color:var(--energy)">−3,2 kWh &nbsp;≈ 19,5 km</div></div></div>
   <div class="row" style="margin-top:14px;gap:18px;min-height:128px">${ring(120,18,parts)}<div style="flex:1">${leg}</div></div>
   <div class="lb" style="margin:16px 0 8px;font-size:13px">Conso récente (kWh/100 km)</div>
   <div style="flex:1;min-height:0;display:flex;flex-direction:column;justify-content:flex-end">${curve(420,248)}
   <div class="row dim" style="font-size:14px;justify-content:space-between;margin-top:6px"><span>il y a 50 km</span><span>Un point par km · moyenne (pointillés)</span></div></div></div>
 </div></div>`}

/* ═════ CLIMATISATION ═════ */
function P_cli(){
 const heat=isHeat(),kc=heat?'var(--heat)':'var(--cold)',r=S.rhd,on=S.on, X=x=>r?1808-x:x;
 const air=['Air recyclé','Air extérieur','Air auto'][S.recirc], t=(l,o,a,c='')=>`<div class="chip ${c} ${o?'on':''}" data-a="${a}" style="height:auto">${l}</div>`;
 const tiles=[t('AUTO<span class="auto"><u style="height:6px"></u><u style="height:11px"></u><u style="height:16px;background:#4A5366"></u></span>',S.auto,'tog:auto'),t('A/C',S.ac,'tog:ac'),t('ECO',S.eco,'tog:eco'),t(air,S.recirc!==1,'recirc'),t('Dégivrage AV',S.defF,'tog:defF','heat'),t('Dégivrage AR',S.defR,'tog:defR','heat')]
  .concat(S.nolink?[]:[t('Dégivrage arrière avec l\'avant',S.linkDef,'tog:linkDef','sm'),t('Suivre le mode ECO',S.linkEco,'tog:linkEco','sm')]);
 const scrOn=on&&(S.flow[2]||S.defF);
 const tag=(i,l,x,y,ic,act)=>`<div class="hs ${act?'on':''}" style="--k:${kc};left:${X(x)}px;top:${y}px" data-a="flow:${i}"><i>${ic}</i>${l}</div>`;
 const left=`<div class="glass" style="position:absolute;${r?'right':'left'}:16px;top:50%;transform:translateY(-50%);width:324px;padding:18px 20px 20px">
   <div class="lb">Température conducteur</div>
   <div class="row" style="margin:12px 0 14px;gap:0"><div class="rb c" data-a="temp:-1" style="width:74px;height:74px;font-size:44px">−</div><div class="big" style="flex:1;text-align:center;font-size:82px;color:${on?'#fff':'var(--off)'}">${tLabel()}</div><div class="rb h" data-a="temp:1" style="width:74px;height:74px;font-size:44px">+</div></div>
   <div class="g" style="--h:24px">${Array.from({length:17},(_,i)=>`<span data-a="tset:${i+1}" style="background:${i<S.temp?mix('#00D2FF','#FF9100',i/16):'var(--gauge)'}"></span>`).join('')}</div>
   <div class="row dim" style="justify-content:space-between;font-size:14px;margin-top:6px"><span>LO</span><span>20°</span><span>24°</span><span>28°</span><span>HI</span></div>
   <div class="hr" style="margin:16px 0 14px"></div>
   <div class="lb">Ventilation</div>
   <div class="row" style="margin:10px 0 12px;gap:0"><div class="rb" data-a="fan:-1" style="width:64px;height:64px;font-size:38px">−</div><div class="big" style="flex:1;text-align:center;font-size:${S.auto&&on?46:58}px;color:${!on?'var(--off)':S.auto?'var(--cold)':'#fff'}">${fanTxt()}</div><div class="rb" data-a="fan:1" style="width:64px;height:64px;font-size:38px">+</div></div>
   ${gauge(11,on&&!S.auto?S.fan:0,'fset','var(--cold)','--h:22px')}
   <div class="dim" style="font-size:14px;margin-top:8px;min-height:17px">${S.auto&&on?'Débit d\'air en AUTO : moyen':''}</div>
  </div>`;
 const right=`<div class="glass col" style="position:absolute;${r?'left':'right'}:16px;top:50%;transform:translateY(-50%);width:344px;height:414px;padding:18px 18px 12px">
   <div class="lb" style="margin-bottom:10px">Modes</div>
   <div style="display:grid;grid-template-columns:1fr 1fr;grid-auto-rows:1fr;gap:9px;flex:1;min-height:0">${tiles.join('')}</div>
   <div style="text-align:center;color:var(--action);font-size:17px;padding:11px 0 2px">Écran clim d'origine ›</div>
  </div>`;
 return hdCli()+`<div class="bd" style="display:block">
  <div class="cab lp" data-lp="side">
   <img class="cabimg" alt="" src="img/cabin_${r?'r':'l'}.webp" style="filter:${on?'none':'saturate(.55) brightness(.72)'}">
   <div class="cabsh"></div><div class="cabtint" style="background:${on?kc:'transparent'}"></div>
   <canvas id="air" width="1808" height="644"></canvas>
   <div data-a="flow:2" style="position:absolute;left:340px;right:340px;top:0;height:300px"></div>
   <div data-a="flow:0" style="position:absolute;left:340px;right:340px;top:392px;height:92px"></div>
   <div data-a="flow:1" style="position:absolute;left:340px;right:340px;top:500px;height:144px"></div>
   ${S.defR&&on?`<div class="hs on" style="--k:var(--heat);left:${X(894)}px;top:84px;height:40px;padding:0 14px 0 6px;font-size:15px;pointer-events:none"><i style="width:28px;height:28px">${AIR.rear}</i>Lunette arrière</div>`:''}
   ${tag(2,'Pare-brise',1196,300,AIR.scr,scrOn)}${tag(0,'Visage',1196,446,AIR.face,on&&S.flow[0])}${tag(1,'Pieds',1196,566,AIR.feet,on&&S.flow[1])}
   ${left}${right}
   <div class="hint" data-a="side" style="position:absolute;left:50%;bottom:12px;transform:translateX(-50%);z-index:2">${touch}Appui long : conduite à gauche ou à droite</div>
   ${S.side?`<div class="scrim" data-a="side"></div><div class="pop"><div class="lb">Poste de conduite</div><div class="row" style="gap:10px;margin-top:14px"><div class="chip nw ${!r?'on':''}" data-a="rhd:0" style="flex:none;width:230px">Conduite à gauche</div><div class="chip nw ${r?'on':''}" data-a="rhd:1" style="flex:none;width:230px">Conduite à droite</div></div></div>`:''}
  </div></div>`}

/* ── shell ── */
const SCR={veh:P_veh,ene:P_ene,cli:P_cli};
function dock(){const act={veh:'car',ene:'bolt'}[S.page];return `<div class="dock">${['gear','dev','call','bolt','car','home'].map(n=>`<i class="${n===act?'on':''}">${ico(n)}</i>`).join('')}</div>`}
function render(){
  const scr=document.getElementById('scr');
  scr.innerHTML=S.cur?`<img class="cur" alt="Écran actuel" src="img/${{veh:'vehicle',ene:'energy',cli:'climate'}[S.page]}.png">`:dock()+`<div class="pg">${SCR[S.page]()}${S.cfg?cfgOv():''}</div>`;
  PS.mount(scr);
  const air=document.getElementById('air');
  if(air)Air.start(air,{heat:isHeat(),rhd:S.rhd,power:S.on?(S.auto?.55:.2+.8*S.fan/11):0,defrost:S.defF,flow:{face:S.flow[0],feet:S.flow[1],screen:S.flow[2]||S.defF}});else Air.stop();
  document.getElementById('pages').innerHTML=PAGES.map(([k,n])=>`<button aria-pressed="${S.page===k}" data-a="page:${k}">${n}</button>`).join('');
  document.getElementById('cmp').innerHTML=`<button aria-pressed="${!S.cur}" data-a="cur:0">Nouvelle maquette</button><button aria-pressed="${!!S.cur}" data-a="cur:1">Écran actuel</button>`;
  const b=(l,on,a)=>`<button aria-pressed="${!!on}" data-a="${a}">${l}</button>`;
  document.getElementById('zoomg').innerHTML=b('Centrée',S.zoom===1,'zoom:1')+b('Agrandie',S.zoom===2,'zoom:2')+b('Entière',!S.zoom,'zoom:0');
  document.getElementById('demo').innerHTML=S.cur?'<span>Capture de l\'écran d\'aujourd\'hui, pour comparer (documentation, en anglais).</span>':'<span>Essayer :</span>'+({
    veh:b('Ouvrir « Couleurs et style »',S.cfg,'cfg')+b('Vue de profil',!S.vview,'vview')+b('Mode Perso',S.mode===4,'mode:'+(S.mode===4?1:4))+b('Pneu à vérifier',S.tyre,'tog:tyre')+b('Voiture plus ancienne',S.oldfw,'tog:oldfw')+b('Surprise',0,'lucky'),
    ene:b('Réchauffage batterie',S.heatB,'tog:heatB')+b('Câble branché',S.plug,'plug')+b('Charge en cours',S.chg,'chgdemo')+b('V2L en cours',S.v2lon,'v2ldemo')+b('Voiture avec limite de charge',S.nat,'tog:nat'),
    cli:b('Conduite à droite',S.rhd,'rhd:'+(S.rhd?0:1))+b('Dehors 9°',S.out===9,'out:9')+b('Dehors 31°',S.out===31,'out:31')+b('Dégivrage avant',S.defF,'tog:defF')+b('Voiture sans réglages liés',S.nolink,'tog:nolink')}[S.page]);
  const n=NOTES[S.page];
  document.getElementById('note').innerHTML=`<div class="idea"><h3>${PAGES.find(p=>p[0]===S.page)[1]}</h3><p>${n.idea}</p></div><div><h3>Ce qui change</h3><ul>${n.li.map(x=>`<li>${x}</li>`).join('')}</ul></div>`;
}
function act(a){const [k,v,w]=a.split(':'),n=+v;
  if(k==='zoom'){S.zoom=n;fit();render();return}
  if(k==='pick'){S.color=n;S.liv=+w;const ver=a.split(':')[3];if(ver!==undefined&&ver!==''){S.ver=+ver;S.wheel=VERS[+ver].wh;S.roof=VERS[+ver].roof}S.page='veh';S.cur=0;S.cfg=0;S.side=0;render();document.getElementById('sw').scrollIntoView({behavior:matchMedia('(prefers-reduced-motion:reduce)').matches?'auto':'smooth',block:'center'});return}
  if(k==='page'){S.page=v;S.cfg=0;S.side=0}else if(k==='cur')S.cur=n;else if(k==='cfg'){S.cfg=S.cfg?0:1;if(S.cfg)S.page=S.page==='cli'?'veh':S.page}else if(k==='side')S.side=S.side?0:1;
  else if(k==='rhd'){S.rhd=n;S.side=0}else if(k==='tab')S.tab=n;else if(k==='hero')S.hero=n;else if(k==='roof'){S.roof=n;S.hero=0}else if(k==='vview')S.vview=S.vview?0:1;
  else if(k==='ver'){S.ver=n;S.wheel=VERS[n].wh;S.roof=VERS[n].roof}
  else if(k==='color')S.color=n;else if(k==='wheel')S.wheel=n;else if(k==='liv')S.liv=n;else if(k==='mode')S.mode=n;else if(k==='cyc')S[v]=(S[v]+1)%+w;
  else if(k==='stock'){S.color=4;S.liv=0;S.ver=0;S.wheel=VERS[0].wh;S.roof=0}
  else if(k==='lucky'){S.color=8+Math.floor(Math.random()*(PAINTS.length-8));S.liv=Math.random()<.5?0:1+Math.floor(Math.random()*(LIVS.length-1));if(Math.random()<.5){S.color=Math.floor(Math.random()*8);S.liv=1+Math.floor(Math.random()*(LIVS.length-1))}}
  else if(k==='temp')S.temp=Math.max(1,Math.min(17,S.temp+n));else if(k==='tset')S.temp=n;
  else if(k==='fan'){if(S.on){if(n<0&&!S.auto&&S.fan<=1)S.on=0;else{S.auto=0;S.fan=Math.max(1,Math.min(11,S.fan+n))}}}
  else if(k==='fset'){S.on=1;S.auto=0;S.fan=n}
  else if(k==='flow'){const f=S.flow.slice();f[n]=f[n]?0:1;if(f.some(Boolean))S.flow=f}
  else if(k==='tog')S[v]=S[v]?0:1;else if(k==='recirc')S.recirc=(S.recirc+1)%3;
  else if(k==='regen'){S.regen=n;S.pedal=0}else if(k==='limit')S.limit=n;else if(k==='amps')S.amps=n;else if(k==='v2l')S.v2l=n;else if(k==='period')S.period=n;
  else if(k==='out')S.out=n;else if(k==='plug'){S.plug=S.plug?0:1;if(!S.plug){S.chg=0;S.v2lon=0}}else if(k==='chgdemo'){S.chg=S.chg?0:1;if(S.chg){S.plug=1;S.v2lon=0}}
  else if(k==='v2ldemo'){S.v2lon=S.v2lon?0:1;if(S.v2lon){S.plug=1;S.chg=0}}
  else if(k==='charge'){if(S.plug&&!S.v2lon)S.chg=S.chg?0:1}
  render()}
/* long press: hold ~0.5 s on [data-lp]; the click that follows is swallowed */
let lpT=0,lpFired=false,lpXY=null;
const lpStop=()=>{clearTimeout(lpT);lpXY=null;document.querySelectorAll('.pressing').forEach(n=>n.classList.remove('pressing'))};
document.addEventListener('pointerdown',e=>{lpFired=false;lpStop();const t=e.target.closest('[data-lp]');if(!t||e.target.closest('.pop,.scrim,.glass,.hs'))return;lpXY=[e.clientX,e.clientY];t.classList.add('pressing');lpT=setTimeout(()=>{lpFired=true;lpStop();act(t.dataset.lp)},480)});
document.addEventListener('pointermove',e=>{if(lpXY&&Math.hypot(e.clientX-lpXY[0],e.clientY-lpXY[1])>12)lpStop()});
['pointerup','pointercancel','pointerleave'].forEach(n=>document.addEventListener(n,lpStop));
document.addEventListener('contextmenu',e=>{if(e.target.closest('[data-lp]'))e.preventDefault()});
document.addEventListener('click',e=>{if(lpFired){lpFired=false;return}const t=e.target.closest('[data-a]');if(t)act(t.dataset.a)});
let fitT=0;
/* Stage size. Wide screens: the whole 1920×720 screen fits the page. Phones: three levels, the default one fits the
   centre of the screen (the car) to the phone's width and the rest is reached by sliding sideways. */
function fit(first){const sw=document.getElementById('sw'),z=window.innerWidth<=760?S.zoom:0,was=+(sw.dataset.z||0); sw.classList.toggle('pan',z>0);
  let k; if(z===1){k=sw.clientWidth/990; sw.style.height=Math.round(720*k)+'px'}else if(z===2){const h=Math.round(Math.min(window.innerHeight*.58,430)); sw.style.height=h+'px'; k=h/720}else{sw.style.height=''; k=sw.clientWidth/1920}
  document.getElementById('scr').style.transform=`scale(${k})`;
  if(z!==was||first===true){sw.dataset.z=z; sw.scrollLeft=z?Math.max(0,1002*k-sw.clientWidth/2):0}
  if(first===true){stageK=k;render()}else if(Math.abs(k-stageK)>.02){stageK=k;clearTimeout(fitT);fitT=setTimeout(render,120)}}
document.getElementById('swl').innerHTML=PAINTS.filter(p=>!p.fun).map(p=>`<span>${swatch(p,22)}${p.n}${p.note?' ('+p.note+')':''}</span>`).join('');
fit(true);
new ResizeObserver(()=>fit()).observe(document.getElementById('sw'));
window.addEventListener('resize',()=>fit());

/* ── gallery under the mock-up: every paint in three-quarter view, every livery in profile; painted when it scrolls into view ── */
const LIVBASE=(m=>LIVS.map(l=>Math.max(0,PAINTS.findIndex(p=>p.id===(m[l.id]||'blue')))))({rally:'white',flames:'black',bolt:'grey',check:'white',pixels:'silver',tricolore:'white',camo:'green',leopard:'orange',zebra:'white',ladybug:'red',cow:'white'});
(function gallery(){
  const vq=PS.META.q34?'q34':'side', dpr=Math.min(3,window.devicePixelRatio||1), gw=document.getElementById('galA').clientWidth||1200, narrow=gw<600;
  const tile=(view,o,label,sub,a,cols)=>{const M=PS.META[view],q=view==='q34',cw=((gw+10)/cols-24)*(q?1.194:1),sc=cw*dpr*.9<=M.w*.25?.25:.5;
    const cv=`<canvas width="${Math.round(M.w*sc)}" height="${Math.round(M.h*sc)}" data-car="${PS.str(view,sc,o)}"></canvas>`; return `<button data-a="${a}">${q?`<div class="q">${cv}</div>`:cv}${label}${sub?`<small>${sub}</small>`:''}</button>`};
  const xp=i=>i===7, cfgP=i=>({body:xp(i)||PAINTS[i].fun?1:0,paint:i,liv:0,roof:xp(i)?1:0,wheel:xp(i)?'xp18':PAINTS[i].fun?'lux18':'aero17',trim:xp(i)?0:1});
  const sub=p=>['Opaque','Métallisée','Mate','Miroir','Satinée'][p.f], c4=narrow?2:4, c5=narrow?2:gw<960?3:5;
  document.getElementById('galA').innerHTML=PAINTS.map((p,i)=>p.fun?'':tile(vq,cfgP(i),p.n,xp(i)?'Satinée · XPower, toit noir':sub(p),`pick:${i}:0:${xp(i)?3:0}`,c4)).join('');
  document.getElementById('galB').innerHTML=PAINTS.map((p,i)=>p.fun?tile(vq,cfgP(i),p.n,'',`pick:${i}:0:2`,c4):'').join('');
  document.getElementById('galC').innerHTML=LIVS.map((l,i)=>i?tile('side',{body:0,paint:LIVBASE[i],liv:i,roof:0,wheel:'aero17',trim:1},l.n,'sur '+PAINTS[LIVBASE[i]].n,`pick:${LIVBASE[i]}:${i}:0`,c4):'').join('');
  const io=new IntersectionObserver(es=>es.forEach(e=>{if(e.isIntersecting){io.unobserve(e.target);PS.mount(e.target)}}),{rootMargin:'300px'});
  ['galA','galB','galC'].forEach(id=>io.observe(document.getElementById(id)));
})();
setTimeout(()=>{if(PS.META.q34)PS.get('q34',pickSc(1000,M_Q.w),{...carCfg(),paint:PAINTS[S.color],liv:LIVS[S.liv]}).catch(()=>{})},1800);
