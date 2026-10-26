#!/usr/bin/env python3
"""FINAL icon selection.

Previous rounds failed for a measurement reason, not a design reason: `min_feature`
took the 2nd percentile of 2*distance over ALL ink pixels, which is dominated by the
outermost ring of pixels (distance 1 by definition). It reported 2.0 px for every
design including ones with 10 px walls.

Replaced with a morphological erosion test, which cannot be fooled by boundaries:

    erode(ink, r) = pixels whose distance-to-background > r
    a mark "has thickness >= 2r" iff a meaningful share of it survives erode(ink, r)

`solid_r` = the largest r for which >=15% of the original ink survives. That is the
radius of the thickest inscribed structure, in px. No percentiles, no edge artefacts.

Legibility is then checked the only way that matters: downsample to launcher sizes
(24/32/48 dp) and require the mark to stay one blob with usable ink.
"""
import math
from raster import rasterise, metrics, blobs_at, rounded_rect, circle, ngon
from round5 import distance_transform, fit_to_radius

ACCENT = "#35D0BA"

def erosion_profile(cov):
    """Largest r (px) where >=15% of ink survives erosion by r. A true thickness."""
    size = len(cov)
    d = distance_transform(cov)
    ink = [(x, y) for y in range(size) for x in range(size) if cov[y][x] > 0.5]
    if not ink:
        return 0.0
    total = len(ink)
    best = 0.0
    r = 0.5
    while r < 20:
        surviving = sum(1 for x, y in ink if d[y][x] > r)
        if surviving / total < 0.15:
            break
        best = r
        r += 0.5
    return best

def downscale_stats(cov, target):
    size = len(cov)
    f = size / target
    small = [[0.0] * target for _ in range(target)]
    for y in range(target):
        for x in range(target):
            acc = n = 0.0
            y0, y1 = int(y * f), max(int(y * f) + 1, int((y + 1) * f))
            x0, x1 = int(x * f), max(int(x * f) + 1, int((x + 1) * f))
            for yy in range(y0, min(y1, size)):
                for xx in range(x0, min(x1, size)):
                    acc += cov[yy][xx]; n += 1
            small[y][x] = acc / n if n else 0.0
    ink = {(x, y) for y in range(target) for x in range(target) if small[y][x] > 0.35}
    seen, blobs = set(), 0
    for p in ink:
        if p in seen:
            continue
        blobs += 1
        stack = [p]; seen.add(p)
        while stack:
            x, y = stack.pop()
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    q = (x + dx, y + dy)
                    if q in ink and q not in seen:
                        seen.add(q); stack.append(q)
    return len(ink) / (target * target), blobs

# ---------------------------------------------------------------- candidates
def k_solid():
    return [[(34, 26), (46, 26), (46, 47), (63, 26), (76, 26),
             (55, 54), (77, 82), (63, 82), (46, 59), (46, 82), (34, 82)]]

def k_die():
    """Die (rounded square) with a K cut out. One shape, thick walls, no thin strokes."""
    outer = rounded_rect(54, 54, 62, 62, 10)
    inner = [(41, 32), (51, 32), (51, 48), (64, 32), (75, 32),
             (58, 54), (76, 76), (64, 76), (51, 59), (51, 76), (41, 76)]
    return [outer, inner]

def hex_ring_core():
    return [ngon(54, 54, 30, 6), ngon(54, 54, 18, 6), ngon(54, 54, 9, 6)]

def bars_baseline():
    bars = []
    for i, h in enumerate((26, 38, 50, 34)):
        x = 29 + i * 14
        bars.append(rounded_rect(x + 5.5, 74 - h / 2, 11, h, 3.5))
    bars.append(rounded_rect(54, 80, 56, 10, 5))
    return bars

def circle_k():
    return [circle(54, 54, 31),
            [((x - 54) * 0.84 + 54, (y - 54) * 0.84 + 54) for x, y in k_solid()[0]]]

def die_only():
    """Just the die with a square core: maximum simplicity, survives any size."""
    return [rounded_rect(54, 54, 62, 62, 12),
            rounded_rect(54, 54, 26, 26, 5)]

CANDS = {
    "k_solid": k_solid,
    "k_die": k_die,
    "hex_ring_core": hex_ring_core,
    "bars_baseline": bars_baseline,
    "circle_k": circle_k,
    "die_core": die_only,
}

print(f"{'candidate':14} {'ink%':>6} {'maxR':>6} {'solid_r':>8} {'24dp':>12} {'32dp':>12}  verdict")
print("-" * 84)
rows = []
for name, fn in CANDS.items():
    contours = fit_to_radius(fn(), 32.0)
    g = rasterise(contours)
    m = metrics(g)
    sr = erosion_profile(m["cov"])
    ink24, b24 = downscale_stats(m["cov"], 24)
    ink32, b32 = downscale_stats(m["cov"], 32)
    fails = []
    if m["maxr"] > 33.0: fails.append(f"r{m['maxr']:.1f}")
    if sr < 3.0: fails.append(f"solid {sr:.1f}px")
    if not (0.12 <= m["coverage"] <= 0.40): fails.append(f"ink{m['coverage']*100:.0f}%")
    if b24 != 1: fails.append(f"{b24}blob@24")
    if b32 != 1: fails.append(f"{b32}blob@32")
    if ink24 < 0.10: fails.append(f"faint{ink24*100:.0f}%")
    rows.append((name, m, sr, ink24, b24, ink32, b32, fails))
    print(f"{name:14} {m['coverage']*100:5.1f}% {m['maxr']:6.1f} {sr:8.1f} "
          f"{ink24*100:5.1f}%/{b24}blob {ink32*100:5.1f}%/{b32}blob  "
          f"{'PASS' if not fails else '; '.join(fails)}")

print()
passed = [r for r in rows if not r[7]]
print("passed:", [r[0] for r in passed] or "none")
if passed:
    # tie-break: most solid structure, then best ink balance near 20%
    best = max(passed, key=lambda r: (r[2], -abs(r[1]["coverage"] - 0.20)))
    print(f"\nWINNER: {best[0]}  (solid_r={best[2]:.1f}px, ink={best[1]['coverage']*100:.1f}%, "
          f"maxR={best[1]['maxr']:.1f})")
