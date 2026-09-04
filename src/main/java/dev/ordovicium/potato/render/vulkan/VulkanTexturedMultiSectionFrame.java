package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;
import dev.ordovicium.potato.render.resource.BlockResourceCapture;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * First production-shaped BLOCK frame batch.
 *
 * <p>This class is not a per-draw proof. It records multiple real Minecraft
 * section buffers into one dynamic-rendering scope, binds pipeline/descriptors
 * once, uses one shared QUADS index buffer, and performs one queue submission
 * for the entire validation batch.</p>
 *
 * <p>Gameplay synchronization is non-blocking: the next layer polls the
 * previous submission and skips Vulkan work when the GPU is still busy.
 * Patch 071 keeps the atomic visible-layer capacity from the old validation
 * ceiling so 24+ chunk SOLID working sets can remain Vulkan-owned.</p>
 */
final class VulkanTexturedMultiSectionFrame
        implements AutoCloseable {

    private static final long FENCE_TIMEOUT_NANOSECONDS =
            10_000_000_000L;

    private static final int MIN_DRAWS_PER_BATCH =
            2;

    private static final int MAX_DRAWS_PER_BATCH =
            Math.max(
                    256,
                    Math.min(
                            4096,
                            Integer.getInteger(
                                    "potato.vulkan.world.maxDrawsPerBatch",
                                    2048
                            )
                    )
            );

    private static final int MAX_BATCH_ATTEMPTS =
            8;

    /*
     * Patch 134: eight full-layer textured raster proofs are sufficient for the
     * bounded production warmup. The fresh 133 run produced eight consecutive
     * positive batches, 3,291 real draws and 12,539,582 rasterized samples with
     * zero failures. The former target of sixteen made liveness depend on rare
     * whole-layer-ready opportunities rather than adding a distinct safety
     * property.
     */
    private static final long VALIDATION_WARMUP_TARGET =
            8L;

    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamilyIndex;

    private final VulkanFrameSession frameSession;
    private final VulkanOpenGlPresentationBridge presentationBridge;
    private final JsonObject report;

    private final VulkanBlockTextureDescriptorSet descriptors;
    private final VulkanSharedQuadIndexBuffer sharedQuadIndices;

    private VulkanTexturedSectionPipeline pipeline;

    private long commandPool =
            NULL;

    private VkCommandBuffer commandBuffer;

    private long fence =
            NULL;

    private long queryPool =
            NULL;

    private SectionLayerFrameContext currentLayerContext;
    private VulkanBlockRenderLayer currentLayer;

    /*
     * Zero-allocation per-layer draw scratch. Patch 070 recorded one new
     * DrawEntry object for every visible section; the last runtime created
     * 6.85 million candidates. 071 stores the same data in reusable arrays.
     */
    private final VulkanGeometryBufferResource[]
            pendingResources =
            new VulkanGeometryBufferResource[
                    MAX_DRAWS_PER_BATCH
            ];

    private final DrawBufferBackendState[]
            pendingStates =
            new DrawBufferBackendState[
                    MAX_DRAWS_PER_BATCH
            ];

    private final float[] pendingOffsets =
            new float[
                    MAX_DRAWS_PER_BATCH * 3
            ];

    private int pendingDrawCount;

    /*
     * Exact native allocations pinned for the one current Vulkan submission.
     * They are released only after the submission fence is harvested.
     */
    private final VulkanGeometryBufferResource.SubmissionBinding[]
            submissionBindings =
            new VulkanGeometryBufferResource.SubmissionBinding[
                    MAX_DRAWS_PER_BATCH
            ];

    private final int[] submissionSourceIndices =
            new int[
                    MAX_DRAWS_PER_BATCH
            ];

    private int preparedBindingCount;
    private int inFlightBindingCount;

    private final Matrix4f scratchMvp =
            new Matrix4f();

    private long layerBeginCount;
    private long supportedLayerBeginCount;
    private long layerEndCount;

    private long drawCandidateCount;
    private long drawAcceptedCount;
    private long drawRejectedStaleCount;
    private long drawRejectedIndexCountMismatchCount;
    private long drawRejectedBatchFullCount;

    private long zeroAllocationScratchWriteCount;
    private long visibleStrictSnapshotRejectCount;
    private long submissionPinnedDrawCount;
    private long validationQueryRecordedBatchCount;
    private long validationQueryRetiredBatchCount;
    private long validationWarmupSubmissionCount;

    private int batchAttemptCount;
    private int successfulBatchCount;

    private int lastSubmittedDrawCount;
    private int maxSubmittedDrawCount;

    private long totalSubmittedDrawCount;

    private int lastTargetGeneration =
            -1;

    private int queueSubmitResult =
            Integer.MIN_VALUE;

    private int fenceWaitResult =
            Integer.MIN_VALUE;

    private int queryResult =
            Integer.MIN_VALUE;

    private long rasterizedSamples;
    private long totalRasterizedSamples;
    private long positiveSampleBatchCount;
    private long zeroSampleBatchCount;

    private boolean submissionInFlight;
    private boolean inFlightQueryRecorded;
    private int inFlightSubmittedDrawCount;
    private VulkanBlockTextureUploadPrototype inFlightTextures;
    private boolean inFlightVisibleOwnership;

    private boolean visibleOwnershipArmed;
    private int visibleExpectedDrawCount;
    private boolean visibleCommitQueued;
    private long visiblePrepareAttemptCount;
    private long visiblePrepareSuccessCount;
    private long visibleCommitQueuedCount;
    private long visibleCommitHarvestedCount;
    private long visibleFailOpenCount;
    private long visibleFailOpenWarmupCount;
    private long visibleFailOpenBackpressureCount;
    private long visibleFailOpenBridgePrepareCount;
    private long visibleFailOpenArmCount;

    private long nonBlockingFencePollCount;
    private long nonBlockingBackpressureSkipCount;
    private int lastFenceStatus =
            Integer.MIN_VALUE;

    private boolean gameplayFenceWaitUsed;
    private boolean shutdownFenceWaitUsed;

    private boolean pipelineBoundOncePerBatch;
    private boolean descriptorsBoundOncePerBatch;
    private boolean sharedIndexBoundOncePerBatch;

    private boolean succeeded;
    private boolean disabledAfterFailure;

    private long failureCount;

    private String lastFailure =
            "";

    private volatile boolean hotPathRetired;

    private boolean verifiedBeforeClose;
    private boolean closed;

    VulkanTexturedMultiSectionFrame(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            VulkanFrameSession frameSession,
            VulkanOpenGlPresentationBridge presentationBridge,
            JsonObject report
    ) {
        this.device =
                device;

        this.graphicsQueue =
                graphicsQueue;

        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;

        this.frameSession =
                frameSession;

        this.presentationBridge =
                presentationBridge;

        this.report =
                report;

        this.descriptors =
                new VulkanBlockTextureDescriptorSet(
                        device,
                        report
                );

        this.sharedQuadIndices =
                new VulkanSharedQuadIndexBuffer(
                        device,
                        physicalDevice
                );

        /*
         * SubmissionBinding objects are scratch slots, not per-draw objects.
         * Allocate them once with the frame runtime and reuse them forever.
         */
        for (int index = 0;
             index < submissionBindings.length;
             index++) {
            submissionBindings[index] =
                    new VulkanGeometryBufferResource.SubmissionBinding();
        }
    }

    boolean wantsLayer(
            net.minecraft.client.renderer.RenderType renderType
    ) {
        return !closed
                && !hotPathRetired
                && !disabledAfterFailure
                && VulkanBlockRenderLayer.from(
                renderType
        ) != null;
    }

    boolean acceptingDraws() {
        return !closed
                && !hotPathRetired
                && !disabledAfterFailure
                && currentLayerContext != null
                && currentLayer != null
                && pendingDrawCount
                < MAX_DRAWS_PER_BATCH;
    }

    boolean hotPathRetired() {
        return hotPathRetired;
    }

    boolean prepareVisibleOwnership(
            net.minecraft.client.renderer.RenderType renderType
    ) {
        visiblePrepareAttemptCount++;
        visibleCommitQueued = false;
        visibleOwnershipArmed = false;
        visibleExpectedDrawCount = 0;

        /*
         * Patch 129: Patch 128 correctly retired millions of hidden non-visible
         * section draws, but the old visible path still demanded sixteen hidden
         * successful batches before it would even attempt a real atomic SOLID
         * submission. With hidden batches retired that became a permanent
         * warmup deadlock and also starved live atlas/lightmap generation sync.
         *
         * Do not reject here for historical warmup counters. The exact current
         * SOLID snapshot is still proof-gated by the caller. submitBatch() below
         * performs the first sixteen requested visible submissions against the
         * hidden Vulkan target with a real occlusion query and WITHOUT suppressing
         * OpenGL. Only after sixteen positive current-process raster proofs may a
         * requested submission target the OpenGL/Vulkan visible bridge.
         */
        if (closed
                || disabledAfterFailure
                || hotPathRetired
                || presentationBridge == null
                || VulkanBlockRenderLayer.from(renderType)
                != VulkanBlockRenderLayer.SOLID) {
            visibleFailOpenCount++;
            return false;
        }

        if (!harvestCompletedSubmission(false)) {
            nonBlockingBackpressureSkipCount++;
            visibleFailOpenCount++;
            visibleFailOpenBackpressureCount++;
            return false;
        }

        VulkanFrameSession.SectionLayerTargetSnapshot snapshot =
                frameSession.sectionLayerTargetSnapshot();

        if (snapshot == null
                || snapshot.target() == null
                || !presentationBridge.prepare(
                snapshot.target().width(),
                snapshot.target().height()
        )) {
            visibleFailOpenCount++;
            visibleFailOpenBridgePrepareCount++;
            return false;
        }

        visiblePrepareSuccessCount++;
        return true;
    }

    int visibleDrawCapacity() {
        return MAX_DRAWS_PER_BATCH;
    }

    boolean armVisibleOwnership(
            int expectedDrawCount
    ) {
        if (expectedDrawCount < MIN_DRAWS_PER_BATCH
                || expectedDrawCount > MAX_DRAWS_PER_BATCH
                || currentLayer != VulkanBlockRenderLayer.SOLID
                || pendingDrawCount != expectedDrawCount
                || presentationBridge == null
                || !presentationBridge.ready()) {
            visibleOwnershipArmed = false;
            visibleExpectedDrawCount = 0;
            visibleFailOpenCount++;
            visibleFailOpenArmCount++;
            return false;
        }

        visibleExpectedDrawCount = expectedDrawCount;
        visibleOwnershipArmed = true;
        return true;
    }

    boolean visibleCommitQueued() {
        return visibleCommitQueued;
    }

    void beginLayer(
            SectionLayerFrameContext context,
            VulkanBlockTextureUploadPrototype textures
    ) {
        layerBeginCount++;

        clearPendingDrawScratch();

        currentLayerContext =
                null;

        currentLayer =
                null;

        if (closed
                || hotPathRetired
                || disabledAfterFailure
                || context == null
                || !context.hasMatrices()
                || !context.shaderUsesBlockVertexFormat()
                || textures == null
                || !textures.verified()) {
            return;
        }

        VulkanBlockRenderLayer layer =
                VulkanBlockRenderLayer.fromName(
                        context.renderTypeName()
                );

        if (layer == null) {
            return;
        }

        currentLayerContext =
                context;

        currentLayer =
                layer;

        supportedLayerBeginCount++;
    }

    void recordDraw(
            VulkanGeometryBufferResource resource,
            DrawBufferBackendState state,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        drawCandidateCount++;

        if (!acceptingDraws()) {
            if (pendingDrawCount
                    >= MAX_DRAWS_PER_BATCH) {
                drawRejectedBatchFullCount++;
            }

            return;
        }

        if (resource == null
                || state == null
                || !resource.readyFor(
                state
        )) {
            drawRejectedStaleCount++;
            return;
        }

        int generatedIndexCount =
                state.vertexCount()
                        / 4
                        * 6;

        if (state.vertexCount() <= 0
                || (state.vertexCount() & 3)
                != 0
                || generatedIndexCount
                != state.indexCount()) {
            drawRejectedIndexCountMismatchCount++;
            return;
        }

        int index =
                pendingDrawCount;

        pendingResources[index] =
                resource;

        pendingStates[index] =
                state;

        int offsetBase =
                index * 3;

        pendingOffsets[offsetBase] =
                chunkOffsetX;

        pendingOffsets[offsetBase + 1] =
                chunkOffsetY;

        pendingOffsets[offsetBase + 2] =
                chunkOffsetZ;

        pendingDrawCount++;
        drawAcceptedCount++;
        zeroAllocationScratchWriteCount++;
    }

    void endLayer(
            VulkanBlockTextureUploadPrototype textures
    ) {
        layerEndCount++;

        try {
            /*
             * Never block the Minecraft render thread waiting for Vulkan.
             * If the previous batch is still in flight, fail open for this
             * layer and let the existing OpenGL path remain visible.
             */
            if (!harvestCompletedSubmission(
                    false
            )) {
                nonBlockingBackpressureSkipCount++;
                return;
            }

            if (currentLayerContext == null
                    || currentLayer == null) {
                return;
            }

            if (pendingDrawCount
                    < MIN_DRAWS_PER_BATCH) {
                return;
            }

            batchAttemptCount++;

            boolean visibleSubmission =
                    visibleOwnershipArmed
                            && visibleExpectedDrawCount
                            == pendingDrawCount;

            submitBatch(
                    textures,
                    visibleSubmission
            );
        } catch (Throwable throwable) {
            failureCount++;

            disabledAfterFailure =
                    true;

            lastFailure =
                    throwable.getClass()
                            .getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        } finally {
            if (!submissionInFlight
                    && preparedBindingCount > 0) {
                releasePreparedBindings();
            }

            hotPathRetired =
                    disabledAfterFailure
                            || (failureCount > 0L
                            && batchAttemptCount
                            >= MAX_BATCH_ATTEMPTS);

            clearLayer();
            enrich();
        }
    }

    boolean verified() {
        return verifiedBeforeClose
                || liveVerified();
    }

    private boolean liveVerified() {
        return !closed
                && successfulBatchCount
                > 0
                && positiveSampleBatchCount
                > 0L
                && totalSubmittedDrawCount
                > 0L
                && totalRasterizedSamples
                > 0L
                && pipelineBoundOncePerBatch
                && descriptorsBoundOncePerBatch
                && sharedIndexBoundOncePerBatch
                && failureCount
                == 0
                && !disabledAfterFailure;
    }

    void enrich() {
        report.addProperty(
                "multiSectionFrameInstalled",
                true
        );
        report.addProperty(
                "multiSectionFrameMode",
                "POTATO_ENGINE_FRAME_STABLE_ZERO_ALLOC_SOLID_BATCH"
        );
        report.addProperty(
                "multiSectionFrameLayerBeginCount",
                layerBeginCount
        );
        report.addProperty(
                "multiSectionFrameSupportedLayerBeginCount",
                supportedLayerBeginCount
        );
        report.addProperty(
                "multiSectionFrameLayerEndCount",
                layerEndCount
        );
        report.addProperty(
                "multiSectionFrameDrawCandidateCount",
                drawCandidateCount
        );
        report.addProperty(
                "multiSectionFrameDrawAcceptedCount",
                drawAcceptedCount
        );
        report.addProperty(
                "multiSectionFrameDrawRejectedStaleCount",
                drawRejectedStaleCount
        );
        report.addProperty(
                "multiSectionFrameDrawRejectedIndexCountMismatchCount",
                drawRejectedIndexCountMismatchCount
        );
        report.addProperty(
                "multiSectionFrameDrawRejectedBatchFullCount",
                drawRejectedBatchFullCount
        );
        report.addProperty(
                "multiSectionFrameZeroAllocationDrawScratch",
                true
        );
        report.addProperty(
                "multiSectionFrameZeroAllocationScratchWriteCount",
                zeroAllocationScratchWriteCount
        );
        report.addProperty(
                "multiSectionFrameVisibleStrictSnapshotRejectCount",
                visibleStrictSnapshotRejectCount
        );
        report.addProperty(
                "multiSectionFrameSubmissionPinnedDrawCount",
                submissionPinnedDrawCount
        );
        report.addProperty(
                "multiSectionFrameInFlightGeometryBindingCount",
                inFlightBindingCount
        );
        report.addProperty(
                "multiSectionFrameValidationQueryWarmupTarget",
                VALIDATION_WARMUP_TARGET
        );
        report.addProperty(
                "multiSectionFrameValidationQueryRecordedBatchCount",
                validationQueryRecordedBatchCount
        );
        report.addProperty(
                "multiSectionFrameValidationQueryRetiredBatchCount",
                validationQueryRetiredBatchCount
        );
        report.addProperty(
                "multiSectionFrameValidationWarmupSubmissionCount",
                validationWarmupSubmissionCount
        );
        report.addProperty(
                "multiSectionFrameValidationWarmupBounded",
                true
        );
        report.addProperty(
                "multiSectionFrameValidationWarmupSuppressesOpenGl",
                false
        );
        report.addProperty(
                "multiSectionFrameProductionValidationQueryRetired",
                positiveSampleBatchCount
                        >= VALIDATION_WARMUP_TARGET
        );
        report.addProperty(
                "multiSectionFrameMinDrawsPerBatch",
                MIN_DRAWS_PER_BATCH
        );
        report.addProperty(
                "multiSectionFrameMaxDrawsPerBatch",
                MAX_DRAWS_PER_BATCH
        );
        report.addProperty(
                "multiSectionFrameVisibleDrawCapacityProfile",
                "PROPERTY_BOUNDED_2048_DEFAULT"
        );
        report.addProperty(
                "multiSectionFrameMaxBatchAttempts",
                MAX_BATCH_ATTEMPTS
        );
        report.addProperty(
                "multiSectionFrameBatchAttemptCount",
                batchAttemptCount
        );
        report.addProperty(
                "multiSectionFrameSuccessfulBatchCount",
                successfulBatchCount
        );
        report.addProperty(
                "multiSectionFrameLastSubmittedDrawCount",
                lastSubmittedDrawCount
        );
        report.addProperty(
                "multiSectionFrameMaxSubmittedDrawCount",
                maxSubmittedDrawCount
        );
        report.addProperty(
                "multiSectionFrameTotalSubmittedDrawCount",
                totalSubmittedDrawCount
        );
        report.addProperty(
                "multiSectionFrameLastTargetGeneration",
                lastTargetGeneration
        );
        report.addProperty(
                "multiSectionFrameQueueSubmitResult",
                queueSubmitResult
        );
        report.addProperty(
                "multiSectionFrameFenceWaitResult",
                fenceWaitResult
        );
        report.addProperty(
                "multiSectionFrameQueryResult",
                queryResult
        );
        report.addProperty(
                "multiSectionFrameRasterizedSamples",
                rasterizedSamples
        );
        report.addProperty(
                "multiSectionFrameTotalRasterizedSamples",
                totalRasterizedSamples
        );
        report.addProperty(
                "multiSectionFramePositiveSampleBatchCount",
                positiveSampleBatchCount
        );
        report.addProperty(
                "multiSectionFrameZeroSampleBatchCount",
                zeroSampleBatchCount
        );
        report.addProperty(
                "multiSectionFrameSubmissionInFlight",
                submissionInFlight
        );
        report.addProperty(
                "multiSectionFrameNonBlockingFencePolling",
                true
        );
        report.addProperty(
                "multiSectionFrameNonBlockingFencePollCount",
                nonBlockingFencePollCount
        );
        report.addProperty(
                "multiSectionFrameNonBlockingBackpressureSkipCount",
                nonBlockingBackpressureSkipCount
        );
        report.addProperty(
                "multiSectionFrameLastFenceStatus",
                lastFenceStatus
        );
        report.addProperty(
                "multiSectionFrameGameplayFenceWaitUsed",
                gameplayFenceWaitUsed
        );
        report.addProperty(
                "multiSectionFrameShutdownFenceWaitUsed",
                shutdownFenceWaitUsed
        );
        report.addProperty(
                "multiSectionFramePipelineBoundOncePerBatch",
                pipelineBoundOncePerBatch
        );
        report.addProperty(
                "multiSectionFrameDescriptorsBoundOncePerBatch",
                descriptorsBoundOncePerBatch
        );
        report.addProperty(
                "multiSectionFrameSharedIndexBoundOncePerBatch",
                sharedIndexBoundOncePerBatch
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexBuffer",
                true
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexRebuildCount",
                sharedQuadIndices.rebuildCount()
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexMaxVertexCount",
                sharedQuadIndices.maxVertexCount()
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexMaxIndexCount",
                sharedQuadIndices.maxIndexCount()
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexImmutableAtlas",
                sharedQuadIndices.immutableAtlas()
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexUint16BuildCount",
                sharedQuadIndices.uint16BuildCount()
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexUint32BuildCount",
                sharedQuadIndices.uint32BuildCount()
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexPublishedAllocationCount",
                sharedQuadIndices.publishedAllocationCount()
        );
        report.addProperty(
                "multiSectionFrameSharedQuadIndexImmutableAtlasBytes",
                sharedQuadIndices.immutableAtlasBytes()
        );
        report.addProperty(
                "multiSectionFrameSingleQueueSubmitPerBatch",
                true
        );
        report.addProperty(
                "multiSectionFramePerDrawQueueSubmit",
                false
        );
        report.addProperty(
                "multiSectionFramePerDrawFenceWait",
                false
        );
        report.addProperty(
                "multiSectionFrameValidationFenceWait",
                false
        );
        report.addProperty(
                "multiSectionFrameDeviceWaitIdlePerBatch",
                false
        );
        report.addProperty(
                "multiSectionFrameQueueWaitIdlePerBatch",
                false
        );
        report.addProperty(
                "multiSectionFrameDepthTestEnabled",
                true
        );
        report.addProperty(
                "multiSectionFrameDepthWriteEnabled",
                true
        );
        report.addProperty(
                "multiSectionFrameBackFaceCullingEnabled",
                false
        );
        report.addProperty(
                "multiSectionFrameCullMode",
                "NONE_ANGLE_SAFE_CORRECTNESS_RECOVERY"
        );
        report.addProperty(
                "multiSectionFrameAngleSafeCullRecovery",
                true
        );
        report.addProperty(
                "multiSectionFrameColorModulatorImplemented",
                true
        );
        report.addProperty(
                "multiSectionFrameFogImplemented",
                true
        );
        report.addProperty(
                "multiSectionFrameFogShapeImplemented",
                true
        );
        report.addProperty(
                "multiSectionFrameAtlasSampled",
                true
        );
        report.addProperty(
                "multiSectionFrameLightmapSampled",
                true
        );
        report.addProperty(
                "multiSectionFrameSolidImplemented",
                true
        );
        report.addProperty(
                "multiSectionFrameCutoutImplemented",
                true
        );
        report.addProperty(
                "multiSectionFrameCutoutMippedDeferred",
                true
        );
        report.addProperty(
                "multiSectionFrameTranslucentDeferred",
                true
        );
        report.addProperty(
                "multiSectionFrameAtlasMipChainDeferred",
                true
        );
        report.addProperty(
                "multiSectionFrameAtlasAnimationSyncDeferred",
                true
        );
        report.addProperty(
                "multiSectionFrameLightmapLiveSyncDeferred",
                false
        );
        report.addProperty(
                "multiSectionFrameLiveTextureSyncPiggybacked",
                true
        );
        report.addProperty(
                "multiSectionFrameOpenGlBaselineStillExecutes",
                visibleCommitQueuedCount == 0L
        );
        report.addProperty(
                "multiSectionFrameVisiblePresentation",
                visibleCommitQueuedCount > 0L
        );
        report.addProperty(
                "multiSectionFrameVisiblePrepareAttemptCount",
                visiblePrepareAttemptCount
        );
        report.addProperty(
                "multiSectionFrameVisiblePrepareSuccessCount",
                visiblePrepareSuccessCount
        );
        report.addProperty(
                "multiSectionFrameVisibleCommitQueuedCount",
                visibleCommitQueuedCount
        );
        report.addProperty(
                "multiSectionFrameVisibleCommitHarvestedCount",
                visibleCommitHarvestedCount
        );
        report.addProperty(
                "multiSectionFrameVisibleCommitCoveragePercent",
                visiblePrepareAttemptCount == 0L
                        ? 0.0
                        : visibleCommitQueuedCount
                        * 100.0
                        / visiblePrepareAttemptCount
        );
        report.addProperty(
                "multiSectionFrameVisiblePrepareToCommitPercent",
                visiblePrepareSuccessCount == 0L
                        ? 0.0
                        : visibleCommitQueuedCount
                        * 100.0
                        / visiblePrepareSuccessCount
        );
        report.addProperty(
                "multiSectionFrameVisibleFailOpenCount",
                visibleFailOpenCount
        );
        report.addProperty(
                "multiSectionFrameVisibleFailOpenWarmupCount",
                visibleFailOpenWarmupCount
        );
        report.addProperty(
                "multiSectionFrameVisibleFailOpenBackpressureCount",
                visibleFailOpenBackpressureCount
        );
        report.addProperty(
                "multiSectionFrameVisibleFailOpenBridgePrepareCount",
                visibleFailOpenBridgePrepareCount
        );
        report.addProperty(
                "multiSectionFrameVisibleFailOpenArmCount",
                visibleFailOpenArmCount
        );
        report.addProperty(
                "potatoEngineVulkanWorldDrawReady",
                visibleCommitHarvestedCount > 0L
        );
        report.addProperty(
                "potatoEngineVulkanVisibleDrawSuppressionEnabled",
                visibleCommitQueuedCount > 0L
        );
        report.addProperty(
                "potatoEngineVulkanMainWindowPresentationReady",
                false
        );

        if (presentationBridge != null) {
            presentationBridge.enrich();
        }
        report.addProperty(
                "multiSectionFrameHotPathRetired",
                hotPathRetired
        );
        report.addProperty(
                "multiSectionFrameHotPathRetirementReason",
                successfulBatchCount > 0
                        ? "PRODUCTION_ACTIVE"
                        : disabledAfterFailure
                        ? "CONTAINED_FAILURE"
                        : hotPathRetired
                        ? "ATTEMPT_BUDGET_EXHAUSTED"
                        : "AWAITING_POSITIVE_RASTER_PROOF"
        );
        report.addProperty(
                "multiSectionFrameFailureCount",
                failureCount
        );
        report.addProperty(
                "multiSectionFrameDisabledAfterFailure",
                disabledAfterFailure
        );

        if (!lastFailure.isBlank()) {
            report.addProperty(
                    "multiSectionFrameLastFailure",
                    lastFailure
            );
        }

        report.addProperty(
                "multiSectionFrameVerifiedBeforeClose",
                verifiedBeforeClose
                        || liveVerified()
        );
        report.addProperty(
                "multiSectionFrameVerified",
                verified()
        );

        descriptors.enrich();
    }

    /**
     * Harvest the one in-flight production submission.
     *
     * <p>Gameplay always polls with vkGetFenceStatus and returns immediately
     * when the GPU is still busy. The only finite vkWaitForFences call is the
     * teardown path, where native resources must not be destroyed in flight.</p>
     */
    private boolean harvestCompletedSubmission(
            boolean waitForCompletion
    ) {
        if (!submissionInFlight) {
            return true;
        }

        int status;

        if (waitForCompletion) {
            shutdownFenceWaitUsed =
                    true;

            try (MemoryStack stack =
                         MemoryStack.stackPush()) {

                status =
                        vkWaitForFences(
                                device,
                                stack.longs(
                                        fence
                                ),
                                true,
                                FENCE_TIMEOUT_NANOSECONDS
                        );
            }

            fenceWaitResult =
                    status;
        } else {
            nonBlockingFencePollCount++;

            status =
                    vkGetFenceStatus(
                            device,
                            fence
                    );

            lastFenceStatus =
                    status;

            if (status
                    == VK_NOT_READY) {
                return false;
            }
        }

        if (status
                != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "HARVEST_MULTI_SECTION_FRAME",
                    (waitForCompletion
                            ? "vkWaitForFences"
                            : "vkGetFenceStatus")
                            + " returned "
                            + status
            );
        }

        /*
         * The fence is complete from this point onward. Even if diagnostic
         * query harvesting or texture generation bookkeeping throws, native
         * geometry must be unpinned and the completed submission must never
         * remain marked in-flight.
         */
        int completedDrawCount =
                inFlightSubmittedDrawCount;

        boolean completedVisible =
                inFlightVisibleOwnership;

        boolean completedQueryRecorded =
                inFlightQueryRecorded;

        VulkanBlockTextureUploadPrototype
                completedTextures =
                inFlightTextures;

        try {
            if (completedQueryRecorded) {
                try (MemoryStack stack =
                             MemoryStack.stackPush()) {

                    ByteBuffer queryData =
                            stack.malloc(
                                    Long.BYTES
                            ).order(
                                    ByteOrder.nativeOrder()
                            );

                    queryResult =
                            vkGetQueryPoolResults(
                                    device,
                                    queryPool,
                                    0,
                                    1,
                                    queryData,
                                    Long.BYTES,
                                    VK_QUERY_RESULT_64_BIT
                            );

                    if (queryResult
                            != VK_SUCCESS) {
                        throw new VulkanProbeException(
                                "READ_MULTI_SECTION_QUERY",
                                "vkGetQueryPoolResults returned "
                                        + queryResult
                        );
                    }

                    rasterizedSamples =
                            queryData.getLong(
                                    0
                            );
                }
            } else {
                queryResult =
                        VK_SUCCESS;

                rasterizedSamples =
                        0L;
            }

            if (completedTextures != null) {
                completedTextures
                        .completeFrameSubmission();
            }

            boolean submissionCompleted =
                    completedDrawCount
                            >= MIN_DRAWS_PER_BATCH
                            && queueSubmitResult
                            == VK_SUCCESS
                            && queryResult
                            == VK_SUCCESS;

            succeeded =
                    submissionCompleted
                            && (!completedQueryRecorded
                            || rasterizedSamples > 0L);

            if (submissionCompleted) {
                successfulBatchCount++;

                totalSubmittedDrawCount +=
                        completedDrawCount;

                maxSubmittedDrawCount =
                        Math.max(
                                maxSubmittedDrawCount,
                                completedDrawCount
                        );

                if (completedQueryRecorded) {
                    if (rasterizedSamples > 0L) {
                        positiveSampleBatchCount++;

                        totalRasterizedSamples +=
                                rasterizedSamples;

                        if (positiveSampleBatchCount
                                == VALIDATION_WARMUP_TARGET) {
                            /*
                             * The warmup has finished on a hidden target. Ask
                             * the existing self-healing preflight scheduler for
                             * a small bounded commit train so the next exact
                             * whole-layer-ready opportunity is not left to
                             * chance. OpenGL remains authoritative until the
                             * normal atomic commit succeeds.
                             */
                            VulkanVisibleNearReadyRetryController
                                    .armPostValidationCommitRetry();
                        }

                        verifiedBeforeClose =
                                true;
                    } else {
                        zeroSampleBatchCount++;
                    }
                }

                lastFailure =
                        "";
            }

            return true;
        } finally {
            releaseInFlightBindings();

            if (completedVisible) {
                visibleCommitHarvestedCount++;
            }

            inFlightVisibleOwnership =
                    false;

            submissionInFlight =
                    false;

            inFlightQueryRecorded =
                    false;

            inFlightSubmittedDrawCount =
                    0;

            inFlightTextures =
                    null;
        }
    }

    private void submitBatch(
            VulkanBlockTextureUploadPrototype textures,
            boolean visibleSubmission
    ) {
        if (textures == null
                || !textures.verified()) {
            throw new VulkanProbeException(
                    "MULTI_SECTION_TEXTURES",
                    "BLOCK texture resources are unavailable."
            );
        }

        if (!prepareSubmissionBindings(
                visibleSubmission
        )) {
            return;
        }

        boolean recordValidationQuery =
                positiveSampleBatchCount
                        < VALIDATION_WARMUP_TARGET;

        /*
         * Patch 129 bounded micro-warmup:
         * a visible ownership request is allowed to exercise the exact textured
         * SOLID pipeline, descriptors, geometry bindings and live texture sync,
         * but the bounded validation proofs render only into the hidden
         * Vulkan target. OpenGL remains fully authoritative for those
         * frames. This replaces the old unbounded hidden-layer workload with a
         * finite current-process proof.
         */
        boolean visibleCommitEligible =
                visibleSubmission
                        && !recordValidationQuery;

        if (visibleSubmission
                && recordValidationQuery) {
            validationWarmupSubmissionCount++;
        }

        VulkanFrameSession.SectionLayerTargetSnapshot
                targetSnapshot =
                frameSession
                        .sectionLayerTargetSnapshot();

        VulkanOffscreenTargetPrototype hiddenTarget =
                targetSnapshot.target();

        VulkanOpenGlPresentationBridge.TargetView visibleTarget =
                visibleCommitEligible && presentationBridge != null
                        ? presentationBridge.targetView()
                        : null;

        RenderTargetView target;

        if (visibleTarget != null) {
            target = new RenderTargetView(
                    visibleTarget.colorImageView(),
                    visibleTarget.depthImageView(),
                    visibleTarget.width(),
                    visibleTarget.height(),
                    visibleTarget.colorFormat(),
                    visibleTarget.depthFormat(),
                    visibleTarget.generation()
            );
        } else {
            visibleCommitEligible = false;
            target = new RenderTargetView(
                    hiddenTarget.colorImageView(),
                    hiddenTarget.depthImageView(),
                    hiddenTarget.width(),
                    hiddenTarget.height(),
                    hiddenTarget.colorFormat(),
                    hiddenTarget.depthFormatOrUndefined(),
                    targetSnapshot.generation()
            );
        }

        if (target.depthImageView() == NULL
                || target.depthFormat() == VK_FORMAT_UNDEFINED) {
            throw new VulkanProbeException(
                    "MULTI_SECTION_DEPTH_TARGET",
                    "Production BLOCK batch requires a Vulkan depth attachment."
            );
        }

        descriptors.ensureCreated(
                textures
        );

        ensurePipeline(
                target.colorFormat(),
                target.depthFormat(),
                descriptors.descriptorSetLayout()
        );

        ensureCommandResources();

        textures
                .prepareLatestSnapshotsForFrame();

        int maxVertexCount =
                0;

        for (int bindingIndex = 0;
             bindingIndex < preparedBindingCount;
             bindingIndex++) {

            VulkanGeometryBufferResource.SubmissionBinding
                    binding =
                    submissionBindings[
                            bindingIndex
                    ];

            maxVertexCount =
                    Math.max(
                            maxVertexCount,
                            binding.vertexCount()
                    );
        }

        VulkanSharedQuadIndexBuffer.Binding
                sharedIndex =
                sharedQuadIndices
                        .ensureForMaxVertexCount(
                                maxVertexCount
                        );

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {

            int result =
                    vkResetFences(
                            device,
                            fence
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "RESET_MULTI_SECTION_FENCE",
                        "vkResetFences failed with VkResult "
                                + result
                );
            }

            result =
                    vkResetCommandBuffer(
                            commandBuffer,
                            0
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "RESET_MULTI_SECTION_COMMAND_BUFFER",
                        "vkResetCommandBuffer failed with VkResult "
                                + result
                );
            }

            VkCommandBufferBeginInfo beginInfo =
                    VkCommandBufferBeginInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            );

            result =
                    vkBeginCommandBuffer(
                            commandBuffer,
                            beginInfo
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "BEGIN_MULTI_SECTION_COMMAND_BUFFER",
                        "vkBeginCommandBuffer failed with VkResult "
                                + result
                );
            }

            if (recordValidationQuery) {
                vkCmdResetQueryPool(
                        commandBuffer,
                        queryPool,
                        0,
                        1
                );
            }

            textures
                    .recordPreparedUpdates(
                            commandBuffer
                    );

            if (visibleCommitEligible) {
                presentationBridge.recordAcquireForVulkan(
                        commandBuffer
                );
            }

            if (recordValidationQuery) {
                vkCmdBeginQuery(
                        commandBuffer,
                        queryPool,
                        0,
                        0
                );
            }

            VkRenderingAttachmentInfo.Buffer colorAttachment =
                    VkRenderingAttachmentInfo.calloc(
                            1,
                            stack
                    );

            colorAttachment.get(0)
                    .sType$Default()
                    .imageView(
                            target.colorImageView()
                    )
                    .imageLayout(
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                    )
                    .loadOp(
                            VK_ATTACHMENT_LOAD_OP_CLEAR
                    )
                    .storeOp(
                            VK_ATTACHMENT_STORE_OP_STORE
                    );

            colorAttachment.get(0)
                    .clearValue()
                    .color()
                    .float32(0, 0.0f)
                    .float32(1, 0.0f)
                    .float32(2, 0.0f)
                    .float32(3, visibleCommitEligible
                            ? 0.0f
                            : 1.0f);

            VkRenderingAttachmentInfo depthAttachment =
                    VkRenderingAttachmentInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .imageView(
                                    target.depthImageView()
                            )
                            .imageLayout(
                                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                            )
                            .loadOp(
                                    VK_ATTACHMENT_LOAD_OP_CLEAR
                            )
                            .storeOp(
                                    VK_ATTACHMENT_STORE_OP_STORE
                            );

            depthAttachment
                    .clearValue()
                    .depthStencil()
                    .depth(
                            1.0f
                    )
                    .stencil(
                            0
                    );

            VkRenderingInfo renderingInfo =
                    VkRenderingInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .layerCount(
                                    1
                            )
                            .pColorAttachments(
                                    colorAttachment
                            );

            renderingInfo.pDepthAttachment(
                    depthAttachment
            );

            renderingInfo
                    .renderArea()
                    .offset()
                    .x(
                            0
                    )
                    .y(
                            0
                    );

            renderingInfo
                    .renderArea()
                    .extent()
                    .width(
                            target.width()
                    )
                    .height(
                            target.height()
                    );

            vkCmdBeginRendering(
                    commandBuffer,
                    renderingInfo
            );

            VkViewport.Buffer viewport =
                    VkViewport.calloc(
                            1,
                            stack
                    );

            viewport.get(0)
                    .x(
                            0.0f
                    )
                    .y(
                            0.0f
                    )
                    .width(
                            (float) target.width()
                    )
                    .height(
                            (float) target.height()
                    )
                    .minDepth(
                            0.0f
                    )
                    .maxDepth(
                            1.0f
                    );

            vkCmdSetViewport(
                    commandBuffer,
                    0,
                    viewport
            );

            VkRect2D.Buffer scissor =
                    VkRect2D.calloc(
                            1,
                            stack
                    );

            scissor.get(0)
                    .offset()
                    .x(
                            0
                    )
                    .y(
                            0
                    );

            scissor.get(0)
                    .extent()
                    .width(
                            target.width()
                    )
                    .height(
                            target.height()
                    );

            vkCmdSetScissor(
                    commandBuffer,
                    0,
                    scissor
            );

            vkCmdBindPipeline(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeline.pipeline()
            );

            pipelineBoundOncePerBatch =
                    true;

            vkCmdBindDescriptorSets(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeline.layout(),
                    0,
                    stack.longs(
                            descriptors.descriptorSet()
                    ),
                    null
            );

            descriptorsBoundOncePerBatch =
                    true;

            vkCmdBindIndexBuffer(
                    commandBuffer,
                    sharedIndex.buffer(),
                    0L,
                    sharedIndex.vkIndexType()
            );

            sharedIndexBoundOncePerBatch =
                    true;

            Matrix4f mvp =
                    scratchMvp
                            .set(
                                    currentLayerContext.projection()
                            ).mul(
                                    currentLayerContext.modelView()
                            );

            ByteBuffer push =
                    stack.malloc(
                            VulkanTexturedSectionPipeline
                                    .PUSH_CONSTANT_BYTES
                    ).order(
                            ByteOrder.nativeOrder()
                    );

            FloatBuffer floats =
                    push.asFloatBuffer();

            mvp.get(
                    floats
            );

            writeLayerConstants(
                    floats
            );

            int submitted =
                    0;

            for (int bindingIndex = 0;
                 bindingIndex < preparedBindingCount;
                 bindingIndex++) {

                int sourceIndex =
                        submissionSourceIndices[
                                bindingIndex
                        ];

                VulkanGeometryBufferResource.SubmissionBinding
                        binding =
                        submissionBindings[
                                bindingIndex
                        ];

                int indexCount =
                        sharedQuadIndices
                                .indexCountForVertexCount(
                                        binding.vertexCount()
                                );

                if (indexCount
                        != binding.indexCount()) {
                    drawRejectedIndexCountMismatchCount++;

                    if (visibleSubmission) {
                        throw new VulkanProbeException(
                                "VISIBLE_SOLID_SNAPSHOT_INDEX_MISMATCH",
                                "Pinned visible SOLID snapshot changed index contract."
                        );
                    }

                    continue;
                }

                vkCmdBindVertexBuffers(
                        commandBuffer,
                        0,
                        stack.longs(
                                binding.vertexBuffer()
                        ),
                        stack.longs(
                                0L
                        )
                );

                int offsetBase =
                        sourceIndex * 3;

                floats.put(
                        16,
                        pendingOffsets[
                                offsetBase
                        ]
                );
                floats.put(
                        17,
                        pendingOffsets[
                                offsetBase + 1
                        ]
                );
                floats.put(
                        18,
                        pendingOffsets[
                                offsetBase + 2
                        ]
                );
                floats.put(
                        19,
                        1.0f
                );

                vkCmdPushConstants(
                        commandBuffer,
                        pipeline.layout(),
                        VK_SHADER_STAGE_VERTEX_BIT
                                | VK_SHADER_STAGE_FRAGMENT_BIT,
                        0,
                        push
                );

                vkCmdDrawIndexed(
                        commandBuffer,
                        indexCount,
                        1,
                        0,
                        0,
                        0
                );

                submitted++;
            }

            lastSubmittedDrawCount =
                    submitted;

            if (visibleSubmission
                    && submitted
                    != visibleExpectedDrawCount) {
                throw new VulkanProbeException(
                        "VISIBLE_SOLID_ATOMIC_SNAPSHOT",
                        "Visible SOLID snapshot submitted "
                                + submitted
                                + " of expected "
                                + visibleExpectedDrawCount
                                + " draws."
                );
            }

            vkCmdEndRendering(
                    commandBuffer
            );

            if (recordValidationQuery) {
                vkCmdEndQuery(
                        commandBuffer,
                        queryPool,
                        0
                );
            }

            if (visibleCommitEligible) {
                presentationBridge.recordReleaseToOpenGl(
                        commandBuffer
                );
            }

            result =
                    vkEndCommandBuffer(
                            commandBuffer
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "END_MULTI_SECTION_COMMAND_BUFFER",
                        "vkEndCommandBuffer failed with VkResult "
                                + result
                );
            }

            VkSubmitInfo.Buffer submit =
                    VkSubmitInfo.calloc(
                            1,
                            stack
                    );

            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(
                            stack.pointers(
                                    commandBuffer.address()
                            )
                    );

            if (visibleCommitEligible) {
                long waitSemaphore =
                        presentationBridge.vulkanWaitSemaphore();

                if (waitSemaphore != NULL) {
                    submit.get(0)
                            .pWaitSemaphores(
                                    stack.longs(waitSemaphore)
                            )
                            .pWaitDstStageMask(
                                    stack.ints(
                                            VK_PIPELINE_STAGE_ALL_COMMANDS_BIT
                                    )
                            );
                }

                submit.get(0)
                        .pSignalSemaphores(
                                stack.longs(
                                        presentationBridge
                                                .vulkanSignalSemaphore()
                                )
                        );
            }

            queueSubmitResult =
                    vkQueueSubmit(
                            graphicsQueue,
                            submit,
                            fence
                    );

            if (queueSubmitResult
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "SUBMIT_MULTI_SECTION_FRAME",
                        "vkQueueSubmit failed with VkResult "
                                + queueSubmitResult
                );
            }

            inFlightBindingCount =
                    preparedBindingCount;

            preparedBindingCount =
                    0;

            inFlightQueryRecorded =
                    recordValidationQuery;

            /*
             * vkQueueSubmit has succeeded. From this point forward the native
             * geometry bindings are owned by the fence lifecycle even if the
             * OpenGL bridge later fails and Potato has to fail open.
             */
            submissionInFlight =
                    true;

            inFlightSubmittedDrawCount =
                    lastSubmittedDrawCount;

            inFlightTextures =
                    textures;

            inFlightVisibleOwnership =
                    false;

            lastTargetGeneration =
                    target.generation();

            if (recordValidationQuery) {
                validationQueryRecordedBatchCount++;
            } else {
                validationQueryRetiredBatchCount++;
            }

            if (visibleCommitEligible) {
                presentationBridge.onVulkanSubmitted();
                visibleCommitQueued =
                        presentationBridge.enqueueComposite();

                if (visibleCommitQueued) {
                    visibleCommitQueuedCount++;
                } else {
                    visibleFailOpenCount++;
                }
            } else {
                visibleCommitQueued = false;
            }

            /*
             * Do not wait here. The next supported Minecraft layer polls this
             * fence with timeout-free vkGetFenceStatus. If it is still busy,
             * Potato skips the hidden Vulkan batch and OpenGL remains visible.
             */
            gameplayFenceWaitUsed =
                    false;

            inFlightVisibleOwnership =
                    visibleCommitEligible && visibleCommitQueued;

            report.addProperty(
                    "multiSectionFrameTargetWidth",
                    target.width()
            );
            report.addProperty(
                    "multiSectionFrameTargetHeight",
                    target.height()
            );
            report.addProperty(
                    "multiSectionFrameTargetColorFormat",
                    target.colorFormat()
            );
            report.addProperty(
                    "multiSectionFrameTargetDepthFormat",
                    target.depthFormat()
            );
            report.addProperty(
                    "multiSectionFrameLastSubmissionVisible",
                    visibleCommitEligible && visibleCommitQueued
            );
            report.addProperty(
                    "multiSectionFrameSharedIndexMemoryTypeIndex",
                    sharedIndex.memoryTypeIndex()
            );
            report.addProperty(
                    "multiSectionFrameSharedIndexHostCoherent",
                    sharedIndex.hostCoherent()
            );
            report.addProperty(
                    "multiSectionFrameSharedIndexRebuilt",
                    sharedIndex.rebuilt()
            );
        }
    }

    private void writeLayerConstants(
            FloatBuffer floats
    ) {
        floats.put(
                20,
                currentLayerContext.colorModulatorR()
        );
        floats.put(
                21,
                currentLayerContext.colorModulatorG()
        );
        floats.put(
                22,
                currentLayerContext.colorModulatorB()
        );
        floats.put(
                23,
                currentLayerContext.colorModulatorA()
        );

        floats.put(
                24,
                currentLayerContext.fogColorR()
        );
        floats.put(
                25,
                currentLayerContext.fogColorG()
        );
        floats.put(
                26,
                currentLayerContext.fogColorB()
        );
        floats.put(
                27,
                currentLayerContext.fogColorA()
        );

        floats.put(
                28,
                currentLayerContext.fogStart()
        );
        floats.put(
                29,
                currentLayerContext.fogEnd()
        );
        floats.put(
                30,
                (float) currentLayerContext.fogShape()
        );
        floats.put(
                31,
                currentLayer.alphaCutoff()
        );
    }

    private void ensurePipeline(
            int colorFormat,
            int depthFormat,
            long descriptorSetLayout
    ) {
        if (pipeline != null
                && pipeline.colorFormat() == colorFormat
                && pipeline.depthFormat() == depthFormat) {
            return;
        }

        if (pipeline != null) {
            pipeline.close();
        }

        pipeline =
                VulkanTexturedSectionPipeline.create(
                        device,
                        colorFormat,
                        depthFormat,
                        descriptorSetLayout,
                        BlockResourceCapture.blockVertexLayout(),
                        report
                );
    }

    private void ensureCommandResources() {
        if (commandPool
                != NULL
                && commandBuffer
                != null
                && fence
                != NULL
                && queryPool
                != NULL) {
            return;
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {

            VkCommandPoolCreateInfo poolInfo =
                    VkCommandPoolCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                            )
                            .queueFamilyIndex(
                                    graphicsQueueFamilyIndex
                            );

            java.nio.LongBuffer poolPointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateCommandPool(
                            device,
                            poolInfo,
                            null,
                            poolPointer
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_MULTI_SECTION_COMMAND_POOL",
                        "vkCreateCommandPool failed with VkResult "
                                + result
                );
            }

            commandPool =
                    poolPointer.get(
                            0
                    );

            VkCommandBufferAllocateInfo allocateInfo =
                    VkCommandBufferAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .commandPool(
                                    commandPool
                            )
                            .level(
                                    VK_COMMAND_BUFFER_LEVEL_PRIMARY
                            )
                            .commandBufferCount(
                                    1
                            );

            org.lwjgl.PointerBuffer commandPointer =
                    stack.mallocPointer(
                            1
                    );

            result =
                    vkAllocateCommandBuffers(
                            device,
                            allocateInfo,
                            commandPointer
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "ALLOCATE_MULTI_SECTION_COMMAND_BUFFER",
                        "vkAllocateCommandBuffers failed with VkResult "
                                + result
                );
            }

            commandBuffer =
                    new VkCommandBuffer(
                            commandPointer.get(
                                    0
                            ),
                            device
                    );

            VkFenceCreateInfo fenceInfo =
                    VkFenceCreateInfo.calloc(
                            stack
                    )
                            .sType$Default();

            java.nio.LongBuffer fencePointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateFence(
                            device,
                            fenceInfo,
                            null,
                            fencePointer
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_MULTI_SECTION_FENCE",
                        "vkCreateFence failed with VkResult "
                                + result
                );
            }

            fence =
                    fencePointer.get(
                            0
                    );

            VkQueryPoolCreateInfo queryInfo =
                    VkQueryPoolCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .queryType(
                                    VK_QUERY_TYPE_OCCLUSION
                            )
                            .queryCount(
                                    1
                            );

            java.nio.LongBuffer queryPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateQueryPool(
                            device,
                            queryInfo,
                            null,
                            queryPointer
                    );

            if (result
                    != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_MULTI_SECTION_QUERY_POOL",
                        "vkCreateQueryPool failed with VkResult "
                                + result
                );
            }

            queryPool =
                    queryPointer.get(
                            0
                    );
        }
    }

    private boolean prepareSubmissionBindings(
            boolean visibleSubmission
    ) {
        if (preparedBindingCount != 0
                || inFlightBindingCount != 0) {
            throw new VulkanProbeException(
                    "PREPARE_MULTI_SECTION_BINDINGS",
                    "Geometry submission binding scratch was not empty."
            );
        }

        for (int sourceIndex = 0;
             sourceIndex < pendingDrawCount;
             sourceIndex++) {

            VulkanGeometryBufferResource resource =
                    pendingResources[
                            sourceIndex
                    ];

            DrawBufferBackendState state =
                    pendingStates[
                            sourceIndex
                    ];

            VulkanGeometryBufferResource.SubmissionBinding
                    binding =
                    submissionBindings[
                            preparedBindingCount
                    ];

            boolean pinned =
                    resource != null
                            && state != null
                            && resource.pinForSubmission(
                                    state,
                                    binding
                            );

            if (!pinned) {
                drawRejectedStaleCount++;

                if (visibleSubmission) {
                    visibleFailOpenCount++;
                    visibleStrictSnapshotRejectCount++;
                    visibleCommitQueued =
                            false;

                    releasePreparedBindings();
                    return false;
                }

                continue;
            }

            submissionSourceIndices[
                    preparedBindingCount
            ] =
                    sourceIndex;

            preparedBindingCount++;
            submissionPinnedDrawCount++;
        }

        if (preparedBindingCount
                < MIN_DRAWS_PER_BATCH) {
            releasePreparedBindings();
            return false;
        }

        if (visibleSubmission
                && preparedBindingCount
                != visibleExpectedDrawCount) {
            visibleFailOpenCount++;
            visibleStrictSnapshotRejectCount++;
            visibleCommitQueued =
                    false;

            releasePreparedBindings();
            return false;
        }

        return true;
    }

    private void releasePreparedBindings() {
        for (int index = 0;
             index < preparedBindingCount;
             index++) {

            VulkanGeometryBufferResource.SubmissionBinding
                    binding =
                    submissionBindings[
                            index
                    ];

            if (binding != null) {
                binding.release();
            }

            submissionSourceIndices[
                    index
            ] =
                    0;
        }

        preparedBindingCount =
                0;
    }

    private void releaseInFlightBindings() {
        for (int index = 0;
             index < inFlightBindingCount;
             index++) {

            VulkanGeometryBufferResource.SubmissionBinding
                    binding =
                    submissionBindings[
                            index
                    ];

            if (binding != null) {
                binding.release();
            }

            submissionSourceIndices[
                    index
            ] =
                    0;
        }

        inFlightBindingCount =
                0;
    }

    private void clearPendingDrawScratch() {
        for (int index = 0;
             index < pendingDrawCount;
             index++) {

            pendingResources[
                    index
            ] =
                    null;

            pendingStates[
                    index
            ] =
                    null;

            int offsetBase =
                    index * 3;

            pendingOffsets[
                    offsetBase
            ] =
                    0.0f;

            pendingOffsets[
                    offsetBase + 1
            ] =
                    0.0f;

            pendingOffsets[
                    offsetBase + 2
            ] =
                    0.0f;
        }

        pendingDrawCount =
                0;
    }

    private void clearLayer() {
        clearPendingDrawScratch();

        currentLayerContext =
                null;

        currentLayer =
                null;

        visibleOwnershipArmed =
                false;

        visibleExpectedDrawCount =
                0;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        try {
            harvestCompletedSubmission(
                    true
            );
        } catch (Throwable throwable) {
            failureCount++;

            lastFailure =
                    throwable.getClass()
                            .getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        }

        verifiedBeforeClose =
                verifiedBeforeClose
                        || liveVerified();

        closed =
                true;

        clearLayer();

        if (presentationBridge != null) {
            presentationBridge.close();
        }

        if (queryPool
                != NULL) {
            vkDestroyQueryPool(
                    device,
                    queryPool,
                    null
            );

            queryPool =
                    NULL;
        }

        if (fence
                != NULL) {
            vkDestroyFence(
                    device,
                    fence,
                    null
            );

            fence =
                    NULL;
        }

        if (commandPool
                != NULL) {
            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            commandPool =
                    NULL;

            commandBuffer =
                    null;
        }

        if (pipeline
                != null) {
            pipeline.close();

            pipeline =
                    null;
        }

        descriptors.close();

        sharedQuadIndices.close();

        report.addProperty(
                "multiSectionFrameClosed",
                true
        );

        enrich();
    
        /*
         * Patch 089: close() is after the final multi-section telemetry enrich.
         * Re-run Potato Engine readiness reconciliation here so the shutdown
         * report sees the live Gate-8/Gate-9 proof instead of the immutable
         * startup 7/11 snapshot.
         */
        dev.ordovicium.potato.render.engine.PotatoRenderEngine
                .reconcileRuntimeReadiness(
                        report
                );}

    private record RenderTargetView(
            long colorImageView,
            long depthImageView,
            int width,
            int height,
            int colorFormat,
            int depthFormat,
            int generation
    ) {
    }

}
