"""Cabin picture of the climate page, from the crop of MG's 360° interior view (pano/cabin_lhd.png, 2400×900).
- the centre screen shows the MG4Dash home screen (a screenshot of the app, laid on the screen in perspective);
- the light streaks seen through the glass are toned down, and the orange ones made neutral so that they are not read as warm air;
- right-hand drive = mirrored picture, in which the MG badge, the instrument cluster and the centre screen are put back the right way
  round, each in the perspective of the mirrored cabin.
usage: cabin.py <cabin_lhd.png> <home screenshot 1920x720> <img dir>"""
import sys, math, numpy as np
from PIL import Image, ImageFilter, ImageDraw
src, shot, dst = sys.argv[1], sys.argv[2], sys.argv[3]
A = dict(x.split('=', 1) for x in sys.argv[4:]); g = lambda k, d: float(A.get(k, d))
im = Image.open(src).convert('RGB'); W, H = im.size
SCREEN = [(1080, 436.5), (1350, 440), (1350, 545), (1080, 539.5)]            # lit area of the centre screen: TL, TR, BR, BL
GLASS = (1066, 426, 1376, 557)                                               # its glass panel
CL_D = [(757, 470), (917, 470), (917, 569), (752, 569)]                      # instrument cluster: lit area
CL_V = [(757, 470), (885, 470), (903, 520), (915, 569), (752, 569)]          # ... the part not hidden by the steering wheel rim
BADGE = [(690, 616), (726, 619.5), (736, 641), (730, 668), (702, 688), (670, 683.5), (654.5, 659.5), (665, 630)]   # octagon: TL, TR, RU, RL, BR, BL, LL, LU
PARTNER = [1, 0, 7, 6, 5, 4, 3, 2]                                           # the vertex facing each one across the badge's vertical axis

def poly_mask(size, pts, blur=0.0, ss=4):
    m = Image.new('L', (size[0] * ss, size[1] * ss), 0); ImageDraw.Draw(m).polygon([(x * ss, y * ss) for x, y in pts], fill=255)
    m = m.resize(size, Image.LANCZOS)
    return np.asarray(m.filter(ImageFilter.GaussianBlur(blur)) if blur else m).astype(np.float32) / 255
def coeffs(out_pts, in_pts):
    """PIL perspective coefficients: output pixel -> input pixel (least squares when more than 4 points)"""
    M = []; B = []
    for (x, y), (u, v) in zip(out_pts, in_pts):
        M.append([x, y, 1, 0, 0, 0, -u * x, -u * y]); M.append([0, 0, 0, x, y, 1, -v * x, -v * y]); B += [u, v]
    return np.linalg.lstsq(np.array(M, float), np.array(B, float), rcond=None)[0]
def grow(pts, d):
    cx = sum(p[0] for p in pts) / len(pts); cy = sum(p[1] for p in pts) / len(pts)
    return [(x + (x - cx) / math.hypot(x - cx, y - cy) * d, y + (y - cy) / math.hypot(x - cx, y - cy) * d) for x, y in pts]
mirror = lambda pts: [(W - x, y) for x, y in pts]

a = np.asarray(im).astype(np.float32) / 255
yy, xx = np.mgrid[0:H, 0:W].astype(np.float32)
# ---- 1. orange streaks -> neutral
mx, mn = a.max(-1), a.min(-1); d = mx - mn; sat = np.where(mx > 0, d / np.maximum(mx, 1e-6), 0)
r, gch, b = a[..., 0], a[..., 1], a[..., 2]
hue = np.zeros_like(mx); m = d > 1e-6
hr = (mx == r) & m; hg = (mx == gch) & m & ~hr; hb = m & ~hr & ~hg
hue[hr] = ((gch - b)[hr] / d[hr]) % 6; hue[hg] = (b - r)[hg] / d[hg] + 2; hue[hb] = (r - gch)[hb] / d[hb] + 4; hue *= 60
warm = ((hue < 62) | (hue > 335)) & (sat > 0.22) & (mx > 0.16)
keep = np.zeros((H, W), bool)
for x0, y0, x1, y1 in ((735, 440, 945, 580), (1180, 560, 1300, 600)):         # cluster, hazard button
    keep[y0:y1, x0:x1] = True
keep[640:, :] = True                                                         # everything under the dashboard (pedals, console)
w = (warm & ~keep).astype(np.float32)
w = np.asarray(Image.fromarray((w * 255).astype(np.uint8)).filter(ImageFilter.GaussianBlur(2.5))).astype(np.float32) / 255
lum = (0.2126 * r + 0.7152 * gch + 0.0722 * b)[..., None]
cool = lum * np.array([0.86, 0.95, 1.08], np.float32)
out = a * (1 - w[..., None] * 0.92) + cool * (w[..., None] * 0.92)
# the reflections of those streaks on the glass of the centre screen: back to the black of the glass
gm = np.zeros((H, W), np.float32); gm[GLASS[1]:GLASS[3], GLASS[0]:GLASS[2]] = 1
gw = (w * gm)[..., None]; out = out * (1 - gw) + np.array([0.022, 0.024, 0.028], np.float32) * gw
# ---- 2. fewer light effects behind the windscreen: the bright streaks are compressed and slightly cooled
L = 0.2126 * out[..., 0] + 0.7152 * out[..., 1] + 0.0722 * out[..., 2]
t0, k = g('t0', 0.26), g('k', 0.22)
Ln = np.where(L > t0, t0 + (L - t0) * k, L)
zone = np.clip((g('zy', 575) - yy) / 90, 0, 1)                               # the glass area: above the dashboard
for q in (SCREEN, CL_D):
    zone *= 1 - poly_mask((W, H), grow(q, 6), 2.0)
f = (Ln / np.maximum(L, 1e-4))[..., None]; dim = out * f
grey = (0.2126 * dim[..., 0] + 0.7152 * dim[..., 1] + 0.0722 * dim[..., 2])[..., None]
amt = np.clip((L - t0) / 0.3, 0, 1)[..., None] * 0.35                        # the brighter the streak, the more it loses its colour
dim = dim * (1 - amt) + grey * np.array([0.86, 0.96, 1.12], np.float32) * amt
out = out * (1 - zone[..., None]) + dim * zone[..., None]
base = Image.fromarray((np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8))

# ---- 3. MG4Dash on the centre screen
home = Image.open(shot).convert('RGB')
def put_screen(img, quad):
    ss = 3; x0 = int(min(p[0] for p in quad)) - 2; y0 = int(min(p[1] for p in quad)) - 2; x1 = int(max(p[0] for p in quad)) + 3; y1 = int(max(p[1] for p in quad)) + 3
    bw, bh = x1 - x0, y1 - y0; cw = int(round((quad[1][0] - quad[0][0]) * ss)); ch = int(round(cw * home.height / home.width))
    c = home.resize((cw, ch), Image.LANCZOS).convert('RGBA')
    co = coeffs([((x - x0) * ss, (y - y0) * ss) for x, y in quad], [(0, 0), (cw, 0), (cw, ch), (0, ch)])
    lay = c.transform((bw * ss, bh * ss), Image.PERSPECTIVE, tuple(co), Image.BICUBIC).resize((bw, bh), Image.LANCZOS)
    la = np.asarray(lay).astype(np.float32) / 255
    la[..., :3] = np.clip(la[..., :3] * g('sgain', 0.94), 0, 1)             # a screen in a dark cabin, not a paper print
    img.paste(Image.fromarray((la * 255 + 0.5).astype(np.uint8)), (x0, y0), Image.fromarray((la[..., 3] * 255 + 0.5).astype(np.uint8)))
lhd = base.copy(); put_screen(lhd, SCREEN)
lhd.save(f"{dst}/cabin_l.webp", quality=88, method=6)

# ---- 4. right-hand drive
rhd = base.transpose(Image.FLIP_LEFT_RIGHT)
put_screen(rhd, [mirror(SCREEN)[1], mirror(SCREEN)[0], mirror(SCREEN)[3], mirror(SCREEN)[2]])
# instrument cluster: its picture slides across unchanged (the screen is seen at the same angle on both sides), behind the mirrored steering wheel
ra = np.asarray(rhd).astype(np.float32) / 255; la_ = np.asarray(base).astype(np.float32) / 255
sh = int(W - 917 - 752)                                                       # the lit area goes from 752..917 to W-917..W-752
vr = poly_mask((W, H), mirror(CL_V), 0.6); vl = np.roll(poly_mask((W, H), grow(CL_V, -1.0), 0.6), sh, axis=1)
srcp = np.roll(la_, sh, axis=1)
content = srcp * vl[..., None] + ra * (1 - vl[..., None])                     # where the rim hid the original, the mirrored picture stays
ra = ra * (1 - vr[..., None]) + content * vr[..., None]
rhd = Image.fromarray((np.clip(ra, 0, 1) * 255 + 0.5).astype(np.uint8))
# MG badge: every point of the logo goes where its mirror twin is, so that the letters read normally in the mirrored perspective
out_pts = [(W - BADGE[PARTNER[i]][0], BADGE[PARTNER[i]][1]) for i in range(8)]
bx0 = int(min(p[0] for p in out_pts)) - 8; by0 = int(min(p[1] for p in out_pts)) - 8; bx1 = int(max(p[0] for p in out_pts)) + 9; by1 = int(max(p[1] for p in out_pts)) + 9
co = coeffs([(x - bx0, y - by0) for x, y in out_pts], BADGE)
lay = base.transform((bx1 - bx0, by1 - by0), Image.PERSPECTIVE, tuple(co), Image.BICUBIC)
bm = poly_mask((bx1 - bx0, by1 - by0), [(x - bx0, y - by0) for x, y in grow(mirror(BADGE), 3.0)], 1.2)
rhd.paste(lay, (bx0, by0), Image.fromarray((bm * 255 + 0.5).astype(np.uint8)))
rhd.save(f"{dst}/cabin_r.webp", quality=88, method=6)
print('cabin', lhd.size, 'neutralised', round(float(w.mean()) * 100, 1), '% of the picture; cluster shift', sh)
lhd.resize((1600, 600), Image.LANCZOS).save(f"{dst}/../_cab_l.png"); rhd.resize((1600, 600), Image.LANCZOS).save(f"{dst}/../_cab_r.png")
for nm, img, box in (('l', lhd, (620, 400, 1420, 730)), ('r', rhd, (980, 400, 1780, 730))): img.crop(box).resize((1600, 660), Image.LANCZOS).save(f"{dst}/../_cabz_{nm}.png")
