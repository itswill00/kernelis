#!/usr/bin/env python3
"""Design bench: compose shapes with real boolean algebra and LOOK at the result.

`emit.py` can measure an icon but cannot judge it. This tool exists so shape decisions
are made by looking at a raster instead of guessing from pathData — that is exactly the
mistake that produced a mark which measured well and still looked generic.

Two things it fixes relative to the first version of this file:

  * Shapes are combined with union/subtract, not even-odd XOR. XOR silently punches a
    hole wherever two contours of the *same* ink overlap (a trace crossing a via), which
    is not what "draw a K out of traces" means.
  * Rasterising happens at the target resolution. The rasteriser's coordinate space is
    pixel space, so asking for 192 px without scaling the polygons renders a 108 px mark
    in the corner; upscaling that afterwards produces the dither seen in the first pass.

Usage:
    python3 design.py            # writes design_sheet.png (192 / 48 / 24 px per mark)
    python3 design.py --one NAME # writes one mark big, for close inspection
"""
import math
import os
import subprocess
import sys

from raster import _fill_polygon, coverage_grid, rounded_rect, circle, ngon, rotate, translate

HERE = os.path.dirname(os.path.abspath(__file__))
CANVAS = 108.0
BG = (0x11, 0x14, 0x17)
INK = (0x35, 0xD0, 0xBA)
SS = 4


# ---------------------------------------------------------------- shape algebra

class Mark:
    """Ink = union(plus) minus union(minus). Both lists hold polygons in 0..108."""

    def __init__(self, plus, minus=()):
        self.plus = list(plus)
        self.minus = list(minus)

    def render(self, px):
        """Coverage grid at px x px. Polygons are scaled from the 108 unit canvas."""
        s = px / CANVAS
        ink = bytearray(px * SS * px * SS)
        for poly in self.plus:
            part = bytearray(px * SS * px * SS)
            _fill_polygon(part, _scaled(poly, s), 1, px, ss=SS)
            for i, v in enumerate(part):
                if v:
                    ink[i] = 1
        if self.minus:
            for poly in self.minus:
                part = bytearray(px * SS * px * SS)
                _fill_polygon(part, _scaled(poly, s), 1, px, ss=SS)
                for i, v in enumerate(part):
                    if v:
                        ink[i] = 0
        return coverage_grid(ink, size=px)


def _scaled(poly, s):
    return [(x * s, y * s) for x, y in poly]


def _as_int(grid):
    return int.from_bytes(bytes(grid), "big")


def _from_int(value, n):
    return bytearray(value.to_bytes(n, "big"))


def box(poly, s):
    return _scaled(poly, s)


# ---------------------------------------------------------------- geometry helpers

def chamfered_die(cx, cy, w, chamfer, corners=("tl", "tr", "br", "bl")):
    """Chip package silhouette: a square with cut corners, optionally only some of them.

    Chamfers are what separate this from a stock rounded square. They also survive
    downscaling better than a large corner radius, which turns into a circle.
    """
    h = w / 2.0
    x0, y0, x1, y1 = cx - h, cy - h, cx + h, cy + h
    c = chamfer
    pts = []
    # clockwise from top-left chamfer end
    if "tl" in corners:
        pts += [(x0 + c, y0)]
    else:
        pts += [(x0, y0)]
    if "tr" in corners:
        pts += [(x1 - c, y0), (x1, y0 + c)]
    else:
        pts += [(x1, y0)]
    if "br" in corners:
        pts += [(x1, y1 - c), (x1 - c, y1)]
    else:
        pts += [(x1, y1)]
    if "bl" in corners:
        pts += [(x0 + c, y1), (x0, y1 - c)]
    else:
        pts += [(x0, y1)]
    if "tl" in corners:
        pts += [(x0, y0 + c)]
    return pts


def k_mark(scale=1.0, cx=54.0, cy=54.0, stem=11.0, height=44.0, arm=1.0):
    """Bold K. `arm` > 1 lengthens the arms, letting them break the container edge."""
    hw = stem / 2.0
    top = cy - height / 2.0
    bot = cy + height / 2.0
    mid = cy
    x0 = cx - hw
    x1 = cx + hw
    # junction points on the stem, kept clear of the stem ends
    j_up = mid - 6.0
    j_dn = mid + 6.0
    reach = (height / 2.0) * arm
    ax = cx + hw + reach * 0.62
    ay = top
    bx = cx + hw + reach * 0.72
    by = bot
    pts = [
        (x0, top), (x1, top), (x1, j_up),
        (ax, ay), (ax + stem * 1.05, ay + stem * 0.55),
        (cx + hw * 0.4, mid),
        (bx + stem * 1.0, by - stem * 0.4), (bx, by),
        (x1, j_dn), (x1, bot), (x0, bot),
    ]
    return [((x - cx) * scale + cx, (y - cy) * scale + cy) for x, y in pts]


def trace_k(cx=54.0, cy=54.0, scale=1.0):
    """K as PCB traces: rectangular stem, stepped arms, round pads at the ends."""
    t = 5.5                      # half-width of a trace
    h = 23.0                     # half height
    stem = [(cx - t, cy - h), (cx + t, cy - h), (cx + t, cy + h), (cx - t, cy + h)]
    # stepped arm: horizontal out of the stem, then diagonal up
    up1 = [(cx + t, cy - 5), (cx + 13, cy - 5), (cx + 13, cy - 11),
           (cx + 13 + t * 2, cy - 11), (cx + 13 + t * 2, cy - 5 + t * 2), (cx + t, cy - 5 + t * 2)]
    arm_up = [(cx + t, cy - 4), (cx + 15, cy - 18), (cx + 15 + t * 1.6, cy - 18 + t * 1.6),
              (cx + t, cy - 4 + t * 1.6)]
    arm_dn = [(cx + t, cy + 4), (cx + 15, cy + 18), (cx + 15 + t * 1.6, cy + 18 - t * 1.6),
              (cx + t, cy + 4 - t * 1.6)]
    pads = [circle(cx, cy - h, t), circle(cx, cy + h, t),
            circle(cx + 15, cy - 18, t * 1.05), circle(cx + 15, cy + 18, t * 1.05)]
    shapes = [stem, arm_up, arm_dn] + pads
    return [[((x - cx) * scale + cx, (y - cy) * scale + cy) for x, y in s] for s in shapes]


def k_slab(cx=54.0, cy=54.0, scale=1.0):
    """K as three slabs with a slot through the stem — reads as a machined part."""
    stem_top = [(cx - 7, cy - 23), (cx + 7, cy - 23), (cx + 7, cy - 7), (cx - 7, cy - 7)]
    stem_bot = [(cx - 7, cy + 7), (cx + 7, cy + 7), (cx + 7, cy + 23), (cx - 7, cy + 23)]
    up = [(cx - 7, cy - 1), (cx + 7, cy - 1), (cx + 24, cy - 23), (cx + 10, cy - 23)]
    dn = [(cx - 7, cy + 1), (cx + 10, cy + 23), (cx + 24, cy + 23), (cx + 7, cy + 1)]
    shapes = [stem_top, stem_bot, up, dn]
    return [[((x - cx) * scale + cx, (y - cy) * scale + cy) for x, y in s] for s in shapes]


# ---------------------------------------------------------------- candidates

def c_octa_k():
    """Chamfered chip package, K cut out. Chamfer is the character."""
    return Mark([chamfered_die(54, 54, 66, 15)], [k_mark(0.80)])


def c_octa_k_big():
    return Mark([chamfered_die(54, 54, 70, 18)], [k_mark(0.88)])


def c_octa_notch():
    """One chamfer deeper than the others: pin-1 marker, asymmetric silhouette."""
    h = 33.0
    x0, y0 = 54 - h, 54 - h
    c, big = 13.0, 24.0
    pts = [(x0 + big, y0), (x0 + 2 * h - c, y0), (x0 + 2 * h, y0 + c),
           (x0 + 2 * h, y0 + 2 * h - c), (x0 + 2 * h - c, y0 + 2 * h),
           (x0 + c, y0 + 2 * h), (x0, y0 + 2 * h - c), (x0, y0 + big)]
    return Mark([pts], [k_mark(0.78)])


def c_k_breaks():
    """K's arms cut through the die edge: the mark and the container become one shape."""
    return Mark([chamfered_die(54, 54, 66, 14)], [k_mark(0.86, arm=1.9)])


def c_octa_slot():
    """Die with a socket slot cut from the right edge, K negative."""
    die = chamfered_die(54, 54, 66, 14)
    slot = [(84, 46), (94, 46), (94, 62), (84, 62)]
    return Mark([die], [k_mark(0.78), slot])


def c_trace_k():
    """No container: K drawn as PCB traces with pads. Maximum contrast at 24 dp."""
    return Mark(trace_k(scale=1.02))


def c_trace_k_die():
    return Mark([chamfered_die(54, 54, 66, 13)], [k_mark(0.78)])


def c_slab_k():
    """Machined K with a slot through the stem: heavy, industrial, no container."""
    return Mark(k_slab(scale=1.05))


def c_slab_k_die():
    die = chamfered_die(54, 54, 66, 13)
    return Mark([die], k_slab(scale=0.82))


def c_hex_k():
    return Mark([ngon(54, 54, 33, 6, rot=-90)], [k_mark(0.78)])


def c_die_pins():
    """Chip with contact pads on all four edges: recognisably hardware, not a letter."""
    d = 52.0
    die = chamfered_die(54, 54, d, 10)
    pins = []
    for i in range(3):
        off = -15 + i * 15
        pins.append([(54 + off - 4.5, 54 - d / 2 - 8), (54 + off + 4.5, 54 - d / 2 - 8),
                     (54 + off + 4.5, 54 - d / 2 + 1), (54 + off - 4.5, 54 - d / 2 + 1)])
        pins.append([(54 + off - 4.5, 54 + d / 2 - 1), (54 + off + 4.5, 54 + d / 2 - 1),
                     (54 + off + 4.5, 54 + d / 2 + 8), (54 + off - 4.5, 54 + d / 2 + 8)])
        pins.append([(54 - d / 2 - 8, 54 + off - 4.5), (54 - d / 2 + 1, 54 + off - 4.5),
                     (54 - d / 2 + 1, 54 + off + 4.5), (54 - d / 2 - 8, 54 + off + 4.5)])
        pins.append([(54 + d / 2 - 1, 54 + off - 4.5), (54 + d / 2 + 8, 54 + off - 4.5),
                     (54 + d / 2 + 8, 54 + off + 4.5), (54 + d / 2 - 1, 54 + off + 4.5)])
    return Mark([die] + pins, [k_mark(0.62)])


def c_current():
    return Mark([rounded_rect(54, 54, 64, 64, 64 * 0.16)], [k_poly_old()])


def k_poly_old(scale=0.82, cx=54.0, cy=54.0):
    pts = [(41, 32), (51, 32), (51, 48), (64, 32), (75, 32),
           (58, 54), (76, 76), (64, 76), (51, 59), (51, 76), (41, 76)]
    return [((x - cx) * scale + cx, (y - cy) * scale + cy) for x, y in pts]


CANDIDATES = [
    ("current", c_current),
    ("octa_k", c_octa_k),
    ("octa_k_big", c_octa_k_big),
    ("octa_notch", c_octa_notch),
    ("k_breaks", c_k_breaks),
    ("octa_slot", c_octa_slot),
    ("trace_k", c_trace_k),
    ("slab_k", c_slab_k),
    ("slab_k_die", c_slab_k_die),
    ("hex_k", c_hex_k),
    ("die_pins", c_die_pins),
]


# ---------------------------------------------------------------- output

def composite(cov, ink=INK, bg=BG):
    return [[tuple(int(bg[i] + (ink[i] - bg[i]) * c) for i in range(3)) for c in row]
            for row in cov]


def downscale(cov, target):
    size = len(cov)
    f = size / target
    out = []
    for y in range(target):
        y0 = int(y * f)
        y1 = max(y0 + 1, min(size, int((y + 1) * f)))
        row = []
        for x in range(target):
            x0 = int(x * f)
            x1 = max(x0 + 1, min(size, int((x + 1) * f)))
            acc = 0.0
            n = 0
            for yy in range(y0, y1):
                for xx in range(x0, x1):
                    acc += cov[yy][xx]
                    n += 1
            row.append(acc / n if n else 0.0)
        out.append(row)
    return out


def write_ppm(path, rows):
    w = max(len(r) for r in rows)
    h = len(rows)
    with open(path, "wb") as f:
        f.write(f"P6\n{w} {h}\n255\n".encode())
        buf = bytearray()
        for row in rows:
            for p in row:
                buf += bytes(p)
            if len(row) < w:
                buf += bytes(BG) * (w - len(row))
        f.write(buf)


def tile(mark, big=176):
    cov = mark.render(big)
    rows = [list(r) for r in composite(cov)]
    small = composite(downscale(cov, 44))
    tiny = composite(downscale(cov, 22))
    for y in range(min(len(small), len(tiny))):
        line = list(small[y]) + [BG] * 12 + list(tiny[y])
        line += [BG] * (big - len(line))
        rows.append(line)
    return rows


def sheet(path, big=176, gap=10, per_row=6):
    tiles = [(name, tile(fn(), big)) for name, fn in CANDIDATES]
    h = max(len(t) for _, t in tiles)
    for _, t in tiles:
        t.extend([[BG] * big for _ in range(h - len(t))])
    rows = []
    for start in range(0, len(tiles), per_row):
        chunk = tiles[start:start + per_row]
        row_w = gap + len(chunk) * (big + gap)
        canvas = [[(10, 12, 14)] * row_w for _ in range(h + gap)]
        for i, (_, t) in enumerate(chunk):
            x0 = gap + i * (big + gap)
            for y in range(h):
                for x in range(big):
                    canvas[y][x0 + x] = t[y][x]
        rows.extend(canvas)
    ppm = os.path.join(HERE, "design_sheet.ppm")
    write_ppm(ppm, rows)
    subprocess.run(["convert", ppm, path], check=True)
    print("wrote", path)
    print("order:", ", ".join(n for n, _ in CANDIDATES))


if __name__ == "__main__":
    if "--one" in sys.argv:
        name = sys.argv[sys.argv.index("--one") + 1]
        fn = dict(CANDIDATES)[name]
        out = os.path.join(HERE, f"one_{name}.png")
        rows = tile(fn(), 360)
        ppm = os.path.join(HERE, "one.ppm")
        write_ppm(ppm, rows)
        subprocess.run(["convert", ppm, out], check=True)
        print("wrote", out)
    else:
        sheet(os.path.join(HERE, "design_sheet.png"))
