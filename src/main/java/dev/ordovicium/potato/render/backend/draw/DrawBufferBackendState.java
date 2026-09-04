package dev.ordovicium.potato.render.backend.draw;

import com.mojang.blaze3d.vertex.VertexFormat;

/**
 * Backend-neutral per-VertexBuffer lifecycle state.
 *
 * <p>Patch 040b retires the synchronized census implementation. Runtime proof
 * established that all live draw-submission events used by this bridge execute
 * on Minecraft's Render thread. Keeping this object render-thread-owned avoids
 * lock traffic on every chunk draw/upload.</p>
 */
public final class DrawBufferBackendState {
    private String usageName =
            "UNKNOWN";

    private long uploadGeneration;

    private boolean uploaded;
    private boolean closed;

    private int vertexCount;
    private int indexCount;
    private int vertexBytes;
    private int indexBytes;

    private VertexFormat format;
    private VertexFormat.Mode mode;
    private VertexFormat.IndexType indexType;

    private boolean explicitIndexBuffer;

    public void initialize(
            String usageName
    ) {
        this.usageName =
                usageName == null
                        ? "UNKNOWN"
                        : usageName;
    }

    public void observeUpload(
            DrawGeometryView geometry
    ) {
        uploadGeneration++;

        uploaded = true;
        closed = false;

        vertexCount =
                geometry.vertexCount();

        indexCount =
                geometry.indexCount();

        vertexBytes =
                geometry.vertexBytes()
                        .remaining();

        indexBytes =
                geometry.indexBytes() == null
                        ? 0
                        : geometry.indexBytes()
                                .remaining();

        format =
                geometry.format();

        mode =
                geometry.mode();

        indexType =
                geometry.indexType();

        explicitIndexBuffer =
                geometry.indexBytes()
                        != null;
    }

    public void observeClose() {
        closed = true;
    }

    public String usageName() {
        return usageName;
    }

    public long uploadGeneration() {
        return uploadGeneration;
    }

    public boolean uploaded() {
        return uploaded;
    }

    public boolean closed() {
        return closed;
    }

    public int vertexCount() {
        return vertexCount;
    }

    public int indexCount() {
        return indexCount;
    }

    public int vertexBytes() {
        return vertexBytes;
    }

    public int indexBytes() {
        return indexBytes;
    }

    public VertexFormat format() {
        return format;
    }

    public VertexFormat.Mode mode() {
        return mode;
    }

    public VertexFormat.IndexType indexType() {
        return indexType;
    }

    public int vertexStrideBytes() {
        return format == null
                ? 0
                : format.getVertexSize();
    }

    /**
     * Diagnostic string creation is intentionally lazy.
     */
    public String formatDescription() {
        return String.valueOf(
                format
        );
    }

    public String modeName() {
        return mode == null
                ? ""
                : mode.name();
    }

    public String indexTypeName() {
        return indexType == null
                ? ""
                : indexType.name();
    }

    public boolean explicitIndexBuffer() {
        return explicitIndexBuffer;
    }
}
