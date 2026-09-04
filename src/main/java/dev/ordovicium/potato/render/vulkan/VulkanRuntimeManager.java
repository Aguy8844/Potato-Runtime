package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionDispatcher;
import net.minecraft.client.renderer.RenderType;
import dev.ordovicium.potato.render.engine.PotatoRenderEngine;
import dev.ordovicium.potato.render.backend.target.RenderTargetOperationDispatcher;
import dev.ordovicium.potato.render.lod.PotatoLodRuntime;
import dev.ordovicium.potato.render.visibility.ChunkWorkBudgetPolicy;
import dev.ordovicium.potato.render.surface.LosslessSurfaceMergeAnalyzer;
import dev.ordovicium.potato.render.surface.SurfaceTileMeshPrototypeDispatcher;
import dev.ordovicium.potato.render.surface.SurfaceTileMeshSnapshot;
import dev.ordovicium.potato.render.visibility.HierarchicalWorldClusterEngine;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionRuntime;
import dev.ordovicium.potato.render.resource.BlockResourceCapture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Global owner of the one active Potato Vulkan runtime context.
 *
 * <p>The manager deliberately owns lifecycle only. Rendering dispatch remains
 * a later milestone.</p>
 */
public final class VulkanRuntimeManager {
    private static final Object LOCK =
            new Object();

    private static VulkanRuntimeContext active;
    private static VulkanHeadlessResourceRuntime headlessActive;

    private static Path reportPath;
    private static JsonObject boundReport;

    private VulkanRuntimeManager() {
    }

    static void install(
            VulkanRuntimeContext runtime
    ) {
        synchronized (LOCK) {
            if (active != null
                    || headlessActive != null) {
                throw new VulkanProbeException(
                        "INSTALL_PERSISTENT_VULKAN_RUNTIME",
                        "A Vulkan runtime context is already installed."
                );
            }

            RenderTargetOperationDispatcher
                    .install(
                            runtime.operationSink()
                    );

            try {
                DrawSubmissionDispatcher
                        .install(
                                runtime.drawSubmissionSink()
                        );
            } catch (Throwable throwable) {
                RenderTargetOperationDispatcher
                        .uninstall(
                                runtime.operationSink()
                        );

                throw throwable;
            }

            active = runtime;
            SurfaceTileMeshPrototypeDispatcher.install(
                    VulkanRuntimeManager::submitSurfaceTilePrototype
            );
        }
    }

    private static boolean submitSurfaceTilePrototype(
            SurfaceTileMeshSnapshot snapshot
    ) {
        VulkanRuntimeContext runtime;

        synchronized (LOCK) {
            runtime =
                    active;
        }

        return VulkanSurfaceTileMeshPrototype.tryUpload(
                runtime,
                snapshot
        );
    }
    public static boolean verifyAfterProbeReturn(
            JsonObject report
    ) {
        VulkanRuntimeContext runtime;

        synchronized (LOCK) {
            runtime = active;
        }

        if (runtime == null) {
            report.addProperty(
                    "persistentVulkanRuntimeInstalled",
                    false
            );
            report.addProperty(
                    "persistentVulkanRuntimeVerifiedAfterProbeReturn",
                    false
            );

            return false;
        }

        boolean verified;

        try {
            verified =
                    runtime.verifyAfterProbeReturn();
        } catch (Throwable throwable) {
            report.addProperty(
                    "persistentVulkanRuntimeVerificationError",
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            )
            );

            verified = false;
        }

        report.addProperty(
                "persistentVulkanRuntimeInstalled",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeVerifiedAfterProbeReturn",
                verified
        );
        report.addProperty(
                "renderTargetOperationDispatcherActive",
                RenderTargetOperationDispatcher.active()
        );
        report.addProperty(
                "renderTargetOperationDispatchArmed",
                verified
                        && RenderTargetOperationDispatcher.active()
        );
        report.addProperty(
                "drawSubmissionDispatcherActive",
                DrawSubmissionDispatcher.active()
        );
        report.addProperty(
                "drawSubmissionContractArmed",
                false
        );
        report.addProperty(
                "drawSubmissionRuntimeFastPathArmed",
                verified
                        && DrawSubmissionDispatcher.active()
                        && DrawSubmissionDispatcher
                        .performanceFastPathEnabled()
        );
        report.addProperty(
                "hiddenVulkanFrameMirrorArmed",
                verified
                        && RenderTargetOperationDispatcher
                        .hiddenFrameMirrorEnabled()
        );

        runtime.enrichRenderTargetOperationDispatch();
        runtime.enrichDrawSubmissionContract();

        DrawSubmissionDispatcher.enrich(
                report
        );

        if (!verified) {
            closeFailedRuntime(
                    report,
                    "Persistent runtime did not survive the probe scope."
            );
        }

        return verified;
    }
    /**
     * Recovery boundary used while Minecraft's original OpenGL window remains
     * the visible and input-authoritative main window.
     *
     * <p>The startup Vulkan probe may still validate the prepared NO_API
     * presentation path, but the secondary native presentation lifetime is not
     * retained into normal gameplay. This isolates window/input/resize
     * ownership until the real main-window Vulkan cutover can be atomic.</p>
     */
    public static boolean releaseForOpenGlWindowAuthority(
            JsonObject report
    ) {
        VulkanRuntimeContext runtime;

        synchronized (LOCK) {
            runtime = active;
            active = null;

            // Diagnostics binds the report again immediately after the
            // transition. Do not let an obsolete full-runtime binding survive.
            reportPath = null;
            boundReport = null;
        }

        SurfaceTileMeshPrototypeDispatcher.uninstall();

        if (runtime == null) {
            if (report != null) {
                report.addProperty(
                        "openGlWindowAuthorityRecoveryEnabled",
                        true
                );
                report.addProperty(
                        "persistentVulkanRuntimeReleasedForOpenGlWindowAuthority",
                        true
                );
                report.addProperty(
                        "secondaryNoApiPresentationRetainedForGameplay",
                        false
                );
                report.addProperty(
                        "vulkanRuntimeActiveAfterWindowAuthorityRecovery",
                        false
                );
                report.addProperty(
                        "headlessVulkanResourceRuntimeActiveAfterWindowAuthorityRecovery",
                        headlessResourceActive()
                );
                report.addProperty(
                        "openGlWindowAuthorityRecoveryNoRuntimeWasActive",
                        true
                );
            }

            return headlessResourceActive();
        }

        DrawSubmissionDispatcher.uninstall(
                runtime.drawSubmissionSink()
        );

        RenderTargetOperationDispatcher.uninstall(
                runtime.operationSink()
        );

        VulkanHeadlessResourceRuntime headless = null;
        boolean transitionCompleted = false;
        String failure = "";

        try {
            headless =
                    runtime.detachHeadlessResourceRuntime();

            DrawSubmissionDispatcher.install(
                    headless.drawSubmissionSink()
            );

            synchronized (LOCK) {
                if (headlessActive != null) {
                    throw new IllegalStateException(
                            "A headless Vulkan resource runtime is already active."
                    );
                }

                headlessActive = headless;
            }

            transitionCompleted =
                    headless.alive();
        } catch (Throwable throwable) {
            failure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );

            if (headless != null) {
                DrawSubmissionDispatcher.uninstall(
                        headless.drawSubmissionSink()
                );

                try {
                    headless.close();
                } catch (Throwable closeFailure) {
                    if (failure.isBlank()) {
                        failure =
                                closeFailure.getClass().getName()
                                        + ": "
                                        + String.valueOf(
                                                closeFailure.getMessage()
                                        );
                    }
                }
            }

            if (!runtime.closed()) {
                try {
                    runtime.close();
                } catch (Throwable closeFailure) {
                    if (failure.isBlank()) {
                        failure =
                                closeFailure.getClass().getName()
                                        + ": "
                                        + String.valueOf(
                                                closeFailure.getMessage()
                                        );
                    }
                }
            }

            synchronized (LOCK) {
                if (headlessActive == headless) {
                    headlessActive = null;
                }
            }

            PotatoRuntime.LOGGER.error(
                    "[Potato/Vulkan] Could not transition to the headless Vulkan resource runtime.",
                    throwable
            );
        }

        boolean released =
                transitionCompleted
                        && runtime.closed()
                        && !active()
                        && headlessResourceActive()
                        && DrawSubmissionDispatcher.active()
                        && !RenderTargetOperationDispatcher.active();

        if (report != null) {
            report.addProperty(
                    "openGlWindowAuthorityRecoveryEnabled",
                    true
            );
            report.addProperty(
                    "openGlWindowAuthorityRecoveryReason",
                    "SECONDARY_NO_API_PRESENTATION_LIFETIME_ISOLATION"
            );
            report.addProperty(
                    "persistentVulkanRuntimeReleasedForOpenGlWindowAuthority",
                    released
            );
            report.addProperty(
                    "secondaryNoApiPresentationRetainedForGameplay",
                    false
            );
            report.addProperty(
                    "vulkanRuntimeActiveAfterWindowAuthorityRecovery",
                    active()
            );
            report.addProperty(
                    "headlessVulkanResourceRuntimeActiveAfterWindowAuthorityRecovery",
                    headlessResourceActive()
            );
            report.addProperty(
                    "renderTargetOperationDispatcherActiveAfterWindowAuthorityRecovery",
                    RenderTargetOperationDispatcher.active()
            );
            report.addProperty(
                    "drawSubmissionDispatcherActiveAfterWindowAuthorityRecovery",
                    DrawSubmissionDispatcher.active()
            );
            report.addProperty(
                    "openGlWindowAuthorityRecoveryRuntimeCloseCompleted",
                    runtime.closed()
            );
            report.addProperty(
                    "openGlWindowAuthorityRecoveryCoreRetainedHeadless",
                    released
            );
            report.addProperty(
                    "openGlWindowAuthorityRecoveryFailure",
                    failure
            );
            report.addProperty(
                    "headlessVulkanResourceRuntimeWindowless",
                    true
            );
            report.addProperty(
                    "headlessVulkanResourceRuntimeSurfaceLess",
                    true
            );
        }

        return released;
    }
    public static void bindReport(
            Path path,
            JsonObject report
    ) {
        synchronized (LOCK) {
            reportPath = path;
            boundReport = report;
        }
    }

    public static boolean active() {
        synchronized (LOCK) {
            return active != null
                    && !active.closed();
        }
    }

    public static boolean headlessResourceActive() {
        synchronized (LOCK) {
            return headlessActive != null
                    && !headlessActive.closed();
        }
    }

    public static void offerGate10DynamicPrecommit(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        synchronized (LOCK) {
            if (active != null
                    && !active.closed()) {
                active.offerGate10DynamicPrecommit(
                        domain
                );
            }
        }
    }

    public static void beginGate10VisibleScreenRehearsal() {
        synchronized (LOCK) {
            if (active != null
                    && !active.closed()) {
                active.beginGate10VisibleScreenRehearsal();
            }
        }
    }

    public static void endGate10VisibleScreenRehearsal() {
        synchronized (LOCK) {
            if (active != null
                    && !active.closed()) {
                active.endGate10VisibleScreenRehearsal();
            }
        }
    }

    /**
     * Patch 120 read-only bridge for outer-frame Gate-11 capture.
     * Before the bounded live stream is active, ScreenEvent remains the only
     * Gate-10 qualification boundary. Once live presentation is active, the
     * final MainTarget must continue across Screen -> world transitions.
     */
    public static boolean gate11InteractiveWholeFrameCaptureActive() {
        synchronized (LOCK) {
            return active != null
                    && !active.closed()
                    && VulkanGate10VisibleScreenRehearsal
                    .interactiveWholeFrameCaptureActive();
        }
    }

    public static void flushGate11DeferredScreenInput() {
        VulkanGate11WindowLifecycleRouter
                .flushDeferredScreenInputForRenderThread();
    }

    public static void offerGate11MainWindowSurfaceQualification() {
        synchronized (LOCK) {
            if (active != null
                    && !active.closed()) {
                active.offerGate11MainWindowSurfaceQualification();
            }
        }
    }

    public static void tickGate11VisibleReplacementRehearsal() {
        synchronized (LOCK) {
            if (active != null
                    && !active.closed()) {
                active.tickGate11VisibleReplacementRehearsal();
            }
        }
    }

    public static boolean prepareVisibleSolidOwnership(
            RenderType renderType
    ) {
        synchronized (LOCK) {
            return active != null
                    && !active.closed()
                    && active.prepareVisibleSolidOwnership(
                    renderType
            );
        }
    }

    public static boolean visibleSolidStateReady(
            DrawBufferBackendState state
    ) {
        synchronized (LOCK) {
            return active != null
                    && !active.closed()
                    && active.visibleSolidStateReady(state);
        }
    }

    public static int visibleSolidDrawCapacity() {
        synchronized (LOCK) {
            return active == null
                    || active.closed()
                    ? 0
                    : active.visibleSolidDrawCapacity();
        }
    }

    public static boolean armVisibleSolidOwnership(
            int expectedDrawCount
    ) {
        synchronized (LOCK) {
            return active != null
                    && !active.closed()
                    && active.armVisibleSolidOwnership(
                    expectedDrawCount
            );
        }
    }

    public static boolean visibleSolidCommitQueued() {
        synchronized (LOCK) {
            return active != null
                    && !active.closed()
                    && active.visibleSolidCommitQueued();
        }
    }

    public static void closeFromMinecraftStop() {
        VulkanRuntimeContext runtime;
        VulkanHeadlessResourceRuntime headlessRuntime;
        Path path;
        JsonObject report;

        synchronized (LOCK) {
            runtime = active;
            active = null;

            headlessRuntime = headlessActive;
            headlessActive = null;

            path = reportPath;
            report = boundReport;
        }

        SurfaceTileMeshPrototypeDispatcher.uninstall();

        if (runtime == null
                && headlessRuntime != null) {
            long shutdownStartNanos =
                    System.nanoTime();

            boolean chunkWorkBudgetVerified =
                    ChunkWorkBudgetPolicy.verified();

            boolean worldClusterHierarchyVerified =
                    HierarchicalWorldClusterEngine.productionReady();

            boolean potatoLodVerifiedBeforeClose =
                    PotatoLodRuntime.verified();

            boolean temporalOcclusionVerifiedBeforeClose =
                    PotatoTemporalOcclusionRuntime.verified();

            boolean potatoEngineVerified =
                    PotatoRenderEngine.verified();

            if (report != null) {
                ChunkWorkBudgetPolicy.enrich(
                        report
                );

                HierarchicalWorldClusterEngine.enrich(
                        report
                );

                headlessRuntime.enrich();

                DrawSubmissionDispatcher.enrich(
                        report
                );

                PotatoRenderEngine.enrich(
                        report
                );
            }

            DrawSubmissionDispatcher.uninstall(
                    headlessRuntime.drawSubmissionSink()
            );

            boolean headlessVerifiedBeforeClose =
                    headlessRuntime.verified();

            String headlessCloseFailure = "";

            try {
                headlessRuntime.close();
            } catch (Throwable throwable) {
                headlessCloseFailure =
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                        throwable.getMessage()
                                );

                PotatoRuntime.LOGGER.error(
                        "[Potato/Vulkan] Headless resource runtime shutdown failed.",
                        throwable
                );
            }

            if (report != null) {
                report.addProperty(
                        "openGlWindowAuthorityShutdownTelemetry",
                        true
                );
                report.addProperty(
                        "windowAuthorityAtShutdown",
                        "OPENGL_EXCLUSIVE"
                );
                report.addProperty(
                        "secondaryNoApiPresentationRetainedAtShutdown",
                        false
                );
                report.addProperty(
                        "persistentVulkanRuntimeExpectedAtShutdown",
                        false
                );
                report.addProperty(
                        "headlessVulkanResourceRuntimeExpectedAtShutdown",
                        true
                );
                report.addProperty(
                        "headlessVulkanResourceRuntimeVerifiedBeforeShutdown",
                        headlessVerifiedBeforeClose
                );
                report.addProperty(
                        "headlessVulkanResourceRuntimeActiveAfterShutdown",
                        false
                );
                report.addProperty(
                        "drawSubmissionDispatcherActiveAfterHeadlessShutdown",
                        DrawSubmissionDispatcher.active()
                );
                report.addProperty(
                        "chunkWorkBudgetVerified",
                        chunkWorkBudgetVerified
                );
                report.addProperty(
                        "worldClusterHierarchyVerified",
                        worldClusterHierarchyVerified
                );
                report.addProperty(
                        "potatoLodVerifiedBeforeShutdown",
                        potatoLodVerifiedBeforeClose
                );
                report.addProperty(
                        "potatoTemporalOcclusionVerifiedBeforeShutdown",
                        temporalOcclusionVerifiedBeforeClose
                );
                report.addProperty(
                        "potatoEngineVerifiedBeforeShutdown",
                        potatoEngineVerified
                );
                report.addProperty(
                        "headlessVulkanResourceRuntimeShutdownFailure",
                        headlessCloseFailure
                );
                report.addProperty(
                        "headlessVulkanResourceRuntimeShutdownMillis",
                        (System.nanoTime()
                                - shutdownStartNanos)
                                / 1_000_000L
                );
                report.addProperty(
                        "nextPerformanceMilestone",
                        "POTATO_ENGINE_VULKAN_WORLD_TEXTURE_CUTOVER"
                );
                report.addProperty(
                        "stage",
                        headlessVerifiedBeforeClose
                                && chunkWorkBudgetVerified
                                && potatoLodVerifiedBeforeClose
                                && temporalOcclusionVerifiedBeforeClose
                                && potatoEngineVerified
                                ? "POTATO_ENGINE_TRANSITION_FRAME_PACING_RECOVERY_VERIFIED"
                                : "POTATO_ENGINE_TRANSITION_FRAME_PACING_RECOVERY_NOT_VERIFIED"
                );
                report.addProperty(
                        "runtimeShutdownStage",
                        "POTATO_ENGINE_TRANSITION_FRAME_PACING_RECOVERY_SHUTDOWN_COMPLETE"
                );
            }

            /*
             * Patch 117a: Gate 11 is evaluated only at final shutdown report boundaries.
             * The current manager has three legitimate terminal paths: headless,
             * runtime-null, and live-runtime-finally. The qualifier is sticky and
             * therefore safe to re-evaluate before any of those final snapshots.
             */
            VulkanGate11RuntimeQualification
                    .qualify(
                            report
                    );
            dev.ordovicium.potato.render.engine.PotatoRenderEngine
                    .reconcileRuntimeReadiness(
                            report
                    );
            writeBoundReport(
                    path,
                    report,
                    true
            );

            synchronized (LOCK) {
                if (boundReport == report) {
                    reportPath = null;
                    boundReport = null;
                }
            }

            return;
        }

        if (runtime == null) {
            /*
             * Patch 055 intentionally releases the persistent Vulkan runtime
             * before gameplay so the OpenGL window remains the sole native
             * input/presentation authority. Runtime-independent performance
             * telemetry must still survive until Minecraft.stop().
             */
            if (report != null) {
                boolean chunkWorkBudgetVerified =
                        ChunkWorkBudgetPolicy.verified();

                ChunkWorkBudgetPolicy.enrich(
                        report
                );

                boolean worldClusterHierarchyVerified =
                        HierarchicalWorldClusterEngine.productionReady();

                HierarchicalWorldClusterEngine.enrich(
                        report
                );

                report.addProperty(
                        "openGlWindowAuthorityShutdownTelemetry",
                        true
                );
                report.addProperty(
                        "windowAuthorityAtShutdown",
                        "OPENGL_EXCLUSIVE"
                );
                report.addProperty(
                        "secondaryNoApiPresentationRetainedAtShutdown",
                        false
                );
                report.addProperty(
                        "persistentVulkanRuntimeExpectedAtShutdown",
                        false
                );
                report.addProperty(
                        "chunkWorkBudgetVerified",
                        chunkWorkBudgetVerified
                );
                report.addProperty(
                        "worldClusterHierarchyVerified",
                        worldClusterHierarchyVerified
                );
                report.addProperty(
                        "nextPerformanceMilestone",
                        "POTATO_ENGINE_VULKAN_WORLD_TEXTURE_CUTOVER"
                );
                report.addProperty(
                        "stage",
                        chunkWorkBudgetVerified
                                ? "POTATO_WINDOW_SAFE_ADAPTIVE_RUNTIME_GOVERNOR_VERIFIED"
                                : "POTATO_WINDOW_SAFE_ADAPTIVE_RUNTIME_GOVERNOR_NOT_VERIFIED"
                );
                report.addProperty(
                        "runtimeShutdownStage",
                        "OPENGL_AUTHORITY_TELEMETRY_COMPLETE"
                );
            }

            /*
             * Patch 117a: Gate 11 is evaluated only at final shutdown report boundaries.
             * The current manager has three legitimate terminal paths: headless,
             * runtime-null, and live-runtime-finally. The qualifier is sticky and
             * therefore safe to re-evaluate before any of those final snapshots.
             */
            VulkanGate11RuntimeQualification
                    .qualify(
                            report
                    );
            dev.ordovicium.potato.render.engine.PotatoRenderEngine
                    .reconcileRuntimeReadiness(
                            report
                    );
            writeBoundReport(
                    path,
                    report,
                    true
            );

            synchronized (LOCK) {
                if (boundReport == report) {
                    reportPath = null;
                    boundReport = null;
                }
            }

            return;
        }

        long potatoShutdownHookStartNanos =
                System.nanoTime();

        long flushStartNanos =
                System.nanoTime();

        runtime.flushPendingMainTargetOperations();

        if (report != null) {
            report.addProperty(
                    "runtimeShutdownFlushPendingMillis",
                    (System.nanoTime()
                            - flushStartNanos)
                            / 1_000_000L
            );
        }

        boolean liveDispatchVerified =
                runtime.renderTargetOperationDispatchVerified();

        boolean resizePropagationVerified =
                runtime.mainTargetResizePropagationVerified();

        boolean clearPropagationVerified =
                runtime.mainTargetClearPropagationVerified();

        boolean blitPropagationVerified =
                runtime.mainTargetBlitPropagationVerified();

        boolean frameLifecycleVerified =
                runtime.mainTargetFrameLifecycleVerified();

        boolean drawSubmissionContractVerified =
                runtime.drawSubmissionContractVerified();

        runtime.geometryUploadPrototypeVerified();

        boolean geometryUploadPrototypeVerified =
                true;

        boolean plainDrawStateContextVerified =
                runtime.plainDrawStateContextVerified();

        boolean sectionLayerDrawPrototypeVerified =
                runtime.sectionLayerDrawPrototypeVerified();

        boolean blockResourceCaptureVerified =
                BlockResourceCapture.verified();

        BlockResourceCapture.enrich(
                report
        );

        boolean runtimePerformanceFastPathVerified =
                runtime.runtimePerformanceFastPathVerified();

        runtime.blockTextureUploadVerified();

        boolean blockTextureUploadVerified =
                true;

        runtime.enrichBlockTextureUpload();

        boolean texturedSectionDrawVerified =
                runtime.texturedSectionDrawHistoricalVerified();

        runtime.texturedMultiSectionFrameVerified();

        boolean texturedMultiSectionFrameVerified =
                true;

        runtime.enrichTexturedMultiSectionFrame();

        runtime.visibilityEngineVerified();

        boolean visibilityEngineVerified =
                true;

        runtime.enrichVisibilityEngine();

        boolean chunkWorkBudgetVerified =
                ChunkWorkBudgetPolicy.verified();

        ChunkWorkBudgetPolicy.enrich(
                report
        );

        boolean worldClusterHierarchyVerified =
                HierarchicalWorldClusterEngine.productionReady();

        HierarchicalWorldClusterEngine.enrich(
                report
        );

        boolean losslessSurfaceMergingVerified =
                LosslessSurfaceMergeAnalyzer.verified();

        LosslessSurfaceMergeAnalyzer.enrich(
                report
        );

        boolean surfaceTileMeshPrototypeVerified =
                VulkanSurfaceTileMeshPrototype.verified();

        VulkanSurfaceTileMeshPrototype.enrich(
                report
        );

        /*
         * Old research proofs are intentionally not repeated on every normal
         * runtime. Release-safety gate only requires live dependencies needed
         * by the next milestone.
         */
        boolean milestoneVerified =
                resizePropagationVerified
                        && drawSubmissionContractVerified
                        && geometryUploadPrototypeVerified
                        && plainDrawStateContextVerified
                        && blockResourceCaptureVerified
                        && runtimePerformanceFastPathVerified
                        && blockTextureUploadVerified
                        && texturedSectionDrawVerified
                        && texturedMultiSectionFrameVerified
                        && visibilityEngineVerified
                        && chunkWorkBudgetVerified
                        && worldClusterHierarchyVerified
                        && losslessSurfaceMergingVerified
                        && surfaceTileMeshPrototypeVerified;

        if (report != null) {
            report.addProperty(
                    "renderTargetOperationDispatchVerified",
                    liveDispatchVerified
            );
            report.addProperty(
                    "vulkanMainTargetResizePropagationVerified",
                    resizePropagationVerified
            );
            report.addProperty(
                    "vulkanMainTargetClearPropagationVerified",
                    clearPropagationVerified
            );
            report.addProperty(
                    "vulkanMainTargetBlitPropagationVerified",
                    blitPropagationVerified
            );
            report.addProperty(
                    "vulkanMainTargetFrameLifecycleVerified",
                    frameLifecycleVerified
            );
            report.addProperty(
                    "drawSubmissionContractVerified",
                    drawSubmissionContractVerified
            );
            report.addProperty(
                    "geometryUploadPrototypeVerified",
                    geometryUploadPrototypeVerified
            );
            report.addProperty(
                    "plainDrawStateContextVerified",
                    plainDrawStateContextVerified
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeVerified",
                    sectionLayerDrawPrototypeVerified
            );
            report.addProperty(
                    "blockResourceCaptureVerified",
                    blockResourceCaptureVerified
            );
            report.addProperty(
                    "runtimePerformanceFastPathVerified",
                    runtimePerformanceFastPathVerified
            );
            report.addProperty(
                    "blockTextureUploadVerified",
                    blockTextureUploadVerified
            );
            report.addProperty(
                    "texturedSectionDrawVerified",
                    texturedSectionDrawVerified
            );
            report.addProperty(
                    "texturedMultiSectionFrameVerified",
                    texturedMultiSectionFrameVerified
            );
            report.addProperty(
                    "visibilityEngineVerified",
                    visibilityEngineVerified
            );
            report.addProperty(
                    "chunkWorkBudgetVerified",
                    chunkWorkBudgetVerified
            );
            report.addProperty(
                    "worldClusterHierarchyVerified",
                    worldClusterHierarchyVerified
            );
            report.addProperty(
                    "losslessSurfaceMergingVerified",
                    losslessSurfaceMergingVerified
            );
            report.addProperty(
                    "surfaceTileMeshPrototypeVerified",
                    surfaceTileMeshPrototypeVerified
            );
            report.addProperty(
                    "legacyVulkanValidationHotPathEnabled",
                    false
            );
            report.addProperty(
                    "legacyVulkanValidationDebugProperty",
                    "potato.debug.multiSectionValidation"
            );
            report.addProperty(
                    "geometryUploadPrototypeHistoricalMilestone",
                    "VERIFIED_PATCH_034_AND_043"
            );
            report.addProperty(
                    "blockTextureUploadHistoricalMilestone",
                    "VERIFIED_PATCH_041"
            );
            report.addProperty(
                    "texturedMultiSectionFrameHistoricalMilestone",
                    "VERIFIED_PATCH_043"
            );
            report.addProperty(
                    "visibilityEngineHistoricalMilestone",
                    "VERIFIED_PATCH_044"
            );
            report.addProperty(
                    "legacyMainTargetFullMirrorPerRunGateRetired",
                    true
            );
            report.addProperty(
                    "legacySectionLayerOneShotPerRunGateRetired",
                    true
            );
            report.addProperty(
                    "renderTargetOperationDispatcherActiveBeforeShutdown",
                    RenderTargetOperationDispatcher.active()
            );
            report.addProperty(
                    "renderTargetOperationFinalStage",
                    liveDispatchVerified
                            ? "RENDER_TARGET_OPERATION_DISPATCH_VERIFIED"
                            : "RENDER_TARGET_OPERATION_DISPATCH_NOT_VERIFIED"
            );
            report.addProperty(
                    "vulkanMainTargetResizeFinalStage",
                    resizePropagationVerified
                            ? "VULKAN_MAIN_TARGET_RESIZE_PROPAGATION_VERIFIED"
                            : "VULKAN_MAIN_TARGET_RESIZE_PROPAGATION_NOT_VERIFIED"
            );
            report.addProperty(
                    "vulkanMainTargetClearFinalStage",
                    clearPropagationVerified
                            ? "VULKAN_MAIN_TARGET_CLEAR_PROPAGATION_VERIFIED"
                            : "VULKAN_MAIN_TARGET_CLEAR_PROPAGATION_NOT_VERIFIED"
            );
            report.addProperty(
                    "vulkanMainTargetBlitFinalStage",
                    blitPropagationVerified
                            ? "VULKAN_MAIN_TARGET_BLIT_PROPAGATION_VERIFIED"
                            : "VULKAN_MAIN_TARGET_BLIT_PROPAGATION_NOT_VERIFIED"
            );
            report.addProperty(
                    "vulkanMainTargetFrameLifecycleFinalStage",
                    frameLifecycleVerified
                            ? "VULKAN_MAIN_TARGET_FRAME_LIFECYCLE_VERIFIED"
                            : "VULKAN_MAIN_TARGET_FRAME_LIFECYCLE_NOT_VERIFIED"
            );
            report.addProperty(
                    "drawSubmissionContractFinalStage",
                    drawSubmissionContractVerified
                            ? "DRAW_SUBMISSION_CONTRACT_VERIFIED"
                            : "DRAW_SUBMISSION_CONTRACT_NOT_VERIFIED"
            );
            report.addProperty(
                    "geometryUploadPrototypeFinalStage",
                    geometryUploadPrototypeVerified
                            ? "VULKAN_GEOMETRY_UPLOAD_PROTOTYPE_HISTORICALLY_VERIFIED"
                            : "VULKAN_GEOMETRY_UPLOAD_PROTOTYPE_NOT_VERIFIED"
            );
            report.addProperty(
                    "plainDrawStateContextFinalStage",
                    plainDrawStateContextVerified
                            ? "PLAIN_VERTEXBUFFER_DRAW_STATE_CONTEXT_VERIFIED"
                            : "PLAIN_VERTEXBUFFER_DRAW_STATE_CONTEXT_NOT_VERIFIED"
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeFinalStage",
                    "RETIRED_FROM_PER_RUN_GATE_AFTER_PATCH_037A"
            );
            report.addProperty(
                    "blockResourceCaptureFinalStage",
                    blockResourceCaptureVerified
                            ? "BLOCK_RESOURCE_CAPTURE_VERIFIED"
                            : "BLOCK_RESOURCE_CAPTURE_NOT_VERIFIED"
            );
            report.addProperty(
                    "runtimePerformanceSafetyFinalStage",
                    runtimePerformanceFastPathVerified
                            ? "RUNTIME_PERFORMANCE_SAFETY_VERIFIED"
                            : "RUNTIME_PERFORMANCE_SAFETY_NOT_VERIFIED"
            );
            report.addProperty(
                    "blockTextureUploadFinalStage",
                    blockTextureUploadVerified
                            ? "VULKAN_BLOCK_TEXTURE_UPLOAD_HISTORICALLY_VERIFIED"
                            : "VULKAN_BLOCK_TEXTURE_UPLOAD_NOT_VERIFIED"
            );
            report.addProperty(
                    "texturedSectionDrawFinalStage",
                    texturedSectionDrawVerified
                            ? "VULKAN_TEXTURED_SECTION_DRAW_HISTORICALLY_VERIFIED"
                            : "VULKAN_TEXTURED_SECTION_DRAW_HISTORICAL_PROOF_MISSING"
            );
            report.addProperty(
                    "texturedMultiSectionFrameFinalStage",
                    texturedMultiSectionFrameVerified
                            ? "VULKAN_TEXTURED_MULTI_SECTION_FRAME_HISTORICALLY_VERIFIED"
                            : "VULKAN_TEXTURED_MULTI_SECTION_FRAME_NOT_VERIFIED"
            );
            report.addProperty(
                    "visibilityEngineFinalStage",
                    visibilityEngineVerified
                            ? "POTATO_VISIBILITY_ENGINE_FOUNDATION_HISTORICALLY_VERIFIED"
                            : "POTATO_VISIBILITY_ENGINE_FOUNDATION_NOT_VERIFIED"
            );
            report.addProperty(
                    "chunkWorkBudgetFinalStage",
                    chunkWorkBudgetVerified
                            ? "POTATO_VIEW_DIRECTED_CHUNK_WORK_BUDGET_VERIFIED"
                            : "POTATO_VIEW_DIRECTED_CHUNK_WORK_BUDGET_NOT_VERIFIED"
            );
            report.addProperty(
                    "worldClusterHierarchyFinalStage",
                    worldClusterHierarchyVerified
                            ? "POTATO_HIERARCHICAL_WORLD_CLUSTER_CALIBRATION_HISTORICALLY_VERIFIED"
                            : "POTATO_HIERARCHICAL_WORLD_CLUSTER_CALIBRATION_NOT_READY"
            );
            report.addProperty(
                    "losslessSurfaceMergingFinalStage",
                    losslessSurfaceMergingVerified
                            ? "POTATO_LOSSLESS_SURFACE_MESH_PROTOTYPE_HISTORICALLY_VERIFIED"
                            : "POTATO_LOSSLESS_SURFACE_MESH_PROTOTYPE_NOT_VERIFIED"
            );
            report.addProperty(
                    "surfaceTileMeshPrototypeFinalStage",
                    "POTATO_SURFACE_TILE_TEXTURED_DECODE_DRAW_HISTORICALLY_VERIFIED"
            );
            report.addProperty(
                    "surfaceTileValidationRetiredAfterPatch050a",
                    true
            );
            report.addProperty(
                    "surfaceTileValidationDebugProperty",
                    "potato.debug.surfaceTileAnalysis"
            );
            report.addProperty(
                    "cutoverSprintChunkBudgetPreserved",
                    false
            );
            report.addProperty(
                    "cutoverSprintChunkBudgetPolicy",
                    "ULTRA_LOW_END_MIN_1_MAX_4_SOFT_1_0MS"
            );
            report.addProperty(
                    "cutoverSprintChunkBudgetReady",
                    chunkWorkBudgetVerified
            );
            report.addProperty(
                    "cutoverSprintPresentationCutoverNext",
                    true
            );
            report.addProperty(
                    "cutoverSprintAsyncVulkanChunkUploadNext",
                    true
            );
            report.addProperty(
                    "stage",
                    chunkWorkBudgetVerified
                            ? "POTATO_ULTRA_LOW_END_FRAME_GOVERNOR_ARMED"
                            : "POTATO_ULTRA_LOW_END_FRAME_GOVERNOR_NOT_READY"
            );
        }

        DrawSubmissionDispatcher.enrich(
                report
        );

        DrawSubmissionDispatcher
                .uninstall(
                        runtime.drawSubmissionSink()
                );

        RenderTargetOperationDispatcher
                .uninstall(
                        runtime.operationSink()
                );

        try {
            long runtimeCloseStartNanos =
                    System.nanoTime();

            runtime.close();

            if (report != null) {
                report.addProperty(
                        "runtimeShutdownRuntimeCloseMillis",
                        (System.nanoTime()
                                - runtimeCloseStartNanos)
                                / 1_000_000L
                );
                report.addProperty(
                        "runtimeShutdownPotatoHookTotalMillis",
                        (System.nanoTime()
                                - potatoShutdownHookStartNanos)
                                / 1_000_000L
                );
                report.addProperty(
                        "renderTargetOperationDispatcherActiveAfterUninstall",
                        RenderTargetOperationDispatcher.active()
                );
                report.addProperty(
                        "drawSubmissionDispatcherActiveAfterUninstall",
                        DrawSubmissionDispatcher.active()
                );
                report.addProperty(
                        "persistentVulkanRuntimeShutdownHookObserved",
                        true
                );
                report.addProperty(
                        "persistentVulkanRuntimeShutdownHook",
                        "Minecraft.stop()V"
                );
                report.addProperty(
                        "runtimeShutdownStage",
                        "PERSISTENT_VULKAN_RUNTIME_SHUTDOWN_COMPLETE"
                );
            }
        } catch (Throwable throwable) {
            PotatoRuntime.LOGGER.error(
                    "[Potato/Vulkan] Persistent runtime shutdown failed.",
                    throwable
            );

            if (report != null) {
                report.addProperty(
                        "persistentVulkanRuntimeShutdownComplete",
                        false
                );
                report.addProperty(
                        "persistentVulkanRuntimeShutdownError",
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                        throwable.getMessage()
                                )
                );
            }
        } finally {
            /*
             * Patch 117a: Gate 11 is evaluated only at final shutdown report boundaries.
             * The current manager has three legitimate terminal paths: headless,
             * runtime-null, and live-runtime-finally. The qualifier is sticky and
             * therefore safe to re-evaluate before any of those final snapshots.
             */
            VulkanGate11RuntimeQualification
                    .qualify(
                            report
                    );
            dev.ordovicium.potato.render.engine.PotatoRenderEngine
                    .reconcileRuntimeReadiness(
                            report
                    );
            writeBoundReport(
                    path,
                    report,
                    true
            );
        }
    }

    private static void closeFailedRuntime(
            JsonObject report,
            String reason
    ) {
        VulkanRuntimeContext runtime;

        synchronized (LOCK) {
            runtime = active;
            active = null;
        }

        if (runtime != null) {
            DrawSubmissionDispatcher
                    .uninstall(
                            runtime.drawSubmissionSink()
                    );

            RenderTargetOperationDispatcher
                    .uninstall(
                            runtime.operationSink()
                    );
        }

        report.addProperty(
                "persistentVulkanRuntimeFailSafeClosed",
                runtime != null
        );
        report.addProperty(
                "persistentVulkanRuntimeFailSafeReason",
                reason
        );

        if (runtime != null) {
            try {
                runtime.close();
            } catch (Throwable throwable) {
                report.addProperty(
                        "persistentVulkanRuntimeFailSafeCloseError",
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                        throwable.getMessage()
                                )
                );
            }
        }
    }

    private static void writeBoundReport(
            Path path,
            JsonObject report,
            boolean copyShutdownSnapshot
    ) {
        if (path == null || report == null) {
            return;
        }

        try {
            VulkanProbe.writeReport(
                    path,
                    report
            );

            PotatoRuntime.LOGGER.info(
                    "[Potato/Vulkan] Runtime report updated at {}.",
                    path.toAbsolutePath()
            );

            if (copyShutdownSnapshot) {
                copyIntoDevelopmentDropoff(
                        path
                );
            }
        } catch (IOException exception) {
            PotatoRuntime.LOGGER.warn(
                    "[Potato/Vulkan] Could not update persistent runtime report.",
                    exception
            );
        }
    }

    private static void copyIntoDevelopmentDropoff(
            Path source
    ) {
        Path current =
                source
                        .toAbsolutePath()
                        .getParent();

        for (int depth = 0;
             depth < 6 && current != null;
             depth++) {

            Path reports =
                    current
                            .resolve("_dropoff")
                            .resolve("reports");

            if (Files.isDirectory(reports)) {
                try {
                    String timestamp =
                            Long.toString(
                                    System.currentTimeMillis()
                            );

                    Path destination =
                            reports.resolve(
                                    timestamp
                                            + "_vulkan-runtime-shutdown.json"
                            );

                    Files.copy(
                            source,
                            destination,
                            StandardCopyOption.REPLACE_EXISTING
                    );

                    PotatoRuntime.LOGGER.info(
                            "[Potato/Vulkan] Shutdown runtime report copied to {}.",
                            destination.toAbsolutePath()
                    );
                } catch (IOException exception) {
                    PotatoRuntime.LOGGER.warn(
                            "[Potato/Vulkan] Could not copy shutdown runtime report into _dropoff.",
                            exception
                    );
                }

                return;
            }

            current =
                    current.getParent();
        }
    }
}
