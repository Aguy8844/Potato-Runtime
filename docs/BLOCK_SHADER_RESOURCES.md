# Block Shader Resources

Patch 038 is an evidence-only census following the first verified real
Minecraft Vulkan section draw.

Patch 037a proved:
- real Minecraft BLOCK VkBuffer;
- generated Vulkan UINT16 quad index buffer;
- captured matrices + CHUNK_OFFSET;
- vkCmdDrawIndexed;
- 13,299 rasterized samples.

The remaining fidelity gap is resource/state ownership:
- block atlas;
- UV0;
- lightmap / UV2;
- vertex color;
- normal;
- alpha/cutout behavior;
- fog and remaining uniforms.

Canonical evidence:

_dropoff/state/038_block_shader_resources/block-shader-resource-census.txt

Extracted vanilla shader assets are stored under:

_dropoff/state/038_block_shader_resources/shader-assets/

Patch 039 must use this evidence rather than guessing sampler slots or shader
resource semantics.