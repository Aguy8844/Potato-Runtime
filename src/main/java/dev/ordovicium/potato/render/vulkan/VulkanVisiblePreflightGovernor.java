package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;

/**
 * Fail-open scheduler for the expensive whole-layer visible Vulkan preflight.
 *
 * <p>The atomic ownership proof remains unchanged. This class only decides
 * when paying for a complete visible-section sweep is worthwhile. Every
 * throttled frame falls straight through to the proven OpenGL baseline, so a
 * scheduling decision can reduce CPU work but can never hide world geometry.</p>
 */
public final class VulkanVisiblePreflightGovernor {
    private static final int MAX_COOLDOWN_LAYERS =
            Math.max(
                    1,
                    Integer.getInteger(
                            "potato.vulkan.preflightMaxCooldownLayers",
                            120
                    )
            );

    private static final int LARGE_VIEW_CANDIDATE_THRESHOLD =
            Math.max(
                    256,
                    Integer.getInteger(
                            "potato.vulkan.preflightLargeViewCandidates",
                            1024
                    )
            );

    /*
     * Patch 140a: once a large visible set repeatedly fails to become
     * atomically committable, a four-layer retry loop is pure CPU overhead.
     * Keep periodic self-healing probes, but stretch the interval into
     * multi-dozen-layer bands after sustained no-progress.
     */
    private static final int DEEP_BACKOFF_FAILURE_STREAK =
            Math.max(
                    2,
                    Integer.getInteger(
                            "potato.vulkan.preflightDeepBackoffFailures",
                            8
                    )
            );

    private static final int STALLED_BACKOFF_FAILURE_STREAK =
            Math.max(
                    DEEP_BACKOFF_FAILURE_STREAK,
                    Integer.getInteger(
                            "potato.vulkan.preflightStalledBackoffFailures",
                            16
                    )
            );

    private static final int DEEP_COOLDOWN_LAYERS =
            Math.max(
                    4,
                    Integer.getInteger(
                            "potato.vulkan.preflightDeepCooldownLayers",
                            30
                    )
            );

    private static final int STALLED_COOLDOWN_LAYERS =
            Math.max(
                    DEEP_COOLDOWN_LAYERS,
                    Integer.getInteger(
                            "potato.vulkan.preflightStalledCooldownLayers",
                            90
                    )
            );

    /*
     * Patch 141: once this process has already completed a real visible Vulkan
     * SOLID commit, a 30-90 layer retry desert is counterproductive. The
     * renderer is no longer proving that the path exists; it is converging a
     * changing large visible set back to the same exact atomic state.
     */
    private static final int POST_SUCCESS_RECOVERY_COOLDOWN_LAYERS =
            Math.max(
                    1,
                    Integer.getInteger(
                            "potato.vulkan.preflightPostSuccessRecoveryCooldownLayers",
                            4
                    )
            );

    private static final int POST_SUCCESS_HOT_PATH_MIN_CANDIDATES =
            Math.max(
                    2,
                    Integer.getInteger(
                            "potato.vulkan.preflightPostSuccessHotPathMinCandidates",
                            128
                    )
            );

    /*
     * Patch 142: the 141 broad-view run had exact residency ready for every
     * checked section (47,488/47,488) but the last full sweep was only about
     * 78% publication-ready. The 90-layer pre-success desert then delayed the
     * second exact observation that would make those pairs stable.
     */
    private static final int PRE_SUCCESS_CONVERGENCE_MIN_CANDIDATES =
            Math.max(
                    128,
                    Integer.getInteger(
                            "potato.vulkan.preflightPreSuccessConvergenceMinCandidates",
                            256
                    )
            );

    private static final int PRE_SUCCESS_CONVERGENCE_READY_PERCENT =
            Math.max(
                    50,
                    Math.min(
                            99,
                            Integer.getInteger(
                                    "potato.vulkan.preflightPreSuccessConvergenceReadyPercent",
                                    70
                            )
                    )
            );

    private static final int PRE_SUCCESS_CONVERGENCE_COOLDOWN_LAYERS =
            Math.max(
                    0,
                    Math.min(
                            4,
                            Integer.getInteger(
                                    "potato.vulkan.preflightPreSuccessConvergenceCooldownLayers",
                                    1
                            )
                    )
            );

    private static int cooldownRemaining;
    private static int failureStreak;
    private static int peakCooldown;
    private static int lastCandidateCount;

    private static long opportunityCount;
    private static long attemptCount;
    private static long throttleSkipCount;
    private static long capacityRejectCount;
    private static long layerRejectCount;
    private static long exactTokenRejectCount;
    private static long prepareRejectCount;
    private static long armRejectCount;
    private static long commitRejectCount;
    private static long commitSuccessCount;
    private static long estimatedAvoidedSectionChecks;
    private static long candidateChecksPaid;
    private static long deepBackoffActivationCount;
    private static long stalledBackoffActivationCount;
    private static boolean postSuccessHotPathActive;
    private static long postSuccessHotPathActivationCount;
    private static long postSuccessHotPathAttemptCount;
    private static long postSuccessHotPathCommitCount;
    private static long postSuccessHotPathExitCount;
    private static long postSuccessRecoveryActivationCount;
    private static long preSuccessConvergenceActivationCount;
    private static long preSuccessConvergenceReadySectionCount;
    private static int preSuccessConvergencePeakReadyPercent;

    private VulkanVisiblePreflightGovernor() {
    }

    public static boolean beginAttempt() {
        opportunityCount++;

        if (postSuccessHotPathActive) {
            attemptCount++;
            postSuccessHotPathAttemptCount++;
            return true;
        }

        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            throttleSkipCount++;
            estimatedAvoidedSectionChecks +=
                    Math.max(0, lastCandidateCount);
            return false;
        }

        attemptCount++;
        return true;
    }

    public static void onCapacityReject() {
        capacityRejectCount++;
        registerFailure(0, FailureKind.CAPACITY);
    }

    public static void onLayerReject(
            int candidateCount,
            int readyCount
    ) {
        layerRejectCount++;
        candidateChecksPaid += Math.max(0, candidateCount);

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

        int readyPercent =
                safeCandidates <= 0
                        ? 0
                        : (int) Math.min(
                                100L,
                                (long) safeReady * 100L
                                        / safeCandidates
                        );

        preSuccessConvergencePeakReadyPercent =
                Math.max(
                        preSuccessConvergencePeakReadyPercent,
                        readyPercent
                );

        if (commitSuccessCount == 0L
                && safeCandidates
                >= PRE_SUCCESS_CONVERGENCE_MIN_CANDIDATES
                && readyPercent
                >= PRE_SUCCESS_CONVERGENCE_READY_PERCENT) {
            lastCandidateCount = safeCandidates;

            /*
             * High publication readiness is measurable forward progress, not
             * a stalled renderer. Clear the old deep-backoff history so one
             * transient ratio dip cannot immediately restore a 90-layer desert.
             */
            failureStreak = 0;
            cooldownRemaining =
                    PRE_SUCCESS_CONVERGENCE_COOLDOWN_LAYERS;
            peakCooldown =
                    Math.max(
                            peakCooldown,
                            cooldownRemaining
                    );
            preSuccessConvergenceActivationCount++;
            preSuccessConvergenceReadySectionCount +=
                    safeReady;
            return;
        }

        registerFailure(
                safeCandidates,
                FailureKind.LAYER
        );
    }

    public static void onExactTokenReject(int candidateCount) {
        exactTokenRejectCount++;
        candidateChecksPaid += Math.max(0, candidateCount);
        registerFailure(candidateCount, FailureKind.TOKEN);
    }

    public static void onPrepareReject(int candidateCount) {
        prepareRejectCount++;
        candidateChecksPaid += Math.max(0, candidateCount);
        registerFailure(candidateCount, FailureKind.PREPARE);
    }

    public static void onArmReject(int candidateCount) {
        armRejectCount++;
        registerFailure(candidateCount, FailureKind.ARM);
    }

    public static void onCommitReject(int candidateCount) {
        commitRejectCount++;
        registerFailure(candidateCount, FailureKind.COMMIT);
    }

    public static void onCommitSuccess(int candidateCount) {
        commitSuccessCount++;
        lastCandidateCount = Math.max(0, candidateCount);
        failureStreak = 0;
        cooldownRemaining = 0;

        if (candidateCount >= POST_SUCCESS_HOT_PATH_MIN_CANDIDATES) {
            if (!postSuccessHotPathActive) {
                postSuccessHotPathActivationCount++;
            }

            postSuccessHotPathActive = true;
            postSuccessHotPathCommitCount++;
        }
    }

    private static void registerFailure(
            int candidateCount,
            FailureKind kind
    ) {
        lastCandidateCount = Math.max(0, candidateCount);

        if (postSuccessHotPathActive) {
            postSuccessHotPathActive = false;
            postSuccessHotPathExitCount++;
        }

        /*
         * After at least one real visible commit, failures in the ordinary
         * layer/token/prepare/arm/commit path represent convergence churn, not
         * an unproven renderer. Retry within a handful of SOLID opportunities
         * so newly-visible exact pairs can mature while the user is still
         * looking at the same broad loaded landscape.
         *
         * Capacity rejection deliberately keeps the original conservative
         * backoff because a too-large layer cannot converge merely by polling
         * it more often.
         */
        if (commitSuccessCount > 0L
                && kind != FailureKind.CAPACITY) {
            failureStreak = Math.min(30, failureStreak + 1);
            cooldownRemaining =
                    Math.min(
                            MAX_COOLDOWN_LAYERS,
                            POST_SUCCESS_RECOVERY_COOLDOWN_LAYERS
                    );
            peakCooldown =
                    Math.max(
                            peakCooldown,
                            cooldownRemaining
                    );
            postSuccessRecoveryActivationCount++;
            return;
        }

        failureStreak = Math.min(30, failureStreak + 1);

        int exponential =
                1 << Math.min(5, Math.max(0, failureStreak - 1));

        boolean largeView =
                candidateCount >= LARGE_VIEW_CANDIDATE_THRESHOLD;

        int floor =
                largeView
                        ? 4
                        : 1;

        if (kind == FailureKind.TOKEN) {
            floor = Math.max(floor, 2);
        } else if (kind == FailureKind.PREPARE
                || kind == FailureKind.ARM
                || kind == FailureKind.COMMIT) {
            floor = Math.max(floor, 4);
        }

        if (largeView
                && failureStreak >= DEEP_BACKOFF_FAILURE_STREAK) {
            floor =
                    Math.max(
                            floor,
                            DEEP_COOLDOWN_LAYERS
                    );
            deepBackoffActivationCount++;
        }

        if (largeView
                && commitSuccessCount == 0L
                && failureStreak >= STALLED_BACKOFF_FAILURE_STREAK) {
            floor =
                    Math.max(
                            floor,
                            STALLED_COOLDOWN_LAYERS
                    );
            stalledBackoffActivationCount++;
        }

        cooldownRemaining =
                Math.min(
                        MAX_COOLDOWN_LAYERS,
                        Math.max(floor, exponential)
                );

        peakCooldown =
                Math.max(
                        peakCooldown,
                        cooldownRemaining
                );
    }

    public static void enrich(JsonObject report) {
        report.addProperty(
                "vulkanVisiblePreflightGovernorInstalled",
                true
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorMode",
                "FAIL_OPEN_PRE_SUCCESS_READINESS_CONVERGENCE_POST_SUCCESS_VULKAN_HOT_PATH"
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorOpportunityCount",
                opportunityCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorAttemptCount",
                attemptCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorThrottleSkipCount",
                throttleSkipCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorCapacityRejectCount",
                capacityRejectCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorLayerRejectCount",
                layerRejectCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorExactTokenRejectCount",
                exactTokenRejectCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPrepareRejectCount",
                prepareRejectCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorArmRejectCount",
                armRejectCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorCommitRejectCount",
                commitRejectCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorCommitSuccessCount",
                commitSuccessCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorCooldownRemaining",
                cooldownRemaining
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPeakCooldown",
                peakCooldown
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorFailureStreak",
                failureStreak
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorLastCandidateCount",
                lastCandidateCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorLargeViewCandidateThreshold",
                LARGE_VIEW_CANDIDATE_THRESHOLD
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorMaxCooldownLayers",
                MAX_COOLDOWN_LAYERS
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorDeepBackoffFailureStreak",
                DEEP_BACKOFF_FAILURE_STREAK
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorStalledBackoffFailureStreak",
                STALLED_BACKOFF_FAILURE_STREAK
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorDeepCooldownLayers",
                DEEP_COOLDOWN_LAYERS
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorStalledCooldownLayers",
                STALLED_COOLDOWN_LAYERS
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorDeepBackoffActivationCount",
                deepBackoffActivationCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorStalledBackoffActivationCount",
                stalledBackoffActivationCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessRecoveryCooldownLayers",
                POST_SUCCESS_RECOVERY_COOLDOWN_LAYERS
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessHotPathMinCandidates",
                POST_SUCCESS_HOT_PATH_MIN_CANDIDATES
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessHotPathActive",
                postSuccessHotPathActive
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessHotPathActivationCount",
                postSuccessHotPathActivationCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessHotPathAttemptCount",
                postSuccessHotPathAttemptCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessHotPathCommitCount",
                postSuccessHotPathCommitCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessHotPathExitCount",
                postSuccessHotPathExitCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPostSuccessRecoveryActivationCount",
                postSuccessRecoveryActivationCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPreSuccessConvergenceMinCandidates",
                PRE_SUCCESS_CONVERGENCE_MIN_CANDIDATES
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPreSuccessConvergenceReadyPercent",
                PRE_SUCCESS_CONVERGENCE_READY_PERCENT
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPreSuccessConvergenceCooldownLayers",
                PRE_SUCCESS_CONVERGENCE_COOLDOWN_LAYERS
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPreSuccessConvergenceActivationCount",
                preSuccessConvergenceActivationCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPreSuccessConvergenceReadySectionCount",
                preSuccessConvergenceReadySectionCount
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorPreSuccessConvergencePeakReadyPercent",
                preSuccessConvergencePeakReadyPercent
        );

        report.addProperty(
                "vulkanVisiblePreflightGovernorEstimatedAvoidedSectionChecks",
                estimatedAvoidedSectionChecks
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorCandidateChecksPaid",
                candidateChecksPaid
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorMutatesOpenGlDraws",
                false
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorFailOpenToOpenGl",
                true
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorNoGameplayGpuWait",
                true
        );
        report.addProperty(
                "vulkanVisiblePreflightGovernorNextMilestone",
                "POTATO_ENGINE_VISIBLE_REGION_INDIRECT_SOLID"
        );
    }

    private enum FailureKind {
        CAPACITY,
        LAYER,
        TOKEN,
        PREPARE,
        ARM,
        COMMIT
    }
}
