package dev.ordovicium.potato.settings;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.lod.PotatoLodProfile;
import dev.ordovicium.potato.render.lod.PotatoLodRuntime;
import net.minecraft.client.Minecraft;

/**
 * Frame-paced view-pressure observer for Potato's AUTO / dynamic modes.
 *
 * <p>Patch 079 deliberately stops changing Minecraft's live render-distance
 * option. OptionInstance#set on render distance causes the vanilla section
 * renderer to rebuild its world view, which is far more expensive than the
 * frame time the governor was trying to save. The controller now computes a
 * soft target only. A later Potato-owned visibility/LOD layer can consume that
 * target without forcing LevelRenderer to recreate the chunk render graph.</p>
 */
public final class PotatoAdaptiveViewController {
    private static final long DISCONTINUITY_NANOS =
            250_000_000L;

    private static final long WORLD_ENTRY_GRACE_NANOS =
            20_000_000_000L;

    private static final long POST_ADJUSTMENT_SETTLE_NANOS =
            10_000_000_000L;

    private static final long DOWNSHIFT_COOLDOWN_NANOS =
            12_000_000_000L;

    private static final long UPSHIFT_COOLDOWN_NANOS =
            24_000_000_000L;

    private static final int WINDOW_SAMPLES =
            180;

    private static final int PRESSURE_WINDOWS_FOR_DOWNSHIFT =
            2;

    private static final int FAST_WINDOWS_FOR_UPSHIFT =
            4;

    private static long lastFrameNanos;
    private static long lastAdjustmentNanos;
    private static long worldEntryNanos;

    private static double frameEmaMillis;
    private static double windowMillis;
    private static int windowSamples;
    private static int consecutivePressureWindows;
    private static int consecutiveFastWindows;

    private static boolean dynamicActive;
    private static int userRenderDistanceCeiling =
            -1;
    private static int observedVanillaRenderDistance =
            -1;
    private static int softTargetChunks =
            -1;

    private static PotatoLodProfile lastAppliedLodProfile;
    private static Object observedLevelIdentity;

    private static long validFrameSampleCount;
    private static long discontinuityCount;
    private static long evaluationCount;
    private static long downshiftCount;
    private static long upshiftCount;
    private static long externalRenderDistanceChangeCount;
    private static long worldTransitionCount;
    private static long graceSkipCount;
    private static long settleSkipCount;
    private static long vanillaRenderDistanceMutationCount;

    private static int minimumSoftTargetChunks =
            Integer.MAX_VALUE;
    private static int maximumSoftTargetChunks;

    private PotatoAdaptiveViewController() {
    }

    public static void onSolidFrame() {
        long now = System.nanoTime();
        PotatoRuntimeSettings.Snapshot settings =
                PotatoRuntimeSettings.snapshot();

        applyLodProfile(settings.lodProfile());

        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft == null
                || minecraft.options == null) {
            resetFrameClock(now);
            return;
        }

        if (!PotatoRuntimeSettings.dynamicChunksEffective()) {
            leaveDynamicMode();
            resetControllerState(now, false);
            return;
        }

        observeLevelLifecycle(
                minecraft,
                now
        );
        enterDynamicModeIfNeeded(
                minecraft,
                now
        );
        observeExternalRenderDistanceChange(
                minecraft,
                now
        );

        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return;
        }

        long elapsed = now - lastFrameNanos;
        lastFrameNanos = now;

        if (elapsed <= 0L
                || elapsed > DISCONTINUITY_NANOS) {
            discontinuityCount++;
            resetWindow();
            clearWindowClassifiers();
            return;
        }

        double millis =
                elapsed / 1_000_000.0;

        if (millis < 1.0) {
            return;
        }

        validFrameSampleCount++;

        frameEmaMillis =
                frameEmaMillis <= 0.0
                        ? millis
                        : frameEmaMillis * 0.93
                        + millis * 0.07;

        windowMillis += millis;
        windowSamples++;

        if (windowSamples < WINDOW_SAMPLES) {
            return;
        }

        evaluate(
                settings,
                now
        );
    }

    public static void applySettingsNow() {
        PotatoRuntimeSettings.Snapshot settings =
                PotatoRuntimeSettings.snapshot();

        applyLodProfile(settings.lodProfile());

        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft == null
                || minecraft.options == null) {
            return;
        }

        long now = System.nanoTime();

        if (!PotatoRuntimeSettings.dynamicChunksEffective()) {
            leaveDynamicMode();
            resetControllerState(now, false);
            return;
        }

        observeLevelLifecycle(
                minecraft,
                now
        );
        enterDynamicModeIfNeeded(
                minecraft,
                now
        );
        resetControllerState(now, true);
    }

    public static int softTargetChunks() {
        return softTargetChunks;
    }

    public static void enrich(JsonObject report) {
        if (report == null) {
            return;
        }

        report.addProperty(
                "potatoAdaptiveViewInstalled",
                true
        );
        report.addProperty(
                "potatoAdaptiveViewMode",
                "SOFT_TARGET_NO_VANILLA_REBUILD"
        );
        report.addProperty(
                "potatoAdaptiveViewDynamicActive",
                dynamicActive
        );
        report.addProperty(
                "potatoAdaptiveViewUserRenderDistanceCeiling",
                userRenderDistanceCeiling
        );
        report.addProperty(
                "potatoAdaptiveViewLastControllerRenderDistance",
                softTargetChunks
        );
        report.addProperty(
                "potatoAdaptiveViewSoftTargetChunks",
                softTargetChunks
        );
        report.addProperty(
                "potatoAdaptiveViewVanillaRenderDistance",
                observedVanillaRenderDistance
        );
        report.addProperty(
                "potatoAdaptiveViewVanillaRenderDistanceMutationCount",
                vanillaRenderDistanceMutationCount
        );
        report.addProperty(
                "potatoAdaptiveViewVanillaRenderDistanceMutationDisabled",
                true
        );
        report.addProperty(
                "potatoAdaptiveViewFrameEmaMillis",
                frameEmaMillis
        );
        report.addProperty(
                "potatoAdaptiveViewValidFrameSampleCount",
                validFrameSampleCount
        );
        report.addProperty(
                "potatoAdaptiveViewDiscontinuityCount",
                discontinuityCount
        );
        report.addProperty(
                "potatoAdaptiveViewEvaluationCount",
                evaluationCount
        );
        report.addProperty(
                "potatoAdaptiveViewDownshiftCount",
                downshiftCount
        );
        report.addProperty(
                "potatoAdaptiveViewUpshiftCount",
                upshiftCount
        );
        report.addProperty(
                "potatoAdaptiveViewRestoredUserDistanceCount",
                0
        );
        report.addProperty(
                "potatoAdaptiveViewExternalRenderDistanceChangeCount",
                externalRenderDistanceChangeCount
        );
        report.addProperty(
                "potatoAdaptiveViewWorldTransitionCount",
                worldTransitionCount
        );
        report.addProperty(
                "potatoAdaptiveViewGraceSkipCount",
                graceSkipCount
        );
        report.addProperty(
                "potatoAdaptiveViewSettleSkipCount",
                settleSkipCount
        );
        report.addProperty(
                "potatoAdaptiveViewWorldEntryGraceMillis",
                WORLD_ENTRY_GRACE_NANOS / 1_000_000L
        );
        report.addProperty(
                "potatoAdaptiveViewPostAdjustmentSettleMillis",
                POST_ADJUSTMENT_SETTLE_NANOS / 1_000_000L
        );
        report.addProperty(
                "potatoAdaptiveViewMinimumAppliedChunks",
                minimumSoftTargetChunks
                        == Integer.MAX_VALUE
                        ? 0
                        : minimumSoftTargetChunks
        );
        report.addProperty(
                "potatoAdaptiveViewMaximumAppliedChunks",
                maximumSoftTargetChunks
        );
        report.addProperty(
                "potatoAdaptiveViewMinimumSoftTargetChunks",
                minimumSoftTargetChunks
                        == Integer.MAX_VALUE
                        ? 0
                        : minimumSoftTargetChunks
        );
        report.addProperty(
                "potatoAdaptiveViewMaximumSoftTargetChunks",
                maximumSoftTargetChunks
        );
        report.addProperty(
                "potatoAdaptiveViewMutatesSimulationDistance",
                false
        );
        report.addProperty(
                "potatoAdaptiveViewPersistsTransientDistanceChanges",
                false
        );
    }

    private static void evaluate(
            PotatoRuntimeSettings.Snapshot settings,
            long now
    ) {
        evaluationCount++;

        double averageMillis =
                windowMillis
                        / Math.max(
                                1,
                                windowSamples
                        );

        resetWindow();

        double targetMillis =
                1000.0
                        / Math.max(
                                30,
                                settings.targetFps()
                        );

        int effectiveMaximum =
                PotatoRuntimeSettings.effectiveMaximumChunks();

        if (userRenderDistanceCeiling > 0) {
            effectiveMaximum =
                    Math.min(
                            effectiveMaximum,
                            userRenderDistanceCeiling
                    );
        }

        int effectiveMinimum =
                Math.min(
                        PotatoRuntimeSettings.effectiveMinimumChunks(),
                        effectiveMaximum
                );

        int current =
                softTargetChunks > 0
                        ? softTargetChunks
                        : effectiveMaximum;

        current = Math.max(
                effectiveMinimum,
                Math.min(
                        effectiveMaximum,
                        current
                )
        );

        if (worldEntryNanos > 0L
                && now - worldEntryNanos
                < WORLD_ENTRY_GRACE_NANOS) {
            graceSkipCount++;
            clearWindowClassifiers();
            return;
        }

        if (lastAdjustmentNanos > 0L
                && now - lastAdjustmentNanos
                < POST_ADJUSTMENT_SETTLE_NANOS) {
            settleSkipCount++;
            clearWindowClassifiers();
            return;
        }

        boolean severePressure =
                averageMillis > targetMillis * 1.55
                        || frameEmaMillis
                        > targetMillis * 1.65;

        boolean sustainedPressure =
                averageMillis > targetMillis * 1.22
                        || frameEmaMillis
                        > targetMillis * 1.30;

        boolean comfortablyFast =
                averageMillis < targetMillis * 0.82
                        && frameEmaMillis
                        < targetMillis * 0.90;

        if (sustainedPressure) {
            consecutivePressureWindows++;
            consecutiveFastWindows = 0;
        } else if (comfortablyFast) {
            consecutiveFastWindows++;
            consecutivePressureWindows = 0;
        } else {
            clearWindowClassifiers();
            return;
        }

        if (consecutivePressureWindows
                >= PRESSURE_WINDOWS_FOR_DOWNSHIFT
                && now - lastAdjustmentNanos
                >= DOWNSHIFT_COOLDOWN_NANOS
                && current > effectiveMinimum) {
            int step = severePressure
                    ? 2
                    : 1;

            setSoftTarget(
                    Math.max(
                            effectiveMinimum,
                            current - step
                    ),
                    now
            );

            downshiftCount++;
            clearWindowClassifiers();
            return;
        }

        if (consecutiveFastWindows
                < FAST_WINDOWS_FOR_UPSHIFT
                || now - lastAdjustmentNanos
                < UPSHIFT_COOLDOWN_NANOS
                || current >= effectiveMaximum) {
            return;
        }

        setSoftTarget(
                Math.min(
                        effectiveMaximum,
                        current + 1
                ),
                now
        );

        upshiftCount++;
        clearWindowClassifiers();
    }

    private static void observeLevelLifecycle(
            Minecraft minecraft,
            long now
    ) {
        Object levelIdentity = minecraft.level;

        if (levelIdentity == null) {
            if (observedLevelIdentity != null) {
                observedLevelIdentity = null;
                leaveDynamicMode();
                resetControllerState(now, false);
            }
            return;
        }

        if (levelIdentity != observedLevelIdentity) {
            observedLevelIdentity = levelIdentity;
            worldEntryNanos = now;
            worldTransitionCount++;

            dynamicActive = false;
            userRenderDistanceCeiling = -1;
            observedVanillaRenderDistance = -1;
            softTargetChunks = -1;

            resetControllerState(now, true);
        }
    }

    private static void enterDynamicModeIfNeeded(
            Minecraft minecraft,
            long now
    ) {
        if (dynamicActive) {
            return;
        }

        int current =
                minecraft.options
                        .renderDistance()
                        .get();

        dynamicActive = true;
        userRenderDistanceCeiling = current;
        observedVanillaRenderDistance = current;
        softTargetChunks = Math.min(
                current,
                PotatoRuntimeSettings.effectiveMaximumChunks()
        );
        lastAdjustmentNanos = now;
        recordSoftTarget(softTargetChunks);
        resetWindow();
        clearWindowClassifiers();
        resetFrameClock(now);
    }

    private static void leaveDynamicMode() {
        if (!dynamicActive) {
            return;
        }

        dynamicActive = false;
        userRenderDistanceCeiling = -1;
        observedVanillaRenderDistance = -1;
        softTargetChunks = -1;
        clearWindowClassifiers();
        resetWindow();
    }

    private static void observeExternalRenderDistanceChange(
            Minecraft minecraft,
            long now
    ) {
        int current =
                minecraft.options
                        .renderDistance()
                        .get();

        if (observedVanillaRenderDistance < 0) {
            observedVanillaRenderDistance = current;
            return;
        }

        if (current == observedVanillaRenderDistance) {
            return;
        }

        externalRenderDistanceChangeCount++;
        observedVanillaRenderDistance = current;
        userRenderDistanceCeiling = current;

        int effectiveMaximum = Math.min(
                PotatoRuntimeSettings.effectiveMaximumChunks(),
                current
        );
        int effectiveMinimum = Math.min(
                PotatoRuntimeSettings.effectiveMinimumChunks(),
                effectiveMaximum
        );

        softTargetChunks = Math.max(
                effectiveMinimum,
                Math.min(
                        effectiveMaximum,
                        softTargetChunks > 0
                                ? softTargetChunks
                                : effectiveMaximum
                )
        );
        recordSoftTarget(softTargetChunks);
        lastAdjustmentNanos = now;
        clearWindowClassifiers();
        resetWindow();
        resetFrameClock(now);
    }

    private static void setSoftTarget(
            int chunks,
            long now
    ) {
        if (softTargetChunks == chunks) {
            return;
        }

        softTargetChunks = chunks;
        recordSoftTarget(chunks);
        lastAdjustmentNanos = now;
        clearWindowClassifiers();
        resetWindow();
        resetFrameClock(now);
    }

    private static void recordSoftTarget(int chunks) {
        if (chunks <= 0) {
            return;
        }

        minimumSoftTargetChunks =
                Math.min(
                        minimumSoftTargetChunks,
                        chunks
                );
        maximumSoftTargetChunks =
                Math.max(
                        maximumSoftTargetChunks,
                        chunks
                );
    }

    private static void applyLodProfile(
            PotatoLodProfile desired
    ) {
        if (desired == null
                || desired == lastAppliedLodProfile) {
            return;
        }

        PotatoLodRuntime.setProfile(desired);
        lastAppliedLodProfile = desired;
    }

    private static void resetControllerState(
            long now,
            boolean preserveWorldGrace
    ) {
        lastFrameNanos = now;
        lastAdjustmentNanos = now;
        frameEmaMillis = 0.0;
        if (!preserveWorldGrace) {
            worldEntryNanos = 0L;
        }
        clearWindowClassifiers();
        resetWindow();
    }

    private static void clearWindowClassifiers() {
        consecutivePressureWindows = 0;
        consecutiveFastWindows = 0;
    }

    private static void resetFrameClock(long now) {
        lastFrameNanos = now;
    }

    private static void resetWindow() {
        windowMillis = 0.0;
        windowSamples = 0;
    }
}
