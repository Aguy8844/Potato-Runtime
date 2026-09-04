package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Records one renderer-shaped frame:
 *
 * <ol>
 *     <li>render triangle into Potato's offscreen color/depth attachments,</li>
 *     <li>transition the offscreen color image for transfer,</li>
 *     <li>blit/scale into the acquired swapchain image,</li>
 *     <li>return the offscreen image to attachment layout,</li>
 *     <li>transition the swapchain image to presentation.</li>
 * </ol>
 */
final class VulkanOffscreenFrameRecorder {
    private VulkanOffscreenFrameRecorder() {
    }

    static void record(
            VkCommandBuffer commandBuffer,
            VulkanOffscreenTargetPrototype target,
            long swapchainImage,
            int swapchainWidth,
            int swapchainHeight,
            int swapchainOldLayout,
            VulkanTrianglePipeline pipeline,
            float angleRadians,
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
                    "RESET_OFFSCREEN_FRAME_COMMAND_BUFFER",
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
                    "RECORD_OFFSCREEN_FRAME",
                    "vkBeginCommandBuffer failed with VkResult "
                            + result
            );
        }

        VkRenderingAttachmentInfo.Buffer colorAttachment =
                VkRenderingAttachmentInfo.calloc(
                        1,
                        stack
                );

        colorAttachment
                .get(0)
                .sType$Default()
                .imageView(
                        target.colorImageView()
                )
                .imageLayout(
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                )
                .loadOp(
                        VK_ATTACHMENT_LOAD_OP_CLEAR
                )
                .storeOp(
                        VK_ATTACHMENT_STORE_OP_STORE
                );

        colorAttachment
                .get(0)
                .clearValue()
                .color()
                .float32(0, 0.025f)
                .float32(1, 0.030f)
                .float32(2, 0.040f)
                .float32(3, 1.0f);

        VkRenderingAttachmentInfo depthAttachment =
                null;

        if (target.useDepth()) {
            depthAttachment =
                    VkRenderingAttachmentInfo.calloc(stack)
                            .sType$Default()
                            .imageView(
                                    target.depthImageView()
                            )
                            .imageLayout(
                                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                            )
                            .loadOp(
                                    VK_ATTACHMENT_LOAD_OP_CLEAR
                            )
                            .storeOp(
                                    VK_ATTACHMENT_STORE_OP_STORE
                            );

            depthAttachment
                    .clearValue()
                    .depthStencil()
                    .depth(1.0f)
                    .stencil(0);
        }

        VkRenderingInfo renderingInfo =
                VkRenderingInfo.calloc(stack)
                        .sType$Default()
                        .layerCount(1)
                        .pColorAttachments(
                                colorAttachment
                        );

        if (depthAttachment != null) {
            renderingInfo.pDepthAttachment(
                    depthAttachment
            );
        }

        renderingInfo
                .renderArea()
                .offset()
                .x(0)
                .y(0);

        renderingInfo
                .renderArea()
                .extent()
                .width(
                        target.width()
                )
                .height(
                        target.height()
                );

        vkCmdBeginRendering(
                commandBuffer,
                renderingInfo
        );

        VkViewport.Buffer viewport =
                VkViewport.calloc(
                        1,
                        stack
                );

        viewport
                .get(0)
                .x(0.0f)
                .y(0.0f)
                .width(
                        (float) target.width()
                )
                .height(
                        (float) target.height()
                )
                .minDepth(0.0f)
                .maxDepth(1.0f);

        vkCmdSetViewport(
                commandBuffer,
                0,
                viewport
        );

        VkRect2D.Buffer scissor =
                VkRect2D.calloc(
                        1,
                        stack
                );

        scissor
                .get(0)
                .offset()
                .x(0)
                .y(0);

        scissor
                .get(0)
                .extent()
                .width(
                        target.width()
                )
                .height(
                        target.height()
                );

        vkCmdSetScissor(
                commandBuffer,
                0,
                scissor
        );

        vkCmdBindPipeline(
                commandBuffer,
                VK_PIPELINE_BIND_POINT_GRAPHICS,
                pipeline.pipeline()
        );

        vkCmdPushConstants(
                commandBuffer,
                pipeline.layout(),
                VK_SHADER_STAGE_VERTEX_BIT,
                0,
                stack.floats(
                        angleRadians
                )
        );

        vkCmdDraw(
                commandBuffer,
                3,
                1,
                0,
                0
        );

        vkCmdEndRendering(
                commandBuffer
        );

        transitionColorAttachmentToTransferSource(
                commandBuffer,
                target.colorImage(),
                stack
        );

        transitionSwapchainToTransferDestination(
                commandBuffer,
                swapchainImage,
                swapchainOldLayout,
                stack
        );

        blitOffscreenToSwapchain(
                commandBuffer,
                target,
                swapchainImage,
                swapchainWidth,
                swapchainHeight,
                stack
        );

        transitionOffscreenBackToColorAttachment(
                commandBuffer,
                target.colorImage(),
                stack
        );

        transitionSwapchainToPresent(
                commandBuffer,
                swapchainImage,
                stack
        );

        result =
                vkEndCommandBuffer(
                        commandBuffer
                );

        if (result != VK_SUCCESS) {
            throw failure(
                    "RECORD_OFFSCREEN_FRAME",
                    "vkEndCommandBuffer failed with VkResult "
                            + result
            );
        }

        report.addProperty(
                "lastFrameAngleRadians",
                angleRadians
        );
        report.addProperty(
                "pushConstantsUsed",
                true
        );
        report.addProperty(
                "pushConstantBytesPerDraw",
                Float.BYTES
        );

        report.addProperty(
                "offscreenTriangleRendered",
                true
        );
        report.addProperty(
                "offscreenDepthAttachmentUsed",
                target.useDepth()
        );
        report.addProperty(
                "offscreenColorTransitionedForBlit",
                true
        );
        report.addProperty(
                "offscreenBlitToSwapchainRecorded",
                true
        );
        report.addProperty(
                "offscreenColorReturnedToAttachmentLayout",
                true
        );
        report.addProperty(
                "swapchainTransitionedToTransferDestination",
                true
        );
        report.addProperty(
                "swapchainTransitionedToPresentAfterBlit",
                true
        );
    }

    private static void transitionColorAttachmentToTransferSource(
            VkCommandBuffer commandBuffer,
            long image,
            MemoryStack stack
    ) {
        barrier(
                commandBuffer,
                image,
                VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                stack
        );
    }

    private static void transitionOffscreenBackToColorAttachment(
            VkCommandBuffer commandBuffer,
            long image,
            MemoryStack stack
    ) {
        barrier(
                commandBuffer,
                image,
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
    }

    private static void transitionSwapchainToTransferDestination(
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            MemoryStack stack
    ) {
        if (oldLayout != VK_IMAGE_LAYOUT_UNDEFINED
                && oldLayout != VK_IMAGE_LAYOUT_PRESENT_SRC_KHR) {
            throw failure(
                    "RECORD_OFFSCREEN_FRAME",
                    "Unexpected swapchain old layout before transfer: "
                            + oldLayout
            );
        }

        barrier(
                commandBuffer,
                image,
                VK_IMAGE_ASPECT_COLOR_BIT,
                oldLayout,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                stack
        );
    }

    private static void transitionSwapchainToPresent(
            VkCommandBuffer commandBuffer,
            long image,
            MemoryStack stack
    ) {
        barrier(
                commandBuffer,
                image,
                VK_IMAGE_ASPECT_COLOR_BIT,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                stack
        );
    }

    private static void blitOffscreenToSwapchain(
            VkCommandBuffer commandBuffer,
            VulkanOffscreenTargetPrototype target,
            long swapchainImage,
            int swapchainWidth,
            int swapchainHeight,
            MemoryStack stack
    ) {
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
    }

    private static void barrier(
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
                .srcAccessMask(
                        srcAccessMask
                )
                .dstAccessMask(
                        dstAccessMask
                )
                .oldLayout(
                        oldLayout
                )
                .newLayout(
                        newLayout
                )
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
                .aspectMask(
                        aspectMask
                )
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
