package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Submits coherent mirrored MainTarget frames through Potato's persistent frame
 * ring and swapchain.
 *
 * <p>The presenter owns scheduling state only. Native resources remain owned by
 * {@link VulkanFrameSession}.</p>
 */
final class VulkanLiveFramePresenter {
    private static final long ACQUIRE_TIMEOUT_NANOSECONDS =
            5_000_000_000L;

    private static final long FENCE_TIMEOUT_NANOSECONDS =
            10_000_000_000L;

    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;

    private final VulkanFrameRing frameRing;
    private final VulkanSwapchainResources swapchain;
    private final VulkanSwapchainSupport.Configuration configuration;

    private final JsonObject report;

    private final long[] imageInFlightFences;

    private int currentFrame;

    private long liveFramePresentCount;
    private long liveFrameWithClearCount;
    private long liveFrameWithoutClearCount;

    VulkanLiveFramePresenter(
            VkDevice device,
            VkQueue graphicsQueue,
            VkQueue presentQueue,
            VulkanFrameRing frameRing,
            VulkanSwapchainResources swapchain,
            VulkanSwapchainSupport.Configuration configuration,
            JsonObject report
    ) {
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue;
        this.frameRing = frameRing;
        this.swapchain = swapchain;
        this.configuration = configuration;
        this.report = report;

        this.imageInFlightFences =
                new long[swapchain.imageCount()];

        this.currentFrame = 0;

        report.addProperty(
                "vulkanMainTargetFramePersistentFrameRingReused",
                true
        );
        report.addProperty(
                "vulkanMainTargetFramePersistentSwapchainReused",
                true
        );
        report.addProperty(
                "vulkanMainTargetFrameRuntimeSchedulerInitialized",
                true
        );

        report.addProperty(
                "vulkanMainTargetBlitPersistentFrameRingReused",
                true
        );
        report.addProperty(
                "vulkanMainTargetBlitPersistentSwapchainReused",
                true
        );
        report.addProperty(
                "vulkanMainTargetBlitRuntimeFrameSchedulerInitialized",
                true
        );
    }

    synchronized FrameOutcome present(
            VulkanOffscreenTargetPrototype target,
            int targetGeneration,
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
        VulkanFrameRing.Frame frame =
                frameRing.frame(
                        currentFrame
                );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            waitFence(
                    frame.inFlightFence(),
                    stack,
                    "WAIT_LIVE_MAIN_TARGET_FRAME_FENCE"
            );

            IntBuffer imageIndexBuffer =
                    stack.mallocInt(1);

            int acquireResult =
                    vkAcquireNextImageKHR(
                            device,
                            swapchain.swapchain(),
                            ACQUIRE_TIMEOUT_NANOSECONDS,
                            frame.imageAvailableSemaphore(),
                            NULL,
                            imageIndexBuffer
                    );

            report.addProperty(
                    "vulkanMainTargetFrameLastAcquireResult",
                    acquireResult
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastAcquireResult",
                    acquireResult
            );

            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                throw failure(
                        "ACQUIRE_LIVE_MAIN_TARGET_FRAME_IMAGE",
                        "Persistent mirror swapchain became out of date."
                );
            }

            if (acquireResult != VK_SUCCESS
                    && acquireResult != VK_SUBOPTIMAL_KHR) {
                throw failure(
                        "ACQUIRE_LIVE_MAIN_TARGET_FRAME_IMAGE",
                        "vkAcquireNextImageKHR failed with VkResult "
                                + acquireResult
                );
            }

            int imageIndex =
                    imageIndexBuffer.get(0);

            long previousImageFence =
                    imageInFlightFences[imageIndex];

            if (previousImageFence != NULL
                    && previousImageFence
                    != frame.inFlightFence()) {
                waitFence(
                        previousImageFence,
                        stack,
                        "WAIT_LIVE_MAIN_TARGET_SWAPCHAIN_IMAGE_FENCE"
                );
            }

            VulkanMainTargetFrameRecorder.record(
                    frame.commandBuffer(),
                    target,
                    swapchain.image(imageIndex),
                    configuration.width(),
                    configuration.height(),
                    swapchain.oldLayoutFor(imageIndex),
                    clearRequested,
                    clearRed,
                    clearGreen,
                    clearBlue,
                    clearAlpha,
                    stack,
                    report
            );

            int resetResult =
                    vkResetFences(
                            device,
                            stack.longs(
                                    frame.inFlightFence()
                            )
                    );

            report.addProperty(
                    "vulkanMainTargetFrameLastResetFenceResult",
                    resetResult
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastResetFenceResult",
                    resetResult
            );

            if (resetResult != VK_SUCCESS) {
                throw failure(
                        "RESET_LIVE_MAIN_TARGET_FRAME_FENCE",
                        "vkResetFences failed with VkResult "
                                + resetResult
                );
            }

            int submitResult =
                    submit(
                            frame.commandBuffer(),
                            frame.imageAvailableSemaphore(),
                            swapchain.renderFinishedSemaphore(
                                    imageIndex
                            ),
                            frame.inFlightFence(),
                            stack
                    );

            report.addProperty(
                    "vulkanMainTargetFrameLastSubmitResult",
                    submitResult
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastSubmitResult",
                    submitResult
            );

            if (submitResult != VK_SUCCESS) {
                throw failure(
                        "SUBMIT_LIVE_MAIN_TARGET_FRAME",
                        "vkQueueSubmit failed with VkResult "
                                + submitResult
                );
            }

            imageInFlightFences[imageIndex] =
                    frame.inFlightFence();

            int presentResult =
                    present(
                            imageIndex,
                            swapchain.renderFinishedSemaphore(
                                    imageIndex
                            ),
                            stack
                    );

            report.addProperty(
                    "vulkanMainTargetFrameLastPresentResult",
                    presentResult
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastPresentResult",
                    presentResult
            );

            swapchain.markPresented(
                    imageIndex
            );

            currentFrame =
                    (currentFrame + 1)
                            % frameRing.size();

            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR) {
                throw failure(
                        "PRESENT_LIVE_MAIN_TARGET_FRAME",
                        "Persistent mirror swapchain became out of date during present."
                );
            }

            if (presentResult != VK_SUCCESS
                    && presentResult != VK_SUBOPTIMAL_KHR) {
                throw failure(
                        "PRESENT_LIVE_MAIN_TARGET_FRAME",
                        "vkQueuePresentKHR failed with VkResult "
                                + presentResult
                );
            }

            liveFramePresentCount++;

            if (clearRequested) {
                liveFrameWithClearCount++;
            } else {
                liveFrameWithoutClearCount++;
            }

            report.addProperty(
                    "vulkanMainTargetFrameLivePresentCount",
                    liveFramePresentCount
            );
            report.addProperty(
                    "vulkanMainTargetFrameWithClearCount",
                    liveFrameWithClearCount
            );
            report.addProperty(
                    "vulkanMainTargetFrameWithoutClearCount",
                    liveFrameWithoutClearCount
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastSemanticSequence",
                    semanticFrameSequence
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastTargetGeneration",
                    targetGeneration
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastSourceWidth",
                    target.width()
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastSourceHeight",
                    target.height()
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastSemanticDestinationWidth",
                    semanticDestinationWidth
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastSemanticDestinationHeight",
                    semanticDestinationHeight
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastSwapchainWidth",
                    configuration.width()
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastSwapchainHeight",
                    configuration.height()
            );
            report.addProperty(
                    "vulkanMainTargetFrameLastClearRequested",
                    clearRequested
            );
            report.addProperty(
                    "vulkanMainTargetFrameNoDeviceWaitIdlePerFrame",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetFrameSingleQueueSubmission",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetFramePresented",
                    true
            );

            /*
             * Patch-030 compatibility diagnostics.
             */
            report.addProperty(
                    "vulkanMainTargetBlitLivePresentCount",
                    liveFramePresentCount
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastTargetGeneration",
                    targetGeneration
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastSourceWidth",
                    target.width()
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastSourceHeight",
                    target.height()
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastSemanticDestinationWidth",
                    semanticDestinationWidth
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastSemanticDestinationHeight",
                    semanticDestinationHeight
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastSwapchainWidth",
                    configuration.width()
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastSwapchainHeight",
                    configuration.height()
            );
            report.addProperty(
                    "vulkanMainTargetBlitLastDisableBlend",
                    disableBlend
            );
            report.addProperty(
                    "vulkanMainTargetBlitBlendFlagNotApplicableToTransfer",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetBlitScaledToPresentationExtent",
                    target.width()
                            != configuration.width()
                            || target.height()
                            != configuration.height()
            );
            report.addProperty(
                    "vulkanMainTargetBlitNoDeviceWaitIdlePerPresent",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetBlitFrameRingReused",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetBlitSwapchainReused",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetBlitPresented",
                    true
            );

            return new FrameOutcome(
                    semanticFrameSequence,
                    targetGeneration,
                    target.width(),
                    target.height(),
                    semanticDestinationWidth,
                    semanticDestinationHeight,
                    configuration.width(),
                    configuration.height(),
                    imageIndex,
                    acquireResult,
                    submitResult,
                    presentResult,
                    clearRequested
            );
        }
    }

    synchronized long liveFramePresentCount() {
        return liveFramePresentCount;
    }

    private void waitFence(
            long fence,
            MemoryStack stack,
            String stage
    ) {
        int result =
                vkWaitForFences(
                        device,
                        stack.longs(fence),
                        true,
                        FENCE_TIMEOUT_NANOSECONDS
                );

        if (result == VK_TIMEOUT) {
            throw failure(
                    stage,
                    "Fence timed out."
            );
        }

        if (result != VK_SUCCESS) {
            throw failure(
                    stage,
                    "vkWaitForFences failed with VkResult "
                            + result
            );
        }
    }

    private int submit(
            VkCommandBuffer commandBuffer,
            long imageAvailableSemaphore,
            long renderFinishedSemaphore,
            long fence,
            MemoryStack stack
    ) {
        VkSubmitInfo.Buffer submitInfo =
                VkSubmitInfo.calloc(
                        1,
                        stack
                );

        submitInfo
                .get(0)
                .sType$Default()
                .pWaitSemaphores(
                        stack.longs(
                                imageAvailableSemaphore
                        )
                )
                .pWaitDstStageMask(
                        stack.ints(
                                VK_PIPELINE_STAGE_TRANSFER_BIT
                        )
                )
                .pCommandBuffers(
                        stack.pointers(
                                commandBuffer.address()
                        )
                )
                .pSignalSemaphores(
                        stack.longs(
                                renderFinishedSemaphore
                        )
                );

        return vkQueueSubmit(
                graphicsQueue,
                submitInfo,
                fence
        );
    }

    private int present(
            int imageIndex,
            long renderFinishedSemaphore,
            MemoryStack stack
    ) {
        VkPresentInfoKHR presentInfo =
                VkPresentInfoKHR.calloc(stack)
                        .sType$Default()
                        .pWaitSemaphores(
                                stack.longs(
                                        renderFinishedSemaphore
                                )
                        )
                        .swapchainCount(1)
                        .pSwapchains(
                                stack.longs(
                                        swapchain.swapchain()
                                )
                        )
                        .pImageIndices(
                                stack.ints(
                                        imageIndex
                                )
                        );

        return vkQueuePresentKHR(
                presentQueue,
                presentInfo
        );
    }

    private static VulkanProbeException failure(
            String stage,
            String message
    ) {
        return new VulkanProbeException(
                stage,
                message
        );
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
}
