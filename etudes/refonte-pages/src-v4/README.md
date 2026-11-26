# Maquettes v4 des pages Véhicule, Énergie, Climatisation (MG4Dash)

Étude seulement : rien n'est dans le code de l'appli.
La v4 (26/11/2026) corrige la v3 d'après les douze photos de Marc (`../sources/photos-marc/`). La v3 reste à côté : `../maquettes_v3.html`, `../src-v3/`, `../rendu3d/v3/`.

## Ce que la v4 change
- Jantes : taille et dessin mesurés sur les photos. Bord de jante visible = 74 % du rayon du pneu en 18″, 72,7 % en 17″, 68,2 % en 16″ (`RIMS` dans `prod.py`).
  18″ Luxury, 17″ bi-ton et 16″ noir sont des dessins 2D (`../rendu3d/rims2d.py`) mis en volume par `wheels.py`. La 18″ XPower est celle du modèle MG, agrandie.
- Hunter Green : `#7C9277`, finition satinée = mélange des rendus mat et métallisé (`MIXF` dans `paint.js`).
- Bas de caisse : calque `*_trim.webp` (plastique noir mat, strié sur la porte arrière) posé sur toutes les versions sauf la XPower.
- Vue transparente : plage arrière et ses supports retirés (ligne derrière la banquette).
- Tableau de bord (`cabin.py`) : accueil de MG4Dash sur l'écran central, reflets du pare-brise atténués, conduite à droite avec logo, compteur et écran remis à l'endroit.
- Retirés : coloris or rose, néon, lave, holographique ; livrées Mondrian, bandes course, topographie, vagues.

## Reconstruire la page
`node build.js` assemble `page.tpl.html` + `paint.js` (moteur de peinture, avec `car.json`) + `air.js` (flux d'air) + `app.js` (les trois pages).
Le script écrit `../mq/maquettes.html` et `../mq/preview.html` : garder ce dossier à côté d'un dossier `mq/` qui contient `img/`.
Tester par HTTP (pas en `file://`, sinon les canvas sont bloqués) : `python3 -m http.server 8765 --bind 127.0.0.1` dans `mq/`.
`node verify.js` passe 277 états des trois pages (débordements, appui long, galerie, erreurs) et doit finir par `ALL CLEAR`.
`node rev2.js` capture 15 états à comparer aux photos, `node zoom.js` les profils en gros plan, `node full.js 1400 sortie.png` la page entière.
`node land.js` teste le téléphone. `node pub.js` charge la page telle que publiée
(télécharger `index.html` de l'artifact dans un dossier servi sur le port 8766, avec `img/` à côté).

## Refaire les images
1. `../rendu3d/prod.py` (Blender en module Python `bpy`, Cycles) calcule les rendus à partir de `../sources/m_mg4_02.glb` :
   `python -I prod.py <glb> <sortie> body=0|1 view=side|q34 w= h= samples= passes=base,wheels,roof,trim,pos,ghost,anchors [wtypes=lux18,...]`.
   Voir `batch.sh`, `batch2.sh` (rendus de base), `batch3.sh` (jantes, bas de caisse, vue transparente) et `batch4.sh` (une seule jante). Chemins à adapter.
   body 0 = Standard/Comfort (becquet retiré, toit refermé), body 1 = Luxury/XPower (livré comme un patch autour du becquet).
   Une passe « wheels » seule écrit `<vue>_b0_meta_wheels.json`, que `export.py` fusionne avec le méta principal.
2. `export.py <final> <img> <car.json> views=side,q34` fabrique les images web (`../rendu3d/final` contient déjà les sorties).
3. `cabin.py <cabin_lhd.png> <capture accueil MG4Dash> <img>` fabrique le tableau de bord, conduite à gauche et à droite.
   La capture d'accueil est `docs/screenshots/home.png` du dépôt MG4Dash.

## Mesurer une jante sur une photo
Dans `../rendu3d/mesures/` : `rect.py` redresse la photo d'une roue, `unwrap.py` la déroule en carte polaire, `ov.py` superpose un dessin de `rims2d.py`
sur cette carte, `prev.py` montre le dessin à côté de la photo, `wsheet.py` aligne les jantes calculées et les photos. `r03/r04/r05/r11.png` sont les roues redressées
des photos 03, 04, 05 et 11 de Marc (bord de jante à 330 px du centre).

## Principe du moteur de peinture
Par finition (opaque, métallisée, mate, chrome), deux rendus : W (peinture claire, albédo 0,8) et K (peinture noire).
Toute couleur c se recalcule en lumière linéaire : K + c/0,8 × (W − K). Des passes de position et de normale donnent le point 3D
de chaque pixel : les livrées et coloris fun sont des textures 3D, donc cohérentes entre la vue de profil et la vue trois-quarts.
La chaîne `data-car` d'un canvas : `vue|échelle|carrosserie|peinture|livrée|toit|jante|bas de caisse`.

## Ce qui reste approximatif
Enjoliveur 16″ noir (photo petite : architecture du 17″, tout en noir), arrière de la Standard refermé à la main, conduite à droite = image retournée,
motif « carbone » des bas de caisse simplifié en stries, couleurs ajustées à l'œil.

## Droits
Le modèle 3D et la vue intérieure à 360° viennent du site MG (configurateur Mexique).
Marc a confirmé le 26/11/2026 : « Tu as le droit d'utiliser les images venant de MG dans l'appli. »
