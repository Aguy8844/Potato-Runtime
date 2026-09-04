package dev.ordovicium.potato.render.engine;
import dev.ordovicium.potato.render.vulkan.VulkanGate10ImmediatePath;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Runtime-wide renderer/backend policy.
 *
 * <p>Potato Engine separates backend preference from backend activation.
 * AUTO prefers native Vulkan whenever the machine and driver expose the
 * required Vulkan capabilities. Visible gameplay remains on the OpenGL
 * compatibility backend until every mandatory Vulkan migration gate is
 * verified. This prevents partial cutovers and black-screen fallback loops.</p>
 */
public final class PotatoRenderEngine {
    public static final String BACKEND_PROPERTY =
            "potato.render.backend";

    private static final long MIB =
            1024L * 1024L;

    private static final int CPU_BALANCED_THREADS =
            8;
    private static final int CPU_HIGH_THREADS =
            16;

    private static final long JVM_BALANCED_MIB =
            3072L;
    private static final long JVM_HIGH_MIB =
            6144L;

    private static final long GPU_BALANCED_MIB =
            3072L;
    private static final long GPU_HIGH_MIB =
            6144L;

    private static final int HARDWARE_CLASS_LOW_SCORE =
            4;
    private static final int HARDWARE_CLASS_BALANCED_SCORE =
            6;
    private static final int HARDWARE_CLASS_HIGH_SCORE =
            8;

    private static volatile PotatoRenderEngineDecision decision =
            fallbackDecision(
                    "NOT_EVALUATED"
            );

    private static volatile boolean evaluated;

    /*
     * POTATO_PATCH_158_ACTIVE_VISIBLE_BACKEND_HARDWARE_CLASS
     *
     * The Vulkan probe may select a discrete adapter even while the visible
     * Minecraft window is still owned by OpenGL on a different GPU. Keep the
     * already-captured OpenGL renderer identity beside the backend decision so
     * runtime budgets can follow the device that actually owns visible work.
     */
    private static volatile String activeOpenGlRendererDescription =
            "";
    private static volatile boolean activeOpenGlIntegratedIntel;
    private static volatile boolean activeOpenGlLowEndIntel;

    private PotatoRenderEngine() {
    }

    public static PotatoRenderBackendPreference requestedPreference() {
        return PotatoRenderBackendPreference.fromProperty(
                System.getProperty(
                        BACKEND_PROPERTY,
                        "AUTO"
                )
        );
    }

    public static PotatoRenderEngineDecision evaluate(
            JsonObject report,
            boolean headlessVulkanCoreActive
    ) {
        PotatoRenderBackendPreference preference =
                requestedPreference();

        int processors =
                Math.max(
                        1,
                        Runtime.getRuntime()
                                .availableProcessors()
                );

        long jvmMaxMiB =
                Runtime.getRuntime()
                        .maxMemory()
                        / MIB;

        DeviceInfo device =
                selectedDevice(
                        report
                );

        boolean probeSucceeded =
                booleanProperty(
                        report,
                        "success"
                );

        boolean dynamicRendering =
                booleanProperty(
                        report,
                        "dynamicRenderingSupported"
                );

        boolean swapchain =
                booleanProperty(
                        report,
                        "swapchainExtensionAvailable"
                );

        boolean graphicsQueue =
                integerProperty(
                        report,
                        "selectedGraphicsQueueFamilyIndex",
                        -1
                ) >= 0;

        boolean presentQueue =
                integerProperty(
                        report,
                        "selectedPresentQueueFamilyIndex",
                        -1
                ) >= 0;

        boolean nativeDevice =
                device.present()
                        && !device.translationLayer()
                        && !"CPU".equals(
                        device.type()
                );

        boolean vulkanEligible =
                probeSucceeded
                        && nativeDevice
                        && dynamicRendering
                        && swapchain
                        && graphicsQueue
                        && presentQueue;

        EnumMap<PotatoRenderReadinessGate, Boolean> readiness =
                new EnumMap<>(
                        PotatoRenderReadinessGate.class
                );

        readiness.put(
                PotatoRenderReadinessGate.VULKAN_CAPABILITY_PROBE,
                probeSucceeded
                        && dynamicRendering
                        && swapchain
                        && graphicsQueue
                        && presentQueue
        );

        readiness.put(
                PotatoRenderReadinessGate.NATIVE_VULKAN_DEVICE,
                nativeDevice
        );

        readiness.put(
                PotatoRenderReadinessGate.WINDOW_HANDOFF_CANDIDATE,
                booleanProperty(
                        report,
                        "mainWindowCutoverCandidateReady"
                )
                        && booleanProperty(
                        report,
                        "handoffCandidateUsesNoApi"
                )
        );

        readiness.put(
                PotatoRenderReadinessGate.CONTEXT_BOOTSTRAP_BOUNDARY,
                booleanProperty(
                        report,
                        "minecraftContextBootstrapDependencyVerified"
                )
                        && booleanProperty(
                        report,
                        "contextBootstrapTransitionBoundaryVerified"
                )
        );

        readiness.put(
                PotatoRenderReadinessGate.RENDERER_INITIALIZATION_DISPATCH,
                booleanProperty(
                        report,
                        "rendererInitializationDispatchVerified"
                )
        );

        readiness.put(
                PotatoRenderReadinessGate.MAIN_TARGET_ABSTRACTION,
                booleanProperty(
                        report,
                        "mainRenderTargetBackendAbstractionReady"
                )
        );

        readiness.put(
                PotatoRenderReadinessGate.PERSISTENT_VULKAN_CORE,
                (headlessVulkanCoreActive
                        && booleanProperty(
                        report,
                        "headlessVulkanResourceRuntimeWindowless"
                )
                        && booleanProperty(
                        report,
                        "headlessVulkanResourceRuntimeSurfaceLess"
                ))
                        || booleanProperty(
                        report,
                        "persistentVulkanRuntimeVerifiedAfterProbeReturn"
                )
        );

        /*
         * Future cutover patches flip these explicit readiness markers only
         * after the corresponding visible path is actually production-ready.
         */
        readiness.put(
                PotatoRenderReadinessGate.VISIBLE_WORLD_DRAW,
                booleanProperty(
                        report,
                        "potatoEngineVulkanWorldDrawReady"
                )
        );

        readiness.put(
                PotatoRenderReadinessGate.TEXTURE_LIFECYCLE,
                booleanProperty(
                        report,
                        "potatoEngineVulkanTextureLifecycleReady"
                )
        );

        readiness.put(
                PotatoRenderReadinessGate.GUI_ENTITY_PARTICLE_PATH,
                booleanProperty(
                        report,
                        "potatoEngineVulkanGuiEntityParticleReady"
                )
        );

        readiness.put(
                PotatoRenderReadinessGate.MAIN_WINDOW_PRESENTATION,
                booleanProperty(
                        report,
                        "minecraftMainWindowActuallyReplaced"
                )
                        && booleanProperty(
                        report,
                        "minecraftWindowUsesNoApi"
                )
                        && booleanProperty(
                        report,
                        "potatoEngineVulkanMainWindowPresentationReady"
                )
        );

        int passed =
                0;

        for (boolean value : readiness.values()) {
            if (value) {
                passed++;
            }
        }

        int total =
                PotatoRenderReadinessGate.values().length;

        int percent =
                total == 0
                        ? 0
                        : Math.round(
                        passed
                                * 100.0f
                                / total
                );

        boolean activationReady =
                passed == total;

        PotatoRenderBackendId preferredBackend;

        if (preference
                == PotatoRenderBackendPreference.OPENGL) {

            preferredBackend =
                    PotatoRenderBackendId.OPENGL_COMPATIBILITY;
        } else if (preference
                == PotatoRenderBackendPreference.VULKAN) {

            preferredBackend =
                    PotatoRenderBackendId.VULKAN;
        } else {
            preferredBackend =
                    vulkanEligible
                            ? PotatoRenderBackendId.VULKAN
                            : PotatoRenderBackendId.OPENGL_COMPATIBILITY;
        }

        PotatoRenderBackendId activeBackend =
                preferredBackend
                == PotatoRenderBackendId.VULKAN
                        && vulkanEligible
                        && activationReady
                        ? PotatoRenderBackendId.VULKAN
                        : PotatoRenderBackendId.OPENGL_COMPATIBILITY;

        String reason =
                decisionReason(
                        preference,
                        preferredBackend,
                        activeBackend,
                        vulkanEligible,
                        activationReady
                );

        String visibleOpenGlRenderer =
                activeBackend
                        == PotatoRenderBackendId.OPENGL_COMPATIBILITY
                        ? stringProperty(
                                report,
                                "rendererInitApiDescriptionAfter",
                                ""
                        )
                        : "";

        activeOpenGlRendererDescription =
                visibleOpenGlRenderer;
        activeOpenGlLowEndIntel =
                isLowEndIntelOpenGlRenderer(
                        visibleOpenGlRenderer
                );
        activeOpenGlIntegratedIntel =
                isIntegratedIntelOpenGlRenderer(
                        visibleOpenGlRenderer
                );

        PotatoHardwareClass hardwareClass =
                classifyHardware(
                        processors,
                        jvmMaxMiB,
                        device
                );

        if (activeBackend
                == PotatoRenderBackendId.OPENGL_COMPATIBILITY) {
            hardwareClass =
                    capForVisibleOpenGlRenderer(
                            hardwareClass,
                            visibleOpenGlRenderer
                    );
        }

        PotatoRenderEngineDecision next =
                new PotatoRenderEngineDecision(
                        preference,
                        preferredBackend,
                        activeBackend,
                        hardwareClass,
                        vulkanEligible,
                        activationReady,
                        passed,
                        total,
                        percent,
                        reason,
                        device.name(),
                        device.type(),
                        device.translationLayer(),
                        device.localMemoryMiB(),
                        processors,
                        jvmMaxMiB,
                        readiness
                );

        decision =
                next;
        evaluated =
                true;

        enrich(
                report
        );

        return next;
    }

    public static PotatoRenderEngineDecision decision() {
        return decision;
    }

    public static String activeOpenGlRendererDescription() {
        return activeOpenGlRendererDescription;
    }

    public static boolean activeOpenGlIntegratedIntel() {
        return activeOpenGlIntegratedIntel;
    }

    public static boolean activeOpenGlLowEndIntel() {
        return activeOpenGlLowEndIntel;
    }

    public static boolean verified() {
        if (!evaluated) {
            return false;
        }

        PotatoRenderEngineDecision current =
                decision;

        if (current == null) {
            return false;
        }

        if (current.activeBackend()
                == PotatoRenderBackendId.VULKAN) {
            return current.vulkanEligible()
                    && current.vulkanActivationReady();
        }

        return current.activeBackend()
                == PotatoRenderBackendId.OPENGL_COMPATIBILITY;
    }

    public static void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        PotatoRenderEngineDecision current =
                decision;

        report.addProperty(
                "potatoEngineInstalled",
                true
        );
        report.addProperty(
                "potatoEngineEvaluated",
                evaluated
        );
        report.addProperty(
                "potatoEngineName",
                "POTATO_ENGINE"
        );
        report.addProperty(
                "potatoEngineBackendProperty",
                BACKEND_PROPERTY
        );
        report.addProperty(
                "potatoEngineBackendPreference",
                current.preference().name()
        );
        report.addProperty(
                "potatoEnginePreferredBackend",
                current.preferredBackend().id()
        );
        report.addProperty(
                "potatoEngineActiveBackend",
                current.activeBackend().id()
        );
        report.addProperty(
                "potatoEngineVulkanPreferredByDefault",
                true
        );
        report.addProperty(
                "potatoEngineVulkanEligible",
                current.vulkanEligible()
        );
        report.addProperty(
                "potatoEngineVulkanActivationReady",
                current.vulkanActivationReady()
        );
        report.addProperty(
                "potatoEngineMigrationGateActive",
                current.migrationGateActive()
        );
        report.addProperty(
                "potatoEngineSelectionReason",
                current.reason()
        );
        report.addProperty(
                "potatoEngineHardwareClass",
                current.hardwareClass().name()
        );
        report.addProperty(
                "potatoEngineHardwareClassUsesActiveVisibleBackend",
                true
        );
        report.addProperty(
                "potatoEngineActiveOpenGlRendererDescription",
                activeOpenGlRendererDescription
        );
        report.addProperty(
                "potatoEngineActiveOpenGlIntegratedIntel",
                activeOpenGlIntegratedIntel
        );
        report.addProperty(
                "potatoEngineActiveOpenGlLowEndIntel",
                activeOpenGlLowEndIntel
        );
        report.addProperty(
                "potatoEngineLogicalProcessors",
                current.logicalProcessors()
        );
        report.addProperty(
                "potatoEngineJvmMaxMiB",
                current.jvmMaxMiB()
        );
        report.addProperty(
                "potatoEngineSelectedDeviceName",
                current.selectedDeviceName()
        );
        report.addProperty(
                "potatoEngineSelectedDeviceType",
                current.selectedDeviceType()
        );
        report.addProperty(
                "potatoEngineSelectedDeviceTranslationLayer",
                current.selectedDeviceTranslationLayer()
        );
        report.addProperty(
                "potatoEngineSelectedDeviceLocalMemoryMiB",
                current.selectedDeviceLocalMemoryMiB()
        );
        report.addProperty(
                "potatoEngineVulkanReadinessPassed",
                current.readinessPassed()
        );
        report.addProperty(
                "potatoEngineVulkanReadinessTotal",
                current.readinessTotal()
        );
        report.addProperty(
                "potatoEngineVulkanReadinessPercent",
                current.readinessPercent()
        );
        report.addProperty(
                "potatoEngineReadinessPolicy",
                "ALL_MANDATORY_GATES_BEFORE_VISIBLE_VULKAN_ACTIVATION"
        );

        StringJoiner pending =
                new StringJoiner(",");

        for (Map.Entry<PotatoRenderReadinessGate, Boolean> entry
                : current.readiness().entrySet()) {

            report.addProperty(
                    "potatoEngineGate."
                            + entry.getKey().id(),
                    entry.getValue()
            );

            if (!entry.getValue()) {
                pending.add(
                        entry.getKey().id()
                );
            }
        }

        report.addProperty(
                "potatoEnginePendingGates",
                pending.toString()
        );
        report.addProperty(
                "potatoEngineVerified",
                verified()
        );
    
        /*
         * Patch 089: the startup PotatoRenderEngineDecision is intentionally
         * immutable for backend activation, but Gate 8/9 become provable only
         * after live rendering. Reconcile report-time readiness without
         * changing the active backend or bypassing the 11/11 activation gate.
         */
        reconcileRuntimeReadiness(report);}

    private static String decisionReason(
            PotatoRenderBackendPreference preference,
            PotatoRenderBackendId preferredBackend,
            PotatoRenderBackendId activeBackend,
            boolean vulkanEligible,
            boolean activationReady
    ) {
        if (preference
                == PotatoRenderBackendPreference.OPENGL) {
            return "OPENGL_FORCED_BY_PROPERTY";
        }

        if (preferredBackend
                == PotatoRenderBackendId.VULKAN
                && !vulkanEligible) {
            return preference
                    == PotatoRenderBackendPreference.VULKAN
                    ? "VULKAN_FORCED_BUT_CAPABILITY_GATE_FAILED"
                    : "AUTO_VULKAN_CAPABILITY_GATE_FAILED";
        }

        if (preferredBackend
                == PotatoRenderBackendId.VULKAN
                && !activationReady) {
            return "VULKAN_PREFERRED_MIGRATION_GATE_PENDING";
        }

        if (activeBackend
                == PotatoRenderBackendId.VULKAN) {
            return "VULKAN_PREFERRED_AND_FULLY_READY";
        }

        return "OPENGL_COMPATIBILITY_SELECTED";
    }


    private static PotatoHardwareClass capForVisibleOpenGlRenderer(
            PotatoHardwareClass baseline,
            String rendererDescription
    ) {
        if (baseline == null) {
            return PotatoHardwareClass.POTATO;
        }

        if (isLowEndIntelOpenGlRenderer(
                rendererDescription
        )) {
            if (baseline == PotatoHardwareClass.HIGH
                    || baseline == PotatoHardwareClass.BALANCED) {
                return PotatoHardwareClass.LOW;
            }

            return baseline;
        }

        if (isIntegratedIntelOpenGlRenderer(
                rendererDescription
        ) && baseline == PotatoHardwareClass.HIGH) {
            return PotatoHardwareClass.BALANCED;
        }

        /* POTATO_PATCH_169_DISCRETE_OPENGL_FLOOR
         * Iris can leave Vulkan DeviceInfo intentionally unavailable while the
         * visible OpenGL context still identifies a discrete GPU. Never infer
         * BALANCED/HIGH from a name alone; only prevent a known discrete renderer
         * from being mislabeled as the absolute POTATO class.
         */
        if (baseline == PotatoHardwareClass.POTATO
                && isDiscreteOpenGlRenderer(rendererDescription)) {
            return PotatoHardwareClass.LOW;
        }

        return baseline;
    }

    private static boolean isDiscreteOpenGlRenderer(
            String rendererDescription
    ) {
        if (rendererDescription == null || rendererDescription.isBlank()) {
            return false;
        }

        String normalized =
                rendererDescription.toLowerCase(Locale.ROOT);

        if (isIntegratedIntelOpenGlRenderer(rendererDescription)) {
            return false;
        }

        return normalized.contains("nvidia")
                || normalized.contains("geforce")
                || normalized.contains("quadro")
                || normalized.contains("radeon")
                || normalized.contains("amd ")
                || normalized.contains("amd/")
                || normalized.contains("intel arc");
    }

    private static boolean isLowEndIntelOpenGlRenderer(
            String rendererDescription
    ) {
        String normalized =
                rendererDescription == null
                        ? ""
                        : rendererDescription
                        .toLowerCase(
                                Locale.ROOT
                        );

        return normalized.contains(
                "intel"
        ) && (normalized.contains(
                "uhd"
        ) || normalized.contains(
                "hd graphics"
        ));
    }

    private static boolean isIntegratedIntelOpenGlRenderer(
            String rendererDescription
    ) {
        String normalized =
                rendererDescription == null
                        ? ""
                        : rendererDescription
                        .toLowerCase(
                                Locale.ROOT
                        );

        return normalized.contains(
                "intel"
        ) && (normalized.contains(
                "uhd"
        ) || normalized.contains(
                "hd graphics"
        ) || normalized.contains(
                "iris"
        ));
    }

    private static PotatoHardwareClass classifyHardware(
            int processors,
            long jvmMaxMiB,
            DeviceInfo device
    ) {
        int score =
                0;

        if (processors
                >= CPU_HIGH_THREADS) {
            score += 2;
        } else if (processors
                >= CPU_BALANCED_THREADS) {
            score += 1;
        }

        if (jvmMaxMiB
                >= JVM_HIGH_MIB) {
            score += 2;
        } else if (jvmMaxMiB
                >= JVM_BALANCED_MIB) {
            score += 1;
        }

        if (device.present()
                && !device.translationLayer()) {
            if ("DISCRETE_GPU".equals(
                    device.type()
            )) {
                score += 4;
            } else if ("INTEGRATED_GPU".equals(
                    device.type()
            )) {
                score += 2;
            }
        } else if (device.present()
                && !"CPU".equals(
                device.type()
        )) {
            score += 1;
        }

        if (device.localMemoryMiB()
                >= GPU_HIGH_MIB) {
            score += 2;
        } else if (device.localMemoryMiB()
                >= GPU_BALANCED_MIB) {
            score += 1;
        }

        if (score
                >= HARDWARE_CLASS_HIGH_SCORE) {
            return PotatoHardwareClass.HIGH;
        }

        if (score
                >= HARDWARE_CLASS_BALANCED_SCORE) {
            return PotatoHardwareClass.BALANCED;
        }

        if (score
                >= HARDWARE_CLASS_LOW_SCORE) {
            return PotatoHardwareClass.LOW;
        }

        return PotatoHardwareClass.POTATO;
    }

    private static DeviceInfo selectedDevice(
            JsonObject report
    ) {
        if (report == null
                || !report.has("devices")
                || !report.has("recommendedDeviceIndex")) {
            return DeviceInfo.absent();
        }

        try {
            int selectedIndex =
                    report.get(
                            "recommendedDeviceIndex"
                    ).getAsInt();

            JsonArray devices =
                    report.getAsJsonArray(
                            "devices"
                    );

            for (JsonElement element : devices) {
                if (!element.isJsonObject()) {
                    continue;
                }

                JsonObject device =
                        element.getAsJsonObject();

                if (!device.has("index")
                        || device.get("index")
                        .getAsInt()
                        != selectedIndex) {
                    continue;
                }

                return new DeviceInfo(
                        true,
                        stringProperty(
                                device,
                                "name",
                                ""
                        ),
                        stringProperty(
                                device,
                                "type",
                                "UNKNOWN"
                        ).toUpperCase(Locale.ROOT),
                        booleanProperty(
                                device,
                                "suspectedTranslationLayer"
                        ),
                        longProperty(
                                device,
                                "deviceLocalMemoryMiB",
                                0L
                        )
                );
            }
        } catch (Throwable ignored) {
            return DeviceInfo.absent();
        }

        return DeviceInfo.absent();
    }

    private static boolean booleanProperty(
            JsonObject object,
            String key
    ) {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()) {
            return false;
        }

        try {
            return object.get(key).getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int integerProperty(
            JsonObject object,
            String key,
            int fallback
    ) {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()) {
            return fallback;
        }

        try {
            return object.get(key).getAsInt();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static long longProperty(
            JsonObject object,
            String key,
            long fallback
    ) {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()) {
            return fallback;
        }

        try {
            return object.get(key).getAsLong();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String stringProperty(
            JsonObject object,
            String key,
            String fallback
    ) {
        if (object == null
                || !object.has(key)
                || object.get(key).isJsonNull()) {
            return fallback;
        }

        try {
            return object.get(key).getAsString();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static PotatoRenderEngineDecision fallbackDecision(
            String reason
    ) {
        EnumMap<PotatoRenderReadinessGate, Boolean> readiness =
                new EnumMap<>(
                        PotatoRenderReadinessGate.class
                );

        for (PotatoRenderReadinessGate gate
                : PotatoRenderReadinessGate.values()) {
            readiness.put(
                    gate,
                    false
            );
        }

        int processors =
                Math.max(
                        1,
                        Runtime.getRuntime()
                                .availableProcessors()
                );

        long jvmMaxMiB =
                Runtime.getRuntime()
                        .maxMemory()
                        / MIB;

        return new PotatoRenderEngineDecision(
                PotatoRenderBackendPreference.AUTO,
                PotatoRenderBackendId.OPENGL_COMPATIBILITY,
                PotatoRenderBackendId.OPENGL_COMPATIBILITY,
                PotatoHardwareClass.POTATO,
                false,
                false,
                0,
                PotatoRenderReadinessGate.values().length,
                0,
                reason,
                "",
                "UNKNOWN",
                false,
                0L,
                processors,
                jvmMaxMiB,
                readiness
        );
    }

    private record DeviceInfo(
            boolean present,
            String name,
            String type,
            boolean translationLayer,
            long localMemoryMiB
    ) {
        static DeviceInfo absent() {
            return new DeviceInfo(
                    false,
                    "",
                    "UNKNOWN",
                    false,
                    0L
            );
        }
    }

    /**
     * Reconciles the immutable startup backend decision with live renderer
     * proof that can only exist after gameplay begins.
     *
     * <p>This method is telemetry/readiness reconciliation only. It never
     * changes {@code decision}, never changes the active backend, and never
     * bypasses the all-11-gates activation policy.</p>
     */
    public static void reconcileRuntimeReadiness(
            JsonObject report
    ) {        /*
         * Patch 091a samples the exact entity/particle OpenGL paths before the
         * existing readiness logic runs. This is telemetry only: Gate 10 stays
         * false until GUI + visible immediate Vulkan ownership are proven.
         */
        VulkanGate10ImmediatePath.enrich(
                report
        );

        if (report == null) {
            return;
        }

        if (!report.has(
                "potatoEngineStartupDecisionReadinessPassed"
        )) {
            report.addProperty(
                    "potatoEngineStartupDecisionReadinessPassed",
                    potatoRuntimeLong(
                            report,
                            "potatoEngineVulkanReadinessPassed"
                    )
            );
        }

        boolean visibleWorldDraw =
                potatoRuntimeBoolean(
                        report,
                        "potatoEngineGate.visibleWorldDraw"
                )
                        || (potatoRuntimeBoolean(
                        report,
                        "potatoEngineVulkanWorldDrawReady"
                )
                        && potatoRuntimeBoolean(
                        report,
                        "potatoEngineVulkanVisibleDrawSuppressionEnabled"
                )
                        && potatoRuntimeBoolean(
                        report,
                        "multiSectionFrameVisiblePresentation"
                )
                        && potatoRuntimeLong(
                        report,
                        "multiSectionFrameVisibleCommitQueuedCount"
                ) > 0L
                        && potatoRuntimeLong(
                        report,
                        "multiSectionFrameVisibleCommitHarvestedCount"
                ) > 0L
                        && potatoRuntimeLong(
                        report,
                        "vulkanOpenGlPresentationBridgeFailureCount"
                ) == 0L);

        long atlasSourceGeneration =
                potatoRuntimeLong(
                        report,
                        "blockTextureAtlasSourceGeneration"
                );

        long atlasUploadedGeneration =
                potatoRuntimeLong(
                        report,
                        "blockTextureAtlasUploadedGeneration"
                );

        long lightmapSourceGeneration =
                potatoRuntimeLong(
                        report,
                        "blockTextureLightmapSourceGeneration"
                );

        long lightmapUploadedGeneration =
                potatoRuntimeLong(
                        report,
                        "blockTextureLightmapUploadedGeneration"
                );

        long liveLifecycleMinimumCompletions =
                Math.max(
                        1L,
                        potatoRuntimeLong(
                                report,
                                "blockTextureUploadLiveLifecycleMinCompletions"
                        )
                );

        boolean currentAtlasGenerationExact =
                atlasSourceGeneration > 0L
                        && atlasSourceGeneration
                        == atlasUploadedGeneration;

        boolean currentLightmapGenerationExact =
                lightmapSourceGeneration > 0L
                        && lightmapSourceGeneration
                        == lightmapUploadedGeneration;

        /*
         * Patch 132 Gate-9 semantics:
         *
         * Texture LIFECYCLE readiness is a capability/liveness proof. It must
         * demonstrate repeated real live generations crossing prepare -> record
         * -> Vulkan submit -> completion without failures or gameplay waits.
         *
         * It is intentionally NOT the same thing as asking whether the very last
         * CPU lightmap generation at an arbitrary report instant has already
         * been consumed. When no Vulkan SOLID commit owns the visible layer,
         * OpenGL remains authoritative and a newer captured CPU generation is
         * not a failed Vulkan lifecycle.
         *
         * Exact current-generation equality remains reported below and can still
         * be required by any future per-frame ownership policy.
         */
        boolean textureLifecycle =
                potatoRuntimeBoolean(
                        report,
                        "potatoEngineGate.textureLifecycle"
                )
                        || (potatoRuntimeBoolean(
                        report,
                        "potatoEngineVulkanTextureLifecycleReady"
                )
                        && potatoRuntimeBoolean(
                        report,
                        "blockTextureUploadVerified"
                )
                        && potatoRuntimeBoolean(
                        report,
                        "blockTextureUploadLiveGenerationSyncEnabled"
                )
                        && potatoRuntimeBoolean(
                        report,
                        "blockTextureUploadLiveLifecycleQualified"
                )
                        && potatoRuntimeLong(
                        report,
                        "blockTextureUploadLiveSyncCompletedCount"
                ) >= liveLifecycleMinimumCompletions
                        && potatoRuntimeLong(
                        report,
                        "blockTextureUploadLiveLifecycleQualifiedLightmapGeneration"
                ) > 0L
                        && potatoRuntimeLong(
                        report,
                        "blockTextureUploadFailureCount"
                ) == 0L
                        && !potatoRuntimeBoolean(
                        report,
                        "blockTextureUploadGameplayFenceWaitUsed"
                )
                        && !potatoRuntimeBoolean(
                        report,
                        "blockTextureUploadDeviceWaitIdleUsed"
                ));

        boolean guiEntityParticlePath =
                potatoRuntimeBoolean(
                        report,
                        "potatoEngineGate.guiEntityParticlePath"
                )
                        || potatoRuntimeBoolean(
                        report,
                        "potatoEngineVulkanGuiEntityParticleReady"
                );

        boolean mainWindowPresentation =
                potatoRuntimeBoolean(
                        report,
                        "potatoEngineGate.mainWindowPresentation"
                )
                        || potatoRuntimeBoolean(
                        report,
                        "potatoEngineVulkanMainWindowPresentationReady"
                );

        report.addProperty(
                "potatoEngineGate.visibleWorldDraw",
                visibleWorldDraw
        );
        report.addProperty(
                "potatoEngineGate.textureLifecycle",
                textureLifecycle
        );
        report.addProperty(
                "potatoEngineGate9QualificationMode",
                "REPEATED_COMPLETED_LIVE_GENERATION_LIFECYCLE_PROOF"
        );
        report.addProperty(
                "potatoEngineGate9CurrentAtlasGenerationExact",
                currentAtlasGenerationExact
        );
        report.addProperty(
                "potatoEngineGate9CurrentLightmapGenerationExact",
                currentLightmapGenerationExact
        );
        report.addProperty(
                "potatoEngineGate9CurrentLightmapGenerationLag",
                Math.max(
                        0L,
                        lightmapSourceGeneration
                                - lightmapUploadedGeneration
                )
        );
        report.addProperty(
                "potatoEngineGate9CurrentGenerationEqualityRequiredForLifecycleReadiness",
                false
        );
        report.addProperty(
                "potatoEngineGate.guiEntityParticlePath",
                guiEntityParticlePath
        );
        report.addProperty(
                "potatoEngineGate.mainWindowPresentation",
                mainWindowPresentation
        );

        String[] gateIds = {
                "vulkanCapabilityProbe",
                "nativeVulkanDevice",
                "windowHandoffCandidate",
                "contextBootstrapBoundary",
                "rendererInitializationDispatch",
                "mainTargetAbstraction",
                "persistentVulkanCore",
                "visibleWorldDraw",
                "textureLifecycle",
                "guiEntityParticlePath",
                "mainWindowPresentation"
        };

        int passed = 0;
        String pending = "";

        for (String gateId : gateIds) {
            boolean ready =
                    potatoRuntimeBoolean(
                            report,
                            "potatoEngineGate." + gateId
                    );

            if (ready) {
                passed++;
            } else {
                if (!pending.isEmpty()) {
                    pending += ",";
                }

                pending += gateId;
            }
        }

        int total = gateIds.length;

        report.addProperty(
                "potatoEngineVulkanReadinessPassed",
                passed
        );
        report.addProperty(
                "potatoEngineVulkanReadinessTotal",
                total
        );
        report.addProperty(
                "potatoEngineVulkanReadinessPercent",
                total > 0
                        ? (passed * 100) / total
                        : 0
        );
        report.addProperty(
                "potatoEnginePendingGates",
                pending
        );

        report.addProperty(
                "potatoEngineRuntimeReadinessReconciled",
                true
        );
        report.addProperty(
                "potatoEngineRuntimeReadinessPassed",
                passed
        );
        report.addProperty(
                "potatoEngineRuntimeReadinessTotal",
                total
        );
        report.addProperty(
                "potatoEngineRuntimeGate.visibleWorldDraw",
                visibleWorldDraw
        );
        report.addProperty(
                "potatoEngineRuntimeGate.textureLifecycle",
                textureLifecycle
        );
        report.addProperty(
                "potatoEngineRuntimeGate.guiEntityParticlePath",
                guiEntityParticlePath
        );
        report.addProperty(
                "potatoEngineRuntimeGate.mainWindowPresentation",
                mainWindowPresentation
        );
        report.addProperty(
                "potatoEngineRuntimeAllMandatoryGatesReady",
                passed == total
        );
        report.addProperty(
                "potatoEngineRuntimeReadinessSource",
                "STARTUP_DECISION_PLUS_LIVE_RENDERER_PROOF"
        );
        report.addProperty(
                "potatoEngineRuntimeActivationStillRequiresAll11",
                true
        );
        report.addProperty(
                "potatoEngineRuntimeActiveBackendUnchangedByReconciliation",
                true
        );
        report.addProperty(
                "potatoEngineRuntimeNextMilestone",
                "POTATO_ENGINE_GUI_ENTITY_PARTICLE_CUTOVER"
        );
    }

    private static boolean potatoRuntimeBoolean(
            JsonObject report,
            String name
    ) {
        try {
            return report.has(name)
                    && report.get(name) != null
                    && !report.get(name).isJsonNull()
                    && report.get(name).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static long potatoRuntimeLong(
            JsonObject report,
            String name
    ) {
        try {
            return report.has(name)
                    && report.get(name) != null
                    && !report.get(name).isJsonNull()
                    ? report.get(name).getAsLong()
                    : 0L;
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }
}
