package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.target.RenderTargetOperationSink;

/**
 * Live semantic mirror of Minecraft's real MainTarget operations.
 *
 * <p>Patch 031 turns the previous independent resize/clear/blit GPU mirrors
 * into a coherent frame lifecycle:</p>
 *
 * <pre>
 * bindWrite -> frame open
 * clear     -> frame state
 * blit      -> frame close
 *              resize if required
 *              optional clear + presentation in one Vulkan submission
 * </pre>
 *
 * <p>OpenGL remains authoritative.</p>
 */
final class VulkanRenderTargetOperationMirror
        implements RenderTargetOperationSink {

    private static final long RESIZE_SETTLE_NANOS =
            75_000_000L;

    private static final long FRAME_PRESENT_MIN_INTERVAL_NANOS =
            250_000_000L;

    private final VulkanFrameSession frameSession;
    private final JsonObject report;

    private long totalDispatchCount;

    private long setClearColorCount;
    private long resizeCount;
    private long bindReadCount;
    private long unbindReadCount;
    private long bindWriteCount;
    private long clearCount;
    private long blitCount;
    private long copyDepthCount;

    private long renderThreadDispatchCount;
    private long nonRenderThreadDispatchCount;

    private String firstDispatchThread = "";
    private String lastDispatchThread = "";
    private String lastOperation = "";

    private int latestWidth;
    private int latestHeight;

    private int latestBlitDestinationWidth;
    private int latestBlitDestinationHeight;

    private float clearRed;
    private float clearGreen;
    private float clearBlue;
    private float clearAlpha;

    private boolean latestBindWriteUpdatesViewport;
    private boolean latestBlitDisablesBlend;
    private boolean latestUseDepth;

    // ---------------------------------------------------------------------
    // Resize lifecycle
    // ---------------------------------------------------------------------

    private boolean pendingResize;
    private int pendingResizeWidth;
    private int pendingResizeHeight;
    private boolean pendingResizeUseDepth;
    private long pendingResizeLastRequestNanos;

    private int lastResizeRequestedWidth;
    private int lastResizeRequestedHeight;

    private long resizeGpuQueuedCount;
    private long resizeGpuAttemptCount;
    private long resizeGpuAppliedCount;
    private long resizeGpuNoOpCount;
    private long resizeGpuFailureCount;
    private long resizeGpuCoalescedReplacementCount;

    private String lastResizeGpuError = "";

    // ---------------------------------------------------------------------
    // Clear state: no standalone Vulkan submission in Patch 031.
    // ---------------------------------------------------------------------

    private boolean pendingClear;
    private float pendingClearRed;
    private float pendingClearGreen;
    private float pendingClearBlue;
    private float pendingClearAlpha;

    private long clearGpuQueuedCount;
    private long clearGpuAttemptCount;
    private long clearGpuAppliedCount;
    private long clearGpuCoalescedReplacementCount;
    private long clearGpuFailureCount;

    private int lastAppliedClearTargetGeneration = -1;

    private float lastAppliedClearRed;
    private float lastAppliedClearGreen;
    private float lastAppliedClearBlue;
    private float lastAppliedClearAlpha;

    private String lastClearGpuError = "";

    // ---------------------------------------------------------------------
    // Semantic MainTarget frame lifecycle
    // ---------------------------------------------------------------------

    private boolean semanticFrameOpen;
    private long semanticFrameSequence;
    private long activeSemanticFrameSequence;

    private long semanticFrameBeginCount;
    private long semanticFrameEndCount;
    private long semanticFrameRebindCount;
    private long semanticFrameSyntheticBeginCount;
    private long semanticFrameSyntheticFlushCount;
    private long semanticFrameClearObservedCount;

    private boolean pendingFrame;
    private long pendingFrameSequence;
    private int pendingFrameDestinationWidth;
    private int pendingFrameDestinationHeight;
    private boolean pendingFrameDisableBlend;

    private long frameGpuQueuedCount;
    private long frameGpuAttemptCount;
    private long frameGpuAppliedCount;
    private long frameGpuFailureCount;
    private long frameGpuWithClearCount;
    private long frameGpuWithoutClearCount;
    private long frameGpuThrottledCount;
    private long frameGpuSkippedPendingResizeCount;
    private long frameGpuCoalescedReplacementCount;
    private long frameGpuSuppressedAfterFailureCount;

    private long lastFrameGpuPresentNanos;
    private long lastPresentedSemanticFrameSequence;
    private int lastPresentedTargetGeneration = -1;

    private boolean frameGpuDisabledAfterFailure;
    private String lastFrameGpuError = "";

    VulkanRenderTargetOperationMirror(
            VulkanFrameSession frameSession,
            JsonObject report
    ) {
        this.frameSession = frameSession;
        this.report = report;
    }

    @Override
    public synchronized void onSetClearColor(
            int width,
            int height,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        record(
                "SET_CLEAR_COLOR",
                width,
                height
        );

        setClearColorCount++;

        clearRed = red;
        clearGreen = green;
        clearBlue = blue;
        clearAlpha = alpha;
    }

    @Override
    public synchronized void onResize(
            int width,
            int height,
            boolean useDepth
    ) {
        record(
                "RESIZE",
                width,
                height
        );

        resizeCount++;

        lastResizeRequestedWidth =
                width;
        lastResizeRequestedHeight =
                height;

        if (pendingResize
                && (pendingResizeWidth != width
                || pendingResizeHeight != height
                || pendingResizeUseDepth != useDepth)) {
            resizeGpuCoalescedReplacementCount++;
        }

        pendingResize = true;

        pendingResizeWidth = width;
        pendingResizeHeight = height;
        pendingResizeUseDepth = useDepth;

        pendingResizeLastRequestNanos =
                System.nanoTime();

        resizeGpuQueuedCount++;

        latestUseDepth = useDepth;

        /*
         * Release fast path mirrors resize only. Apply it immediately because
         * bindWrite/blit callbacks are intentionally suppressed.
         */
        if (!dev.ordovicium.potato.render.backend.RuntimePerformancePolicy
                .hiddenFrameMirrorEnabled()) {
            applyPendingResizeIfReady(
                    true
            );
        }
    }

    @Override
    public synchronized void onBindRead(
            int width,
            int height
    ) {
        record(
                "BIND_READ",
                width,
                height
        );

        bindReadCount++;
    }

    @Override
    public synchronized void onUnbindRead(
            int width,
            int height
    ) {
        record(
                "UNBIND_READ",
                width,
                height
        );

        unbindReadCount++;
    }

    @Override
    public synchronized void onBindWrite(
            int width,
            int height,
            boolean updateViewport
    ) {
        record(
                "BIND_WRITE",
                width,
                height
        );

        bindWriteCount++;

        latestBindWriteUpdatesViewport =
                updateViewport;

        if (!semanticFrameOpen) {
            beginSemanticFrame(false);
        } else {
            semanticFrameRebindCount++;
        }

        /*
         * Resource replacement may happen before the eventual presentation,
         * but clear/present themselves now share one frame submission.
         */
        applyPendingResizeIfReady(false);
    }

    @Override
    public synchronized void onClear(
            int width,
            int height,
            boolean useDepth,
            float red,
            float green,
            float blue,
            float alpha
    ) {
        record(
                "CLEAR",
                width,
                height
        );

        clearCount++;
        latestUseDepth = useDepth;

        ensureSemanticFrame();

        semanticFrameClearObservedCount++;

        clearRed = red;
        clearGreen = green;
        clearBlue = blue;
        clearAlpha = alpha;

        if (pendingClear) {
            clearGpuCoalescedReplacementCount++;
        }

        pendingClear = true;

        pendingClearRed = red;
        pendingClearGreen = green;
        pendingClearBlue = blue;
        pendingClearAlpha = alpha;

        clearGpuQueuedCount++;
    }

    @Override
    public synchronized void onBlitToScreen(
            int sourceWidth,
            int sourceHeight,
            int destinationWidth,
            int destinationHeight,
            boolean disableBlend
    ) {
        record(
                "BLIT_TO_SCREEN",
                sourceWidth,
                sourceHeight
        );

        blitCount++;

        latestBlitDestinationWidth =
                destinationWidth;
        latestBlitDestinationHeight =
                destinationHeight;

        latestBlitDisablesBlend =
                disableBlend;

        ensureSemanticFrame();

        semanticFrameEndCount++;

        if (pendingFrame) {
            frameGpuCoalescedReplacementCount++;
        }

        pendingFrame = true;
        pendingFrameSequence =
                activeSemanticFrameSequence;
        pendingFrameDestinationWidth =
                destinationWidth;
        pendingFrameDestinationHeight =
                destinationHeight;
        pendingFrameDisableBlend =
                disableBlend;

        frameGpuQueuedCount++;

        semanticFrameOpen = false;

        applyPendingResizeIfReady(false);
        applyPendingFrameIfReady(false);
    }

    @Override
    public synchronized void onCopyDepth(
            int width,
            int height
    ) {
        record(
                "COPY_DEPTH",
                width,
                height
        );

        copyDepthCount++;
    }

    synchronized void flushPendingOperations() {
        /*
         * A nested clear/resize could theoretically occur immediately before
         * shutdown without another public blit. Reuse the latest known
         * destination to produce one final coherent mirror frame.
         */
        if (!pendingFrame
                && (pendingResize || pendingClear)
                && blitCount > 0) {

            beginSemanticFrame(true);

            semanticFrameEndCount++;
            semanticFrameSyntheticFlushCount++;

            pendingFrame = true;
            pendingFrameSequence =
                    activeSemanticFrameSequence;
            pendingFrameDestinationWidth =
                    latestBlitDestinationWidth;
            pendingFrameDestinationHeight =
                    latestBlitDestinationHeight;
            pendingFrameDisableBlend =
                    latestBlitDisablesBlend;

            semanticFrameOpen = false;
        }

        applyPendingResizeIfReady(true);
        applyPendingFrameIfReady(true);
    }

    synchronized boolean resizePropagationVerified() {
        return resizeCount > 0
                && resizeGpuAttemptCount > 0
                && resizeGpuAppliedCount > 0
                && resizeGpuFailureCount == 0
                && !pendingResize
                && frameSession.offscreenWidth()
                == lastResizeRequestedWidth
                && frameSession.offscreenHeight()
                == lastResizeRequestedHeight;
    }

    synchronized boolean clearPropagationVerified() {
        return clearCount > 0
                && clearGpuAttemptCount > 0
                && clearGpuAppliedCount > 0
                && clearGpuFailureCount == 0
                && !pendingClear
                && lastAppliedClearTargetGeneration
                == frameSession.offscreenTargetGeneration()
                && sameFloat(
                        lastAppliedClearRed,
                        clearRed
                )
                && sameFloat(
                        lastAppliedClearGreen,
                        clearGreen
                )
                && sameFloat(
                        lastAppliedClearBlue,
                        clearBlue
                )
                && sameFloat(
                        lastAppliedClearAlpha,
                        clearAlpha
                );
    }

    synchronized boolean blitPropagationVerified() {
        return blitCount > 0
                && frameGpuAttemptCount > 0
                && frameGpuAppliedCount > 0
                && frameGpuFailureCount == 0
                && !pendingFrame
                && !frameGpuDisabledAfterFailure
                && lastPresentedTargetGeneration
                == frameSession.offscreenTargetGeneration();
    }

    synchronized boolean frameLifecycleVerified() {
        return semanticFrameBeginCount > 0
                && semanticFrameEndCount > 0
                && frameGpuAttemptCount > 0
                && frameGpuAppliedCount > 0
                && frameGpuFailureCount == 0
                && lastPresentedSemanticFrameSequence > 0
                && !pendingFrame
                && !pendingClear
                && !frameGpuDisabledAfterFailure
                && blitPropagationVerified()
                && clearPropagationVerified();
    }

    synchronized boolean liveDispatchVerified() {
        return totalDispatchCount > 0
                && bindWriteCount > 0
                && blitCount > 0
                && renderThreadDispatchCount > 0
                && nonRenderThreadDispatchCount == 0;
    }

    synchronized void enrich() {
        frameSession.enrichPersistentState();

        report.addProperty(
                "renderTargetOperationMirrorInstalled",
                true
        );
        report.addProperty(
                "renderTargetOperationMirrorMode",
                "OPENGL_BASELINE_PLUS_VULKAN_RESIZE_ONLY_FAST_PATH"
        );
        report.addProperty(
                "renderTargetOperationBaselineOpenGlStillExecutes",
                true
        );
        report.addProperty(
                "renderTargetOperationVulkanGpuExecutionEnabled",
                resizeGpuAppliedCount > 0
                        || frameGpuAppliedCount > 0
        );
        report.addProperty(
                "renderTargetOperationVulkanGpuExecutionCoverage",
                "MAIN_TARGET_RESIZE_ONLY_UNTIL_VISIBLE_CUTOVER"
        );

        report.addProperty(
                "runtimeMainTargetOperationDispatchCount",
                totalDispatchCount
        );
        report.addProperty(
                "runtimeMainTargetSetClearColorDispatchCount",
                setClearColorCount
        );
        report.addProperty(
                "runtimeMainTargetResizeDispatchCount",
                resizeCount
        );
        report.addProperty(
                "runtimeMainTargetBindReadDispatchCount",
                bindReadCount
        );
        report.addProperty(
                "runtimeMainTargetUnbindReadDispatchCount",
                unbindReadCount
        );
        report.addProperty(
                "runtimeMainTargetBindWriteDispatchCount",
                bindWriteCount
        );
        report.addProperty(
                "runtimeMainTargetClearDispatchCount",
                clearCount
        );
        report.addProperty(
                "runtimeMainTargetBlitDispatchCount",
                blitCount
        );
        report.addProperty(
                "runtimeMainTargetCopyDepthDispatchCount",
                copyDepthCount
        );

        report.addProperty(
                "runtimeMainTargetRenderThreadDispatchCount",
                renderThreadDispatchCount
        );
        report.addProperty(
                "runtimeMainTargetNonRenderThreadDispatchCount",
                nonRenderThreadDispatchCount
        );

        report.addProperty(
                "runtimeMainTargetFirstDispatchThread",
                firstDispatchThread
        );
        report.addProperty(
                "runtimeMainTargetLastDispatchThread",
                lastDispatchThread
        );
        report.addProperty(
                "runtimeMainTargetLastOperation",
                lastOperation
        );

        report.addProperty(
                "runtimeMainTargetLatestWidth",
                latestWidth
        );
        report.addProperty(
                "runtimeMainTargetLatestHeight",
                latestHeight
        );
        report.addProperty(
                "runtimeMainTargetLatestBlitDestinationWidth",
                latestBlitDestinationWidth
        );
        report.addProperty(
                "runtimeMainTargetLatestBlitDestinationHeight",
                latestBlitDestinationHeight
        );

        report.addProperty(
                "runtimeMainTargetLatestClearRed",
                clearRed
        );
        report.addProperty(
                "runtimeMainTargetLatestClearGreen",
                clearGreen
        );
        report.addProperty(
                "runtimeMainTargetLatestClearBlue",
                clearBlue
        );
        report.addProperty(
                "runtimeMainTargetLatestClearAlpha",
                clearAlpha
        );

        report.addProperty(
                "runtimeMainTargetLatestUseDepth",
                latestUseDepth
        );
        report.addProperty(
                "runtimeMainTargetLatestBindWriteUpdatesViewport",
                latestBindWriteUpdatesViewport
        );
        report.addProperty(
                "runtimeMainTargetLatestBlitDisablesBlend",
                latestBlitDisablesBlend
        );

        // Resize compatibility diagnostics.
        report.addProperty(
                "vulkanMainTargetResizePropagationEnabled",
                true
        );
        report.addProperty(
                "vulkanMainTargetResizeCoalescingEnabled",
                true
        );
        report.addProperty(
                "vulkanMainTargetResizeSettleMillis",
                RESIZE_SETTLE_NANOS / 1_000_000L
        );
        report.addProperty(
                "vulkanMainTargetResizePending",
                pendingResize
        );
        report.addProperty(
                "vulkanMainTargetResizeGpuQueuedCount",
                resizeGpuQueuedCount
        );
        report.addProperty(
                "vulkanMainTargetResizeGpuAttemptCount",
                resizeGpuAttemptCount
        );
        report.addProperty(
                "vulkanMainTargetResizeGpuAppliedCount",
                resizeGpuAppliedCount
        );
        report.addProperty(
                "vulkanMainTargetResizeGpuNoOpCount",
                resizeGpuNoOpCount
        );
        report.addProperty(
                "vulkanMainTargetResizeGpuFailureCount",
                resizeGpuFailureCount
        );
        report.addProperty(
                "vulkanMainTargetResizeCoalescedReplacementCount",
                resizeGpuCoalescedReplacementCount
        );
        report.addProperty(
                "vulkanMainTargetResizeLastRequestedWidth",
                lastResizeRequestedWidth
        );
        report.addProperty(
                "vulkanMainTargetResizeLastRequestedHeight",
                lastResizeRequestedHeight
        );
        report.addProperty(
                "vulkanMainTargetResizePropagationVerified",
                resizePropagationVerified()
        );
        report.addProperty(
                "runtimeMainTargetResizeGpuRebuildDeferred",
                pendingResize
        );

        if (!lastResizeGpuError.isBlank()) {
            report.addProperty(
                    "vulkanMainTargetResizeLastError",
                    lastResizeGpuError
            );
        }

        // Clear is now part of a frame submission.
        report.addProperty(
                "vulkanMainTargetClearPropagationEnabled",
                true
        );
        report.addProperty(
                "vulkanMainTargetClearCoalescingEnabled",
                true
        );
        report.addProperty(
                "vulkanMainTargetClearPending",
                pendingClear
        );
        report.addProperty(
                "vulkanMainTargetClearGpuQueuedCount",
                clearGpuQueuedCount
        );
        report.addProperty(
                "vulkanMainTargetClearGpuAttemptCount",
                clearGpuAttemptCount
        );
        report.addProperty(
                "vulkanMainTargetClearGpuAppliedCount",
                clearGpuAppliedCount
        );
        report.addProperty(
                "vulkanMainTargetClearGpuDeduplicatedCount",
                0
        );
        report.addProperty(
                "vulkanMainTargetClearCoalescedReplacementCount",
                clearGpuCoalescedReplacementCount
        );
        report.addProperty(
                "vulkanMainTargetClearGpuFailureCount",
                clearGpuFailureCount
        );
        report.addProperty(
                "vulkanMainTargetClearLastAppliedTargetGeneration",
                lastAppliedClearTargetGeneration
        );
        report.addProperty(
                "vulkanMainTargetClearStandaloneSubmissionUsed",
                false
        );
        report.addProperty(
                "vulkanMainTargetClearIntegratedIntoFrameSubmission",
                true
        );
        report.addProperty(
                "vulkanMainTargetClearPropagationVerified",
                clearPropagationVerified()
        );
        report.addProperty(
                "runtimeMainTargetClearGpuExecutionDeferred",
                pendingClear
        );

        if (!lastClearGpuError.isBlank()) {
            report.addProperty(
                    "vulkanMainTargetClearLastError",
                    lastClearGpuError
            );
        }

        // Blit compatibility diagnostics are now backed by coherent frames.
        report.addProperty(
                "vulkanMainTargetBlitPropagationEnabled",
                true
        );
        report.addProperty(
                "vulkanMainTargetBlitPresentationThrottleEnabled",
                true
        );
        report.addProperty(
                "vulkanMainTargetBlitPresentationThrottleMillis",
                FRAME_PRESENT_MIN_INTERVAL_NANOS / 1_000_000L
        );
        report.addProperty(
                "vulkanMainTargetBlitPending",
                pendingFrame
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuQueuedCount",
                frameGpuQueuedCount
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuAttemptCount",
                frameGpuAttemptCount
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuAppliedCount",
                frameGpuAppliedCount
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuThrottledCount",
                frameGpuThrottledCount
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuSkippedPendingMutationCount",
                frameGpuSkippedPendingResizeCount
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuCoalescedReplacementCount",
                frameGpuCoalescedReplacementCount
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuFailureCount",
                frameGpuFailureCount
        );
        report.addProperty(
                "vulkanMainTargetBlitGpuSuppressedAfterFailureCount",
                frameGpuSuppressedAfterFailureCount
        );
        report.addProperty(
                "vulkanMainTargetBlitLastPresentedTargetGeneration",
                lastPresentedTargetGeneration
        );
        report.addProperty(
                "vulkanMainTargetBlitDisabledAfterFailure",
                frameGpuDisabledAfterFailure
        );
        report.addProperty(
                "vulkanMainTargetBlitReadsOffscreenWithoutDirtying",
                !pendingClear
        );
        report.addProperty(
                "vulkanMainTargetBlitPropagationVerified",
                blitPropagationVerified()
        );

        // New Patch-031 lifecycle diagnostics.
        report.addProperty(
                "vulkanMainTargetFrameLifecycleEnabled",
                true
        );
        report.addProperty(
                "vulkanMainTargetFrameLifecycleMode",
                "BIND_WRITE_CLEAR_BLIT_SINGLE_SUBMISSION"
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameOpen",
                semanticFrameOpen
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameSequence",
                semanticFrameSequence
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameBeginCount",
                semanticFrameBeginCount
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameEndCount",
                semanticFrameEndCount
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameRebindCount",
                semanticFrameRebindCount
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameSyntheticBeginCount",
                semanticFrameSyntheticBeginCount
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameSyntheticFlushCount",
                semanticFrameSyntheticFlushCount
        );
        report.addProperty(
                "vulkanMainTargetSemanticFrameClearObservedCount",
                semanticFrameClearObservedCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuQueuedCount",
                frameGpuQueuedCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuAttemptCount",
                frameGpuAttemptCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuAppliedCount",
                frameGpuAppliedCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuFailureCount",
                frameGpuFailureCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuWithClearCount",
                frameGpuWithClearCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuWithoutClearCount",
                frameGpuWithoutClearCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuThrottledCount",
                frameGpuThrottledCount
        );
        report.addProperty(
                "vulkanMainTargetFrameGpuCoalescedReplacementCount",
                frameGpuCoalescedReplacementCount
        );
        report.addProperty(
                "vulkanMainTargetFrameLastPresentedSemanticSequence",
                lastPresentedSemanticFrameSequence
        );
        report.addProperty(
                "vulkanMainTargetFrameLastPresentedTargetGeneration",
                lastPresentedTargetGeneration
        );
        report.addProperty(
                "vulkanMainTargetFrameStandaloneClearSubmissionCount",
                0
        );
        report.addProperty(
                "vulkanMainTargetFrameClearAndPresentSingleSubmission",
                frameGpuWithClearCount > 0
        );
        report.addProperty(
                "vulkanMainTargetFrameLifecycleVerified",
                frameLifecycleVerified()
        );

        if (!lastFrameGpuError.isBlank()) {
            report.addProperty(
                    "vulkanMainTargetFrameLastError",
                    lastFrameGpuError
            );
        }

        report.addProperty(
                "renderTargetOperationLiveDispatchVerified",
                liveDispatchVerified()
        );
    }

    private void beginSemanticFrame(
            boolean synthetic
    ) {
        semanticFrameSequence++;

        activeSemanticFrameSequence =
                semanticFrameSequence;

        semanticFrameOpen = true;
        semanticFrameBeginCount++;

        if (synthetic) {
            semanticFrameSyntheticBeginCount++;
        }
    }

    private void ensureSemanticFrame() {
        if (!semanticFrameOpen) {
            beginSemanticFrame(true);
        }
    }

    private void applyPendingResizeIfReady(
            boolean force
    ) {
        if (!pendingResize) {
            return;
        }

        long elapsed =
                System.nanoTime()
                        - pendingResizeLastRequestNanos;

        if (!force
                && elapsed < RESIZE_SETTLE_NANOS) {
            return;
        }

        int width =
                pendingResizeWidth;
        int height =
                pendingResizeHeight;
        boolean useDepth =
                pendingResizeUseDepth;

        pendingResize = false;
        resizeGpuAttemptCount++;

        try {
            VulkanFrameSession.ResizeOutcome outcome =
                    frameSession.resizeOffscreenTarget(
                            width,
                            height,
                            useDepth
                    );

            if (outcome.changed()) {
                resizeGpuAppliedCount++;
            } else {
                resizeGpuNoOpCount++;
            }

            lastResizeGpuError = "";
        } catch (Throwable throwable) {
            resizeGpuFailureCount++;

            lastResizeGpuError =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );

            report.addProperty(
                    "vulkanMainTargetResizeFailureContained",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetResizeFallbackBackend",
                    "OPENGL_BASELINE"
            );
        }
    }

    private void applyPendingFrameIfReady(
            boolean force
    ) {
        if (!pendingFrame) {
            return;
        }

        if (frameGpuDisabledAfterFailure) {
            pendingFrame = false;
            frameGpuSuppressedAfterFailureCount++;
            return;
        }

        if (pendingResize) {
            applyPendingResizeIfReady(force);

            if (pendingResize) {
                frameGpuSkippedPendingResizeCount++;
                return;
            }
        }

        long now =
                System.nanoTime();

        if (!force
                && lastFrameGpuPresentNanos != 0L
                && now - lastFrameGpuPresentNanos
                < FRAME_PRESENT_MIN_INTERVAL_NANOS) {

            frameGpuThrottledCount++;
            return;
        }

        long frameSequence =
                pendingFrameSequence;

        int destinationWidth =
                pendingFrameDestinationWidth;
        int destinationHeight =
                pendingFrameDestinationHeight;

        boolean disableBlend =
                pendingFrameDisableBlend;

        boolean includeClear =
                pendingClear;

        float red =
                pendingClearRed;
        float green =
                pendingClearGreen;
        float blue =
                pendingClearBlue;
        float alpha =
                pendingClearAlpha;

        pendingFrame = false;

        frameGpuAttemptCount++;

        if (includeClear) {
            clearGpuAttemptCount++;
        }

        try {
            VulkanFrameSession.FrameOutcome outcome =
                    frameSession.presentMainTargetFrame(
                            frameSequence,
                            destinationWidth,
                            destinationHeight,
                            disableBlend,
                            includeClear,
                            red,
                            green,
                            blue,
                            alpha
                    );

            lastPresentedSemanticFrameSequence =
                    outcome.semanticFrameSequence();

            lastPresentedTargetGeneration =
                    outcome.targetGeneration();

            lastFrameGpuPresentNanos =
                    System.nanoTime();

            frameGpuAppliedCount++;

            if (includeClear) {
                pendingClear = false;

                clearGpuAppliedCount++;
                frameGpuWithClearCount++;

                lastAppliedClearTargetGeneration =
                        outcome.targetGeneration();

                lastAppliedClearRed = red;
                lastAppliedClearGreen = green;
                lastAppliedClearBlue = blue;
                lastAppliedClearAlpha = alpha;

                lastClearGpuError = "";
            } else {
                frameGpuWithoutClearCount++;
            }

            lastFrameGpuError = "";
        } catch (Throwable throwable) {
            frameGpuFailureCount++;

            lastFrameGpuError =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );

            if (includeClear) {
                clearGpuFailureCount++;

                lastClearGpuError =
                        lastFrameGpuError;

                pendingClear = false;
            }

            frameGpuDisabledAfterFailure = true;

            report.addProperty(
                    "vulkanMainTargetFrameFailureContained",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetFrameFallbackBackend",
                    "OPENGL_BASELINE"
            );
        }
    }

    private static boolean sameFloat(
            float first,
            float second
    ) {
        return Float.floatToIntBits(first)
                == Float.floatToIntBits(second);
    }

    private void record(
            String operation,
            int width,
            int height
    ) {
        totalDispatchCount++;

        String thread =
                Thread.currentThread().getName();

        if (firstDispatchThread.isBlank()) {
            firstDispatchThread =
                    thread;
        }

        lastDispatchThread =
                thread;

        lastOperation =
                operation;

        latestWidth =
                width;
        latestHeight =
                height;

        if ("Render thread".equals(thread)) {
            renderThreadDispatchCount++;
        } else {
            nonRenderThreadDispatchCount++;
        }
    }
}
