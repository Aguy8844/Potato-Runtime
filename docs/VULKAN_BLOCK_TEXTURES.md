# Vulkan BLOCK Texture Resources

Patch 041 turns Potato-owned CPU BLOCK resources into real persistent Vulkan
images.

## Inputs

From Patch 040:
- `BlockAtlasSnapshot` — stitched base-mip RGBA8 atlas;
- `LightmapSnapshot` — current 16x16 RGBA8 lightmap.

No OpenGL readback is used.

## GPU resources

Each snapshot becomes:
- `VkImage` using `VK_FORMAT_R8G8B8A8_UNORM`;
- device-local `VkDeviceMemory`;
- `VkImageView`;
- persistent host-visible staging `VkBuffer`;
- one reusable command pool / command buffer / fence for the initial upload.

Image usage:
- `VK_IMAGE_USAGE_TRANSFER_DST_BIT`;
- `VK_IMAGE_USAGE_SAMPLED_BIT`.

Upload lifecycle:
1. CPU snapshot -> direct temporary copy;
2. persistent mapped staging buffer;
3. image layout UNDEFINED -> TRANSFER_DST_OPTIMAL;
4. `vkCmdCopyBufferToImage`;
5. TRANSFER_DST_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL;
6. one queue submit;
7. finite fence wait;
8. no `vkDeviceWaitIdle`.

The temporary direct copy exists only because the backend-neutral snapshots are
ordinary Java-owned buffers. It is one-time, bounded and freed immediately.

## Performance policy

Patch 041 uploads only the first available atlas and lightmap snapshots.

It does **not** mirror every lightmap generation yet.

That live synchronization belongs to the textured frame scheduler, where
updates can be coalesced and synchronized with actual Vulkan image sampling.

Therefore Patch 041 adds:
- no per-frame queue submit;
- no per-frame fence wait;
- no descriptor set yet;
- no Vulkan shader sampling yet.

Next milestone:

`VULKAN_TEXTURED_SECTION_DRAW`