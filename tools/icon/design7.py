#!/usr/bin/env python3
"""Round 7: pick between the three survivors of round 6.

Round 6 rejected everything with pins: at 24 px the pins become dust, and an icon that
turns to noise in the launcher is not an icon. Three marks survived, and they differ
only in how the K relates to its container, so this round isolates exactly that:

    A. arms BREAK the body   -> the silhouette itself is the mark (most character)
    B. arms STOP inside      -> a die with a K cut out (calmer, more "app")
    C. no container          -> a bare K (cleanest, least specific)

Plus a pin-1 variant of A, because an asymmetric silhouette is harder to confuse with
another app than a symmetric one.

Shown at 24 / 32 / 48 / 96 px, masked, on dark and light wallpaper.
"""
import math
import os
import subprocess
import sys

from design import Mark, write_ppm, chamfered_die
from design4 import rendered_max_radius, fit_safe
from design5 import k_outline

HERE = os.path.dirname(os.path.abspath(__file__))
ACCENT = (0x35, 0xD0, 0xBA)
DARK = (0x11, 0x14, 0x17)
LIGHT = (0xF2, 0xF4, 0xF6)
MASK_R = 36.0


def body_pin1(body, small, deep):
    """Chamfered body with one corner cut much deeper: the pin-1 marker."""
    h = body / 2.0
    x0, y0 = 54 - h, 54 - h
    return [(x0 + deep, y0), (x0 + body - small, y0), (x0 + body, y0 + small),
            (x0 + body, y0 + body - small), (x0 + body - small, y0 + body),
            (x0 + small, y0 + body), (x0, y0 + body - small), (x0, y0 + deep)]


def a_break():
    """K's arms run out through the body edges: silhouette becomes the mark."""
    return Mark([chamfered_die(54, 54, 64, 14)],
                [k_outline(stem=14.0, half_h=22.0, arm_v=12.0, arm_reach=1.18, gap=0.0)])


def a_break_pin1():
    """Same, with a deep pin-1 chamfer so the outline is asymmetric too."""
    return Mark([body_pin1(64.0, 13.0, 24.0)],
                [k_outline(stem=14.0, half_h=22.0, arm_v=12.0, arm_reach=1.18, gap=0.0)])


def b_stop():
    """Arms stop just inside the body: a die with a K cut out, calmer."""
    return Mark([chamfered_die(54, 54, 66, 15)],
                [k_outline(stem=14.0, half_h=23.0, arm_v=12.0, arm_reach=0.92, gap=0.0)])


def b_stop_pin1():
    return Mark([body_pin1(66.0, 14.0, 25.0)],
                [k_outline(stem=14.0, half_h=23.0, arm_v=12.0, arm_reach=0.92, gap=0.0)])


def c_bare():
    """No container: a heavy K, arms to the safe circle."""
    return Mark([k_outline(stem=18.0, half_h=30.0, arm_v=16.0, arm_reach=1.05, gap=0.0)])


CANDIDATES = [
    ("a_break", a_break),
    ("a_break_pin1", a_break_pin1),
    ("b_stop", b_stop),
    ("b_stop_pin1", b_stop_pin1),
    ("c_bare", c_bare),
]


def masked(mark, px, bg):
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


def upscale(rows, f):
    out = []
    for row in rows:
        line = []
        for p in row:
            line.extend([p] * f)
        for _ in range(f):
            out.append(list(line))
    return out


def tile(mark):
    """Row 1: 96 px dark, 96 px light. Row 2: 24, 32, 48 px upscaled to 96."""
    dark = masked(mark, 96, DARK)
    light = masked(mark, 96, LIGHT)
    rows = [list(dark[y]) + list(light[y]) for y in range(96)]
    s24 = upscale(masked(mark, 24, DARK), 4)
    s32 = upscale(masked(mark, 32, DARK), 3)
    s48 = upscale(masked(mark, 48, DARK), 2)
    for y in range(96):
        rows.append(list(s24[y]) + list(s32[y]) + list(s48[y]))
    return rows


def sheet(path, gap=14, per_row=5):
    tiles = []
    for name, fn in CANDIDATES:
        m = fit_safe(fn(), target=31.0)
        r = rendered_max_radius(m)
        print(f"{name:14} maxR={r:5.2f}")
        tiles.append((name, tile(m)))
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
    ppm = os.path.join(HERE, "design7.ppm")
    write_ppm(ppm, rows)
    subprocess.run(["convert", ppm, path], check=True)
    print("wrote", path)


if __name__ == "__main__":
    sheet(os.path.join(HERE, "design7.png"))
