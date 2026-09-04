package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.visibility.PotatoViewTurnRelief;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

import java.util.IdentityHashMap;

/**
 * Render-thread publication barrier between Minecraft section compilation and
 * Potato's visible Vulkan SOLID ownership.
 *
 * <p>Patch 073 proved that a single global "no BLOCK upload anywhere for
 * 500 ms" condition is correct but far too conservative: on a large render
 * distance unrelated chunks keep uploading continuously, so Vulkan visible
 * ownership never becomes eligible. Patch 074 moves stabilization to the exact
 * visible section publication pair instead.</p>
 *
 * <p>Each tracked RenderSection is represented by the identity of Minecraft's
 * currently published CompiledSection plus the upload generation of that
 * section's DrawBufferBackendState. Only a pair that remains unchanged for a
 * bounded local proof interval may participate in an atomic Vulkan SOLID
 * commit. A CompiledSection identity change without a matching upload
 * generation advance remains a hard fail-open mismatch until the upload
 * catches up.</p>
 */
public final class VulkanVisiblePublicationGate {
    private static final long DEFAULT_INITIAL_PROOF_MILLIS =
            750L;

    private static final long INITIAL_PROOF_MILLIS =
            Math.max(
                    100L,
                    Math.min(
                            3_000L,
                            Long.getLong(
                                    "potato.vulkan.publicationInitialProofMillis",
                                    DEFAULT_INITIAL_PROOF_MILLIS
                            )
                    )
            );

    private static final long DEFAULT_PAIR_SETTLE_MILLIS =
            150L;

    private static final long PAIR_SETTLE_MILLIS =
            Math.max(
                    25L,
                    Math.min(
                            1_000L,
                            Long.getLong(
                                    "potato.vulkan.publicationPairSettleMillis",
                                    DEFAULT_PAIR_SETTLE_MILLIS
                            )
                    )
            );

    private static final int DEFAULT_STABLE_PASS_TARGET =
            2;

    private static final int STABLE_PASS_TARGET =
            Math.max(
                    1,
                    Math.min(
                            8,
                            Integer.getInteger(
                                    "potato.vulkan.publicationStablePasses",
                                    DEFAULT_STABLE_PASS_TARGET
                            )
                    )
            );

    private static final int MAX_TRACKED_SECTIONS =
            131_072;

    private static final IdentityHashMap<
            SectionRenderDispatcher.RenderSection,
            PublicationSnapshot> snapshots =
            new IdentityHashMap<>();

    private static long blockUploadAttemptCount;
    private static long geometryUploadDispatchCount;
    private static long visibleSectionObservationCount;
    private static long compiledGenerationChangeCount;
    private static long uploadGenerationAdvanceCount;
    private static long compiledChangedBeforeUploadCount;
    private static long pendingMismatchObservationCount;
    private static long visibleCommitAttemptCount;
    private static long quietWindowRejectCount;
    private static long stablePassRejectCount;
    private static long pendingMismatchRejectCount;
    private static long allowedCount;
    private static long resourcePreflightRejectCount;
    private static long fallbackSubmissionSuppressedCount;
    private static long trackerResetCount;

    private static long fullLayerSweepCount;
    private static long fullLayerSweepCandidateCount;
    private static long fullLayerSweepReadyCount;
    private static long fullLayerSweepPendingCount;
    private static long fullLayerSweepRejectCount;
    private static long fullLayerSweepEligibleCount;
    private static int fullLayerSweepPeakCandidateCount;
    private static int fullLayerSweepPeakReadyCount;

    private static long firstObservationRejectCount;
    private static long missingGenerationRejectCount;
    private static long initialProofRejectCount;
    private static long pairSettleRejectCount;
    private static long localStablePassRejectCount;
    private static long readyObservationCount;
    private static long provenSectionTransitionCount;

    private static int peakTrackedSectionCount;
    private static int pendingMismatchCount;
    private static int peakPendingMismatchCount;
    private static int provenSectionCount;
    private static int peakProvenSectionCount;

    private VulkanVisiblePublicationGate() {
    }

    public static void resetForRuntime() {
        snapshots.clear();

        blockUploadAttemptCount = 0L;
        geometryUploadDispatchCount = 0L;
        visibleSectionObservationCount = 0L;
        compiledGenerationChangeCount = 0L;
        uploadGenerationAdvanceCount = 0L;
        compiledChangedBeforeUploadCount = 0L;
        pendingMismatchObservationCount = 0L;
        visibleCommitAttemptCount = 0L;
        quietWindowRejectCount = 0L;
        stablePassRejectCount = 0L;
        pendingMismatchRejectCount = 0L;
        allowedCount = 0L;
        resourcePreflightRejectCount = 0L;
        fallbackSubmissionSuppressedCount = 0L;
        trackerResetCount = 0L;

        fullLayerSweepCount = 0L;
        fullLayerSweepCandidateCount = 0L;
        fullLayerSweepReadyCount = 0L;
        fullLayerSweepPendingCount = 0L;
        fullLayerSweepRejectCount = 0L;
        fullLayerSweepEligibleCount = 0L;
        fullLayerSweepPeakCandidateCount = 0;
        fullLayerSweepPeakReadyCount = 0;

        firstObservationRejectCount = 0L;
        missingGenerationRejectCount = 0L;
        initialProofRejectCount = 0L;
        pairSettleRejectCount = 0L;
        localStablePassRejectCount = 0L;
        readyObservationCount = 0L;
        provenSectionTransitionCount = 0L;

        peakTrackedSectionCount = 0;
        pendingMismatchCount = 0;
        peakPendingMismatchCount = 0;
        provenSectionCount = 0;
        peakProvenSectionCount = 0;

        VulkanVisibleNearReadyRetryController
                .resetForRuntime();

        VulkanVisibleSealedEpoch.resetForRuntime();

        PotatoViewTurnRelief.resetForRuntime();
    }

    /**
     * Called at the actual eligible BLOCK VertexBuffer upload boundary before
     * Vulkan admission. Patch 074 deliberately does not reset a global quiet
     * timer here: an upload in a distant/unrelated section must not invalidate
     * already-proven visible section pairs.
     */
    public static void onBlockUploadAttempt() {
        blockUploadAttemptCount++;
    }

    public static void onGeometryUploadDispatch() {
        geometryUploadDispatchCount++;
    }

    /**
     * Observe one currently visible, non-empty SOLID section.
     *
     * @return true while this exact CompiledSection + Vulkan upload-generation
     * publication pair is not yet proven safe for visible Vulkan ownership.
     */
    public static boolean observeVisibleSection(
            SectionRenderDispatcher.RenderSection section,
            SectionRenderDispatcher.CompiledSection compiled,
            DrawBufferBackendState state
    ) {
        visibleSectionObservationCount++;

        if (section == null
                || compiled == null
                || state == null
                || !state.uploaded()
                || state.closed()
                || state.uploadGeneration() <= 0L) {
            missingGenerationRejectCount++;
            return true;
        }

        if (snapshots.size() >= MAX_TRACKED_SECTIONS
                && !snapshots.containsKey(section)) {
            resetTrackerOnly();
        }

        long now =
                System.nanoTime();

        long generation =
                state.uploadGeneration();

        PublicationSnapshot snapshot =
                snapshots.get(section);

        if (snapshot == null) {
            snapshot =
                    new PublicationSnapshot(
                            compiled,
                            generation,
                            now
                    );

            snapshots.put(
                    section,
                    snapshot
            );

            peakTrackedSectionCount =
                    Math.max(
                            peakTrackedSectionCount,
                            snapshots.size()
                    );

            firstObservationRejectCount++;
            return true;
        }

        boolean compiledChanged =
                snapshot.compiled
                        != compiled;

        boolean uploadAdvanced =
                generation
                        != snapshot.uploadGeneration;

        if (compiledChanged) {
            compiledGenerationChangeCount++;
        }

        if (uploadAdvanced) {
            uploadGenerationAdvanceCount++;
        }

        if (compiledChanged
                || uploadAdvanced) {
            boolean previouslyProven =
                    snapshot.proven;

            if (previouslyProven) {
                snapshot.proven = false;
                provenSectionCount =
                        Math.max(
                                0,
                                provenSectionCount - 1
                        );
            }

            if (compiledChanged
                    && !uploadAdvanced) {
                compiledChangedBeforeUploadCount++;

                if (!snapshot.pendingMismatch) {
                    snapshot.pendingMismatch = true;
                    pendingMismatchCount++;
                    peakPendingMismatchCount =
                            Math.max(
                                    peakPendingMismatchCount,
                                    pendingMismatchCount
                            );
                }
            }

            if (uploadAdvanced
                    && snapshot.pendingMismatch) {
                snapshot.pendingMismatch = false;
                pendingMismatchCount =
                        Math.max(
                                0,
                                pendingMismatchCount - 1
                        );
            }

            snapshot.compiled =
                    compiled;

            snapshot.uploadGeneration =
                    generation;

            snapshot.pairSinceNanos =
                    now;

            snapshot.stablePasses =
                    1;

            if (previouslyProven) {
                provenSectionTransitionCount++;
            }
        } else {
            snapshot.stablePasses =
                    Math.min(
                            STABLE_PASS_TARGET,
                            snapshot.stablePasses + 1
                    );
        }

        if (snapshot.pendingMismatch) {
            pendingMismatchObservationCount++;
            return true;
        }

        if (snapshot.stablePasses
                < STABLE_PASS_TARGET) {
            localStablePassRejectCount++;
            return true;
        }

        /*
         * Patch 120 retires the 750 ms / 150 ms wall-clock publication wait.
         * It was conservative but not a real correctness primitive: the 119
         * settings run had 61,660/61,660 resident prepares ready while 13,175
         * observations still failed only because a newly visible section had
         * not existed for 750 ms yet.
         *
         * Exact safety is now split cleanly:
         *  - this per-section gate requires two consecutive observations of
         *    the exact CompiledSection + upload-generation pair, and
         *  - VulkanVisibleSealedEpoch requires a complete identical whole-layer
         *    generation fingerprint before ownership can be armed.
         *
         * The current-generation resource is still pinned by the existing
         * submission binding immediately before queue submission. No stale or
         * partially ready layer is accepted.
         */
        if (!snapshot.proven) {
            snapshot.proven = true;
            snapshot.everProven = true;

            provenSectionCount++;
            peakProvenSectionCount =
                    Math.max(
                            peakProvenSectionCount,
                            provenSectionCount
                    );
        }

        readyObservationCount++;
        return false;
    }

    /**
     * One complete atomic SOLID preflight observation.
     *
     * <p>Patch 075 deliberately scans the entire visible section set even when
     * one section is not ready. That lets all section publication pairs mature
     * in parallel instead of proving one early-return section at a time.</p>
     */
    public static void onFullLayerSweep(
            int candidateCount,
            int readyCount,
            int pendingCount,
            boolean rejected
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

        fullLayerSweepCount++;
        fullLayerSweepCandidateCount +=
                safeCandidates;
        fullLayerSweepReadyCount +=
                safeReady;
        fullLayerSweepPendingCount +=
                safePending;

        fullLayerSweepPeakCandidateCount =
                Math.max(
                        fullLayerSweepPeakCandidateCount,
                        safeCandidates
                );

        fullLayerSweepPeakReadyCount =
                Math.max(
                        fullLayerSweepPeakReadyCount,
                        safeReady
                );

        if (rejected) {
            fullLayerSweepRejectCount++;
        } else {
            fullLayerSweepEligibleCount++;
        }
    }

    public static void onResourcePreflightReject() {
        resourcePreflightRejectCount++;
    }

    /**
     * Whole-layer gate remains atomic. By the time this method is reached every
     * visible non-empty SOLID section has already supplied a proven publication
     * pair and a ready Vulkan resource.
     */
    public static boolean allowVisibleCommit(
            boolean visiblePendingMismatch
    ) {
        visibleCommitAttemptCount++;

        if (visiblePendingMismatch) {
            pendingMismatchRejectCount++;
            return false;
        }

        allowedCount++;
        return true;
    }

    public static void onFallbackSubmissionSuppressed() {
        fallbackSubmissionSuppressedCount++;
    }

    public static void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "vulkanPublicationGateInstalled",
                true
        );
        report.addProperty(
                "vulkanPublicationGateMode",
                "PER_SECTION_EXACT_PAIR_PLUS_SEALED_VISIBLE_EPOCH_FAIL_OPEN"
        );
        report.addProperty(
                "vulkanPublicationGateGlobalQuietWindowRetired",
                true
        );

        /*
         * Compatibility fields retained so report consumers do not break.
         * The old global quiet timer is intentionally no longer active.
         */
        report.addProperty(
                "vulkanPublicationGateQuietMillis",
                0
        );
        report.addProperty(
                "vulkanPublicationGateQuietWindowRejectCount",
                quietWindowRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateStablePassRejectCount",
                stablePassRejectCount
        );

        report.addProperty(
                "vulkanPublicationGateInitialProofMillis",
                INITIAL_PROOF_MILLIS
        );
        report.addProperty(
                "vulkanPublicationGatePairSettleMillis",
                PAIR_SETTLE_MILLIS
        );
        report.addProperty(
                "vulkanPublicationGateWallClockProofRetiredBySealedEpoch",
                true
        );
        report.addProperty(
                "vulkanPublicationGateActiveProofUsesConsecutiveExactPairs",
                true
        );
        report.addProperty(
                "vulkanPublicationGateStablePassTarget",
                STABLE_PASS_TARGET
        );
        report.addProperty(
                "vulkanPublicationGateBlockUploadAttemptCount",
                blockUploadAttemptCount
        );
        report.addProperty(
                "vulkanPublicationGateGeometryUploadDispatchCount",
                geometryUploadDispatchCount
        );
        report.addProperty(
                "vulkanPublicationGateVisibleSectionObservationCount",
                visibleSectionObservationCount
        );
        report.addProperty(
                "vulkanPublicationGateCompiledGenerationChangeCount",
                compiledGenerationChangeCount
        );
        report.addProperty(
                "vulkanPublicationGateUploadGenerationAdvanceCount",
                uploadGenerationAdvanceCount
        );
        report.addProperty(
                "vulkanPublicationGateCompiledChangedBeforeUploadCount",
                compiledChangedBeforeUploadCount
        );
        report.addProperty(
                "vulkanPublicationGatePendingMismatchObservationCount",
                pendingMismatchObservationCount
        );
        report.addProperty(
                "vulkanPublicationGatePendingMismatchCount",
                pendingMismatchCount
        );
        report.addProperty(
                "vulkanPublicationGatePeakPendingMismatchCount",
                peakPendingMismatchCount
        );
        report.addProperty(
                "vulkanPublicationGateVisibleCommitAttemptCount",
                visibleCommitAttemptCount
        );
        report.addProperty(
                "vulkanPublicationGatePendingMismatchRejectCount",
                pendingMismatchRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateResourcePreflightRejectCount",
                resourcePreflightRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateAllowedCount",
                allowedCount
        );
        report.addProperty(
                "vulkanPublicationGateFallbackSubmissionSuppressedCount",
                fallbackSubmissionSuppressedCount
        );
        report.addProperty(
                "vulkanPublicationGateTrackerResetCount",
                trackerResetCount
        );
        report.addProperty(
                "vulkanPublicationGateTrackedSectionCount",
                snapshots.size()
        );
        report.addProperty(
                "vulkanPublicationGatePeakTrackedSectionCount",
                peakTrackedSectionCount
        );

        report.addProperty(
                "vulkanPublicationGateFirstObservationRejectCount",
                firstObservationRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateMissingGenerationRejectCount",
                missingGenerationRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateInitialProofRejectCount",
                initialProofRejectCount
        );
        report.addProperty(
                "vulkanPublicationGatePairSettleRejectCount",
                pairSettleRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateLocalStablePassRejectCount",
                localStablePassRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateReadyObservationCount",
                readyObservationCount
        );
        report.addProperty(
                "vulkanPublicationGateProvenSectionTransitionCount",
                provenSectionTransitionCount
        );
        report.addProperty(
                "vulkanPublicationGateProvenSectionCount",
                provenSectionCount
        );
        report.addProperty(
                "vulkanPublicationGatePeakProvenSectionCount",
                peakProvenSectionCount
        );

        report.addProperty(
                "vulkanPublicationGateFullLayerSweepParallelized",
                true
        );
        report.addProperty(
                "vulkanPublicationGateProvenSectionsRetainedOffscreen",
                true
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepCount",
                fullLayerSweepCount
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepCandidateCount",
                fullLayerSweepCandidateCount
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepReadyCount",
                fullLayerSweepReadyCount
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepPendingCount",
                fullLayerSweepPendingCount
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepRejectCount",
                fullLayerSweepRejectCount
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepEligibleCount",
                fullLayerSweepEligibleCount
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepPeakCandidateCount",
                fullLayerSweepPeakCandidateCount
        );
        report.addProperty(
                "vulkanPublicationGateFullLayerSweepPeakReadyCount",
                fullLayerSweepPeakReadyCount
        );

        VulkanVisibleNearReadyRetryController
                .enrich(
                        report
                );

        VulkanVisibleSealedEpoch.enrich(
                report
        );

        PotatoViewTurnRelief.enrich(
                report
        );

        report.addProperty(
                "vulkanPublicationGateNoCpuWait",
                true
        );
        report.addProperty(
                "vulkanPublicationGatePerSectionPublicationProof",
                true
        );
        report.addProperty(
                "vulkanPublicationGateExactPublicationTokenNext",
                false
        );
    }

    private static void resetTrackerOnly() {
        snapshots.clear();
        pendingMismatchCount = 0;
        provenSectionCount = 0;
        trackerResetCount++;
    }

    private static final class PublicationSnapshot {
        private SectionRenderDispatcher.CompiledSection compiled;
        private long uploadGeneration;
        private long pairSinceNanos;
        private int stablePasses;
        private boolean pendingMismatch;
        private boolean proven;
        private boolean everProven;

        private PublicationSnapshot(
                SectionRenderDispatcher.CompiledSection compiled,
                long uploadGeneration,
                long pairSinceNanos
        ) {
            this.compiled =
                    compiled;

            this.uploadGeneration =
                    uploadGeneration;

            this.pairSinceNanos =
                    pairSinceNanos;

            this.stablePasses =
                    1;
        }
    }
}
