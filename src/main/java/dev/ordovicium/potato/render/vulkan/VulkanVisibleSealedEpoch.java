package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;

/**
 * Exact whole-layer publication epoch for visible Vulkan SOLID ownership.
 *
 * <p>Gate 8 used to require every mutable visible section to happen to satisfy
 * a wall-clock settle timer in the same sweep. That was fail-open safe, but it
 * made liveness probabilistic at large view distances. This controller turns
 * the proof into a small BUILDING/ACTIVE state machine instead.</p>
 *
 * <p>A BUILDING epoch is only sealed from a complete sweep whose every section
 * already has exact current residency and an exact stable publication pair.
 * Patch 142 removes the redundant second whole-layer temporal observation:
 * every section has already survived the publication gate's two exact pair
 * observations, and the exact frame token rechecks the current generation
 * before ownership can queue. A complete current sweep may therefore activate
 * immediately without weakening whole-layer atomicity. Any changed generation
 * still rebuilds the epoch while OpenGL remains authoritative. No incomplete
 * layer is ever accepted and no stale generation is replayed.</p>
 */
public final class VulkanVisibleSealedEpoch {
    private static final long FNV_OFFSET =
            0xcbf29ce484222325L;

    private static final long FNV_PRIME =
            0x100000001b3L;

    /*
     * Patch 142: per-section publication stability already requires two exact
     * CompiledSection + upload-generation observations. Requiring two more
     * identical complete layer sweeps duplicated liveness proof.
     */
    private static final int REQUIRED_IDENTICAL_COMPLETE_SWEEPS =
            1;

    private static boolean buildingValid;
    private static long buildingFingerprint;
    private static int buildingCandidateCount;
    private static int buildingStableSweeps;

    private static boolean activeValid;
    private static long activeFingerprint;
    private static int activeCandidateCount;

    private static long completeSweepOfferCount;
    private static long rejectedSweepCount;
    private static long buildingStartCount;
    private static long buildingAdvanceCount;
    private static long buildingResetCount;
    private static long activationCount;
    private static long activeReuseCount;
    private static long commitSuccessCount;
    private static int peakBuildingStableSweeps;

    private VulkanVisibleSealedEpoch() {
    }

    public static synchronized void resetForRuntime() {
        buildingValid = false;
        buildingFingerprint = 0L;
        buildingCandidateCount = 0;
        buildingStableSweeps = 0;

        activeValid = false;
        activeFingerprint = 0L;
        activeCandidateCount = 0;

        completeSweepOfferCount = 0L;
        rejectedSweepCount = 0L;
        buildingStartCount = 0L;
        buildingAdvanceCount = 0L;
        buildingResetCount = 0L;
        activationCount = 0L;
        activeReuseCount = 0L;
        commitSuccessCount = 0L;
        peakBuildingStableSweeps = 0;
    }

    public static long seed() {
        return FNV_OFFSET;
    }

    public static long mixSection(
            long fingerprint,
            int sectionX,
            int sectionY,
            int sectionZ,
            int compiledIdentity,
            long meshGeneration,
            long residentGeneration,
            long uploadGeneration
    ) {
        long mixed = fingerprint;
        mixed = mix(mixed, sectionX);
        mixed = mix(mixed, sectionY);
        mixed = mix(mixed, sectionZ);
        mixed = mix(mixed, compiledIdentity);
        mixed = mix(mixed, meshGeneration);
        mixed = mix(mixed, residentGeneration);
        mixed = mix(mixed, uploadGeneration);
        return mixed;
    }

    /**
     * Offer one already-complete, exact-current whole-layer sweep.
     *
     * @return true only when this exact epoch is already ACTIVE or this
     * complete exact-current sweep activates it.
     */
    public static synchronized boolean offerCompleteSweep(
            long fingerprint,
            int candidateCount
    ) {
        completeSweepOfferCount++;

        if (candidateCount < 2) {
            onRejectedSweep();
            return false;
        }

        if (activeValid
                && activeCandidateCount == candidateCount
                && activeFingerprint == fingerprint) {
            activeReuseCount++;
            return true;
        }

        if (buildingValid
                && buildingCandidateCount == candidateCount
                && buildingFingerprint == fingerprint) {
            buildingStableSweeps++;
            buildingAdvanceCount++;

            peakBuildingStableSweeps =
                    Math.max(
                            peakBuildingStableSweeps,
                            buildingStableSweeps
                    );

            if (buildingStableSweeps
                    >= REQUIRED_IDENTICAL_COMPLETE_SWEEPS) {
                activeValid = true;
                activeFingerprint = fingerprint;
                activeCandidateCount = candidateCount;
                activationCount++;

                buildingValid = false;
                buildingFingerprint = 0L;
                buildingCandidateCount = 0;
                buildingStableSweeps = 0;
                return true;
            }

            return false;
        }

        if (buildingValid) {
            buildingResetCount++;
        }

        buildingValid = true;
        buildingFingerprint = fingerprint;
        buildingCandidateCount = candidateCount;
        buildingStableSweeps = 1;
        buildingStartCount++;

        peakBuildingStableSweeps =
                Math.max(
                        peakBuildingStableSweeps,
                        buildingStableSweeps
                );

        if (buildingStableSweeps
                >= REQUIRED_IDENTICAL_COMPLETE_SWEEPS) {
            activeValid = true;
            activeFingerprint = fingerprint;
            activeCandidateCount = candidateCount;
            activationCount++;

            buildingValid = false;
            buildingFingerprint = 0L;
            buildingCandidateCount = 0;
            buildingStableSweeps = 0;
            return true;
        }

        return false;
    }

    public static synchronized void onRejectedSweep() {
        rejectedSweepCount++;

        if (buildingValid) {
            buildingResetCount++;
        }

        buildingValid = false;
        buildingFingerprint = 0L;
        buildingCandidateCount = 0;
        buildingStableSweeps = 0;
    }

    public static synchronized void onVisibleCommitSuccess(
            long fingerprint,
            int candidateCount
    ) {
        if (activeValid
                && activeFingerprint == fingerprint
                && activeCandidateCount == candidateCount) {
            commitSuccessCount++;
        }
    }

    public static synchronized void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "vulkanVisibleSealedEpochInstalled",
                true
        );
        report.addProperty(
                "vulkanVisibleSealedEpochMode",
                "EXACT_CURRENT_COMPLETE_SWEEP_EPOCH_NO_REDUNDANT_LAYER_DELAY"
        );
        report.addProperty(
                "vulkanVisibleSealedEpochRequiredIdenticalCompleteSweeps",
                REQUIRED_IDENTICAL_COMPLETE_SWEEPS
        );
        report.addProperty(
                "vulkanVisibleSealedEpochTemporalRedundancyRetired",
                true
        );
        report.addProperty(
                "vulkanVisibleSealedEpochExactCurrentSweepActivation",
                true
        );
        report.addProperty(
                "vulkanVisibleSealedEpochCompleteSweepOfferCount",
                completeSweepOfferCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochRejectedSweepCount",
                rejectedSweepCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochBuildingStartCount",
                buildingStartCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochBuildingAdvanceCount",
                buildingAdvanceCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochBuildingResetCount",
                buildingResetCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochActivationCount",
                activationCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochActiveReuseCount",
                activeReuseCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochCommitSuccessCount",
                commitSuccessCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochPeakBuildingStableSweeps",
                peakBuildingStableSweeps
        );
        report.addProperty(
                "vulkanVisibleSealedEpochBuildingValid",
                buildingValid
        );
        report.addProperty(
                "vulkanVisibleSealedEpochActiveValid",
                activeValid
        );
        report.addProperty(
                "vulkanVisibleSealedEpochActiveCandidateCount",
                activeCandidateCount
        );
        report.addProperty(
                "vulkanVisibleSealedEpochActiveFingerprint",
                Long.toUnsignedString(activeFingerprint)
        );
        report.addProperty(
                "vulkanVisibleSealedEpochRelaxesWholeLayerAtomicity",
                false
        );
        report.addProperty(
                "vulkanVisibleSealedEpochAcceptsStaleGenerations",
                false
        );
        report.addProperty(
                "vulkanVisibleSealedEpochOpenGlFailOpenPreserved",
                true
        );
        report.addProperty(
                "vulkanVisibleSealedEpochGameplayGpuWait",
                false
        );
    }

    private static long mix(
            long fingerprint,
            long value
    ) {
        long mixed = fingerprint;
        mixed ^= value;
        mixed *= FNV_PRIME;
        mixed ^= (value >>> 32);
        mixed *= FNV_PRIME;
        return mixed;
    }
}
