package dev.ordovicium.potato.render.resource;

import java.nio.ByteBuffer;

/**
 * Owned CPU copy of the stitched block atlas base mip.
 *
 * <p>The snapshot intentionally owns no Minecraft NativeImage reference and no
 * OpenGL texture id. Consumers receive read-only ByteBuffer views.</p>
 */
public final class BlockAtlasSnapshot {
    private final long generation;

    private final int width;
    private final int height;
    private final int declaredMipLevel;

    private final int spriteCount;
    private final int animatedSheetSpriteCount;

    private final ByteBuffer rgba;

    private final long crc32;
    private final String captureThread;
    private final long capturedAtNanos;

    BlockAtlasSnapshot(
            long generation,
            int width,
            int height,
            int declaredMipLevel,
            int spriteCount,
            int animatedSheetSpriteCount,
            byte[] rgba,
            long crc32,
            String captureThread,
            long capturedAtNanos
    ) {
        this.generation = generation;
        this.width = width;
        this.height = height;
        this.declaredMipLevel = declaredMipLevel;
        this.spriteCount = spriteCount;
        this.animatedSheetSpriteCount =
                animatedSheetSpriteCount;

        this.rgba =
                ByteBuffer.wrap(
                        rgba
                ).asReadOnlyBuffer();

        this.crc32 = crc32;
        this.captureThread = captureThread;
        this.capturedAtNanos = capturedAtNanos;
    }

    public long generation() {
        return generation;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int declaredMipLevel() {
        return declaredMipLevel;
    }

    public int spriteCount() {
        return spriteCount;
    }

    public int animatedSheetSpriteCount() {
        return animatedSheetSpriteCount;
    }

    public int byteSize() {
        return rgba.capacity();
    }

    public ByteBuffer rgbaView() {
        ByteBuffer view =
                rgba.asReadOnlyBuffer();

        view.position(0);

        return view;
    }

    public long crc32() {
        return crc32;
    }

    public String captureThread() {
        return captureThread;
    }

    public long capturedAtNanos() {
        return capturedAtNanos;
    }

    public boolean baseMipOnly() {
        return true;
    }

    public String animationFramePolicy() {
        return "STATIC_OR_ANIMATION_GRID_FRAME_0";
    }
}