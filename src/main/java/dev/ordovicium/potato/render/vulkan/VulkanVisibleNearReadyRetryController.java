package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;

/**
 * Self-healing scheduler for Gate-8 whole-layer ownership proof.
 *
 * <p>The safety rule is unchanged: Vulkan may own a visible SOLID layer only
 * after every visible candidate is ready for the exact mesh generation and the
 * downstream frame-token / presentation commit succeeds. This class never
 * changes that rule and never mutates readiness directly.</p>
 *
 * <p>Patch 101 changes only <em>when</em> forced full-layer re-probes happen.
 * 100a showed that the old immediate-burst scheduler was hammering a moving
 * generation window: 60 arms / 126 immediate forced scans, zero ready layers,
 * while the best normal population was 2248/2253. That is close enough to
 * justify waiting for publication pairs to settle instead of immediately
 * repeating the same expensive scan.</p>
 *
 * <p>A retry burst is therefore converted into a small deferred recovery train.
 * The first forced retry is delayed by one or two SOLID opportunities, then
 * failed forced retries back off 1, 2, 4, up to 8 opportunities. Repeated
 * unproductive bursts also increase the initial delay. Normal OpenGL rendering
 * continues during every delay. A normal full-layer scan is still allowed to
 * win naturally at any time.</p>
 */
public final class VulkanVisibleNearReadyRetryController {

    private static final int READY_PERCENT_TARGET =
            99;

    private static final int RETRY_BURST =
            2;

    private static final int MIN_MISSING_ALLOWANCE =
            8;

    private static final int MISSING_DIVISOR =
            64;

    private static final int BROAD_READY_PERCENT_TARGET =
            97;

    private static final int BROAD_RETRY_BURST =
            1;

    private static final int BROAD_MIN_MISSING_ALLOWANCE =
            32;

    private static final int BROAD_MISSING_DIVISOR =
            32;

    private static final int BROAD_REARM_NORMAL_REJECTS =
            8;

    private static final int BROAD_MIN_CANDIDATE_COUNT =
            256;

    private static final int DEEP_READY_PERCENT_TARGET =
            95;

    private static final int DEEP_RETRY_BURST =
            1;

    private static final int DEEP_MIN_MISSING_ALLOWANCE =
            64;

    private static final int DEEP_MISSING_DIVISOR =
            20;

    private static final int DEEP_REARM_NORMAL_REJECTS =
            32;

    private static final int DEEP_MIN_CANDIDATE_COUNT =
            512;

    private static final int TIGHT_INITIAL_SETTLE_LAYERS =
            1;

    private static final int BROAD_INITIAL_SETTLE_LAYERS =
            2;

    private static final int DEEP_INITIAL_SETTLE_LAYERS =
            4;

    private static final int MAX_SETTLE_LAYERS =
            64;

    private static final int MAX_UNPRODUCTIVE_BACKOFF_SHIFT =
            6;

    private static final int QUIET_BACKOFF_BASE_LAYERS =
            16;

    private static final int MAX_QUIET_BACKOFF_LAYERS =
            120;

    private static int retryBudget;
    private static int settleLayersRemaining;
    private static int intraBurstBackoffShift;
    private static int consecutiveUnproductiveBursts;
    private static int broadRearmRejectsRemaining;
    private static int deepRearmRejectsRemaining;
    private static boolean broadBandRetired;
    private static boolean deepBandRetired;
    private static boolean postValidationCommitTrainActive;
    private static int quietBackoffLayersRemaining;

    private static long armCount;
    private static long consumeCount;
    private static long forcedRejectCount;
    private static long forcedAbortCount;
    private static long forcedDownstreamRejectCount;
    private static long layerReadyCount;
    private static long layerReadyFromForcedRetryCount;
    private static long visibleCommitFromForcedRetryCount;
    private static long postValidationCommitArmCount;
    private static long postValidationCommitConsumeCount;

    private static long tightBandArmCount;
    private static long broadBandArmCount;
    private static long broadBandThrottleCount;
    private static long deepBandArmCount;
    private static long deepBandThrottleCount;

    private static long deferredArmCount;
    private static long settleSkipCount;
    private static long normalRejectWhileDeferredCount;
    private static long unproductiveBurstCount;
    private static long recoveryCount;
    private static long quietBackoffSkipCount;
    private static long quietBackoffArmSuppressCount;
    private static long quietBackoffActivationCount;

    private static int peakSettleLayers;
    private static int peakConsecutiveUnproductiveBursts;
    private static int peakQuietBackoffLayers;

    private static int lastCandidateCount;
    private static int lastReadyCount;
    private static int lastPendingCount;
    private static int lastMissingCount;
    private static int peakMissingCountAtArm;

    private VulkanVisibleNearReadyRetryController() {
    }

    public static synchronized void resetForRuntime() {
        retryBudget = 0;
        settleLayersRemaining = 0;
        intraBurstBackoffShift = 0;
        consecutiveUnproductiveBursts = 0;
        broadRearmRejectsRemaining = 0;
        deepRearmRejectsRemaining = 0;
        broadBandRetired = false;
        deepBandRetired = false;
        postValidationCommitTrainActive = false;
        quietBackoffLayersRemaining = 0;

        armCount = 0L;
        consumeCount = 0L;
        forcedRejectCount = 0L;
        forcedAbortCount = 0L;
        forcedDownstreamRejectCount = 0L;
        layerReadyCount = 0L;
        layerReadyFromForcedRetryCount = 0L;
        visibleCommitFromForcedRetryCount = 0L;
        postValidationCommitArmCount = 0L;
        postValidationCommitConsumeCount = 0L;

        tightBandArmCount = 0L;
        broadBandArmCount = 0L;
        broadBandThrottleCount = 0L;
        deepBandArmCount = 0L;
        deepBandThrottleCount = 0L;

        deferredArmCount = 0L;
        settleSkipCount = 0L;
        normalRejectWhileDeferredCount = 0L;
        unproductiveBurstCount = 0L;
        recoveryCount = 0L;
        quietBackoffSkipCount = 0L;
        quietBackoffArmSuppressCount = 0L;
        quietBackoffActivationCount = 0L;

        peakSettleLayers = 0;
        peakConsecutiveUnproductiveBursts = 0;
        peakQuietBackoffLayers = 0;

        lastCandidateCount = 0;
        lastReadyCount = 0;
        lastPendingCount = 0;
        lastMissingCount = 0;
        peakMissingCountAtArm = 0;
    }

    /**
     * Consume one already-armed forced re-probe only after its settling delay.
     *
     * <p>Returning false during the delay intentionally lets the normal
     * renderer/governor continue. No frame is blocked and OpenGL remains the
     * fail-open visible owner.</p>
     */
    public static synchronized boolean consumeForcedRetry() {
        /*
         * Patch 140a: a large view that has already produced several bounded
         * but unproductive retry trains enters a cheap quiet band. This method
         * is called once per eligible SOLID opportunity, so the band decays
         * without any full visible-section sweep and remains self-healing.
         */
        if (quietBackoffLayersRemaining > 0
                && !postValidationCommitTrainActive) {
            quietBackoffLayersRemaining--;
            quietBackoffSkipCount++;
            return false;
        }

        if (retryBudget <= 0) {
            return false;
        }

        if (settleLayersRemaining > 0) {
            settleLayersRemaining--;
            settleSkipCount++;
            return false;
        }

        retryBudget--;
        consumeCount++;

        if (postValidationCommitTrainActive) {
            postValidationCommitConsumeCount++;

            if (retryBudget <= 0) {
                postValidationCommitTrainActive =
                        false;
            }
        }

        return true;
    }

    /**
     * Arm a small post-validation commit train.
     *
     * <p>The textured warmup itself runs against the hidden Vulkan target while
     * OpenGL stays authoritative. Once that proof completes, waiting for another
     * rare governor-selected whole-layer opportunity can make Gate 8 appear
     * nondeterministic. This method schedules four ordinary forced preflights
     * through the existing fail-open path. It never changes readiness, never
     * suppresses OpenGL, and never relaxes exact whole-layer atomicity.</p>
     */
    public static synchronized void armPostValidationCommitRetry() {
        postValidationCommitArmCount++;

        retryBudget =
                Math.max(
                        retryBudget,
                        4
                );

        settleLayersRemaining =
                0;
        quietBackoffLayersRemaining =
                0;

        intraBurstBackoffShift =
                0;

        postValidationCommitTrainActive =
                true;
    }

    /**
     * Observe one rejected full-layer scan.
     *
     * <p>Only a normal governor attempt may create a new recovery train.
     * Forced retries only advance the train's bounded temporal backoff.</p>
     */
    public static synchronized void observeRejectedSweep(
            boolean forcedRetry,
            int candidateCount,
            int readyCount,
            int pendingCount
    ) {
        int safeCandidates =
                Math.max(
                        0,
                        candidateCount
                );

        int safeReady =
                Math.max(
                        0,
                        Math.min(
                                safeCandidates,
                                readyCount
                        )
                );

        int safePending =
                Math.max(
                        0,
                        pendingCount
                );

        lastCandidateCount =
                safeCandidates;

        lastReadyCount =
                safeReady;

        lastPendingCount =
                safePending;

        lastMissingCount =
                Math.max(
                        0,
                        safeCandidates - safeReady
                );

        if (forcedRetry) {
            forcedRejectCount++;

            if (retryBudget > 0) {
                intraBurstBackoffShift =
                        Math.min(
                                intraBurstBackoffShift + 1,
                                MAX_UNPRODUCTIVE_BACKOFF_SHIFT
                        );

                armSettleDelay(
                        1 << intraBurstBackoffShift
                );
            } else {
                consecutiveUnproductiveBursts =
                        Math.min(
                                consecutiveUnproductiveBursts + 1,
                                MAX_UNPRODUCTIVE_BACKOFF_SHIFT
                        );

                peakConsecutiveUnproductiveBursts =
                        Math.max(
                                peakConsecutiveUnproductiveBursts,
                                consecutiveUnproductiveBursts
                        );

                unproductiveBurstCount++;
                settleLayersRemaining = 0;
                intraBurstBackoffShift = 0;
                armQuietBackoff();
            }

            return;
        }

        if (broadRearmRejectsRemaining > 0) {
            broadRearmRejectsRemaining--;
        }

        if (deepRearmRejectsRemaining > 0) {
            deepRearmRejectsRemaining--;
        }

        if (quietBackoffLayersRemaining > 0) {
            quietBackoffArmSuppressCount++;
            return;
        }

        /*
         * Do not continuously reset a recovery train while it is deliberately
         * waiting for generations/publication pairs to settle. A normal scan
         * can still become fully ready and call onLayerReady().
         */
        if (retryBudget > 0) {
            normalRejectWhileDeferredCount++;
            return;
        }

        int missingCount =
                lastMissingCount;

        int tightMissingAllowance =
                Math.max(
                        MIN_MISSING_ALLOWANCE,
                        safeCandidates / MISSING_DIVISOR
                );

        boolean tightNearReady =
                safeCandidates >= 2
                        && safeReady < safeCandidates
                        && ((long) safeReady * 100L)
                        >= ((long) safeCandidates
                        * READY_PERCENT_TARGET)
                        && missingCount <= tightMissingAllowance;

        int broadMissingAllowance =
                Math.max(
                        BROAD_MIN_MISSING_ALLOWANCE,
                        safeCandidates / BROAD_MISSING_DIVISOR
                );

        boolean broadNearReady =
                !tightNearReady
                        && !broadBandRetired
                        && broadRearmRejectsRemaining == 0
                        && safeCandidates >= BROAD_MIN_CANDIDATE_COUNT
                        && safeReady < safeCandidates
                        && ((long) safeReady * 100L)
                        >= ((long) safeCandidates
                        * BROAD_READY_PERCENT_TARGET)
                        && missingCount <= broadMissingAllowance;

        int deepMissingAllowance =
                Math.max(
                        DEEP_MIN_MISSING_ALLOWANCE,
                        safeCandidates / DEEP_MISSING_DIVISOR
                );

        boolean deepNearReady =
                !tightNearReady
                        && !broadNearReady
                        && !deepBandRetired
                        && deepRearmRejectsRemaining == 0
                        && safeCandidates >= DEEP_MIN_CANDIDATE_COUNT
                        && safeReady < safeCandidates
                        && ((long) safeReady * 100L)
                        >= ((long) safeCandidates
                        * DEEP_READY_PERCENT_TARGET)
                        && missingCount <= deepMissingAllowance;

        if (!tightNearReady && !broadNearReady && !deepNearReady) {
            if (!broadBandRetired
                    && broadRearmRejectsRemaining > 0
                    && safeCandidates >= BROAD_MIN_CANDIDATE_COUNT
                    && safeReady < safeCandidates
                    && ((long) safeReady * 100L)
                    >= ((long) safeCandidates
                    * BROAD_READY_PERCENT_TARGET)
                    && missingCount <= broadMissingAllowance) {
                broadBandThrottleCount++;
            }

            if (!deepBandRetired
                    && deepRearmRejectsRemaining > 0
                    && safeCandidates >= DEEP_MIN_CANDIDATE_COUNT
                    && safeReady < safeCandidates
                    && ((long) safeReady * 100L)
                    >= ((long) safeCandidates
                    * DEEP_READY_PERCENT_TARGET)
                    && missingCount <= deepMissingAllowance) {
                deepBandThrottleCount++;
            }

            return;
        }

        armCount++;
        deferredArmCount++;

        int requestedBurst;
        int baseSettleLayers;

        if (tightNearReady) {
            tightBandArmCount++;

            requestedBurst =
                    RETRY_BURST;

            baseSettleLayers =
                    TIGHT_INITIAL_SETTLE_LAYERS;
        } else if (broadNearReady) {
            broadBandArmCount++;

            broadRearmRejectsRemaining =
                    BROAD_REARM_NORMAL_REJECTS;

            requestedBurst =
                    BROAD_RETRY_BURST;

            baseSettleLayers =
                    BROAD_INITIAL_SETTLE_LAYERS;
        } else {
            deepBandArmCount++;

            deepRearmRejectsRemaining =
                    DEEP_REARM_NORMAL_REJECTS;

            requestedBurst =
                    DEEP_RETRY_BURST;

            baseSettleLayers =
                    DEEP_INITIAL_SETTLE_LAYERS;
        }

        retryBudget =
                requestedBurst;

        intraBurstBackoffShift =
                0;

        int initialDelay =
                baseSettleLayers
                        << Math.min(
                                consecutiveUnproductiveBursts,
                                MAX_UNPRODUCTIVE_BACKOFF_SHIFT
                        );

        armSettleDelay(
                initialDelay
        );

        peakMissingCountAtArm =
                Math.max(
                        peakMissingCountAtArm,
                        missingCount
                );
    }

    private static void armQuietBackoff() {
        int shift =
                Math.min(
                        3,
                        Math.max(
                                0,
                                consecutiveUnproductiveBursts - 1
                        )
                );

        int requestedLayers =
                QUIET_BACKOFF_BASE_LAYERS
                        << shift;

        quietBackoffLayersRemaining =
                Math.max(
                        quietBackoffLayersRemaining,
                        Math.min(
                                MAX_QUIET_BACKOFF_LAYERS,
                                requestedLayers
                        )
                );

        peakQuietBackoffLayers =
                Math.max(
                        peakQuietBackoffLayers,
                        quietBackoffLayersRemaining
                );

        quietBackoffActivationCount++;
    }

    private static void armSettleDelay(
            int requestedLayers
    ) {
        settleLayersRemaining =
                Math.max(
                        1,
                        Math.min(
                                MAX_SETTLE_LAYERS,
                                requestedLayers
                        )
                );

        peakSettleLayers =
                Math.max(
                        peakSettleLayers,
                        settleLayersRemaining
                );
    }

    public static synchronized void onForcedRetryAborted() {
        forcedAbortCount++;
        retryBudget = 0;
        settleLayersRemaining = 0;
        intraBurstBackoffShift = 0;
        postValidationCommitTrainActive = false;
    }

    public static synchronized void onLayerReady(
            boolean forcedRetry,
            int candidateCount
    ) {
        layerReadyCount++;

        if (forcedRetry) {
            layerReadyFromForcedRetryCount++;
        }

        lastCandidateCount =
                Math.max(
                        0,
                        candidateCount
                );

        /*
         * A genuinely complete layer is the recovery event. Clear all adaptive
         * backoff so future churn starts from the cheapest recovery profile.
         */
        if (consecutiveUnproductiveBursts > 0
                || retryBudget > 0
                || settleLayersRemaining > 0) {
            recoveryCount++;
        }

        consecutiveUnproductiveBursts = 0;
        retryBudget = 0;
        settleLayersRemaining = 0;
        intraBurstBackoffShift = 0;
        quietBackoffLayersRemaining = 0;
    }

    public static synchronized void onForcedDownstreamReject() {
        forcedDownstreamRejectCount++;

        /*
         * Once the layer itself was ready but a downstream exact-token /
         * presentation condition rejected it, this scheduler must not keep
         * spending CPU. That is a different subsystem and remains fail-open.
         */
        retryBudget = 0;
        settleLayersRemaining = 0;
        intraBurstBackoffShift = 0;
        postValidationCommitTrainActive = false;
        quietBackoffLayersRemaining =
                Math.max(
                        quietBackoffLayersRemaining,
                        QUIET_BACKOFF_BASE_LAYERS
                );
        peakQuietBackoffLayers =
                Math.max(
                        peakQuietBackoffLayers,
                        quietBackoffLayersRemaining
                );
    }

    public static synchronized void onVisibleCommitSuccess(
            boolean forcedRetry
    ) {
        if (forcedRetry) {
            visibleCommitFromForcedRetryCount++;
        }

        broadBandRetired = true;
        deepBandRetired = true;
        broadRearmRejectsRemaining = 0;
        deepRearmRejectsRemaining = 0;

        consecutiveUnproductiveBursts = 0;
        retryBudget = 0;
        settleLayersRemaining = 0;
        intraBurstBackoffShift = 0;
        postValidationCommitTrainActive = false;
        quietBackoffLayersRemaining = 0;
    }

    public static synchronized void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "vulkanVisibleNearReadyRetryInstalled",
                true
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryMode",
                "SELF_HEALING_DEFERRED_99_97_95_WITH_STEADY_STATE_QUIET_BACKOFF"
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryReadyPercentTarget",
                READY_PERCENT_TARGET
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBurst",
                RETRY_BURST
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryMinimumMissingAllowance",
                MIN_MISSING_ALLOWANCE
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryMissingDivisor",
                MISSING_DIVISOR
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadReadyPercentTarget",
                BROAD_READY_PERCENT_TARGET
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadBurst",
                BROAD_RETRY_BURST
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadMinimumMissingAllowance",
                BROAD_MIN_MISSING_ALLOWANCE
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadMissingDivisor",
                BROAD_MISSING_DIVISOR
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadRearmNormalRejects",
                BROAD_REARM_NORMAL_REJECTS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadMinimumCandidateCount",
                BROAD_MIN_CANDIDATE_COUNT
        );

        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepReadyPercentTarget",
                DEEP_READY_PERCENT_TARGET
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepBurst",
                DEEP_RETRY_BURST
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepMinimumMissingAllowance",
                DEEP_MIN_MISSING_ALLOWANCE
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepMissingDivisor",
                DEEP_MISSING_DIVISOR
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepRearmNormalRejects",
                DEEP_REARM_NORMAL_REJECTS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepMinimumCandidateCount",
                DEEP_MIN_CANDIDATE_COUNT
        );

        report.addProperty(
                "vulkanVisibleNearReadyRetryTemporalSettleInstalled",
                true
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryTightInitialSettleLayers",
                TIGHT_INITIAL_SETTLE_LAYERS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadInitialSettleLayers",
                BROAD_INITIAL_SETTLE_LAYERS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepInitialSettleLayers",
                DEEP_INITIAL_SETTLE_LAYERS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryMaximumSettleLayers",
                MAX_SETTLE_LAYERS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryQuietBackoffBaseLayers",
                QUIET_BACKOFF_BASE_LAYERS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryMaximumQuietBackoffLayers",
                MAX_QUIET_BACKOFF_LAYERS
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryQuietBackoffLayersRemaining",
                quietBackoffLayersRemaining
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetrySettleLayersRemaining",
                settleLayersRemaining
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryIntraBurstBackoffShift",
                intraBurstBackoffShift
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryConsecutiveUnproductiveBursts",
                consecutiveUnproductiveBursts
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPeakConsecutiveUnproductiveBursts",
                peakConsecutiveUnproductiveBursts
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPeakSettleLayers",
                peakSettleLayers
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeferredArmCount",
                deferredArmCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetrySettleSkipCount",
                settleSkipCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryNormalRejectWhileDeferredCount",
                normalRejectWhileDeferredCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryUnproductiveBurstCount",
                unproductiveBurstCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryRecoveryCount",
                recoveryCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryQuietBackoffSkipCount",
                quietBackoffSkipCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryQuietBackoffArmSuppressCount",
                quietBackoffArmSuppressCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryQuietBackoffActivationCount",
                quietBackoffActivationCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPeakQuietBackoffLayers",
                peakQuietBackoffLayers
        );

        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadRearmRejectsRemaining",
                broadRearmRejectsRemaining
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepRearmRejectsRemaining",
                deepRearmRejectsRemaining
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadBandRetired",
                broadBandRetired
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepBandRetired",
                deepBandRetired
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryCurrentBudget",
                retryBudget
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryArmCount",
                armCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryTightBandArmCount",
                tightBandArmCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadBandArmCount",
                broadBandArmCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBroadBandThrottleCount",
                broadBandThrottleCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepBandArmCount",
                deepBandArmCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryDeepBandThrottleCount",
                deepBandThrottleCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryConsumeCount",
                consumeCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryForcedRejectCount",
                forcedRejectCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryForcedAbortCount",
                forcedAbortCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryForcedDownstreamRejectCount",
                forcedDownstreamRejectCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryLayerReadyCount",
                layerReadyCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryLayerReadyFromForcedRetryCount",
                layerReadyFromForcedRetryCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryVisibleCommitFromForcedRetryCount",
                visibleCommitFromForcedRetryCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPostValidationCommitArmCount",
                postValidationCommitArmCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPostValidationCommitConsumeCount",
                postValidationCommitConsumeCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPostValidationCommitTrainActive",
                postValidationCommitTrainActive
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPostValidationCommitTrainSize",
                4
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryLastCandidateCount",
                lastCandidateCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryLastReadyCount",
                lastReadyCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryLastPendingCount",
                lastPendingCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryLastMissingCount",
                lastMissingCount
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryPeakMissingCountAtArm",
                peakMissingCountAtArm
        );

        report.addProperty(
                "vulkanVisibleNearReadyRetryRelaxesWholeLayerAtomicity",
                false
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryMutatesReadiness",
                false
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryOpenGlFailOpenPreserved",
                true
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryGameplayGpuWait",
                false
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetryBlocksGameplay",
                false
        );
        report.addProperty(
                "vulkanVisibleNearReadyRetrySelfHealingScheduler",
                true
        );
    }
}
