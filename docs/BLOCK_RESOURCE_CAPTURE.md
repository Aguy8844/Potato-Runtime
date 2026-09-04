# BLOCK Resource Capture

Patch 040 establishes backend-neutral CPU ownership for the resources required
by the first faithful Vulkan BLOCK draw.

## Runtime-derived BLOCK vertex layout

Potato does not hard-code the 1.21.1 byte offsets as renderer constants.

It queries:

- `DefaultVertexFormat.BLOCK.getVertexSize()`;
- `VertexFormat.getOffset(VertexFormatElement.*)`;
- element type and byte size.

The current verified layout is:

- Position: offset 0, FLOAT, 12 bytes;
- Color: offset 12, UBYTE, 4 bytes;
- UV0: offset 16, FLOAT, 8 bytes;
- UV2: offset 24, SHORT, 4 bytes;
- Normal: offset 28, BYTE, 3 bytes;
- trailing padding: 1 byte;
- stride: 32 bytes.

Patch 041 translates this backend-neutral description into Vulkan vertex input
descriptions.

## Block atlas capture

The exact capture boundary is the return of:

`TextureAtlas.upload(SpriteLoader.Preparations)`

Only `TextureAtlas.LOCATION_BLOCKS` is captured.

Potato reconstructs the stitched base mip from the finished
`TextureAtlasSprite` map:

- atlas dimensions come from `SpriteLoader.Preparations`;
- sprite atlas position comes from `TextureAtlasSprite.getX/getY`;
- sprite dimensions come from `SpriteContents`;
- RGBA pixels come from `TextureAtlasSprite.getPixelRGBA`.

For animated sheets Patch 040 captures animation grid frame 0 only.
Tick-synchronized animation is intentionally deferred.

The resulting snapshot is Potato-owned RGBA8 memory.
No OpenGL texture id and no Minecraft NativeImage reference is retained.

## Lightmap capture

`LightTexture` retains a 16x16 `NativeImage` as `lightPixels`.

Patch 040 snapshots it:
- once after LightTexture construction;
- after every `updateLightTexture(float)` return.

A CRC avoids creating a new generation when the pixels did not change.

## Safety

Capture exceptions are contained inside the mixin bridge.
Minecraft's normal OpenGL resource upload continues unchanged.

No OpenGL readback occurs.
No Vulkan image is created in Patch 040.

Next milestone:

`VULKAN_BLOCK_TEXTURE_UPLOAD`