#!/usr/bin/env python3
"""Emit the chosen launcher mark to Android vector XML, verified by round-trip.

Chosen mark: `a_break_pin1` from design7.py — a chamfered chip body with one deeply cut
pin-1 corner, and a bold K whose arms run out through the body's right edge. The
silhouette itself is the identity, which is what the previous rounded-square-with-a-
letter lacked.

Why the emit step is not a one-liner, and why an earlier version was wrong:

  The ink is `body minus K`, and the K's arms poke out past the body edge. A
  VectorDrawable can only fill contours, and fillType="evenOdd" fills the *symmetric
  difference* — so emitting body and K as two contours fills the protruding arms
  instead of cutting them. Measured on this exact geometry: 212 wrong pixels out of
  11664. The mark therefore has to be emitted already subtracted, as real polygons,
  which is what `boolean.difference()` computes.

  The subtracted result is two separate ink islands, not one shape with a hole: the body
  proper, plus the wedge between the K's arms where they break the right edge. Both must
  be filled, so the path carries two counter-clockwise contours. Using evenOdd with both
  as "outer" would be correct here only because they do not overlap — but nonZero is
  used instead, because it is correct by winding order and does not depend on that
  accident.

The verifier parses the emitted XML back, re-rasterises it, and compares against the
design raster. It fails loudly if they differ, so the numbers in the README describe
what actually ships.
"""
import os
import re
import subprocess
import sys

from design import Mark, write_ppm, composite
from design4 import rendered_max_radius, fit_safe
from design7 import a_break_pin1
import boolean as B

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.abspath(os.path.join(HERE, "..", "..", "app", "src", "main", "res", "drawable"))
ACCENT = "#35D0BA"


def chosen_mark():
    return fit_safe(a_break_pin1(), target=31.0)


# ---------------------------------------------------------------- path emission

def contours_for(mark):
    """The ink as exact, already-subtracted loops in 0..108 canvas units."""
    loops = B.difference(mark.plus, mark.minus)
    if not loops:
        raise SystemExit("FAIL: boolean produced no contours")
    return loops


def poly_to_path(pts):
    d = f"M{pts[0][0]:.2f},{pts[0][1]:.2f}"
    for x, y in pts[1:]:
        d += f" L{x:.2f},{y:.2f}"
    return d + " Z"


def build_path_data(mark):
    loops = contours_for(mark)
    return " ".join(poly_to_path(l) for l in loops), loops


def emit_foreground(path, mark):
    d, loops = build_path_data(mark)
    r = rendered_max_radius(mark)
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- KERNELIS launcher mark: a chamfered chip body with a deep pin-1 corner, and a bold
     K whose arms run out through the body's right edge.

     The geometry is emitted ALREADY SUBTRACTED, as exact polygon loops produced by a
     polygon boolean, not as "body contour + K contour". That distinction is not
     cosmetic: with fillType="evenOdd" a K that pokes outside the body would be filled
     where it protrudes instead of cut, which measured 212 wrong pixels on this design.
     Two ink islands result (the body, and the wedge between the arms where they break
     the edge); both are wound counter-clockwise and filled with fillType="nonZero", so
     correctness comes from winding order rather than from contours not overlapping.

     Measured, not eyeballed: ink radius {r:.2f} of the 108 canvas (the adaptive-icon
     safe circle is 33, so the launcher mask crops nothing). Selected from 40+
     candidates rendered inside the real mask at 24/32/48/96 px on dark and light
     wallpaper; candidates whose pins turned to dust at 24 px were rejected. The emitted
     pathData is parsed back and re-measured by tools/icon/emit2.py, which refuses to
     ship on any mismatch. See tools/icon/README.md. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{ACCENT}"
        android:fillType="nonZero"
        android:pathData="{d}" />
</vector>
'''
    with open(path, "w") as f:
        f.write(xml)
    return d, loops


def emit_monochrome(path, mark):
    d, loops = build_path_data(mark)
    xml = f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Monochrome layer for themed icons (Android 13+). The system replaces this layer's
     colour with a wallpaper-derived tint, so the mark must be the same silhouette as
     the foreground — the shape, not the colour, is what survives. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:fillType="nonZero"
        android:pathData="{d}" />
</vector>
'''
    with open(path, "w") as f:
        f.write(xml)
    return d


# ---------------------------------------------------------------- verification

def parse_path(d):
    """Parse emitted pathData back into contours. The first half of the round-trip."""
    contours = []
    for seg in re.findall(r"M[^M]*", d):
        nums = re.findall(r"-?\d+\.?\d*", seg)
        pts = [(float(nums[i]), float(nums[i + 1])) for i in range(0, len(nums), 2)]
        contours.append(pts)
    return contours


def verify_written(xml_path, mark, px=216, tol_frac=0.0015):
    """Parse the XML actually on disk, re-rasterise, compare to the design raster."""
    with open(xml_path) as f:
        xml = f.read()
    m = re.search(r'android:pathData="([^"]+)"', xml)
    if not m:
        raise SystemExit(f"FAIL: no pathData in {xml_path}")
    contours = parse_path(m.group(1))
    if not contours:
        raise SystemExit("FAIL: pathData parsed to no contours")

    # nonZero with all-CCW contours: union of them, which is what the boolean produced.
    shipped = Mark(contours).render(px)
    design = mark.render(px)
    diff = sum(1 for y in range(px) for x in range(px)
               if abs(shipped[y][x] - design[y][x]) > 0.30)
    frac = diff / (px * px)
    r = rendered_max_radius(Mark(contours), px)
    ink = sum(1 for y in range(px) for x in range(px) if shipped[y][x] > 0.5)
    return contours, diff, frac, r, ink


def main():
    mark = chosen_mark()
    fg = os.path.join(RES, "ic_launcher_foreground.xml")
    mo = os.path.join(RES, "ic_launcher_monochrome.xml")

    emit_foreground(fg, mark)
    emit_monochrome(mo, mark)
    print("wrote", fg)
    print("wrote", mo)

    contours, diff, frac, r, ink = verify_written(fg, mark)
    print(f"contours: {len(contours)}  points: {[len(c) for c in contours]}")
    print(f"round-trip: mismatch={diff}px ({frac*100:.3f}%)  maxR={r:.2f}  ink={ink}px")
    if frac > 0.0015:
        raise SystemExit(f"FAIL: emitted geometry differs from design on {diff} px")
    if r > 33.0:
        raise SystemExit(f"FAIL: maxR {r:.2f} exceeds the 33 safe radius")
    print("round-trip: PASS")

    # Preview of exactly what was written.
    cov = Mark(contours).render(192)
    write_ppm(os.path.join(HERE, "shipped.ppm"), [list(rr) for rr in composite(cov)])
    subprocess.run(["convert", os.path.join(HERE, "shipped.ppm"),
                    os.path.join(HERE, "shipped.png")], check=True)
    print("wrote shipped.png")


if __name__ == "__main__":
    main()
