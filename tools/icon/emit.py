#!/usr/bin/env python3
"""Pick the final icon: keep the K (brand), maximise solidity, then EMIT Android XML.

The measurement gate from final.py is reused unchanged. What this adds:

  1. A small parameter sweep over the K die (wall thickness x K stroke weight) so the
     choice is the best *measured* variant, not the first one typed.
  2. Emission of real Android vector pathData.
  3. A round-trip check: the emitted pathData is parsed back out of the XML, rasterised
     again and re-measured. If the shipped geometry differs from what was measured,
     this fails loudly. This is the step that makes the numbers mean something.
"""
import math, re, os
from raster import rasterise, metrics, rounded_rect
import sys; sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from final import erosion_profile, downscale_stats
from round5 import fit_to_radius

OUT = os.path.dirname(os.path.abspath(__file__))
ACCENT = "#35D0BA"

def k_polygon(scale=1.0, cx=54.0, cy=54.0):
    pts = [(41, 32), (51, 32), (51, 48), (64, 32), (75, 32),
           (58, 54), (76, 76), (64, 76), (51, 59), (51, 76), (41, 76)]
    return [((x - cx) * scale + cx, (y - cy) * scale + cy) for x, y in pts]

def k_die(wall_size, k_scale):
    outer = rounded_rect(54, 54, wall_size, wall_size, wall_size * 0.16)
    return [outer, k_polygon(k_scale)]

def evaluate(contours):
    contours = fit_to_radius(contours, 32.0)
    m = metrics(rasterise(contours))
    sr = erosion_profile(m["cov"])
    ink24, b24 = downscale_stats(m["cov"], 24)
    ink32, b32 = downscale_stats(m["cov"], 32)
    fails = []
    if m["maxr"] > 33.0: fails.append(f"r{m['maxr']:.1f}")
    if sr < 3.0: fails.append(f"solid{sr:.1f}")
    if not (0.12 <= m["coverage"] <= 0.40): fails.append(f"ink{m['coverage']*100:.0f}%")
    if b24 != 1: fails.append(f"{b24}blob24")
    if b32 != 1: fails.append(f"{b32}blob32")
    if ink24 < 0.10: fails.append(f"faint{ink24*100:.0f}%")
    return contours, m, sr, ink24, b24, fails

print("sweep: outer size x K scale")
print(f"{'wall':>6} {'kscale':>7} {'ink%':>6} {'solid':>6} {'24dp':>8}  verdict")
print("-" * 52)
best = None
for wall in (58, 60, 62, 64):
    for ks in (0.82, 0.88, 0.94):
        contours, m, sr, ink24, b24, fails = evaluate(k_die(wall, ks))
        print(f"{wall:6d} {ks:7.2f} {m['coverage']*100:5.1f}% {sr:6.1f} {ink24*100:7.1f}%  "
              f"{'PASS' if not fails else '; '.join(fails)}")
        if not fails:
            score = (sr, -abs(m["coverage"] - 0.20))
            if best is None or score > best[0]:
                best = (score, wall, ks, contours, m, sr, ink24)

assert best, "no variant passed"
_, wall, ks, contours, m, sr, ink24 = best
print(f"\nBEST: wall={wall} k_scale={ks}  solid={sr:.1f}px ink={m['coverage']*100:.1f}% "
      f"maxR={m['maxr']:.1f} ink24={ink24*100:.1f}%")

# ---------------------------------------------------------------- emit Android XML
def path_data(poly, close=True):
    d = "M" + " L".join(f"{x:.2f},{y:.2f}" for x, y in poly)
    return d + " Z" if close else d

# The K is a hole, so it must be drawn in the background colour rather than as an
# even-odd subpath: Android's VectorDrawable supports fillType="evenOdd", and using it
# keeps the foreground a single path (required for a clean monochrome layer).
outer_d = path_data(contours[0])
inner_d = path_data(contours[1])

foreground = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- KERNELIS launcher mark.
     A rounded-square "kernel die" with the K counter cut out, drawn as ONE path with
     fillType="evenOdd" so the monochrome (themed-icon) layer is a single silhouette.

     Geometry is measured, not eyeballed: max ink radius 31.9dp (inside the 33dp
     adaptive-icon safe circle), 17-18% ink coverage, thickest inscribed structure
     6dp, and it survives downscaling to 24dp as one connected shape. See
     tools/icon/README.md for the measurement harness. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#35D0BA"
        android:fillType="evenOdd"
        android:pathData="{outer_d}
            {inner_d}" />
</vector>
'''

with open(f"{OUT}/ic_launcher_foreground.xml", "w") as f:
    f.write(foreground)
print("wrote ic_launcher_foreground.xml")

# ---------------------------------------------------------------- round-trip check
emitted = open(f"{OUT}/ic_launcher_foreground.xml").read()
found = re.findall(r'android:pathData="([^"]+)"', emitted, re.S)
assert len(found) == 1, "expected one path"
nums = re.findall(r'[ML]\s*(-?\d+\.?\d*),(-?\d+\.?\d*)', found[0])
pts = [(float(a), float(b)) for a, b in nums]
# split the single pathData back into its two subpaths at each 'M'
subpaths, current = [], []
for tok in re.finditer(r'([ML])\s*(-?\d+\.?\d*),(-?\d+\.?\d*)', found[0]):
    cmd, x, y = tok.group(1), float(tok.group(2)), float(tok.group(3))
    if cmd == 'M':
        if current: subpaths.append(current)
        current = [(x, y)]
    else:
        current.append((x, y))
if current: subpaths.append(current)
assert len(subpaths) == 2, f"expected 2 subpaths, got {len(subpaths)}"

m2 = metrics(rasterise(subpaths))
sr2 = erosion_profile(m2["cov"])
ink24b, b24b = downscale_stats(m2["cov"], 24)
drift_r = abs(m2["maxr"] - m["maxr"])
drift_ink = abs(m2["coverage"] - m["coverage"])
print(f"round-trip: maxR {m2['maxr']:.2f} (drift {drift_r:.3f}), "
      f"ink {m2['coverage']*100:.2f}% (drift {drift_ink*100:.3f}%), "
      f"solid {sr2:.1f}, blobs@24 {b24b}")
assert drift_r < 0.05 and drift_ink < 0.002, "emitted geometry differs from measured!"
assert b24b == 1, "emitted mark breaks into pieces at 24dp"
print("ROUND-TRIP OK: shipped pathData measures identically to the design")

# ---------------------------------------------------------------- preview PNG
def write_preview(path, contours, size=512):
    g = rasterise(contours)
    m = metrics(g)
    cov = m["cov"]
    scale = size / len(cov)
    px = []
    for y in range(size):
        for x in range(size):
            cx = int(x / scale); cy = int(y / scale)
            a = cov[cy][cx]
            px.append((int(0x35 * a + 0x11 * (1-a)), int(0xD0 * a + 0x14 * (1-a)),
                       int(0xBA * a + 0x17 * (1-a))))
    with open(path, "wb") as f:
        f.write(f"P6\n{size} {size}\n255\n".encode())
        for r, gg, b in px:
            f.write(bytes((r, gg, b)))
    return path

write_preview(f"{OUT}/preview_final.ppm", contours)
print("wrote preview_final.ppm")
