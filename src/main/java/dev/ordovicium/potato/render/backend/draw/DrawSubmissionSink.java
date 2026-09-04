package dev.ordovicium.potato.render.backend.draw;

import net.minecraft.client.renderer.RenderType;

/**
 * Backend-neutral VertexBuffer lifecycle consumer.
 *
 * <p>The production section path is layer-oriented: expensive matrices and
 * shader globals are captured once at layer begin, then each section draw
 * sends only buffer identity + ChunkOffset.</p>
 */
public interface DrawSubmissionSink {

    default boolean wantsBufferCreated(
            DrawBufferBackendState state
    ) {
        return false;
    }

    /**
     * Cheap allocation gate used before Potato creates a per-VertexBuffer
     * sidecar for a new STATIC BLOCK upload.
     */
    default boolean wantsNewStaticBlockUpload() {
        return false;
    }

    default boolean wantsUpload(
            DrawBufferBackendState state
    ) {
        return true;
    }

    default boolean wantsShaderDraw(
            DrawBufferBackendState state
    ) {
        return false;
    }

    default boolean wantsPlainDraw(
            DrawBufferBackendState state
    ) {
        return false;
    }

    default boolean wantsSectionLayerFrame(
            RenderType renderType
    ) {
        return false;
    }

    default boolean wantsSectionLayerDraw(
            DrawBufferBackendState state
    ) {
        return false;
    }

    default boolean wantsClose(
            DrawBufferBackendState state
    ) {
        return true;
    }

    void onBufferCreated(
            DrawBufferBackendState state
    );

    void onUpload(
            DrawBufferBackendState state,
            DrawGeometryView geometry
    );

    void onShaderDraw(
            DrawBufferBackendState state,
            DrawShaderContext context
    );

    void onPlainDraw(
            DrawBufferBackendState state
    );

    default void onSectionLayerBegin(
            SectionLayerFrameContext context
    ) {
    }

    /**
     * Production fast path. No Matrix4f copy is created for this callback.
     */
    default void onSectionLayerDrawFast(
            DrawBufferBackendState state,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
    }

    default void onSectionLayerEnd() {
    }

    /**
     * Historical Patch 036 callback retained while older diagnostics are still
     * present in the tree. Production LevelRenderer no longer invokes it.
     */
    default void onSectionLayerDraw(
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
    }

    void onClose(
            DrawBufferBackendState state
    );
}