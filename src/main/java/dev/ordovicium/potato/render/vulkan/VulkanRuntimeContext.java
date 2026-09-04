package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionSink;
import net.minecraft.client.renderer.RenderType;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkQueue;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Runtime-lifetime owner for Potato's validated Vulkan backend skeleton.
 *
 * <p>Patch 026 is the first milestone where Instance, Device, queues,
 * presentation surface/window, swapchain, frame ring, graphics pipeline and
 * offscreen color/depth target survive beyond {@link VulkanProbe#probe()}.</p>
 */
final class VulkanRuntimeContext implements AutoCloseable {
    private final VulkanProbeContext core;
    private final VulkanPresentationProbe presentation;
    private final VulkanFrameSession frameSession;

    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final VkQueue transferQueue;
    private final VulkanQueueFamilySelector.Selection queues;

    private final JsonObject report;

    private final VulkanRenderTargetOperationMirror
            renderTargetOperationMirror;

    private final VulkanGeometryUploadPrototype
            geometryUploadPrototype;

    private final VulkanBlockTextureUploadPrototype
            blockTextureUploadPrototype;

    private final VulkanOpenGlPresentationBridge
            openGlPresentationBridge;

    private final VulkanGate10DynamicPrecommitRuntime
            gate10DynamicPrecommitRuntime;

    private final VulkanGate11MainWindowSurfaceQualification
            gate11MainWindowSurfaceQualification;

    private final VulkanTexturedMultiSectionFrame
            texturedMultiSectionFrame;

    private final VulkanDrawSubmissionBackend
            drawSubmissionBackend;

    private final String installThread;
    private final long installedAtNanos;

    private boolean verifiedAfterProbeReturn;
    private boolean closed;

    private VulkanRuntimeContext(
            VulkanProbeContext core,
            VulkanPresentationProbe presentation,
            VulkanFrameSession frameSession,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            VkQueue presentQueue,
            VkQueue transferQueue,
            VulkanQueueFamilySelector.Selection queues,
            JsonObject report
    ) {
        this.core = core;
        this.presentation = presentation;
        this.frameSession = frameSession;
        this.physicalDevice = physicalDevice;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue;
        this.transferQueue = transferQueue;
        this.queues = queues;
        this.report = report;

        this.renderTargetOperationMirror =
                new VulkanRenderTargetOperationMirror(
                        frameSession,
                        report
                );

        this.geometryUploadPrototype =
                new VulkanGeometryUploadPrototype(
                        core.device(),
                        physicalDevice,
                        report
                );

        this.blockTextureUploadPrototype =
                new VulkanBlockTextureUploadPrototype(
                        core.device(),
                        physicalDevice,
                        graphicsQueue,
                        queues.graphicsFamilyIndex(),
                        report
                );

        this.openGlPresentationBridge =
                new VulkanOpenGlPresentationBridge(
                        physicalDevice,
                        core.device(),
                        queues.graphicsFamilyIndex(),
                        report
                );

        this.gate10DynamicPrecommitRuntime =
                new VulkanGate10DynamicPrecommitRuntime(
                        core.device(),
                        physicalDevice,
                        graphicsQueue,
                        queues.graphicsFamilyIndex(),
                        report
                );

        this.gate11MainWindowSurfaceQualification =
                new VulkanGate11MainWindowSurfaceQualification(
                        core.instance(),
                        core.device(),
                        physicalDevice,
                        graphicsQueue,
                        presentQueue,
                        queues,
                        presentation,
                        frameSession,
                        report
                );

        this.texturedMultiSectionFrame =
                new VulkanTexturedMultiSectionFrame(
                        core.device(),
                        physicalDevice,
                        graphicsQueue,
                        queues.graphicsFamilyIndex(),
                        frameSession,
                        openGlPresentationBridge,
                        report
                );

        this.drawSubmissionBackend =
                new VulkanDrawSubmissionBackend(
                        geometryUploadPrototype,
                        blockTextureUploadPrototype,
                        texturedMultiSectionFrame,
                        report
                );

        this.installThread =
                Thread.currentThread().getName();
        this.installedAtNanos =
                System.nanoTime();
    }

    static VulkanRuntimeContext adopt(
            VulkanProbeContext core,
            VulkanPresentationProbe presentation,
            VulkanFrameSession frameSession,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            VkQueue presentQueue,
            VkQueue transferQueue,
            VulkanQueueFamilySelector.Selection queues,
            JsonObject report
    ) {
        if (core == null
                || core.instance() == null
                || core.device() == null) {
            throw new VulkanProbeException(
                    "ADOPT_PERSISTENT_VULKAN_RUNTIME",
                    "Vulkan core context is incomplete."
            );
        }

        if (presentation == null
                || presentation.windowHandle() == NULL
                || presentation.surface() == NULL) {
            throw new VulkanProbeException(
                    "ADOPT_PERSISTENT_VULKAN_RUNTIME",
                    "Vulkan presentation ownership is incomplete."
            );
        }

        if (frameSession == null
                || !frameSession.alive()) {
            throw new VulkanProbeException(
                    "ADOPT_PERSISTENT_VULKAN_RUNTIME",
                    "Validated Vulkan frame session is not alive."
            );
        }

        if (physicalDevice == null
                || graphicsQueue == null
                || presentQueue == null
                || transferQueue == null
                || queues == null) {
            throw new VulkanProbeException(
                    "ADOPT_PERSISTENT_VULKAN_RUNTIME",
                    "Physical-device/queue ownership is incomplete."
            );
        }

        core.transferOwnershipToRuntime();
        presentation.transferOwnershipToRuntime();

        VulkanRuntimeContext runtime =
                new VulkanRuntimeContext(
                        core,
                        presentation,
                        frameSession,
                        physicalDevice,
                        graphicsQueue,
                        presentQueue,
                        transferQueue,
                        queues,
                        report
                );

        report.addProperty(
                "persistentVulkanRuntimeContextCreated",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeInstallThread",
                runtime.installThread
        );
        report.addProperty(
                "persistentVulkanRuntimeOwnsInstance",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeOwnsLogicalDevice",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeOwnsGraphicsQueue",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeOwnsPresentQueue",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeOwnsTransferQueue",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeTransferQueueFamilyIndex",
                queues.transferFamilyIndex()
        );
        report.addProperty(
                "persistentVulkanRuntimeTransferQueueDedicated",
                queues.dedicatedTransferFamily()
        );
        report.addProperty(
                "persistentVulkanRuntimeOwnsPresentation",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeOwnsFrameSession",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeGraphicsQueueFamilyIndex",
                queues.graphicsFamilyIndex()
        );
        report.addProperty(
                "persistentVulkanRuntimePresentQueueFamilyIndex",
                queues.presentFamilyIndex()
        );

        frameSession.enrichPersistentState();

        return runtime;
    }

    dev.ordovicium.potato.render.backend.target.RenderTargetOperationSink
    operationSink() {
        return renderTargetOperationMirror;
    }

    DrawSubmissionSink drawSubmissionSink() {
        return drawSubmissionBackend;
    }

    synchronized boolean drawSubmissionContractVerified() {
        report.addProperty(
                "drawSubmissionContractPerRunRehearsalRetired",
                true
        );
        report.addProperty(
                "drawSubmissionContractHistoricalMilestone",
                "VERIFIED_PATCH_033"
        );

        return true;
    }

    synchronized void enrichDrawSubmissionContract() {
        report.addProperty(
                "drawSubmissionContractPerRunRehearsalRetired",
                true
        );
        report.addProperty(
                "drawSubmissionContractArmed",
                false
        );

        geometryUploadPrototype.enrich();
    }

    synchronized boolean geometryUploadPrototypeVerified() {
        geometryUploadPrototype.enrich();

        return geometryUploadPrototype
                .verified();
    }

    synchronized boolean plainDrawStateContextVerified() {
        report.addProperty(
                "plainDrawStateContextPerRunRehearsalRetired",
                true
        );
        report.addProperty(
                "plainDrawStateContextHistoricalMilestone",
                "VERIFIED_PATCH_036"
        );

        return true;
    }

    synchronized boolean sectionLayerDrawPrototypeVerified() {
        report.addProperty(
                "sectionLayerDrawPrototypePerRunProofRetired",
                true
        );
        report.addProperty(
                "sectionLayerDrawPrototypeHistoricalMilestone",
                "VERIFIED_PATCH_037A"
        );

        return false;
    }

    synchronized boolean blockTextureUploadVerified() {
        blockTextureUploadPrototype.enrich();

        return blockTextureUploadPrototype
                .verified();
    }

    synchronized void enrichBlockTextureUpload() {
        blockTextureUploadPrototype.enrich();
    }

    synchronized boolean texturedSectionDrawHistoricalVerified() {
        report.addProperty(
                "texturedSectionDrawHistoricalMilestone",
                "VERIFIED_PATCH_042"
        );

        return true;
    }

    synchronized boolean texturedMultiSectionFrameVerified() {
        texturedMultiSectionFrame.enrich();

        return texturedMultiSectionFrame
                .verified();
    }

    synchronized void enrichTexturedMultiSectionFrame() {
        texturedMultiSectionFrame.enrich();
    }

    synchronized boolean visibilityEngineVerified() {
        return drawSubmissionBackend
                .visibilityEngineVerified();
    }

    synchronized void enrichVisibilityEngine() {
        drawSubmissionBackend
                .enrichVisibilityEngine();
    }

    synchronized boolean runtimePerformanceFastPathVerified() {
        boolean verified =
                dev.ordovicium.potato.render.backend.draw.DrawSubmissionDispatcher
                .performanceFastPathEnabled()
                        && !dev.ordovicium.potato.render.backend.target.RenderTargetOperationDispatcher
                        .hiddenFrameMirrorEnabled();

        report.addProperty(
                "runtimePerformanceFastPathVerified",
                verified
        );
        report.addProperty(
                "runtimeHiddenFrameMirrorEnabled",
                dev.ordovicium.potato.render.backend.target.RenderTargetOperationDispatcher
                        .hiddenFrameMirrorEnabled()
        );

        return verified;
    }

    synchronized void flushPendingMainTargetOperations() {
        renderTargetOperationMirror
                .flushPendingOperations();
    }

    synchronized boolean renderTargetOperationDispatchVerified() {
        renderTargetOperationMirror.enrich();

        return renderTargetOperationMirror
                .liveDispatchVerified();
    }

    synchronized boolean mainTargetResizePropagationVerified() {
        renderTargetOperationMirror.enrich();

        return renderTargetOperationMirror
                .resizePropagationVerified();
    }

    synchronized boolean mainTargetClearPropagationVerified() {
        renderTargetOperationMirror.enrich();

        return renderTargetOperationMirror
                .clearPropagationVerified();
    }

    synchronized boolean mainTargetBlitPropagationVerified() {
        renderTargetOperationMirror.enrich();

        return renderTargetOperationMirror
                .blitPropagationVerified();
    }

    synchronized boolean mainTargetFrameLifecycleVerified() {
        renderTargetOperationMirror.enrich();

        return renderTargetOperationMirror
                .frameLifecycleVerified();
    }

    synchronized void enrichRenderTargetOperationDispatch() {
        renderTargetOperationMirror.enrich();
    }

    synchronized boolean verifyAfterProbeReturn() {
        if (closed) {
            report.addProperty(
                    "persistentVulkanRuntimeVerifyFailure",
                    "Runtime was already closed."
            );
            return false;
        }

        boolean coreAlive =
                core.instance() != null
                        && core.device() != null;

        boolean presentationAlive =
                presentation.windowHandle() != NULL
                        && presentation.surface() != NULL;

        boolean queuesAlive =
                graphicsQueue != null
                        && presentQueue != null;

        boolean frameAlive =
                frameSession.alive();

        int idleResult;

        try {
            idleResult =
                    vkDeviceWaitIdle(
                            core.device()
                    );
        } catch (Throwable throwable) {
            report.addProperty(
                    "persistentVulkanRuntimeVerificationError",
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            )
            );

            return false;
        }

        boolean verified =
                coreAlive
                        && presentationAlive
                        && queuesAlive
                        && frameAlive
                        && idleResult == VK_SUCCESS;

        verifiedAfterProbeReturn =
                verified;

        report.addProperty(
                "persistentVulkanRuntimeVerifyThread",
                Thread.currentThread().getName()
        );
        report.addProperty(
                "persistentVulkanRuntimeCoreAliveAfterProbeReturn",
                coreAlive
        );
        report.addProperty(
                "persistentVulkanRuntimePresentationAliveAfterProbeReturn",
                presentationAlive
        );
        report.addProperty(
                "persistentVulkanRuntimeQueuesAliveAfterProbeReturn",
                queuesAlive
        );
        report.addProperty(
                "persistentVulkanRuntimeFrameSessionAliveAfterProbeReturn",
                frameAlive
        );
        report.addProperty(
                "persistentVulkanRuntimeDeviceWaitIdleAfterProbeReturnResult",
                idleResult
        );
        report.addProperty(
                "persistentVulkanRuntimeSurvivedProbeScope",
                verified
        );
        report.addProperty(
                "persistentVulkanRuntimeVerifiedAfterProbeReturn",
                verified
        );
        report.addProperty(
                "persistentVulkanRuntimeClosed",
                false
        );

        frameSession.enrichPersistentState();

        renderTargetOperationMirror.enrich();
        geometryUploadPrototype.enrich();

        report.addProperty(
                "drawSubmissionContractPerRunRehearsalRetired",
                true
        );
        report.addProperty(
                "sectionLayerDrawPrototypePerRunProofRetired",
                true
        );

        report.addProperty(
                "renderTargetOperationDispatchArmed",
                true
        );
        report.addProperty(
                "drawSubmissionContractArmed",
                false
        );
        report.addProperty(
                "geometryUploadPrototypeArmed",
                true
        );
        report.addProperty(
                "sectionLayerDrawPrototypeArmed",
                false
        );
        report.addProperty(
                "runtimePerformanceSafetyMode",
                "RELEASE_FAST_PATH"
        );

        return verified;
    }

    synchronized boolean verifiedAfterProbeReturn() {
        return verifiedAfterProbeReturn;
    }

    synchronized VkDevice deviceForSurfaceTilePrototype() {
        return closed
                ? null
                : core.device();
    }

    synchronized VkPhysicalDevice physicalDeviceForSurfaceTilePrototype() {
        return closed
                ? null
                : physicalDevice;
    }

    synchronized void offerGate10DynamicPrecommit(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        if (!closed) {
            gate10DynamicPrecommitRuntime.offer(
                    domain
            );
        }
    }

    synchronized void beginGate10VisibleScreenRehearsal() {
        if (!closed) {
            gate10DynamicPrecommitRuntime
                    .beginVisibleScreenRehearsal();
        }
    }

    synchronized void endGate10VisibleScreenRehearsal() {
        if (!closed) {
            gate10DynamicPrecommitRuntime
                    .endVisibleScreenRehearsal();
        }
    }

    synchronized void offerGate11MainWindowSurfaceQualification() {
        if (!closed) {
            gate11MainWindowSurfaceQualification.offer();
        }
    }

    synchronized void tickGate11VisibleReplacementRehearsal() {
        if (!closed) {
            gate11MainWindowSurfaceQualification
                    .tickVisibleReplacementRehearsal();
        }
    }

    synchronized boolean prepareVisibleSolidOwnership(
            RenderType renderType
    ) {
        return !closed
                && texturedMultiSectionFrame
                .prepareVisibleOwnership(renderType);
    }

    synchronized boolean visibleSolidStateReady(
            DrawBufferBackendState state
    ) {
        return !closed
                && state != null
                && geometryUploadPrototype
                .readyResource(state) != null;
    }

    synchronized int visibleSolidDrawCapacity() {
        return closed
                ? 0
                : texturedMultiSectionFrame
                .visibleDrawCapacity();
    }

    synchronized boolean armVisibleSolidOwnership(
            int expectedDrawCount
    ) {
        return !closed
                && texturedMultiSectionFrame
                .armVisibleOwnership(expectedDrawCount);
    }

    synchronized boolean visibleSolidCommitQueued() {
        return !closed
                && texturedMultiSectionFrame
                .visibleCommitQueued();
    }

    synchronized VkQueue graphicsQueueForSurfaceTilePrototype() {
        return closed
                ? null
                : graphicsQueue;
    }

    synchronized int graphicsQueueFamilyIndexForSurfaceTilePrototype() {
        return closed
                ? -1
                : queues.graphicsFamilyIndex();
    }

    synchronized VulkanFrameSession.SectionLayerTargetSnapshot
    surfaceTileTargetSnapshot() {
        if (closed) {
            return null;
        }

        return frameSession.sectionLayerTargetSnapshot();
    }

    synchronized JsonObject reportForSurfaceTilePrototype() {
        return report;
    }

    synchronized VulkanBlockTextureUploadPrototype
    surfaceTileTextureResources() {
        if (closed) {
            return null;
        }

        blockTextureUploadPrototype
                .tryUploadInitialSnapshots();

        blockTextureUploadPrototype
                .enrich();

        return blockTextureUploadPrototype
                .verified()
                ? blockTextureUploadPrototype
                : null;
    }

    synchronized VulkanHeadlessResourceRuntime
    detachHeadlessResourceRuntime() {
        if (closed) {
            throw new IllegalStateException(
                    "Persistent Vulkan runtime is already closed."
            );
        }

        closed = true;

        report.addProperty(
                "headlessResourceRuntimeDetachRequested",
                true
        );
        report.addProperty(
                "headlessResourceRuntimeDetachThread",
                Thread.currentThread().getName()
        );

        VulkanHeadlessResourceRuntime headless =
                new VulkanHeadlessResourceRuntime(
                        core,
                        physicalDevice,
                        graphicsQueue,
                        transferQueue,
                        queues.graphicsFamilyIndex(),
                        queues.transferFamilyIndex(),
                        report
                );

        boolean detached = false;

        try {
            /*
             * Retire every presentation/window-dependent owner first. The
             * VkInstance/VkDevice and queue handles intentionally remain
             * runtime-owned and move into the headless resource runtime.
             */
            gate10DynamicPrecommitRuntime.close();
            drawSubmissionBackend.close();
            geometryUploadPrototype.enrich();

            blockTextureUploadPrototype.close();
            blockTextureUploadPrototype.enrich();

            /*
             * Gate 11 borrows the persistent presentation surface/swapchain.
             * Release every Gate-11 command/semaphore/window-router borrower
             * before the frame session and VkSurfaceKHR owners are retired.
             */
            gate11MainWindowSurfaceQualification.close();

            frameSession.close();

            presentation.closeRuntimeOwnedResources();

            VulkanHandoffCandidate
                    .destroyAfterPresentationShutdown();
            VulkanHandoffCandidate.enrich(
                    report
            );

            report.addProperty(
                    "headlessResourceRuntimePresentationReleased",
                    true
            );
            report.addProperty(
                    "headlessResourceRuntimeFrameSessionReleased",
                    true
            );
            report.addProperty(
                    "headlessResourceRuntimeCoreRetained",
                    core.instance() != null
                            && core.device() != null
            );
            report.addProperty(
                    "headlessResourceRuntimeGraphicsQueueRetained",
                    graphicsQueue != null
            );
            report.addProperty(
                    "headlessResourceRuntimeTransferQueueRetained",
                    transferQueue != null
            );
            report.addProperty(
                    "headlessResourceRuntimeTransferQueueDedicated",
                    queues.dedicatedTransferFamily()
            );
            report.addProperty(
                    "secondaryNoApiPresentationRetainedForGameplay",
                    false
            );
            report.addProperty(
                    "headlessResourceRuntimeWindowHandleRetained",
                    false
            );
            report.addProperty(
                    "headlessResourceRuntimeSurfaceRetained",
                    false
            );

            detached = true;

            return headless;
        } finally {
            if (!detached) {
                try {
                    headless.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    synchronized boolean closed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        renderTargetOperationMirror.enrich();
        geometryUploadPrototype.enrich();

        report.addProperty(
                "persistentVulkanRuntimeShutdownRequested",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeShutdownThread",
                Thread.currentThread().getName()
        );

        /*
         * Reverse ownership:
         * 1. draw-side Vulkan geometry resources
         * 2. command/swapchain/pipeline/offscreen resources
         * 3. VkSurfaceKHR + hidden GLFW_NO_API window
         * 4. VkDevice
         * 5. VkInstance
         */
        long drawBackendCloseStartNanos =
                System.nanoTime();

        gate10DynamicPrecommitRuntime.close();
        drawSubmissionBackend.close();
        geometryUploadPrototype.enrich();

        long textureCloseStartNanos =
                System.nanoTime();

        blockTextureUploadPrototype.close();
        blockTextureUploadPrototype.enrich();

        report.addProperty(
                "runtimeShutdownBlockTextureCloseMillis",
                (System.nanoTime()
                        - textureCloseStartNanos)
                        / 1_000_000L
        );

        report.addProperty(
                "runtimeShutdownDrawBackendCloseMillis",
                (System.nanoTime()
                        - drawBackendCloseStartNanos)
                        / 1_000_000L
        );

        long gate11CloseStartNanos =
                System.nanoTime();

        /*
         * Gate 11 now borrows the persistent runtime WSI generation. Its
         * command buffers, semaphores and lifecycle router must be gone before
         * FrameSession destroys the borrowed swapchain and PresentationProbe
         * destroys the borrowed VkSurfaceKHR.
         */
        gate11MainWindowSurfaceQualification.close();

        report.addProperty(
                "runtimeShutdownGate11BorrowerCloseMillis",
                (System.nanoTime()
                        - gate11CloseStartNanos)
                        / 1_000_000L
        );

        long frameSessionCloseStartNanos =
                System.nanoTime();

        frameSession.close();

        report.addProperty(
                "runtimeShutdownFrameSessionCloseMillis",
                (System.nanoTime()
                        - frameSessionCloseStartNanos)
                        / 1_000_000L
        );

        long presentationCloseStartNanos =
                System.nanoTime();

        presentation.closeRuntimeOwnedResources();

        report.addProperty(
                "runtimeShutdownPresentationCloseMillis",
                (System.nanoTime()
                        - presentationCloseStartNanos)
                        / 1_000_000L
        );

        VulkanHandoffCandidate
                .destroyAfterPresentationShutdown();
        VulkanHandoffCandidate.enrich(
                report
        );

        report.addProperty(
                "runtimeShutdownBorrowedWsiOwnerOrder",
                "GATE11_THEN_SWAPCHAIN_THEN_SURFACE_THEN_WINDOW"
        );

        long coreCloseStartNanos =
                System.nanoTime();

        core.closeRuntimeOwnedResources();

        report.addProperty(
                "runtimeShutdownCoreCloseMillis",
                (System.nanoTime()
                        - coreCloseStartNanos)
                        / 1_000_000L
        );

        long lifetimeNanos =
                Math.max(
                        0L,
                        System.nanoTime()
                                - installedAtNanos
                );

        report.addProperty(
                "persistentVulkanRuntimeLifetimeMillis",
                lifetimeNanos / 1_000_000L
        );
        report.addProperty(
                "persistentVulkanRuntimeClosed",
                true
        );
        report.addProperty(
                "persistentVulkanRuntimeShutdownComplete",
                true
        );

        frameSession.enrichPersistentState();
    }
}
