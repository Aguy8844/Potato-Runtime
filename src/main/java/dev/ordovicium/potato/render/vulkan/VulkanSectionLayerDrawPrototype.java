package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.SectionLayerDrawContext;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * First real Minecraft section-geometry Vulkan draw.
 *
 * <p>The prototype is deliberately one-shot. It consumes a current real
 * Minecraft BLOCK VkBuffer mirror, synthesizes Minecraft's sequential QUADS
 * index buffer, applies the captured matrices + CHUNK_OFFSET, executes
 * vkCmdDrawIndexed into Potato's hidden offscreen target and verifies
 * rasterized samples with an occlusion query.</p>
 */
final class VulkanSectionLayerDrawPrototype
        implements AutoCloseable {

    private static final long FENCE_TIMEOUT_NANOSECONDS =
            10_000_000_000L;

    private final VkDevice device;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamilyIndex;

    private final VulkanFrameSession frameSession;
    private final JsonObject report;

    private VulkanSectionLayerPipeline pipeline;

    private long commandPool = NULL;
    private VkCommandBuffer commandBuffer;
    private long fence = NULL;
    private long queryPool = NULL;

    private long candidateObservedCount;
    private long eligibleCandidateCount;

    private boolean attempted;
    private boolean succeeded;
    private boolean disabledAfterFailure;

    private long failureCount;
    private String lastFailure =
            "";

    private int submittedTargetGeneration = -1;
    private int submittedVertexCount;
    private int submittedIndexCount;
    private int generatedIndexBytes;

    private long rasterizedSamples;

    private int queueSubmitResult =
            Integer.MIN_VALUE;

    private int fenceWaitResult =
            Integer.MIN_VALUE;

    private int queryResult =
            Integer.MIN_VALUE;

    private String selectedRenderType =
            "";

    private float selectedChunkOffsetX;
    private float selectedChunkOffsetY;
    private float selectedChunkOffsetZ;

    private boolean closed;

    VulkanSectionLayerDrawPrototype(
            VkDevice device,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            VulkanFrameSession frameSession,
            JsonObject report
    ) {
        this.device = device;
        this.graphicsQueue = graphicsQueue;
        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;
        this.frameSession = frameSession;
        this.report = report;
    }

    synchronized void tryDraw(
            VulkanGeometryBufferResource resource,
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
        candidateObservedCount++;

        if (closed
                || succeeded
                || attempted
                || disabledAfterFailure) {
            return;
        }

        if (!eligible(
                resource,
                state,
                context
        )) {
            return;
        }

        eligibleCandidateCount++;
        attempted = true;

        try {
            VulkanGeometryBufferResource.SequentialIndexBinding
                    indexBinding =
                    resource.ensureSequentialQuadIndexBuffer(
                            state
                    );

            VulkanFrameSession.SectionLayerTargetSnapshot
                    targetSnapshot =
                    frameSession.sectionLayerTargetSnapshot();

            VulkanOffscreenTargetPrototype target =
                    targetSnapshot.target();

            ensurePipeline(
                    target
            );

            ensureCommandResources();

            recordAndSubmit(
                    target,
                    targetSnapshot.generation(),
                    resource,
                    state,
                    context,
                    indexBinding
            );

            succeeded =
                    queueSubmitResult == VK_SUCCESS
                            && fenceWaitResult == VK_SUCCESS
                            && queryResult == VK_SUCCESS
                            && rasterizedSamples > 0L;

            if (!succeeded) {
                lastFailure =
                        "Section-layer draw completed without positive rasterization proof."
                                + " submit="
                                + queueSubmitResult
                                + ", fence="
                                + fenceWaitResult
                                + ", query="
                                + queryResult
                                + ", samples="
                                + rasterizedSamples;
            }
        } catch (Throwable throwable) {
            failureCount++;
            disabledAfterFailure = true;

            lastFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );

            /*
             * The OpenGL draw is executed immediately after this callback by
             * LevelRendererSectionLayerDrawMixin, so Vulkan proof failure never
             * escapes into Minecraft rendering.
             */
        }

        enrich();
    }

    synchronized boolean verified() {
        return attempted
                && succeeded
                && !disabledAfterFailure
                && failureCount == 0L
                && queueSubmitResult == VK_SUCCESS
                && fenceWaitResult == VK_SUCCESS
                && queryResult == VK_SUCCESS
                && rasterizedSamples > 0L
                && submittedVertexCount > 0
                && submittedIndexCount > 0;
    }

    synchronized void enrich() {
        report.addProperty(
                "sectionLayerDrawPrototypeInstalled",
                true
        );
        report.addProperty(
                "sectionLayerDrawPrototypeMode",
                "ONE_SHOT_REAL_MINECRAFT_BLOCK_GEOMETRY_DEBUG_DRAW"
        );

        report.addProperty(
                "sectionLayerDrawPrototypeCandidateObservedCount",
                candidateObservedCount
        );
        report.addProperty(
                "sectionLayerDrawPrototypeEligibleCandidateCount",
                eligibleCandidateCount
        );

        report.addProperty(
                "sectionLayerDrawPrototypeAttempted",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeSucceeded",
                succeeded
        );
        report.addProperty(
                "sectionLayerDrawPrototypeDisabledAfterFailure",
                disabledAfterFailure
        );
        report.addProperty(
                "sectionLayerDrawPrototypeFailureCount",
                failureCount
        );

        if (!lastFailure.isBlank()) {
            report.addProperty(
                    "sectionLayerDrawPrototypeLastFailure",
                    lastFailure
            );
        }

        report.addProperty(
                "sectionLayerDrawPrototypeSubmittedTargetGeneration",
                submittedTargetGeneration
        );
        report.addProperty(
                "sectionLayerDrawPrototypeSubmittedVertexCount",
                submittedVertexCount
        );
        report.addProperty(
                "sectionLayerDrawPrototypeSubmittedIndexCount",
                submittedIndexCount
        );
        report.addProperty(
                "sectionLayerDrawPrototypeGeneratedIndexBytes",
                generatedIndexBytes
        );

        report.addProperty(
                "sectionLayerDrawPrototypeSelectedRenderType",
                selectedRenderType
        );
        report.addProperty(
                "sectionLayerDrawPrototypeChunkOffsetX",
                selectedChunkOffsetX
        );
        report.addProperty(
                "sectionLayerDrawPrototypeChunkOffsetY",
                selectedChunkOffsetY
        );
        report.addProperty(
                "sectionLayerDrawPrototypeChunkOffsetZ",
                selectedChunkOffsetZ
        );

        report.addProperty(
                "sectionLayerDrawPrototypeQueueSubmitResult",
                queueSubmitResult
        );
        report.addProperty(
                "sectionLayerDrawPrototypeFenceWaitResult",
                fenceWaitResult
        );
        report.addProperty(
                "sectionLayerDrawPrototypeQueryResult",
                queryResult
        );
        report.addProperty(
                "sectionLayerDrawPrototypeRasterizedSamples",
                rasterizedSamples
        );

        report.addProperty(
                "sectionLayerDrawPrototypeUsesRealMinecraftVertexBuffer",
                submittedVertexCount > 0
        );
        report.addProperty(
                "sectionLayerDrawPrototypeUsesGeneratedSequentialQuadIndices",
                generatedIndexBytes > 0
        );
        report.addProperty(
                "sectionLayerDrawPrototypeUsesVkCmdBindVertexBuffers",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeUsesVkCmdBindIndexBuffer",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeUsesVkCmdDrawIndexed",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeUsesCapturedMatrices",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeUsesCapturedChunkOffset",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeOpenGlClipConvertedToVulkan",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeOcclusionQueryUsed",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeQueryResetBeforeBegin",
                attempted
        );
        report.addProperty(
                "sectionLayerDrawPrototypeQueryResetStrategy",
                "vkCmdResetQueryPool"
        );
        report.addProperty(
                "sectionLayerDrawPrototypeNoQueueIdlePerDraw",
                true
        );
        report.addProperty(
                "sectionLayerDrawPrototypeNoDeviceWaitIdlePerDraw",
                true
        );
        report.addProperty(
                "sectionLayerDrawPrototypeOpenGlBaselineStillExecutes",
                true
        );
        report.addProperty(
                "sectionLayerDrawPrototypeTexturesImplemented",
                false
        );
        report.addProperty(
                "sectionLayerDrawPrototypeLightmapImplemented",
                false
        );
        report.addProperty(
                "sectionLayerDrawPrototypeFogImplemented",
                false
        );

        report.addProperty(
                "drawSubmissionVulkanGpuExecutionEnabled",
                succeeded
        );
        report.addProperty(
                "geometryUploadPrototypeDrawExecutionEnabled",
                succeeded
        );
        report.addProperty(
                "sectionLayerDrawContextVulkanDrawExecutionEnabled",
                succeeded
        );

        report.addProperty(
                "sectionLayerDrawPrototypeVerified",
                verified()
        );
    }

    private boolean eligible(
            VulkanGeometryBufferResource resource,
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
        if (resource == null
                || state == null
                || context == null) {
            return false;
        }

        if (!resource.readyFor(
                state
        )) {
            return false;
        }

        if (!"STATIC".equals(
                state.usageName()
        )) {
            return false;
        }

        if (state.format() == null
                || !DefaultVertexFormat.BLOCK.equals(
                state.format()
        )) {
            return false;
        }

        if (state.vertexStrideBytes() != 32) {
            return false;
        }

        if (state.mode()
                != VertexFormat.Mode.QUADS) {
            return false;
        }

        if (state.indexType()
                != VertexFormat.IndexType.SHORT) {
            return false;
        }

        if (state.explicitIndexBuffer()) {
            return false;
        }

        if (state.vertexCount() <= 0
                || (state.vertexCount() & 3) != 0
                || state.vertexCount() > 0xFFFF) {
            return false;
        }

        if (state.indexCount()
                != (state.vertexCount() / 4) * 6) {
            return false;
        }

        if (!context.hasMatrices()
                || !context.hasShader()
                || !context.hasNonZeroChunkOffset()) {
            return false;
        }

        return context.shader()
                .getVertexFormat()
                .equals(
                        state.format()
                );
    }

    private void ensurePipeline(
            VulkanOffscreenTargetPrototype target
    ) {
        if (pipeline != null
                && pipeline.colorFormat()
                == target.colorFormat()) {
            return;
        }

        if (pipeline != null) {
            pipeline.close();
        }

        pipeline =
                VulkanSectionLayerPipeline.create(
                        device,
                        target.colorFormat(),
                        report
                );
    }

    private void ensureCommandResources() {
        if (commandPool != NULL
                && commandBuffer != null
                && fence != NULL
                && queryPool != NULL) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo =
                    VkCommandPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                            )
                            .queueFamilyIndex(
                                    graphicsQueueFamilyIndex
                            );

            LongBuffer poolPointer =
                    stack.mallocLong(1);

            int result =
                    vkCreateCommandPool(
                            device,
                            poolInfo,
                            null,
                            poolPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_SECTION_LAYER_DRAW_COMMAND_POOL",
                        "vkCreateCommandPool failed with VkResult "
                                + result
                );
            }

            commandPool =
                    poolPointer.get(0);

            VkCommandBufferAllocateInfo allocateInfo =
                    VkCommandBufferAllocateInfo.calloc(stack)
                            .sType$Default()
                            .commandPool(
                                    commandPool
                            )
                            .level(
                                    VK_COMMAND_BUFFER_LEVEL_PRIMARY
                            )
                            .commandBufferCount(1);

            org.lwjgl.PointerBuffer commandPointer =
                    stack.mallocPointer(1);

            result =
                    vkAllocateCommandBuffers(
                            device,
                            allocateInfo,
                            commandPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "ALLOCATE_SECTION_LAYER_DRAW_COMMAND_BUFFER",
                        "vkAllocateCommandBuffers failed with VkResult "
                                + result
                );
            }

            commandBuffer =
                    new VkCommandBuffer(
                            commandPointer.get(0),
                            device
                    );

            VkFenceCreateInfo fenceInfo =
                    VkFenceCreateInfo.calloc(stack)
                            .sType$Default();

            LongBuffer fencePointer =
                    stack.mallocLong(1);

            result =
                    vkCreateFence(
                            device,
                            fenceInfo,
                            null,
                            fencePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_SECTION_LAYER_DRAW_FENCE",
                        "vkCreateFence failed with VkResult "
                                + result
                );
            }

            fence =
                    fencePointer.get(0);

            VkQueryPoolCreateInfo queryInfo =
                    VkQueryPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .queryType(
                                    VK_QUERY_TYPE_OCCLUSION
                            )
                            .queryCount(1);

            LongBuffer queryPointer =
                    stack.mallocLong(1);

            result =
                    vkCreateQueryPool(
                            device,
                            queryInfo,
                            null,
                            queryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "CREATE_SECTION_LAYER_OCCLUSION_QUERY",
                        "vkCreateQueryPool failed with VkResult "
                                + result
                );
            }

            queryPool =
                    queryPointer.get(0);
        }

        report.addProperty(
                "sectionLayerDrawPrototypeCommandPoolCreated",
                commandPool != NULL
        );
        report.addProperty(
                "sectionLayerDrawPrototypeCommandBufferAllocated",
                commandBuffer != null
        );
        report.addProperty(
                "sectionLayerDrawPrototypeFenceCreated",
                fence != NULL
        );
        report.addProperty(
                "sectionLayerDrawPrototypeQueryPoolCreated",
                queryPool != NULL
        );
    }

    private void recordAndSubmit(
            VulkanOffscreenTargetPrototype target,
            int targetGeneration,
            VulkanGeometryBufferResource resource,
            DrawBufferBackendState state,
            SectionLayerDrawContext context,
            VulkanGeometryBufferResource.SequentialIndexBinding indexBinding
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo =
                    VkCommandBufferBeginInfo.calloc(stack)
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            );

            int result =
                    vkBeginCommandBuffer(
                            commandBuffer,
                            beginInfo
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "BEGIN_SECTION_LAYER_DRAW_COMMAND_BUFFER",
                        "vkBeginCommandBuffer failed with VkResult "
                                + result
                );
            }

            /*
             * Vulkan query-pool entries are not implicitly initialized when
             * VkQueryPoolCreateInfo.flags is zero.
             *
             * Patch 037 created the occlusion pool with flags == 0 and began
             * query 0 immediately. Reset it in command-buffer execution order
             * before the first vkCmdBeginQuery so the query is in the required
             * unavailable state.
             */
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
                    VkRenderingInfo.calloc(stack)
                            .sType$Default()
                            .layerCount(1)
                            .pColorAttachments(
                                    colorAttachment
                            );

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

            viewport.get(0)
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

            vkCmdBindVertexBuffers(
                    commandBuffer,
                    0,
                    stack.longs(
                            resource.vertexBuffer()
                    ),
                    stack.longs(
                            0L
                    )
            );

            vkCmdBindIndexBuffer(
                    commandBuffer,
                    indexBinding.buffer(),
                    0L,
                    indexBinding.vkIndexType()
            );

            ByteBuffer push =
                    buildPushConstants(
                            context,
                            stack
                    );

            vkCmdPushConstants(
                    commandBuffer,
                    pipeline.layout(),
                    VK_SHADER_STAGE_VERTEX_BIT,
                    0,
                    push
            );

            vkCmdDrawIndexed(
                    commandBuffer,
                    indexBinding.indexCount(),
                    1,
                    0,
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
                        "END_SECTION_LAYER_DRAW_COMMAND_BUFFER",
                        "vkEndCommandBuffer failed with VkResult "
                                + result
                );
            }

            VkSubmitInfo.Buffer submitInfo =
                    VkSubmitInfo.calloc(
                            1,
                            stack
                    );

            submitInfo.get(0)
                    .sType$Default()
                    .pCommandBuffers(
                            stack.pointers(
                                    commandBuffer.address()
                            )
                    );

            queueSubmitResult =
                    vkQueueSubmit(
                            graphicsQueue,
                            submitInfo,
                            fence
                    );

            if (queueSubmitResult != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "SUBMIT_SECTION_LAYER_DRAW",
                        "vkQueueSubmit failed with VkResult "
                                + queueSubmitResult
                );
            }

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
                        "WAIT_SECTION_LAYER_DRAW_FENCE",
                        "vkWaitForFences returned "
                                + fenceWaitResult
                );
            }

            ByteBuffer queryData =
                    stack.malloc(
                            Long.BYTES
                    ).order(
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
                        "READ_SECTION_LAYER_OCCLUSION_QUERY",
                        "vkGetQueryPoolResults returned "
                                + queryResult
                );
            }

            rasterizedSamples =
                    queryData.getLong(0);

            submittedTargetGeneration =
                    targetGeneration;

            submittedVertexCount =
                    state.vertexCount();

            submittedIndexCount =
                    indexBinding.indexCount();

            generatedIndexBytes =
                    indexBinding.bytes();

            selectedRenderType =
                    String.valueOf(
                            context.renderType()
                    );

            selectedChunkOffsetX =
                    context.chunkOffsetX();

            selectedChunkOffsetY =
                    context.chunkOffsetY();

            selectedChunkOffsetZ =
                    context.chunkOffsetZ();

            report.addProperty(
                    "sectionLayerDrawPrototypeTargetWidth",
                    target.width()
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeTargetHeight",
                    target.height()
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeTargetColorFormat",
                    target.colorFormat()
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeVertexBufferHandleNonZero",
                    resource.vertexBuffer() != NULL
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeIndexBufferHandleNonZero",
                    indexBinding.buffer() != NULL
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeIndexMemoryTypeIndex",
                    indexBinding.memoryTypeIndex()
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeIndexHostCoherent",
                    indexBinding.hostCoherent()
            );
            report.addProperty(
                    "sectionLayerDrawPrototypeIndexBufferReallocated",
                    indexBinding.reallocated()
            );
        }
    }

    private static ByteBuffer buildPushConstants(
            SectionLayerDrawContext context,
            MemoryStack stack
    ) {
        Matrix4f mvp =
                new Matrix4f(
                        context.projection()
                ).mul(
                        context.modelView()
                );

        ByteBuffer bytes =
                stack.malloc(
                        VulkanSectionLayerPipeline.PUSH_CONSTANT_BYTES
                ).order(
                        ByteOrder.nativeOrder()
                );

        FloatBuffer floats =
                bytes.asFloatBuffer();

        mvp.get(
                floats
        );

        floats.put(
                16,
                context.chunkOffsetX()
        );
        floats.put(
                17,
                context.chunkOffsetY()
        );
        floats.put(
                18,
                context.chunkOffsetZ()
        );
        floats.put(
                19,
                1.0f
        );

        return bytes;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        if (queryPool != NULL) {
            vkDestroyQueryPool(
                    device,
                    queryPool,
                    null
            );

            queryPool = NULL;
        }

        if (fence != NULL) {
            vkDestroyFence(
                    device,
                    fence,
                    null
            );

            fence = NULL;
        }

        if (commandPool != NULL) {
            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            commandPool = NULL;
            commandBuffer = null;
        }

        if (pipeline != null) {
            pipeline.close();
            pipeline = null;
        }

        report.addProperty(
                "sectionLayerDrawPrototypeClosed",
                true
        );

        enrich();
    }
}
