# Potato Visibility Engine

Patch 044 establishes the renderer-only visibility model used by future
high-distance scheduling, occlusion and LOD work.

## Fundamental rule

World state and render state are separate.

Visibility may change:
- mesh scheduling priority;
- GPU residency;
- draw submission;
- future LOD selection.

Visibility must not change:
- chunk loading;
- world ticking;
- entity simulation;
- block-entity simulation;
- saving;
- game mechanics.

An automatic farm behind the camera remains a normal simulated farm.

## Camera source

The engine uses the exact model-view and projection matrices already selected
for Minecraft's current render camera.

It does not derive visibility from the player entity.

Therefore the visibility model naturally follows:
- first person;
- third person;
- spectator;
- camera position changes.

## Tiers

### HOT

A 16x16x16 section AABB intersects the exact homogeneous camera clip volume,
or the section is extremely close to the camera.

HOT is the future full-detail rendering class.

### WARM

A section is outside the exact clip volume but:
- near enough to the camera that aggressive deferral could cause visible
  popping; or
- inside an expanded clip-space guard band.

WARM is the future prewarm / predictive-meshing class.

### COLD

A section is outside both the current camera volume and the guard band.

COLD is the future deferred/full-detail-not-needed class.

Patch 044 does not yet skip visible OpenGL draws or defer vanilla mesh builds.
It only establishes and verifies the classification contract.

## Clip-space implementation

For each layer:
- copy model-view and projection once;
- calculate projection * model-view once.

For each section:
- test the eight AABB corners directly in homogeneous clip space;
- allocate no vectors/matrices/objects.

A section is outside only when all eight corners lie outside the same plane.

The WARM test uses a deliberately generous expanded clip volume.

## Roadmap

Patch 045:
`POTATO_VIEW_DIRECTED_CHUNK_MESHING`

Use HOT/WARM/COLD before expensive chunk mesh preparation:
- HOT -> immediate;
- WARM -> background/prewarm;
- COLD -> defer full-detail rebuild.

Later:
- GPU HZB occlusion;
- indirect draw generation;
- LOD / surface shells;
- visible Vulkan terrain authority.