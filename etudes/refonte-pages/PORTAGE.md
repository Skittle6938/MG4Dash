# Porter la refonte Véhicule / Énergie / Climatisation dans MG4Dash

Note écrite le 26/11/2026 par le fil de l'étude (« Repenser les pages énergie, véhicule et clim ») pour le fil « MG4Dash », qui code et installe.
Tout ce qui est cité ici se trouve dans `/mnt/project-files/etudes/refonte-pages/`.
Je n'ai touché ni au dépôt ni à la voiture. Pour lire le code de l'appli j'ai utilisé un clone au commit `3ec9332` (« Release 1.7.4-beta22 ») : vérifie sur ta version, la tienne fait foi.

## 1. La demande de Marc

- 26/11, 10 h 30 : « Ok excellent, retire juste les settings alerte survitesse et bip limite qui n'existe pas vraiment et qu'on ne sait pas piloter. Tu peux coder tout ça et installer sur ma voiture stp! »
- 26/11, 10 h 31 : « juste un détail sur la vue climatisation fais le fond du combiné d'instrumentation derrière le volant un peu plus noir stp, garde les éléments dessus tels quels »

Ces deux retouches sont faites dans la maquette et dans les images (publiées à 10 h 42). Le reste de la maquette v4 est validé (« Ok excellent »).

La référence visuelle est la maquette interactive : https://claude.ai/artifact/1hiPtW5iTdqjYmHhUBxRkY
La même page en local : `maquettes.html` avec `img/` à côté, à servir en HTTP (`python3 -m http.server` dans le dossier ; en `file://` les canvas sont bloqués).

## 2. Où est quoi

| Chemin | Contenu |
|---|---|
| `maquettes.html` | La maquette publiée (v4 + les deux retouches). `maquettes_v1…v3.html` : anciennes versions. |
| `img/` | Les 70 images de la maquette. 66 servent à l'appli (section 7). |
| `src-v4/app.js` | Les trois pages et le menu « Couleurs et style » : disposition, textes, comportements. |
| `src-v4/paint.js` | Le moteur de peinture (190 lignes) : c'est lui qu'il faut porter en Kotlin. |
| `src-v4/air.js` | Les flux d'air animés de la page Climatisation. |
| `src-v4/car.json` | Les repères géométriques des images (roues, batterie, prise, toit…). Il est inséré dans `paint.js` à la construction (`META`). |
| `src-v4/page.tpl.html` | Le CSS (couleurs, tailles, rayons) et le texte autour de la maquette. |
| `src-v4/README.md` | Comment reconstruire la page, refaire les images, mesurer une jante. |
| `src-v4/cabin.py`, `glass.py`, `mirror_whole.py` | Fabriquent `cabin_l.webp`, `cabin_r.webp`, leurs versions de jour `cabin_l_day.webp`, `cabin_r_day.webp` et les pastilles de peinture à partir de la vue intérieure MG (section 13). |
| `ref/` | 30 voitures calculées par le moteur de la maquette, en PNG pleine taille, pour contrôler le portage (section 9), et les deux décalcomanies `decal_num.png`, `decal_bolt.png`. |
| `rendu3d/`, `sources/` | Scripts Blender, modèle 3D, photos de Marc. Inutile pour coder, utile seulement pour refaire une image. |

## 3. Ce que Marc a décidé

- Direction A « Showroom » : la voiture au centre. Le menu de gauche (dock) ne change pas.
- Page Véhicule : vue trois-quarts par défaut, toucher la voiture passe au profil et inversement. Appui long sur la voiture : menu « Couleurs et style » (version, jantes, toit, couleur, livrée).
- Santé batterie retirée de la page Véhicule (elle reste accessible depuis la page Énergie).
- « Alerte de survitesse » et « Bip limite de vitesse » retirés (26/11). Dans l'appli actuelle ce sont deux lignes d'information de `VehicleActivity` (`vh_overspeed`, `vh_limit_beep`, lignes 87 et 235-236).
- Page Énergie : vue transparente de la voiture avec la batterie à sa place, réchauffage batterie posé près de la batterie.
- Page Climatisation : le vrai tableau de bord, flux d'air animés, conduite à gauche ou à droite par appui long. L'écran central montre l'accueil de MG4Dash. Fond du combiné d'instrumentation plus noir (26/11).
- Les pages doivent tenir selon les options de la voiture (limite de charge ou non, voitures plus anciennes, réglages liés absents).
- Toit noir en option, coloris fun et livrées « comme chez Tesla ».
- Retirés à sa demande : coloris or rose, néon, lave, holographique ; livrées Mondrian, bandes course, topographie, vagues. Ne pas les remettre.
- Droits : « Tu as le droit d'utiliser les images venant de MG dans l'appli. » (26/11). Le modèle 3D et la vue intérieure viennent du configurateur MG.
- Poids : environ 6 Mo d'images en plus (l'APK fait 8,6 Mo). Je l'ai dit deux fois à Marc, il n'a pas objecté et a donné le feu vert ensuite. À rappeler dans ton message de résultat.

## 4. Ce qui est vrai et ce qui est du décor dans la maquette

- Toutes les valeurs affichées sont inventées (76 %, 18 364 km, 13,6 kWh…). Les vraies données et les vraies commandes sont celles des pages actuelles : `pages/VehicleActivity.kt`, `pages/EnergyActivity.kt`, `pages/ClimateActivity.kt`.
- La maquette ne crée aucune fonction de la voiture. Elle réorganise les commandes des pages actuelles. Les libellés français sont repris des `strings.xml` : garde les chaînes et les fonctions de libellé de l'appli (`VehicleState.lasLabel`, `aebLabel`, `steeringLabel`…), pas les listes écrites en dur dans `app.js`.
- Les boutons « Essayer » sous la maquette simulent des états. Leur équivalent réel :

| Bouton de la maquette (`S.…`) | Dans l'appli |
|---|---|
| Voiture plus ancienne (`oldfw`) | Direction, pédale de frein et économie d'énergie absentes : `setOpt` de `VehicleActivity` les masque quand la voiture ne répond pas (#37). |
| Pneu à vérifier (`tyre`) | État du capteur de pression différent de 0 (`refreshTires`). |
| Voiture avec limite de charge (`nat`) | `Firmware.nativeChargeLimit`. |
| Voiture sans réglages liés (`nolink`) | `defrostLinked == null && ecoLinked == null` dans `ClimateActivity`. |
| Câble branché, charge, V2L (`plug`, `chg`, `v2lon`) | État de charge réel. |
| Dehors 9° / 31° (`out`) | Température extérieure : orange si la consigne est au-dessus, bleu sinon. |

- Le bouton « Écran actuel », la galerie de peintures et les notes sous la maquette ne font pas partie de l'appli.

## 5. Les trois pages

La maquette est dessinée en pixels pour l'écran 1920 × 720. La zone de page fait 1808 × 706 et commence à x = 98, y = 6 (à droite du dock). Les couleurs sont celles de `colors.xml` (`accent_cold`, `accent_energy`, `accent_heat`, `accent_action`, `accent_danger`, `text_sub`, `text_title`). Les grands chiffres sont en Roboto Light. Les positions exactes sont dans `app.js` (`P_veh`, `cfgOv`, `P_ene`, `eneArt`, `P_cli`).

### Véhicule (`P_veh`)

Trois colonnes : 412 px, le reste (956 px), 412 px. En-tête inchangé (Son, Réglages SAIC, Ouvrir MG4 Control).

Colonne de gauche :
- « Mode de conduite » : cinq choix (Éco, Normal, Sport, Neige, Perso). Dessous, « Direction » et « Pédale de frein », réglables seulement en mode Perso (grisées sinon, texte « Réglable en mode Perso »).
- « Récupération d'énergie » : jauge à quatre crans, « Conduite à une pédale », « Économie d'énergie ».
- « Aides à la conduite » : deux tuiles l'une sous l'autre, « Maintien de voie » et « Freinage d'urgence », avec un point allumé quand l'aide est active.

Aujourd'hui `VehicleActivity` est en lecture seule (sauf la jauge de récupération). Ces réglages se changent déjà dans la carte d'accueil « Conduite » (`ui/DrivingCard.kt`, #38) avec `CarBridge.setDriveMode`, `setRegenLevel`, `setOnePedal`, `setSteering`, `setBrakePedal`, `setLaneMode`, `setFcw`, grisés quand la voiture ne répond pas. Le plus sûr est de reprendre cette logique.

**Attention, « Économie d'énergie »** : la maquette montre un interrupteur, mais je n'ai trouvé qu'une lecture (`enduranceMode`), aucune commande. C'est le même cas que les deux réglages que Marc a fait retirer (« qu'on ne sait pas piloter »). Sans commande, affiche-le comme une simple valeur, ou demande à Marc.

Centre : la voiture.
- En haut, « MG4 <version> » et le nom de la peinture.
- Vue trois-quarts : image large de 1196 px (source 1600 px), sans reflet. Vue de profil : 952 px (source 1840 px), avec un reflet au sol (la même image retournée, 27 % d'opacité, fondue vers le bas).
- Pressions des pneus accrochées sous chaque roue (`an.front.ground`, `an.rear.ground` de `car.json`), gauche et droite côte à côte, avec la température. Pneu à vérifier : texte rouge et anneau rouge qui pulse autour de la roue de l'essieu concerné (`an.front.c`, `rx`, `ry`). Les deux vues montrent le flanc gauche de la voiture.
- En bas, le rappel « Toucher la voiture : autre vue | Appui long : couleurs et style ». Toucher ce rappel ouvre aussi le menu.
- Appui long : 480 ms, annulé si le doigt bouge de plus de 12 px. La voiture s'éclaircit pendant l'appui.

Colonne de droite : Autonomie (km, %, jauge 20 crans, conso moyenne), Aujourd'hui (km, trajets, frise 24 h, lien Trajets), quatre petites tuiles (Odomètre, Vitesse, Extérieur, Charge), Autoradio (processeur, mémoire, « Détail par appli »).

### Menu « Couleurs et style » (`cfgOv`)

Il recouvre la page : à gauche la voiture en grand (trois-quarts ou profil, au choix), à droite un panneau de 800 px avec trois onglets et le bouton « Terminé ».
- Modèle : version (Standard, Comfort, Luxury, XPower), jantes (quatre vignettes découpées dans l'image des roues de profil), toit (couleur carrosserie ou noir). Choisir une version remet ses jantes et son toit de série.
- Couleur : 8 couleurs MG4, 8 coloris fun.
- Livrée : « Aucune » et 11 livrées, chacune avec une vignette de la voiture dans la couleur choisie.
- En bas : « Au hasard », « D'origine » et une ligne sur la finition.

Il s'ouvre aussi par appui long sur la voiture de la page Énergie.
À mémoriser dans `Prefs` : version, jantes, toit, couleur, livrée, vue préférée, côté de conduite.
Point ouvert : « D'origine » revient au réglage de départ de la maquette (Standard, Brighton Blue, 17″, sans livrée). L'appli ne connaît pas la vraie couleur de la voiture. Je propose : retirer la livrée et le coloris fun, revenir à la dernière couleur MG choisie.

### Énergie (`P_ene`, `eneArt`)

Trois colonnes : 400 px, le reste (910 px), 470 px.
- Gauche : « Charge programmée » (programmation, finir jusqu'à la limite, début, fin) et « V2L · prise extérieure ».
- Centre : le pourcentage en très grand, l'autonomie et l'état de charge. Dessous, la voiture en vue transparente : `s0_ghost.webp` (Standard, Comfort) ou `s1_ghost.webp` (Luxury, XPower), large de 880 px. Cette image n'est pas repeinte.
- Retouche du 26/11 à 15 h, demandée par Marc : les roues du côté opposé ne sont plus dans cette image. Vues à travers la voiture, leurs contours bleu clair doublaient ceux des roues proches (« il y avait deux fois les contours bleu ciel un peu transparent qui se chevauchaient »). **Les deux fichiers sont à recopier**, même taille et mêmes repères (`an.battery`, `an.port`…), rien à changer dans le code. Ne pas dessiner de roues pleines par-dessus : Marc veut garder la voiture transparente telle quelle. Avant et après : `apercu/voiture-transparente-roues.jpg`.
- Sur cette image, un dessin vectoriel dans les coordonnées de l'image (1840 × 680) : la batterie (`an.battery`, quatre coins) en 20 cellules qui se remplissent, vertes en charge et orange en V2L, la cellule suivante qui clignote pendant la charge, le trait « limite 80 % » si la voiture a une limite. La prise (`an.port`, milieu des deux points, aile arrière gauche) s'allume quand le câble est branché, et des tirets circulent le long du câble jusqu'à la batterie (sens inverse en V2L).
- « Réchauffage batterie » : une pastille avec interrupteur, posée au-dessus de la batterie et reliée à elle par un trait. Actif : contour orange et ondes de chaleur. Aujourd'hui c'est une tuile de l'en-tête (`DRVNG_PTC_HEAT`).
- Sous la voiture : « Batterie 96 % · santé estimée | Réglages de la batterie › », qui ouvre la fenêtre de batterie actuelle.
- En bas : le bouton principal (CHARGER MAINTENANT, ARRÊTER LA CHARGE, CÂBLE DÉBRANCHÉ) et « Déverrouiller le pistolet » si le câble est branché. Puis « Limite de charge » (seulement avec `Firmware.nativeChargeLimit`) et « Courant de charge AC ». Les deux dispositions sont prévues. Pendant le V2L, le bouton principal devient un bandeau orange (« La voiture alimente la prise extérieure · arrêt automatique à 40 % ») et la zone du bas montre trois informations (batterie utilisable, énergie disponible, durée à cette puissance).
- Droite : « Consommation » (période, total, récupération, anneau, courbe récente), comme aujourd'hui.

### Climatisation (`P_cli`, `air.js`)

- Fond : `cabin_l.webp` (conduite à gauche) ou `cabin_r.webp` (à droite), 2400 × 900, affichée en 1808 × 678 et remontée de 20 px dans une zone de 1808 × 644. Par-dessus, un voile sombre sur les côtés et en haut, et une légère teinte orange ou bleue. Clim arrêtée : image désaturée et assombrie, plus de flux.
- Flux d'air : rubans le long de courbes de Bézier qui partent des vraies bouches. Les coordonnées de `air.js` sont dans l'image source 2400 × 900 (facteur 1808/2400, décalage −20 px). Trois groupes : visage (quatre aérateurs), pare-brise (douze le long de la planche), pieds (deux). Dessin en mode additif. Orange quand la consigne dépasse la température extérieure, bleu sinon. Vitesse et force suivent la ventilation (0,55 en AUTO, sinon 0,2 + 0,8 × niveau/11). Le dégivrage avant renforce le pare-brise. En conduite à droite, tout le dessin est retourné.
- Trois repères « Pare-brise », « Visage », « Pieds » à x = 1196 (ou 1808 − 1196 à droite), y = 300, 446, 566, et trois zones tactiles invisibles sur l'image. C'est la logique actuelle (`toggleFlow`, au moins une sortie reste active).
- Panneau vitré côté conducteur : température (−, valeur, +, barre de 17 crans LO à HI) et ventilation (−, valeur, +, jauge 11 crans). Garde les crans réels de l'appli (`tempFirst`, `tempLast`).
- Panneau vitré côté passager : AUTO (avec le débit d'air AUTO), A/C, ECO, air recyclé / extérieur / auto, dégivrage avant, dégivrage arrière, et les deux réglages liés quand la voiture les fournit. Lien « Écran clim d'origine ».
- Appui long sur l'image, hors panneaux et repères : fenêtre « Poste de conduite », conduite à gauche ou à droite. Les deux panneaux changent de côté.
- En-tête : « Extérieur 9° · Chauffage » (ou Refroidissement, Clim arrêtée) et le bouton marche/arrêt.

## 6. `car.json`

Coordonnées en pixels de l'image source de chaque vue (`side` 1840 × 680, `q34` 1600 × 720).
- `pos.lo`, `pos.size` : boîte en mètres pour décoder les passes de position. x en travers (positif vers la caméra du profil), y le long de la voiture (essieu avant à −1,273, arrière à +1,462), z vers le haut depuis le sol.
- `wheels[type]`, `trim`, `roof[carrosserie]` : rectangle `[x0, y0, x1, y1]` où poser l'image des roues, des bas de caisse, du masque de toit.
- `an` : `front` et `rear` (centre `c`, point au sol `ground`, rayons `rx`, `ry`), `battery`, `port`, `rims`, `nose`, `tail`, `roof`, moteurs.
- `cam` : position de la caméra en mètres (pour l'effet d'angle des coloris fun).
- `box` : sert seulement à la fabrication des images, inutile à l'appli. `ghost: true` : la vue transparente n'existe que de profil.

## 7. Les images à embarquer

66 fichiers, 5,98 Mo (80 fichiers et environ 6,4 Mo avec le tableau de bord de jour et les pastilles de peinture de la section 13). Ne pas embarquer `vehicle.png`, `energy.png`, `climate.png` (captures de l'appli actuelle) ni `mg4_top.png`.

| Rôle | Fichiers | Format | Profil | Trois-quarts |
|---|---|---|---|---|
| Rendus W et K par finition | `{s,q}{0,1}_{solid,metal,matte,chrome}{W,K}.webp` | WebP sans transparence, pleine taille | 811 Ko | 677 Ko |
| Silhouette | `{s,q}{0,1}_a.png` | PNG gris | 131 Ko | 80 Ko |
| Position et normale | `{s,q}{0,1}_{p1,p2,n}.png` | PNG (p1, p2 sans transparence ; n avec transparence 0 ou 255) | 1 031 Ko | 1 435 Ko |
| Masque de toit | `{s,q}{0,1}_roof.png` | PNG gris, rogné à `roof[carrosserie]` | 41 Ko | 20 Ko |
| Roues | `{s,q}_wh_{steel16,aero17,lux18,xp18}.webp` | WebP avec transparence, rogné à `wheels[type]` | 91 Ko | 65 Ko |
| Bas de caisse mats | `{s,q}_trim.webp` | WebP avec transparence, rogné à `trim` | 13 Ko | 13 Ko |
| Vue transparente | `s{0,1}_ghost.webp` | WebP avec transparence, pleine taille | 1 032 Ko | refaite le 26/11 à 15 h (sans les roues du côté opposé) |
| Tableau de bord | `cabin_{l,r}.webp` | WebP 2400 × 900 | 398 Ko | refait le 26/11 à 15 h 30 (rétroviseurs) |
| Tableau de bord de jour (mode clair, section 13) | `cabin_{l,r}_day.webp` | WebP 2400 × 900 | 312 Ko | ajout du 26/11, fond « Brume », refait à 15 h 30 (rétroviseurs) |
| Peinture des montants de porte sur le tableau de bord (section 13) | `cabin_{l,r}_paint_{a,b,c}.png`, `cabin_{l,r}_day_paint_{a,b,c}.png` | PNG blanc avec transparence, rogné | 70 Ko | 12 fichiers, ajout du 26/11 à 14 h 15, refaits à 15 h 30 |

`s` = profil, `q` = trois-quarts. `0` = Standard et Comfort (sans becquet), `1` = Luxury et XPower (becquet).
Les passes de position et de normale (2,5 Mo) ne servent qu'aux six coloris fun calculés et aux livrées.

Conseils Android :
- Mets-les dans `assets/` (le dossier n'existe pas encore), pas dans `res/drawable` : pas de mise à l'échelle selon la densité.
- Décode avec `inScaled = false` et `ARGB_8888`.
- Les fichiers copiés dans les fichiers projet gagnent environ 6 Ko chacun (une étiquette de provenance ajoutée par la plateforme). Les pixels sont identiques.

## 8. Le moteur de peinture (`paint.js`)

Le principe : pour chaque finition, deux rendus de la même voiture, l'un avec une peinture claire (W, albédo 0,8), l'autre avec une peinture noire (K). N'importe quelle couleur s'en déduit, pixel par pixel, en lumière linéaire :

```
sortie = K + (c / 0,8) × (W − K)
```

`c` est la couleur de la peinture en linéaire. W et K sont convertis de sRGB en linéaire par une table de 256 valeurs (`lut`), la sortie revient en sRGB par une table de 16 384 valeurs (`olut`).

Les étapes de `compose()` (lignes 144 à 165) :
1. Charger W et K de la finition de la peinture (`f` : 0 opaque, 1 métallisée, 2 mate, 3 chrome, 4 satinée). La finition satinée (Hunter Green) n'a pas d'image : c'est le mélange, en linéaire, de 40 % du rendu mat et 60 % du métallisé (`MIXF`), pour W comme pour K.
2. Pour chaque pixel dont la silhouette (`_a.png`) n'est pas nulle, appliquer la formule. C'est tout pour les 8 couleurs MG, l'or et le chrome.
3. Coloris fun calculés et livrées : chaque pixel a un point 3D. Décodage : `x = lo.x + size.x × ((p1.b × 256 + p2.b) / 65535)`, `y = lo.y + size.y × ((p1.r × 256 + p1.g) / 65535)`, `z = lo.z + size.z × ((p2.r × 256 + p2.g) / 65535)`. Normale = `n.rgb − 128`, divisée par 127. Le pixel est valide si `n.alpha ≥ 128`, sinon il prend la couleur de base. La fonction `fx(s)` de la peinture, puis celle de la livrée, modifient la couleur `s.r, s.g, s.b` (linéaire) à partir de `s.x, s.y, s.z`, de la normale et de `s.f` (cosinus de l'angle entre la normale et la direction de la caméra `cam`, borné entre 0 et 1). `s.er, s.eg, s.eb` s'ajoutent à la fin (les étoiles de « Galaxie »). `s.k` est la couleur de contraste (noir sur peinture claire, blanc sinon, seuil de luminance 0,30). `s.aa` est la largeur d'adoucissement des bords, en mètres : 0,0026 de profil, 0,0034 de trois-quarts, divisée par l'échelle.
4. Toit noir : `sortie = mélange(sortie, K de la finition opaque, masque / 255)`.
5. Conversion en sRGB, transparence = silhouette.
6. Poser l'image des bas de caisse mats (toutes les versions sauf la XPower), puis l'image des roues.

Parce que les livrées sont calculées en 3D, elles tombent juste dans les deux vues sans rien dessiner deux fois.

Pièges du portage :
- **Ne jamais lisser `p1`, `p2`, `n`**. Chaque coordonnée est codée sur deux octets rangés dans des canaux différents : une moyenne entre voisins donne des points faux. Pour une vignette, prendre un pixel sur N. Les rendus W et K, eux, se réduisent avec lissage.
- `h3` (le bruit) repose sur la multiplication entière sur 32 bits de `Math.imul` et sur `>>>`. En Kotlin : `Int` qui déborde, `ushr`, puis passage en non signé avant de diviser par 2³². `Math.floor` sur les négatifs : `floor()`, pas `toInt()`.
- Les décalcomanies (numéro de la livrée « Rallye », éclair) sont dessinées à plat dans le plan du flanc : `X = (y + 2,3) × k`, `Y = (1,7 − z) × k`, `k = 2048 / 4,6` pixels par mètre. Elles sont lues avec une interpolation bilinéaire (`dsample`) et posées seulement sur les flancs (`side(s)`), le numéro seulement côté caméra (`s.x > 0`). Le numéro utilise la police Saira Semi Condensed, que l'appli n'a pas : prends les deux images toutes faites `ref/decal_num.png` et `ref/decal_bolt.png` (2048 × 757).
- La livrée « Flammes » est entièrement calculée (l'entrée `flame` de `DECAL` ne sert pas).

Conseils pour l'autoradio :
- Une image de profil fait 1,25 million de pixels. Calcule hors du fil principal, avec des `IntArray` et les deux tables.
- Garde en cache l'image composée par réglage (la maquette utilise la clé `vue|échelle|carrosserie|peinture|livrée|toit|jante|bas de caisse`) et écris sur disque celle du réglage courant, pour que la page s'ouvre sans calcul.
- Garde l'ancienne image affichée pendant le calcul de la nouvelle (ce que fait `mount`).
- Chaque image pleine taille décodée pèse 5 Mo en mémoire : décoder, composer, libérer. Les passes de position ne se chargent que pour un coloris fun ou une livrée.
- Vignettes du menu : les 12 livrées sont calculées à l'échelle 1/8, une par une, sans bloquer l'affichage. Les 4 jantes sont simplement découpées dans l'image des roues de profil, autour de la roue avant (`an.front`).

## 9. Contrôler le portage

`ref/` contient 30 voitures calculées par la maquette à l'échelle 1 (PNG, pixels exacts) : les quatre versions, les huit coloris fun, les onze livrées, dans les deux vues. `ref/index.json` donne le réglage de chacune (`key`).
Pour une couleur MG, le résultat Kotlin doit être identique à une ou deux unités près par canal (le décodage WebP peut varier très légèrement). Pour les coloris calculés et les livrées, le dessin doit se superposer.
`src-v4/refs.js` les a produites (`node refs.js <dossier>`, la maquette servie sur le port 8765).

## 10. Ce qui reste approximatif (déjà dit à Marc)

- Enjoliveur 16″ noir : dessiné sur l'architecture du 17″, tout en noir (photo trop petite).
- Arrière de la Standard (sans becquet) : refermé à la main à partir du modèle à becquet.
- Conduite à droite : image retournée, avec le logo, le compteur et l'écran remis à l'endroit.
- Motif « carbone » des bas de caisse simplifié en stries.
- Couleurs ajustées à l'œil sur les photos.

## 11. Liste des réglages de la voiture affichée

- Versions (`VERS`) : Standard (carrosserie 0, 17″, bas de caisse mats), Comfort (idem), Luxury (carrosserie 1, 18″ Luxury, mats), XPower (carrosserie 1, 18″ XPower, toit noir, bas de caisse brillants).
- Jantes (`WHEELS`) : `steel16` 16″ tôle (enjoliveur noir, rare), `aero17` 17″ alliage (enjoliveur bi-ton), `lux18` 18″ alliage (enjoliveur Luxury), `xp18` 18″ XPower (étriers orange).
- Couleurs MG (`PAINTS`) : Dover White `#E9EBEA` opaque, Pebble Black `#101114` métallisée, Medal Silver `#B9BDC2` métallisée, Andes Grey `#5A6069` métallisée, Brighton Blue `#008CC8` opaque, Diamond Red `#B40C12` métallisée, Fizzy Orange `#F0641A` métallisée, Hunter Green `#7C9277` satinée (XPower).
- Coloris fun : Or massif, Chrome (finition chrome, sans calcul), Arc-en-ciel, Caméléon, Galaxie, Coucher de soleil, Barbe à papa, Carbone forgé.
- Livrées (`LIVS`) : Aucune, Rallye, Flammes, Éclair, Damier, Pixels, Tricolore, Camouflage, Léopard, Zèbre, Coccinelle, Vache.

Les noms des couleurs MG restent en anglais. Les noms des coloris fun, des livrées et les nouveaux textes sont à traduire dans les langues de l'appli.

## 12. Points à trancher ou à surveiller

1. « Économie d'énergie » : interrupteur dans la maquette, pas de commande trouvée dans l'appli (section 5).
2. « D'origine » dans le menu : sens à fixer (section 5).
3. Vitesse du calcul sur l'autoradio, surtout pour les coloris calculés : à mesurer sur la voiture.
4. Poids : +6 Mo, à dire à Marc dans le résultat.
5. Côté de conduite par défaut : gauche dans la maquette.

## 13. Tableau de bord : version « jour » et écran central (ajout du 26/11, 13 h, retouché à 13 h 15, fond « Brume » à 13 h 25, couleur de la carrosserie et bande noire du pare-brise à 14 h 15, rétroviseurs à 15 h 30)

Demandé par Marc le 26/11 (retours sur la première installation) : en mode clair la photo sombre rend les textes noirs illisibles, et l'écran central doit montrer la vue Climatisation elle-même (mise en abyme).

### Les images de jour
- `img/cabin_l_day.webp` (conduite à gauche) et `img/cabin_r_day.webp` (à droite) : 2400 × 900, WebP, environ 150 Ko chacune. **Même cadrage, même taille et mêmes repères** que `cabin_l.webp` et `cabin_r.webp` : on change seulement de fichier selon le thème (clair = `_day`, sombre = image actuelle). Les flux d'air, les zones tactiles et les coins de l'écran ne bougent pas.
- Ce qui change : derrière les vitres, le tunnel de nuit est remplacé par un fond abstrait, « Brume » : un ciel en dégradé (bleu pâle en haut, presque blanc à l'horizon), un sol clair et quelques rayons très légers, sans route ni décor ; l'habitacle est rééclairé (gris clair au lieu de noir) ; les rétroviseurs montrent le même dégradé. Les deux écrans gardent leur propre lumière.
- Ce fond est entièrement calculé par `cabin.py` (aucune photo extérieure, donc aucune question de droits en plus). En conduite à droite l'image est simplement retournée.
- Poids : +0,3 Mo dans l'APK.

### Retouches du 26/11 à 13 h 15, après l'avis de Marc (« très bien dans l'ensemble »)
- Paysage plus discret : plus flou, plus pâle, moins saturé.
- Le combiné ne se voit plus à travers la jante du volant : seule sa partie visible garde sa lumière.
- La barre noire brillante du tableau de bord est redressée près du volant (le rendu MG la terminait par un reflet recourbé ; « en réalité c'est vraiment une barre tout le long »). Cette retouche touche aussi les deux images de nuit : **les quatre images sont à recopier**, mêmes noms, mêmes repères.

### Fond « Brume », choisi par Marc le 26/11 à 13 h 25
- La première version de jour montrait une route de campagne. À 13 h 10 Marc demande un paysage « plus abstrait, moins réaliste ». Trois essais lui sont montrés (`apercu/tableau-de-bord-jour-abstrait.jpg`) : Brume, Lumière (le tunnel de nuit en tons clairs), Vitesse (le paysage étiré en traînées). Il choisit **Brume**.
- **Les deux images de jour sont à recopier** (`cabin_l_day.webp`, `cabin_r_day.webp`), mêmes noms, mêmes repères. L'habitacle est identique à la version de 13 h 15, les images de nuit ne changent pas.
- L'ancien paysage et les essais non retenus sont gardés dans `apercu/essais-jour/` (`paysage`, `brume`, `lumiere`, `vitesse`), au cas où. Aperçu de la version en place : `apercu/tableau-de-bord-jour.jpg`.

### Couleur de la carrosserie et bande noire du pare-brise (26/11, 14 h 15)
Demandé par Marc à 13 h 28 : « on voit un peu la carrosserie à droite et à gauche, ici en bleu, ça pourrait suivre la couleur choisie ? et sur le contour du pare-brise la petite bande noire doit être continue ».

**Les quatre images du tableau de bord sont refaites et à recopier**, mêmes noms, mêmes repères (zones tactiles, flux d'air et coins de l'écran inchangés) :
- La peinture bleue des montants de porte, à gauche et à droite, et de leur reflet dans le rétroviseur intérieur est retirée des images. À ces endroits il ne reste que du noir et les reflets clairs.
- De jour, la bande noire qui borde le pare-brise est redessinée d'un seul tenant : le long des deux montants (large en haut, fine en bas, comme sur la vue de MG) et au pied du pare-brise. De nuit rien ne change sur ce point, la bande y était déjà entière.
- De jour aussi, un coin du ciel du tunnel resté sous le montant de la porte gauche est retiré.

**Douze pastilles de peinture, nouvelles, dans `img/`** : `cabin_{l,r}_paint_{a,b,c}.png` pour les images de nuit, `cabin_{l,r}_day_paint_{a,b,c}.png` pour celles de jour. Chaque pastille est blanche, sa transparence (alpha) est la quantité de peinture. `a` et `c` sont les deux montants de porte, `b` le reflet dans le rétroviseur intérieur. Les pastilles `r` sont déjà retournées : il n'y a rien à retourner soi-même. Place de chacune dans son image 2400 × 900, en pixels (les mêmes valeurs sont dans `src-v4/cabin.json`, clé `paint`, listes `l`, `l_day`, `r`, `r_day`) :

| Pastille | Dans `cabin_l` et `cabin_l_day` | Dans `cabin_r` et `cabin_r_day` | Taille |
|---|---|---|---|
| `a` | x 137, y 147 | x 1870, y 147 | 393 × 319 |
| `b` | x 985, y 105 | x 1370, y 105 | 45 × 93 |
| `c` | x 1901, y 187 | x 126, y 187 | 373 × 295 |

**La règle** : image affichée = image du tableau de bord + pastille × couleur de la voiture (fusion additive, plafonnée à 255). Sans les pastilles les montants sont noirs, ce qui est aussi le rendu d'une voiture noire. Avec `#008CC8` (Brighton Blue) on retrouve le rendu de MG, un peu plus saturé.

**Quelle couleur** : la teinte de base du coloris choisi dans « Couleurs et style » (pour les sept teintes MG, les valeurs de `PAINTS`, section 8 ; pour un coloris fun ou une livrée, sa couleur dominante). La même couleur de jour et de nuit : les pastilles de nuit sont déjà plus sombres.

**Sur Android**, le plus sûr est de composer une fois dans un bitmap, à chaque changement de couleur, de thème ou de côté de conduite, puis de se servir de ce bitmap partout où l'image du tableau de bord servait. Ainsi la désaturation « clim arrêtée », le voile et la mise en abyme s'appliquent d'eux-mêmes à la peinture.

```kotlin
val out = cabin.copy(Bitmap.Config.ARGB_8888, true)      // cabin_l ou cabin_l_day, décodée avec inScaled = false
val canvas = Canvas(out)
val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
    colorFilter = PorterDuffColorFilter(carColor, PorterDuff.Mode.SRC_IN)   // la pastille blanche prend la couleur (opaque, 0xFFrrggbb) et garde son alpha
    xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)                      // puis elle s'ajoute à l'image
}
for (p in patches) canvas.drawBitmap(p.bitmap, p.x.toFloat(), p.y.toFloat(), paint)   // x, y du tableau ci-dessus
```

En Compose, la même chose dans un calque : `drawImage(patch, topLeft = Offset(x, y), colorFilter = ColorFilter.tint(carColor, BlendMode.SrcIn), blendMode = BlendMode.Plus)`. En canvas web : teinter la pastille dans un canvas à part (`source-in`), puis la dessiner en `lighter`.

**Contrôle** : `cabin_l_day` et ses trois pastilles en `#B40C12` donnent des montants rouges sans liseré bleu ; en `#101114` les montants restent noirs. Aperçu : `apercu/tableau-de-bord-couleur-carrosserie.jpg` (rouge de jour, sept teintes en vignettes, orange de nuit, bande noire avant et après).

**Poids** : +70 Ko. **Maquette** (version 8, 26/11 à 14 h 30) : la page Climatisation dessine les trois pastilles de nuit à la couleur choisie, voir `CABP`, `carCss` et `cabPaint` dans `maquettes.html` (un calque par pastille, masqué par le PNG, en `mix-blend-mode: plus-lighter`, dans le même bloc que l'image pour que la désaturation « clim arrêtée » s'applique au tout). Les panneaux et le voile sombre cachent une bonne partie des montants : on les voit peu, comme dans l'appli.

### Rétroviseurs (26/11, 15 h 30)
Demandé par Marc à 14 h 53 : « En vue claire le rétroviseur côté conducteur a été un peu coupé, il faut le corriger ».

Trois défauts trouvés, tous corrigés dans les images. **Rien à changer dans le code.**
- **Rétroviseur intérieur, de jour** : le bas de son cadre, du côté du conducteur, était pris pour du ciel et se perdait dans le fond clair, son petit voyant vert aussi. Le cadre est de nouveau entier, le voyant est vert, la glace va jusqu'au cadre.
- **Rétroviseur de porte du conducteur, de nuit comme de jour** : dans la vue de MG il sort de l'image (sa pointe est 43 px au-delà du bord). Il est maintenant entier, à 7 px du bord : la partie qui manquait vient du panorama MG (`sources/interior.jpg`), et le rétroviseur est resserré vers la porte, de 30 % au plus à sa pointe et de rien à sa racine. Le reste de l'image ne bouge pas : seule la zone x 0 à 253, y 302 à 559 de `cabin_l` change (la même, retournée, dans `cabin_r`).
- **Rétroviseurs de porte, de jour** : leur coque était aussi claire que le ciel et leur glace aussi lumineuse, on lisait mal leur forme. Coque et glace sont assombries.

**À recopier : les quatre images `cabin_*.webp` et les douze pastilles**, mêmes noms, même taille, mêmes repères. `cabin.json` n'a pas changé (coins de l'écran, combiné, places des pastilles). Les pastilles `a` et `c` sont les mêmes à un niveau près ; les pastilles `b` de jour vont maintenant jusqu'au cadre retrouvé du rétroviseur intérieur.

Le rétroviseur de porte du passager sort toujours de l'image, comme dans la vue de MG : sa pointe est à 102 px au-delà du bord, le resserrer autant le déformerait. Dans la page, les deux rétroviseurs de porte sont en grande partie sous les panneaux.

Aperçu : `apercu/tableau-de-bord-retroviseurs.jpg` (l'image de jour entière, puis avant et après pour le rétroviseur intérieur et pour celui de la porte du conducteur).

**Maquette** (version 9, 26/11 à 15 h 30) : elle reçoit les deux images de nuit, les deux pastilles `b` de nuit et la voiture transparente refaites. Elle n'a pas de mode clair : les images de jour se regardent dans l'aperçu.

### À adapter dans l'appli en mode clair
- Les flux d'air sont dessinés en mode additif (`lighter`) dans la maquette : sur une image claire ils deviennent presque blancs. En mode clair, les dessiner en fusion normale avec des couleurs plus saturées.
- Le voile sombre sur les côtés et en haut (`.cabsh`) est fait pour l'image de nuit : en mode clair, le retirer ou le remplacer par un voile blanc léger.
- Le haut de l'image (vitres) est très clair, le bas de l'habitacle reste gris moyen. Avec des panneaux blancs à environ 55 % d'opacité, les textes noirs se lisent partout (essai fait ici) ; en dessous de 40 %, le bas des panneaux devient juste.
- Avec le fond « Brume », le haut de l'image est presque blanc : des panneaux blancs translucides s'y détachent peu. Leur donner un fin liseré ou une ombre douce.
- Clim arrêtée : la désaturation prévue marche aussi sur l'image de jour, mais l'assombrir moins.

### Les quatre coins de l'écran central
Pixels de l'image 2400 × 900, identiques pour la nuit et le jour. Ordre : haut gauche, haut droit, bas droit, bas gauche.

| Image | Haut gauche | Haut droit | Bas droit | Bas gauche |
|---|---|---|---|---|
| `cabin_l`, `cabin_l_day` | (1080, 436,5) | (1350, 440) | (1350, 545) | (1080, 539,5) |
| `cabin_r`, `cabin_r_day` | (1050, 440) | (1320, 436,5) | (1320, 539,5) | (1050, 545) |

- Les mêmes valeurs sont dans `src-v4/cabin.json` (`screen.l`, `screen.r`, et `cluster` pour le combiné), écrit par `cabin.py`.
- À l'écran : multiplier par 1808/2400 (0,7533) puis retirer 20 px en hauteur, comme pour le reste de la page.
- Le rectangle fait environ 270 × 104 px, soit le rapport 1920 × 720 de l'écran : une capture plein écran s'y pose sans déformation, avec une transformation en perspective sur ces quatre points (`Matrix.setPolyToPoly` sur Android).
- Multiplier les couleurs de la capture par 0,94 (`screen.gain`) : un écran dans un habitacle, pas une feuille blanche.
- Mise en abyme : dessiner dans ce rectangle la dernière image rendue de la vue. Chaque niveau fait 11 % du précédent : au troisième niveau l'écran mesure 3 px, inutile d'aller plus loin.
- Sans rien dessiner, l'écran montre l'accueil de MG4Dash, déjà incrusté dans les quatre images.

### Refaire ces images
Trois étapes, dans `src-v4/` :
1. `glass.py <cabin_lhd.png> masques` détoure les vitres et les miroirs de la vue de MG (`glass.png`, `mirrors.png`, `foot.npz`, déjà dans `src-v4/masques/`).
2. `mirror_whole.py <sources/interior.jpg> <cabin_lhd.png> masques masques2` (depuis le 26/11 à 15 h 30) remet en entier le rétroviseur de porte du conducteur à partir du panorama et écrit dans `src-v4/masques2/` la vue corrigée (`cabin_lhd.png`) et ses masques. Ce dossier est déjà rempli.
3. `cabin.py masques2/cabin_lhd.png <capture accueil> <img> day=masques2` écrit les quatre images et les douze pastilles de peinture.

Avec la vue de MG d'origine et `day=masques`, on retrouve le rétroviseur de porte coupé par le bord. Sans `day=`, seules les images de nuit sont refaites, avec la peinture bleue laissée dans l'image et sans pastilles. `style=brume|paysage|lumiere|vitesse` choisit le fond de jour ; sans `style=`, c'est `brume`, celui de l'appli. Il faut `opencv-python-headless`.

### Approximations
Fond de synthèse (un dégradé, pas un vrai paysage), capot non représenté au pied du pare-brise, rétroviseurs remplis du même dégradé, habitacle éclairci par une courbe de tons (pas un nouveau rendu 3D). Peinture des montants : une teinte unie posée par addition, sans le reflet propre à chaque peinture (métallisée, nacrée) ; le haut des montants, très éclairé dans le rendu de MG, reste clair quelle que soit la teinte. Bande noire du pare-brise : un gris anthracite uni, redessiné. Rétroviseur de porte du conducteur : resserré (30 % au plus) pour tenir dans l'image ; celui du passager reste coupé par le bord, comme dans la vue de MG.
