package dev.ordovicium.potato.diagnostics;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import dev.ordovicium.potato.render.engine.PotatoRenderEngine;
import dev.ordovicium.potato.render.engine.PotatoRenderEngineDecision;
import dev.ordovicium.potato.render.engine.PotatoRenderBackendPreference;
import dev.ordovicium.potato.render.vulkan.VulkanProbe;
import dev.ordovicium.potato.render.vulkan.VulkanRuntimeManager;
import dev.ordovicium.potato.settings.PotatoRuntimeMode;
import dev.ordovicium.potato.settings.PotatoRuntimeSettings;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class PotatoDiagnostics {
    private static final long MIB = 1024L * 1024L;

    private PotatoDiagnostics() {
    }

    public static void writeStartupReport() {
        Runtime runtime = Runtime.getRuntime();
        List<String> report = new ArrayList<>();

        report.add("Potato Runtime startup report");
        report.add("============================");
        report.add("timestamp=" + Instant.now());
        report.add("potato.version=0.9.0");
        report.add("minecraft.version=" + SharedConstants.getCurrentVersion().getName());
        report.add("java.version=" + System.getProperty("java.version"));
        report.add("java.vendor=" + System.getProperty("java.vendor"));
        report.add("os.name=" + System.getProperty("os.name"));
        report.add("os.version=" + System.getProperty("os.version"));
        report.add("os.arch=" + System.getProperty("os.arch"));
        report.add("cpu.logicalProcessors=" + runtime.availableProcessors());
        report.add("memory.jvmMaxMiB=" + (runtime.maxMemory() / MIB));
        report.add("memory.jvmTotalMiB=" + (runtime.totalMemory() / MIB));
        report.add("memory.jvmFreeMiB=" + (runtime.freeMemory() / MIB));

        PotatoRuntimeMode runtimeMode =
                PotatoRuntimeMode.startupMode();

        report.add("potato.mode.startup=" + runtimeMode.name());
        report.add(
                "potato.mode.selected="
                        + PotatoRuntimeMode.selectedMode().name()
        );
        report.add(
                "potato.mode.restartRequired="
                        + PotatoRuntimeMode.restartRequired()
        );
        report.add("potato.settings.advancedUi=DISABLED");

        Path outputDirectory = Minecraft.getInstance()
                .gameDirectory
                .toPath()
                .resolve("potato");

        Path startupFile = outputDirectory.resolve("startup-report.txt");
        Path vulkanFile = outputDirectory.resolve("vulkan-report.json");

        try {
            Files.createDirectories(outputDirectory);

            JsonObject vulkanReport;

            if (runtimeMode == PotatoRuntimeMode.OFF) {
                vulkanReport = new JsonObject();
                vulkanReport.addProperty(
                        "success",
                        false
                );
                vulkanReport.addProperty(
                        "stage",
                        "POTATO_RUNTIME_OFF_BASELINE"
                );
                vulkanReport.addProperty(
                        "potatoRuntimeMode",
                        runtimeMode.name()
                );
                vulkanReport.addProperty(
                        "potatoRuntimeMixinsEnabled",
                        false
                );
                vulkanReport.addProperty(
                        "vulkanProbeSkippedByPotatoRuntimeMode",
                        true
                );
                vulkanReport.addProperty(
                        "vulkanRuntimeActive",
                        false
                );
                vulkanReport.addProperty(
                        "rendererAuthority",
                        "EXTERNAL_MODPACK_AUTHORITY"
                );

                report.add(
                        "renderer.engine=DISABLED_BY_POTATO_MODE_OFF"
                );
                report.add(
                        "renderer.backend.preference=EXTERNAL_MODPACK_AUTHORITY"
                );
                report.add(
                        "renderer.backend.preferred=EXTERNAL_MODPACK_AUTHORITY"
                );
                report.add(
                        "renderer.backend.active=EXTERNAL_MODPACK_AUTHORITY"
                );
                report.add("renderer.migrationGate=DISABLED");
                report.add(
                        "vulkan.probe=SKIPPED_BY_POTATO_MODE_OFF"
                );
                report.add("vulkan.runtime=NOT_ACTIVE");
                report.add("lod.engine=NOT_ACTIVE");
                report.add("chunkWork.policy=EXTERNAL_MODPACK_BASELINE");
                report.add("status=POTATO_OFF_BASELINE");

                Files.write(
                        startupFile,
                        report,
                        StandardCharsets.UTF_8
                );
                Files.writeString(
                        vulkanFile,
                        vulkanReport.toString(),
                        StandardCharsets.UTF_8
                );

                copyIntoDevelopmentDropoffIfPresent(
                        startupFile
                );
                copyIntoDevelopmentDropoffIfPresent(
                        vulkanFile
                );

                PotatoRuntime.LOGGER.info(
                        "[Potato] OFF baseline active. "
                                + "Potato mixins and Vulkan probe are disabled for this process."
                );

                for (String line : report) {
                    PotatoRuntime.LOGGER.info(
                            "[Potato] {}",
                            line
                    );
                }

                return;
            }

            PotatoRenderBackendPreference requestedPreference =
                    PotatoRenderEngine.requestedPreference();

            if (requestedPreference
                    == PotatoRenderBackendPreference.OPENGL) {

                vulkanReport =
                        new JsonObject();

                vulkanReport.addProperty(
                        "success",
                        false
                );
                vulkanReport.addProperty(
                        "stage",
                        "POTATO_ENGINE_OPENGL_FORCED_PROBE_SKIPPED"
                );
                vulkanReport.addProperty(
                        "vulkanProbeSkippedByPotatoEngine",
                        true
                );
                vulkanReport.addProperty(
                        "fallbackBackend",
                        "OPENGL_COMPATIBILITY"
                );
            } else {
                try {
                    vulkanReport = VulkanProbe.probe();
                } catch (Throwable throwable) {
                    vulkanReport = new JsonObject();
                    vulkanReport.addProperty("success", false);
                    vulkanReport.addProperty(
                            "stage",
                            "DIAGNOSTIC_FAIL_SAFE_FALLBACK"
                    );
                    vulkanReport.addProperty(
                            "fallbackBackend",
                            "OPENGL_COMPATIBILITY"
                    );
                    vulkanReport.addProperty(
                            "errorType",
                            throwable.getClass().getName()
                    );
                    vulkanReport.addProperty(
                            "error",
                            String.valueOf(
                                    throwable.getMessage()
                            )
                    );

                    PotatoRuntime.LOGGER.warn(
                            "[Potato] Vulkan probe threw unexpectedly. Potato Engine selected the OpenGL compatibility fallback.",
                            throwable
                    );
                }
            }

            boolean vulkanSuccess = vulkanReport.has("success")
                    && vulkanReport.get("success").getAsBoolean();

            boolean persistentRuntimeVerified = false;

            if (vulkanSuccess) {
                persistentRuntimeVerified =
                        VulkanRuntimeManager
                                .verifyAfterProbeReturn(
                                        vulkanReport
                                );

                if (!persistentRuntimeVerified) {
                    vulkanSuccess = false;

                    vulkanReport.addProperty(
                            "success",
                            false
                    );
                    vulkanReport.addProperty(
                            "stage",
                            "PERSISTENT_VULKAN_RUNTIME_CONTEXT_NOT_VERIFIED"
                    );
                    vulkanReport.addProperty(
                            "fallbackBackend",
                            "OPENGL_COMPATIBILITY"
                    );
                }
            }
            boolean persistentRuntimeReleasedForWindowAuthority =
                    false;

            boolean headlessResourceRuntimeActive =
                    VulkanRuntimeManager
                            .headlessResourceActive();

            /*
             * Patch 159 keeps the verified Vulkan runtime alive only until the
             * Potato Engine has made its active-backend decision below.
             *
             * If OpenGL owns the visible gameplay window, the proven
             * releaseForOpenGlWindowAuthority() boundary then retires the full
             * presentation runtime before normal gameplay and preserves only
             * the windowless headless resource runtime.
             */
            if (persistentRuntimeVerified) {
                vulkanReport.addProperty(
                        "persistentVulkanRuntimeIntentionallyNotRetained",
                        false
                );
                vulkanReport.addProperty(
                        "persistentVulkanRuntimeRetainedForGameplay",
                        true
                );
                vulkanReport.addProperty(
                        "secondaryNoApiPresentationVisible",
                        false
                );
                vulkanReport.addProperty(
                        "openGlMainWindowAuthoritative",
                        true
                );
                vulkanReport.addProperty(
                        "potatoEngineVulkanWorldRasterRuntimeArmed",
                        true
                );
                vulkanReport.addProperty(
                        "potatoEngineVulkanVisibleDrawSuppressionEnabled",
                        false
                );
                vulkanReport.addProperty(
                        "potatoEngineVulkanFailOpenToOpenGl",
                        true
                );
                vulkanReport.addProperty(
                        "stage",
                        "POTATO_ENGINE_VULKAN_WORLD_TEXTURE_CUTOVER"
                );

                VulkanRuntimeManager.bindReport(
                        vulkanFile,
                        vulkanReport
                );
            }

            /*
             * POTATO_PATCH_168_COMPAT_VISIBLE_GL_IDENTITY_RECOVERY
             *
             * Iris compatibility intentionally leaves RenderSystem.initRenderer
             * under vanilla/Iris ownership. In that topology the old Potato
             * renderer-init rehearsal metadata is absent, even though a valid
             * OpenGL context is already current on the render thread.
             *
             * Patch 158's active-backend pressure governor must classify the GPU
             * that actually owns the visible OpenGL window. Recover only the
             * renderer/vendor/version strings from the live current context when
             * the existing rehearsal metadata is blank. This is read-only:
             * no OpenGL state, buffer, texture, framebuffer or ownership is
             * changed.
             */
            String existingOpenGlRendererDescription =
                    jsonString(
                            vulkanReport,
                            "rendererInitApiDescriptionAfter"
                    );

            String recoveredOpenGlRendererDescription =
                    existingOpenGlRendererDescription.isBlank()
                            ? recoverActiveOpenGlRendererDescription()
                            : "";

            boolean activeOpenGlRendererDescriptionRecovered =
                    !recoveredOpenGlRendererDescription.isBlank();

            if (activeOpenGlRendererDescriptionRecovered) {
                vulkanReport.addProperty(
                        "rendererInitApiDescriptionAfter",
                        recoveredOpenGlRendererDescription
                );
            }

            vulkanReport.addProperty(
                    "potatoCompatVisibleOpenGlIdentityRecoveryInstalled",
                    true
            );
            vulkanReport.addProperty(
                    "potatoCompatVisibleOpenGlIdentityRecovered",
                    activeOpenGlRendererDescriptionRecovered
            );
            vulkanReport.addProperty(
                    "potatoCompatVisibleOpenGlIdentityRecoverySource",
                    activeOpenGlRendererDescriptionRecovered
                            ? "LIVE_CURRENT_OPENGL_CONTEXT"
                            : existingOpenGlRendererDescription.isBlank()
                            ? "UNAVAILABLE_FAIL_CONSERVATIVE"
                            : "EXISTING_RENDERER_INIT_METADATA"
            );

            /*
             * Always publish the effective three-state runtime plan, including
             * fail-open compatibility runs where VulkanProbe exits before its
             * older settings-enrichment point.
             */
            PotatoRuntimeSettings.enrich(
                    vulkanReport
            );

            PotatoRenderEngineDecision engineDecision =
                    PotatoRenderEngine.evaluate(
                            vulkanReport,
                            headlessResourceRuntimeActive
                    );

            /*
             * POTATO_PATCH_159_RELEASE_OPENGL_AUTHORITY_ISOLATION
             *
             * The 158 runtime proved that active visible ownership can be
             * OpenGL on Intel while the complete Quadro-backed Vulkan
             * presentation runtime is still retained. That state is useful
             * while developing an atomic Vulkan cutover, but it is the wrong
             * default lifetime for the release-safe OpenGL compatibility path.
             *
             * Resolve the backend decision first. If Vulkan is not the active
             * visible backend, reuse the already-proven window-authority
             * recovery boundary: retire every window/surface/swapchain and
             * presentation-dependent Vulkan owner while retaining only the
             * windowless headless resource runtime. OpenGL remains the only
             * native gameplay/presentation authority.
             */
            boolean releaseSafeOpenGlAuthorityIsolationRequested =
                    persistentRuntimeVerified
                            && engineDecision.activeBackend().id()
                            .equals("OPENGL_COMPATIBILITY");

            if (releaseSafeOpenGlAuthorityIsolationRequested) {
                persistentRuntimeReleasedForWindowAuthority =
                        VulkanRuntimeManager
                                .releaseForOpenGlWindowAuthority(
                                        vulkanReport
                                );

                headlessResourceRuntimeActive =
                        VulkanRuntimeManager
                                .headlessResourceActive();

                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityIsolationInstalled",
                        true
                );
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityIsolationRequested",
                        true
                );
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityIsolationCompleted",
                        persistentRuntimeReleasedForWindowAuthority
                );
                vulkanReport.addProperty(
                        "persistentVulkanRuntimeIntentionallyNotRetained",
                        true
                );
                vulkanReport.addProperty(
                        "persistentVulkanRuntimeRetainedForGameplay",
                        VulkanRuntimeManager.active()
                );
                vulkanReport.addProperty(
                        "secondaryNoApiPresentationVisible",
                        false
                );
                vulkanReport.addProperty(
                        "openGlMainWindowAuthoritative",
                        true
                );
                vulkanReport.addProperty(
                        "potatoEngineVulkanWorldRasterRuntimeArmed",
                        false
                );
                vulkanReport.addProperty(
                        "potatoEngineVulkanVisibleDrawSuppressionEnabled",
                        false
                );
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityHeadlessResourceRuntimeActive",
                        headlessResourceRuntimeActive
                );
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityFullPresentationRuntimeActive",
                        VulkanRuntimeManager.active()
                );
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityNoGameplayGpuWait",
                        true
                );
                vulkanReport.addProperty(
                        "stage",
                        persistentRuntimeReleasedForWindowAuthority
                                ? "POTATO_ENGINE_RELEASE_OPENGL_AUTHORITY_ISOLATED"
                                : "POTATO_ENGINE_RELEASE_OPENGL_AUTHORITY_FAIL_OPEN"
                );

                /*
                 * releaseForOpenGlWindowAuthority deliberately clears the old
                 * full-runtime report binding. Rebind after the transition so
                 * headless shutdown + chunk-governor telemetry is preserved.
                 */
                VulkanRuntimeManager.bindReport(
                        vulkanFile,
                        vulkanReport
                );
            } else {
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityIsolationInstalled",
                        true
                );
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityIsolationRequested",
                        false
                );
                vulkanReport.addProperty(
                        "potatoReleaseOpenGlAuthorityIsolationCompleted",
                        false
                );
            }

            boolean persistentPresentationRuntimeActive =
                    VulkanRuntimeManager.active();

            PotatoRenderEngine.enrich(
                    vulkanReport
            );

            report.add(
                    "renderer.engine=POTATO_ENGINE"
            );
            report.add(
                    "renderer.backend.preference="
                            + engineDecision.preference().name()
            );
            report.add(
                    "renderer.backend.preferred="
                            + engineDecision.preferredBackend().id()
            );
            report.add(
                    "renderer.backend.active="
                            + engineDecision.activeBackend().id()
            );
            report.add(
                    "renderer.backend.reason="
                            + engineDecision.reason()
            );
            report.add(
                    "renderer.hardwareClass="
                            + engineDecision.hardwareClass().name()
            );
            report.add(
                    "renderer.opengl.description="
                            + PotatoRenderEngine
                            .activeOpenGlRendererDescription()
            );
            report.add(
                    "renderer.opengl.lowEndIntel="
                            + PotatoRenderEngine
                            .activeOpenGlLowEndIntel()
            );
            report.add(
                    "runtime.resourcePlan="
                            + PotatoRuntimeSettings
                            .hardwarePlan()
                            .source()
            );
            report.add(
                    "runtime.resourcePlanMaxChunks="
                            + PotatoRuntimeSettings
                            .hardwarePlan()
                            .automaticMaxChunks()
            );
            report.add(
                    "renderer.vulkanEligible="
                            + engineDecision.vulkanEligible()
            );
            report.add(
                    "renderer.vulkanActivationReady="
                            + engineDecision.vulkanActivationReady()
            );
            report.add(
                    "renderer.vulkanReadiness="
                            + engineDecision.readinessPassed()
                            + "/"
                            + engineDecision.readinessTotal()
            );
            report.add(
                    "renderer.vulkanReadinessPercent="
                            + engineDecision.readinessPercent()
            );
            report.add(
                    "renderer.migrationGate="
                            + (engineDecision.migrationGateActive()
                            ? "ACTIVE"
                            : "CLEAR")
            );
            report.add(
                    "window.authority="
                            + (engineDecision.activeBackend().id().equals("VULKAN")
                            ? "POTATO_VULKAN"
                            : "OPENGL_COMPATIBILITY")
            );
            report.add(
                    "vulkan.presentationGameplayLifetime="
                            + (engineDecision.activeBackend().id().equals("VULKAN")
                            ? "ACTIVE"
                            : "RELEASED")
            );

            report.add("vulkan.probe=" + (vulkanSuccess ? "PASS" : "FAIL_SAFE_FALLBACK"));
            report.add(
                    "vulkan.runtime="
                            + (persistentPresentationRuntimeActive
                            ? "PERSISTENT_PRESENTATION_RUNTIME"
                            : headlessResourceRuntimeActive
                            ? "HEADLESS_RESOURCE_RUNTIME"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "vulkan.resourceRuntime="
                            + (persistentPresentationRuntimeActive
                            ? "PRESENTATION_CORE_DEVICE_QUEUES"
                            : headlessResourceRuntimeActive
                            ? "HEADLESS_CORE_DEVICE_QUEUES"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "renderTarget.dispatch="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "renderTarget.resizeGpu="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "renderTarget.clearGpu="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "renderTarget.blitGpu="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "renderTarget.frameGpu="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "drawSubmission.contract="
                            + (headlessResourceRuntimeActive
                            ? "HEADLESS_RESOURCE_MIRROR"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "geometryUpload.prototype="
                            + (headlessResourceRuntimeActive
                            ? "ARMED_BOUNDED_HEADLESS"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "plainDraw.context="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "sectionLayer.drawGpu=NOT_ACTIVE"
            );
            report.add(
                    "blockResources.capture="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "runtime.performanceFastPath="
                            + (headlessResourceRuntimeActive
                            ? "HEADLESS_RESOURCE_FAST_PATH"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "runtime.hiddenFrameMirror=OFF_BY_DEFAULT"
            );
            report.add(
                    "blockTextures.uploadGpu="
                            + (persistentPresentationRuntimeActive
                            ? "ARMED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "blockTextures.texturedDrawGpu="
                            + (persistentPresentationRuntimeActive
                            ? "HISTORICALLY_VERIFIED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "blockFrames.multiSectionGpu="
                            + (persistentPresentationRuntimeActive
                            ? "HISTORICALLY_VERIFIED"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "visibility.engine="
                            + (headlessResourceRuntimeActive
                            ? "ARMED_BOUNDED_LIVE_CLASSIFICATION"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "visibility.occlusion="
                            + (headlessResourceRuntimeActive
                            ? "TEMPORAL_CONSERVATIVE_ANY_SAMPLES_QUERY_STAGE1"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "visibility.worldSimulationMutation=NONE"
            );
            report.add(
                    "lod.engine="
                            + (headlessResourceRuntimeActive
                            ? "ARMED_TRANSITION_CAPACITY_RECOVERY_LOD_STAGE3"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "lod.profile=ADAPTIVE_DEFAULT_RUNTIME_MUTABLE"
            );
            report.add(
                    "lod.visibleSubstitution="
                            + (headlessResourceRuntimeActive
                            ? "ARMED_SOLID_ONLY"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "lod.proxyBuild="
                            + (headlessResourceRuntimeActive
                            ? "ASYNC_MULTIWORKER_BOUNDED_CPU_DEFERRED_CATCHUP"
                            : "NOT_ACTIVE")
            );
            report.add(
                    "lod.renderDistanceMutation=NONE"
            );
            report.add(
                    "chunkWork.frameBudget=ARMED"
            );
            report.add(
                    "chunkWork.compile=DEDICATED_ADAPTIVE_MULTITHREADED"
            );
            report.add(
                    "chunkWork.uploadTiming=ACTUAL_RUNNABLE_EXECUTION"
            );
            report.add(
                    "lod.catchup=SEPARATE_POOL_FRAME_PACED_INSTALL"
            );
            report.add(
                    "chunkWork.meshDiscard=NONE"
            );
            report.add(
                    "chunkWork.uploadBudget=ADAPTIVE_TIME_AND_COUNT"
            );
            report.add(
                    "worldClusters.hierarchy=ARMED"
            );
            report.add(
                    "worldClusters.visibleMutation=NONE"
            );
            report.add(
                    "worldClusters.screenSpaceCalibration=HISTORICALLY_VERIFIED"
            );
            report.add(
                    "surfaceMerging.census=HISTORICALLY_VERIFIED"
            );
            report.add(
                    "surfaceMerging.visibleMutation=NONE"
            );
            report.add(
                    "surfaceTiles.decouplingCensus=HISTORICALLY_VERIFIED"
            );
            report.add(
                    "surfaceTiles.visibleMutation=NONE"
            );
            report.add(
                    "surfaceTiles.vkBufferPrototype=HISTORICALLY_VERIFIED"
            );
            report.add(
                    "surfaceTiles.gpuDraw=HISTORICALLY_VERIFIED"
            );
            report.add(
                    "surfaceTiles.texturedDecode=HISTORICALLY_VERIFIED"
            );
            report.add(
                    "surfaceTiles.runtimeAnalysis=DEBUG_ONLY"
            );
            report.add("runtime.cutoverSprint=POTATO_ENGINE_VISIBLE_SOLID_ATOMIC_CUTOVER");
            report.add("chunkWork.policy=ACTIVE_BACKEND_AWARE_UPLOAD_PRESSURE_BOUNDED_CATCHUP");
            report.add("vulkan.worldRaster=VISIBLE_SOLID_VISIBLE_FIRST_RESIDENCY_FAIL_OPEN");
            report.add("vulkan.textureLifecycle=LIVE_GENERATION_SYNC_PIGGYBACKED");
        report.add("vulkan.indexTopology=IMMUTABLE_DUAL_QUAD_INDEX_ATLAS");
        report.add("vulkan.geometryArena=SHADOW_GLOBAL_BUDDY_PLUS_REGION_SURVEY");
        report.add("vulkan.geometryResidency=VISIBLE_FIRST_BOUNDED_DEFERRED_GENERATION_PROMOTION");
        report.add("vulkan.publicationGate=PARALLEL_FULL_SWEEP_PER_SECTION_STABILITY_FAIL_OPEN");
        report.add("view.turnRelief=ABRUPT_ROTATION_EXISTING_LOD_PROXY_BURST");
        report.add("lod.longView=ADAPTIVE_RD28_PLUS_AGGRESSIVE_SOLID");
        report.add("vulkan.duplicateFallbackSubmission=SUPPRESSED");
        report.add("vulkan.regionArena=SHADOW_8X4X8_SURVEY");
            report.add("next.performance=POTATO_ENGINE_DEVICE_LOCAL_REGION_ARENA");
            report.add("status=BOOTSTRAP_OK");

            vulkanReport.addProperty(
                    "potatoRuntimeMode",
                    runtimeMode.name()
            );
            vulkanReport.addProperty(
                    "potatoRuntimeModeSelected",
                    PotatoRuntimeMode.selectedMode().name()
            );
            vulkanReport.addProperty(
                    "potatoRuntimeModeRestartRequired",
                    PotatoRuntimeMode.restartRequired()
            );
            vulkanReport.addProperty(
                    "potatoAdvancedSettingsUiExposed",
                    false
            );

            Files.write(startupFile, report, StandardCharsets.UTF_8);
            VulkanProbe.writeReport(vulkanFile, vulkanReport);

            copyIntoDevelopmentDropoffIfPresent(startupFile);
            copyIntoDevelopmentDropoffIfPresent(vulkanFile);

            PotatoRuntime.LOGGER.info("[Potato] Startup diagnostics written to {}", startupFile.toAbsolutePath());
            PotatoRuntime.LOGGER.info("[Potato] Vulkan diagnostics written to {}", vulkanFile.toAbsolutePath());

            for (String line : report) {
                PotatoRuntime.LOGGER.info("[Potato] {}", line);
            }
        } catch (IOException exception) {
            PotatoRuntime.LOGGER.error("[Potato] Failed to write startup diagnostics.", exception);
        }
    }

    private static String jsonString(
            JsonObject report,
            String key
    ) {
        if (report == null
                || key == null
                || !report.has(key)
                || report.get(key).isJsonNull()) {
            return "";
        }

        try {
            return report.get(key)
                    .getAsString()
                    .trim();
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /**
     * Read-only compatibility fallback for active OpenGL device identity.
     *
     * <p>This path is used only when Potato's renderer-init rehearsal metadata
     * is unavailable, which is expected with Iris because that ownership mixin
     * is deliberately skipped. The query runs on the current OpenGL context and
     * does not mutate renderer state.</p>
     */
    private static String recoverActiveOpenGlRendererDescription() {
        try {
            if (!PotatoWindowBootstrapTrace
                    .mainWindowOwnsOpenGlContext()) {
                return "";
            }

            if (GLFW.glfwGetCurrentContext() == 0L) {
                return "";
            }

            /*
             * Throws when no capabilities are installed on this thread.
             * Catching below preserves the conservative POTATO fallback.
             */
            GL.getCapabilities();

            String renderer =
                    GL11C.glGetString(
                            GL11C.GL_RENDERER
                    );

            if (renderer == null
                    || renderer.isBlank()) {
                return "";
            }

            String vendor =
                    GL11C.glGetString(
                            GL11C.GL_VENDOR
                    );

            String version =
                    GL11C.glGetString(
                            GL11C.GL_VERSION
                    );

            StringBuilder description =
                    new StringBuilder(
                            renderer.trim()
                    );

            if (vendor != null
                    && !vendor.isBlank()
                    && !description.toString()
                    .toLowerCase()
                    .contains(
                            vendor.trim()
                                    .toLowerCase()
                    )) {
                description.append(
                        " | "
                ).append(
                        vendor.trim()
                );
            }

            if (version != null
                    && !version.isBlank()) {
                description.append(
                        " | GL "
                ).append(
                        version.trim()
                );
            }

            return description.toString();
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void copyIntoDevelopmentDropoffIfPresent(Path source) {
        Path current = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath();

        // Dev runs may use run/ or run/client/. Search a few parents for the
        // project-local _dropoff folder without coupling release builds to it.
        for (int depth = 0; depth < 4 && current != null; depth++) {
            Path reports = current.resolve("_dropoff").resolve("reports");

            if (Files.isDirectory(reports)) {
                try {
                    String timestamp = Long.toString(System.currentTimeMillis());
                    Path destination = reports.resolve(timestamp + "_" + source.getFileName());
                    Files.copy(source, destination);
                    PotatoRuntime.LOGGER.info(
                            "[Potato] Development report copied to {}",
                            destination.toAbsolutePath()
                    );
                } catch (IOException exception) {
                    PotatoRuntime.LOGGER.warn(
                            "[Potato] Could not copy development report into _dropoff.",
                            exception
                    );
                }
                return;
            }

            current = current.getParent();
        }
    }
}