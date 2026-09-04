package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Records one coherent mirrored Minecraft MainTarget frame.
 *
 * <p>Patch 031 collapses the former standalone clear submission and live blit
 * submission into one reusable frame-ring command buffer:</p>
 *
 * <pre>
 * optional clear color/depth
 *     -> offscreen color TRANSFER_SRC
 *     -> swapchain TRANSFER_DST
 *     -> vkCmdBlitImage
 *     -> restore offscreen attachment layout
 *     -> swapchain PRESENT
 * </pre>
 */
final class VulkanMainTargetFrameRecorder {
    private VulkanMainTargetFrameRecorder() {
    }

    static void record(
            VkCommandBuffer commandBuffer,
            VulkanOffscreenTargetPrototype target,
            long swapchainImage,
            int swapchainWidth,
            int swapchainHeight,
            int swapchainOldLayout,
            boolean clearRequested,
            float clearRed,
            float clearGreen,
            float clearBlue,
            float clearAlpha,
            MemoryStack stack,
            JsonObject report
    ) {
        int result =
                vkResetCommandBuffer(
                        commandBuffer,
                        0
                );

        if (result != VK_SUCCESS) {
            throw failure(
                    "RESET_LIVE_MAIN_TARGET_FRAME_COMMAND_BUFFER",
                    "vkResetCommandBuffer failed with VkResult "
                            + result
            );
        }

        VkCommandBufferBeginInfo beginInfo =
                VkCommandBufferBeginInfo.calloc(stack)
                        .sType$Default()
                        .flags(
                                VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                        );

        result =
                vkBeginCommandBuffer(
                        commandBuffer,
                        beginInfo
                );

        if (result != VK_SUCCESS) {
            throw failure(
                    "BEGIN_LIVE_MAIN_TARGET_FRAME_COMMAND_BUFFER",
                    "vkBeginCommandBuffer failed with VkResult "
                            + result
            );
        }

        if (clearRequested) {
            transitionImage(
                    commandBuffer,
                    target.colorImage(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                            | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );

            VkImageSubresourceRange.Buffer colorRange =
                    VkImageSubresourceRange.calloc(
                            1,
                            stack
                    );

            colorRange
                    .get(0)
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            VkClearColorValue clearColor =
                    VkClearColorValue.calloc(stack);

            clearColor
                    .float32(0, clearRed)
                    .float32(1, clearGreen)
                    .float32(2, clearBlue)
                    .float32(3, clearAlpha);

            vkCmdClearColorImage(
                    commandBuffer,
                    target.colorImage(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearColor,
                    colorRange
            );

            if (target.useDepth()) {
                transitionImage(
                        commandBuffer,
                        target.depthImage(),
                        VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                                | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                                | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        stack
                );

                VkImageSubresourceRange depthRange =
                        VkImageSubresourceRange.calloc(stack)
                                .aspectMask(
                                        VK_IMAGE_ASPECT_DEPTH_BIT
                                )
                                .baseMipLevel(0)
                                .levelCount(1)
                                .baseArrayLayer(0)
                                .layerCount(1);

                VkClearDepthStencilValue clearDepth =
                        VkClearDepthStencilValue.calloc(stack)
                                .depth(1.0f)
                                .stencil(0);

                vkCmdClearDepthStencilImage(
                        commandBuffer,
                        target.depthImage(),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        clearDepth,
                        depthRange
                );

                transitionImage(
                        commandBuffer,
                        target.depthImage(),
                        VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                                | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
                        VK_PIPELINE_STAGE_TRANSFER_BIT,
                        VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT
                                | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
                        stack
                );
            }

            transitionImage(
                    commandBuffer,
                    target.colorImage(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );

            report.addProperty(
                    "vulkanMainTargetFrameClearRecorded",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetFrameClearColorRecorded",
                    true
            );
            report.addProperty(
                    "vulkanMainTargetFrameClearDepthRecorded",
                    target.useDepth()
            );
            report.addProperty(
                    "vulkanMainTargetFrameClearRed",
                    clearRed
            );
            report.addProperty(
                    "vulkanMainTargetFrameClearGreen",
                    clearGreen
            );
            report.addProperty(
                    "vulkanMainTargetFrameClearBlue",
                    clearBlue
            );
            report.addProperty(
                    "vulkanMainTargetFrameClearAlpha",
                    clearAlpha
            );
        } else {
            transitionImage(
                    commandBuffer,
                    target.colorImage(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                            | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_ACCESS_TRANSFER_READ_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    stack
            );

            report.addProperty(
                    "vulkanMainTargetFrameClearRecorded",
                    false
            );
        }

        transitionImage(
                commandBuffer,
                swapchainImage,
                VK_IMAGE_ASPECT_COLOR_BIT,
                swapchainOldLayout,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                stack
        );

        VkImageBlit.Buffer blit =
                VkImageBlit.calloc(
                        1,
                        stack
                );

        VkImageBlit region =
                blit.get(0);

        region
                .srcSubresource()
                .aspectMask(
                        VK_IMAGE_ASPECT_COLOR_BIT
                )
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

        region
                .srcOffsets(0)
                .x(0)
                .y(0)
                .z(0);

        region
                .srcOffsets(1)
                .x(
                        target.width()
                )
                .y(
                        target.height()
                )
                .z(1);

        region
                .dstSubresource()
                .aspectMask(
                        VK_IMAGE_ASPECT_COLOR_BIT
                )
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

        region
                .dstOffsets(0)
                .x(0)
                .y(0)
                .z(0);

        region
                .dstOffsets(1)
                .x(
                        swapchainWidth
                )
                .y(
                        swapchainHeight
                )
                .z(1);

        vkCmdBlitImage(
                commandBuffer,
                target.colorImage(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                swapchainImage,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                blit,
                VK_FILTER_NEAREST
        );

        transitionImage(
                commandBuffer,
                target.colorImage(),
                VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                        | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                stack
        );

        transitionImage(
                commandBuffer,
                swapchainImage,
                VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                stack
        );

        result =
                vkEndCommandBuffer(
                        commandBuffer
                );

        if (result != VK_SUCCESS) {
            throw failure(
                    "END_LIVE_MAIN_TARGET_FRAME_COMMAND_BUFFER",
                    "vkEndCommandBuffer failed with VkResult "
                            + result
            );
        }

        report.addProperty(
                "vulkanMainTargetFrameRecorderUsed",
                true
        );
        report.addProperty(
                "vulkanMainTargetFrameBlitRecorded",
                true
        );
        report.addProperty(
                "vulkanMainTargetFrameOffscreenColorReturnedToAttachmentLayout",
                true
        );
        report.addProperty(
                "vulkanMainTargetFrameSwapchainTransitionedToPresent",
                true
        );
        report.addProperty(
                "vulkanMainTargetFrameClearAndBlitSingleCommandBuffer",
                clearRequested
        );

        /*
         * Keep the Patch-030 compatibility diagnostics populated while the
         * implementation is now frame-lifecycle based.
         */
        report.addProperty(
                "vulkanMainTargetBlitOffscreenColorTransitionedForPresent",
                true
        );
        report.addProperty(
                "vulkanMainTargetBlitToSwapchainRecorded",
                true
        );
        report.addProperty(
                "vulkanMainTargetBlitOffscreenColorReturnedToAttachmentLayout",
                true
        );
        report.addProperty(
                "vulkanMainTargetBlitSwapchainTransitionedToPresent",
                true
        );
    }

    private static void transitionImage(
            VkCommandBuffer commandBuffer,
            long image,
            int aspectMask,
            int oldLayout,
            int newLayout,
            int srcAccessMask,
            int dstAccessMask,
            int srcStageMask,
            int dstStageMask,
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barrier =
                VkImageMemoryBarrier.calloc(
                        1,
                        stack
                );

        barrier
                .get(0)
                .sType$Default()
                .srcAccessMask(srcAccessMask)
                .dstAccessMask(dstAccessMask)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .dstQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .image(image);

        barrier
                .get(0)
                .subresourceRange()
                .aspectMask(aspectMask)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        vkCmdPipelineBarrier(
                commandBuffer,
                srcStageMask,
                dstStageMask,
                0,
                null,
                null,
                barrier
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
}
