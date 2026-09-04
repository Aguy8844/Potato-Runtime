package dev.ordovicium.potato.render.resource;

import java.nio.ByteBuffer;

/**
 * Owned CPU copy of Minecraft's dynamic 16x16 lightmap.
 */
public final class LightmapSnapshot {
    private final long generation;

    private final int width;
    private final int height;

    private final ByteBuffer rgba;

    private final long crc32;
    private final String captureThread;
    private final long capturedAtNanos;

    LightmapSnapshot(
            long generation,
            int width,
            int height,
            byte[] rgba,
            long crc32,
            String captureThread,
            long capturedAtNanos
    ) {
        this.generation = generation;
        this.width = width;
        this.height = height;

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
}