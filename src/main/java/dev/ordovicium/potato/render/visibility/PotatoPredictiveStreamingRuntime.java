package dev.ordovicium.potato.render.visibility;

import com.google.gson.JsonObject;

/**
 * Research-derived, renderer-only motion predictor for terrain work.
 *
 * <p>The runtime turns camera translation into a small set of future chunk
 * footprints and a swept travel corridor. It never creates server chunk
 * tickets, never changes simulation distance and never changes the user's
 * configured render distance. The only active consumers are client mesh
 * scheduling, upload pacing, LOD hysteresis and conservative occlusion.</p>
 *
 * <p>Design influences are deliberately modest and explicit: visibility-based
 * look-ahead/prefetching, ray-guided demand streaming, viewer-centred clipmap
 * stability and soft real-time deadline scheduling. The implementation is not
 * a claim that Minecraft has become an out-of-core voxel engine.</p>
 */
public final class PotatoPredictiveStreamingRuntime {

    public enum SpeedBand {
        IDLE,
        NORMAL,
        FAST,
        EXTREME
    }

    public enum PriorityBand {
        IMMEDIATE,
        PREDICTED_NEAR,
        PREDICTED_FAR,
        NORMAL
    }

    private static final double VELOCITY_EMA_ALPHA = 0.28;
    private static final double TELEPORT_RESET_BLOCKS = 128.0;
    private static final double IDLE_SPEED_BLOCKS_PER_SECOND = 1.5;
    private static final double FAST_SPEED_BLOCKS_PER_SECOND = 18.0;
    private static final double EXTREME_SPEED_BLOCKS_PER_SECOND = 48.0;

    private static final double HORIZON_NEAR_SECONDS = 0.10;
    private static final double HORIZON_MID_SECONDS = 0.30;
    private static final double HORIZON_FAR_BASE_SECONDS = 0.70;
    private static final double HORIZON_FAR_MAX_SECONDS = 1.80;

    private static boolean initialized;
    private static long lastSampleNanos;
    private static double lastX;
    private static double lastY;
    private static double lastZ;
    private static double velocityX;
    private static double velocityY;
    private static double velocityZ;
    private static double horizontalSpeed;
    private static SpeedBand speedBand = SpeedBand.IDLE;

    private static double cameraX;
    private static double cameraY;
    private static double cameraZ;
    private static double predictedNearX;
    private static double predictedNearZ;
    private static double predictedMidX;
    private static double predictedMidZ;
    private static double predictedFarX;
    private static double predictedFarZ;
    private static double currentFarHorizonSeconds = HORIZON_FAR_BASE_SECONDS;

    private static int currentChunkX;
    private static int currentChunkZ;
    private static int predictedNearChunkX;
    private static int predictedNearChunkZ;
    private static int predictedMidChunkX;
    private static int predictedMidChunkZ;
    private static int predictedFarChunkX;
    private static int predictedFarChunkZ;

    private static long sampleCount;
    private static long teleportResetCount;
    private static long speedBandChangeCount;
    private static long classificationCount;
    private static long immediateClassificationCount;
    private static long predictedNearClassificationCount;
    private static long predictedFarClassificationCount;
    private static long normalClassificationCount;
    private static double peakHorizontalSpeed;

    private PotatoPredictiveStreamingRuntime() {
    }

    public static void updateCamera(
            double x,
            double y,
            double z
    ) {
        long now = System.nanoTime();
        sampleCount++;

        cameraX = x;
        cameraY = y;
        cameraZ = z;
        currentChunkX = floorChunk(x);
        currentChunkZ = floorChunk(z);

        if (!initialized) {
            initialized = true;
            lastSampleNanos = now;
            lastX = x;
            lastY = y;
            lastZ = z;
            resetVelocity();
            recomputePrediction();
            return;
        }

        double dt = Math.max(
                0.001,
                Math.min(
                        0.250,
                        (now - lastSampleNanos) / 1_000_000_000.0
                )
        );

        double dx = x - lastX;
        double dy = y - lastY;
        double dz = z - lastZ;
        double displacementSquared = dx * dx + dy * dy + dz * dz;

        if (!Double.isFinite(displacementSquared)
                || displacementSquared
                > TELEPORT_RESET_BLOCKS * TELEPORT_RESET_BLOCKS
                || now - lastSampleNanos > 1_000_000_000L) {
            teleportResetCount++;
            resetVelocity();
        } else {
            double rawVx = dx / dt;
            double rawVy = dy / dt;
            double rawVz = dz / dt;

            if (Double.isFinite(rawVx)
                    && Double.isFinite(rawVy)
                    && Double.isFinite(rawVz)) {
                velocityX += (rawVx - velocityX) * VELOCITY_EMA_ALPHA;
                velocityY += (rawVy - velocityY) * VELOCITY_EMA_ALPHA;
                velocityZ += (rawVz - velocityZ) * VELOCITY_EMA_ALPHA;
            }
        }

        lastSampleNanos = now;
        lastX = x;
        lastY = y;
        lastZ = z;

        horizontalSpeed = Math.sqrt(
                velocityX * velocityX
                        + velocityZ * velocityZ
        );

        if (!Double.isFinite(horizontalSpeed)) {
            resetVelocity();
        }

        peakHorizontalSpeed = Math.max(
                peakHorizontalSpeed,
                horizontalSpeed
        );

        SpeedBand nextBand = classifySpeed(horizontalSpeed);

        if (nextBand != speedBand) {
            speedBand = nextBand;
            speedBandChangeCount++;
        }

        recomputePrediction();
    }

    private static void resetVelocity() {
        velocityX = 0.0;
        velocityY = 0.0;
        velocityZ = 0.0;
        horizontalSpeed = 0.0;
        speedBand = SpeedBand.IDLE;
    }

    private static void recomputePrediction() {
        currentFarHorizonSeconds =
                Math.max(
                        HORIZON_FAR_BASE_SECONDS,
                        Math.min(
                                HORIZON_FAR_MAX_SECONDS,
                                HORIZON_FAR_BASE_SECONDS
                                        + horizontalSpeed / 80.0
                        )
                );

        predictedNearX = cameraX + velocityX * HORIZON_NEAR_SECONDS;
        predictedNearZ = cameraZ + velocityZ * HORIZON_NEAR_SECONDS;
        predictedMidX = cameraX + velocityX * HORIZON_MID_SECONDS;
        predictedMidZ = cameraZ + velocityZ * HORIZON_MID_SECONDS;
        predictedFarX = cameraX + velocityX * currentFarHorizonSeconds;
        predictedFarZ = cameraZ + velocityZ * currentFarHorizonSeconds;

        predictedNearChunkX = floorChunk(predictedNearX);
        predictedNearChunkZ = floorChunk(predictedNearZ);
        predictedMidChunkX = floorChunk(predictedMidX);
        predictedMidChunkZ = floorChunk(predictedMidZ);
        predictedFarChunkX = floorChunk(predictedFarX);
        predictedFarChunkZ = floorChunk(predictedFarZ);
    }

    public static PriorityBand classifyChunk(
            int chunkX,
            int chunkZ
    ) {
        classificationCount++;

        if (chebyshevDistance(
                chunkX,
                chunkZ,
                currentChunkX,
                currentChunkZ
        ) <= 1) {
            immediateClassificationCount++;
            return PriorityBand.IMMEDIATE;
        }

        int nearRadius =
                speedBand == SpeedBand.EXTREME
                        ? 2
                        : 1;

        if (chebyshevDistance(
                chunkX,
                chunkZ,
                predictedNearChunkX,
                predictedNearChunkZ
        ) <= nearRadius
                || chebyshevDistance(
                chunkX,
                chunkZ,
                predictedMidChunkX,
                predictedMidChunkZ
        ) <= nearRadius
                || corridorDistanceBlocks(chunkX, chunkZ)
                <= nearCorridorRadiusBlocks()) {
            predictedNearClassificationCount++;
            return PriorityBand.PREDICTED_NEAR;
        }

        int farRadius =
                speedBand == SpeedBand.EXTREME
                        ? 3
                        : speedBand == SpeedBand.FAST
                        ? 2
                        : 1;

        if (chebyshevDistance(
                chunkX,
                chunkZ,
                predictedFarChunkX,
                predictedFarChunkZ
        ) <= farRadius
                || corridorDistanceBlocks(chunkX, chunkZ)
                <= farCorridorRadiusBlocks()) {
            predictedFarClassificationCount++;
            return PriorityBand.PREDICTED_FAR;
        }

        normalClassificationCount++;
        return PriorityBand.NORMAL;
    }

    private static double corridorDistanceBlocks(
            int chunkX,
            int chunkZ
    ) {
        if (horizontalSpeed < IDLE_SPEED_BLOCKS_PER_SECOND) {
            return Double.POSITIVE_INFINITY;
        }

        double px = chunkX * 16.0 + 8.0;
        double pz = chunkZ * 16.0 + 8.0;
        double ax = cameraX;
        double az = cameraZ;
        double bx = predictedFarX;
        double bz = predictedFarZ;
        double vx = bx - ax;
        double vz = bz - az;
        double lengthSquared = vx * vx + vz * vz;

        if (!(lengthSquared > 1.0)) {
            return Double.POSITIVE_INFINITY;
        }

        double t = ((px - ax) * vx + (pz - az) * vz) / lengthSquared;

        if (t < -0.05 || t > 1.15) {
            return Double.POSITIVE_INFINITY;
        }

        t = Math.max(0.0, Math.min(1.0, t));

        double closestX = ax + vx * t;
        double closestZ = az + vz * t;
        double dx = px - closestX;
        double dz = pz - closestZ;

        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double nearCorridorRadiusBlocks() {
        return switch (speedBand) {
            case EXTREME -> 28.0;
            case FAST -> 22.0;
            case NORMAL -> 16.0;
            case IDLE -> 0.0;
        };
    }

    private static double farCorridorRadiusBlocks() {
        return switch (speedBand) {
            case EXTREME -> 48.0;
            case FAST -> 36.0;
            case NORMAL -> 24.0;
            case IDLE -> 0.0;
        };
    }

    public static int predictiveSyncBudgetPerCompilePass() {
        return switch (speedBand) {
            case EXTREME -> 2;
            case FAST -> 1;
            case NORMAL, IDLE -> 0;
        };
    }

    public static long uploadBudgetBoostNanos() {
        return switch (speedBand) {
            case EXTREME -> 1_000_000L;
            case FAST -> 500_000L;
            case NORMAL, IDLE -> 0L;
        };
    }

    public static int lodMotionInsetChunks() {
        return switch (speedBand) {
            case EXTREME -> 2;
            case FAST -> 1;
            case NORMAL, IDLE -> 0;
        };
    }

    public static double lodRecoveryHysteresisScale() {
        return switch (speedBand) {
            case EXTREME -> 0.82;
            case FAST -> 0.86;
            case NORMAL, IDLE -> 0.90;
        };
    }

    public static int motionSafeOcclusionSkipCap(
            int baseline
    ) {
        int cap = switch (speedBand) {
            case EXTREME -> 1;
            case FAST -> 2;
            case NORMAL, IDLE -> baseline;
        };

        return Math.max(0, Math.min(baseline, cap));
    }

    public static int motionSafeOcclusionQueryBudget(
            int baseline
    ) {
        int cap = switch (speedBand) {
            case EXTREME -> 8;
            case FAST -> 12;
            case NORMAL, IDLE -> baseline;
        };

        return Math.max(1, Math.min(baseline, cap));
    }

    public static SpeedBand speedBand() {
        return speedBand;
    }

    public static double horizontalSpeedBlocksPerSecond() {
        return horizontalSpeed;
    }

    public static void enrich(
            JsonObject target
    ) {
        if (target == null) {
            return;
        }

        target.addProperty(
                "potatoPredictiveResidencyInstalled",
                true
        );
        target.addProperty(
                "potatoPredictiveResidencyMode",
                "TIME_TO_VISIBLE_100_300_ADAPTIVE_700_1800MS_SWEPT_CORRIDOR"
        );
        target.addProperty(
                "potatoPredictiveResidencySpeedBand",
                speedBand.name()
        );
        target.addProperty(
                "potatoPredictiveResidencyHorizontalSpeedBlocksPerSecond",
                horizontalSpeed
        );
        target.addProperty(
                "potatoPredictiveResidencyPeakHorizontalSpeedBlocksPerSecond",
                peakHorizontalSpeed
        );
        target.addProperty(
                "potatoPredictiveResidencyFarHorizonMillis",
                currentFarHorizonSeconds * 1000.0
        );
        target.addProperty(
                "potatoPredictiveResidencySampleCount",
                sampleCount
        );
        target.addProperty(
                "potatoPredictiveResidencyTeleportResetCount",
                teleportResetCount
        );
        target.addProperty(
                "potatoPredictiveResidencySpeedBandChangeCount",
                speedBandChangeCount
        );
        target.addProperty(
                "potatoPredictiveResidencyClassificationCount",
                classificationCount
        );
        target.addProperty(
                "potatoPredictiveResidencyImmediateClassificationCount",
                immediateClassificationCount
        );
        target.addProperty(
                "potatoPredictiveResidencyNearClassificationCount",
                predictedNearClassificationCount
        );
        target.addProperty(
                "potatoPredictiveResidencyFarClassificationCount",
                predictedFarClassificationCount
        );
        target.addProperty(
                "potatoPredictiveResidencyNormalClassificationCount",
                normalClassificationCount
        );
        target.addProperty(
                "potatoPredictiveResidencyCurrentChunkX",
                currentChunkX
        );
        target.addProperty(
                "potatoPredictiveResidencyCurrentChunkZ",
                currentChunkZ
        );
        target.addProperty(
                "potatoPredictiveResidencyPredictedFarChunkX",
                predictedFarChunkX
        );
        target.addProperty(
                "potatoPredictiveResidencyPredictedFarChunkZ",
                predictedFarChunkZ
        );
        target.addProperty(
                "potatoPredictiveResidencyMutatesServerChunkTickets",
                false
        );
        target.addProperty(
                "potatoPredictiveResidencyMutatesWorldSimulation",
                false
        );
        target.addProperty(
                "potatoPredictiveResidencyMutatesRenderDistance",
                false
        );

        /*
         * Research roadmap is explicit so telemetry never confuses an idea we
         * derived from a paper with a feature that already owns production
         * pixels.
         */
        target.addProperty(
                "potatoResearchGpuHiZCurrentFrameRecheck",
                "STAGED_NOT_PRODUCTION_ENABLED"
        );
        target.addProperty(
                "potatoResearchVisibilityBuffer",
                "STAGED_NOT_PRODUCTION_ENABLED"
        );
        target.addProperty(
                "potatoResearchCompressedMeshlets",
                "STAGED_NOT_PRODUCTION_ENABLED"
        );
        target.addProperty(
                "potatoResearchMeshShaderFastPath",
                "STAGED_CAPABILITY_DEPENDENT_NOT_PRODUCTION_ENABLED"
        );
        target.addProperty(
                "potatoResearchProductionRegionIndirectSolid",
                "NEXT_ATOMIC_VULKAN_OWNERSHIP_CUTOVER"
        );
    }

    private static SpeedBand classifySpeed(
            double speed
    ) {
        if (speed < IDLE_SPEED_BLOCKS_PER_SECOND) {
            return SpeedBand.IDLE;
        }
        if (speed < FAST_SPEED_BLOCKS_PER_SECOND) {
            return SpeedBand.NORMAL;
        }
        if (speed < EXTREME_SPEED_BLOCKS_PER_SECOND) {
            return SpeedBand.FAST;
        }

        return SpeedBand.EXTREME;
    }

    private static int floorChunk(
            double coordinate
    ) {
        return ((int) Math.floor(coordinate)) >> 4;
    }

    private static int chebyshevDistance(
            int ax,
            int az,
            int bx,
            int bz
    ) {
        return Math.max(
                Math.abs(ax - bx),
                Math.abs(az - bz)
        );
    }
}
