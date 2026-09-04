package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendBridge;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionDispatcher;
import dev.ordovicium.potato.render.lod.PotatoLodBuildResult;
import dev.ordovicium.potato.render.lod.PotatoLodProxyBridge;
import dev.ordovicium.potato.render.lod.PotatoLodRuntime;
import dev.ordovicium.potato.render.lod.PotatoOpenGlLodProxy;
import dev.ordovicium.potato.render.surface.LosslessSurfaceMergeAnalyzer;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionBridge;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionFrame;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionRuntime;
import dev.ordovicium.potato.render.vulkan.PotatoMeshGenerationBridge;
import dev.ordovicium.potato.render.vulkan.VulkanRegionArenaIngress;
import dev.ordovicium.potato.render.vulkan.VulkanSurfaceClusterVisibility;
import dev.ordovicium.potato.render.vulkan.VulkanVisibleGeometryResidency;
import dev.ordovicium.potato.render.vulkan.VulkanVisibleSolidCutoverPolicy;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL43C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Narrow production VertexBuffer seam for BLOCK section geometry.
 *
 * <p>Patch 058 keeps Vulkan mirroring bounded as before, while a separate
 * asynchronous LOD path may build a compact OpenGL proxy for any sufficiently
 * complex STATIC BLOCK QUADS buffer. Proxy CPU work never runs on the render
 * thread; only the final VBO/VAO upload does.</p>
 */
@Mixin(VertexBuffer.class)
abstract class VertexBufferDrawSubmissionMixin
        implements DrawBufferBackendBridge,
        PotatoLodProxyBridge,
        PotatoTemporalOcclusionBridge,
        PotatoMeshGenerationBridge {

    /*
     * Visible SOLID policy is centralized in VulkanVisibleSolidCutoverPolicy.
     * LOD + region-arena capture stay independent; the legacy textured mirror
     * is restored only because the already-proven atomic presentation path
     * still consumes it during the 088 cutover milestone.
     */

    @Unique
    private DrawBufferBackendState
            potato$drawBackendState;

    @Unique
    private VertexBuffer.Usage
            potato$usage;

    @Unique
    private long potato$lodGeneration;

    @Unique
    private PotatoOpenGlLodProxy potato$lodProxy;

    @Unique
    private int potato$currentLodTier;

    @Unique
    private boolean potato$lodClosed;

    @Unique
    private int potato$occlusionQueryId;

    @Unique
    private boolean potato$occlusionQueryInFlight;

    @Unique
    private boolean potato$occlusionQueryActive;

    @Unique
    private boolean potato$occlusionLastResultOccluded;

    @Unique
    private int potato$occlusionConsecutiveSkips;

    @Unique
    private long potato$occlusionGeneration;

    @Unique
    private long potato$occlusionQueryGeneration;

    @Unique
    private PotatoTemporalOcclusionFrame potato$occlusionEvidenceFrame;

    @Override
    public DrawBufferBackendState
    potato$drawBackendState() {
        if (potato$drawBackendState == null) {
            potato$drawBackendState =
                    new DrawBufferBackendState();

            potato$drawBackendState.initialize(
                    potato$usage == null
                            ? "UNKNOWN"
                            : potato$usage.name()
            );
        }

        return potato$drawBackendState;
    }

    @Override
    public DrawBufferBackendState
    potato$drawBackendStateIfPresent() {
        return potato$drawBackendState;
    }

    @Override
    public long potato$meshGeneration() {
        return potato$lodGeneration;
    }

    @Override
    public void potato$installLodBuild(
            long generation,
            PotatoLodBuildResult result
    ) {
        if (potato$lodClosed
                || generation
                != potato$lodGeneration
                || result == null
                || !result.usable()) {

            PotatoLodRuntime.onStaleBuildResult();

            return;
        }

        potato$invalidateTemporalOcclusion();

        PotatoOpenGlLodProxy replacement =
                PotatoOpenGlLodProxy.create(
                        result
                );

        if (replacement == null) {
            return;
        }

        if (potato$lodProxy != null) {
            potato$lodProxy.close();
        }

        potato$lodProxy =
                replacement;

        potato$currentLodTier =
                0;
    }

    @Override
    public boolean potato$drawLodProxy(
            int requestedTier
    ) {
        if (requestedTier <= 0
                || potato$lodClosed
                || potato$lodProxy == null) {

            potato$currentLodTier =
                    0;

            return false;
        }

        int actualTier =
                potato$lodProxy.drawBest(
                        requestedTier
                );

        potato$currentLodTier =
                actualTier;

        return actualTier > 0;
    }

    @Override
    public int potato$currentLodTier() {
        return potato$currentLodTier;
    }

    @Override
    public boolean potato$shouldSkipTemporalOcclusion(
            PotatoTemporalOcclusionFrame frame
    ) {
        if (frame == null) {
            return false;
        }

        try {
            if (potato$occlusionQueryInFlight
                    && !potato$occlusionQueryActive
                    && potato$occlusionQueryId != 0) {

                int available = GL15C.glGetQueryObjecti(
                        potato$occlusionQueryId,
                        GL15C.GL_QUERY_RESULT_AVAILABLE
                );

                if (available != 0) {
                    int samples = GL15C.glGetQueryObjecti(
                            potato$occlusionQueryId,
                            GL15C.GL_QUERY_RESULT
                    );

                    potato$occlusionQueryInFlight = false;

                    boolean generationMatches =
                            potato$occlusionQueryGeneration
                                    == potato$occlusionGeneration;

                    potato$occlusionLastResultOccluded =
                            generationMatches && samples == 0;

                    potato$occlusionConsecutiveSkips = 0;

                    PotatoTemporalOcclusionRuntime.onQueryResolved(
                            potato$occlusionLastResultOccluded
                    );
                }
            }

            if (!potato$occlusionLastResultOccluded) {
                return false;
            }

            if (potato$occlusionEvidenceFrame == null
                    || !frame.compatibleWith(
                    potato$occlusionEvidenceFrame
            )) {
                potato$occlusionLastResultOccluded = false;
                potato$occlusionConsecutiveSkips = 0;
                PotatoTemporalOcclusionRuntime.onCameraInvalidation();
                return false;
            }

            int maximumConsecutiveSkips =
                    PotatoTemporalOcclusionRuntime
                            .maximumConsecutiveSkips(
                                    frame
                            );

            if (potato$occlusionConsecutiveSkips
                    < maximumConsecutiveSkips) {
                potato$occlusionConsecutiveSkips++;
                PotatoTemporalOcclusionRuntime.onSkippedDraw();
                return true;
            }

            potato$occlusionLastResultOccluded = false;
            potato$occlusionConsecutiveSkips = 0;
            PotatoTemporalOcclusionRuntime.onForcedRevalidation();
            return false;
        } catch (Throwable throwable) {
            PotatoTemporalOcclusionRuntime.disableAfterFailure(throwable);
            potato$invalidateTemporalOcclusion();
            return false;
        }
    }

    @Override
    public boolean potato$beginTemporalOcclusionQuery(
            PotatoTemporalOcclusionFrame frame
    ) {
        if (frame == null
                || potato$occlusionQueryActive
                || potato$occlusionQueryInFlight) {
            return false;
        }

        try {
            int activeQuery = GL15C.glGetQueryi(
                    GL43C.GL_ANY_SAMPLES_PASSED_CONSERVATIVE,
                    GL15C.GL_CURRENT_QUERY
            );

            if (activeQuery != 0) {
                PotatoTemporalOcclusionRuntime.onQueryConflict();
                return false;
            }

            if (potato$occlusionQueryId == 0) {
                potato$occlusionQueryId = GL15C.glGenQueries();

                if (potato$occlusionQueryId == 0) {
                    return false;
                }

                PotatoTemporalOcclusionRuntime.onQueryObjectCreated();
            }

            GL15C.glBeginQuery(
                    GL43C.GL_ANY_SAMPLES_PASSED_CONSERVATIVE,
                    potato$occlusionQueryId
            );

            potato$occlusionQueryActive = true;
            potato$occlusionQueryInFlight = true;
            potato$occlusionQueryGeneration = potato$occlusionGeneration;
            potato$occlusionEvidenceFrame = frame;
            potato$occlusionLastResultOccluded = false;
            potato$occlusionConsecutiveSkips = 0;

            PotatoTemporalOcclusionRuntime.onQueryStarted();
            return true;
        } catch (Throwable throwable) {
            PotatoTemporalOcclusionRuntime.disableAfterFailure(throwable);
            potato$invalidateTemporalOcclusion();
            return false;
        }
    }

    @Override
    public void potato$endTemporalOcclusionQuery() {
        if (!potato$occlusionQueryActive) {
            return;
        }

        try {
            GL15C.glEndQuery(
                    GL43C.GL_ANY_SAMPLES_PASSED_CONSERVATIVE
            );
        } catch (Throwable throwable) {
            PotatoTemporalOcclusionRuntime.disableAfterFailure(throwable);
        } finally {
            potato$occlusionQueryActive = false;
        }
    }

    @Override
    public void potato$invalidateTemporalOcclusion() {
        potato$occlusionGeneration++;
        potato$occlusionLastResultOccluded = false;
        potato$occlusionConsecutiveSkips = 0;
        potato$occlusionEvidenceFrame = null;
    }

    @Override
    public void potato$closeTemporalOcclusion() {
        try {
            if (potato$occlusionQueryActive) {
                GL15C.glEndQuery(GL43C.GL_ANY_SAMPLES_PASSED_CONSERVATIVE);
            }

            if (potato$occlusionQueryId != 0) {
                GL15C.glDeleteQueries(potato$occlusionQueryId);
                PotatoTemporalOcclusionRuntime.onQueryObjectDeleted();
            }
        } catch (Throwable throwable) {
            PotatoTemporalOcclusionRuntime.disableAfterFailure(throwable);
        } finally {
            potato$occlusionQueryId = 0;
            potato$occlusionQueryActive = false;
            potato$occlusionQueryInFlight = false;
            potato$occlusionLastResultOccluded = false;
            potato$occlusionConsecutiveSkips = 0;
            potato$occlusionEvidenceFrame = null;
        }
    }

    @Inject(
            method = "<init>(Lcom/mojang/blaze3d/vertex/VertexBuffer$Usage;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$rememberUsageWithoutAllocatingSidecar(
            VertexBuffer.Usage usage,
            CallbackInfo callbackInfo
    ) {
        potato$usage =
                usage;
    }

    @Inject(
            method = "upload(Lcom/mojang/blaze3d/vertex/MeshData;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeOnlyRelevantBlockUpload(
            MeshData meshData,
            CallbackInfo callbackInfo
    ) {
        /*
         * Any upload invalidates the previous visual proxy immediately. A
         * worker result is accepted only when its generation still matches.
         */
        potato$lodGeneration++;
        potato$invalidateTemporalOcclusion();
        potato$currentLodTier = 0;
        potato$lodClosed = false;

        if (potato$lodProxy != null) {
            potato$lodProxy.close();
            potato$lodProxy = null;
        }

        if (potato$usage
                != VertexBuffer.Usage.STATIC) {
            return;
        }

        if (meshData == null) {
            return;
        }

        MeshData.DrawState drawState =
                meshData.drawState();

        if (drawState == null) {
            return;
        }

        if (!DefaultVertexFormat.BLOCK.equals(
                drawState.format()
        )) {
            return;
        }

        if (drawState.mode()
                != VertexFormat.Mode.QUADS) {
            return;
        }

        /*
         * Current section path uses Minecraft's sequential quad index buffer.
         * Explicit-index resources remain outside the Stage 1 LOD contract.
         */
        if (meshData.indexBuffer()
                != null) {
            return;
        }

        /*
         * CPU simplification is asynchronous and bounded. It preserves every
         * complex/rejected quad byte-for-byte and merges only full 1x1 faces.
         */
        PotatoLodRuntime.scheduleBuild(
                (PotatoLodProxyBridge)
                        (Object) this,
                potato$lodGeneration,
                meshData
        );

        /*
         * Historical surface analysis is still a debug-only branch.
         */
        LosslessSurfaceMergeAnalyzer.observeEligibleUpload(
                meshData
        );

        /*
         * Patch 083 feeds the real device-local region-arena staging ingress
         * before the legacy visible-Vulkan gate. This does not allocate the
         * old one-VkBuffer-per-section mirror and does not cancel OpenGL.
         */
        DrawGeometryView geometryView =
                DrawGeometryView.from(
                        meshData
                );

        VulkanSurfaceClusterVisibility.onUpload(
                (Object) this,
                potato$lodGeneration,
                geometryView
        );

        VulkanRegionArenaIngress.onUpload(
                (Object) this,
                potato$lodGeneration,
                geometryView
        );

        /*
         * Patch 076 decouples the cheap backend-neutral upload generation from
         * scarce native Vulkan residency.
         *
         * Before this change, a STATIC BLOCK VertexBuffer received no
         * DrawBufferBackendState at all after the Vulkan mirror hit its native
         * admission ceiling. At 32 chunks that made already-loaded sections
         * permanently ineligible for visible Vulkan ownership until Minecraft
         * rebuilt them.
         *
         * While the Vulkan draw backend is active we now ALWAYS publish the
         * exact upload generation. Native VkBuffer mirroring remains bounded.
         * If immediate residency cannot be admitted, the same MeshData bytes
         * are copied into a bounded deferred cache and may be promoted later
         * only when the section actually becomes visible.
         */
        /*
         * Release gameplay cannot consume the old per-section Vulkan mirror.
         * Returning here removes duplicate native allocation/copy/residency
         * work while preserving the LOD scheduleBuild call above. The complete
         * mirror path remains available for explicit renderer development.
         */
        if (!VulkanVisibleSolidCutoverPolicy.enabled()) {
            return;
        }

        if (!DrawSubmissionDispatcher.active()) {
            return;
        }

        DrawBufferBackendState state =
                potato$drawBackendState();

        if (DrawSubmissionDispatcher.wantsUpload(state)) {
            DrawSubmissionDispatcher.upload(
                    state,
                    meshData
            );

            VulkanVisibleGeometryResidency
                    .onImmediateUpload(
                            state,
                            geometryView
                    );

            return;
        }

        state.observeUpload(
                geometryView
        );

        VulkanVisibleGeometryResidency
                .deferUnmirroredUpload(
                        state,
                        geometryView
                );
    }

    @Inject(
            method = "close()V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeTrackedBufferClose(
            CallbackInfo callbackInfo
    ) {
        potato$lodGeneration++;
        potato$closeTemporalOcclusion();
        potato$lodClosed = true;
        potato$currentLodTier = 0;

        if (potato$lodProxy != null) {
            potato$lodProxy.close();
            potato$lodProxy = null;
        }

        VulkanSurfaceClusterVisibility.onClose(
                (Object) this
        );

        VulkanRegionArenaIngress.onClose(
                (Object) this
        );

        DrawBufferBackendState state =
                potato$drawBackendState;

        if (state == null) {
            return;
        }

        VulkanVisibleGeometryResidency
                .onStateClosed(
                        state
                );

        /*
         * DrawSubmissionDispatcher.close observes the close only when the
         * native sink owns a resource. Deferred-only states still need their
         * backend-neutral lifecycle closed, so mark it first. The second
         * observeClose on a resident state is idempotent.
         */
        state.observeClose();

        DrawSubmissionDispatcher.close(
                state
        );
    }
}
