package dev.ordovicium.potato.render.visibility;

import com.google.gson.JsonObject;
import org.joml.Matrix4f;

/**
 * Short, quality-preserving frame-pacing relief for abrupt camera reversals.
 *
 * <p>Large render distances can expose a very different set of section draws
 * in one frame when the camera turns sharply. Potato already builds distant
 * LOD proxies asynchronously. This governor only asks the existing LOD path to
 * prefer those already-built proxies for a few SOLID layers after a sharp
 * rotation. If a proxy is missing, the normal full-detail draw remains the
 * fallback.</p>
 *
 * <p>No chunk loading, render distance, world simulation or GPU resource
 * ownership is changed here.</p>
 */
public final class PotatoViewTurnRelief {
    private static final boolean ENABLED =
            Boolean.parseBoolean(
                    System.getProperty(
                            "potato.turnRelief.enabled",
                            "true"
                    )
            );

    private static final float ROTATION_DELTA_THRESHOLD =
            boundedFloatProperty(
                    "potato.turnRelief.rotationDelta",
                    0.80f,
                    0.20f,
                    2.80f
            );

    /*
     * Patch 142: the 141 turn-back run still collapsed to about 10 FPS.
     * At emergency pressure level 4 the ordinary LOD thresholds were already
     * around 6/10 chunks, so the historical 10-layer 10/18 burst was often
     * weaker than the steady-state policy. Use a short but materially stronger
     * already-built-proxy burst while the newly revealed view becomes hot.
     */
    private static final int RELIEF_SOLID_LAYERS =
            boundedIntegerProperty(
                    "potato.turnRelief.layers",
                    36,
                    1,
                    48
            );

    private static final int LOD1_START_CHUNKS =
            boundedIntegerProperty(
                    "potato.turnRelief.lod1StartChunks",
                    3,
                    3,
                    48
            );

    private static final int LOD2_START_CHUNKS =
            boundedIntegerProperty(
                    "potato.turnRelief.lod2StartChunks",
                    5,
                    LOD1_START_CHUNKS + 2,
                    64
            );

    private static boolean previousRotationValid;

    private static float previousM00;
    private static float previousM01;
    private static float previousM02;
    private static float previousM10;
    private static float previousM11;
    private static float previousM12;
    private static float previousM20;
    private static float previousM21;
    private static float previousM22;

    private static int reliefLayersRemaining;

    private static long solidLayerObservationCount;
    private static long abruptTurnDetectedCount;
    private static long reliefSelectionCount;
    private static long reliefTier1SelectionCount;
    private static long reliefTier2SelectionCount;
    private static long reliefActiveLayerCount;
    private static float peakRotationDelta;

    private PotatoViewTurnRelief() {
    }

    public static void resetForRuntime() {
        previousRotationValid = false;

        previousM00 = 0.0f;
        previousM01 = 0.0f;
        previousM02 = 0.0f;
        previousM10 = 0.0f;
        previousM11 = 0.0f;
        previousM12 = 0.0f;
        previousM20 = 0.0f;
        previousM21 = 0.0f;
        previousM22 = 0.0f;

        reliefLayersRemaining = 0;

        solidLayerObservationCount = 0L;
        abruptTurnDetectedCount = 0L;
        reliefSelectionCount = 0L;
        reliefTier1SelectionCount = 0L;
        reliefTier2SelectionCount = 0L;
        reliefActiveLayerCount = 0L;
        peakRotationDelta = 0.0f;
    }

    public static void observeSolidLayer(
            Matrix4f modelView
    ) {
        solidLayerObservationCount++;

        if (!ENABLED || modelView == null) {
            return;
        }

        float m00 = modelView.m00();
        float m01 = modelView.m01();
        float m02 = modelView.m02();
        float m10 = modelView.m10();
        float m11 = modelView.m11();
        float m12 = modelView.m12();
        float m20 = modelView.m20();
        float m21 = modelView.m21();
        float m22 = modelView.m22();

        if (previousRotationValid) {
            float d00 = m00 - previousM00;
            float d01 = m01 - previousM01;
            float d02 = m02 - previousM02;
            float d10 = m10 - previousM10;
            float d11 = m11 - previousM11;
            float d12 = m12 - previousM12;
            float d20 = m20 - previousM20;
            float d21 = m21 - previousM21;
            float d22 = m22 - previousM22;

            float delta =
                    (float) Math.sqrt(
                            d00 * d00
                                    + d01 * d01
                                    + d02 * d02
                                    + d10 * d10
                                    + d11 * d11
                                    + d12 * d12
                                    + d20 * d20
                                    + d21 * d21
                                    + d22 * d22
                    );

            peakRotationDelta =
                    Math.max(
                            peakRotationDelta,
                            delta
                    );

            if (delta >= ROTATION_DELTA_THRESHOLD) {
                abruptTurnDetectedCount++;

                reliefLayersRemaining =
                        Math.max(
                                reliefLayersRemaining,
                                RELIEF_SOLID_LAYERS
                        );
            }
        }

        previousM00 = m00;
        previousM01 = m01;
        previousM02 = m02;
        previousM10 = m10;
        previousM11 = m11;
        previousM12 = m12;
        previousM20 = m20;
        previousM21 = m21;
        previousM22 = m22;

        previousRotationValid = true;

        if (reliefLayersRemaining > 0) {
            reliefActiveLayerCount++;
        }
    }

    public static int applyTier(
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ,
            int desiredTier
    ) {
        if (!ENABLED
                || reliefLayersRemaining <= 0) {
            return desiredTier;
        }

        float centerX =
                chunkOffsetX + 8.0f;

        float centerY =
                chunkOffsetY + 8.0f;

        float centerZ =
                chunkOffsetZ + 8.0f;

        double distanceSquared =
                centerX * centerX
                        + centerY * centerY
                        + centerZ * centerZ;

        double lod2Blocks =
                LOD2_START_CHUNKS * 16.0;

        double lod1Blocks =
                LOD1_START_CHUNKS * 16.0;

        int boostedTier =
                desiredTier;

        if (distanceSquared
                >= lod2Blocks * lod2Blocks) {
            boostedTier =
                    Math.max(
                            boostedTier,
                            2
                    );
        } else if (
                distanceSquared
                        >= lod1Blocks * lod1Blocks
        ) {
            boostedTier =
                    Math.max(
                            boostedTier,
                            1
                    );
        }

        if (boostedTier > desiredTier) {
            reliefSelectionCount++;

            if (boostedTier >= 2) {
                reliefTier2SelectionCount++;
            } else {
                reliefTier1SelectionCount++;
            }
        }

        return boostedTier;
    }

    public static void endSolidLayer() {
        if (reliefLayersRemaining > 0) {
            reliefLayersRemaining--;
        }
    }

    public static void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        report.addProperty(
                "potatoViewTurnReliefInstalled",
                true
        );
        report.addProperty(
                "potatoViewTurnReliefEnabled",
                ENABLED
        );
        report.addProperty(
                "potatoViewTurnReliefMode",
                "ABRUPT_ROTATION_EXISTING_LOD_PROXY_BURST_36L_3_5_WORKING_SET"
        );
        report.addProperty(
                "potatoViewTurnReliefRotationDeltaThreshold",
                ROTATION_DELTA_THRESHOLD
        );
        report.addProperty(
                "potatoViewTurnReliefSolidLayers",
                RELIEF_SOLID_LAYERS
        );
        report.addProperty(
                "potatoViewTurnReliefLod1StartChunks",
                LOD1_START_CHUNKS
        );
        report.addProperty(
                "potatoViewTurnReliefLod2StartChunks",
                LOD2_START_CHUNKS
        );
        report.addProperty(
                "potatoViewTurnReliefSolidLayerObservationCount",
                solidLayerObservationCount
        );
        report.addProperty(
                "potatoViewTurnReliefAbruptTurnDetectedCount",
                abruptTurnDetectedCount
        );
        report.addProperty(
                "potatoViewTurnReliefReliefSelectionCount",
                reliefSelectionCount
        );
        report.addProperty(
                "potatoViewTurnReliefTier1SelectionCount",
                reliefTier1SelectionCount
        );
        report.addProperty(
                "potatoViewTurnReliefTier2SelectionCount",
                reliefTier2SelectionCount
        );
        report.addProperty(
                "potatoViewTurnReliefActiveLayerCount",
                reliefActiveLayerCount
        );
        report.addProperty(
                "potatoViewTurnReliefPeakRotationDelta",
                peakRotationDelta
        );
        report.addProperty(
                "potatoViewTurnReliefLayersRemaining",
                reliefLayersRemaining
        );
        report.addProperty(
                "potatoViewTurnReliefMutatesRenderDistance",
                false
        );
        report.addProperty(
                "potatoViewTurnReliefRequiresExistingProxy",
                true
        );
        report.addProperty(
                "potatoViewTurnReliefBaselineFallbackPreserved",
                true
        );
    }

    private static int boundedIntegerProperty(
            String name,
            int fallback,
            int minimum,
            int maximum
    ) {
        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        Integer.getInteger(
                                name,
                                fallback
                        )
                )
        );
    }

    private static float boundedFloatProperty(
            String name,
            float fallback,
            float minimum,
            float maximum
    ) {
        String raw =
                System.getProperty(
                        name
                );

        if (raw == null
                || raw.isBlank()) {
            return fallback;
        }

        try {
            float value =
                    Float.parseFloat(
                            raw
                    );

            if (!Float.isFinite(value)) {
                return fallback;
            }

            return Math.max(
                    minimum,
                    Math.min(
                            maximum,
                            value
                    )
            );
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
    /**
     * True while the short abrupt-rotation relief window is active.
     *
     * <p>Patch 081a exposes this read-only state so optional renderer
     * work can yield without duplicating or weakening the detector.</p>
     */
    public static boolean active() {
        return (ENABLED)
                && ((reliefLayersRemaining) > 0);
    }
}
