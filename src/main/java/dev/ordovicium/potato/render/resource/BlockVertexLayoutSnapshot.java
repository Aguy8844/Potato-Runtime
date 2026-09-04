package dev.ordovicium.potato.render.resource;

/**
 * Runtime-derived Minecraft BLOCK vertex layout.
 *
 * <p>No Vulkan or OpenGL constants are stored here. Patch 041 may translate
 * these backend-neutral values into VkVertexInputAttributeDescription data.</p>
 */
public record BlockVertexLayoutSnapshot(
        int strideBytes,

        int positionOffset,
        String positionType,
        int positionBytes,

        int colorOffset,
        String colorType,
        int colorBytes,

        int uv0Offset,
        String uv0Type,
        int uv0Bytes,

        int uv2Offset,
        String uv2Type,
        int uv2Bytes,

        int normalOffset,
        String normalType,
        int normalBytes,

        int trailingPaddingBytes
) {
    public boolean verifiedForMinecraft1211() {
        return strideBytes == 32
                && positionOffset == 0
                && "FLOAT".equals(positionType)
                && positionBytes == 12

                && colorOffset == 12
                && "UBYTE".equals(colorType)
                && colorBytes == 4

                && uv0Offset == 16
                && "FLOAT".equals(uv0Type)
                && uv0Bytes == 8

                && uv2Offset == 24
                && "SHORT".equals(uv2Type)
                && uv2Bytes == 4

                && normalOffset == 28
                && "BYTE".equals(normalType)
                && normalBytes == 3

                && trailingPaddingBytes == 1;
    }
}