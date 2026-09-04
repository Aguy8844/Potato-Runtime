# Vulkan Textured Section Draw

Patch 042 proves the first real Minecraft BLOCK draw that consumes Potato's
real Vulkan texture resources.

Inputs:
- real Minecraft STATIC BLOCK VkBuffer;
- runtime-derived 32-byte BLOCK vertex layout;
- generated sequential QUADS index buffer;
- captured Minecraft MVP + chunk offset;
- device-local block atlas VkImage;
- device-local 16x16 lightmap VkImage.

Descriptor mapping:
- set 0 / binding 0 = block atlas, vanilla Sampler0 semantics;
- set 0 / binding 1 = lightmap, vanilla Sampler2 semantics.

Vertex inputs:
- Position = R32G32B32_SFLOAT;
- Color = R8G8B8A8_UNORM;
- UV0 = R32G32_SFLOAT;
- UV2 = R16G16_SINT.

The shader reproduces the vanilla lightmap coordinate rule:
`clamp(UV2 / 256.0, 0.5/16, 15.5/16)`.

The first proof is deliberately restricted to the non-mipmapped `solid` and
`cutout` BLOCK layers because Patch 040/041 capture only atlas mip 0.

Deferred fidelity:
- cutout_mipped;
- atlas mip chain;
- animated atlas frame synchronization;
- fog;
- dynamic ColorModulator values;
- culling/depth parity;
- multi-section frame scheduling.

The proof remains one-shot and hidden. OpenGL stays authoritative.

Next milestone:
`VULKAN_TEXTURED_MULTI_SECTION_FRAME`