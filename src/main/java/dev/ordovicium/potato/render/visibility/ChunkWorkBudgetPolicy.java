package dev.ordovicium.potato.render.visibility;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.engine.PotatoHardwareClass;
import dev.ordovicium.potato.render.engine.PotatoRenderBackendId;
import dev.ordovicium.potato.render.engine.PotatoRenderEngine;
import dev.ordovicium.potato.render.engine.PotatoRenderEngineDecision;

import java.util.Queue;

/**
 * Frame-pacing policy for vanilla chunk compilation/upload work.
 *
 * <p>Patch 045 proved that Minecraft already compiles only sections contained
 * in LevelRenderer.visibleSections. Potato therefore does not invent a second
 * "COLD sections" queue here. Instead it controls two render-thread spike
 * sources that remain after vanilla visibility selection:</p>
 *
 * <ul>
 *     <li>synchronous near/player-affected section rebuilds;</li>
 *     <li>unbounded draining of completed mesh uploads.</li>
 * </ul>
 *
 * <p>No work is discarded. Work exceeding a per-pass budget remains queued or
 * is scheduled through vanilla's existing asynchronous dispatcher.</p>
 */
public final class ChunkWorkBudgetPolicy {

    private static final int DEFAULT_MAX_SYNC_REBUILDS_PER_PASS =
            1;

    /*
     * Patch 143 player-local render safety ring. This is client mesh priority
     * only; it never creates or mutates server chunk tickets.
     */
    private static final int NEAR_FIELD_PRIORITY_RADIUS_CHUNKS =
            1;

    private static final int NEAR_FIELD_PRIORITY_MAX_SYNC_PER_PASS =
            integerProperty(
                    "potato.chunk.nearFieldMaxSyncPerPass",
                    2,
                    0,
                    4
            );

    /*
     * Upload scheduling is now bounded by both count and elapsed render-thread
     * wall time.
     *
     * The minimum guarantees forward progress.
     * The maximum prevents pathological queue draining even when individual
     * upload Runnables happen to be extremely cheap.
     */
    private static final int DEFAULT_MIN_UPLOADS_PER_PASS =
            4;

    private static final int DEFAULT_MAX_UPLOADS_PER_PASS =
            32;

    private static final long DEFAULT_UPLOAD_TIME_BUDGET_NANOS =
            1_500_000L;

    private static final int MAX_CONFIGURED_SYNC_REBUILDS =
            8;

    private static final int MAX_CONFIGURED_UPLOADS =
            128;

    private static final long MIN_UPLOAD_TIME_BUDGET_NANOS =
            250_000L;

    private static final long MAX_UPLOAD_TIME_BUDGET_NANOS =
            8_000_000L;

    private static final int maxSyncRebuildsPerPass =
            integerProperty(
                    "potato.chunk.maxSyncBuildsPerPass",
                    DEFAULT_MAX_SYNC_REBUILDS_PER_PASS,
                    0,
                    MAX_CONFIGURED_SYNC_REBUILDS
            );

    private static final int minUploadsPerPass =
            integerProperty(
                    "potato.chunk.minUploadsPerPass",
                    1,
                    1,
                    MAX_CONFIGURED_UPLOADS
            );

    private static final int maxUploadsPerPass =
            Math.max(
                    minUploadsPerPass,
                    integerProperty(
                            "potato.chunk.maxUploadsPerPass",
                            4,
                            1,
                            MAX_CONFIGURED_UPLOADS
                    )
            );

    private static final long uploadTimeBudgetNanos =
            Math.max(
                    250_000L,
                    Math.min(
                            4_000_000L,
                            Long.getLong(
                                    "potato.chunk.uploadTimeBudgetNanos",
                                    1_000_000L
                            )
                    )
            );

    /*
     * Both patched call sites execute on the render thread in the mapped
     * vanilla flow. No atomics/locks are needed on the hot path.
     */
    private static int syncRebuildsUsedThisPass;
    private static int uploadsUsedThisPass;

    private static long uploadPassStartedNanos;
    private static long uploadPassElapsedNanosLast;
    private static long uploadPassRunnableElapsedNanos;
    private static long uploadRunnableElapsedNanosPeak;
    private static long uploadRunnableTimingSampleCount;

    private static long uploadSpikeCount;
    private static int uploadSpikeCooldownPassesRemaining;
    private static int uploadSpikeEffectiveMaxUploads =
            Integer.MAX_VALUE;
    private static long uploadSpikeEffectiveBudgetNanos =
            Long.MAX_VALUE;

    private static int lastObservedUploadQueueSize;
    private static int streamingPressureLevel;
    private static long streamingPressureLevelChangeCount;

    private static long compilePassCount;
    private static long uploadPassCount;

    private static long uploadTimeBudgetHitCount;
    private static long uploadCountBudgetHitCount;

    private static long uploadElapsedNanosTotal;
    private static long uploadElapsedNanosPeak;

    private static int uploadPassCompletedCount;
    private static int uploadPassPeakRunnableCount;

    private static long uploadPassesBelowOldFixedTwelveCount;
    private static long uploadPassesAboveOldFixedTwelveCount;

    private static long syncRebuildRequestedCount;
    private static long syncRebuildExecutedCount;
    private static long syncRebuildRedirectedAsyncCount;
    private static long nearFieldPrioritySyncCount;
    private static long nearFieldPriorityAsyncFallbackCount;
    private static long predictivePrioritySyncCount;
    private static long predictivePriorityAsyncFallbackCount;
    private static long predictiveFarAsyncObservedCount;
    private static long speedAwareUploadBoostPassCount;
    private static long speedAwareUploadBoostNanosTotal;
    private static long speedAwareUploadBoostNanosPeak;
    private static long speedAwareUploadBoostLastRecordedPass = -1L;

    private static long uploadPollCount;
    private static long uploadRunnableExecutedCount;
    private static long uploadBudgetHitCount;
    private static long uploadRunnableDeferredEstimate;

    private static int lastDeferredUploadQueueSize;
    private static int peakDeferredUploadQueueSize;

    private ChunkWorkBudgetPolicy() {
    }

    public static void beginCompilePass() {
        syncRebuildsUsedThisPass =
                0;

        compilePassCount++;
    }

    public static boolean allowSynchronousRebuild() {
        syncRebuildRequestedCount++;

        if (syncRebuildsUsedThisPass
                < maxSyncRebuildsPerPass) {

            syncRebuildsUsedThisPass++;

            syncRebuildExecutedCount++;

            return true;
        }

        syncRebuildRedirectedAsyncCount++;

        return false;
    }

    public static int nearFieldPriorityRadiusChunks() {
        return NEAR_FIELD_PRIORITY_RADIUS_CHUNKS;
    }

    public static int nearFieldPriorityMaximumSyncPerPass() {
        return NEAR_FIELD_PRIORITY_MAX_SYNC_PER_PASS;
    }

    public static void onNearFieldPrioritySync() {
        nearFieldPrioritySyncCount++;
    }

    public static void onNearFieldPriorityAsyncFallback() {
        nearFieldPriorityAsyncFallbackCount++;
    }

    public static void onPredictivePrioritySync() {
        predictivePrioritySyncCount++;
    }

    public static void onPredictivePriorityAsyncFallback() {
        predictivePriorityAsyncFallbackCount++;
    }

    public static void onPredictiveFarAsyncObserved() {
        predictiveFarAsyncObservedCount++;
    }

    public static void beginUploadPass() {
        long now =
                System.nanoTime();

        if (uploadSpikeCooldownPassesRemaining > 0) {
            uploadSpikeCooldownPassesRemaining--;

            if (uploadSpikeCooldownPassesRemaining == 0) {
                uploadSpikeEffectiveMaxUploads =
                        Integer.MAX_VALUE;

                uploadSpikeEffectiveBudgetNanos =
                        Long.MAX_VALUE;
            }
        }

        /*
         * Patch 060: finalize only actual upload Runnable execution time.
         *
         * Patch 059 removed triangular poll accumulation but finalized the
         * interval between consecutive upload passes, which accidentally
         * included almost the whole frame.
         */
        if (uploadPassCount > 0) {
            long completedRunnableNanos =
                    Math.max(
                            0L,
                            uploadPassRunnableElapsedNanos
                    );

            uploadPassElapsedNanosLast =
                    completedRunnableNanos;

            uploadElapsedNanosTotal +=
                    completedRunnableNanos;

            uploadElapsedNanosPeak =
                    Math.max(
                            uploadElapsedNanosPeak,
                            completedRunnableNanos
                    );

            uploadPassCompletedCount++;

            uploadPassPeakRunnableCount =
                    Math.max(
                            uploadPassPeakRunnableCount,
                            uploadsUsedThisPass
                    );

            if (uploadsUsedThisPass < 12) {
                uploadPassesBelowOldFixedTwelveCount++;
            } else if (uploadsUsedThisPass > 12) {
                uploadPassesAboveOldFixedTwelveCount++;
            }
        }

        uploadsUsedThisPass = 0;
        uploadPassRunnableElapsedNanos = 0L;
        uploadPassStartedNanos = now;
        uploadPassCount++;
    }

    /**
     * Replacement for the single Queue.poll() in
     * SectionRenderDispatcher.uploadAllPendingUploads().
     *
     * <p>Returning null ends vanilla's existing drain loop without removing
     * remaining queue entries. They are available on the next render pass.</p>
     */
    public static Object pollUpload(
            Queue<?> queue
    ) {
        uploadPollCount++;

        if (queue == null) {
            return null;
        }

        long now =
                System.nanoTime();

        long elapsedNanos =
                Math.max(
                        0L,
                        now - uploadPassStartedNanos
                );

        int observedQueueSize =
                Math.max(
                        0,
                        queue.size()
                );

        lastObservedUploadQueueSize =
                observedQueueSize;

        updateStreamingPressure(
                observedQueueSize
        );

        boolean countBudgetExhausted =
                uploadsUsedThisPass
                        >= adaptiveMaxUploadsPerPass();

        boolean timeBudgetExhausted =
                uploadsUsedThisPass
                        >= minUploadsPerPass
                        && elapsedNanos
                        >= adaptiveUploadTimeBudgetNanos();

        if (countBudgetExhausted
                || timeBudgetExhausted) {

            int remaining =
                    observedQueueSize;

            if (remaining > 0) {
                uploadBudgetHitCount++;

                if (countBudgetExhausted) {
                    uploadCountBudgetHitCount++;
                }

                if (timeBudgetExhausted) {
                    uploadTimeBudgetHitCount++;
                }

                lastDeferredUploadQueueSize =
                        remaining;

                peakDeferredUploadQueueSize =
                        Math.max(
                                peakDeferredUploadQueueSize,
                                remaining
                        );

                uploadRunnableDeferredEstimate +=
                        remaining;
            }

            return null;
        }

        Object runnable =
                queue.poll();

        if (runnable != null) {
            uploadsUsedThisPass++;
            uploadRunnableExecutedCount++;

            int remainingAfterPoll =
                    Math.max(
                            0,
                            queue.size()
                    );

            lastObservedUploadQueueSize =
                    remainingAfterPoll;

            updateStreamingPressure(
                    remainingAfterPoll
            );

            if (runnable instanceof Runnable uploadRunnable) {
                return new TimedUploadRunnable(
                        uploadRunnable
                );
            }
        } else {
            lastObservedUploadQueueSize = 0;
            lastDeferredUploadQueueSize = 0;

            updateStreamingPressure(
                    0
            );
        }

        return runnable;
    }

    private static void recordUploadRunnableElapsed(
            long elapsedNanos
    ) {
        long bounded =
                Math.max(
                        0L,
                        elapsedNanos
                );

        uploadPassRunnableElapsedNanos +=
                bounded;

        uploadRunnableElapsedNanosPeak =
                Math.max(
                        uploadRunnableElapsedNanosPeak,
                        bounded
                );

        if (bounded
                >= UPLOAD_SPIKE_THRESHOLD_NANOS) {
            uploadSpikeCount++;

            boolean severe =
                    bounded
                            >= UPLOAD_SEVERE_SPIKE_THRESHOLD_NANOS;

            uploadSpikeCooldownPassesRemaining =
                    Math.max(
                            uploadSpikeCooldownPassesRemaining,
                            severe
                                    ? UPLOAD_SEVERE_SPIKE_COOLDOWN_PASSES
                                    : UPLOAD_SPIKE_COOLDOWN_PASSES
                    );

            uploadSpikeEffectiveMaxUploads =
                    Math.min(
                            uploadSpikeEffectiveMaxUploads,
                            severe
                                    ? 2
                                    : 4
                    );

            uploadSpikeEffectiveBudgetNanos =
                    Math.min(
                            uploadSpikeEffectiveBudgetNanos,
                            severe
                                    ? 500_000L
                                    : 750_000L
                    );
        }

        uploadRunnableTimingSampleCount++;
    }

    private static final class TimedUploadRunnable
            implements Runnable {

        private final Runnable delegate;

        private TimedUploadRunnable(
                Runnable delegate
        ) {
            this.delegate =
                    delegate;
        }

        @Override
        public void run() {
            long started =
                    System.nanoTime();

            try {
                delegate.run();
            } finally {
                recordUploadRunnableElapsed(
                        System.nanoTime()
                                - started
                );
            }
        }
    }

    public static int currentUploadQueueSize() {
        return Math.max(
                0,
                lastObservedUploadQueueSize
        );
    }

    /**
     * 0 = clear, 1 = mild catch-up, 2 = strong catch-up, 3 = emergency.
     */
    public static int streamingPressureLevel() {
        return streamingPressureLevel;
    }

    public static boolean shouldYieldLodBuild(
            int sourceQuads
    ) {
        int pressure =
                streamingPressureLevel;

        if (pressure >= 3) {
            return true;
        }

        if (pressure >= 2) {
            return sourceQuads < 768;
        }

        if (pressure >= 1) {
            return sourceQuads < 320;
        }

        return false;
    }

    public static boolean shouldYieldLodInstall() {
        return streamingPressureLevel >= 2;
    }

    private static void updateStreamingPressure(
            int queueSize
    ) {
        int desired;

        if (queueSize >= 64) {
            desired = 3;
        } else if (queueSize >= 32) {
            desired = 2;
        } else if (queueSize >= 12) {
            desired = 1;
        } else {
            desired = 0;
        }

        if (desired != streamingPressureLevel) {
            streamingPressureLevel =
                    desired;

            streamingPressureLevelChangeCount++;
        }
    }

    public static boolean verified() {
        return compilePassCount > 0
                && uploadPassCount > 0;
    }

    public static void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "chunkWorkBudgetInstalled",
                true
        );
        report.addProperty(
                "chunkWorkBudgetMode",
                "FRAME_BUDGETED_VISIBLE_SECTION_COMPILE_AND_UPLOAD"
        );

        report.addProperty(
                "chunkWorkBudgetMaxSyncRebuildsPerPass",
                maxSyncRebuildsPerPass
        );
        int effectiveHardMaxUploads =
                adaptiveConfiguredHardMaxUploads();
        long effectiveBudgetCeilingNanos =
                adaptiveConfiguredBudgetCeilingNanos();

        report.addProperty(
                "chunkWorkBudgetMinUploadsPerPass",
                Math.min(
                        minUploadsPerPass,
                        effectiveHardMaxUploads
                )
        );
        report.addProperty(
                "chunkWorkBudgetMaxUploadsPerPass",
                effectiveHardMaxUploads
        );
        report.addProperty(
                "chunkWorkBudgetUploadTimeBudgetNanos",
                effectiveBudgetCeilingNanos
        );
        report.addProperty(
                "chunkWorkBudgetLegacyConfiguredMaxUploadsPerPass",
                maxUploadsPerPass
        );
        report.addProperty(
                "chunkWorkBudgetLegacyConfiguredTimeBudgetNanos",
                uploadTimeBudgetNanos
        );
        report.addProperty(
                "chunkWorkBudgetUploadTimeBudgetMillis",
                adaptiveUploadTimeBudgetNanos()
                        / 1_000_000.0
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveUploadBudget",
                true
        );
        report.addProperty(
                "chunkWorkBudgetAdaptivePolicy",
                "MIN_PROGRESS_THEN_TIME_OR_COUNT_CAP"
        );
        report.addProperty(
                "chunkWorkBudgetProductionPolicy",
                "ACTIVE_BACKEND_AWARE_UPLOAD_PRESSURE_BOUNDED_CATCHUP"
        );
        report.addProperty(
                "chunkWorkBudgetActiveBackendAwarePressureGovernor",
                true
        );
        report.addProperty(
                "chunkWorkBudgetActiveBackendHardwareClass",
                adaptiveActiveHardwareClassName()
        );
        report.addProperty(
                "chunkWorkBudgetActiveOpenGlLowEndIntel",
                PotatoRenderEngine.activeOpenGlLowEndIntel()
        );
        report.addProperty(
                "chunkWorkBudgetEffectiveHardMaxUploads",
                effectiveHardMaxUploads
        );
        report.addProperty(
                "chunkWorkBudgetEffectiveBudgetCeilingNanos",
                effectiveBudgetCeilingNanos
        );
        report.addProperty(
                "chunkWorkBudgetSpeedAwareUploadBoostAllowed",
                adaptiveSpeedAwareUploadBoostAllowed()
        );
        report.addProperty(
                "chunkWorkBudgetPolicyChangedByPatch052",
                true
        );
        report.addProperty(
                "chunkWorkBudgetPolicyChangedByPatch053",
                true
        );
        report.addProperty(
                "chunkWorkBudgetPolicyChangedByPatch056",
                true
        );
        report.addProperty(
                "chunkWorkBudgetPolicyChangedByPatch059",
                true
        );
        report.addProperty(
                "chunkWorkBudgetPolicyChangedByPatch060",
                true
        );
        report.addProperty(
                "chunkWorkBudgetFrameFirstPolicy",
                true
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorEnabled",
                true
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorPerUploadPass",
                true
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorRetuneIntervalPasses",
                ADAPTIVE_RETUNE_INTERVAL_PASSES
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorCurrentMaxUploads",
                Math.min(
                        adaptiveCurrentMaxUploads,
                        effectiveHardMaxUploads
                )
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorRawCurrentMaxUploads",
                adaptiveCurrentMaxUploads
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorCurrentBudgetNanos",
                Math.min(
                        adaptiveCurrentBudgetNanos,
                        effectiveBudgetCeilingNanos
                )
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorCurrentBudgetMillis",
                Math.min(
                        adaptiveCurrentBudgetNanos,
                        effectiveBudgetCeilingNanos
                ) / 1_000_000.0
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorLastWindowAverageUploadNanos",
                adaptiveLastWindowAverageUploadNanos
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorLastWindowCompletedPasses",
                adaptiveLastWindowCompletedPasses
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorLastWindowExecutedUploads",
                adaptiveLastWindowExecutedUploads
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorLastQueuePressure",
                adaptiveLastQueuePressure
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorRetuneCount",
                adaptiveRetuneCount
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorUpshiftCount",
                adaptiveUpshiftCount
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorDownshiftCount",
                adaptiveDownshiftCount
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorStableCount",
                adaptiveStableCount
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorHardwareProcessors",
                Runtime.getRuntime().availableProcessors()
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorDefaultHardMaxUploads",
                ADAPTIVE_DEFAULT_HARD_MAX_UPLOADS
        );
        report.addProperty(
                "chunkWorkBudgetUploadSpikeGuardEnabled",
                true
        );
        report.addProperty(
                "chunkWorkBudgetUploadSpikeThresholdMillis",
                UPLOAD_SPIKE_THRESHOLD_NANOS
                        / 1_000_000.0
        );
        report.addProperty(
                "chunkWorkBudgetUploadSevereSpikeThresholdMillis",
                UPLOAD_SEVERE_SPIKE_THRESHOLD_NANOS
                        / 1_000_000.0
        );
        report.addProperty(
                "chunkWorkBudgetUploadSpikeCount",
                uploadSpikeCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadSpikeCooldownPassesRemaining",
                uploadSpikeCooldownPassesRemaining
        );
        report.addProperty(
                "chunkWorkBudgetUploadSpikeGuardActive",
                uploadSpikeCooldownPassesRemaining > 0
        );
        report.addProperty(
                "chunkWorkBudgetUploadSpikeEffectiveMaxUploads",
                Math.min(
                        effectiveHardMaxUploads,
                        uploadSpikeCooldownPassesRemaining > 0
                                ? uploadSpikeEffectiveMaxUploads
                                : adaptiveCurrentMaxUploads
                )
        );
        report.addProperty(
                "chunkWorkBudgetUploadSpikeEffectiveBudgetMillis",
                Math.min(
                        effectiveBudgetCeilingNanos,
                        uploadSpikeCooldownPassesRemaining > 0
                                ? Math.min(
                                        adaptiveCurrentBudgetNanos,
                                        uploadSpikeEffectiveBudgetNanos
                                )
                                : adaptiveCurrentBudgetNanos
                ) / 1_000_000.0
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorPreservesRenderDistanceIntent",
                true
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorMutatesRenderDistance",
                false
        );
        report.addProperty(
                "chunkWorkBudgetAdaptiveRuntimeGovernorWindowAuthority",
                "ACTIVE_RENDER_BACKEND"
        );
        report.addProperty(
                "chunkWorkBudgetChunkCatchupMayLagUnderSustainedPressure",
                true
        );
        report.addProperty(
                "chunkWorkBudgetUploadElapsedAccounting",
                "SUM_OF_ACTUAL_UPLOAD_RUNNABLE_EXECUTION_NANOS_PER_PASS"
        );
        report.addProperty(
                "chunkWorkBudgetUploadElapsedTriangularOvercountFixedByPatch059",
                true
        );
        report.addProperty(
                "chunkWorkBudgetFrameIntervalMiscountFixedByPatch060",
                true
        );
        report.addProperty(
                "chunkWorkBudgetUploadCostExcludesInterPassFrameTime",
                true
        );
        report.addProperty(
                "chunkWorkBudgetLastCompletedUploadPassNanos",
                uploadPassElapsedNanosLast
        );
        report.addProperty(
                "chunkWorkBudgetCurrentPassActualUploadNanos",
                uploadPassRunnableElapsedNanos
        );
        report.addProperty(
                "chunkWorkBudgetUploadRunnableTimingSampleCount",
                uploadRunnableTimingSampleCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadRunnableElapsedNanosPeak",
                uploadRunnableElapsedNanosPeak
        );
        report.addProperty(
                "chunkWorkBudgetLastObservedUploadQueueSize",
                lastObservedUploadQueueSize
        );
        report.addProperty(
                "chunkWorkBudgetStreamingPressureLevel",
                streamingPressureLevel
        );
        report.addProperty(
                "chunkWorkBudgetStreamingPressureLevelChangeCount",
                streamingPressureLevelChangeCount
        );
        report.addProperty(
                "chunkWorkBudgetLodYieldsToStreaming",
                true
        );
        report.addProperty(
                "chunkWorkBudgetCountCapRole",
                "SAFETY_CEILING_TIME_BUDGET_PRIMARY"
        );

        PotatoChunkCompileController.enrich(
                report
        );
        report.addProperty(
                "chunkWorkBudgetMinUploadsProperty",
                "potato.chunk.minUploadsPerPass"
        );
        report.addProperty(
                "chunkWorkBudgetMaxUploadsProperty",
                "potato.chunk.maxUploadsPerPass"
        );
        report.addProperty(
                "chunkWorkBudgetTimeBudgetProperty",
                "potato.chunk.uploadTimeBudgetNanos"
        );

        report.addProperty(
                "chunkWorkBudgetCompilePassCount",
                compilePassCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadPassCount",
                uploadPassCount
        );

        report.addProperty(
                "chunkWorkBudgetSyncRebuildRequestedCount",
                syncRebuildRequestedCount
        );
        report.addProperty(
                "chunkWorkBudgetSyncRebuildExecutedCount",
                syncRebuildExecutedCount
        );
        report.addProperty(
                "chunkWorkBudgetSyncRebuildRedirectedAsyncCount",
                syncRebuildRedirectedAsyncCount
        );
        report.addProperty(
                "chunkWorkBudgetNearFieldPriorityInstalled",
                true
        );
        report.addProperty(
                "chunkWorkBudgetNearFieldPriorityMode",
                "PLAYER_3X3_RENDER_MESH_SAFETY_RING"
        );
        report.addProperty(
                "chunkWorkBudgetNearFieldPriorityRadiusChunks",
                NEAR_FIELD_PRIORITY_RADIUS_CHUNKS
        );
        report.addProperty(
                "chunkWorkBudgetNearFieldPriorityMaximumSyncPerPass",
                NEAR_FIELD_PRIORITY_MAX_SYNC_PER_PASS
        );
        report.addProperty(
                "chunkWorkBudgetNearFieldPrioritySyncCount",
                nearFieldPrioritySyncCount
        );
        report.addProperty(
                "chunkWorkBudgetNearFieldPriorityAsyncFallbackCount",
                nearFieldPriorityAsyncFallbackCount
        );
        report.addProperty(
                "chunkWorkBudgetNearFieldPriorityMutatesServerChunkTickets",
                false
        );
        report.addProperty(
                "chunkWorkBudgetPredictivePriorityInstalled",
                true
        );
        report.addProperty(
                "chunkWorkBudgetPredictivePrioritySyncCount",
                predictivePrioritySyncCount
        );
        report.addProperty(
                "chunkWorkBudgetPredictivePriorityAsyncFallbackCount",
                predictivePriorityAsyncFallbackCount
        );
        report.addProperty(
                "chunkWorkBudgetPredictiveFarAsyncObservedCount",
                predictiveFarAsyncObservedCount
        );
        report.addProperty(
                "chunkWorkBudgetSpeedAwareUploadDeadlineRecovery",
                true
        );
        report.addProperty(
                "chunkWorkBudgetSpeedAwareUploadBoostPassCount",
                speedAwareUploadBoostPassCount
        );
        report.addProperty(
                "chunkWorkBudgetSpeedAwareUploadBoostAverageMillis",
                speedAwareUploadBoostPassCount == 0L
                        ? 0.0
                        : speedAwareUploadBoostNanosTotal
                        / 1_000_000.0
                        / speedAwareUploadBoostPassCount
        );
        report.addProperty(
                "chunkWorkBudgetSpeedAwareUploadBoostPeakMillis",
                speedAwareUploadBoostNanosPeak / 1_000_000.0
        );
        report.addProperty(
                "chunkWorkBudgetSpeedAwareUploadHardBudgetMillis",
                ADAPTIVE_MAX_BUDGET_NANOS / 1_000_000.0
        );

        PotatoPredictiveStreamingRuntime.enrich(
                report
        );

        report.addProperty(
                "chunkWorkBudgetUploadPollCount",
                uploadPollCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadRunnableExecutedCount",
                uploadRunnableExecutedCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadBudgetHitCount",
                uploadBudgetHitCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadTimeBudgetHitCount",
                uploadTimeBudgetHitCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadCountBudgetHitCount",
                uploadCountBudgetHitCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadRunnableDeferredEstimate",
                uploadRunnableDeferredEstimate
        );
        report.addProperty(
                "chunkWorkBudgetUploadElapsedNanosTotal",
                uploadElapsedNanosTotal
        );
        report.addProperty(
                "chunkWorkBudgetUploadElapsedNanosPeak",
                uploadElapsedNanosPeak
        );
        report.addProperty(
                "chunkWorkBudgetUploadPassCompletedCount",
                uploadPassCompletedCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadPassPeakRunnableCount",
                uploadPassPeakRunnableCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadPassesBelowOldFixedTwelveCount",
                uploadPassesBelowOldFixedTwelveCount
        );
        report.addProperty(
                "chunkWorkBudgetUploadPassesAboveOldFixedTwelveCount",
                uploadPassesAboveOldFixedTwelveCount
        );
        report.addProperty(
                "chunkWorkBudgetLastDeferredUploadQueueSize",
                lastDeferredUploadQueueSize
        );
        report.addProperty(
                "chunkWorkBudgetPeakDeferredUploadQueueSize",
                peakDeferredUploadQueueSize
        );

        report.addProperty(
                "chunkWorkBudgetUsesVanillaAsyncDispatcher",
                true
        );
        report.addProperty(
                "chunkWorkBudgetUsesVanillaPriorityQueues",
                true
        );
        report.addProperty(
                "chunkWorkBudgetDropsMeshWork",
                false
        );
        report.addProperty(
                "chunkWorkBudgetClearsDirtyWithoutScheduling",
                false
        );
        report.addProperty(
                "chunkWorkBudgetMutatesChunkLoading",
                false
        );
        report.addProperty(
                "chunkWorkBudgetMutatesWorldTicking",
                false
        );
        report.addProperty(
                "chunkWorkBudgetMutatesEntitySimulation",
                false
        );
        report.addProperty(
                "chunkWorkBudgetMutatesSaving",
                false
        );

        report.addProperty(
                "chunkWorkBudgetOffscreenCompileDeferralAdded",
                false
        );
        report.addProperty(
                "chunkWorkBudgetOffscreenCompileDeferralReason",
                "VANILLA_COMPILE_SEAM_ALREADY_ITERATES_VISIBLE_SECTIONS_ONLY"
        );

        report.addProperty(
                "chunkWorkBudgetVerified",
                verified()
        );
    }

    private static long longProperty(
            String key,
            long defaultValue,
            long minimum,
            long maximum
    ) {
        String raw =
                System.getProperty(
                        key
                );

        if (raw == null
                || raw.isBlank()) {
            return defaultValue;
        }

        try {
            long parsed =
                    Long.parseLong(
                            raw.trim()
                    );

            return Math.max(
                    minimum,
                    Math.min(
                            maximum,
                            parsed
                    )
            );
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
    private static int integerProperty(
            String key,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        String raw =
                System.getProperty(
                        key
                );

        if (raw == null
                || raw.isBlank()) {
            return defaultValue;
        }

        try {
            int parsed =
                    Integer.parseInt(
                            raw.trim()
                    );

            return Math.max(
                    minimum,
                    Math.min(
                            maximum,
                            parsed
                    )
            );
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    /*
     * Patch 056: window-safe, genuinely per-upload-pass adaptive governor.
     *
     * OpenGL owns the only live gameplay window while this policy runs.
     * The governor changes admission pressure only; it never changes the
     * user's render distance, world simulation or save behavior.
     */
    private static final long ADAPTIVE_RETUNE_INTERVAL_PASSES = 60L;
    private static final long ADAPTIVE_MIN_BUDGET_NANOS = 500_000L;
    private static final long ADAPTIVE_MAX_BUDGET_NANOS = 4_000_000L;
    private static final long ADAPTIVE_BUDGET_STEP_NANOS = 125_000L;
    private static final long ADAPTIVE_COST_FLOOR_NANOS = 50_000L;
    private static final int ADAPTIVE_DEFAULT_HARD_MAX_UPLOADS = 64;

    /*
     * Averages hide the exact hitch we care about: one 10-25 ms upload Runnable
     * can blow an otherwise excellent frame while thousands of 0.05 ms uploads
     * make the window average look harmless. The spike guard temporarily
     * reduces subsequent render-thread upload pressure after such an event.
     */
    private static final long UPLOAD_SPIKE_THRESHOLD_NANOS =
            4_000_000L;
    private static final long UPLOAD_SEVERE_SPIKE_THRESHOLD_NANOS =
            8_000_000L;
    private static final int UPLOAD_SPIKE_COOLDOWN_PASSES =
            30;
    private static final int UPLOAD_SEVERE_SPIKE_COOLDOWN_PASSES =
            60;

    private static volatile int adaptiveCurrentMaxUploads = 8;
    private static volatile long adaptiveCurrentBudgetNanos = 1_000_000L;
    private static volatile long adaptiveNextRetuneCompletedPass = 120L;

    private static long adaptiveLastCompletedPasses;
    private static long adaptiveLastElapsedNanos;
    private static long adaptiveLastExecutedUploads;
    private static long adaptiveLastTimeBudgetHits;
    private static long adaptiveLastCountBudgetHits;

    private static long adaptiveLastWindowAverageUploadNanos;
    private static long adaptiveLastWindowCompletedPasses;
    private static long adaptiveLastWindowExecutedUploads;
    private static long adaptiveLastQueuePressure;

    private static int adaptiveRetuneCount;
    private static int adaptiveUpshiftCount;
    private static int adaptiveDownshiftCount;
    private static int adaptiveStableCount;

    private static int adaptiveMaxUploadsPerPass() {
        adaptiveGovernorRetuneIfDue();

        int hardCap =
                adaptiveConfiguredHardMaxUploads();

        int configuredMinimum =
                Math.min(
                        hardCap,
                        integerProperty(
                                "potato.chunk.minUploadsPerPass",
                                1,
                                1,
                                MAX_CONFIGURED_UPLOADS
                        )
                );

        int adaptive =
                Math.max(
                        configuredMinimum,
                        Math.min(
                                hardCap,
                                adaptiveCurrentMaxUploads
                        )
                );

        if (uploadSpikeCooldownPassesRemaining <= 0) {
            return adaptive;
        }

        return Math.max(
                configuredMinimum,
                Math.min(
                        adaptive,
                        uploadSpikeEffectiveMaxUploads
                )
        );
    }

    private static long adaptiveUploadTimeBudgetNanos() {
        adaptiveGovernorRetuneIfDue();

        long configuredCeiling =
                adaptiveConfiguredBudgetCeilingNanos();

        long baseBudget =
                Math.min(
                        configuredCeiling,
                        adaptiveCurrentBudgetNanos
                );

        if (uploadSpikeCooldownPassesRemaining > 0) {
            return Math.min(
                    baseBudget,
                    uploadSpikeEffectiveBudgetNanos
            );
        }

        if (!adaptiveSpeedAwareUploadBoostAllowed()) {
            return baseBudget;
        }

        long boost =
                PotatoPredictiveStreamingRuntime
                        .uploadBudgetBoostNanos();

        if (boost <= 0L
                || lastObservedUploadQueueSize <= 0) {
            return baseBudget;
        }

        long boosted =
                Math.min(
                        configuredCeiling,
                        baseBudget + boost
                );

        long applied =
                Math.max(
                        0L,
                        boosted - baseBudget
                );

        if (applied > 0L
                && speedAwareUploadBoostLastRecordedPass
                != uploadPassCount) {
            speedAwareUploadBoostLastRecordedPass =
                    uploadPassCount;
            speedAwareUploadBoostPassCount++;
            speedAwareUploadBoostNanosTotal += applied;
            speedAwareUploadBoostNanosPeak =
                    Math.max(
                            speedAwareUploadBoostNanosPeak,
                            applied
                    );
        }

        return boosted;
    }

    /*
     * POTATO_PATCH_158_ACTIVE_BACKEND_UPLOAD_PRESSURE
     *
     * CPU count alone is not a safe upload-admission proxy. A 12-thread CPU can
     * feed completed meshes much faster than an integrated OpenGL renderer can
     * allocate/publish them. The visible backend therefore supplies a safety
     * ceiling while the existing cost/queue feedback still chooses below it.
     */
    private static int adaptiveConfiguredHardMaxUploads() {
        int processors =
                Math.max(
                        1,
                        Runtime.getRuntime()
                                .availableProcessors()
                );

        int processorCap =
                Math.max(
                        8,
                        Math.min(
                                ADAPTIVE_DEFAULT_HARD_MAX_UPLOADS,
                                processors * 2
                        )
                );

        int hardwareSafetyCap =
                processorCap;

        if (PotatoRenderEngine.verified()) {
            PotatoRenderEngineDecision current =
                    PotatoRenderEngine.decision();

            if (current.activeBackend()
                    == PotatoRenderBackendId.OPENGL_COMPATIBILITY) {
                if (PotatoRenderEngine.activeOpenGlLowEndIntel()) {
                    hardwareSafetyCap =
                            Math.min(
                                    hardwareSafetyCap,
                                    4
                            );
                } else {
                    hardwareSafetyCap =
                            Math.min(
                                    hardwareSafetyCap,
                                    switch (current.hardwareClass()) {
                                        case POTATO -> 2;
                                        case LOW -> 4;
                                        case BALANCED -> 8;
                                        case HIGH -> processorCap;
                                    }
                            );
                }
            }
        }

        int configured =
                integerProperty(
                        "potato.chunk.maxUploadsPerPass",
                        hardwareSafetyCap,
                        1,
                        MAX_CONFIGURED_UPLOADS
                );

        return Math.max(
                1,
                Math.min(
                        hardwareSafetyCap,
                        configured
                )
        );
    }

    private static long adaptiveConfiguredBudgetCeilingNanos() {
        long configured =
                Math.max(
                        ADAPTIVE_MIN_BUDGET_NANOS,
                        Math.min(
                                ADAPTIVE_MAX_BUDGET_NANOS,
                                Long.getLong(
                                        "potato.chunk.uploadTimeBudgetNanos",
                                        2_000_000L
                                )
                        )
                );

        if (!PotatoRenderEngine.verified()) {
            return configured;
        }

        PotatoRenderEngineDecision current =
                PotatoRenderEngine.decision();

        if (current.activeBackend()
                != PotatoRenderBackendId.OPENGL_COMPATIBILITY) {
            return configured;
        }

        long hardwareSafetyCeiling =
                configured;

        if (PotatoRenderEngine.activeOpenGlLowEndIntel()) {
            hardwareSafetyCeiling =
                    Math.min(
                            hardwareSafetyCeiling,
                            1_000_000L
                    );
        } else {
            hardwareSafetyCeiling =
                    Math.min(
                            hardwareSafetyCeiling,
                            switch (current.hardwareClass()) {
                                case POTATO -> 750_000L;
                                case LOW -> 1_000_000L;
                                case BALANCED -> 1_500_000L;
                                case HIGH -> configured;
                            }
                    );
        }

        return Math.max(
                ADAPTIVE_MIN_BUDGET_NANOS,
                hardwareSafetyCeiling
        );
    }

    private static boolean adaptiveSpeedAwareUploadBoostAllowed() {
        if (!PotatoRenderEngine.verified()) {
            return true;
        }

        PotatoRenderEngineDecision current =
                PotatoRenderEngine.decision();

        if (current.activeBackend()
                != PotatoRenderBackendId.OPENGL_COMPATIBILITY) {
            return true;
        }

        if (PotatoRenderEngine.activeOpenGlLowEndIntel()) {
            return false;
        }

        return current.hardwareClass()
                != PotatoHardwareClass.POTATO
                && current.hardwareClass()
                != PotatoHardwareClass.LOW;
    }

    private static String adaptiveActiveHardwareClassName() {
        if (!PotatoRenderEngine.verified()) {
            return "NOT_EVALUATED";
        }

        PotatoRenderEngineDecision current =
                PotatoRenderEngine.decision();

        return current.hardwareClass().name();
    }

    private static long adaptiveNumericValue(
            long value
    ) {
        return value;
    }

    private static long adaptiveNumericValue(
            int value
    ) {
        return value;
    }

    private static long adaptiveNumericValue(
            Number value
    ) {
        return value != null
                ? value.longValue()
                : 0L;
    }

    private static void adaptiveGovernorRetuneIfDue() {
        long completedPasses =
                adaptiveNumericValue(
                        uploadPassCompletedCount
                );

        if (completedPasses
                < adaptiveNextRetuneCompletedPass) {
            return;
        }

        synchronized (ChunkWorkBudgetPolicy.class) {
            completedPasses =
                    adaptiveNumericValue(
                            uploadPassCompletedCount
                    );

            if (completedPasses
                    < adaptiveNextRetuneCompletedPass) {
                return;
            }

            adaptiveGovernorRetune(
                    completedPasses
            );

            adaptiveNextRetuneCompletedPass =
                    completedPasses
                            + ADAPTIVE_RETUNE_INTERVAL_PASSES;
        }
    }

    private static void adaptiveGovernorRetune(
            long completedPasses
    ) {
        long elapsedNanos =
                adaptiveNumericValue(
                        uploadElapsedNanosTotal
                );

        long executedUploads =
                adaptiveNumericValue(
                        uploadRunnableExecutedCount
                );

        long timeBudgetHits =
                adaptiveNumericValue(
                        uploadTimeBudgetHitCount
                );

        long countBudgetHits =
                adaptiveNumericValue(
                        uploadCountBudgetHitCount
                );

        long windowPasses =
                Math.max(
                        1L,
                        completedPasses
                                - adaptiveLastCompletedPasses
                );

        long windowElapsedNanos =
                Math.max(
                        0L,
                        elapsedNanos
                                - adaptiveLastElapsedNanos
                );

        long windowExecutedUploads =
                Math.max(
                        0L,
                        executedUploads
                                - adaptiveLastExecutedUploads
                );

        long windowTimeBudgetHits =
                Math.max(
                        0L,
                        timeBudgetHits
                                - adaptiveLastTimeBudgetHits
                );

        long windowCountBudgetHits =
                Math.max(
                        0L,
                        countBudgetHits
                                - adaptiveLastCountBudgetHits
                );

        long averageUploadNanos =
                windowExecutedUploads > 0L
                        ? windowElapsedNanos
                                / windowExecutedUploads
                        : ADAPTIVE_COST_FLOOR_NANOS;

        averageUploadNanos =
                Math.max(
                        ADAPTIVE_COST_FLOOR_NANOS,
                        averageUploadNanos
                );

        long queuePressure =
                Math.max(
                        0L,
                        adaptiveNumericValue(
                                lastObservedUploadQueueSize
                        )
                );

        int processors =
                Math.max(
                        1,
                        Runtime.getRuntime()
                                .availableProcessors()
                );

        int configuredHardCap =
                adaptiveConfiguredHardMaxUploads();

        long configuredBudgetCeiling =
                adaptiveConfiguredBudgetCeilingNanos();

        long desiredBudgetNanos;

        if (processors >= 16) {
            desiredBudgetNanos = 1_750_000L;
        } else if (processors >= 8) {
            desiredBudgetNanos = 1_500_000L;
        } else if (processors >= 4) {
            desiredBudgetNanos = 1_125_000L;
        } else {
            desiredBudgetNanos = 750_000L;
        }

        boolean countBound =
                windowCountBudgetHits * 100L
                        >= windowPasses * 70L;

        boolean timeBound =
                windowTimeBudgetHits * 100L
                        >= windowPasses * 10L;

        if (averageUploadNanos >= 1_500_000L) {
            desiredBudgetNanos = 500_000L;
        } else if (averageUploadNanos >= 750_000L) {
            desiredBudgetNanos =
                    Math.min(
                            desiredBudgetNanos,
                            750_000L
                    );
        } else if (averageUploadNanos >= 350_000L) {
            desiredBudgetNanos =
                    Math.min(
                            desiredBudgetNanos,
                            1_125_000L
                    );
        }

        if (queuePressure >= 24L
                && averageUploadNanos < 350_000L
                && !timeBound) {
            desiredBudgetNanos += 250_000L;
        }

        if (queuePressure >= 48L
                && averageUploadNanos < 250_000L
                && !timeBound) {
            desiredBudgetNanos += 250_000L;
        }

        if (queuePressure >= 64L
                && averageUploadNanos < 175_000L
                && !timeBound) {
            desiredBudgetNanos += 250_000L;
        }

        if (timeBound) {
            desiredBudgetNanos -= 250_000L;
        }

        desiredBudgetNanos =
                Math.max(
                        ADAPTIVE_MIN_BUDGET_NANOS,
                        Math.min(
                                configuredBudgetCeiling,
                                desiredBudgetNanos
                        )
                );

        int costBasedCap =
                (int) Math.max(
                        1L,
                        desiredBudgetNanos
                                / averageUploadNanos
                );

        int desiredMaxUploads =
                Math.max(
                        1,
                        Math.min(
                                configuredHardCap,
                                costBasedCap
                        )
                );

        if (countBound
                && !timeBound
                && queuePressure >= 12L
                && averageUploadNanos < 350_000L) {

            int catchupBonus =
                    queuePressure >= 64L
                            ? 12
                            : queuePressure >= 32L
                            ? 8
                            : 4;

            desiredMaxUploads =
                    Math.min(
                            configuredHardCap,
                            desiredMaxUploads
                                    + catchupBonus
                    );
        }

        if (timeBound
                || averageUploadNanos >= 750_000L) {
            desiredMaxUploads =
                    Math.max(
                            1,
                            desiredMaxUploads - 1
                    );
        }

        int previousMaxUploads =
                adaptiveCurrentMaxUploads;

        long previousBudgetNanos =
                adaptiveCurrentBudgetNanos;

        if (desiredMaxUploads
                > adaptiveCurrentMaxUploads) {

            int upStep =
                    queuePressure >= 64L
                            ? 6
                            : queuePressure >= 32L
                            ? 4
                            : queuePressure >= 12L
                            ? 2
                            : 1;

            adaptiveCurrentMaxUploads =
                    Math.min(
                            desiredMaxUploads,
                            adaptiveCurrentMaxUploads
                                    + upStep
                    );
        } else if (desiredMaxUploads
                < adaptiveCurrentMaxUploads) {

            adaptiveCurrentMaxUploads--;
        }

        adaptiveCurrentMaxUploads =
                Math.max(
                        1,
                        Math.min(
                                configuredHardCap,
                                adaptiveCurrentMaxUploads
                        )
                );

        if (desiredBudgetNanos
                > adaptiveCurrentBudgetNanos) {
            adaptiveCurrentBudgetNanos =
                    Math.min(
                            desiredBudgetNanos,
                            adaptiveCurrentBudgetNanos
                                    + ADAPTIVE_BUDGET_STEP_NANOS
                    );
        } else if (desiredBudgetNanos
                < adaptiveCurrentBudgetNanos) {
            adaptiveCurrentBudgetNanos =
                    Math.max(
                            desiredBudgetNanos,
                            adaptiveCurrentBudgetNanos
                                    - ADAPTIVE_BUDGET_STEP_NANOS
                    );
        }

        adaptiveCurrentBudgetNanos =
                Math.max(
                        ADAPTIVE_MIN_BUDGET_NANOS,
                        Math.min(
                                configuredBudgetCeiling,
                                adaptiveCurrentBudgetNanos
                        )
                );

        if (adaptiveCurrentMaxUploads
                        > previousMaxUploads
                || adaptiveCurrentBudgetNanos
                        > previousBudgetNanos) {
            adaptiveUpshiftCount++;
        } else if (adaptiveCurrentMaxUploads
                        < previousMaxUploads
                || adaptiveCurrentBudgetNanos
                        < previousBudgetNanos) {
            adaptiveDownshiftCount++;
        } else {
            adaptiveStableCount++;
        }

        adaptiveRetuneCount++;
        adaptiveLastWindowAverageUploadNanos =
                averageUploadNanos;
        adaptiveLastWindowCompletedPasses =
                windowPasses;
        adaptiveLastWindowExecutedUploads =
                windowExecutedUploads;
        adaptiveLastQueuePressure =
                queuePressure;

        adaptiveLastCompletedPasses =
                completedPasses;
        adaptiveLastElapsedNanos =
                elapsedNanos;
        adaptiveLastExecutedUploads =
                executedUploads;
        adaptiveLastTimeBudgetHits =
                timeBudgetHits;
        adaptiveLastCountBudgetHits =
                countBudgetHits;
    }
}
