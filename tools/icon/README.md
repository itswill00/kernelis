# Icon geometry harness

The launcher icon is **measured**, not eyeballed. This directory contains the tooling
that produced `app/src/main/res/drawable/ic_launcher_foreground.xml`.

## The problem with the first shipped icon

v0.4.0 shipped a rounded square with a K cut out of it. It passed every geometric gate
(radius inside the safe circle, 17.8% ink, one blob at 24 dp) and still looked like
nothing: the container was a stock shape, and the letter inside had no geometric
relationship to it. Measuring "is it legible" cannot answer "does it have character".

So this round the candidates were **looked at**, rendered inside the real adaptive-icon
mask at 24/32/48/96 px on dark and light wallpaper. That changed the outcome twice: it
rejected the entire pin-grid family (six 2 px pins at 24 px read as dust, not as a chip)
and it exposed that the letter itself was breaking apart at small sizes.

## The chosen mark

A chamfered chip body with one deeply cut pin-1 corner, and a bold K whose arms run out
through the body's right edge. The silhouette itself is the identity — remove the colour
and the letter and it is still not a rounded square.

| Metric | Value | Rule |
|---|---|---|
| ink radius | 30.7 | ≤ 33 (adaptive-icon safe circle) |
| ink coverage | ~18 % | 12–40 % |
| contours | 2 islands | must be > 0 and not a blob |
| K counter | open | must not fill in |
| at 24 px | reads | verified by rendering, not asserted |

## Contrast, measured

| Pair | Ratio | Verdict |
|---|---|---|
| accent `#35D0BA` on our background `#111417` | **9.58:1** | well past 4.5:1 |
| accent on a white wallpaper | 1.93:1 | only reachable if a launcher ignores the background layer |

The 1.93:1 case is not a colour choice that can be fixed by darkening the accent: it only
occurs if a launcher fails to draw the adaptive-icon background, which conforming
launchers do not do. Darkening the accent enough to pass on white would drop the
9.58:1 we actually rely on. The monochrome layer is tinted by the system with a colour
the system picks for contrast, so its ratio is not ours to choose either — what matters
there is that the shape survives a flat tint, which it does because the K's counter is
open to the edge rather than a closed hole that a tint could flatten.

## Why the emit step is not a one-liner

The ink is `body minus K`, and the K's arms poke out past the body's right edge. A
VectorDrawable can only fill contours, and `fillType="evenOdd"` fills the **symmetric
difference** of its contours. Emitting "body contour + K contour" therefore *fills* the
protruding arms instead of cutting them. Measured on the exact geometry that was about
to ship: **212 wrong pixels out of 11664**.

The fix is to emit the ink already subtracted, as exact polygons. `boolean.py` computes
that difference properly: every edge of each operand is split at its intersections with
the other, pieces are kept or dropped by a strict point-in-polygon test on their
midpoints, and the kept pieces are chained into closed loops. The result is two separate
ink islands — the body, and the wedge between the arms where they break the edge — both
wound counter-clockwise and filled with `nonZero`, so correctness comes from winding
order rather than from the accident that they do not overlap.

`emit2.py` then parses the pathData back out of the written XML, re-rasterises it and
compares against the design raster. It refuses to ship on any mismatch. Current
round-trip: **0 px mismatch**.

## Mistakes made along the way

Five of these produced numbers or images that were simply wrong, and all five are
recorded because they are easy to repeat:

1. **ImageMagick's SVG renderer silently ignores `transform`.** Verified with a probe: a
   rect with `rotate(45 35 35)` rendered axis-aligned. Any measurement taken from
   `convert foo.svg foo.png` described a different shape than the one written.
2. **Percentile-of-distance is not thickness.** The 2nd percentile of
   `2 * distance-to-background` over all ink pixels reports ~2 px for every design,
   because the outermost ring of ink pixels is at distance 1 by definition. It condemned
   designs with 10 px walls. Replaced with a morphological erosion profile.
3. **"Union" that was really XOR.** The first shape algebra combined contours with
   even-odd across the whole mark, so overlapping same-ink shapes punched holes in each
   other — a trace crossing a via became a hole. `design.py::Mark` now unions and
   subtracts explicitly.
4. **Rendering at the wrong resolution.** The rasteriser's coordinate space is pixel
   space, so asking for 192 px without scaling the polygons renders a 108 px mark in the
   corner; upscaling that afterwards produces a dithered mess. The first preview was
   judged on that image.
5. **Fitting the safe circle on vertices instead of ink.** A chamfer vertex sits outside
   the ink by its whole offset, so fitting on vertices shrinks the mark more than needed.
   `rendered_max_radius()` measures the raster instead.

A sixth was caught only because a "0 px mismatch" looked too good: the verifier was
checking the *intended* semantics ("body minus K") while the XML shipped `evenOdd`.
When the two disagree, the test passes and the icon is wrong. The verifier now parses
the XML and renders what the vector actually does.

## What is measured

| Metric | Rule | Why |
|---|---|---|
| `maxR` | ≤ 33 | Adaptive icons crop outside a 66 dp circle |
| `coverage` | 12–40 % | Visible without becoming a blob |
| `solid_r` | ≥ 3 dp | Largest radius where ≥15 % of ink survives erosion |
| `blobs @ 24 dp` | exactly 1 | Must read as one mark at launcher size |
| `ink @ 24 dp` | ≥ 10 % | Must not fade away when small |

`raster.py` is a self-contained scanline rasteriser (even-odd fill, 4× supersampling)
that self-tests against analytic answers before any measurement is trusted:

```
$ python3 raster.py
rasteriser self-test: PASS (matches analytic area/radius/stroke/hole)
```

## Running it

```bash
cd tools/icon
python3 raster.py     # self-test the rasteriser
python3 boolean.py    # self-test the polygon boolean (two analytic cases)
python3 design7.py    # render the finalist sheet (design7.png)
python3 emit2.py      # emit XML + verify round-trip, fails loudly on mismatch
```

`design4.py`/`design5.py`/`design6.py` are the earlier candidate rounds, kept because
they document which families were rejected and why. `design.py` is the shape algebra
they all share.

`app/src/test/java/dev/kernelis/ui/theme/LauncherIconTest.kt` locks the shipped geometry:
fill rule, contour count, safe-circle radius, and that the K counter is actually open.
It was verified red-on-broken by flipping `nonZero` to `evenOdd` in the shipped XML.
