#!/usr/bin/env python3
"""Round 4: refine the chip family and measure the adaptive-icon safe zone.

Round 3 showed the chip-with-pins silhouette has the character the rounded square
lacked: the outline alone says "hardware". But several of those candidates put pin
tips outside the adaptive-icon safe circle (radius 33 of the 108 canvas), which the
launcher crops. So this round fits geometry to the circle *before* looking at it, and
prints the measured max radius next to every candidate.

The other lesson from round 3 is about feature size, not shape: at 22 px the dense
pin grids turn into noise. So pins are fewer and thicker here, and the K is bolder.
"""
import math
import os
import subprocess
import sys

from design import Mark, BG, composite, downscale, write_ppm, chamfered_die, circle
from raster import rounded_rect

HERE = os.path.dirname(os.path.abspath(__file__))
C = 54.0
SAFE = 33.0


# ---------------------------------------------------------------- primitives

def band(x0, y0, x1, y1, w):
    dx, dy = x1 - x0, y1 - y0
    length = math.hypot(dx, dy)
    ux, uy = dx / length, dy / length
    nx, ny = -uy * w / 2.0, ux * w / 2.0
    return [(x0 + nx, y0 + ny), (x1 + nx, y1 + ny),
            (x1 - nx, y1 - ny), (x0 - nx, y0 - ny)]


def k_bands(cx=54.0, cy=54.0, stem=13.0, half_h=26.0, arm_w=13.0,
            arm_reach=1.0, gap=5.0, angle=45.0, arm_start=0.30):
    """K as three unioned bands. `arm_start` pulls the arms out from the stem centre."""
    top, bot = cy - half_h, cy + half_h
    length = half_h * 1.42 * arm_reach
    a = math.radians(angle)
    ux, uy = math.cos(a), math.sin(a)
    jx = cx + (stem / 2.0) * arm_start
    up = band(jx, cy - gap, jx + length * ux, cy - gap - length * uy, arm_w)
    dn = band(jx, cy + gap, jx + length * ux, cy + gap + length * uy, arm_w)
    return [band(cx, top, cx, bot, stem), up, dn]


def pin_strip(cx, cy, body, count, length, width, edge, inset=0.0):
    """Pads along one edge. `edge` is 't','b','l','r'. Corners are never used."""
    h = body / 2.0
    span = body - 2 * inset
    out = []
    for i in range(count):
        off = -span / 2.0 + span * i / (count - 1) if count > 1 else 0.0
        if edge in ("t", "b"):
            s = -1 if edge == "t" else 1
            out.append([(cx + off - width / 2, cy + s * h), (cx + off + width / 2, cy + s * h),
                        (cx + off + width / 2, cy + s * (h + length)),
                        (cx + off - width / 2, cy + s * (h + length))])
        else:
            s = -1 if edge == "l" else 1
            out.append([(cx + s * h, cy + off - width / 2), (cx + s * (h + length), cy + off - width / 2),
                        (cx + s * (h + length), cy + off + width / 2),
                        (cx + s * h, cy + off + width / 2)])
    return out


def max_radius(contours):
    """Largest distance from centre to any vertex of any contour, in canvas units."""
    best = 0.0
    for poly in contours:
        for x, y in poly:
            best = max(best, math.hypot(x - C, y - C))
    return best


def mark_radius(mark):
    return max(max_radius(mark.plus), max_radius(mark.minus))


def rendered_max_radius(mark, px=108):
    """Max ink radius measured from the raster, not from vertices.

    Vertices are not ink: a chamfer vertex sits outside the ink by its whole offset,
    so fitting on vertices shrinks the mark more than needed. This measures the same
    thing the launcher crops on.
    """
    cov = mark.render(px)
    cx = cy = px / 2.0
    best = 0.0
    scale = CANVAS_UNITS / px
    for y, row in enumerate(cov):
        for x, c in enumerate(row):
            if c > 0.02:
                best = max(best, math.hypot(x + 0.5 - cx, y + 0.5 - cy) * scale)
    return best


CANVAS_UNITS = 108.0


def _scale_about_centre(polys, f, cx=54.0, cy=54.0):
    return [[((x - cx) * f + cx, (y - cy) * f + cy) for x, y in p] for p in polys]


def fit_safe(mark, target=32.0, iters=6):
    """Scale the whole mark about the centre until its measured ink radius <= target."""
    plus, minus = mark.plus, mark.minus
    for _ in range(iters):
        r = rendered_max_radius(Mark(plus, minus))
        if r <= target:
            break
        f = target / r
        plus = _scale_about_centre(plus, f)
        minus = _scale_about_centre(minus, f)
    return Mark(plus, minus)


# ---------------------------------------------------------------- candidates

def d_chip_pins4():
    """Chip, pins on four edges, 3 per edge, chunky. The strongest round-3 shape."""
    body = 44.0
    pin_len, pin_w = 8.0, 7.5
    pins = []
    for e in ("t", "b", "l", "r"):
        pins += pin_strip(54, 54, body, 3, pin_len, pin_w, e, inset=4.0)
    return Mark([chamfered_die(54, 54, body, 8)] + pins,
                k_bands(stem=10.0, half_h=15.0, arm_w=10.0, arm_reach=0.8, gap=4.0))


def d_chip_pins2():
    """Pins top and bottom only: like a real package, less noise at small sizes."""
    body = 48.0
    pins = pin_strip(54, 54, body, 3, 9.0, 8.5, "t", inset=6.0)
    pins += pin_strip(54, 54, body, 3, 9.0, 8.5, "b", inset=6.0)
    return Mark([chamfered_die(54, 54, body, 9)] + pins,
                k_bands(stem=11.0, half_h=17.0, arm_w=11.0, arm_reach=0.85, gap=4.0))


def d_chip_pins2_pin1():
    """Same, one corner chamfered much harder: the pin-1 marker becomes the identity."""
    body = 48.0
    h = body / 2.0
    x0, y0 = 54 - h, 54 - h
    small, deep = 9.0, 20.0
    body_pts = [(x0 + deep, y0), (x0 + body - small, y0), (x0 + body, y0 + small),
                (x0 + body, y0 + body - small), (x0 + body - small, y0 + body),
                (x0 + small, y0 + body), (x0, y0 + body - small), (x0, y0 + deep)]
    pins = pin_strip(54, 54, body, 3, 9.0, 8.5, "t", inset=6.0)
    pins += pin_strip(54, 54, body, 3, 9.0, 8.5, "b", inset=6.0)
    return Mark([body_pts] + pins,
                k_bands(stem=10.0, half_h=16.0, arm_w=10.5, arm_reach=0.85, gap=4.0))


def d_chip_sides():
    """Pins left and right only, tall body: a DIP package, narrower silhouette."""
    body = 46.0
    pins = pin_strip(54, 54, body, 4, 9.0, 7.0, "l", inset=5.0)
    pins += pin_strip(54, 54, body, 4, 9.0, 7.0, "r", inset=5.0)
    return Mark([rounded_rect(54, 54, body, body, 7)] + pins,
                k_bands(stem=10.0, half_h=16.0, arm_w=10.0, arm_reach=0.82, gap=4.0))


def d_chip_lead_frame():
    """Chip inside a lead frame: pins touch a thin outer ring, so the mark is one piece."""
    body = 40.0
    pins = []
    for e in ("t", "b", "l", "r"):
        pins += pin_strip(54, 54, body, 3, 13.0, 7.0, e, inset=5.0)
    ring_outer = chamfered_die(54, 54, 74, 15)
    ring_inner = chamfered_die(54, 54, 66, 12)
    return Mark([ring_outer] + pins, [ring_inner] + k_bands(stem=9.0, half_h=13.0,
                                                           arm_w=9.0, arm_reach=0.8, gap=3.5))


def d_chip_k_alone():
    """No container: bold K with a 45-degree arm system, the purest option."""
    return Mark(k_bands(stem=18.0, half_h=30.0, arm_w=18.0, arm_reach=0.98, gap=6.0))


def d_chip_notch_ring():
    """Die with a notch cut into the top edge, K inside: reads as a socketed part."""
    body = 62.0
    notch = [(54 - 8, 54 - body / 2 - 1), (54 + 8, 54 - body / 2 - 1),
             (54 + 8, 54 - body / 2 + 11), (54 - 8, 54 - body / 2 + 11)]
    return Mark([chamfered_die(54, 54, body, 14)],
                k_bands(stem=12.0, half_h=20.0, arm_w=12.0, arm_reach=0.92, gap=4.5) + [notch])


CANDIDATES = [
    ("chip_pins4", d_chip_pins4),
    ("chip_pins2", d_chip_pins2),
    ("chip_pin1", d_chip_pins2_pin1),
    ("chip_dip", d_chip_sides),
    ("chip_leadframe", d_chip_lead_frame),
    ("k_alone", d_chip_k_alone),
    ("chip_notch", d_chip_notch_ring),
]


# ---------------------------------------------------------------- output

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


def sheet(path, big=176, gap=10, per_row=7):
    tiles = []
    for name, fn in CANDIDATES:
        m = fn()
        raw = rendered_max_radius(m)
        fitted = fit_safe(m, target=32.0)
        r = rendered_max_radius(fitted)
        print(f"{name:16} raw={raw:5.2f} fitted={r:5.2f}  {'OK' if r <= SAFE else 'CROPPED'}")
        tiles.append((name, tile(fitted, big)))
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
    ppm = os.path.join(HERE, "design4.ppm")
    write_ppm(ppm, rows)
    subprocess.run(["convert", ppm, path], check=True)
    print("wrote", path)
    print("order:", ", ".join(n for n, _ in CANDIDATES))


if __name__ == "__main__":
    if "--one" in sys.argv:
        name = sys.argv[sys.argv.index("--one") + 1]
        out = os.path.join(HERE, f"one_{name}.png")
        ppm = os.path.join(HERE, "one4.ppm")
        write_ppm(ppm, tile(dict(CANDIDATES)[name](), 340))
        subprocess.run(["convert", ppm, out], check=True)
        print("wrote", out)
    else:
        sheet(os.path.join(HERE, "design4.png"))
