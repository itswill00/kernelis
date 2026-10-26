#!/usr/bin/env python3
"""A small, self-contained polygon rasteriser for icon design.

Why this exists: ImageMagick's built-in SVG renderer silently ignores `transform`
(verified), so measuring rendered SVG told us nothing. Rather than trust a renderer
we cannot audit, the icon geometry is expressed as polygons and rasterised here, with
a self-test that proves the rasteriser agrees with known analytic answers.

The same polygon list is what gets emitted as Android vector pathData, so the numbers
measured here describe exactly what ships.
"""
import math

CANVAS = 108.0
SS = 4                      # supersampling factor

# ---------------------------------------------------------------- geometry helpers

def rounded_rect(cx, cy, w, h, r, seg=12):
    """Clockwise rounded-rectangle outline as a polygon."""
    x0, y0, x1, y1 = cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2
    pts = []
    corners = [
        (x1 - r, y1 - r, 0),        # bottom-right
        (x0 + r, y1 - r, 90),       # bottom-left
        (x0 + r, y0 + r, 180),      # top-left
        (x1 - r, y0 + r, 270),      # top-right
    ]
    for ccx, ccy, start in corners:
        for i in range(seg + 1):
            a = math.radians(start + 90.0 * i / seg)
            pts.append((ccx + r * math.cos(a), ccy + r * math.sin(a)))
    return pts

def circle(cx, cy, r, seg=64):
    return [(cx + r * math.cos(2 * math.pi * i / seg),
             cy + r * math.sin(2 * math.pi * i / seg)) for i in range(seg)]

def ngon(cx, cy, r, n, rot=-90.0):
    return [(cx + r * math.cos(math.radians(rot + 360.0 * i / n)),
             cy + r * math.sin(math.radians(rot + 360.0 * i / n))) for i in range(n)]

def rotate(pts, deg, cx, cy):
    a = math.radians(deg)
    ca, sa = math.cos(a), math.sin(a)
    return [((x - cx) * ca - (y - cy) * sa + cx,
             (x - cx) * sa + (y - cy) * ca + cy) for x, y in pts]

def translate(pts, dx, dy):
    return [(x + dx, y + dy) for x, y in pts]

# ---------------------------------------------------------------- rasteriser

def _fill_polygon(grid, poly, value, size, ss=SS):
    """Scanline fill with the even-odd rule. grid is a bytearray of size*ss squared."""
    n = len(poly)
    if n < 3:
        return
    ys = [p[1] for p in poly]
    ymin = max(0, int(math.floor(min(ys) * ss)))
    ymax = min(size * ss - 1, int(math.ceil(max(ys) * ss)))
    for py in range(ymin, ymax + 1):
        yc = (py + 0.5) / ss
        xs = []
        for i in range(n):
            x1, y1 = poly[i]
            x2, y2 = poly[(i + 1) % n]
            if (y1 <= yc < y2) or (y2 <= yc < y1):
                xs.append(x1 + (yc - y1) * (x2 - x1) / (y2 - y1))
        xs.sort()
        for i in range(0, len(xs) - 1, 2):
            xa = max(0, int(round(xs[i] * ss)))
            xb = min(size * ss - 1, int(round(xs[i + 1] * ss)) - 1)
            row = py * size * ss
            for px in range(xa, xb + 1):
                grid[row + px] ^= value

def rasterise(contours, size=int(CANVAS)):
    """contours: list of polygons, all XORed together (even-odd across the whole mark).

    Even-odd across contours is what makes "badge with a letter cut out" work: the
    inner contour subtracts from the outer one.
    """
    grid = bytearray(size * SS * size * SS)
    for poly in contours:
        _fill_polygon(grid, poly, 1, size)
    return grid

def coverage_grid(grid, size=int(CANVAS)):
    """Downsample the supersampled grid to per-pixel coverage in 0..1."""
    out = [[0.0] * size for _ in range(size)]
    inv = 1.0 / (SS * SS)
    for y in range(size):
        for x in range(size):
            acc = 0
            base = y * SS * size * SS + x * SS
            for sy in range(SS):
                row = base + sy * size * SS
                for sx in range(SS):
                    acc += grid[row + sx]
            out[y][x] = acc * inv
    return out

# ---------------------------------------------------------------- metrics

def metrics(grid, size=int(CANVAS)):
    cov = coverage_grid(grid, size)
    total = sum(sum(r) for r in cov)
    coverage = total / (size * size)

    cx = cy = size / 2.0
    maxr = 0.0
    for y in range(size):
        for x in range(size):
            if cov[y][x] > 0.02:
                maxr = max(maxr, math.hypot(x + 0.5 - cx, y + 0.5 - cy))

    # thinnest run of ink along a row or column, in device pixels
    min_run = 10 ** 9
    for y in range(size):
        run = 0
        for x in range(size):
            if cov[y][x] > 0.5:
                run += 1
            else:
                if run: min_run = min(min_run, run)
                run = 0
        if run: min_run = min(min_run, run)
    for x in range(size):
        run = 0
        for y in range(size):
            if cov[y][x] > 0.5:
                run += 1
            else:
                if run: min_run = min(min_run, run)
                run = 0
        if run: min_run = min(min_run, run)
    if min_run == 10 ** 9:
        min_run = 0
    return dict(coverage=coverage, maxr=maxr, min_run=min_run, cov=cov)

def blobs_at(cov, target=24):
    """Connected components after box-downscaling to `target` px — the launcher view."""
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
    seen, count = set(), 0
    for p in ink:
        if p in seen:
            continue
        count += 1
        stack = [p]; seen.add(p)
        while stack:
            x, y = stack.pop()
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    q = (x + dx, y + dy)
                    if q in ink and q not in seen:
                        seen.add(q); stack.append(q)
    return count, small

# ---------------------------------------------------------------- self-test

def _selftest():
    """Prove the rasteriser matches analytic answers before trusting any measurement."""
    # 1. circle of r=30 centred: area = pi*900 / 108^2
    g = rasterise([circle(54, 54, 30)])
    m = metrics(g)
    expected = math.pi * 900 / (108 * 108)
    assert abs(m["coverage"] - expected) < 0.01, (m["coverage"], expected)
    assert abs(m["maxr"] - 30) < 1.2, m["maxr"]
    # 2. square 40x40: area = 1600/11664, min run = 40
    g = rasterise([[(34, 34), (74, 34), (74, 74), (34, 74)]])
    m = metrics(g)
    assert abs(m["coverage"] - 1600 / 11664) < 0.005, m["coverage"]
    assert abs(m["min_run"] - 40) <= 1, m["min_run"]
    # 3. even-odd: outer square with inner square cut out -> ring, one blob
    g = rasterise([[(30, 30), (78, 30), (78, 78), (30, 78)],
                   [(44, 44), (64, 44), (64, 64), (44, 64)]])
    m = metrics(g)
    expected = (48 * 48 - 20 * 20) / 11664
    assert abs(m["coverage"] - expected) < 0.006, m["coverage"]
    assert m["cov"][54][54] < 0.02, "hole not cut"
    return True

if __name__ == "__main__":
    _selftest()
    print("rasteriser self-test: PASS (matches analytic area/radius/stroke/hole)")
