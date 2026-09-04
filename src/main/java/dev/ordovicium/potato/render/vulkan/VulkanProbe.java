package dev.ordovicium.potato.render.vulkan;

import dev.ordovicium.potato.render.backend.RenderBackendBoundary;
import dev.ordovicium.potato.render.backend.RenderBackendBoundaryManifest;

import dev.ordovicium.potato.diagnostics.PotatoWindowBootstrapTrace;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.Version;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRExternalMemory.VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphore.VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.VK_API_VERSION_1_3;

/**
 * Startup-only Vulkan capability probe.
 *
 * <p>The probe validates the full Vulkan window-system path on a hidden
 * GLFW_NO_API window without taking ownership of Minecraft's OpenGL window.</p>
 */
public final class VulkanProbe {
    private static final Gson GSON =
            new GsonBuilder().setPrettyPrinting().create();

    private static final String VULKAN_BINDING_VERSION = "3.3.3";
    private static final int VULKAN_INSTANCE_CAPABILITY_STACK_BYTES = 2 * 1024 * 1024;

    private VulkanProbe() {
    }

    public static JsonObject probe() {
        JsonObject report = new JsonObject();

        report.addProperty("probeVersion", 69);
        report.addProperty("backend", "VULKAN");
        report.addProperty("probeMode", "POTATO_ENGINE_TRANSITION_RECOVERY_VULKAN_PREFERRED");
        report.addProperty("cutoverSprintStage", 11);
        report.addProperty(
                "surfaceTileResearchValidationDefault",
                false
        );
        report.addProperty(
                "surfaceTileResearchValidationDebugProperty",
                "potato.debug.surfaceTileAnalysis"
        );
        report.addProperty(
                "chunkUploadPolicyChangedByStage2",
                true
        );
        report.addProperty(
                "chunkUploadPolicyChangedByStage3",
                true
        );
        report.addProperty(
                "chunkUploadLowLatencyPolicy",
                "ULTRA_LOW_END_MIN_1_MAX_4_SOFT_1_0MS"
        );
        report.addProperty(
                "ultraLowEndFrameFirstPolicy",
                true
        );
        report.addProperty(
                "nextPerformanceMilestone",
                "POTATO_ENGINE_VULKAN_VISIBLE_LAYER_EXPANSION"
        );
        report.addProperty(
                "adaptiveRuntimeGovernorEnabled",
                true
        );
        report.addProperty(
                "adaptiveRuntimeGovernorPerUploadPass",
                true
        );
        report.addProperty(
                "adaptiveRuntimeGovernorInputs",
                "WINDOWED_UPLOAD_COST,COUNT_HITS,TIME_HITS,QUEUE_PRESSURE,CPU_CAP"
        );
        report.addProperty(
                "adaptiveRuntimeGovernorPreservesUserRenderDistance",
                true
        );
        report.addProperty(
                "secondaryPresentationLifetimeAllowedDuringOpenGlGameplay",
                false
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeNext",
                false
        );
        report.addProperty(
                "headlessVulkanResourceRuntimeImplementedByPatch057a",
                true
        );
        report.addProperty(
                "dedicatedTransferQueueRequestedByPatch057a",
                true
        );
        report.addProperty(
                "visibleLodStage1ImplementedByPatch058",
                true
        );
        report.addProperty(
                "streamingCatchupGovernorImplementedByPatch059",
                true
        );
        report.addProperty(
                "uploadPassCostAccountingFixedByPatch059",
                true
        );
        report.addProperty(
                "lodStreamingYieldImplementedByPatch059",
                true
        );
        report.addProperty(
                "lodSingleTierProxyCompactionImplementedByPatch059",
                true
        );
        report.addProperty(
                "visibleLodStage2ImplementedByPatch059",
                true
        );
        report.addProperty(
                "trueUploadRunnableCostImplementedByPatch060",
                true
        );
        report.addProperty(
                "parallelChunkCompileExecutorImplementedByPatch060",
                true
        );
        report.addProperty(
                "adaptiveSectionBuilderPoolImplementedByPatch060",
                true
        );
        report.addProperty(
                "deferredLodCatchupImplementedByPatch060",
                true
        );
        report.addProperty(
                "separateDeferredLodCapturePoolImplementedByPatch061",
                true
        );
        report.addProperty(
                "framePacedLodInstallImplementedByPatch061",
                true
        );
        report.addProperty(
                "temporalActualDrawOcclusionImplementedByPatch061",
                true
        );
        report.addProperty(
                "potatoEngineBackendSelectorImplementedByPatch062",
                true
        );
        report.addProperty(
                "potatoEngineTransitionRecoveryImplementedByPatch063",
                true
        );
        report.addProperty(
                "lodTransitionCapacityRecoveryImplementedByPatch063",
                true
        );
        report.addProperty(
                "conservativeAnySamplesOcclusionImplementedByPatch063",
                true
        );
        report.addProperty(
                "potatoEngineVisibleVulkanCutoverStillSafetyGatedByPatch063",
                true
        );
        report.addProperty(
                "potatoEngineVulkanPreferredByDefault",
                true
        );
        report.addProperty(
                "potatoEngineBackendProperty",
                "potato.render.backend"
        );
        report.addProperty(
                "potatoEngineBackendValues",
                "AUTO,VULKAN,OPENGL"
        );
        report.addProperty(
                "potatoEngineVisibleVulkanActivationSafetyGated",
                true
        );
        report.addProperty(
                "potatoEnginePartialVulkanCutoverForbidden",
                true
        );
        report.addProperty(
                "potatoEngineVulkanWorldDrawReady",
                false
        );
        report.addProperty(
                "potatoEngineVulkanTextureLifecycleReady",
                false
        );
        report.addProperty(
                "potatoEngineVulkanGuiEntityParticleReady",
                false
        );
        report.addProperty(
                "potatoEngineVulkanMainWindowPresentationReady",
                false
        );
        report.addProperty(
                "visibleLodDefaultProfile",
                "ADAPTIVE"
        );
        report.addProperty(
                "visibleLodRuntimeMutableProfile",
                true
        );
        report.addProperty(
                "visibleLodSolidLayerSubstitution",
                true
        );
        report.addProperty(
                "visibleLodAsyncCpuProxyBuild",
                true
        );
        report.addProperty(
                "visibleLodPreservesMinecraftRenderDistance",
                true
        );
        report.addProperty(
                "visibilityOcclusionNext",
                false
        );
        report.addProperty(
                "visibilityTemporalOcclusionStage1Active",
                true
        );
        report.addProperty(
                "visibilityHeadlessVulkanOcclusionStage2Next",
                true
        );
        report.addProperty(
                "visibilityLodAfterOcclusion",
                false
        );
        report.addProperty("readOnlyProbe", false);
        report.addProperty("rendersAnything", true);
        report.addProperty("writesSwapchainPixels", true);
        report.addProperty("touchesMinecraftWindow", true);
        report.addProperty("mutatesMinecraftWindow", false);
        report.addProperty("rendersToMinecraftWindow", false);
        report.addProperty("lwjglCoreVersion", Version.getVersion());
        report.addProperty(
                "lwjglVulkanBindingVersion",
                VULKAN_BINDING_VERSION
        );

        VulkanProbeOptions options =
                VulkanProbeOptions.fromEnvironment();

        report.addProperty(
                "visibleFrameVerificationRequested",
                options.visibleFrameVerification()
        );
        report.addProperty(
                "visibleFrameVerificationDurationMillis",
                options.visibleDurationMillis()
        );

        PotatoWindowBootstrapTrace.enrich(report);

        if (!PotatoWindowBootstrapTrace.seamObserved()) {
            throw new VulkanProbeException(
                    "MINECRAFT_WINDOW_BOOTSTRAP_TRACE",
                    "Potato did not observe Minecraft's native GLFW window creation seam."
            );
        }

        if (!PotatoWindowBootstrapTrace.mainWindowOwnsOpenGlContext()) {
            throw new VulkanProbeException(
                    "MINECRAFT_WINDOW_BOOTSTRAP_TRACE",
                    "Expected Minecraft baseline window to own an OpenGL context during Patch 014."
            );
        }

        report.addProperty(
                "minecraftWindowBootstrapSeamVerified",
                true
        );

        RenderBackendBoundaryManifest.enrich(report);
        VulkanHandoffCandidate.enrich(report);
        VulkanEarlyDeviceLimits.enrich(report);
        VulkanContextBootstrapRehearsal.enrich(report);

        if (!VulkanContextBootstrapRehearsal.transitionBoundaryVerified()) {
            report.addProperty(
                    "minecraftContextBootstrapDependencyVerified",
                    false
            );
            report.addProperty(
                    "mainWindowVulkanMutationReady",
                    false
            );
            report.addProperty(
                    "stage",
                    "MINECRAFT_CONTEXT_BOOTSTRAP_TRANSITION_NOT_VERIFIED"
            );
            report.addProperty(
                    "success",
                    false
            );
            report.addProperty(
                    "fallbackBackend",
                    "OPENGL_BASELINE"
            );
            report.addProperty(
                    "fallbackReason",
                    "Context-bootstrap transition rehearsal did not satisfy all non-destructive prerequisites."
            );

            return report;
        }

        report.addProperty(
                "minecraftContextBootstrapDependencyVerified",
                true
        );
        report.addProperty(
                "minecraftContextBootstrapBypassVerified",
                false
        );
        report.addProperty(
                "minecraftContextBootstrapBypassDeferredUntilRendererCutover",
                true
        );

        dev.ordovicium.potato.render.backend.RendererInitializationRehearsal
                .enrich(report);

        if (!dev.ordovicium.potato.render.backend.RendererInitializationRehearsal
                .dispatchVerified()) {

            report.addProperty(
                    "rendererInitializationDispatchVerified",
                    false
            );
            report.addProperty(
                    "mainWindowVulkanMutationReady",
                    false
            );
            report.addProperty(
                    "stage",
                    "RENDERER_INITIALIZATION_TRANSITION_NOT_VERIFIED"
            );
            report.addProperty(
                    "success",
                    false
            );
            report.addProperty(
                    "fallbackBackend",
                    "OPENGL_BASELINE"
            );
            report.addProperty(
                    "fallbackReason",
                    "Potato renderer initialization dispatch did not preserve the required OpenGL transition contract."
            );

            return report;
        }

        report.addProperty(
                "rendererInitializationDependencyVerified",
                true
        );
        report.addProperty(
                "rendererInitializationDispatchVerified",
                true
        );
        report.addProperty(
                "rendererInitializationBypassVerified",
                true
        );
        report.addProperty(
                "rendererInitializationBypassDeferredUntilGlxSplit",
                false
        );
        report.addProperty(
                "rendererInitializationOpenGlTransitionActive",
                true
        );

        dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics
                .enrich(report);

        if (!dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics
                .mainTargetOwnershipVerified()) {

            report.addProperty(
                    "mainRenderTargetOwnershipVerified",
                    false
            );
            report.addProperty(
                    "mainWindowVulkanMutationReady",
                    false
            );
            report.addProperty(
                    "stage",
                    "MAIN_RENDER_TARGET_OWNERSHIP_NOT_VERIFIED"
            );
            report.addProperty(
                    "success",
                    false
            );
            report.addProperty(
                    "fallbackBackend",
                    "OPENGL_BASELINE"
            );
            report.addProperty(
                    "fallbackReason",
                    "MainTarget lifecycle/state ownership rehearsal did not observe a complete initial OpenGL target allocation."
            );

            return report;
        }

        report.addProperty(
                "mainRenderTargetOwnershipVerified",
                true
        );
        report.addProperty(
                "mainRenderTargetBackendAbstractionReady",
                true
        );

        if (!VulkanHandoffCandidate.readyForVulkanProbe()) {
            throw new VulkanProbeException(
                    "POTATO_SURFACE_TILE_TEXTURED_DECODE_DRAW",
                    "The NeoForge handoff did not produce a valid hidden GLFW_NO_API candidate."
            );
        }

        report.addProperty(
                "earlyDisplayIsolationStrategySelected",
                true
        );
        report.addProperty(
                "earlyDisplayIsolationStrategy",
                "TEMPORARY_OPENGL_WINDOW_THEN_NO_API_REPLACEMENT"
        );

        if (!RenderBackendBoundaryManifest.hasExactlyOneCutoverBlocker()) {
            throw new VulkanProbeException(
                    "POTATO_SURFACE_TILE_TEXTURED_DECODE_DRAW",
                    "Backend contract must identify exactly one current main-window cutover blocker."
            );
        }

        report.addProperty(
                "backendBoundaryContractVerified",
                true
        );
        report.addProperty(
                "backendCurrentCutoverBlocker",
                RenderBackendBoundaryManifest.cutoverBlocker().id()
        );
        report.addProperty(
                "mainWindowCutoverCandidateReady",
                VulkanHandoffCandidate.readyForVulkanProbe()
        );
        report.addProperty(
                "mainWindowCutoverCandidateRequiresNoApi",
                true
        );
        report.addProperty(
                "mainWindowCutoverMutationEnabled",
                false
        );
        report.addProperty(
                "mainWindowCutoverNextMilestone",
                "POTATO_ENGINE_VULKAN_VISIBLE_LAYER_EXPANSION"
        );

        boolean loaderReady = false;

        try (VulkanProbeContext context = new VulkanProbeContext(report)) {
            report.addProperty("stage", "QUERY_LOADER_VERSION");

            int loaderVersion = VK.getInstanceVersionSupported();
            loaderReady = true;

            report.addProperty("loaderAvailable", true);
            report.addProperty(
                    "loaderApiVersionRaw",
                    Integer.toUnsignedLong(loaderVersion)
            );
            report.addProperty(
                    "loaderApiVersion",
                    VulkanFormat.apiVersion(loaderVersion)
            );

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer requiredExtensions =
                        VulkanPresentationProbe.requiredInstanceExtensions(
                                report
                        );

                VkInstance instance = createInstance(
                        loaderVersion,
                        requiredExtensions,
                        stack,
                        report
                );
                context.own(instance);

                try (VulkanPresentationProbe presentation =
                             VulkanPresentationProbe.create(
                                     instance,
                                     stack,
                                     report,
                                     options
                             )) {

                    VulkanDeviceCatalog.Selection deviceSelection =
                            VulkanDeviceCatalog.scan(
                                    instance,
                                    stack,
                                    report
                            );

                    VkPhysicalDevice physicalDevice =
                            deviceSelection.physicalDevice();

                    VulkanFeatureSet featureSet =
                            VulkanFeatureSet.queryRequired(
                                    physicalDevice,
                                    stack,
                                    report
                            );

                    VulkanDeviceExtensions.requireSwapchain(
                            physicalDevice,
                            stack,
                            report
                    );

                    VulkanQueueFamilySelector.Selection queueSelection =
                            VulkanQueueFamilySelector.select(
                                    physicalDevice,
                                    presentation.surface(),
                                    stack,
                                    report
                            );

                    presentation.showForVerificationIfRequested();

                    VulkanSwapchainSupport.Configuration swapchainConfiguration =
                            VulkanSwapchainSupport.query(
                                    physicalDevice,
                                    presentation.surface(),
                                    presentation.windowHandle(),
                                    stack,
                                    report
                            );

                    VkDevice device = createLogicalDevice(
                            physicalDevice,
                            queueSelection,
                            featureSet,
                            stack,
                            report
                    );
                    context.own(device);

                    QueueHandles queues = retrieveQueues(
                            device,
                            queueSelection,
                            stack,
                            report
                    );

                    VulkanFrameSession frameSession =
                            VulkanFrameLoopProbe.createAndValidate(
                                    physicalDevice,
                                    device,
                                    queues.graphicsQueue(),
                                    queues.presentQueue(),
                                    queueSelection,
                                    presentation.surface(),
                                    swapchainConfiguration,
                                    presentation,
                                    options,
                                    stack,
                                    report
                            );

                    VulkanRuntimeContext runtimeContext =
                            VulkanRuntimeContext.adopt(
                                    context,
                                    presentation,
                                    frameSession,
                                    physicalDevice,
                                    queues.graphicsQueue(),
                                    queues.presentQueue(),
                                    queues.transferQueue(),
                                    queueSelection,
                                    report
                            );

                    boolean runtimeInstalled = false;

                    try {
                        VulkanRuntimeManager.install(
                                runtimeContext
                        );

                        runtimeInstalled = true;
                    } finally {
                        if (!runtimeInstalled) {
                            runtimeContext.close();
                        }
                    }

                    report.addProperty(
                            "persistentVulkanRuntimeInstalledBeforeProbeReturn",
                            true
                    );
                    report.addProperty(
                            "persistentVulkanRuntimeProbeScopeExitPending",
                            true
                    );

                    report.addProperty(
                            "stage",
                            "POTATO_ENGINE_BACKEND_SELECTION_ARMED"
                    );
                    report.addProperty("success", true);
                }

                return report;
            }
        } catch (VulkanProbeException exception) {
            report.addProperty("success", false);
            report.addProperty("loaderAvailable", loaderReady);
            report.addProperty("stage", exception.stage());
            report.addProperty("failureStage", exception.stage());
            report.addProperty(
                    "errorType",
                    exception.getClass().getName()
            );
            report.addProperty("error", exception.getMessage());

            PotatoRuntime.LOGGER.warn(
                    "[Potato/Vulkan] Probe failed at {}: {}",
                    exception.stage(),
                    exception.getMessage()
            );

            return report;
        } catch (Throwable throwable) {
            String failureStage = currentStage(report);

            report.addProperty("success", false);
            report.addProperty("loaderAvailable", loaderReady);
            report.addProperty("failureStage", failureStage);
            report.addProperty(
                    "errorType",
                    throwable.getClass().getName()
            );
            report.addProperty(
                    "error",
                    String.valueOf(throwable.getMessage())
            );
            report.addProperty(
                    "stackTrace",
                    stackTraceToString(throwable)
            );

            PotatoRuntime.LOGGER.warn(
                    "[Potato/Vulkan] Unexpected probe failure at {}. Potato remains on OpenGL.",
                    failureStage,
                    throwable
            );

            return report;
        }
    }

    public static void writeReport(
            Path path,
            JsonObject report
    ) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(
                path,
                GSON.toJson(report),
                StandardCharsets.UTF_8
        );
    }

    private static VkInstance createInstance(
            int loaderVersion,
            PointerBuffer requiredExtensions,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty(
                "stage",
                "BUILD_INSTANCE_CREATE_INFO"
        );

        int requestedVersion =
                Math.min(loaderVersion, VK_API_VERSION_1_3);

        VkApplicationInfo applicationInfo =
                VkApplicationInfo.calloc(stack)
                        .sType$Default()
                        .pApplicationName(stack.UTF8("Potato Runtime"))
                        .applicationVersion(1)
                        .pEngineName(
                                stack.UTF8("Potato Runtime Probe")
                        )
                        .engineVersion(1)
                        .apiVersion(requestedVersion);

        VkInstanceCreateInfo createInfo =
                VkInstanceCreateInfo.calloc(stack)
                        .sType$Default()
                        .pApplicationInfo(applicationInfo)
                        .ppEnabledExtensionNames(requiredExtensions);

        PointerBuffer pointer = stack.mallocPointer(1);

        report.addProperty("stage", "VK_CREATE_INSTANCE");

        int result = vkCreateInstance(
                createInfo,
                null,
                pointer
        );
        report.addProperty("vkCreateInstanceResult", result);

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "VK_CREATE_INSTANCE",
                    "vkCreateInstance failed with VkResult " + result
            );
        }

        report.addProperty("stage", "WRAP_VK_INSTANCE");

        long instanceHandle = pointer.get(0);
        boolean wrapped = false;

        LwjglVulkanMemoryStackScope stackScope =
                LwjglVulkanMemoryStackScope.open(
                        VULKAN_INSTANCE_CAPABILITY_STACK_BYTES
                );

        report.addProperty(
                "vulkanProbeExpandedInstanceStackRequested",
                true
        );
        report.addProperty(
                "vulkanProbeInstanceStackPreviousBytes",
                stackScope.previousSizeBytes()
        );
        report.addProperty(
                "vulkanProbeInstanceStackExpandedBytes",
                stackScope.expandedSizeBytes()
        );
        report.addProperty(
                "vulkanProbeInstanceStackTlsReplaced",
                stackScope.isInstalled()
        );
        report.addProperty(
                "vulkanProbeInstanceStackTlsRestored",
                false
        );
        report.addProperty(
                "vulkanProbeInstanceWrapperSucceeded",
                false
        );

        try {
            VkInstance instance =
                    new VkInstance(
                            instanceHandle,
                            createInfo
                    );

            wrapped = true;
            report.addProperty(
                    "vulkanProbeInstanceWrapperSucceeded",
                    true
            );

            return instance;
        } finally {
            stackScope.close();
            report.addProperty(
                    "vulkanProbeInstanceStackTlsRestored",
                    stackScope.isRestored()
            );

            if (!wrapped) {
                boolean destroyed =
                        VulkanEarlyDeviceLimits.destroyRawInstanceHandle(
                                instanceHandle
                        );
                report.addProperty(
                        "vulkanProbeFailedWrapperRawInstanceDestroyed",
                        destroyed
                );
            }
        }
    }

    private static VkDevice createLogicalDevice(
            VkPhysicalDevice physicalDevice,
            VulkanQueueFamilySelector.Selection queues,
            VulkanFeatureSet featureSet,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty(
                "stage",
                "BUILD_DEVICE_CREATE_INFO"
        );

        Set<Integer> uniqueFamilies = new LinkedHashSet<>();
        uniqueFamilies.add(queues.graphicsFamilyIndex());
        uniqueFamilies.add(queues.presentFamilyIndex());
        uniqueFamilies.add(queues.transferFamilyIndex());

        VkDeviceQueueCreateInfo.Buffer queueCreateInfos =
                VkDeviceQueueCreateInfo.calloc(
                        uniqueFamilies.size(),
                        stack
                );

        FloatBuffer priority = stack.floats(1.0f);

        int infoIndex = 0;

        for (int familyIndex : uniqueFamilies) {
            queueCreateInfos.get(infoIndex)
                    .sType$Default()
                    .queueFamilyIndex(familyIndex)
                    .pQueuePriorities(priority);

            infoIndex++;
        }

        boolean win32InteropExtensionsAvailable =
                report.has(
                        "vulkanOpenGlWin32InteropDeviceExtensionsAvailable"
                )
                        && report.get(
                        "vulkanOpenGlWin32InteropDeviceExtensionsAvailable"
                ).getAsBoolean();

        PointerBuffer deviceExtensions =
                stack.mallocPointer(
                        win32InteropExtensionsAvailable
                                ? 5
                                : 1
                );

        deviceExtensions.put(
                stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
        );

        if (win32InteropExtensionsAvailable) {
            deviceExtensions.put(
                    stack.UTF8(VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME)
            );
            deviceExtensions.put(
                    stack.UTF8(VK_KHR_EXTERNAL_MEMORY_WIN32_EXTENSION_NAME)
            );
            deviceExtensions.put(
                    stack.UTF8(VK_KHR_EXTERNAL_SEMAPHORE_EXTENSION_NAME)
            );
            deviceExtensions.put(
                    stack.UTF8(VK_KHR_EXTERNAL_SEMAPHORE_WIN32_EXTENSION_NAME)
            );
        }

        deviceExtensions.flip();

        VkPhysicalDeviceVulkan13Features enabledVulkan13Features =
                featureSet.createEnableChain(stack);

        VkDeviceCreateInfo createInfo =
                VkDeviceCreateInfo.calloc(stack)
                        .sType$Default()
                        .pNext(enabledVulkan13Features.address())
                        .pQueueCreateInfos(queueCreateInfos)
                        .ppEnabledExtensionNames(deviceExtensions);

        report.addProperty(
                "dynamicRenderingEnabled",
                featureSet.dynamicRendering()
        );

        PointerBuffer pointer = stack.mallocPointer(1);

        report.addProperty(
                "logicalDeviceRequestedQueueFamilyCount",
                uniqueFamilies.size()
        );
        report.addProperty(
                "swapchainExtensionEnabled",
                true
        );
        report.addProperty(
                "vulkanOpenGlWin32InteropDeviceExtensionsEnabled",
                win32InteropExtensionsAvailable
        );
        report.addProperty("stage", "VK_CREATE_DEVICE");

        int result = vkCreateDevice(
                physicalDevice,
                createInfo,
                null,
                pointer
        );
        report.addProperty("vkCreateDeviceResult", result);

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "VK_CREATE_DEVICE",
                    "vkCreateDevice failed with VkResult " + result
            );
        }

        report.addProperty("stage", "WRAP_VK_DEVICE");

        VkDevice device = new VkDevice(
                pointer.get(0),
                physicalDevice,
                createInfo
        );

        report.addProperty("logicalDeviceCreated", true);

        return device;
    }

    private static QueueHandles retrieveQueues(
            VkDevice device,
            VulkanQueueFamilySelector.Selection selection,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "GET_GRAPHICS_QUEUE");

        VkQueue graphicsQueue = getQueue(
                device,
                selection.graphicsFamilyIndex(),
                stack,
                "graphics"
        );

        report.addProperty("graphicsQueueRetrieved", true);
        report.addProperty(
                "graphicsQueueHandleNonZero",
                graphicsQueue.address() != NULL
        );

        VkQueue presentQueue;

        if (selection.sharedFamily()) {
            presentQueue = graphicsQueue;
            report.addProperty(
                    "presentQueueAliasesGraphicsQueue",
                    true
            );
        } else {
            report.addProperty("stage", "GET_PRESENT_QUEUE");

            presentQueue = getQueue(
                    device,
                    selection.presentFamilyIndex(),
                    stack,
                    "present"
            );

            report.addProperty(
                    "presentQueueAliasesGraphicsQueue",
                    false
            );
        }

        report.addProperty("presentQueueRetrieved", true);
        report.addProperty(
                "presentQueueHandleNonZero",
                presentQueue.address() != NULL
        );

        VkQueue transferQueue;

        if (selection.transferSharesGraphicsFamily()) {
            transferQueue = graphicsQueue;
            report.addProperty(
                    "transferQueueAliasesGraphicsQueue",
                    true
            );
        } else if (selection.transferSharesPresentFamily()) {
            transferQueue = presentQueue;
            report.addProperty(
                    "transferQueueAliasesGraphicsQueue",
                    false
            );
            report.addProperty(
                    "transferQueueAliasesPresentQueue",
                    true
            );
        } else {
            report.addProperty(
                    "stage",
                    "GET_TRANSFER_QUEUE"
            );

            transferQueue = getQueue(
                    device,
                    selection.transferFamilyIndex(),
                    stack,
                    "transfer"
            );

            report.addProperty(
                    "transferQueueAliasesGraphicsQueue",
                    false
            );
            report.addProperty(
                    "transferQueueAliasesPresentQueue",
                    false
            );
        }

        report.addProperty(
                "transferQueueRetrieved",
                true
        );
        report.addProperty(
                "transferQueueHandleNonZero",
                transferQueue.address() != NULL
        );
        report.addProperty(
                "transferQueueFamilyDedicated",
                selection.dedicatedTransferFamily()
        );

        return new QueueHandles(
                graphicsQueue,
                presentQueue,
                transferQueue
        );
    }

    private static VkQueue getQueue(
            VkDevice device,
            int familyIndex,
            MemoryStack stack,
            String role
    ) {
        PointerBuffer pointer = stack.mallocPointer(1);

        vkGetDeviceQueue(
                device,
                familyIndex,
                0,
                pointer
        );

        long handle = pointer.get(0);

        if (handle == NULL) {
            throw new VulkanProbeException(
                    "GET_" + role.toUpperCase(java.util.Locale.ROOT) + "_QUEUE",
                    "vkGetDeviceQueue returned a null "
                            + role
                            + " queue handle."
            );
        }

        return new VkQueue(handle, device);
    }

    private static String currentStage(JsonObject report) {
        if (report.has("stage")) {
            return report.get("stage").getAsString();
        }

        return "UNKNOWN";
    }

    private static String stackTraceToString(Throwable throwable) {
        StringWriter writer = new StringWriter();

        throwable.printStackTrace(
                new PrintWriter(writer)
        );

        return writer.toString();
    }

    private record QueueHandles(
            VkQueue graphicsQueue,
            VkQueue presentQueue,
            VkQueue transferQueue
    ) {
    }
}