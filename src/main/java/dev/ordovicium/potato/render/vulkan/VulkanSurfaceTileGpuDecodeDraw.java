package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Executes one bounded textured surface-tile validation draw.
 *
 * <p>The draw remains hidden/offscreen. It proves that one fragment program
 * simultaneously consumes the merged-rectangle SSBO, exact tile SSBO, real
 * Minecraft BLOCK atlas and real 16x16 lightmap while OpenGL stays visible.</p>
 */
final class VulkanSurfaceTileGpuDecodeDraw {

    private static final long FENCE_TIMEOUT_NANOSECONDS =
            10_000_000_000L;

    private VulkanSurfaceTileGpuDecodeDraw() {
    }

    static Outcome execute(
            VkDevice device,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            VulkanFrameSession.SectionLayerTargetSnapshot targetSnapshot,
            long rectangleBuffer,
            long rectangleBytes,
            long tileBuffer,
            long tileBytes,
            int rectangleCount,
            VulkanBlockTextureUploadPrototype textures,
            JsonObject report
    ) {
        long commandPool = NULL;
        VkCommandBuffer commandBuffer = null;
        long fence = NULL;
        long queryPool = NULL;

        VulkanSurfaceTileDescriptorSet descriptors = null;
        VulkanSurfaceTileDecodePipeline pipeline = null;

        boolean pipelineCreated = false;
        boolean descriptorsCreated = false;
        boolean queueSubmitUsed = false;
        boolean fenceWaitUsed = false;
        boolean resourcesClosed = false;
        boolean fallbackQueueWaitIdleUsed = false;

        int queueSubmitResult = Integer.MIN_VALUE;
        int fenceWaitResult = Integer.MIN_VALUE;
        int queryResult = Integer.MIN_VALUE;

        long rasterizedSamples = 0L;
        int drawVertexCount = 0;

        String failure = "";

        boolean submitted = false;
        boolean gpuComplete = false;

        try {
            if (device == null
                    || graphicsQueue == null
                    || graphicsQueueFamilyIndex < 0
                    || targetSnapshot == null
                    || rectangleBuffer == NULL
                    || tileBuffer == NULL
                    || rectangleBytes <= 0L
                    || tileBytes <= 0L
                    || rectangleCount <= 0
                    || textures == null
                    || !textures.verified()) {

                throw new VulkanProbeException(
                        "SURFACE_TILE_TEXTURED_DECODE_INPUT",
                        "Textured GPU decode prerequisites are incomplete."
                );
            }

            VulkanOffscreenTargetPrototype target =
                    targetSnapshot.target();

            descriptors =
                    VulkanSurfaceTileDescriptorSet.create(
                            device,
                            rectangleBuffer,
                            rectangleBytes,
                            tileBuffer,
                            tileBytes,
                            textures,
                            report
                    );

            descriptorsCreated =
                    descriptors.verified();

            pipeline =
                    VulkanSurfaceTileDecodePipeline.create(
                            device,
                            target.colorFormat(),
                            descriptors.layout(),
                            report
                    );

            pipelineCreated =
                    pipeline.created();

            try (MemoryStack stack =
                         MemoryStack.stackPush()) {

                VkCommandPoolCreateInfo poolInfo =
                        VkCommandPoolCreateInfo.calloc(
                                stack
                        )
                                .sType$Default()
                                .flags(
                                        VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                                )
                                .queueFamilyIndex(
                                        graphicsQueueFamilyIndex
                                );

                java.nio.LongBuffer poolPointer =
                        stack.mallocLong(
                                1
                        );

                int result =
                        vkCreateCommandPool(
                                device,
                                poolInfo,
                                null,
                                poolPointer
                        );

                report.addProperty(
                        "surfaceTileTexturedCommandPoolCreateResult",
                        result
                );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "CREATE_SURFACE_TILE_TEXTURED_COMMAND_POOL",
                            "vkCreateCommandPool failed with VkResult "
                                    + result
                    );
                }

                commandPool =
                        poolPointer.get(
                                0
                        );

                VkCommandBufferAllocateInfo allocateInfo =
                        VkCommandBufferAllocateInfo.calloc(
                                stack
                        )
                                .sType$Default()
                                .commandPool(
                                        commandPool
                                )
                                .level(
                                        VK_COMMAND_BUFFER_LEVEL_PRIMARY
                                )
                                .commandBufferCount(
                                        1
                                );

                PointerBuffer commandPointer =
                        stack.mallocPointer(
                                1
                        );

                result =
                        vkAllocateCommandBuffers(
                                device,
                                allocateInfo,
                                commandPointer
                        );

                report.addProperty(
                        "surfaceTileTexturedCommandBufferAllocateResult",
                        result
                );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "ALLOCATE_SURFACE_TILE_TEXTURED_COMMAND_BUFFER",
                            "vkAllocateCommandBuffers failed with VkResult "
                                    + result
                    );
                }

                commandBuffer =
                        new VkCommandBuffer(
                                commandPointer.get(
                                        0
                                ),
                                device
                        );

                VkFenceCreateInfo fenceInfo =
                        VkFenceCreateInfo.calloc(
                                stack
                        )
                                .sType$Default();

                java.nio.LongBuffer fencePointer =
                        stack.mallocLong(
                                1
                        );

                result =
                        vkCreateFence(
                                device,
                                fenceInfo,
                                null,
                                fencePointer
                        );

                report.addProperty(
                        "surfaceTileTexturedFenceCreateResult",
                        result
                );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "CREATE_SURFACE_TILE_TEXTURED_FENCE",
                            "vkCreateFence failed with VkResult "
                                    + result
                    );
                }

                fence =
                        fencePointer.get(
                                0
                        );

                VkQueryPoolCreateInfo queryInfo =
                        VkQueryPoolCreateInfo.calloc(
                                stack
                        )
                                .sType$Default()
                                .queryType(
                                        VK_QUERY_TYPE_OCCLUSION
                                )
                                .queryCount(
                                        1
                                );

                java.nio.LongBuffer queryPointer =
                        stack.mallocLong(
                                1
                        );

                result =
                        vkCreateQueryPool(
                                device,
                                queryInfo,
                                null,
                                queryPointer
                        );

                report.addProperty(
                        "surfaceTileTexturedQueryPoolCreateResult",
                        result
                );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "CREATE_SURFACE_TILE_TEXTURED_QUERY_POOL",
                            "vkCreateQueryPool failed with VkResult "
                                    + result
                    );
                }

                queryPool =
                        queryPointer.get(
                                0
                        );

                VkCommandBufferBeginInfo beginInfo =
                        VkCommandBufferBeginInfo.calloc(
                                stack
                        )
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
                    throw new VulkanProbeException(
                            "BEGIN_SURFACE_TILE_TEXTURED_COMMAND_BUFFER",
                            "vkBeginCommandBuffer failed with VkResult "
                                    + result
                    );
                }

                vkCmdResetQueryPool(
                        commandBuffer,
                        queryPool,
                        0,
                        1
                );

                vkCmdBeginQuery(
                        commandBuffer,
                        queryPool,
                        0,
                        0
                );

                VkRenderingAttachmentInfo.Buffer colorAttachment =
                        VkRenderingAttachmentInfo.calloc(
                                1,
                                stack
                        );

                colorAttachment.get(0)
                        .sType$Default()
                        .imageView(
                                target.colorImageView()
                        )
                        .imageLayout(
                                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                        )
                        .loadOp(
                                VK_ATTACHMENT_LOAD_OP_LOAD
                        )
                        .storeOp(
                                VK_ATTACHMENT_STORE_OP_STORE
                        );

                VkRenderingInfo renderingInfo =
                        VkRenderingInfo.calloc(
                                stack
                        )
                                .sType$Default()
                                .layerCount(
                                        1
                                )
                                .pColorAttachments(
                                        colorAttachment
                                );

                renderingInfo.renderArea()
                        .offset()
                        .x(0)
                        .y(0);

                renderingInfo.renderArea()
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

                viewport.get(0)
                        .x(
                                0.0f
                        )
                        .y(
                                0.0f
                        )
                        .width(
                                (float) target.width()
                        )
                        .height(
                                (float) target.height()
                        )
                        .minDepth(
                                0.0f
                        )
                        .maxDepth(
                                1.0f
                        );

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

                scissor.get(0)
                        .offset()
                        .x(0)
                        .y(0);

                scissor.get(0)
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

                vkCmdBindDescriptorSets(
                        commandBuffer,
                        VK_PIPELINE_BIND_POINT_GRAPHICS,
                        pipeline.layout(),
                        0,
                        stack.longs(
                                descriptors.set()
                        ),
                        null
                );

                drawVertexCount =
                        Math.multiplyExact(
                                rectangleCount,
                                6
                        );

                vkCmdDraw(
                        commandBuffer,
                        drawVertexCount,
                        1,
                        0,
                        0
                );

                vkCmdEndRendering(
                        commandBuffer
                );

                vkCmdEndQuery(
                        commandBuffer,
                        queryPool,
                        0
                );

                result =
                        vkEndCommandBuffer(
                                commandBuffer
                        );

                if (result != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "END_SURFACE_TILE_TEXTURED_COMMAND_BUFFER",
                            "vkEndCommandBuffer failed with VkResult "
                                    + result
                    );
                }

                VkSubmitInfo.Buffer submit =
                        VkSubmitInfo.calloc(
                                1,
                                stack
                        );

                submit.get(0)
                        .sType$Default()
                        .pCommandBuffers(
                                stack.pointers(
                                        commandBuffer.address()
                                )
                        );

                queueSubmitUsed = true;

                queueSubmitResult =
                        vkQueueSubmit(
                                graphicsQueue,
                                submit,
                                fence
                        );

                if (queueSubmitResult != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "SUBMIT_SURFACE_TILE_TEXTURED_DRAW",
                            "vkQueueSubmit failed with VkResult "
                                    + queueSubmitResult
                    );
                }

                submitted = true;
                fenceWaitUsed = true;

                fenceWaitResult =
                        vkWaitForFences(
                                device,
                                stack.longs(
                                        fence
                                ),
                                true,
                                FENCE_TIMEOUT_NANOSECONDS
                        );

                if (fenceWaitResult != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "WAIT_SURFACE_TILE_TEXTURED_DRAW",
                            "vkWaitForFences returned "
                                    + fenceWaitResult
                    );
                }

                gpuComplete = true;

                ByteBuffer queryData =
                        stack.malloc(
                                Long.BYTES
                        )
                                .order(
                                        ByteOrder.nativeOrder()
                                );

                queryResult =
                        vkGetQueryPoolResults(
                                device,
                                queryPool,
                                0,
                                1,
                                queryData,
                                Long.BYTES,
                                VK_QUERY_RESULT_64_BIT
                        );

                if (queryResult != VK_SUCCESS) {
                    throw new VulkanProbeException(
                            "READ_SURFACE_TILE_TEXTURED_QUERY",
                            "vkGetQueryPoolResults returned "
                                    + queryResult
                    );
                }

                rasterizedSamples =
                        queryData.getLong(
                                0
                        );

                if (rasterizedSamples <= 0L) {
                    throw new VulkanProbeException(
                            "VERIFY_SURFACE_TILE_TEXTURED_RASTERIZATION",
                            "Textured surface decode produced zero rasterized samples."
                    );
                }

                report.addProperty(
                        "surfaceTileTexturedTargetGeneration",
                        targetSnapshot.generation()
                );
                report.addProperty(
                        "surfaceTileTexturedTargetWidth",
                        target.width()
                );
                report.addProperty(
                        "surfaceTileTexturedTargetHeight",
                        target.height()
                );
                report.addProperty(
                        "surfaceTileTexturedTargetColorFormat",
                        target.colorFormat()
                );
            }
        } catch (Throwable throwable) {
            failure =
                    throwable.getClass()
                            .getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        } finally {
            if (submitted
                    && !gpuComplete
                    && graphicsQueue != null) {
                try {
                    fallbackQueueWaitIdleUsed = true;

                    vkQueueWaitIdle(
                            graphicsQueue
                    );
                } catch (Throwable ignored) {
                    // Preserve the original failure.
                }
            }

            if (queryPool != NULL) {
                vkDestroyQueryPool(
                        device,
                        queryPool,
                        null
                );
            }

            if (fence != NULL) {
                vkDestroyFence(
                        device,
                        fence,
                        null
                );
            }

            if (commandPool != NULL) {
                vkDestroyCommandPool(
                        device,
                        commandPool,
                        null
                );
            }

            if (pipeline != null) {
                pipeline.close();
            }

            if (descriptors != null) {
                descriptors.close();
            }

            resourcesClosed = true;
        }

        boolean verified =
                failure.isBlank()
                        && pipelineCreated
                        && descriptorsCreated
                        && queueSubmitUsed
                        && queueSubmitResult == VK_SUCCESS
                        && fenceWaitUsed
                        && fenceWaitResult == VK_SUCCESS
                        && queryResult == VK_SUCCESS
                        && rasterizedSamples > 0L
                        && drawVertexCount == rectangleCount * 6
                        && resourcesClosed;

        return new Outcome(
                verified,
                pipelineCreated,
                descriptorsCreated,
                queueSubmitUsed,
                fenceWaitUsed,
                resourcesClosed,
                fallbackQueueWaitIdleUsed,
                queueSubmitResult,
                fenceWaitResult,
                queryResult,
                rasterizedSamples,
                drawVertexCount,
                failure
        );
    }

    record Outcome(
            boolean verified,
            boolean pipelineCreated,
            boolean descriptorsCreated,
            boolean queueSubmitUsed,
            boolean fenceWaitUsed,
            boolean resourcesClosed,
            boolean fallbackQueueWaitIdleUsed,
            int queueSubmitResult,
            int fenceWaitResult,
            int queryResult,
            long rasterizedSamples,
            int drawVertexCount,
            String failure
    ) {
    }
}