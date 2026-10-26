#!/usr/bin/env python3
"""Round 6: the finalists, judged at the sizes that matter.

Round 5 established two things by looking:

  * The chip silhouettes read as "hardware" at 120 px and dissolve into noise at 24 px.
    The pins are the problem: six small squares at 24 px are 2 px each, which is below
    the threshold where a shape reads as a shape.
  * A bare K survives every size, but at 24 px the arms detach from the stem visually
    because the junction notch is only ~2 px wide.

This round fixes both by construction rather than by taste:

  * Pins are 2 per edge and thick (12 units), so at 24 px they are ~2.7 px and still
    read as stubs rather than dust.
  * The K's junction uses gap = 0 and a wider stem, so the notch is closed and the mark
    cannot break apart.
  * Each candidate is shown inside the real adaptive mask, on dark AND light wallpaper,
    at 96 / 48 / 24 px.

Anything that fails the 24 px check is rejected here, not shipped.
"""
import math
import os
import subprocess
import sys

from design import Mark, write_ppm, chamfered_die, circle
from design4 import rendered_max_radius, fit_safe
from design5 import k_outline
from raster import rounded_rect

HERE = os.path.dirname(os.path.abspath(__file__))
ACCENT = (0x35, 0xD0, 0xBA)
DARK = (0x11, 0x14, 0x17)
LIGHT = (0xF2, 0xF4, 0xF6)
MASK_R = 36.0


# ---------------------------------------------------------------- pin helper

def pins_two_edges(body, count, length, width, inset=0.0):
    """Pins on the top and bottom edges only. Fewer edges = less noise when small."""
    out = []
    h = body / 2.0
    span = body - 2 * inset
    for i in range(count):
        off = -span / 2.0 + span * i / (count - 1) if count > 1 else 0.0
        out.append([(54 + off - width / 2, 54 - h), (54 + off + width / 2, 54 - h),
                    (54 + off + width / 2, 54 - h - length), (54 + off - width / 2, 54 - h - length)])
        out.append([(54 + off - width / 2, 54 + h), (54 + off + width / 2, 54 + h),
                    (54 + off + width / 2, 54 + h + length), (54 + off - width / 2, 54 + h + length)])
    return out


def pins_four_edges(body, count, length, width, inset=0.0):
    out = pins_two_edges(body, count, length, width, inset)
    h = body / 2.0
    span = body - 2 * inset
    for i in range(count):
        off = -span / 2.0 + span * i / (count - 1) if count > 1 else 0.0
        out.append([(54 - h, 54 + off - width / 2), (54 - h, 54 + off + width / 2),
                    (54 - h - length, 54 + off + width / 2), (54 - h - length, 54 + off - width / 2)])
        out.append([(54 + h, 54 + off - width / 2), (54 + h, 54 + off + width / 2),
                    (54 + h + length, 54 + off + width / 2), (54 + h + length, 54 + off - width / 2)])
    return out


# ---------------------------------------------------------------- finalists

def f_chip_2pin():
    """Chip, two thick pins per side. Simplest silhouette that still says 'chip'."""
    body = 46.0
    return Mark([chamfered_die(54, 54, body, 9)] + pins_two_edges(body, 2, 9.0, 13.0),
                [k_outline(stem=13.0, half_h=16.0, arm_v=11.0, arm_reach=0.92, gap=0.0)])


def f_chip_3pin():
    """Chip, three thinner pins per side: more like a real package, more noise."""
    body = 46.0
    return Mark([chamfered_die(54, 54, body, 9)] + pins_two_edges(body, 3, 9.0, 9.0),
                [k_outline(stem=12.0, half_h=16.0, arm_v=10.5, arm_reach=0.92, gap=0.0)])


def f_chip_2pin_pin1():
    """Same, with one deeply chamfered corner: the pin-1 marker is the identity."""
    body = 46.0
    h = body / 2.0
    x0, y0 = 54 - h, 54 - h
    small, deep = 9.0, 19.0
    body_pts = [(x0 + deep, y0), (x0 + body - small, y0), (x0 + body, y0 + small),
                (x0 + body, y0 + body - small), (x0 + body - small, y0 + body),
                (x0 + small, y0 + body), (x0, y0 + body - small), (x0, y0 + deep)]
    return Mark([body_pts] + pins_two_edges(body, 2, 9.0, 13.0),
                [k_outline(stem=12.0, half_h=15.0, arm_v=10.5, arm_reach=0.9, gap=0.0)])


def f_chip_4pin():
    """Pins on all four edges, thick. Busiest, but the most obviously a chip."""
    body = 44.0
    return Mark([chamfered_die(54, 54, body, 8)] + pins_four_edges(body, 2, 9.0, 11.0),
                [k_outline(stem=12.0, half_h=15.0, arm_v=10.0, arm_reach=0.9, gap=0.0)])


def f_chip_solid():
    """No pins: chamfered die with the K cut out, but the K now touches the edges."""
    return Mark([chamfered_die(54, 54, 64, 14)],
                [k_outline(stem=14.0, half_h=22.0, arm_v=12.0, arm_reach=1.18, gap=0.0)])


def f_k_bold():
    """Bare K, heavy, arms reaching the safe circle. No container at all."""
    return Mark([k_outline(stem=18.0, half_h=30.0, arm_v=16.0, arm_reach=1.05, gap=0.0)])


def f_k_chevron():
    """Bare K where the arms are equal-width chevrons meeting the stem at its middle."""
    return Mark([k_outline(stem=17.0, half_h=29.0, arm_v=17.0, arm_reach=0.98, gap=0.0)])


CANDIDATES = [
    ("chip_2pin", f_chip_2pin),
    ("chip_3pin", f_chip_3pin),
    ("chip_pin1", f_chip_2pin_pin1),
    ("chip_4pin", f_chip_4pin),
    ("chip_solid", f_chip_solid),
    ("k_bold", f_k_bold),
    ("k_chevron", f_k_chevron),
]


# ---------------------------------------------------------------- rendering

def masked(mark, px, bg):
    """Mark composited over `bg` and clipped to the adaptive-icon mask circle."""
    cov = mark.render(px)
    s = px / 108.0
    cx = cy = px / 2.0
    r = MASK_R * s
    rows = []
    for y in range(px):
        row = []
        for x in range(px):
            if math.hypot(x + 0.5 - cx, y + 0.5 - cy) > r:
                row.append(bg)
                continue
            c = cov[y][x]
            row.append(tuple(int(DARK[i] + (ACCENT[i] - DARK[i]) * c) for i in range(3)))
        rows.append(row)
    return rows


def upscale_nearest(rows, factor):
    out = []
    for row in rows:
        line = []
        for p in row:
            line.extend([p] * factor)
        for _ in range(factor):
            out.append(list(line))
    return out


def tile(mark, big=110):
    """96 px on dark, 96 px on light, then 48 and 24 px upscaled for visibility."""
    dark = masked(mark, big, DARK)
    light = masked(mark, big, LIGHT)
    rows = [list(dark[y]) + list(light[y]) for y in range(big)]
    s48 = upscale_nearest(masked(mark, 48, DARK), 2)      # 96 px
    s24 = upscale_nearest(masked(mark, 24, DARK), 4)      # 96 px
    for y in range(len(s48)):
        line = list(s48[y]) + list(s24[y])
        line += [DARK] * (2 * big - len(line))
        rows.append(line)
    return rows


def sheet(path, big=110, gap=12, per_row=7):
    tiles = []
    for name, fn in CANDIDATES:
        m = fit_safe(fn(), target=31.0)
        r = rendered_max_radius(m)
        print(f"{name:12} maxR={r:5.2f}  {'OK' if r <= 33 else 'CROPPED'}")
        tiles.append((name, tile(m, big)))
    h = max(len(t) for _, t in tiles)
    w = max(len(t[0]) for _, t in tiles)
    for _, t in tiles:
        t.extend([[DARK] * w for _ in range(h - len(t))])
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
    ppm = os.path.join(HERE, "design6.ppm")
    write_ppm(ppm, rows)
    subprocess.run(["convert", ppm, path], check=True)
    print("wrote", path)
    print("order:", ", ".join(n for n, _ in CANDIDATES))


if __name__ == "__main__":
    if "--one" in sys.argv:
        name = sys.argv[sys.argv.index("--one") + 1]
        out = os.path.join(HERE, f"one_{name}.png")
        ppm = os.path.join(HERE, "one6.ppm")
        write_ppm(ppm, tile(fit_safe(dict(CANDIDATES)[name](), target=31.0), 220))
        subprocess.run(["convert", ppm, out], check=True)
        print("wrote", out)
    else:
        sheet(os.path.join(HERE, "design6.png"))
