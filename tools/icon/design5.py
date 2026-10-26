#!/usr/bin/env python3
"""Round 5: judge the candidates the way a launcher actually shows them.

Every earlier sheet rendered the mark on a bare canvas. That is not what a user sees.
An adaptive icon is composited over the launcher background and then MASKED — usually
to a circle of roughly 72 dp diameter, on a wallpaper of unknown colour. Pins that look
crisp on black can vanish against a light wallpaper, and a mark whose ink radius is 32
looks cramped once the mask cuts at 36.

So this sheet shows, for each candidate:
  * masked to a circle at 96 px, on a DARK wallpaper
  * masked to a circle at 96 px, on a LIGHT wallpaper
  * the same at 48 px and 24 px on dark, which is the size that decides legibility

This is the only view that answers "does this hold up as an app icon".
"""
import math
import os
import subprocess
import sys

from design import Mark, composite, downscale, write_ppm, chamfered_die, circle
from design4 import rendered_max_radius, fit_safe, band
from raster import rounded_rect

HERE = os.path.dirname(os.path.abspath(__file__))
C = 54.0

ACCENT = (0x35, 0xD0, 0xBA)
DARK = (0x11, 0x14, 0x17)
LIGHT = (0xF2, 0xF4, 0xF6)


# ---------------------------------------------------------------- the K as ONE polygon

def k_outline(cx=54.0, cy=54.0, stem=13.0, half_h=26.0, arm_v=11.0,
              arm_reach=1.0, gap=0.0):
    """The K as a single simple polygon, wound clockwise, arms as parallelograms.

    Why one polygon instead of three unioned bands: the shipped vector should be one
    path, and neither fill rule can express a union of overlapping shapes. With evenOdd,
    overlapping bands cancel into holes; with nonZero, two bands under a hole give
    winding -1, which is still "non-zero" and therefore still filled. Both are wrong.
    One correctly-wound outline has no ambiguity.

    Construction, clockwise from the stem's top-left. The stem is the vertical bar
    [a,b] x [t,btm]. Each arm is a parallelogram whose long edges are the two 45-degree
    lines through the arm's attach points and whose tip is a vertical cut:

        upper arm attaches over [y1, y2], lower arm over [y3, y4], where
            y2 = cy - gap,  y1 = y2 - arm_v      (upper)
            y3 = cy + gap,  y4 = y3 + arm_v      (lower)

    `arm_v` is the arm's *vertical* thickness where it meets the stem, which is the
    number a designer can actually reason about. An earlier version took perpendicular
    width instead and derived the vertical extent as w*sqrt(2); with a 13-unit arm that
    spans 18.4 vertically, which pushed the attach point to the very top of the stem and
    clamped the tip reach to 2.6 — a K with stubs for arms. Measuring vertically avoids
    that whole class of error.

    `gap = 0` (the default) makes both arms meet the stem at the centre, which is how a
    K is actually drawn: the two arms share the junction point.

    This function has been wrong twice, both times from offset algebra rather than
    construction, so `verify_outline()` compares it against the union-of-bands
    reference and the emit step refuses to ship if they disagree.
    """
    a = cx - stem / 2.0
    b = cx + stem / 2.0
    t = cy - half_h
    btm = cy + half_h

    y2 = cy - gap                            # upper arm: lower attach
    y1 = y2 - arm_v                          # upper arm: upper attach
    y3 = cy + gap                            # lower arm: upper attach
    y4 = y3 + arm_v                          # lower arm: lower attach

    # Reach is NOT clamped to the stem's height. A real K's arms run from the middle of
    # the stem out past its top and bottom edges — clamping the tip to stay inside the
    # stem is what turned the arms into 6-unit stumps in an earlier attempt. The tip
    # corners (y1 - reach, y2 - reach) are allowed above the stem top; the only real
    # limit is the adaptive-icon safe circle, which is checked after rendering.
    reach = max(4.0, (half_h * 1.42 * arm_reach) - (b - cx))

    pts = [
        (a, t),
        (b, t),
        (b, y1),
        (b + reach, y1 - reach),             # upper tip, top
        (b + reach, y2 - reach),             # upper tip, bottom
        (b, y2),
    ]
    # With gap = 0 the upper arm's lower attach and the lower arm's upper attach are
    # the same point; emitting it twice creates a zero-length edge.
    if y3 != y2:
        pts.append((b, y3))
    pts += [
        (b + reach, y3 + reach),             # lower tip, top
        (b + reach, y4 + reach),             # lower tip, bottom
        (b, y4),
        (b, btm),
        (a, btm),
    ]
    return pts


def verify_outline(tol_px=45, px=108):
    """Prove the single-outline K matches the union-of-bands reference.

    Returns (mismatched_px, ink_px). A correct outline differs only along antialiased
    edges, so a small mismatch is expected; the old broken version differed on almost
    every ink pixel.
    """
    kw = dict(stem=13.0, half_h=26.0, arm_v=13.0, arm_reach=1.0, gap=5.0)
    a = Mark([k_outline(**kw)]).render(px)
    b = Mark(union_of_bands(arm_start=1.0, **kw)).render(px)
    diff = sum(1 for y in range(px) for x in range(px) if abs(a[y][x] - b[y][x]) > 0.35)
    ink = sum(1 for y in range(px) for x in range(px) if a[y][x] > 0.5)
    return diff, ink


def k_outline_ccw(**kw):
    """Same outline, reversed: used as the hole contour."""
    return list(reversed(k_outline(**kw)))


def union_of_bands(**kw):
    """Reference implementation: the same K built from overlapping bands."""
    from design4 import k_bands
    return k_bands(**kw)


# ---------------------------------------------------------------- refined candidates

def pins_2edge(body, count, length, width, inset=6.0):
    out = []
    h = body / 2.0
    span = body - 2 * inset
    for i in range(count):
        off = -span / 2.0 + span * i / (count - 1)
        out.append([(54 + off - width / 2, 54 - h), (54 + off + width / 2, 54 - h),
                    (54 + off + width / 2, 54 - h - length), (54 + off - width / 2, 54 - h - length)])
        out.append([(54 + off - width / 2, 54 + h), (54 + off + width / 2, 54 + h),
                    (54 + off + width / 2, 54 + h + length), (54 + off - width / 2, 54 + h + length)])
    return out


def pins_4edge(body, count, length, width, inset=5.0):
    out = pins_2edge(body, count, length, width, inset)
    h = body / 2.0
    span = body - 2 * inset
    for i in range(count):
        off = -span / 2.0 + span * i / (count - 1)
        out.append([(54 - h, 54 + off - width / 2), (54 - h, 54 + off + width / 2),
                    (54 - h - length, 54 + off + width / 2), (54 - h - length, 54 + off - width / 2)])
        out.append([(54 + h, 54 + off - width / 2), (54 + h, 54 + off + width / 2),
                    (54 + h + length, 54 + off + width / 2), (54 + h + length, 54 + off - width / 2)])
    return out


def chip_2edge():
    """Chip, three chunky pins top and bottom, bold K cut out."""
    body = 48.0
    return Mark([chamfered_die(54, 54, body, 9)] + pins_2edge(body, 3, 9.0, 8.5),
                [k_outline(stem=11.0, half_h=17.0, arm_v=11.0, arm_reach=0.85, gap=4.0)])


def chip_2edge_bold():
    """Same, fewer and thicker pins: survives 24 px."""
    body = 50.0
    return Mark([chamfered_die(54, 54, body, 10)] + pins_2edge(body, 2, 9.5, 12.0),
                [k_outline(stem=12.0, half_h=18.0, arm_v=12.0, arm_reach=0.85, gap=4.5)])


def chip_4edge():
    """Pins on all four edges. More 'chip', more noise when small."""
    body = 44.0
    return Mark([chamfered_die(54, 54, body, 8)] + pins_4edge(body, 3, 8.5, 7.5),
                [k_outline(stem=10.0, half_h=15.0, arm_v=10.0, arm_reach=0.8, gap=4.0)])


def chip_dip():
    """Tall body, pins left and right: a DIP package. Narrow, specific silhouette."""
    body = 46.0
    h = body / 2.0
    pins = []
    for i in range(4):
        off = -15.0 + i * 10.0
        pins.append([(54 - h, 54 + off - 3.5), (54 - h - 9, 54 + off - 3.5),
                     (54 - h - 9, 54 + off + 3.5), (54 - h, 54 + off + 3.5)])
        pins.append([(54 + h, 54 + off - 3.5), (54 + h + 9, 54 + off - 3.5),
                     (54 + h + 9, 54 + off + 3.5), (54 + h, 54 + off + 3.5)])
    return Mark([rounded_rect(54, 54, body, body, 7)] + pins,
                [k_outline(stem=10.0, half_h=16.0, arm_v=10.0, arm_reach=0.82, gap=4.0)])


def chip_rounded_2edge():
    """Rounded body instead of chamfered: softer, closer to the old icon but with pins."""
    body = 48.0
    return Mark([rounded_rect(54, 54, body, body, 10)] + pins_2edge(body, 3, 9.0, 8.5),
                [k_outline(stem=11.0, half_h=17.0, arm_v=11.0, arm_reach=0.85, gap=4.0)])


def k_bold():
    """No container. Just a heavy K — the safest, least specific option."""
    return Mark([k_outline(stem=18.0, half_h=30.0, arm_v=18.0, arm_reach=0.98, gap=6.0)])


CANDIDATES = [
    ("chip_2edge", chip_2edge),
    ("chip_2edge_bold", chip_2edge_bold),
    ("chip_4edge", chip_4edge),
    ("chip_dip", chip_dip),
    ("chip_rounded", chip_rounded_2edge),
    ("k_bold", k_bold),
]


# ---------------------------------------------------------------- masked preview

MASK_R = 36.0        # radius of the launcher mask, in canvas units


def masked_rows(mark, px, wallpaper):
    """Render the mark over a wallpaper and clip it to the adaptive-icon mask."""
    cov = mark.render(px)
    s = px / 108.0
    cx = cy = px / 2.0
    r = MASK_R * s
    rows = []
    for y in range(px):
        row = []
        for x in range(px):
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) > r:
                row.append(wallpaper)
                continue
            c = cov[y][x]
            row.append(tuple(int(DARK[i] + (ACCENT[i] - DARK[i]) * c) for i in range(3)))
        rows.append(row)
    return rows


def tile(mark, big=120):
    dark = masked_rows(mark, big, (0x0A, 0x0C, 0x0E))
    light = masked_rows(mark, big, (0xE8, 0xEA, 0xEC))
    # 48 and 24 px on dark, upscaled with nearest neighbour only for the sheet layout
    d48 = masked_rows(mark, 48, (0x0A, 0x0C, 0x0E))
    d24 = masked_rows(mark, 24, (0x0A, 0x0C, 0x0E))
    rows = []
    for y in range(big):
        rows.append(list(dark[y]) + [light[y]] if False else list(dark[y]) + list(light[y]))
    for y in range(max(len(d48), len(d24))):
        line = list(d48[y] if y < len(d48) else [wall] * 48)
        line += list(d24[y] if y < len(d24) else [wall] * 24)
        line += [(0x0A, 0x0C, 0x0E)] * (2 * big - len(line))
        rows.append(line)
    return rows


wall = (0x0A, 0x0C, 0x0E)


def sheet(path, big=120, gap=12, per_row=6):
    tiles = []
    for name, fn in CANDIDATES:
        m = fit_safe(fn(), target=31.0)
        r = rendered_max_radius(m)
        print(f"{name:18} maxR={r:5.2f}")
        tiles.append((name, tile(m, big)))
    h = max(len(t) for _, t in tiles)
    w = max(len(t[0]) for _, t in tiles)
    for _, t in tiles:
        t.extend([[wall] * w for _ in range(h - len(t))])
    rows = []
    for start in range(0, len(tiles), per_row):
        chunk = tiles[start:start + per_row]
        row_w = gap + len(chunk) * (w + gap)
        canvas = [[(0x06, 0x07, 0x08)] * row_w for _ in range(h + gap)]
        for i, (_, t) in enumerate(chunk):
            x0 = gap + i * (w + gap)
            for y in range(h):
                for x in range(w):
                    canvas[y][x0 + x] = t[y][x]
        rows.extend(canvas)
    ppm = os.path.join(HERE, "design5.ppm")
    write_ppm(ppm, rows)
    subprocess.run(["convert", ppm, path], check=True)
    print("wrote", path)
    print("order:", ", ".join(n for n, _ in CANDIDATES))


if __name__ == "__main__":
    if "--one" in sys.argv:
        name = sys.argv[sys.argv.index("--one") + 1]
        out = os.path.join(HERE, f"one_{name}.png")
        ppm = os.path.join(HERE, "one5.ppm")
        write_ppm(ppm, tile(fit_safe(dict(CANDIDATES)[name](), target=31.0), 300))
        subprocess.run(["convert", ppm, out], check=True)
        print("wrote", out)
    else:
        sheet(os.path.join(HERE, "design5.png"))
