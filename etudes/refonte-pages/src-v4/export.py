"""Turn the Blender outputs (r3d/out/final) into the web assets of the mock-ups (mq/img) + car.json.
usage: export.py <final dir> <img dir> <car.json> [views=side,q34]"""
import sys, os, json, shutil, math, numpy as np
from PIL import Image
SRC, DST, META_OUT = sys.argv[1], sys.argv[2], sys.argv[3]
VIEWS = (sys.argv[4].split('=')[1] if len(sys.argv) > 4 else 'side,q34').split(',')
TMP = os.path.join(SRC, '_tmp'); os.makedirs(DST, exist_ok=True)
FIN = ('solid', 'metal', 'matte', 'chrome')
WHEEL_GAMMA, WHEEL_GAIN = 0.8, 1.04

def bleed(rgb, a, it=10):
    rgb = rgb.astype(np.float32); m = (a > 0).astype(np.float32)
    for _ in range(it):
        s = np.zeros_like(rgb); c = np.zeros_like(m)
        for dy in (-1, 0, 1):
            for dx in (-1, 0, 1):
                s += np.roll(np.roll(rgb * m[..., None], dy, 0), dx, 1); c += np.roll(np.roll(m, dy, 0), dx, 1)
        new = (m == 0) & (c > 0); rgb[new] = s[new] / c[new][:, None]; m[new] = 1
    return rgb.astype(np.uint8)

def feather(h, w, n=10):
    """1 inside, falling to 0 on the border over n pixels"""
    y = np.minimum(np.arange(h), np.arange(h)[::-1])[:, None]; x = np.minimum(np.arange(w), np.arange(w)[::-1])[None, :]
    return np.clip(np.minimum(y, x) / float(n), 0, 1).astype(np.float32)

def dilate(arrs, ok, it=6):
    """grow the valid area of the data images by copying a valid neighbour (keeps exact values)"""
    ok = ok.copy(); arrs = [a.copy() for a in arrs]
    for _ in range(it):
        todo = ~ok; done = np.zeros_like(ok)
        for dy, dx in ((0, 1), (0, -1), (1, 0), (-1, 0), (1, 1), (1, -1), (-1, 1), (-1, -1)):
            src_ok = np.roll(np.roll(ok, dy, 0), dx, 1); take = todo & src_ok & ~done
            if not take.any(): continue
            for a in arrs: a[take] = np.roll(np.roll(a, dy, 0), dx, 1)[take]
            done |= take
        ok = ok | done
    return arrs, ok

OUT = {'pos': None}
for view in VIEWS:
    p = 's' if view == 'side' else 'q'
    m0 = json.load(open(f"{SRC}/{view}_b0_meta.json")); m1 = json.load(open(f"{SRC}/{view}_b1_meta.json"))
    if os.path.exists(f"{SRC}/{view}_b0_meta_wheels.json"):                      # wheels, anchors and trim re-rendered on their own
        mw = json.load(open(f"{SRC}/{view}_b0_meta_wheels.json")); m0['wheels'] = mw.get('wheels', m0.get('wheels')); m0['anchors'] = mw.get('anchors', m0['anchors'])
    W, H = m0['w'], m0['h']; box = m1['box']
    # Blender rounds the render border by itself: find where the patch really sits (compare its rim with the full render)
    full = np.array(Image.open(f"{TMP}/{view}_b0_solidW.png").convert('RGBA')).astype(np.float32); crop = np.array(Image.open(f"{TMP}/{view}_b1_solidW.png").convert('RGBA')).astype(np.float32)
    bh, bw = crop.shape[:2]; best = None
    for oy in (-2, -1, 0, 1, 2):
        for ox in (-2, -1, 0, 1, 2):
            y0, x0 = box[1] + oy, box[0] + ox
            if y0 < 0 or x0 < 0 or y0 + bh > H or x0 + bw > W: continue
            reg = full[y0:y0 + bh, x0:x0 + bw]; rim = np.ones((bh, bw), bool); rim[8:-8, 8:-8] = False
            e = np.abs(reg - crop)[rim].mean()
            if best is None or e < best[0]: best = (e, ox, oy)
    print(view, 'patch', crop.shape, 'declared', box, 'offset', best)
    box = [box[0] + best[1], box[1] + best[2], box[0] + best[1] + bw, box[1] + best[2] + bh]; fe = feather(bh, bw)[..., None]
    V = {'w': W, 'h': H, 'box': box, 'wheels': m0.get('wheels', {}), 'an': m0['anchors'], 'roof': {}}
    OUT['pos'] = m0.get('pos') or m1.get('pos') or OUT['pos']
    # ---- colour bases: body 0 as rendered, body 1 = body 0 with the spoiler patch
    for f in FIN:
        for t in 'WK':
            src0 = f"{TMP}/{view}_b0_{f}{t}.png"; src1 = f"{TMP}/{view}_b1_{f}{t}.png"
            if not os.path.exists(src0): print('missing', src0); continue
            shutil.copy(f"{SRC}/{view}_b0_{f}{t}.webp", f"{DST}/{p}0_{f}{t}.webp")
            if not os.path.exists(src1): print('missing', src1); continue
            a0 = np.array(Image.open(src0).convert('RGBA')).astype(np.float32); c1 = np.array(Image.open(src1).convert('RGBA')).astype(np.float32)
            assert c1.shape[:2] == (bh, bw), (c1.shape, box)
            # premultiplied blend of the patch (feathered border)
            reg = a0[box[1]:box[3], box[0]:box[2]]
            pa0 = reg[..., :3] * reg[..., 3:] / 255; pa1 = c1[..., :3] * c1[..., 3:] / 255
            al = reg[..., 3:] * (1 - fe) + c1[..., 3:] * fe; pm = pa0 * (1 - fe) + pa1 * fe
            reg[..., :3] = np.where(al > 0, pm * 255 / np.maximum(al, 1e-6), 0); reg[..., 3:] = al
            a0 = np.clip(a0 + 0.5, 0, 255).astype(np.uint8)
            Image.fromarray(bleed(a0[..., :3], a0[..., 3])).save(f"{DST}/{p}1_{f}{t}.webp", quality=90, method=6)
            if f == 'solid' and t == 'K':
                Image.fromarray(a0[..., 3]).save(f"{DST}/{p}1_a.png", optimize=True)
                Image.fromarray(np.array(Image.open(src0).convert('RGBA'))[..., 3]).save(f"{DST}/{p}0_a.png", optimize=True)
    # ---- position / normal passes (dilated so that the anti-aliased rim of the paint has data too)
    def load_pos(b):
        f = lambda k: np.array(Image.open(f"{SRC}/{view}_b{b}_{k}.png"))
        return f('p1'), f('p2'), f('n')
    if os.path.exists(f"{SRC}/{view}_b0_p1.png"):
        p1, p2, n = load_pos(0)
        sets = {0: (p1, p2, n)}
        if os.path.exists(f"{SRC}/{view}_b1_p1.png"):
            q1, q2, qn = load_pos(1); a1, a2, an_ = p1.copy(), p2.copy(), n.copy()
            a1[box[1]:box[3], box[0]:box[2]] = q1; a2[box[1]:box[3], box[0]:box[2]] = q2; an_[box[1]:box[3], box[0]:box[2]] = qn; sets[1] = (a1, a2, an_)
        for b, (a1, a2, an_) in sets.items():
            ok = an_[..., 3] > 127; (d1, d2, dn), ok2 = dilate([a1, a2, an_[..., :3]], ok)
            Image.fromarray(d1).save(f"{DST}/{p}{b}_p1.png", optimize=True); Image.fromarray(d2).save(f"{DST}/{p}{b}_p2.png", optimize=True)
            Image.fromarray(np.dstack([dn, (ok2 * 255).astype(np.uint8)])).save(f"{DST}/{p}{b}_n.png", optimize=True)
    # ---- roof masks
    for b, m in ((0, m0), (1, m1)):
        f = f"{SRC}/{view}_b{b}_roof.png"; rb = m.get('roof_box')
        if not rb and os.path.exists(f"{SRC}/{view}_b{b}_meta_roof.json"): rb = json.load(open(f"{SRC}/{view}_b{b}_meta_roof.json")).get('roof_box')
        if os.path.exists(f) and rb: shutil.copy(f, f"{DST}/{p}{b}_roof.png"); V['roof'][str(b)] = rb
    # ---- wheels, x-ray
    for t in V['wheels']:
        src = f"{TMP}/{view}_wh_{t}.png"
        if os.path.exists(src):
            # the wheel wells sit in the shade of the studio lights: lift the wheels a little so that their design reads on a dark screen
            a = np.array(Image.open(src).convert('RGBA')).astype(np.float32) / 255
            wb = V['wheels'][t]
            if a.shape[:2] == (H, W): a = a[wb[1]:wb[3], wb[0]:wb[2]]          # the lossless original is the full frame
            assert a.shape[:2] == (wb[3] - wb[1], wb[2] - wb[0]), (a.shape, wb)
            a[..., :3] = np.clip(WHEEL_GAIN * a[..., :3] ** WHEEL_GAMMA, 0, 1)
            Image.fromarray((a * 255 + 0.5).astype(np.uint8)).save(f"{DST}/{p}_wh_{t}.webp", quality=90, method=6)
        else: shutil.copy(f"{SRC}/{view}_wh_{t}.webp", f"{DST}/{p}_wh_{t}.webp")
    # ---- matt black sills and bumper lowers (every version but the XPower): keep only the solid parts and their soft edge
    src = f"{TMP}/{view}_trim.png"
    if os.path.exists(src):
        t = np.array(Image.open(src).convert('RGBA')); solid = t[..., 3] > 128; m = solid.copy()
        for _ in range(3):
            m = m | np.roll(m, 1, 0) | np.roll(m, -1, 0) | np.roll(m, 1, 1) | np.roll(m, -1, 1)
        t[~m] = 0; ys, xs = np.nonzero(t[..., 3] > 0); tb = [int(xs.min()) - 1, int(ys.min()) - 1, int(xs.max()) + 2, int(ys.max()) + 2]
        Image.fromarray(t).crop(tb).save(f"{DST}/{p}_trim.webp", quality=90, method=6); V['trim'] = tb
    for b in (0, 1):
        f = f"{SRC}/{view}_b{b}_ghost.webp"; src = f"{TMP}/{view}_b{b}_ghost.png"
        if os.path.exists(src): Image.open(src).convert('RGBA').save(f"{DST}/{p}{b}_ghost.webp", quality=84, method=6); V['ghost'] = True
        elif os.path.exists(f): shutil.copy(f, f"{DST}/{p}{b}_ghost.webp"); V['ghost'] = True
    OUT[view] = V
OUT['side' if 'side' in OUT else VIEWS[0]].setdefault('cam', [60, 0, 0.745])
if 'q34' in OUT:
    az = math.radians(35); OUT["q34"]["cam"] = [9 * math.cos(az), -9 * math.sin(az), 1.85]
if os.path.exists(META_OUT):
    old = json.load(open(META_OUT))
    for k, v in old.items():
        if k not in OUT or OUT[k] is None: OUT[k] = v
def rnd(o):
    if isinstance(o, float): return round(o, 2)
    if isinstance(o, list): return [rnd(x) for x in o]
    if isinstance(o, dict): return {k: rnd(v) for k, v in o.items()}
    return o
json.dump(rnd(OUT), open(META_OUT, 'w'), separators=(',', ':'))
print('exported', VIEWS, sum(os.path.getsize(os.path.join(DST, f)) for f in os.listdir(DST)) // 1024, 'KB in', len(os.listdir(DST)), 'files')
