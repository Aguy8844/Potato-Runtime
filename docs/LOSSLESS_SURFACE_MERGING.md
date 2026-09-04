# Lossless Surface Merging

Patch 047 measures how much of Minecraft's already-generated BLOCK quad stream
can be represented by larger rectangles without changing geometry or per-face
shading information.

## Why this comes before lossy LOD

Patch 046a showed that coarse world regions often occupy a large portion of the
screen. Region size alone is therefore not a safe LOD decision.

Flat/open terrain can still contain enormous redundancy: hundreds of adjacent
block faces may describe one visually regular surface.

Potato first attacks that redundancy losslessly.

## Strict candidate contract

047 accepts only axis-aligned 1x1 block-grid faces with:
- exact uniform packed color;
- exact uniform packed UV2/lightmap value;
- exact packed axis normal;
- rectangular UV0 coordinates;
- identical UV0 atlas bounds;
- identical UV orientation.

It then performs a deterministic greedy rectangle cover inside each section.

The census does not alter MeshData.

## Important atlas requirement

A merged rectangle cannot simply stretch the vanilla atlas UV rectangle across
its full size. That would stretch the block texture.

A future Potato surface vertex format must carry:
- atlas sprite/subrect bounds;
- local tile-space coordinates;
- orientation.

The shader then repeats the sprite *inside its atlas subrect*.

This is how a 16x16 grass plane can remain visually tiled as 16x16 grass
blocks while using far fewer geometry quads.

## Conservative limitations in 047

The census currently:
- merges only inside one 16x16x16 section;
- does not merge across section boundaries;
- does not capture the RenderType identity at the upload seam;
- does not mutate visible rendering;
- does not claim translucent/cutout production safety.

These make the measured reduction conservative as geometry potential, while
the missing RenderType identity intentionally blocks production mutation.

## Sampling safety

The analyzer:
- skips the first 64 eligible uploads;
- samples every 8th eligible upload;
- analyzes at most 48 meshes;
- analyzes at most 500,000 quads;
- rejects individual meshes above 20,000 quads;
- allocates no object per quad;
- retires automatically.

Patch 046a runtime calibration is historical by default and can be explicitly
re-enabled with:

`-Dpotato.debug.worldClusterCalibration=true`

## Next

`POTATO_LOSSLESS_SURFACE_MESH_PROTOTYPE`

The next milestone may build an offscreen Vulkan surface mesh using the exact
strict merge contract, including atlas-subrect texture repetition.
## Patch 047a - decoupled surface tiles

Patch 047 found that the strict merge subset compressed by 33.39%, but that
subset represented only a small fraction of all analyzed quads.

The dominant blocker was baked per-vertex shading:
- 20,181 total analyzed quads;
- 17,393 rejected at the strict uniform color/lightmap test;
- 1,032 rejected as non-axis/non-unit geometry;
- 0 UV mapping failures.

This suggests that geometry and shading should not share the same granularity.

### Proposed exact representation

One large axis-aligned topology rectangle may cover many block tiles.

A separate per-tile payload stores:
- atlas UV bounds;
- UV orientation;
- four vertex colors;
- four UV2/lightmap values.

The fragment shader determines the tile from local surface coordinates and
reconstructs the original per-tile texture and piecewise triangle shading.

This allows adjacent tiles to keep different materials and lighting while
sharing topology.

### 047a census

Two greedy covers are measured:

1. Homogeneous attribute cover
   - adjacent cells must have identical full tile attributes.

2. Decoupled topology cover
   - adjacent cells only need continuous compatible geometry;
   - per-tile material/shading differences are kept in the tile payload.

The difference estimates the geometry reduction unlocked specifically by
decoupling attributes from topology.

No visible rendering is changed.

The next implementation milestone remains:

`POTATO_LOSSLESS_SURFACE_MESH_PROTOTYPE`
## Patch 048 - real Vulkan Surface Tile buffers

One real Minecraft section surface is converted into:
- 32-byte merged rectangle descriptors;
- 64-byte exact per-tile attribute records.

The four color/lightmap corners are canonicalized by geometric tile corner, not
Minecraft emission order.

The snapshot is backend-neutral.

Vulkan consumes it through a one-shot dispatcher and uploads both streams into
real `VK_BUFFER_USAGE_STORAGE_BUFFER_BIT` buffers using the existing mapped
Vulkan allocation helper.

No queue submission, fence wait, device idle or visible rendering mutation is
performed in this milestone.

Next:

`POTATO_SURFACE_TILE_GPU_DECODE_DRAW`