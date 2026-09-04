# BLOCK Resource Layout

Patch 039 is the final evidence-only census before Potato creates Vulkan image
resources for faithful BLOCK rendering.

Patch 038 already mapped shader semantics:
- Sampler0 = block atlas;
- Sampler2 = lightmap;
- Position / Color / UV0 / UV2 / Normal declarations;
- Color * sampled lightmap in the vertex shader;
- texture * vertexColor * ColorModulator in the fragment shader;
- RenderType-specific alpha discard.

Patch 039 maps:
- exact DefaultVertexFormat.BLOCK element byte layout;
- NativeImage CPU pixel access;
- TextureAtlas stitching/reload ownership;
- SpriteContents mip/image ownership;
- LightTexture CPU image and update lifecycle.

Canonical evidence:

_dropoff/state/039_block_resource_layout/block-resource-layout-census.txt

Patch 040 may create Vulkan atlas/lightmap images only from evidence established
here.