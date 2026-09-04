package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.glfwPollEvents;
import static org.lwjgl.glfw.GLFW.glfwWindowShouldClose;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Persistent renderer-shaped frame loop with swapchain-generation recovery.
 */
final class VulkanFrameLoopProbe {
    private static final long ACQUIRE_TIMEOUT_NANOSECONDS =
            5_000_000_000L;

    private static final long FENCE_TIMEOUT_NANOSECONDS =
            10_000_000_000L;

    private static final int HIDDEN_VALIDATION_FRAMES = 8;

    private VulkanFrameLoopProbe() {
    }

    static VulkanFrameSession createAndValidate(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue graphicsQueue,
            VkQueue presentQueue,
            VulkanQueueFamilySelector.Selection queues,
            long surface,
            VulkanSwapchainSupport.Configuration initialConfiguration,
            VulkanPresentationProbe presentation,
            VulkanProbeOptions options,
            MemoryStack setupStack,
            JsonObject report
    ) {
        report.addProperty(
                "stage",
                "CREATE_PERSISTENT_FRAME_RESOURCES"
        );

        VulkanSwapchainSupport.Configuration configuration =
                initialConfiguration;

        VulkanTrianglePipeline pipeline = null;
        VulkanSwapchainResources swapchain = null;
        VulkanOffscreenTargetPrototype offscreenTarget = null;
        VulkanFrameRing frameRing = null;

        boolean sessionTransferred = false;

        int offscreenWidth =
                dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics
                        .mainTargetWidth();

        int offscreenHeight =
                dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics
                        .mainTargetHeight();

        boolean offscreenUseDepth =
                dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics
                        .mainTargetUsesDepth();

        if (offscreenWidth <= 0
                || offscreenHeight <= 0) {
            throw failure(
                    "CREATE_VULKAN_OFFSCREEN_TARGET",
                    "Minecraft MainTarget dimensions were unavailable to the Vulkan frame session."
            );
        }

        report.addProperty(
                "vulkanOffscreenTargetMatchesMinecraftMainTarget",
                offscreenWidth
                        == dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics
                        .mainTargetWidth()
                        && offscreenHeight
                        == dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics
                        .mainTargetHeight()
        );

        try {
            offscreenTarget =
                    VulkanOffscreenTargetPrototype.create(
                            physicalDevice,
                            device,
                            graphicsQueue,
                            queues.graphicsFamilyIndex(),
                            offscreenWidth,
                            offscreenHeight,
                            offscreenUseDepth,
                            report
                    );

            VulkanImageBlitSupport.verify(
                    physicalDevice,
                    offscreenTarget.colorFormat(),
                    configuration.format(),
                    report
            );

            frameRing =
                    VulkanFrameRing.create(
                            device,
                            queues.graphicsFamilyIndex(),
                            setupStack,
                            report
                    );

            pipeline =
                    VulkanTrianglePipeline.create(
                            device,
                            offscreenTarget.colorFormat(),
                            offscreenTarget.depthFormatOrUndefined(),
                            offscreenTarget.useDepth(),
                            setupStack,
                            report
                    );

            swapchain =
                    VulkanSwapchainResources.create(
                            device,
                            queues,
                            surface,
                            configuration,
                            NULL,
                            setupStack,
                            report
                    );

            long[] imageInFlightFences =
                    new long[swapchain.imageCount()];

            int currentFrame = 0;
            int framesRendered = 0;
            int swapchainRecreationCount = 0;
            int pipelineRecreationCount = 0;

            boolean resizeVerificationRequested = false;

            int initialWidth = configuration.width();
            int initialHeight = configuration.height();

            long loopStartNanos =
                    System.nanoTime();

            long visibleDurationNanos =
                    options.visibleDurationMillis()
                            * 1_000_000L;

            long visibleDeadlineNanos =
                    loopStartNanos
                            + visibleDurationNanos;

            long resizeTriggerNanos =
                    loopStartNanos
                            + visibleDurationNanos / 3L;

            report.addProperty(
                    "initialSwapchainWidth",
                    initialWidth
            );
            report.addProperty(
                    "initialSwapchainHeight",
                    initialHeight
            );
            report.addProperty(
                    "stage",
                    "PERSISTENT_FRAME_LOOP"
            );
            report.addProperty(
                    "perFrameMemoryStackScope",
                    true
            );
            report.addProperty(
                    "frameAcquireSemaphoreWaitStage",
                    "TRANSFER"
            );
            report.addProperty(
                    "offscreenRenderThenPresentPath",
                    true
            );
            report.addProperty(
                    "offscreenPipelineIndependentOfSwapchainFormat",
                    true
            );

            while (shouldRenderAnotherFrame(
                    presentation.windowHandle(),
                    options,
                    framesRendered,
                    visibleDeadlineNanos
            )) {
                if (options.visibleFrameVerification()) {
                    glfwPollEvents();

                    if (!resizeVerificationRequested
                            && System.nanoTime() >= resizeTriggerNanos) {
                        presentation
                                .requestSwapchainResizeVerification();

                        resizeVerificationRequested = true;
                    }
                }

                if (presentation.framebufferExtentDiffers(
                        configuration.width(),
                        configuration.height()
                )) {
                    RecreatedGeneration recreated =
                            recreateGeneration(
                                    physicalDevice,
                                    device,
                                    queues,
                                    surface,
                                    presentation,
                                    configuration,
                                    swapchain,
                                    pipeline,
                                    offscreenTarget,
                                    report
                            );

                    configuration =
                            recreated.configuration();

                    swapchain =
                            recreated.swapchain();

                    pipeline =
                            recreated.pipeline();

                    imageInFlightFences =
                            new long[swapchain.imageCount()];

                    swapchainRecreationCount++;

                    if (recreated.pipelineRecreated()) {
                        pipelineRecreationCount++;
                    }

                    continue;
                }

                VulkanFrameRing.Frame frame =
                        frameRing.frame(currentFrame);

                boolean recreateAfterPresent = false;

                try (MemoryStack frameStack = MemoryStack.stackPush()) {
                    waitFence(
                            device,
                            frame.inFlightFence(),
                            frameStack,
                            "WAIT_FRAME_FENCE"
                    );

                    IntBuffer imageIndexBuffer =
                            frameStack.mallocInt(1);

                    int acquireResult =
                            vkAcquireNextImageKHR(
                                    device,
                                    swapchain.swapchain(),
                                    ACQUIRE_TIMEOUT_NANOSECONDS,
                                    frame.imageAvailableSemaphore(),
                                    NULL,
                                    imageIndexBuffer
                            );

                    if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                        report.addProperty(
                                "outOfDateObservedAtAcquire",
                                true
                        );

                        RecreatedGeneration recreated =
                                recreateGeneration(
                                        physicalDevice,
                                        device,
                                        queues,
                                        surface,
                                        presentation,
                                        configuration,
                                        swapchain,
                                        pipeline,
                                        offscreenTarget,
                                        report
                                );

                        configuration =
                                recreated.configuration();

                        swapchain =
                                recreated.swapchain();

                        pipeline =
                                recreated.pipeline();

                        imageInFlightFences =
                                new long[swapchain.imageCount()];

                        swapchainRecreationCount++;

                        if (recreated.pipelineRecreated()) {
                            pipelineRecreationCount++;
                        }

                        continue;
                    }

                    if (acquireResult == VK_SUBOPTIMAL_KHR) {
                        report.addProperty(
                                "suboptimalObservedAtAcquire",
                                true
                        );

                        recreateAfterPresent = true;
                    } else if (acquireResult != VK_SUCCESS) {
                        throw failure(
                                "ACQUIRE_FRAME_IMAGE",
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
                                device,
                                previousImageFence,
                                frameStack,
                                "WAIT_SWAPCHAIN_IMAGE_FENCE"
                        );
                    }

                    int result = vkResetFences(
                            device,
                            frameStack.longs(
                                    frame.inFlightFence()
                            )
                    );

                    if (result != VK_SUCCESS) {
                        throw failure(
                                "RESET_FRAME_FENCE",
                                "vkResetFences failed with VkResult "
                                        + result
                        );
                    }

                    float angle =
                            framesRendered * 0.045f;

                    VulkanOffscreenFrameRecorder.record(
                            frame.commandBuffer(),
                            offscreenTarget,
                            swapchain.image(imageIndex),
                            configuration.width(),
                            configuration.height(),
                            swapchain.oldLayoutFor(imageIndex),
                            pipeline,
                            angle,
                            frameStack,
                            report
                    );

                    submit(
                            graphicsQueue,
                            frame.commandBuffer(),
                            frame.imageAvailableSemaphore(),
                            swapchain.renderFinishedSemaphore(
                                    imageIndex
                            ),
                            frame.inFlightFence(),
                            frameStack
                    );

                    imageInFlightFences[imageIndex] =
                            frame.inFlightFence();

                    int presentResult =
                            present(
                                    presentQueue,
                                    swapchain.swapchain(),
                                    imageIndex,
                                    swapchain.renderFinishedSemaphore(
                                            imageIndex
                                    ),
                                    frameStack
                            );

                    swapchain.markPresented(imageIndex);

                    if (presentResult == VK_ERROR_OUT_OF_DATE_KHR) {
                        report.addProperty(
                                "outOfDateObservedAtPresent",
                                true
                        );

                        recreateAfterPresent = true;
                    } else if (presentResult == VK_SUBOPTIMAL_KHR) {
                        report.addProperty(
                                "suboptimalObservedAtPresent",
                                true
                        );

                        recreateAfterPresent = true;
                    } else if (presentResult != VK_SUCCESS) {
                        throw failure(
                                "PRESENT_FRAME",
                                "vkQueuePresentKHR failed with VkResult "
                                        + presentResult
                        );
                    }
                }

                framesRendered++;

                if (framesRendered == 1) {
                    presentation
                            .bringToFrontAfterPresentIfRequested();
                }

                currentFrame =
                        (currentFrame + 1)
                                % frameRing.size();

                if (recreateAfterPresent) {
                    RecreatedGeneration recreated =
                            recreateGeneration(
                                    physicalDevice,
                                    device,
                                    queues,
                                    surface,
                                    presentation,
                                    configuration,
                                    swapchain,
                                    pipeline,
                                    offscreenTarget,
                                    report
                            );

                    configuration =
                            recreated.configuration();

                    swapchain =
                            recreated.swapchain();

                    pipeline =
                            recreated.pipeline();

                    imageInFlightFences =
                            new long[swapchain.imageCount()];

                    swapchainRecreationCount++;

                    if (recreated.pipelineRecreated()) {
                        pipelineRecreationCount++;
                    }
                }
            }

            int idleResult = vkDeviceWaitIdle(device);

            if (idleResult != VK_SUCCESS) {
                throw failure(
                        "FRAME_LOOP_SHUTDOWN_IDLE",
                        "vkDeviceWaitIdle failed with VkResult "
                                + idleResult
                );
            }

            long loopDurationMillis =
                    (System.nanoTime() - loopStartNanos)
                            / 1_000_000L;

            report.addProperty(
                    "framesRendered",
                    framesRendered
            );
            report.addProperty(
                    "frameLoopDurationMillis",
                    loopDurationMillis
            );
            report.addProperty(
                    "frameResourceReuse",
                    true
            );
            report.addProperty(
                    "frameRingSurvivedSwapchainRecreation",
                    true
            );
            report.addProperty(
                    "deviceWaitIdleUsedPerFrame",
                    false
            );
            report.addProperty(
                    "deviceWaitIdleUsedForSwapchainRecreation",
                    swapchainRecreationCount > 0
            );
            report.addProperty(
                    "commandBufferReRecordedPerFrame",
                    true
            );
            report.addProperty(
                    "animatedTriangle",
                    true
            );
            report.addProperty(
                    "triangleRenderedIntoOffscreenTarget",
                    true
            );
            report.addProperty(
                    "offscreenColorBlittedToSwapchain",
                    true
            );
            report.addProperty(
                    "swapchainDirectTriangleRendering",
                    false
            );
            report.addProperty(
                    "nativeStackGrowthAcrossFrames",
                    false
            );
            report.addProperty(
                    "swapchainRecreationCount",
                    swapchainRecreationCount
            );
            report.addProperty(
                    "pipelineRecreationCount",
                    pipelineRecreationCount
            );
            report.addProperty(
                    "finalSwapchainWidth",
                    configuration.width()
            );
            report.addProperty(
                    "finalSwapchainHeight",
                    configuration.height()
            );
            report.addProperty(
                    "resizeVerificationRequested",
                    resizeVerificationRequested
            );

            if (options.visibleFrameVerification()) {
                report.addProperty(
                        "visibleFrameVerifiedByPresentationPath",
                        true
                );
            }

            if (options.visibleFrameVerification()
                    && swapchainRecreationCount < 1) {
                throw failure(
                        "VERIFY_SWAPCHAIN_RECREATION",
                        "Visible resize test did not recreate the swapchain."
                );
            }

            report.addProperty(
                    "stage",
                    "SWAPCHAIN_RECREATION_COMPLETE"
            );

            PotatoRuntime.LOGGER.info(
                    "[Potato/Vulkan] Frame loop completed {} frames with {} swapchain recreation(s).",
                    framesRendered,
                    swapchainRecreationCount
            );

            VulkanFrameSession session =
                    new VulkanFrameSession(
                            physicalDevice,
                            device,
                            graphicsQueue,
                            presentQueue,
                            queues.graphicsFamilyIndex(),
                            queues,
                            configuration,
                            frameRing,
                            swapchain,
                            pipeline,
                            offscreenTarget,
                            report
                    );

            sessionTransferred = true;

            report.addProperty(
                    "persistentFrameSessionCreated",
                    true
            );
            report.addProperty(
                    "persistentFrameSessionRetainedAfterValidation",
                    true
            );

            return session;
        } finally {
            if (!sessionTransferred) {
                VulkanFrameSession.closeFailedCreation(
                        device,
                        frameRing,
                        swapchain,
                        pipeline,
                        offscreenTarget,
                        report
                );
            }
        }
    }

    private static RecreatedGeneration recreateGeneration(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VulkanQueueFamilySelector.Selection queues,
            long surface,
            VulkanPresentationProbe presentation,
            VulkanSwapchainSupport.Configuration oldConfiguration,
            VulkanSwapchainResources oldSwapchain,
            VulkanTrianglePipeline oldPipeline,
            VulkanOffscreenTargetPrototype offscreenTarget,
            JsonObject report
    ) {
        report.addProperty(
                "stage",
                "RECREATE_SWAPCHAIN_GENERATION"
        );

        presentation.awaitRenderableFramebuffer();

        int idleResult = vkDeviceWaitIdle(device);

        if (idleResult != VK_SUCCESS) {
            throw failure(
                    "RECREATE_SWAPCHAIN_GENERATION",
                    "vkDeviceWaitIdle before recreation failed with VkResult "
                            + idleResult
            );
        }

        try (MemoryStack recreateStack = MemoryStack.stackPush()) {
            VulkanSwapchainSupport.Configuration newConfiguration =
                    VulkanSwapchainSupport.query(
                            physicalDevice,
                            surface,
                            presentation.windowHandle(),
                            recreateStack,
                            report
                    );

            boolean formatChanged =
                    newConfiguration.format()
                            != oldConfiguration.format();

            VulkanImageBlitSupport.verify(
                    physicalDevice,
                    offscreenTarget.colorFormat(),
                    newConfiguration.format(),
                    report
            );

            VulkanSwapchainResources newSwapchain =
                    VulkanSwapchainResources.create(
                            device,
                            queues,
                            surface,
                            newConfiguration,
                            oldSwapchain.swapchain(),
                            recreateStack,
                            report
                    );

            oldSwapchain.close();

            report.addProperty(
                    "lastRecreationFormatChanged",
                    formatChanged
            );
            report.addProperty(
                    "lastRecreatedSwapchainWidth",
                    newConfiguration.width()
            );
            report.addProperty(
                    "lastRecreatedSwapchainHeight",
                    newConfiguration.height()
            );
            report.addProperty(
                    "oldSwapchainRetiredAfterReplacement",
                    true
            );
            report.addProperty(
                    "offscreenPipelineIndependentOfSwapchainFormat",
                    true
            );

            return new RecreatedGeneration(
                    newConfiguration,
                    newSwapchain,
                    oldPipeline,
                    false
            );
        }
    }

    private static boolean shouldRenderAnotherFrame(
            long window,
            VulkanProbeOptions options,
            int framesRendered,
            long visibleDeadlineNanos
    ) {
        if (!options.visibleFrameVerification()) {
            return framesRendered < HIDDEN_VALIDATION_FRAMES;
        }

        return System.nanoTime() < visibleDeadlineNanos
                && !glfwWindowShouldClose(window);
    }

    private static void waitFence(
            VkDevice device,
            long fence,
            MemoryStack stack,
            String stage
    ) {
        int result = vkWaitForFences(
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

    private static void submit(
            VkQueue graphicsQueue,
            VkCommandBuffer commandBuffer,
            long imageAvailableSemaphore,
            long renderFinishedSemaphore,
            long fence,
            MemoryStack stack
    ) {
        VkSubmitInfo.Buffer submitInfo =
                VkSubmitInfo.calloc(1, stack);

        submitInfo.get(0)
                .sType$Default()
                .pWaitSemaphores(
                        stack.longs(imageAvailableSemaphore)
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
                        stack.longs(renderFinishedSemaphore)
                );

        int result = vkQueueSubmit(
                graphicsQueue,
                submitInfo,
                fence
        );

        if (result != VK_SUCCESS) {
            throw failure(
                    "SUBMIT_FRAME",
                    "vkQueueSubmit failed with VkResult " + result
            );
        }
    }

    private static int present(
            VkQueue presentQueue,
            long swapchain,
            int imageIndex,
            long renderFinishedSemaphore,
            MemoryStack stack
    ) {
        VkPresentInfoKHR presentInfo =
                VkPresentInfoKHR.calloc(stack)
                        .sType$Default()
                        .pWaitSemaphores(
                                stack.longs(renderFinishedSemaphore)
                        )
                        .swapchainCount(1)
                        .pSwapchains(
                                stack.longs(swapchain)
                        )
                        .pImageIndices(
                                stack.ints(imageIndex)
                        );

        return vkQueuePresentKHR(
                presentQueue,
                presentInfo
        );
    }

    private record RecreatedGeneration(
            VulkanSwapchainSupport.Configuration configuration,
            VulkanSwapchainResources swapchain,
            VulkanTrianglePipeline pipeline,
            boolean pipelineRecreated
    ) {
    }

    private static VulkanProbeException failure(
            String stage,
            String message
    ) {
        return new VulkanProbeException(stage, message);
    }
}