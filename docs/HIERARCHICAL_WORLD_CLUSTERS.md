# Hierarchical World Clusters

Patch 046 establishes the spatial hierarchy used by Potato's future far-field
surface renderer, HZB culling and screen-space LOD.

## Why a hierarchy

A render distance of 128 chunks covers 257 x 257 = 66,049 horizontal chunk
columns before vertical sections are considered.

Testing every chunk or section independently every frame would itself become a
CPU bottleneck.

Potato therefore needs to answer visibility/detail questions at progressively
larger region scales.

## Current hierarchy

Horizontal footprints:

- 16 x 16 blocks;
- 32 x 32 blocks;
- 64 x 64 blocks;
- 128 x 128 blocks;
- 256 x 256 blocks.

Vertical size is data-driven. A region records the lowest/highest observed
16-block terrain section in that X/Z footprint.

This is intentionally not a rigid cubic octree. Minecraft terrain is usually
better represented as horizontal regions with a variable vertical envelope.

## Screen-space policy

Patch 046 measures how large each observed cluster actually appears in the
current framebuffer using Minecraft's real model-view/projection matrices.

Classes used for diagnostics:

- <= 8 px;
- <= 32 px;
- <= 128 px;
- > 128 px.

No LOD is applied yet.

A future renderer may select the coarsest cluster whose visual error remains
below its screen-space threshold.

## Hot-path safety

Patch 046 observes only the SOLID terrain layer.

Observation is bounded to:
- 24 solid layers;
- or 100,000 section observations.

Primitive fixed-size hash tables are used:
- no boxing;
- no per-section object allocation;
- generation stamps avoid clearing tables per frame.

After retirement the existing mixin only checks one boolean before the old
per-section draw path continues. The hierarchy engine is not called.

## No visible mutation

Patch 046 does not:
- cancel OpenGL draws;
- replace geometry;
- enable LOD;
- enable HZB;
- alter chunk loading;
- alter ticking;
- alter entities;
- alter saves.

## Next

`POTATO_LOSSLESS_SURFACE_MERGING_FOUNDATION`

The next milestone should build a far-field surface representation capable of
merging compatible coplanar faces before any lossy LOD is introduced.
## Patch 046a calibration correction

Patch 046 proved the hierarchy structure but exposed two measurement problems.

### Early sampling

The first observer retired after the first 24 SOLID layer entries. On a
high-distance world this can occur while terrain is still being compiled and
uploaded.

046a:
- ignores the first 60 SOLID layers;
- samples every 12th SOLID layer;
- requires at least 8 samples;
- prefers to retire only after a sample reaches 512 observed sections;
- hard-retires after 24 samples or other strict limits.

This keeps instrumentation sparse while measuring a mature scene.

### Viewport-bounded screen occupancy

Raw projected NDC values near the camera plane can become arbitrarily large.
The original report therefore produced impossible values above the physical
framebuffer width.

046a clips projected X/Y to the NDC viewport before pixel conversion.

The visible occupancy metric now satisfies:

`0 <= projectedPixelSpan <= max(framebufferWidth, framebufferHeight)`

Near-plane intersections remain conservative full-screen.

### Important distinction

Projected cluster footprint is not yet a complete LOD error metric.

A future LOD selector must compare the geometric/material error of a simplified
surface against screen resolution. The footprint metric is currently used to
understand hierarchy scale and merging opportunities.