import re, os
# Génère app/src/main/res/values*/strings.xml à partir de i18n/strings.psv
rows = [l.rstrip('\n').split('|') for l in open('i18n/strings.psv', encoding='utf-8') if l.strip()]
hdr = rows[0][1:]
rows = rows[1:]
extra = {
    'aa_title': ['ANDROID AUTO'] * 7,
    'aa_ready': ['Phone connected · Tap to open', 'Téléphone connecté · Appuyer pour ouvrir', 'Telefon verbunden · Zum Öffnen tippen',
                 'Teléfono conectado · Pulse para abrir', 'Telefono connesso · Tocca per aprire', 'Telefoon verbonden · Tik om te openen', 'Telefon bağlı · Açmak için dokunun'],
    'aa_waiting': ['Connect your phone via USB', 'Branchez votre téléphone en USB', 'Telefon per USB verbinden',
                   'Conecte su teléfono por USB', 'Collega il telefono via USB', 'Sluit je telefoon aan via USB', 'Telefonunuzu USB ile bağlayın'],
    'unit_kwh100': ['kWh/100'] * 7,
    'no_data': ['—'] * 7,
}
BS = chr(92)

def esc(s):
    if not re.search(r'%\d\$', s):
        s = s.replace('%%', '%')
    s = s.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    s = s.replace(BS, BS + BS).replace("'", BS + "'").replace('"', BS + '"')
    if s != s.strip():
        s = '"' + s + '"'
    return s

for i, lang in enumerate(hdr):
    d = 'app/src/main/res/values' + ('' if lang == 'en' else '-' + lang)
    os.makedirs(d, exist_ok=True)
    out = ['<?xml version="1.0" encoding="utf-8"?>', '<!-- Généré par i18n/gen.py depuis i18n/strings.psv -->', '<resources>']
    items = [(r[0], r[1 + i]) for r in rows] + [(k, v[i]) for k, v in extra.items()]
    for k, v in items:
        fmt = ' formatted="false"' if ('%' in v and not re.search(r'%\d\$', v)) else ''
        out.append(f'    <string name="{k}"{fmt}>{esc(v)}</string>')
    out.append('</resources>\n')
    open(d + '/strings.xml', 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
    print(d, len(items))
