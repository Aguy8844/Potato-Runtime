package dev.ordovicium.potato.render.visibility;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;
import dev.ordovicium.potato.render.lod.PotatoLodRuntime;

/**
 * Conservative Stage-1 loaded-view occlusion.
 *
 * <p>No bounding box is ever assumed to be opaque. Potato measures the samples
 * that passed depth testing for the section's real SOLID draw. A zero-sample
 * result may suppress at most two later draws, and only while camera position,
 * rotation and projection remain nearly unchanged. The third frame is forced
 * to draw again and refresh evidence. Camera jumps therefore fail open.</p>
 */
public final class PotatoTemporalOcclusionRuntime {

    private static final int MINIMUM_DISTANCE_CHUNKS = 10;
    private static final int PRESSURE_2_QUERY_BUDGET = 24;
    private static final int PRESSURE_3_QUERY_BUDGET = 48;
    private static final int PRESSURE_4_QUERY_BUDGET = 16;
    private static final int PRESSURE_4_MAX_CONSECUTIVE_SKIPS = 6;

    /*
     * All query lifecycle callbacks are consumed on Minecraft's render thread.
     * Plain counters avoid striped atomic updates in the already expensive
     * loaded-view section loop while preserving exact process telemetry.
     */
    private static long candidateCount;
    private static long cohortRejectedCount;
    private static long queryBudgetRejectedCount;
    private static long queryStartedCount;
    private static long queryResolvedCount;
    private static long queryOccludedCount;
    private static long queryVisibleCount;
    private static long skippedDrawCount;
    private static long forcedRevalidationCount;
    private static long cameraInvalidationCount;
    private static long queryConflictCount;
    private static long queryObjectCreatedCount;
    private static long queryObjectDeletedCount;
    private static long failureCount;

    private static volatile JsonObject report;
    private static volatile boolean closed = true;
    private static volatile boolean disabledAfterFailure;

    private static PotatoTemporalOcclusionFrame currentFrame;
    private static long layerSequence;
    private static long layerBeginCount;
    private static long layerEndCount;
    private static int queriesIssuedThisLayer;
    private static int peakQueriesIssuedPerLayer;
    private static String lastFailure = "";

    private PotatoTemporalOcclusionRuntime() {
    }

    public static void bindReport(JsonObject newReport) {
        report = newReport;
        closed = false;
        disabledAfterFailure = false;

        if (newReport != null) {
            newReport.addProperty(
                    "potatoTemporalOcclusionRuntimeInstalled",
                    true
            );
        }
    }

    public static boolean enabled() {
        return !closed
                && !disabledAfterFailure
                && !Boolean.getBoolean("potato.occlusion.disabled");
    }

    public static void beginLayer(SectionLayerFrameContext context) {
        currentFrame = null;
        queriesIssuedThisLayer = 0;

        if (!enabled()
                || context == null
                || !context.solidLayer()
                || !context.shaderUsesBlockVertexFormat()
                || !context.hasMatrices()) {
            return;
        }

        int pressure = PotatoLodRuntime.adaptivePressureLevelSnapshot();

        if (pressure < 2) {
            return;
        }

        int maximumQueries;

        if (pressure >= 4) {
            /*
             * Emergency pressure: fewer fresh GL queries, longer conservative
             * reuse of a proven zero-sample result. Camera compatibility still
             * fails open exactly as before.
             */
            maximumQueries =
                    PRESSURE_4_QUERY_BUDGET;
        } else if (pressure >= 3) {
            maximumQueries =
                    PRESSURE_3_QUERY_BUDGET;
        } else {
            maximumQueries =
                    PRESSURE_2_QUERY_BUDGET;
        }

        /*
         * Previous-frame visibility evidence is least trustworthy while the
         * player is moving quickly. Under FAST/EXTREME travel we intentionally
         * issue fewer queries and reuse old zero-sample evidence for fewer
         * frames. This mirrors the research lesson behind current-frame Hi-Z
         * rechecks without pretending that the future Vulkan Hi-Z path exists
         * already.
         */
        maximumQueries =
                PotatoPredictiveStreamingRuntime
                        .motionSafeOcclusionQueryBudget(
                                maximumQueries
                        );

        PotatoTemporalOcclusionFrame frame =
                PotatoTemporalOcclusionFrame.capture(
                        ++layerSequence,
                        context.cameraX(),
                        context.cameraY(),
                        context.cameraZ(),
                        context.modelView(),
                        context.projection(),
                        pressure,
                        maximumQueries
                );

        if (frame != null) {
            currentFrame = frame;
            layerBeginCount++;
        }
    }

    public static PotatoTemporalOcclusionFrame currentFrame() {
        return currentFrame;
    }

    public static boolean isCandidate(
            Object owner,
            int desiredTier,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        PotatoTemporalOcclusionFrame frame = currentFrame;

        if (frame == null
                || desiredTier < 2) {
            return false;
        }

        double centerX = chunkOffsetX + 8.0;
        double centerY = chunkOffsetY + 8.0;
        double centerZ = chunkOffsetZ + 8.0;
        double minimumDistance = MINIMUM_DISTANCE_CHUNKS * 16.0;

        if (centerX * centerX + centerY * centerY + centerZ * centerZ
                < minimumDistance * minimumDistance) {
            return false;
        }

        candidateCount++;

        int identity = System.identityHashCode(owner);

        int mask =
                frame.pressureLevel() >= 4
                        ? 3
                        : frame.pressureLevel() >= 3
                        ? 1
                        : 3;

        if ((identity & mask) != 0) {
            cohortRejectedCount++;
            return false;
        }

        return true;
    }

    public static boolean tryAcquireQuerySlot() {
        PotatoTemporalOcclusionFrame frame = currentFrame;

        if (frame == null
                || queriesIssuedThisLayer >= frame.maximumQueries()) {
            queryBudgetRejectedCount++;
            return false;
        }

        queriesIssuedThisLayer++;
        peakQueriesIssuedPerLayer = Math.max(
                peakQueriesIssuedPerLayer,
                queriesIssuedThisLayer
        );

        return true;
    }

    public static int maximumConsecutiveSkips(
            PotatoTemporalOcclusionFrame frame
    ) {
        if (frame == null) {
            return 0;
        }

        int baseline =
                frame.pressureLevel() >= 4
                        ? PRESSURE_4_MAX_CONSECUTIVE_SKIPS
                        : 2;

        return PotatoPredictiveStreamingRuntime
                .motionSafeOcclusionSkipCap(
                        baseline
                );
    }

    public static void endLayer() {
        if (currentFrame != null) {
            layerEndCount++;
        }

        currentFrame = null;
        queriesIssuedThisLayer = 0;
    }

    public static void onQueryStarted() {
        queryStartedCount++;
    }

    public static void onQueryResolved(boolean occluded) {
        queryResolvedCount++;

        if (occluded) {
            queryOccludedCount++;
        } else {
            queryVisibleCount++;
        }
    }

    public static void onSkippedDraw() {
        skippedDrawCount++;
    }

    public static void onForcedRevalidation() {
        forcedRevalidationCount++;
    }

    public static void onCameraInvalidation() {
        cameraInvalidationCount++;
    }

    public static void onQueryConflict() {
        queryConflictCount++;
    }

    public static void onQueryObjectCreated() {
        queryObjectCreatedCount++;
    }

    public static void onQueryObjectDeleted() {
        queryObjectDeletedCount++;
    }

    public static void disableAfterFailure(Throwable throwable) {
        failureCount++;
        disabledAfterFailure = true;
        currentFrame = null;

        if (throwable != null) {
            lastFailure = throwable.getClass().getName()
                    + ": "
                    + String.valueOf(throwable.getMessage());
        }
    }

    public static boolean verified() {
        return !closed
                && !disabledAfterFailure
                && queryStartedCount > 0
                && queryResolvedCount > 0;
    }

    public static void enrich(JsonObject target) {
        if (target == null) {
            return;
        }

        target.addProperty("potatoTemporalOcclusionRuntimeInstalled", true);
        target.addProperty("potatoTemporalOcclusionEnabled", enabled());
        target.addProperty("potatoTemporalOcclusionMode",
                "ACTUAL_SOLID_DRAW_PRESSURE4_SPARSE_QUERY_EXTENDED_ZERO_SAMPLE_REUSE");
        target.addProperty(
                "potatoTemporalOcclusionRenderHotCountersAtomic",
                false
        );
        target.addProperty("potatoTemporalOcclusionActualGeometryEvidence", true);
        target.addProperty(
                "potatoTemporalOcclusionQueryTarget",
                "GL_ANY_SAMPLES_PASSED_CONSERVATIVE"
        );
        target.addProperty(
                "potatoTemporalOcclusionExactSampleCountingRequired",
                false
        );
        target.addProperty(
                "potatoTemporalOcclusionConservativeVisibilityBias",
                true
        );
        target.addProperty("potatoTemporalOcclusionBoundingBoxOpacityAssumption", false);
        target.addProperty("potatoTemporalOcclusionCameraMotionFailsOpen", true);
        target.addProperty(
                "potatoTemporalOcclusionMaximumConsecutiveSkips",
                PRESSURE_4_MAX_CONSECUTIVE_SKIPS
        );
        target.addProperty("potatoTemporalOcclusionMinimumDistanceChunks", MINIMUM_DISTANCE_CHUNKS);
        target.addProperty("potatoTemporalOcclusionPressure2QueryBudget", PRESSURE_2_QUERY_BUDGET);
        target.addProperty("potatoTemporalOcclusionPressure3QueryBudget", PRESSURE_3_QUERY_BUDGET);
        target.addProperty("potatoTemporalOcclusionPressure4QueryBudget", PRESSURE_4_QUERY_BUDGET);
        target.addProperty("potatoTemporalOcclusionPressure4ExtendedReuse", true);
        target.addProperty(
                "potatoTemporalOcclusionMotionSafeReuseInstalled",
                true
        );
        target.addProperty(
                "potatoTemporalOcclusionMotionSafeSpeedBand",
                PotatoPredictiveStreamingRuntime
                        .speedBand()
                        .name()
        );
        target.addProperty(
                "potatoTemporalOcclusionResearchCurrentFrameHiZRecheck",
                "STAGED_NOT_PRODUCTION_ENABLED"
        );
        target.addProperty("potatoTemporalOcclusionLayerBeginCount", layerBeginCount);
        target.addProperty("potatoTemporalOcclusionLayerEndCount", layerEndCount);
        target.addProperty("potatoTemporalOcclusionCandidateCount", candidateCount);
        target.addProperty("potatoTemporalOcclusionCohortRejectedCount", cohortRejectedCount);
        target.addProperty("potatoTemporalOcclusionQueryBudgetRejectedCount", queryBudgetRejectedCount);
        target.addProperty("potatoTemporalOcclusionQueryStartedCount", queryStartedCount);
        target.addProperty("potatoTemporalOcclusionQueryResolvedCount", queryResolvedCount);
        target.addProperty("potatoTemporalOcclusionQueryOccludedCount", queryOccludedCount);
        target.addProperty("potatoTemporalOcclusionQueryVisibleCount", queryVisibleCount);
        target.addProperty("potatoTemporalOcclusionSkippedDrawCount", skippedDrawCount);
        target.addProperty("potatoTemporalOcclusionForcedRevalidationCount", forcedRevalidationCount);
        target.addProperty("potatoTemporalOcclusionCameraInvalidationCount", cameraInvalidationCount);
        target.addProperty("potatoTemporalOcclusionQueryConflictCount", queryConflictCount);
        target.addProperty("potatoTemporalOcclusionQueryObjectCreatedCount", queryObjectCreatedCount);
        target.addProperty("potatoTemporalOcclusionQueryObjectDeletedCount", queryObjectDeletedCount);
        target.addProperty("potatoTemporalOcclusionPeakQueriesIssuedPerLayer", peakQueriesIssuedPerLayer);
        target.addProperty("potatoTemporalOcclusionFailureCount", failureCount);
        target.addProperty("potatoTemporalOcclusionDisabledAfterFailure", disabledAfterFailure);
        target.addProperty("potatoTemporalOcclusionMutatesChunkLoading", false);
        target.addProperty("potatoTemporalOcclusionMutatesWorldSimulation", false);
        target.addProperty("potatoTemporalOcclusionCancelsOnlyPreviouslyZeroSampleSolidDraws", true);
        target.addProperty("potatoTemporalOcclusionVerified", verified());

        if (!lastFailure.isBlank()) {
            target.addProperty("potatoTemporalOcclusionLastFailure", lastFailure);
        }
    }

    public static void close() {
        JsonObject currentReport = report;

        if (currentReport != null) {
            enrich(currentReport);
            currentReport.addProperty("potatoTemporalOcclusionRuntimeClosed", true);
        }

        currentFrame = null;
        closed = true;
    }
}
