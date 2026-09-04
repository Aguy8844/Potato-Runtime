# Plain VertexBuffer Draw State

Patch 035 is an evidence-only bytecode census.

Patch 034 proved that real Minecraft MeshData can be mirrored into real Vulkan
VkBuffer resources.

The remaining blocker before first Minecraft Vulkan draw execution is state
ownership for the direct VertexBuffer.draw() path.

The world test that motivated this census observed:

- 3,760,483 direct/plain VertexBuffer draws;
- 83,245 shader-wrapped draws.

Therefore _drawWithShader(...) cannot be treated as the universal Minecraft
draw boundary.

Canonical evidence:

_dropoff/state/035_plain_vertexbuffer_draw_state/plain-draw-state-census.txt

Raw javap output for every discovered class is stored beside it.

Patch 036 must use this evidence to define the narrowest backend-neutral direct
draw context before any real Minecraft geometry is rendered with Vulkan.
## Patch 036 — Live section-layer draw context

Patch 035 proves that direct chunk/world VertexBuffer draws are owned by
`LevelRenderer.renderSectionLayer(...)`.

The exact sequence is:

1. `RenderType.setupRenderState()`;
2. `RenderSystem.getShader()`;
3. `ShaderInstance.setDefaultUniforms(QUADS, modelView, projection, window)`;
4. `ShaderInstance.apply()`;
5. for each visible section:
   - resolve the RenderSection VertexBuffer;
   - calculate camera-relative CHUNK_OFFSET;
   - set + upload CHUNK_OFFSET;
   - bind VertexBuffer;
   - draw VertexBuffer;
6. reset CHUNK_OFFSET;
7. `ShaderInstance.clear()`;
8. `VertexBuffer.unbind()`;
9. NeoForge render-stage dispatch;
10. `RenderType.clearRenderState()`.

Patch 036 captures a backend-neutral `SectionLayerDrawContext` immediately
before the direct VertexBuffer.draw() call.

The context includes:
- RenderType;
- current ShaderInstance;
- model-view matrix snapshot;
- projection matrix snapshot;
- camera position;
- per-section CHUNK_OFFSET.

The existing DrawBufferBackendState supplies:
- exact VertexFormat object;
- vertex stride;
- mode;
- index type;
- counts;
- upload generation.

The Vulkan geometry prototype then verifies whether the exact VertexBuffer being
drawn already has a current real VkBuffer mirror.

No Vulkan draw command is executed in Patch 036.

The next milestone is `VULKAN_SECTION_LAYER_DRAW_PROTOTYPE`.
## Patch 037 — First real Minecraft Vulkan section draw

Patch 036 proved that a live LevelRenderer section draw can be correlated with
a current real Vulkan VkBuffer sidecar.

Patch 037 executes one bounded proof draw.

Eligibility is intentionally narrow:
- STATIC VertexBuffer;
- DefaultVertexFormat.BLOCK;
- 32-byte stride;
- QUADS;
- SHORT index type;
- Minecraft sequential indices;
- current Vulkan geometry sidecar;
- matching active ShaderInstance vertex format;
- captured matrices;
- non-zero CHUNK_OFFSET.

### Index expansion

Vulkan has no QUADS primitive topology.

For the one proof resource Potato generates a real UINT16 Vulkan index buffer
that expands every four Minecraft vertices into two triangles.

Culling is disabled in the debug pipeline, so the proof is independent of the
eventual front-face policy.

### Transformation

The vertex shader consumes the real BLOCK Position attribute at byte offset 0.

The CPU supplies:
- projection * model-view matrix;
- per-section CHUNK_OFFSET.

The shader converts Minecraft/OpenGL clip conventions to Vulkan:
- Y is inverted;
- Z is remapped from [-W,+W] to [0,+W].

### Rasterization proof

The draw executes:
- vkCmdBindVertexBuffers;
- vkCmdBindIndexBuffer;
- vkCmdPushConstants;
- vkCmdDrawIndexed.

An occlusion query surrounds the draw.

The milestone is verified only if:
- queue submission succeeds;
- the fence completes;
- query readback succeeds;
- rasterized sample count is greater than zero.

### Intentionally incomplete

This is a debug-shaded geometry proof, not faithful block rendering.

Not implemented yet:
- block atlas sampling;
- alpha/cutout semantics;
- lightmap;
- vertex color;
- normals;
- fog;
- exact RenderType pipeline state.

OpenGL remains authoritative and still performs the normal Minecraft draw.