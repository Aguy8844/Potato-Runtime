package dev.ordovicium.potato.render.vulkan;

import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;
import dev.ordovicium.potato.render.backend.draw.DrawShaderContext;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionSink;
import dev.ordovicium.potato.render.backend.draw.SectionLayerDrawContext;
import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;
import dev.ordovicium.potato.render.visibility.PotatoVisibilityEngine;
import dev.ordovicium.potato.render.visibility.SectionVisibilityTier;
import net.minecraft.client.renderer.RenderType;

/**
 * Production-shaped Vulkan BLOCK draw backend.
 *
 * <p>Patch 043 retires the one-shot textured draw from normal dispatch. The
 * active path now mirrors bounded geometry, snapshots shader state once per
 * layer and records multiple section draws into one validation frame batch.</p>
 */
final class VulkanDrawSubmissionBackend
        implements DrawSubmissionSink, AutoCloseable {

    /**
     * Patch 064b promotes the proven textured multi-section renderer into the
     * gameplay Vulkan world-raster path. It remains fail-open because this
     * backend never suppresses the original OpenGL draw.
     *
     * Disable explicitly with:
     * -Dpotato.vulkan.worldRaster=false
     */
    private static final boolean VULKAN_WORLD_RASTER_ENABLED =
            Boolean.parseBoolean(
                    System.getProperty(
                            "potato.vulkan.worldRaster",
                            "true"
                    )
            );

    private final VulkanGeometryUploadPrototype geometryUpload;
    private final VulkanBlockTextureUploadPrototype textureUpload;
    private final VulkanTexturedMultiSectionFrame multiSectionFrame;
    private final PotatoVisibilityEngine visibilityEngine;

    VulkanDrawSubmissionBackend(
            VulkanGeometryUploadPrototype geometryUpload,
            VulkanBlockTextureUploadPrototype textureUpload,
            VulkanTexturedMultiSectionFrame multiSectionFrame,
            JsonObject report
    ) {
        this.geometryUpload =
                geometryUpload;

        this.textureUpload =
                textureUpload;

        this.multiSectionFrame =
                multiSectionFrame;

        this.visibilityEngine =
                new PotatoVisibilityEngine(
                        report
                );
    }

    /**
     * Production STATIC BLOCK geometry admission.
     *
     * <p>064b activated the continuous Vulkan SOLID layer but accidentally
     * inherited DrawSubmissionSink.wantsNewStaticBlockUpload() = false.
     * That prevented new Minecraft BLOCK VertexBuffers from receiving their
     * backend sidecar, so geometry upload and Vulkan draw candidates remained
     * exactly zero despite thousands of Vulkan layer begins.</p>
     *
     * <p>Admission remains bounded and fail-open. If Vulkan is disabled, the
     * batch retires, or the bounded geometry store is full, this returns false
     * and Minecraft continues through its original OpenGL path.</p>
     */
    @Override
    public boolean wantsNewStaticBlockUpload() {
        return VULKAN_WORLD_RASTER_ENABLED
                && !multiSectionFrame
                .hotPathRetired()
                && geometryUpload
                .canAdmitNewStaticResource();
    }

    @Override
    public boolean wantsUpload(
            DrawBufferBackendState state
    ) {
        if (!VULKAN_WORLD_RASTER_ENABLED) {
            return false;
        }

        if (multiSectionFrame
                .hotPathRetired()) {
            return false;
        }

        return geometryUpload
                .wantsUpload(
                        state
                );
    }

    @Override
    public boolean wantsSectionLayerFrame(
            RenderType renderType
    ) {
        if (!VULKAN_WORLD_RASTER_ENABLED) {
            return false;
        }

        return multiSectionFrame
                .wantsLayer(
                        renderType
                );
    }

    @Override
    public boolean wantsSectionLayerDraw(
            DrawBufferBackendState state
    ) {
        if (!VULKAN_WORLD_RASTER_ENABLED) {
            return false;
        }

        return multiSectionFrame
                .acceptingDraws()
                && geometryUpload
                .wantsSectionLayerDraw(
                        state
                );
    }

    @Override
    public boolean wantsClose(
            DrawBufferBackendState state
    ) {
        return geometryUpload
                .wantsClose(
                        state
                );
    }

    @Override
    public void onBufferCreated(
            DrawBufferBackendState state
    ) {
    }

    @Override
    public void onUpload(
            DrawBufferBackendState state,
            DrawGeometryView geometry
    ) {
        geometryUpload.onUpload(
                state,
                geometry
        );
    }

    @Override
    public void onShaderDraw(
            DrawBufferBackendState state,
            DrawShaderContext context
    ) {
    }

    @Override
    public void onPlainDraw(
            DrawBufferBackendState state
    ) {
    }

    @Override
    public void onSectionLayerBegin(
            SectionLayerFrameContext context
    ) {
        if (!VULKAN_WORLD_RASTER_ENABLED) {
            return;
        }

        visibilityEngine.beginLayer(
                context
        );

        textureUpload
                .tryUploadInitialSnapshots();

        multiSectionFrame.beginLayer(
                context,
                textureUpload
        );
    }

    @Override
    public void onSectionLayerDrawFast(
            DrawBufferBackendState state,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        if (!VULKAN_WORLD_RASTER_ENABLED) {
            return;
        }

        /*
         * Patch 044 classifies every section that reaches the active Potato
         * renderer path, but deliberately does not cancel the baseline or the
         * current validation batch yet.
         *
         * Patch 045 will consume HOT/WARM/COLD before expensive mesh work.
         */
        SectionVisibilityTier visibilityTier =
                visibilityEngine.classify(
                        chunkOffsetX,
                        chunkOffsetY,
                        chunkOffsetZ
                );

        VulkanGeometryBufferResource resource =
                geometryUpload.readyResource(
                        state
                );

        if (resource == null) {
            return;
        }

        multiSectionFrame.recordDraw(
                resource,
                state,
                chunkOffsetX,
                chunkOffsetY,
                chunkOffsetZ
        );
    }

    @Override
    public void onSectionLayerEnd() {
        if (!VULKAN_WORLD_RASTER_ENABLED) {
            return;
        }

        multiSectionFrame.endLayer(
                textureUpload
        );

        visibilityEngine.endLayer();
    }

    boolean visibilityEngineVerified() {
        visibilityEngine.enrich();

        return visibilityEngine
                .verified();
    }

    void enrichVisibilityEngine() {
        visibilityEngine.enrich();
    }

    @Override
    public void onSectionLayerDraw(
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
        // Historical callback retired from the production section path.
    }

    @Override
    public void onClose(
            DrawBufferBackendState state
    ) {
        geometryUpload.onClose(
                state
        );
    }

    @Override
    public void close() {
        /*
         * The frame batch owns descriptor/pipeline resources referencing
         * texture image views, so it must close before texture VkImages.
         */
        multiSectionFrame.close();

        visibilityEngine.close();

        geometryUpload.close();
    }
}
