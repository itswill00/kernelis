#!/usr/bin/env python3
"""Exact polygon boolean for emitting the launcher mark.

Why this is needed: the chosen mark is `body minus K`, and the K's arms poke out
through the body's right edge. A VectorDrawable can only fill contours, and
fillType="evenOdd" fills the *symmetric difference* — so emitting body and K as two
contours fills the protruding arms instead of cutting them. That was measured at 212
wrong pixels out of 11664 on the geometry that was about to ship.

So the ink has to be emitted already subtracted, as real polygons. This module computes
that difference exactly, by the classic construction:

    boundary(A - B)  =  { parts of dA outside B }  +  { parts of dB inside A, reversed }

Every edge of each operand is split at its intersections with the other operand, the
pieces are kept or dropped by a strict point-in-polygon test on their midpoints, and the
kept pieces are chained back into closed loops. No rasterisation, no approximation, no
staircase to simplify.

`verify()` re-rasterises the result against the design raster, so correctness is
measured rather than assumed.
"""
import math

from design import Mark

EPS = 1e-9
SNAP = 1e-6


# ---------------------------------------------------------------- basics

def area(poly):
    a = 0.0
    n = len(poly)
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        a += x1 * y2 - x2 * y1
    return a / 2.0


def ccw(poly):
    """Same contour wound counter-clockwise. Orientation is what makes chaining work."""
    return poly if area(poly) > 0 else list(reversed(poly))


def _seg_intersect(p1, p2, q1, q2):
    """Return (t, u) with p1+t*(p2-p1) == q1+u*(q2-q1), or None if parallel."""
    d1x, d1y = p2[0] - p1[0], p2[1] - p1[1]
    d2x, d2y = q2[0] - q1[0], q2[1] - q1[1]
    det = d2x * d1y - d1x * d2y
    if abs(det) < 1e-14:
        return None
    ex, ey = q1[0] - p1[0], q1[1] - p1[1]
    t = (d2x * ey - ex * d2y) / det
    u = (d1x * ey - ex * d1y) / det
    return t, u


def _on_segment(pt, a, b, tol=1e-7):
    cross = (b[0] - a[0]) * (pt[1] - a[1]) - (b[1] - a[1]) * (pt[0] - a[0])
    if abs(cross) > tol:
        return False
    dot = (pt[0] - a[0]) * (b[0] - a[0]) + (pt[1] - a[1]) * (b[1] - a[1])
    if dot < -tol:
        return False
    sq = (b[0] - a[0]) ** 2 + (b[1] - a[1]) ** 2
    return dot <= sq + tol


def strictly_inside(pt, poly):
    """Point-in-polygon, returning False for points on the boundary.

    Boundary must not count as inside, otherwise edges that lie along the other operand
    are kept twice and the chained loops double back on themselves.
    """
    n = len(poly)
    for i in range(n):
        if _on_segment(pt, poly[i], poly[(i + 1) % n]):
            return False
    x, y = pt
    inside = False
    for i in range(n):
        x1, y1 = poly[i]
        x2, y2 = poly[(i + 1) % n]
        if (y1 <= y < y2) or (y2 <= y < y1):
            xin = x1 + (y - y1) * (x2 - x1) / (y2 - y1)
            if xin > x:
                inside = not inside
    return inside


# ---------------------------------------------------------------- difference

def _split_params(a, b, other):
    """Parameters along segment a->b where it crosses any edge of `other`."""
    ts = [0.0, 1.0]
    for i in range(len(other)):
        q1, q2 = other[i], other[(i + 1) % len(other)]
        hit = _seg_intersect(a, b, q1, q2)
        if hit is None:
            continue
        t, u = hit
        if -EPS <= t <= 1 + EPS and -EPS <= u <= 1 + EPS:
            ts.append(min(1.0, max(0.0, t)))
    ts.sort()
    out = [ts[0]]
    for t in ts[1:]:
        if t - out[-1] > 1e-9:
            out.append(t)
    return out


def difference(a_polys, b_polys):
    """Return the loops of union(a_polys) minus union(b_polys), oriented CCW/CW.

    Outer boundaries come out counter-clockwise and holes clockwise, matching what the
    vector path expects.
    """
    a_polys = [ccw(p) for p in a_polys]
    b_polys = [ccw(p) for p in b_polys]

    segments = []      # (start, end)

    for a in a_polys:
        for i in range(len(a)):
            p1, p2 = a[i], a[(i + 1) % len(a)]
            ts = [0.0, 1.0]
            for b in b_polys:
                ts += _split_params(p1, p2, b)[1:-1]
            ts = sorted(set(round(t, 12) for t in ts))
            for j in range(len(ts) - 1):
                t0, t1 = ts[j], ts[j + 1]
                if t1 - t0 < 1e-9:
                    continue
                s = _lerp(p1, p2, t0)
                e = _lerp(p1, p2, t1)
                mid = _lerp(p1, p2, (t0 + t1) / 2.0)
                if any(strictly_inside(mid, b) for b in b_polys):
                    continue
                segments.append((s, e))

    for b in b_polys:
        for i in range(len(b)):
            p1, p2 = b[i], b[(i + 1) % len(b)]
            ts = [0.0, 1.0]
            for a in a_polys:
                ts += _split_params(p1, p2, a)[1:-1]
            ts = sorted(set(round(t, 12) for t in ts))
            for j in range(len(ts) - 1):
                t0, t1 = ts[j], ts[j + 1]
                if t1 - t0 < 1e-9:
                    continue
                mid = _lerp(p1, p2, (t0 + t1) / 2.0)
                if not any(strictly_inside(mid, a) for a in a_polys):
                    continue
                # reversed: this piece becomes an inward-facing boundary
                segments.append((_lerp(p1, p2, t1), _lerp(p1, p2, t0)))

    return _chain(segments)


def _lerp(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t)


def _key(pt):
    return (round(pt[0] / SNAP), round(pt[1] / SNAP))


def _chain(segments):
    """Chain directed segments into closed loops by matching endpoints.

    At an intersection point more than one segment can start, so "pick any unused one"
    is wrong: it stitches unrelated pieces together and produces loops that cross
    themselves. Measured on the real design, that turned 2 correct loops into 2 loops
    with the wrong shape (1672 mismatched pixels).

    The rule that is correct for boundary extraction is to take the sharpest clockwise
    turn relative to the incoming direction. Walking the ink boundary with ink on the
    left, that choice always traces the same region rather than jumping to a neighbour.
    """
    outgoing = {}
    for idx, (s, e) in enumerate(segments):
        outgoing.setdefault(_key(s), []).append(idx)

    used = set()
    loops = []
    for idx, (s, e) in enumerate(segments):
        if idx in used:
            continue
        loop = [s]
        cur_idx = idx
        used.add(idx)
        guard = 0
        while guard < len(segments) + 4:
            guard += 1
            cur_end = segments[cur_idx][1]
            if _key(cur_end) == _key(loop[0]):
                break
            loop.append(cur_end)
            cands = [j for j in outgoing.get(_key(cur_end), []) if j not in used]
            if not cands:
                break
            nxt = _sharpest_clockwise(segments[cur_idx], segments, cands)
            used.add(nxt)
            cur_idx = nxt
        if len(loop) >= 3:
            loops.append(_dedupe(loop))
    return loops


def _sharpest_clockwise(prev_seg, segments, cands):
    """Among `cands`, the segment that turns most clockwise from `prev_seg`."""
    p0, p1 = prev_seg
    inx, iny = p1[0] - p0[0], p1[1] - p0[1]
    in_ang = math.atan2(iny, inx)
    best, best_turn = cands[0], 1e9
    for j in cands:
        q0, q1 = segments[j]
        outx, outy = q1[0] - q0[0], q1[1] - q0[1]
        out_ang = math.atan2(outy, outx)
        # clockwise turn in (-pi, pi], positive = clockwise
        turn = (in_ang - out_ang) % (2 * math.pi)
        if turn < best_turn:
            best_turn, best = turn, j
    return best


def _dedupe(poly):
    out = []
    for p in poly:
        if not out or _key(p) != _key(out[-1]):
            out.append(p)
    if len(out) > 1 and _key(out[0]) == _key(out[-1]):
        out.pop()
    return out


# ---------------------------------------------------------------- verification

def verify(design, px=216):
    """Compare the boolean result against the design raster. Returns (loops, diff, frac)."""
    loops = difference(design.plus, design.minus)
    # Orientation decides fill: counter-clockwise loops are ink, clockwise are holes.
    # Treating every loop after the first as a hole was wrong — the design legitimately
    # produces two separate ink islands (the body, and the wedge between the K's arms
    # where they break the body edge), and the second island was being punched out.
    outer = [l for l in loops if area(l) > 0]
    holes = [l for l in loops if area(l) <= 0]
    shipped = Mark(outer, holes).render(px)
    ref = design.render(px)
    diff = sum(1 for y in range(px) for x in range(px)
               if abs(shipped[y][x] - ref[y][x]) > 0.30)
    return loops, diff, diff / (px * px)


if __name__ == "__main__":
    # Two analytic cases the real design depends on: a hole entirely inside, and a
    # subtractor that pokes out through the edge (which is what the K's arms do).
    outer = [(20, 20), (80, 20), (80, 80), (20, 80)]
    hole = [(40, 40), (60, 40), (60, 60), (40, 60)]
    loops = difference([outer], [hole])
    ink = sum(1 for r in Mark(loops[:1], loops[1:]).render(108) for c in r if c > 0.5)
    assert abs(ink - 3200) <= 40, ink

    outer2 = [(30, 30), (60, 30), (60, 70), (30, 70)]
    b2 = [(50, 40), (80, 40), (80, 60), (50, 60)]
    loops2 = difference([outer2], [b2])
    ink2 = sum(1 for r in Mark(loops2).render(108) for c in r if c > 0.5)
    assert abs(ink2 - 1000) <= 40, ink2
    print(f"boolean self-test: PASS (hole {ink}px, protrusion {ink2}px)")
