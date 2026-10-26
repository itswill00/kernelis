# Icon geometry harness

The launcher icon is **measured**, not eyeballed. This directory contains the
throwaway-but-kept tooling that produced `app/src/main/res/drawable/ic_launcher_foreground.xml`.

## Why this exists

The icon has to work at 24 dp in a launcher, inside an adaptive-icon mask, as a single
silhouette for themed icons. Those are measurable properties, so they are measured
instead of argued about.

Two earlier attempts produced numbers that were simply wrong, and both mistakes are
recorded here because they are easy to repeat:

1. **ImageMagick's built-in SVG renderer silently ignores `transform`.**
   Verified with a probe: a rect with `rotate(45 35 35)` rendered axis-aligned. Any
   measurement taken from `convert foo.svg foo.png` was therefore describing a
   different shape than the one written. `rsvg-convert` is not installed here, so the
   delegate never ran.
2. **Percentile-of-distance is not thickness.**
   Taking the 2nd percentile of `2 * distance-to-background` over all ink pixels
   reports ~2 px for every design, because the outermost ring of ink pixels is at
   distance 1 by definition. It condemned designs with 10 px walls. Replaced with a
   morphological erosion profile (see `final.py::erosion_profile`).

## What is measured

| Metric | Rule | Why |
|---|---|---|
| `maxR` | ≤ 33 dp | Adaptive icons crop outside a 66 dp circle |
| `coverage` | 12–40 % | Visible without becoming a blob |
| `solid_r` | ≥ 3 dp | Largest radius where ≥15 % of ink survives erosion |
| `blobs @ 24 dp` | exactly 1 | Must read as one mark at launcher size |
| `ink @ 24 dp` | ≥ 10 % | Must not fade away when small |

`raster.py` is a self-contained scanline rasteriser (even-odd fill, 4× supersampling).
It runs a **self-test against analytic answers** before any measurement is trusted:

```
$ python3 tools/icon/raster.py
rasteriser self-test: PASS (matches analytic area/radius/stroke/hole)
```

`emit.py` closes the loop: it emits the Android `pathData`, parses that XML back,
re-rasterises it and re-measures. It asserts the shipped geometry measures identically
to the design (drift < 0.05 dp on radius, < 0.2 % on coverage). That round-trip is what
makes the table above meaningful — otherwise the numbers describe a drawing that never
ships.

## Result

The chosen mark is a rounded-square "kernel die" with a K counter cut out, drawn as one
path with `fillType="evenOdd"` so the monochrome layer is a single silhouette.

```
BEST: wall=64 k_scale=0.82  solid=6.0px ink=17.8% maxR=31.9 ink24=20.8%
round-trip: maxR 31.95 (drift 0.000), ink 17.77% (drift 0.002%), solid 6.0, blobs@24 1
```

Rejected candidates and why, from the same harness: `k_solid` (9 % ink, faint at
24 dp), `bars_baseline` (3.5 dp solid, too thin), `hex_ring_core` (breaks into 2 blobs
at 24 and 32 dp).

## Running it

```bash
cd tools/icon
python3 raster.py     # self-test the rasteriser
python3 emit.py       # sweep, select, emit XML, verify round-trip
```

`emit.py` writes `ic_launcher_foreground.xml` and `preview_final.ppm` next to itself.
The preview is a plain PPM because no image viewer is required to produce it — convert
it with `convert preview_final.ppm preview_final.png` if you want to look at it.
