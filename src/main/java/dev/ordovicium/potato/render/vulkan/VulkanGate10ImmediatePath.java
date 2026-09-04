package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.lwjgl.opengl.GL11C.glGetInteger;
import static org.lwjgl.opengl.GL30C.GL_DRAW_FRAMEBUFFER_BINDING;

/**
 * Gate-10 observer for Minecraft's dynamic immediate renderer paths.
 *
 * <p>Entities, particles, HUD and Screen rendering remain OpenGL-authoritative
 * in Stage 095. The purpose of this class is to prove all four production
 * boundaries and expose one fail-open ownership contract before any dynamic
 * OpenGL draw is eligible for suppression.</p>
 */
public final class VulkanGate10ImmediatePath {

    private static final LongAdder ENTITY_CALLS =
            new LongAdder();

    private static final LongAdder ENTITY_TOTAL_NANOS =
            new LongAdder();

    private static final AtomicLong ENTITY_PEAK_NANOS =
            new AtomicLong();

    private static final LongAdder PARTICLE_PASSES =
            new LongAdder();

    private static final LongAdder PARTICLE_TOTAL_NANOS =
            new LongAdder();

    private static final AtomicLong PARTICLE_PEAK_NANOS =
            new AtomicLong();

    /*
     * Patch 135: verify that the offscreen OpenGL raster source still binds the
     * exact vanilla particle destination while the Vulkan window is presenting.
     * This repairs presentation-induced framebuffer drift without changing the
     * particle renderer, blend state, predicates or ownership model.
     */
    private static final LongAdder PARTICLE_TARGET_CHECKS =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_ACTIVE_CHECKS =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_MAIN_EXPECTED =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_AUX_EXPECTED =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_MATCHES =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_MISMATCHES =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_REPAIRS =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_REPAIR_VERIFIED =
            new LongAdder();

    private static final LongAdder PARTICLE_TARGET_FAILURES =
            new LongAdder();

    private static final AtomicLong PARTICLE_TARGET_LAST_EXPECTED_FBO =
            new AtomicLong(-1L);

    private static final AtomicLong PARTICLE_TARGET_LAST_ACTUAL_FBO =
            new AtomicLong(-1L);

    private static final AtomicLong PARTICLE_TARGET_LAST_REPAIRED_FBO =
            new AtomicLong(-1L);

    private static final LongAdder GUI_HUD_FRAMES =
            new LongAdder();

    private static final LongAdder GUI_HUD_TOTAL_NANOS =
            new LongAdder();

    private static final AtomicLong GUI_HUD_PEAK_NANOS =
            new AtomicLong();

    private static final LongAdder GUI_SCREEN_FRAMES =
            new LongAdder();

    private static final LongAdder GUI_SCREEN_TOTAL_NANOS =
            new LongAdder();

    private static final AtomicLong GUI_SCREEN_PEAK_NANOS =
            new AtomicLong();

    private static volatile String observedRenderThreadName =
            "";

    /*
     * Patch 106: Gate 10 is no longer opened by a source constant. A fresh
     * gameplay qualification test owns this latch. The latch is fail-open:
     * an explicit rehearsal failure can revoke it again in the same process.
     */
    private static volatile boolean runtimeQualificationPassed;

    private static volatile long runtimeQualificationHandoffCount;

    private static volatile int runtimeQualificationSessionCount;

    private static volatile long runtimeQualificationPassCount;

    private static volatile long runtimeQualificationRevokeCount;

    private static volatile String runtimeQualificationReason =
            "WAITING_FOR_FRESH_GATE10_RUNTIME_TEST";

    private VulkanGate10ImmediatePath() {
    }

    public static void observeEntityRender(
            long elapsedNanos
    ) {
        long clamped =
                Math.max(
                        0L,
                        elapsedNanos
                );

        ENTITY_CALLS.increment();
        ENTITY_TOTAL_NANOS.add(clamped);
        updatePeak(
                ENTITY_PEAK_NANOS,
                clamped
        );
        observeThread();

        VulkanRuntimeManager.offerGate10DynamicPrecommit(
                VulkanGate10DynamicOwnershipContract.Domain.ENTITY
        );
    }

    public static void verifyParticleOutputTarget(
            RenderTarget expectedTarget
    ) {
        PARTICLE_TARGET_CHECKS.increment();

        if (!VulkanRuntimeManager
                .gate11InteractiveWholeFrameCaptureActive()) {
            return;
        }

        PARTICLE_TARGET_ACTIVE_CHECKS.increment();

        if (expectedTarget == null
                || expectedTarget.frameBufferId <= 0) {
            PARTICLE_TARGET_FAILURES.increment();
            return;
        }

        int expectedFramebuffer =
                expectedTarget.frameBufferId;

        PARTICLE_TARGET_LAST_EXPECTED_FBO.set(
                expectedFramebuffer
        );

        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft != null
                && minecraft.getMainRenderTarget() == expectedTarget) {
            PARTICLE_TARGET_MAIN_EXPECTED.increment();
        } else {
            PARTICLE_TARGET_AUX_EXPECTED.increment();
        }

        try {
            int actualFramebuffer =
                    glGetInteger(
                            GL_DRAW_FRAMEBUFFER_BINDING
                    );

            PARTICLE_TARGET_LAST_ACTUAL_FBO.set(
                    actualFramebuffer
            );

            if (actualFramebuffer == expectedFramebuffer) {
                PARTICLE_TARGET_MATCHES.increment();
                PARTICLE_TARGET_LAST_REPAIRED_FBO.set(
                        actualFramebuffer
                );
                return;
            }

            PARTICLE_TARGET_MISMATCHES.increment();

            /*
             * Re-establish only the framebuffer output that vanilla selected.
             * bindWrite(false) deliberately does not force a viewport change.
             */
            expectedTarget.bindWrite(
                    false
            );

            PARTICLE_TARGET_REPAIRS.increment();

            int repairedFramebuffer =
                    glGetInteger(
                            GL_DRAW_FRAMEBUFFER_BINDING
                    );

            PARTICLE_TARGET_LAST_REPAIRED_FBO.set(
                    repairedFramebuffer
            );

            if (repairedFramebuffer == expectedFramebuffer) {
                PARTICLE_TARGET_REPAIR_VERIFIED.increment();
            } else {
                PARTICLE_TARGET_FAILURES.increment();
            }
        } catch (Throwable throwable) {
            PARTICLE_TARGET_FAILURES.increment();
        }
    }

    public static void observeParticlePass(
            long elapsedNanos
    ) {
        long clamped =
                Math.max(
                        0L,
                        elapsedNanos
                );

        PARTICLE_PASSES.increment();
        PARTICLE_TOTAL_NANOS.add(clamped);
        updatePeak(
                PARTICLE_PEAK_NANOS,
                clamped
        );
        observeThread();

        VulkanRuntimeManager.offerGate10DynamicPrecommit(
                VulkanGate10DynamicOwnershipContract.Domain.PARTICLE
        );
    }

    public static void observeGuiHudFrame(
            long elapsedNanos
    ) {
        long clamped =
                Math.max(
                        0L,
                        elapsedNanos
                );

        GUI_HUD_FRAMES.increment();
        GUI_HUD_TOTAL_NANOS.add(clamped);
        updatePeak(
                GUI_HUD_PEAK_NANOS,
                clamped
        );
        observeThread();

        VulkanRuntimeManager.offerGate10DynamicPrecommit(
                VulkanGate10DynamicOwnershipContract.Domain.HUD
        );
    }

    public static void observeGuiScreenFrame(
            long elapsedNanos
    ) {
        long clamped =
                Math.max(
                        0L,
                        elapsedNanos
                );

        GUI_SCREEN_FRAMES.increment();
        GUI_SCREEN_TOTAL_NANOS.add(clamped);
        updatePeak(
                GUI_SCREEN_PEAK_NANOS,
                clamped
        );
        observeThread();

        VulkanRuntimeManager.offerGate10DynamicPrecommit(
                VulkanGate10DynamicOwnershipContract.Domain.SCREEN
        );
    }

    static synchronized void qualifyRuntimeGate10(
            JsonObject report,
            long successfulFullFrameHandoffs,
            int successfulScreenSessions
    ) {
        boolean allImmediateSeamsObserved =
                ENTITY_CALLS.sum() > 0L
                        && PARTICLE_PASSES.sum() > 0L
                        && GUI_HUD_FRAMES.sum() > 0L
                        && GUI_SCREEN_FRAMES.sum() > 0L;

        runtimeQualificationHandoffCount =
                Math.max(
                        runtimeQualificationHandoffCount,
                        successfulFullFrameHandoffs
                );

        runtimeQualificationSessionCount =
                Math.max(
                        runtimeQualificationSessionCount,
                        successfulScreenSessions
                );

        if (!allImmediateSeamsObserved) {
            runtimeQualificationPassed =
                    false;
            runtimeQualificationReason =
                    "WAITING_FOR_ALL_FOUR_PRODUCTION_SEAMS";
        } else if (successfulFullFrameHandoffs < 120L) {
            runtimeQualificationPassed =
                    false;
            runtimeQualificationReason =
                    "WAITING_FOR_120_SUCCESSFUL_FULL_FRAME_HANDOFFS";
        } else if (successfulScreenSessions < 1) {
            runtimeQualificationPassed =
                    false;
            runtimeQualificationReason =
                    "WAITING_FOR_AUTONOMOUS_WORLD_SESSION";
        } else {
            if (!runtimeQualificationPassed) {
                runtimeQualificationPassCount++;
            }

            runtimeQualificationPassed =
                    true;
            runtimeQualificationReason =
                    "PASSED_FRESH_LIVE_GATE10_QUALIFICATION";
        }

        enrich(
                report
        );

        if (runtimeQualificationPassed
                && report != null) {
            dev.ordovicium.potato.render.engine.PotatoRenderEngine
                    .reconcileRuntimeReadiness(
                            report
                    );

            /*
             * Gate 11 starts only after Gate 10 has been earned by this
             * process. Patch 107 performs a one-shot, non-presenting surface
             * compatibility qualification against Minecraft's actual GLFW
             * main window. The result never flips Gate 11 by itself.
             */
            VulkanRuntimeManager
                    .offerGate11MainWindowSurfaceQualification();
        }
    }

    static synchronized void revokeRuntimeGate10(
            JsonObject report,
            String reason
    ) {
        if (runtimeQualificationPassed) {
            runtimeQualificationRevokeCount++;
        }

        runtimeQualificationPassed =
                false;
        runtimeQualificationReason =
                reason == null
                        || reason.isBlank()
                        ? "REVOKED_FAIL_OPEN"
                        : reason;

        enrich(
                report
        );

        if (report != null) {
            dev.ordovicium.potato.render.engine.PotatoRenderEngine
                    .reconcileRuntimeReadiness(
                            report
                    );
        }
    }

    public static void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        long entityCalls =
                ENTITY_CALLS.sum();

        long entityTotalNanos =
                ENTITY_TOTAL_NANOS.sum();

        long particlePasses =
                PARTICLE_PASSES.sum();

        long particleTotalNanos =
                PARTICLE_TOTAL_NANOS.sum();

        long guiHudFrames =
                GUI_HUD_FRAMES.sum();

        long guiHudTotalNanos =
                GUI_HUD_TOTAL_NANOS.sum();

        long guiScreenFrames =
                GUI_SCREEN_FRAMES.sum();

        long guiScreenTotalNanos =
                GUI_SCREEN_TOTAL_NANOS.sum();

        boolean entityObserved =
                entityCalls > 0L;

        boolean particleObserved =
                particlePasses > 0L;

        boolean guiHudObserved =
                guiHudFrames > 0L;

        boolean guiScreenObserved =
                guiScreenFrames > 0L;

        boolean entityParticlePrecommitVerified =
                entityObserved
                        && particleObserved;

        boolean guiObserved =
                guiHudObserved
                        && guiScreenObserved;

        boolean allImmediateSeamsObserved =
                entityParticlePrecommitVerified
                        && guiObserved;

        boolean gate10RuntimeReady =
                allImmediateSeamsObserved
                        && runtimeQualificationPassed;

        report.addProperty(
                "gate10ImmediatePathStage",
                gate10RuntimeReady
                        ? "GATE10_RUNTIME_QUALIFIED_OPENGL_FAIL_OPEN"
                        : allImmediateSeamsObserved
                        ? "ENTITY_PARTICLE_GUI_SEAMS_OPENGL_AUTHORITATIVE"
                        : "GATE10_DYNAMIC_SEAM_DISCOVERY"
        );
        report.addProperty(
                "gate10EntityRenderSeamObserved",
                entityObserved
        );
        report.addProperty(
                "gate10EntityRenderCallCount",
                entityCalls
        );
        report.addProperty(
                "gate10EntityRenderTotalNanos",
                entityTotalNanos
        );
        report.addProperty(
                "gate10EntityRenderAverageNanos",
                entityCalls > 0L
                        ? entityTotalNanos / entityCalls
                        : 0L
        );
        report.addProperty(
                "gate10EntityRenderPeakNanos",
                ENTITY_PEAK_NANOS.get()
        );
        report.addProperty(
                "gate10ParticleRenderSeamObserved",
                particleObserved
        );
        report.addProperty(
                "gate10ParticleRenderPassCount",
                particlePasses
        );
        report.addProperty(
                "gate10ParticleRenderTotalNanos",
                particleTotalNanos
        );
        report.addProperty(
                "gate10ParticleRenderAverageNanos",
                particlePasses > 0L
                        ? particleTotalNanos / particlePasses
                        : 0L
        );
        report.addProperty(
                "gate10ParticleRenderPeakNanos",
                PARTICLE_PEAK_NANOS.get()
        );
        report.addProperty(
                "gate10ParticleOutputTargetParityInstalled",
                true
        );
        report.addProperty(
                "gate10ParticleOutputTargetParityPolicy",
                "VANILLA_FABULOUS_PARTICLES_TARGET_ELSE_MAIN_TARGET_REPAIR_ONLY_WHILE_VULKAN_PRESENTS"
        );
        report.addProperty(
                "gate10ParticleOutputTargetCheckCount",
                PARTICLE_TARGET_CHECKS.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetActiveCheckCount",
                PARTICLE_TARGET_ACTIVE_CHECKS.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetMainTargetExpectedCount",
                PARTICLE_TARGET_MAIN_EXPECTED.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetAuxTargetExpectedCount",
                PARTICLE_TARGET_AUX_EXPECTED.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetMatchCount",
                PARTICLE_TARGET_MATCHES.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetMismatchCount",
                PARTICLE_TARGET_MISMATCHES.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetRepairCount",
                PARTICLE_TARGET_REPAIRS.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetRepairVerifiedCount",
                PARTICLE_TARGET_REPAIR_VERIFIED.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetFailureCount",
                PARTICLE_TARGET_FAILURES.sum()
        );
        report.addProperty(
                "gate10ParticleOutputTargetLastExpectedFramebuffer",
                PARTICLE_TARGET_LAST_EXPECTED_FBO.get()
        );
        report.addProperty(
                "gate10ParticleOutputTargetLastActualFramebuffer",
                PARTICLE_TARGET_LAST_ACTUAL_FBO.get()
        );
        report.addProperty(
                "gate10ParticleOutputTargetLastRepairedFramebuffer",
                PARTICLE_TARGET_LAST_REPAIRED_FBO.get()
        );
        report.addProperty(
                "gate10GuiHudSeamObserved",
                guiHudObserved
        );
        report.addProperty(
                "gate10GuiHudFrameCount",
                guiHudFrames
        );
        report.addProperty(
                "gate10GuiHudTotalNanos",
                guiHudTotalNanos
        );
        report.addProperty(
                "gate10GuiHudAverageNanos",
                guiHudFrames > 0L
                        ? guiHudTotalNanos / guiHudFrames
                        : 0L
        );
        report.addProperty(
                "gate10GuiHudPeakNanos",
                GUI_HUD_PEAK_NANOS.get()
        );
        report.addProperty(
                "gate10GuiScreenSeamObserved",
                guiScreenObserved
        );
        report.addProperty(
                "gate10GuiScreenFrameCount",
                guiScreenFrames
        );
        report.addProperty(
                "gate10GuiScreenTotalNanos",
                guiScreenTotalNanos
        );
        report.addProperty(
                "gate10GuiScreenAverageNanos",
                guiScreenFrames > 0L
                        ? guiScreenTotalNanos / guiScreenFrames
                        : 0L
        );
        report.addProperty(
                "gate10GuiScreenPeakNanos",
                GUI_SCREEN_PEAK_NANOS.get()
        );
        report.addProperty(
                "gate10ObservedRenderThreadName",
                observedRenderThreadName
        );
        report.addProperty(
                "gate10EntityParticlePrecommitVerified",
                entityParticlePrecommitVerified
        );
        report.addProperty(
                "gate10GuiSeamObserved",
                guiObserved
        );
        report.addProperty(
                "gate10GuiSeamState",
                guiObserved
                        ? "HUD_AND_SCREEN_BOUNDARIES_OBSERVED"
                        : "WAITING_FOR_HUD_AND_SCREEN_BOUNDARIES"
        );
        report.addProperty(
                "gate10AllImmediateSeamsObserved",
                allImmediateSeamsObserved
        );

        VulkanGate10DynamicOwnershipContract.enrich(
                report
        );

        report.addProperty(
                "gate10OpenGlEntityAuthority",
                true
        );
        report.addProperty(
                "gate10OpenGlParticleAuthority",
                true
        );
        report.addProperty(
                "gate10OpenGlGuiAuthority",
                true
        );
        report.addProperty(
                "gate10VisibleOwnership",
                false
        );
        report.addProperty(
                "gate10NoGameplayGpuWait",
                true
        );

        report.addProperty(
                "gate10RuntimeQualificationInstalled",
                true
        );
        report.addProperty(
                "gate10RuntimeQualificationFreshTestRequired",
                !gate10RuntimeReady
        );
        report.addProperty(
                "gate10RuntimeQualificationPolicy",
                "FRESH_PROCESS_4_SEAMS_PLUS_1_AUTONOMOUS_WORLD_SESSION_PLUS_120_FULLFRAME_HANDOFFS"
        );
        report.addProperty(
                "gate10RuntimeQualificationPassed",
                gate10RuntimeReady
        );
        report.addProperty(
                "gate10RuntimeQualificationHandoffCount",
                runtimeQualificationHandoffCount
        );
        report.addProperty(
                "gate10RuntimeQualificationSessionCount",
                runtimeQualificationSessionCount
        );
        report.addProperty(
                "gate10RuntimeQualificationPassCount",
                runtimeQualificationPassCount
        );
        report.addProperty(
                "gate10RuntimeQualificationRevokeCount",
                runtimeQualificationRevokeCount
        );
        report.addProperty(
                "gate10RuntimeQualificationReason",
                runtimeQualificationReason
        );
        report.addProperty(
                "gate10RuntimeQualificationKeepsOpenGlFailOpen",
                true
        );
        report.addProperty(
                "gate10RuntimeQualificationEnablesOpenGlSuppression",
                false
        );

        /*
         * Gate 10 is a runtime-readiness result now, not a patch-time boolean.
         * It becomes true only after the fresh full-frame qualification test
         * passes while all four production seams are observed.
         *
         * This readiness does NOT grant per-domain Vulkan draw authority.
         * ENTITY/PARTICLE/HUD/SCREEN remain fail-open OpenGL until a later
         * ownership cutover can independently prove those visible draws.
         */
        report.addProperty(
                "potatoEngineVulkanGuiEntityParticleReady",
                gate10RuntimeReady
        );
        report.addProperty(
                "gate10NextMilestone",
                gate10RuntimeReady
                        ? "POTATO_ENGINE_GATE11_MAIN_WINDOW_PRESENTATION"
                        : "POTATO_ENGINE_GATE10_AUTOMATIC_RUNTIME_QUALIFICATION"
        );
    }

    private static void observeThread() {
        if (observedRenderThreadName.isEmpty()) {
            observedRenderThreadName =
                    Thread.currentThread()
                            .getName();
        }
    }

    private static void updatePeak(
            AtomicLong peak,
            long candidate
    ) {
        long current =
                peak.get();

        while (candidate > current
                && !peak.compareAndSet(
                current,
                candidate
        )) {
            current =
                    peak.get();
        }
    }
}
