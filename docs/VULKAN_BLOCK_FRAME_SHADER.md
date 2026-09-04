# Production BLOCK Shader + Multi-Section Frame

Patch 043 ends the one-draw shader-proof phase.

## Shader fidelity implemented

The Potato BLOCK shader now mirrors the important vanilla 1.21.1 solid/cutout
semantics established by Patch 038:

Vertex:
- Position + ChunkOffset
- captured Projection * ModelView
- OpenGL clip -> Vulkan clip conversion
- Color
- UV0 block-atlas coordinates
- UV2 lightmap coordinates
- exact `minecraft_sample_lightmap` coordinate clamp
- `fog_distance` with SPHERE/CYLINDER FogShape behavior

Fragment:
- Sampler0 block atlas
- vertex Color * Sampler2 lightmap
- ColorModulator
- cutout alpha threshold 0.1
- vanilla `linear_fog`
- FogStart / FogEnd / FogColor

Pipeline:
- BLOCK stride/layout derived from runtime
- LEQUAL depth test
- depth writes
- back-face culling
- clockwise front-face after clip-Y conversion
- no blending for solid/cutout

Still deferred:
- atlas mip chain / cutout_mipped threshold 0.5
- animated atlas frame synchronization
- translucent layer sorting/blending
- live coalesced lightmap GPU updates

## Frame architecture

Matrices and global shader state are captured once per RenderType layer.

Each section draw sends only:
- tracked Vulkan vertex resource
- ChunkOffset

A validation batch:
- records 2..32 real Minecraft section draws;
- begins dynamic rendering once;
- binds pipeline once;
- binds atlas/lightmap descriptor set once;
- binds one shared UINT32 QUADS index buffer once;
- pushes per-section ChunkOffset;
- issues vkCmdDrawIndexed for each section;
- ends rendering once;
- submits once;
- performs one finite validation fence wait.

There is no per-draw queue submission or fence wait.

Because OpenGL is still visible/authoritative, the Vulkan validation batch
retires after one successful batch. Full continuous execution begins only at
the visible cutover milestone.

Next:
`VULKAN_VISIBLE_SECTION_FRAME_CUTOVER`