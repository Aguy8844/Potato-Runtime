package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;
import dev.ordovicium.potato.render.backend.draw.DrawShaderContext;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionSink;
import dev.ordovicium.potato.render.backend.draw.SectionLayerDrawContext;
import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;
import dev.ordovicium.potato.render.lod.PotatoLodRuntime;
import dev.ordovicium.potato.render.visibility.PotatoVisibilityEngine;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionRuntime;
import dev.ordovicium.potato.render.visibility.SectionVisibilityTier;
import net.minecraft.client.renderer.RenderType;

/**
 * Windowless production bridge used while OpenGL remains the visible renderer.
 *
 * <p>The bridge performs two bounded jobs only:
 * <ul>
 *     <li>mirror a small working set of STATIC BLOCK meshes into real Vulkan
 *     buffers so the later headless depth/occlusion pass has GPU resources;</li>
 *     <li>run the already-established camera visibility classifier for those
 *     mirrored section buffers.</li>
 * </ul>
 *
 * <p>It never creates a GLFW window/surface and never presents. Patch 058's
 * separate LOD runtime may substitute eligible distant SOLID OpenGL geometry,
 * but the headless Vulkan observer itself never cancels visible draws. Patch 063 keeps the temporary OpenGL temporal path but uses conservative any-samples queries and larger adaptive LOD capacity while the visible Vulkan world/texture cutover remains safety-gated.</p>
 */
final class VulkanHeadlessDrawSubmissionBackend
        implements DrawSubmissionSink, AutoCloseable {

    private final VulkanGeometryUploadPrototype geometryUpload;
    private final PotatoVisibilityEngine visibilityEngine;
    private final JsonObject report;

    private long layerBeginCount;
    private long layerEndCount;
    private long classifiedDrawCount;
    private long hotCount;
    private long warmCount;
    private long coldCount;
    private long mirroredReadyDrawCount;

    private boolean closed;

    VulkanHeadlessDrawSubmissionBackend(
            VulkanGeometryUploadPrototype geometryUpload,
            JsonObject report
    ) {
        this.geometryUpload = geometryUpload;
        this.report = report;
        this.visibilityEngine =
                new PotatoVisibilityEngine(
                        report
                );

        PotatoLodRuntime.bindReport(
                report
        );

        PotatoTemporalOcclusionRuntime.bindReport(
                report
        );
    }

    @Override
    public boolean wantsNewStaticBlockUpload() {
        return !closed
                && geometryUpload
                .canAdmitNewStaticResource();
    }

    @Override
    public boolean wantsUpload(
            DrawBufferBackendState state
    ) {
        return !closed
                && geometryUpload
                .wantsUpload(
                        state
                );
    }

    @Override
    public boolean wantsSectionLayerFrame(
            RenderType renderType
    ) {
        return !closed
                && renderType != null;
    }

    @Override
    public boolean wantsSectionLayerDraw(
            DrawBufferBackendState state
    ) {
        return !closed
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
        if (closed) {
            return;
        }

        visibilityEngine.beginLayer(
                context
        );

        layerBeginCount++;
    }

    @Override
    public void onSectionLayerDrawFast(
            DrawBufferBackendState state,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        if (closed) {
            return;
        }

        SectionVisibilityTier tier =
                visibilityEngine.classify(
                        chunkOffsetX,
                        chunkOffsetY,
                        chunkOffsetZ
                );

        classifiedDrawCount++;

        switch (tier) {
            case HOT ->
                    hotCount++;
            case WARM ->
                    warmCount++;
            case COLD ->
                    coldCount++;
        }

        if (geometryUpload.readyResource(
                state
        ) != null) {
            mirroredReadyDrawCount++;
        }
    }

    @Override
    public void onSectionLayerEnd() {
        if (closed) {
            return;
        }

        visibilityEngine.endLayer();
        layerEndCount++;
    }

    @Override
    public void onSectionLayerDraw(
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
        // Historical callback remains retired.
    }

    @Override
    public void onClose(
            DrawBufferBackendState state
    ) {
        geometryUpload.onClose(
                state
        );
    }

    boolean verified() {
        return !closed
                && geometryUpload.verified()
                && visibilityEngine.verified()
                && classifiedDrawCount > 0
                && mirroredReadyDrawCount > 0;
    }

    void enrich() {
        geometryUpload.enrich();
        visibilityEngine.enrich();
        PotatoLodRuntime.enrich(
                report
        );
        PotatoTemporalOcclusionRuntime.enrich(
                report
        );

        report.addProperty(
                "headlessDrawSubmissionBackendInstalled",
                true
        );
        report.addProperty(
                "headlessDrawSubmissionBackendMode",
                "BOUNDED_GEOMETRY_MIRROR_PLUS_TRANSITION_RECOVERY_CONSERVATIVE_OCCLUSION_LOD_STAGE3"
        );
        report.addProperty(
                "headlessDrawSubmissionLayerBeginCount",
                layerBeginCount
        );
        report.addProperty(
                "headlessDrawSubmissionLayerEndCount",
                layerEndCount
        );
        report.addProperty(
                "headlessDrawSubmissionClassifiedDrawCount",
                classifiedDrawCount
        );
        report.addProperty(
                "headlessDrawSubmissionHotCount",
                hotCount
        );
        report.addProperty(
                "headlessDrawSubmissionWarmCount",
                warmCount
        );
        report.addProperty(
                "headlessDrawSubmissionColdCount",
                coldCount
        );
        report.addProperty(
                "headlessDrawSubmissionMirroredReadyDrawCount",
                mirroredReadyDrawCount
        );
        report.addProperty(
                "headlessDrawSubmissionCancelsOpenGlDraws",
                false
        );
        report.addProperty(
                "headlessDrawSubmissionCreatesPresentationResources",
                false
        );
        report.addProperty(
                "headlessDrawSubmissionDepthOcclusionEnabled",
                false
        );
        report.addProperty(
                "headlessDrawSubmissionTemporalOpenGlOcclusionEnabled",
                true
        );
        report.addProperty(
                "headlessDrawSubmissionVisibleLodEnabled",
                true
        );
        report.addProperty(
                "headlessDrawSubmissionVisibleLodSolidOnly",
                true
        );
        report.addProperty(
                "headlessDrawSubmissionVisibleLodSubstitutesEligibleOpenGlGeometry",
                true
        );
        report.addProperty(
                "headlessDrawSubmissionNextMilestone",
                "POTATO_ENGINE_VULKAN_WORLD_TEXTURE_CUTOVER"
        );
        report.addProperty(
                "headlessDrawSubmissionVerified",
                verified()
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        enrich();

        visibilityEngine.close();
        geometryUpload.close();
        PotatoTemporalOcclusionRuntime.close();
        PotatoLodRuntime.close();

        closed = true;

        report.addProperty(
                "headlessDrawSubmissionBackendClosed",
                true
        );
    }
}
