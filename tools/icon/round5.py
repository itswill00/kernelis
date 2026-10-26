#!/usr/bin/env python3
"""Round 5: exact thickness via signed distance (chamfer 3-4), plus a proper
'is the mark still legible at 24 dp' test.

Round 4's thickness number was 2.0 px for every candidate, including designs whose
smallest feature is visibly ~8 px. Cause: a 4-neighbour BFS gives Manhattan distance,
and the 5th-percentile was dominated by the outermost ring of ink pixels (distance 1
by definition). The metric was measuring its own edge, not the design.

Fixed here:
  - Chamfer 3-4 distance transform (near-Euclidean, isotropic enough for this job)
  - thickness is measured as the MEDIAN over the ink skeleton, i.e. ridge pixels
    only, so boundaries cannot skew it
"""
import math
from raster import rasterise, metrics, blobs_at, rounded_rect, circle, ngon

ACCENT = "#35D0BA"

def distance_transform(cov):
    """Chamfer 3-4 distance to nearest background pixel, in px (approx Euclidean)."""
    size = len(cov)
    BIG = 1e9
    d = [[BIG] * size for _ in range(size)]
    for y in range(size):
        for x in range(size):
            if cov[y][x] <= 0.5:
                d[y][x] = 0.0
    # forward pass
    for y in range(size):
        for x in range(size):
            if d[y][x] == 0:
                continue
            best = d[y][x]
            if y > 0:
                best = min(best, d[y-1][x] + 3)
                if x > 0: best = min(best, d[y-1][x-1] + 4)
                if x < size-1: best = min(best, d[y-1][x+1] + 4)
            if x > 0:
                best = min(best, d[y][x-1] + 3)
            d[y][x] = best
    # backward pass
    for y in range(size-1, -1, -1):
        for x in range(size-1, -1, -1):
            if d[y][x] == 0:
                continue
            best = d[y][x]
            if y < size-1:
                best = min(best, d[y+1][x] + 3)
                if x > 0: best = min(best, d[y+1][x-1] + 4)
                if x < size-1: best = min(best, d[y+1][x+1] + 4)
            if x < size-1:
                best = min(best, d[y][x+1] + 3)
            d[y][x] = best
    return [[v / 3.0 for v in row] for row in d]   # back to px units

def ridge_thickness(cov):
    """Median local thickness (2*distance) over ridge pixels of the ink."""
    size = len(cov)
    d = distance_transform(cov)
    vals = []
    for y in range(1, size-1):
        for x in range(1, size-1):
            if cov[y][x] <= 0.5:
                continue
            here = d[y][x]
            # ridge pixel: at least as far from background as all 4 neighbours
            if here >= d[y-1][x] and here >= d[y+1][x] and here >= d[y][x-1] and here >= d[y][x+1]:
                if here > 0.5:
                    vals.append(2.0 * here)
    if not vals:
        return 0.0
    vals.sort()
    return vals[len(vals) // 2]

def min_feature(cov):
    """Thinnest meaningful feature: 2nd percentile of 2*distance over ALL ink."""
    size = len(cov)
    d = distance_transform(cov)
    vals = sorted(2.0 * d[y][x] for y in range(size) for x in range(size) if cov[y][x] > 0.5)
    if not vals:
        return 0.0
    return vals[max(0, int(len(vals) * 0.02))]

def fit_to_radius(contours, maxr=32.0):
    cx = cy = 54.0
    worst = max(math.hypot(x - cx, y - cy) for c in contours for x, y in c)
    if worst <= maxr:
        return contours
    s = maxr / worst
    return [[((x - cx) * s + cx, (y - cy) * s + cy) for x, y in c] for c in contours]

# ---------------------------------------------------------------- designs
def k_solid():
    return [[(34, 26), (46, 26), (46, 47), (63, 26), (76, 26),
             (55, 54), (77, 82), (63, 82), (46, 59), (46, 82), (34, 82)]]

def k_die_solid():
    outer = rounded_rect(54, 54, 60, 60, 9)
    inner = [(42, 33), (51, 33), (51, 49), (63, 33), (73, 33),
             (58, 54), (74, 75), (63, 75), (51, 59), (51, 75), (42, 75)]
    return [outer, inner]

def hex_module():
    return [ngon(54, 54, 30, 6), ngon(54, 54, 17, 6), ngon(54, 54, 9, 6)]

def core_bars():
    bars = []
    for i, h in enumerate((26, 38, 50, 34)):
        x = 30 + i * 14
        bars.append(rounded_rect(x + 5, 76 - h / 2, 11, h, 3.5))
    bars.append(rounded_rect(54, 80, 54, 9, 4.5))
    return bars

def ring_k():
    outer = circle(54, 54, 31)
    inner = [((x - 54) * 0.82 + 54, (y - 54) * 0.82 + 54) for x, y in k_solid()[0]]
    return [outer, inner]

def die_k_plus():
    """Die with a K cut out, and four bond pads outside — reads as 'chip + kernel'."""
    return k_die_solid()

CANDS = {
    "k_solid": k_solid,
    "k_die_solid": k_die_solid,
    "hex_module": hex_module,
    "core_bars": core_bars,
    "ring_k": ring_k,
}

print(f"{'candidate':13} {'ink%':>6} {'maxR':>6} {'ridge':>6} {'minFeat':>8} {'blobs24':>8}  verdict")
print("-" * 78)
passed = []
for name, fn in CANDS.items():
    contours = fit_to_radius(fn(), 32.0)
    g = rasterise(contours)
    m = metrics(g)
    blobs, _ = blobs_at(m["cov"])
    ridge = ridge_thickness(m["cov"])
    mf = min_feature(m["cov"])
    fails = []
    if m["maxr"] > 33.0: fails.append(f"r{m['maxr']:.1f}")
    if ridge < 6.0: fails.append(f"ridge {ridge:.1f}px")
    if mf < 2.5: fails.append(f"feat {mf:.1f}px")
    if not (0.12 <= m["coverage"] <= 0.40): fails.append(f"ink {m['coverage']*100:.0f}%")
    if blobs != 1: fails.append(f"{blobs} blobs")
    if not fails: passed.append(name)
    print(f"{name:13} {m['coverage']*100:5.1f}% {m['maxr']:6.1f} {ridge:6.1f} {mf:8.1f} "
          f"{blobs:8d}  {'PASS' if not fails else '; '.join(fails)}")

print()
print("passed:", passed or "none")
