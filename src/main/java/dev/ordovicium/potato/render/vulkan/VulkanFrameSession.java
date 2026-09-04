package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Persistent ownership container for Potato's validated Vulkan frame resources.
 *
 * <p>Patch 028 adds the first live GPU mutation driven by Minecraft's actual
 * MainTarget lifecycle: a completed Minecraft resize can replace the persistent
 * Vulkan color/depth target while OpenGL remains the game's active owner.</p>
 */
final class VulkanFrameSession implements AutoCloseable {
    private final VkPhysicalDevice physicalDevice;
    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final int graphicsQueueFamilyIndex;
    private final VulkanQueueFamilySelector.Selection queues;
    private VulkanSwapchainSupport.Configuration swapchainConfiguration;
    private final JsonObject report;

    private VulkanFrameRing frameRing;
    private VulkanSwapchainResources swapchain;
    private VulkanTrianglePipeline pipeline;
    private VulkanOffscreenTargetPrototype offscreenTarget;
    private VulkanLiveFramePresenter liveFramePresenter;
    private final List<VulkanSwapchainResources> retiredSwapchains =
            new ArrayList<>();

    private int gameplaySwapchainRecreationCount;
    private int gameplaySwapchainBusyDeferralCount;
    private int gameplaySwapchainFailureCount;

    private int offscreenTargetGeneration = 1;
    private int offscreenResizeApplyCount;
    private int offscreenResizeNoOpCount;
    private int offscreenResizePipelineRecreationCount;
    private int offscreenResizeDeviceIdleCount;

    private boolean closed;

    VulkanFrameSession(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue graphicsQueue,
            VkQueue presentQueue,
            int graphicsQueueFamilyIndex,
            VulkanQueueFamilySelector.Selection queues,
            VulkanSwapchainSupport.Configuration swapchainConfiguration,
            VulkanFrameRing frameRing,
            VulkanSwapchainResources swapchain,
            VulkanTrianglePipeline pipeline,
            VulkanOffscreenTargetPrototype offscreenTarget,
            JsonObject report
    ) {
        this.physicalDevice = physicalDevice;
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue;
        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;
        this.queues = queues;
        this.swapchainConfiguration =
                swapchainConfiguration;

        this.frameRing = frameRing;
        this.swapchain = swapchain;
        this.pipeline = pipeline;
        this.offscreenTarget = offscreenTarget;
        this.report = report;

        this.liveFramePresenter =
                new VulkanLiveFramePresenter(
                        device,
                        graphicsQueue,
                        presentQueue,
                        frameRing,
                        swapchain,
                        swapchainConfiguration,
                        report
                );
    }

    synchronized boolean alive() {
        return !closed
                && frameRing != null
                && frameRing.size() > 0
                && swapchain != null
                && swapchain.swapchain() != NULL
                && pipeline != null
                && pipeline.pipeline() != NULL
                && offscreenTarget != null
                && offscreenTarget.colorImage() != NULL
                && offscreenTarget.colorImageView() != NULL;
    }

    /**
     * Rebuilds only the persistent gameplay swapchain against the already
     * retained NO_API surface after the candidate has been resized from the
     * NeoForge bootstrap extent to the live Minecraft framebuffer.
     *
     * <p>No gameplay wait is permitted here. Every frame-ring fence is polled
     * with vkGetFenceStatus; if any submission is still busy, Gate 11 simply
     * defers/fails open for this offer. The previous swapchain generation is
     * retained until shutdown so the presentation engine can retire it
     * naturally without a queue/device idle stall.</p>
     */
    synchronized boolean prepareGameplayPresentationSwapchain(
            long surface,
            long windowHandle
    ) {
        if (closed
                || surface == NULL
                || windowHandle == NULL
                || frameRing == null
                || swapchain == null
                || offscreenTarget == null
                || queues == null) {
            gameplaySwapchainFailureCount++;
            enrichGameplaySwapchainState(
                    "RUNTIME_PRESENTATION_RESOURCES_INCOMPLETE"
            );
            return false;
        }

        for (int index = 0;
             index < frameRing.size();
             index++) {
            int fenceStatus =
                    vkGetFenceStatus(
                            device,
                            frameRing.frame(index)
                                    .inFlightFence()
                    );

            if (fenceStatus == VK_NOT_READY) {
                gameplaySwapchainBusyDeferralCount++;
                enrichGameplaySwapchainState(
                        "FRAME_RING_BUSY_NONBLOCKING_DEFERRAL"
                );
                return false;
            }

            if (fenceStatus != VK_SUCCESS) {
                gameplaySwapchainFailureCount++;
                enrichGameplaySwapchainState(
                        "FRAME_FENCE_STATUS_"
                                + fenceStatus
                );
                return false;
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanSwapchainSupport.Configuration newConfiguration =
                    VulkanSwapchainSupport.query(
                            physicalDevice,
                            surface,
                            windowHandle,
                            stack,
                            report
                    );

            boolean unchanged =
                    newConfiguration.width()
                                    == swapchainConfiguration.width()
                            && newConfiguration.height()
                                    == swapchainConfiguration.height()
                            && newConfiguration.format()
                                    == swapchainConfiguration.format()
                            && newConfiguration.colorSpace()
                                    == swapchainConfiguration.colorSpace()
                            && newConfiguration.presentMode()
                                    == swapchainConfiguration.presentMode()
                            && newConfiguration.imageUsage()
                                    == swapchainConfiguration.imageUsage();

            if (unchanged) {
                enrichGameplaySwapchainState(
                        "PERSISTENT_SWAPCHAIN_ALREADY_MATCHES_LIVE_WINDOW"
                );
                return true;
            }

            VulkanImageBlitSupport.verify(
                    physicalDevice,
                    offscreenTarget.colorFormat(),
                    newConfiguration.format(),
                    report
            );

            VulkanSwapchainResources replacement =
                    VulkanSwapchainResources.create(
                            device,
                            queues,
                            surface,
                            newConfiguration,
                            swapchain.swapchain(),
                            stack,
                            report
                    );

            VulkanSwapchainResources retired =
                    swapchain;

            swapchain =
                    replacement;
            swapchainConfiguration =
                    newConfiguration;

            retiredSwapchains.add(
                    retired
            );

            liveFramePresenter =
                    new VulkanLiveFramePresenter(
                            device,
                            graphicsQueue,
                            presentQueue,
                            frameRing,
                            swapchain,
                            swapchainConfiguration,
                            report
                    );

            gameplaySwapchainRecreationCount++;

            report.addProperty(
                    "gameplaySwapchainOldGenerationRetainedUntilShutdown",
                    true
            );
            report.addProperty(
                    "gameplaySwapchainUsesOldSwapchainHandoff",
                    true
            );

            enrichGameplaySwapchainState(
                    "PERSISTENT_SWAPCHAIN_RECREATED_NONBLOCKING"
            );

            return true;
        } catch (Throwable throwable) {
            gameplaySwapchainFailureCount++;

            enrichGameplaySwapchainState(
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            )
            );

            return false;
        }
    }

    private void enrichGameplaySwapchainState(
            String state
    ) {
        report.addProperty(
                "gameplaySwapchainPreparationState",
                state
        );
        report.addProperty(
                "gameplaySwapchainRecreationCount",
                gameplaySwapchainRecreationCount
        );
        report.addProperty(
                "gameplaySwapchainBusyDeferralCount",
                gameplaySwapchainBusyDeferralCount
        );
        report.addProperty(
                "gameplaySwapchainFailureCount",
                gameplaySwapchainFailureCount
        );
        report.addProperty(
                "gameplaySwapchainRetiredGenerationCount",
                retiredSwapchains.size()
        );
        report.addProperty(
                "gameplaySwapchainNoGameplayGpuWait",
                true
        );
    }

    synchronized long presentationSwapchainHandle() {
        return closed || swapchain == null
                ? NULL
                : swapchain.swapchain();
    }

    synchronized long[] presentationSwapchainImagesSnapshot() {
        if (closed || swapchain == null) {
            return new long[0];
        }

        long[] images =
                new long[swapchain.imageCount()];

        for (int index = 0;
             index < images.length;
             index++) {
            images[index] =
                    swapchain.image(index);
        }

        return images;
    }

    synchronized VulkanSwapchainSupport.Configuration
    presentationConfiguration() {
        return swapchainConfiguration;
    }

    synchronized int presentationImageOldLayout(
            int imageIndex
    ) {
        if (closed
                || swapchain == null
                || imageIndex < 0
                || imageIndex >= swapchain.imageCount()) {
            return VK_IMAGE_LAYOUT_UNDEFINED;
        }

        return swapchain.oldLayoutFor(
                imageIndex
        );
    }

    synchronized void markPresentationImagePresented(
            int imageIndex
    ) {
        if (closed
                || swapchain == null
                || imageIndex < 0
                || imageIndex >= swapchain.imageCount()) {
            return;
        }

        swapchain.markPresented(
                imageIndex
        );
    }

    synchronized int offscreenWidth() {
        return offscreenTarget == null
                ? 0
                : offscreenTarget.width();
    }

    synchronized int offscreenHeight() {
        return offscreenTarget == null
                ? 0
                : offscreenTarget.height();
    }

    synchronized boolean offscreenUsesDepth() {
        return offscreenTarget != null
                && offscreenTarget.useDepth();
    }

    synchronized int offscreenTargetGeneration() {
        return offscreenTargetGeneration;
    }

    synchronized SectionLayerTargetSnapshot
    sectionLayerTargetSnapshot() {
        if (closed
                || offscreenTarget == null) {
            throw new IllegalStateException(
                    "Persistent Vulkan offscreen target is unavailable."
            );
        }

        return new SectionLayerTargetSnapshot(
                offscreenTarget,
                offscreenTargetGeneration
        );
    }

    /**
     * Replaces only Potato's offscreen scene attachments.
     *
     * <p>Strong guarantee: replacement resources are created and GPU-initialized
     * before the old target is retired. If creation fails, the old target and
     * pipeline remain installed.</p>
     */
    synchronized ResizeOutcome resizeOffscreenTarget(
            int width,
            int height,
            boolean useDepth
    ) {
        if (closed) {
            throw new IllegalStateException(
                    "Persistent Vulkan frame session is already closed."
            );
        }

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "Invalid Vulkan MainTarget resize "
                            + width
                            + "x"
                            + height
            );
        }

        if (offscreenTarget == null
                || pipeline == null) {
            throw new IllegalStateException(
                    "Persistent Vulkan render resources are incomplete."
            );
        }

        int oldWidth =
                offscreenTarget.width();
        int oldHeight =
                offscreenTarget.height();

        if (oldWidth == width
                && oldHeight == height
                && offscreenTarget.useDepth() == useDepth) {

            offscreenResizeNoOpCount++;

            report.addProperty(
                    "vulkanMainTargetResizeLastWasNoOp",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetResizeCurrentWidth",
                    oldWidth
            );
            report.addProperty(
                    "vulkanMainTargetResizeCurrentHeight",
                    oldHeight
            );

            enrichResizeState();

            return new ResizeOutcome(
                    false,
                    false,
                    oldWidth,
                    oldHeight,
                    oldWidth,
                    oldHeight
            );
        }

        VulkanOffscreenTargetPrototype replacementTarget = null;
        VulkanTrianglePipeline replacementPipeline = null;

        boolean formatChanged = false;
        boolean pipelineRecreated = false;

        try {
            report.addProperty(
                    "vulkanMainTargetResizeLastRequestedWidth",
                    width
            );
            report.addProperty(
                    "vulkanMainTargetResizeLastRequestedHeight",
                    height
            );
            report.addProperty(
                    "vulkanMainTargetResizeLastOldWidth",
                    oldWidth
            );
            report.addProperty(
                    "vulkanMainTargetResizeLastOldHeight",
                    oldHeight
            );

            replacementTarget =
                    VulkanOffscreenTargetPrototype.create(
                            physicalDevice,
                            device,
                            graphicsQueue,
                            graphicsQueueFamilyIndex,
                            width,
                            height,
                            useDepth,
                            report
                    );

            VulkanImageBlitSupport.verify(
                    physicalDevice,
                    replacementTarget.colorFormat(),
                    swapchainConfiguration.format(),
                    report
            );

            formatChanged =
                    replacementTarget.colorFormat()
                            != offscreenTarget.colorFormat()
                            || replacementTarget.depthFormatOrUndefined()
                            != offscreenTarget.depthFormatOrUndefined()
                            || replacementTarget.useDepth()
                            != offscreenTarget.useDepth();

            if (formatChanged) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    replacementPipeline =
                            VulkanTrianglePipeline.create(
                                    device,
                                    replacementTarget.colorFormat(),
                                    replacementTarget.depthFormatOrUndefined(),
                                    replacementTarget.useDepth(),
                                    stack,
                                    report
                            );
                }

                pipelineRecreated = true;
            }

            /*
             * Resize is rare and coalesced by the operation mirror. A full
             * device-idle barrier is deliberately conservative here: all
             * previously submitted commands that could reference the old
             * image/view must be complete before those objects are destroyed.
             */
            int idleResult =
                    vkDeviceWaitIdle(device);

            report.addProperty(
                    "vulkanMainTargetResizeLastDeviceWaitIdleResult",
                    idleResult
            );

            if (idleResult != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "VULKAN_MAIN_TARGET_RESIZE_WAIT_IDLE",
                        "vkDeviceWaitIdle failed with VkResult "
                                + idleResult
                );
            }

            offscreenResizeDeviceIdleCount++;

            VulkanOffscreenTargetPrototype oldTarget =
                    offscreenTarget;

            VulkanTrianglePipeline oldPipeline =
                    pipeline;

            offscreenTarget =
                    replacementTarget;

            replacementTarget = null;

            if (pipelineRecreated) {
                pipeline =
                        replacementPipeline;

                replacementPipeline = null;
            }

            offscreenTargetGeneration++;
            offscreenResizeApplyCount++;

            /*
             * No future submission may use the old target. Patch 028 does not
             * resume the validation frame loop, and the device is idle here.
             */
            oldTarget.close();

            if (pipelineRecreated) {
                oldPipeline.close();
                offscreenResizePipelineRecreationCount++;
            }

            markCurrentResourcesAlive();

            report.addProperty(
                    "vulkanMainTargetResizeLastWasNoOp",
                    false
            );
            report.addProperty(
                    "vulkanMainTargetResizeLastApplied",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetResizeLastFormatChanged",
                    formatChanged
            );
            report.addProperty(
                    "vulkanMainTargetResizeLastPipelineRecreated",
                    pipelineRecreated
            );
            report.addProperty(
                    "vulkanMainTargetResizeLastOldTargetRetired",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetResizeCurrentWidth",
                    offscreenTarget.width()
            );
            report.addProperty(
                    "vulkanMainTargetResizeCurrentHeight",
                    offscreenTarget.height()
            );
            report.addProperty(
                    "vulkanMainTargetResizeCurrentColorFormat",
                    offscreenTarget.colorFormat()
            );
            report.addProperty(
                    "vulkanMainTargetResizeCurrentDepthFormat",
                    offscreenTarget.depthFormatOrUndefined()
            );
            report.addProperty(
                    "vulkanMainTargetResizeSwapchainRecreated",
                    false
            );
            report.addProperty(
                    "vulkanMainTargetResizePresentationExtentIndependent",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetResizeFrameCommandsRequireRerecordBeforeReuse",
                    true
            );

            enrichResizeState();

            return new ResizeOutcome(
                    true,
                    pipelineRecreated,
                    oldWidth,
                    oldHeight,
                    width,
                    height
            );
        } catch (Throwable throwable) {
            if (replacementPipeline != null) {
                try {
                    replacementPipeline.close();
                } catch (Throwable ignored) {
                    // Preserve the original resize failure.
                }
            }

            if (replacementTarget != null) {
                try {
                    replacementTarget.close();
                } catch (Throwable ignored) {
                    // Preserve the original resize failure.
                }
            }

            /*
             * The previously installed target/pipeline are still the active
             * resources when replacement failed.
             */
            markCurrentResourcesAlive();

            report.addProperty(
                    "vulkanMainTargetResizeStrongGuaranteePreserved",
                    true
            );

            if (throwable instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            throw new VulkanProbeException(
                    "VULKAN_MAIN_TARGET_RESIZE",
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            )
            );
        }
    }

    synchronized FrameOutcome presentMainTargetFrame(
            long semanticFrameSequence,
            int semanticDestinationWidth,
            int semanticDestinationHeight,
            boolean disableBlend,
            boolean clearRequested,
            float clearRed,
            float clearGreen,
            float clearBlue,
            float clearAlpha
    ) {
        if (closed) {
            throw new IllegalStateException(
                    "Persistent Vulkan frame session is already closed."
            );
        }

        if (offscreenTarget == null
                || liveFramePresenter == null) {
            throw new IllegalStateException(
                    "Persistent Vulkan frame resources are unavailable."
            );
        }

        VulkanLiveFramePresenter.FrameOutcome outcome =
                liveFramePresenter.present(
                        offscreenTarget,
                        offscreenTargetGeneration,
                        semanticFrameSequence,
                        semanticDestinationWidth,
                        semanticDestinationHeight,
                        disableBlend,
                        clearRequested,
                        clearRed,
                        clearGreen,
                        clearBlue,
                        clearAlpha
                );

        if (clearRequested) {
            report.addProperty(
                    "vulkanMainTargetClearGpuSubmitted",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetClearGpuCompleted",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetClearColorImageCleared",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetClearDepthImageCleared",
                    offscreenTarget.useDepth()
            );
            report.addProperty(
                    "vulkanMainTargetClearColorFinalLayout",
                    "COLOR_ATTACHMENT_OPTIMAL"
            );

            if (offscreenTarget.useDepth()) {
                report.addProperty(
                        "vulkanMainTargetClearDepthFinalLayout",
                        "DEPTH_STENCIL_ATTACHMENT_OPTIMAL"
                );
            }

            report.addProperty(
                    "vulkanMainTargetClearTargetGeneration",
                    offscreenTargetGeneration
            );
            report.addProperty(
                    "vulkanMainTargetClearTargetWidth",
                    offscreenTarget.width()
            );
            report.addProperty(
                    "vulkanMainTargetClearTargetHeight",
                    offscreenTarget.height()
            );
            report.addProperty(
                    "vulkanMainTargetClearStandaloneSubmissionUsed",
                    false
            );
            report.addProperty(
                    "vulkanMainTargetClearIntegratedIntoFrameSubmission",
                    true
            );
        }

        return new FrameOutcome(
                outcome.semanticFrameSequence(),
                outcome.targetGeneration(),
                outcome.sourceWidth(),
                outcome.sourceHeight(),
                outcome.semanticDestinationWidth(),
                outcome.semanticDestinationHeight(),
                outcome.swapchainWidth(),
                outcome.swapchainHeight(),
                outcome.imageIndex(),
                outcome.acquireResult(),
                outcome.submitResult(),
                outcome.presentResult(),
                outcome.clearIncluded()
        );
    }

    synchronized void enrichPersistentState() {
        report.addProperty(
                "persistentFrameSessionAlive",
                alive()
        );
        report.addProperty(
                "persistentFrameSessionOwnsFrameRing",
                !closed
                        && frameRing != null
                        && frameRing.size() > 0
        );
        report.addProperty(
                "persistentFrameSessionOwnsSwapchain",
                !closed
                        && swapchain != null
                        && swapchain.swapchain() != NULL
        );
        report.addProperty(
                "persistentFrameSessionOwnsGraphicsPipeline",
                !closed
                        && pipeline != null
                        && pipeline.pipeline() != NULL
        );
        report.addProperty(
                "persistentFrameSessionOwnsOffscreenTarget",
                !closed
                        && offscreenTarget != null
                        && offscreenTarget.colorImage() != NULL
        );
        report.addProperty(
                "persistentFrameSessionClosed",
                closed
        );

        report.addProperty(
                "persistentFrameSessionOffscreenTargetGeneration",
                offscreenTargetGeneration
        );
        report.addProperty(
                "persistentFrameSessionOffscreenWidth",
                offscreenWidth()
        );
        report.addProperty(
                "persistentFrameSessionOffscreenHeight",
                offscreenHeight()
        );

        enrichResizeState();

        report.addProperty(
                "persistentFrameSessionLiveFramePresenterAvailable",
                !closed
                        && liveFramePresenter != null
        );

        if (liveFramePresenter != null) {
            report.addProperty(
                    "vulkanMainTargetFrameLivePresentCount",
                    liveFramePresenter.liveFramePresentCount()
            );
            report.addProperty(
                    "vulkanMainTargetBlitLivePresentCount",
                    liveFramePresenter.liveFramePresentCount()
            );
        }
    }

    private void enrichResizeState() {
        report.addProperty(
                "vulkanMainTargetResizeGpuApplyCount",
                offscreenResizeApplyCount
        );
        report.addProperty(
                "vulkanMainTargetResizeGpuNoOpCount",
                offscreenResizeNoOpCount
        );
        report.addProperty(
                "vulkanMainTargetResizePipelineRecreationCount",
                offscreenResizePipelineRecreationCount
        );
        report.addProperty(
                "vulkanMainTargetResizeDeviceIdleCount",
                offscreenResizeDeviceIdleCount
        );
        report.addProperty(
                "vulkanMainTargetResizeTargetGeneration",
                offscreenTargetGeneration
        );
    }

    private void markCurrentResourcesAlive() {
        report.addProperty(
                "vulkanOffscreenTargetDestroyed",
                false
        );
        report.addProperty(
                "vulkanOffscreenColorImageViewDestroyed",
                false
        );
        report.addProperty(
                "vulkanOffscreenColorImageDestroyed",
                false
        );
        report.addProperty(
                "vulkanOffscreenColorMemoryFreed",
                false
        );

        if (offscreenTarget != null
                && offscreenTarget.useDepth()) {
            report.addProperty(
                    "vulkanOffscreenDepthImageViewDestroyed",
                    false
            );
            report.addProperty(
                    "vulkanOffscreenDepthImageDestroyed",
                    false
            );
            report.addProperty(
                    "vulkanOffscreenDepthMemoryFreed",
                    false
            );
        }

        report.addProperty(
                "graphicsPipelineDestroyed",
                false
        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        closeResources(
                device,
                frameRing,
                swapchain,
                pipeline,
                offscreenTarget,
                report,
                "RUNTIME_SHUTDOWN"
        );

        for (VulkanSwapchainResources retired
                : retiredSwapchains) {
            if (retired != null) {
                retired.close();
            }
        }

        report.addProperty(
                "gameplaySwapchainRetiredGenerationsDestroyedAtShutdown",
                retiredSwapchains.size()
        );
        retiredSwapchains.clear();

        liveFramePresenter = null;
        frameRing = null;
        swapchain = null;
        pipeline = null;
        offscreenTarget = null;

        report.addProperty(
                "persistentFrameSessionClosed",
                true
        );
    }

    static void closeFailedCreation(
            VkDevice device,
            VulkanFrameRing frameRing,
            VulkanSwapchainResources swapchain,
            VulkanTrianglePipeline pipeline,
            VulkanOffscreenTargetPrototype offscreenTarget,
            JsonObject report
    ) {
        closeResources(
                device,
                frameRing,
                swapchain,
                pipeline,
                offscreenTarget,
                report,
                "FAILED_CREATION"
        );
    }

    private static void closeResources(
            VkDevice device,
            VulkanFrameRing frameRing,
            VulkanSwapchainResources swapchain,
            VulkanTrianglePipeline pipeline,
            VulkanOffscreenTargetPrototype offscreenTarget,
            JsonObject report,
            String reason
    ) {
        boolean idleBeforeTeardown = false;

        if (device != null) {
            try {
                int result =
                        vkDeviceWaitIdle(device);

                idleBeforeTeardown =
                        result == VK_SUCCESS;

                report.addProperty(
                        "frameSessionTeardownDeviceWaitIdleResult",
                        result
                );
            } catch (Throwable throwable) {
                report.addProperty(
                        "frameSessionTeardownDeviceWaitIdleError",
                        String.valueOf(
                                throwable.getMessage()
                        )
                );
            }
        }

        if (frameRing != null) {
            frameRing.close();
        }

        if (swapchain != null) {
            swapchain.close();
        }

        if (pipeline != null) {
            pipeline.close();

            report.addProperty(
                    "graphicsPipelineDestroyed",
                    true
            );
        }

        if (offscreenTarget != null) {
            offscreenTarget.close();
        }

        report.addProperty(
                "frameSessionGpuIdleBeforeResourceTeardown",
                idleBeforeTeardown
        );
        report.addProperty(
                "frameSessionTeardownReason",
                reason
        );
    }

    record ResizeOutcome(
            boolean changed,
            boolean pipelineRecreated,
            int oldWidth,
            int oldHeight,
            int newWidth,
            int newHeight
    ) {
    }

    record FrameOutcome(
            long semanticFrameSequence,
            int targetGeneration,
            int sourceWidth,
            int sourceHeight,
            int semanticDestinationWidth,
            int semanticDestinationHeight,
            int swapchainWidth,
            int swapchainHeight,
            int imageIndex,
            int acquireResult,
            int submitResult,
            int presentResult,
            boolean clearIncluded
    ) {
    }

    record SectionLayerTargetSnapshot(
            VulkanOffscreenTargetPrototype target,
            int generation
    ) {
    }
}
