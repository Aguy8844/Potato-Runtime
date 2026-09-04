package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_fragment_shader;
import static org.lwjgl.util.shaderc.Shaderc.shaderc_vertex_shader;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Non-blocking real-geometry Vulkan indirect proof driven by resident terrain
 * in {@link VulkanDeviceLocalRegionArena}.
 *
 * <p>Patch 094 retires the old private procedural canary as the cutover proof.
 * The candidate list still comes from real visible SOLID sections, but now the
 * graphics queue also binds the actual DEVICE_LOCAL arena as the vertex source
 * and executes indexed indirect commands over the real Minecraft BLOCK vertex
 * stream. The proof renders into a tiny private validation target, never into
 * the player's visible frame.</p>
 *
 * <p>This remains deliberately fail-open. Gameplay never waits for the proof
 * fence. If BLOCK layout assumptions, arena offsets, Vulkan commands, or query
 * completion fail, the proof disables itself and the existing OpenGL / proven
 * visible SOLID path remains authoritative.</p>
 */
final class VulkanRegionIndirectDrawRuntime implements AutoCloseable {
    private static final int TARGET_WIDTH = 128;
    private static final int TARGET_HEIGHT = 128;
    private static final int COLOR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;

    /*
     * DefaultVertexFormat.BLOCK is 32 bytes on the 1.21.1 production path:
     * position(12), color(4), uv0(8), uv2/light(4), normal+padding(4).
     * The proof validates usedBytes == vertexCount * stride before submission.
     */
    private static final int BLOCK_VERTEX_STRIDE_BYTES = 32;

    private static final int DRAW_INDEXED_INDIRECT_COMMAND_BYTES =
            5 * Integer.BYTES;

    /*
     * Hidden proof work is intentionally bounded. We sample at most 256 real
     * resident sections per proof and need only eight clean completions before
     * the result becomes sticky for the visible cutover policy.
     */
    private static final int MAX_REAL_PROOF_DRAWS = 256;
    private static final long MIN_POSITIVE_PROOFS_FOR_CUTOVER = 8L;

    /*
     * One shared immutable uint32 quad index atlas. 262,144 quads corresponds
     * to one 32 MiB BLOCK vertex payload at 32 bytes/vertex, matching the upper
     * staging-segment scale and keeping the proof conservative for modded
     * sections without creating per-section index allocations.
     */
    private static final int MAX_INDEX_QUADS = 262_144;
    private static final int MAX_INDEX_COUNT = MAX_INDEX_QUADS * 6;

    private static final String VERTEX_SHADER =
            "assets/potato_runtime/shaders/vulkan/region_arena_precommit.vert";
    private static final String FRAGMENT_SHADER =
            "assets/potato_runtime/shaders/vulkan/region_arena_precommit.frag";

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final int graphicsQueueFamilyIndex;
    private final long arenaVertexBuffer;
    private final JsonObject report;

    private VkQueue graphicsQueue;

    private long indirectBuffer = NULL;
    private long indirectMemory = NULL;
    private long indirectMappedAddress = NULL;
    private ByteBuffer indirectMapped;
    private int indirectMemoryTypeIndex = -1;

    private long quadIndexBuffer = NULL;
    private long quadIndexMemory = NULL;
    private int quadIndexMemoryTypeIndex = -1;

    private long targetImage = NULL;
    private long targetMemory = NULL;
    private long targetImageView = NULL;
    private int targetMemoryTypeIndex = -1;
    private boolean targetLayoutInitialized;

    private long pipelineLayout = NULL;
    private long pipeline = NULL;

    private long commandPool = NULL;
    private VkCommandBuffer commandBuffer;
    private long fence = NULL;
    private long queryPool = NULL;

    private long offerCount;
    private long retiredOfferCount;
    private long preparedCandidateCount;
    private long submittedCandidateCount;
    private long truncatedCandidateCount;
    private long layoutRejectCount;
    private long candidateCountMismatchCount;
    private long submissionCount;
    private long completedSubmissionCount;
    private long positiveRasterProofCount;
    private long rasterizedSamples;
    private long fencePollCount;
    private long fenceNotReadyCount;
    private long busySkipCount;
    private long frameTokenRejectCount;
    private long queueSubmitFailureCount;
    private long queryFailureCount;
    private long failureCount;
    private int peakProofDrawCount;
    private int lastProofDrawCount;
    private long lastOfferedFrameSequence;
    private long lastOfferedCameraFingerprint;
    private String lastFailure = "";

    private boolean initialized;
    private boolean inFlight;
    private boolean executionObserved;
    private boolean disabledAfterFailure;
    private boolean shutdownQueueWaitIdleUsed;
    private int shutdownQueueWaitIdleResult = Integer.MIN_VALUE;
    private boolean resourcesDestroyed;
    private boolean closed;

    VulkanRegionIndirectDrawRuntime(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            int graphicsQueueFamilyIndex,
            long arenaVertexBuffer,
            JsonObject report
    ) {
        this.device = device;
        this.physicalDevice = physicalDevice;
        this.graphicsQueueFamilyIndex = graphicsQueueFamilyIndex;
        this.arenaVertexBuffer = arenaVertexBuffer;
        this.report = report;

        try {
            if (device == null
                    || physicalDevice == null
                    || graphicsQueueFamilyIndex < 0
                    || arenaVertexBuffer == NULL) {
                throw new IllegalStateException(
                        "Real arena indirect Vulkan inputs are incomplete."
                );
            }

            retrieveGraphicsQueue();
            createIndirectBuffer();
            createQuadIndexBuffer();
            createValidationTarget();
            createPipeline();
            createCommandResources();
            initialized = true;
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
            destroyResources(false);
        }

        enrich(report);
    }

    synchronized void trySubmit(
            int residentCandidateCount,
            long frameSequence,
            long cameraFingerprint
    ) {
        offerCount++;
        lastOfferedFrameSequence = frameSequence;
        lastOfferedCameraFingerprint = cameraFingerprint;

        if (!initialized
                || disabledAfterFailure
                || closed
                || residentCandidateCount <= 0) {
            return;
        }

        harvestCompletion();

        if (positiveRasterProofCount
                >= MIN_POSITIVE_PROOFS_FOR_CUTOVER) {
            retiredOfferCount++;
            enrich(report);
            return;
        }

        if (inFlight) {
            busySkipCount++;
            return;
        }

        if (frameSequence <= 0L
                || cameraFingerprint == 0L
                || frameSequence
                != VulkanExactFramePublicationToken.currentFrameSequence()
                || cameraFingerprint
                != VulkanExactFramePublicationToken.currentCameraFingerprint()) {
            frameTokenRejectCount++;
            return;
        }

        int sampledCandidateCount =
                VulkanSurfaceClusterVisibility.sampledCandidateCount();

        if (sampledCandidateCount != residentCandidateCount) {
            candidateCountMismatchCount++;
            return;
        }

        preparedCandidateCount += residentCandidateCount;

        try {
            int proofDrawCount =
                    writeRealArenaIndirectCommands(
                            residentCandidateCount,
                            frameSequence
                    );

            if (proofDrawCount <= 0) {
                return;
            }

            submittedCandidateCount += proofDrawCount;
            lastProofDrawCount = proofDrawCount;
            peakProofDrawCount =
                    Math.max(
                            peakProofDrawCount,
                            proofDrawCount
                    );

            recordAndSubmit(proofDrawCount);
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
        }

        enrich(report);
    }

    synchronized boolean executionObserved() {
        harvestCompletion();
        return executionObserved;
    }

    synchronized boolean verified() {
        harvestCompletion();
        return verifiedWithoutHarvest();
    }

    synchronized void enrich(JsonObject output) {
        if (!closed) {
            harvestCompletion();
        }

        output.addProperty(
                "vulkanRegionIndirectInstalled",
                true
        );
        output.addProperty(
                "vulkanRegionIndirectMode",
                "REAL_DEVICE_LOCAL_ARENA_GEOMETRY_VKCMD_DRAW_INDEXED_INDIRECT_PRECOMMIT"
        );
        output.addProperty(
                "vulkanRegionIndirectInitialized",
                initialized
        );
        output.addProperty(
                "vulkanRegionIndirectTargetWidth",
                TARGET_WIDTH
        );
        output.addProperty(
                "vulkanRegionIndirectTargetHeight",
                TARGET_HEIGHT
        );
        output.addProperty(
                "vulkanRegionIndirectTargetColorFormat",
                COLOR_FORMAT
        );
        output.addProperty(
                "vulkanRegionIndirectBlockVertexStrideBytes",
                BLOCK_VERTEX_STRIDE_BYTES
        );
        output.addProperty(
                "vulkanRegionIndirectMaximumRealProofDraws",
                MAX_REAL_PROOF_DRAWS
        );
        output.addProperty(
                "vulkanRegionIndirectMaximumIndexQuads",
                MAX_INDEX_QUADS
        );
        output.addProperty(
                "vulkanRegionIndirectBufferCreated",
                indirectBuffer != NULL
        );
        output.addProperty(
                "vulkanRegionIndirectBufferPersistentlyMapped",
                indirectMappedAddress != NULL
        );
        output.addProperty(
                "vulkanRegionIndirectMemoryTypeIndex",
                indirectMemoryTypeIndex
        );
        output.addProperty(
                "vulkanRegionIndirectQuadIndexBufferCreated",
                quadIndexBuffer != NULL
        );
        output.addProperty(
                "vulkanRegionIndirectQuadIndexMemoryTypeIndex",
                quadIndexMemoryTypeIndex
        );
        output.addProperty(
                "vulkanRegionIndirectTargetMemoryTypeIndex",
                targetMemoryTypeIndex
        );
        output.addProperty(
                "vulkanRegionIndirectPipelineCreated",
                pipeline != NULL
                        && pipelineLayout != NULL
        );
        output.addProperty(
                "vulkanRegionIndirectCommandPoolCreated",
                commandPool != NULL
        );
        output.addProperty(
                "vulkanRegionIndirectFenceCreated",
                fence != NULL
        );
        output.addProperty(
                "vulkanRegionIndirectQueryPoolCreated",
                queryPool != NULL
        );
        output.addProperty(
                "vulkanRegionIndirectOfferCount",
                offerCount
        );
        output.addProperty(
                "vulkanRegionIndirectProofWarmupTarget",
                MIN_POSITIVE_PROOFS_FOR_CUTOVER
        );
        output.addProperty(
                "vulkanRegionIndirectRetiredOfferCount",
                retiredOfferCount
        );
        output.addProperty(
                "vulkanRegionIndirectProofRetiredAfterCutoverWarmup",
                positiveRasterProofCount
                        >= MIN_POSITIVE_PROOFS_FOR_CUTOVER
        );
        output.addProperty(
                "vulkanRegionIndirectPreparedCandidateCount",
                preparedCandidateCount
        );
        output.addProperty(
                "vulkanRegionIndirectSubmittedCandidateCount",
                submittedCandidateCount
        );
        output.addProperty(
                "vulkanRegionIndirectTruncatedCandidateCount",
                truncatedCandidateCount
        );
        output.addProperty(
                "vulkanRegionIndirectLayoutRejectCount",
                layoutRejectCount
        );
        output.addProperty(
                "vulkanRegionIndirectCandidateCountMismatchCount",
                candidateCountMismatchCount
        );
        output.addProperty(
                "vulkanRegionIndirectSubmissionCount",
                submissionCount
        );
        output.addProperty(
                "vulkanRegionIndirectCompletedSubmissionCount",
                completedSubmissionCount
        );
        output.addProperty(
                "vulkanRegionIndirectPositiveRasterProofCount",
                positiveRasterProofCount
        );
        output.addProperty(
                "vulkanRegionIndirectRasterizedSamples",
                rasterizedSamples
        );
        output.addProperty(
                "vulkanRegionIndirectFencePollCount",
                fencePollCount
        );
        output.addProperty(
                "vulkanRegionIndirectFenceNotReadyCount",
                fenceNotReadyCount
        );
        output.addProperty(
                "vulkanRegionIndirectBusySkipCount",
                busySkipCount
        );
        output.addProperty(
                "vulkanRegionIndirectFrameTokenRejectCount",
                frameTokenRejectCount
        );
        output.addProperty(
                "vulkanRegionIndirectQueueSubmitFailureCount",
                queueSubmitFailureCount
        );
        output.addProperty(
                "vulkanRegionIndirectQueryFailureCount",
                queryFailureCount
        );
        output.addProperty(
                "vulkanRegionIndirectFailureCount",
                failureCount
        );
        output.addProperty(
                "vulkanRegionIndirectPeakProofDrawCount",
                peakProofDrawCount
        );
        output.addProperty(
                "vulkanRegionIndirectLastProofDrawCount",
                lastProofDrawCount
        );
        output.addProperty(
                "vulkanRegionIndirectLastOfferedFrameSequence",
                lastOfferedFrameSequence
        );
        output.addProperty(
                "vulkanRegionIndirectLastOfferedCameraFingerprint",
                Long.toUnsignedString(lastOfferedCameraFingerprint)
        );
        output.addProperty(
                "vulkanRegionIndirectLastFailure",
                lastFailure
        );
        output.addProperty(
                "vulkanRegionIndirectVkCmdDrawIndirectUsed",
                false
        );
        output.addProperty(
                "vulkanRegionIndirectVkCmdDrawIndexedIndirectUsed",
                executionObserved
        );
        output.addProperty(
                "vulkanRegionIndirectCandidateSource",
                "REAL_VISIBLE_SOLID_RESIDENT_SECTION_LIST"
        );
        output.addProperty(
                "vulkanRegionIndirectRasterSource",
                "REAL_DEVICE_LOCAL_REGION_ARENA_BLOCK_VERTEX_STREAM"
        );
        output.addProperty(
                "vulkanRegionIndirectBindsActualArenaVertexBuffer",
                arenaVertexBuffer != NULL
                        && executionObserved
        );
        output.addProperty(
                "vulkanRegionIndirectUsesPrivateProceduralCanary",
                false
        );
        output.addProperty(
                "vulkanRegionIndirectOpenGlVisibleAuthority",
                true
        );
        output.addProperty(
                "vulkanRegionIndirectVisibleOwnership",
                false
        );
        output.addProperty(
                "vulkanRegionIndirectNoGameplayGpuWait",
                true
        );
        output.addProperty(
                "vulkanRegionIndirectShutdownQueueWaitIdleUsed",
                shutdownQueueWaitIdleUsed
        );
        output.addProperty(
                "vulkanRegionIndirectShutdownQueueWaitIdleResult",
                shutdownQueueWaitIdleResult
        );
        output.addProperty(
                "vulkanRegionIndirectDisabledAfterFailure",
                disabledAfterFailure
        );
        output.addProperty(
                "vulkanRegionIndirectVerified",
                verifiedWithoutHarvest()
        );
        output.addProperty(
                "vulkanRegionIndirectRealArenaGeometryVerified",
                verifiedWithoutHarvest()
        );
        output.addProperty(
                "vulkanRegionIndirectResourcesDestroyed",
                resourcesDestroyed
        );
        output.addProperty(
                "vulkanRegionIndirectClosed",
                closed
        );
        output.addProperty(
                "vulkanRegionIndirectNextMilestone",
                "POTATO_ENGINE_VISIBLE_REGION_INDIRECT_SOLID"
        );
    }

    private boolean verifiedWithoutHarvest() {
        return initialized
                && executionObserved
                && positiveRasterProofCount
                >= MIN_POSITIVE_PROOFS_FOR_CUTOVER
                && submittedCandidateCount > 0L
                && layoutRejectCount == 0L
                && candidateCountMismatchCount == 0L
                && failureCount == 0L
                && frameTokenRejectCount == 0L
                && queueSubmitFailureCount == 0L
                && queryFailureCount == 0L
                && !disabledAfterFailure;
    }

    private void retrieveGraphicsQueue() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);

            vkGetDeviceQueue(
                    device,
                    graphicsQueueFamilyIndex,
                    0,
                    pointer
            );

            long handle = pointer.get(0);
            if (handle == NULL) {
                throw new IllegalStateException(
                        "vkGetDeviceQueue returned NULL for real arena indirect graphics queue."
                );
            }

            graphicsQueue = new VkQueue(handle, device);
        }
    }

    private void createIndirectBuffer() {
        long bufferBytes =
                (long) MAX_REAL_PROOF_DRAWS
                        * DRAW_INDEXED_INDIRECT_COMMAND_BYTES;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo =
                    VkBufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .size(bufferBytes)
                            .usage(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer bufferPointer = stack.mallocLong(1);
            int result = vkCreateBuffer(
                    device,
                    bufferInfo,
                    null,
                    bufferPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateBuffer(real arena indirect) failed with VkResult " + result
                );
            }

            indirectBuffer = bufferPointer.get(0);

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(
                    device,
                    indirectBuffer,
                    requirements
            );

            indirectMemoryTypeIndex =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                    | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    );

            VkMemoryAllocateInfo allocation =
                    VkMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .allocationSize(requirements.size())
                            .memoryTypeIndex(indirectMemoryTypeIndex);

            LongBuffer memoryPointer = stack.mallocLong(1);
            result = vkAllocateMemory(
                    device,
                    allocation,
                    null,
                    memoryPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkAllocateMemory(real arena indirect) failed with VkResult " + result
                );
            }

            indirectMemory = memoryPointer.get(0);

            result = vkBindBufferMemory(
                    device,
                    indirectBuffer,
                    indirectMemory,
                    0L
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkBindBufferMemory(real arena indirect) failed with VkResult " + result
                );
            }

            PointerBuffer mapped = stack.mallocPointer(1);
            result = vkMapMemory(
                    device,
                    indirectMemory,
                    0L,
                    bufferBytes,
                    0,
                    mapped
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkMapMemory(real arena indirect) failed with VkResult " + result
                );
            }

            indirectMappedAddress = mapped.get(0);
            if (indirectMappedAddress == NULL) {
                throw new IllegalStateException(
                        "vkMapMemory(real arena indirect) returned NULL."
                );
            }

            indirectMapped =
                    MemoryUtil.memByteBuffer(
                            indirectMappedAddress,
                            Math.toIntExact(bufferBytes)
                    ).order(ByteOrder.nativeOrder());
        }
    }

    private void createQuadIndexBuffer() {
        long indexBytes =
                (long) MAX_INDEX_COUNT
                        * Integer.BYTES;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo =
                    VkBufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .size(indexBytes)
                            .usage(VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer bufferPointer = stack.mallocLong(1);
            int result = vkCreateBuffer(
                    device,
                    bufferInfo,
                    null,
                    bufferPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateBuffer(real arena quad index) failed with VkResult " + result
                );
            }

            quadIndexBuffer = bufferPointer.get(0);

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(
                    device,
                    quadIndexBuffer,
                    requirements
            );

            quadIndexMemoryTypeIndex =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                    | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
                    );

            VkMemoryAllocateInfo allocation =
                    VkMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .allocationSize(requirements.size())
                            .memoryTypeIndex(quadIndexMemoryTypeIndex);

            LongBuffer memoryPointer = stack.mallocLong(1);
            result = vkAllocateMemory(
                    device,
                    allocation,
                    null,
                    memoryPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkAllocateMemory(real arena quad index) failed with VkResult " + result
                );
            }

            quadIndexMemory = memoryPointer.get(0);

            result = vkBindBufferMemory(
                    device,
                    quadIndexBuffer,
                    quadIndexMemory,
                    0L
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkBindBufferMemory(real arena quad index) failed with VkResult " + result
                );
            }

            PointerBuffer mapped = stack.mallocPointer(1);
            result = vkMapMemory(
                    device,
                    quadIndexMemory,
                    0L,
                    indexBytes,
                    0,
                    mapped
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkMapMemory(real arena quad index) failed with VkResult " + result
                );
            }

            long address = mapped.get(0);
            if (address == NULL) {
                throw new IllegalStateException(
                        "vkMapMemory(real arena quad index) returned NULL."
                );
            }

            IntBuffer indices =
                    MemoryUtil.memIntBuffer(
                            address,
                            MAX_INDEX_COUNT
                    );

            for (int quad = 0;
                 quad < MAX_INDEX_QUADS;
                 quad++) {
                int vertexBase =
                        quad * 4;
                int indexBase =
                        quad * 6;

                indices.put(indexBase, vertexBase);
                indices.put(indexBase + 1, vertexBase + 1);
                indices.put(indexBase + 2, vertexBase + 2);
                indices.put(indexBase + 3, vertexBase + 2);
                indices.put(indexBase + 4, vertexBase + 3);
                indices.put(indexBase + 5, vertexBase);
            }

            vkUnmapMemory(
                    device,
                    quadIndexMemory
            );
        }
    }

    private void createValidationTarget() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkFormatProperties formatProperties =
                    VkFormatProperties.malloc(stack);
            vkGetPhysicalDeviceFormatProperties(
                    physicalDevice,
                    COLOR_FORMAT,
                    formatProperties
            );

            if ((formatProperties.optimalTilingFeatures()
                    & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT) == 0) {
                throw new IllegalStateException(
                        "VK_FORMAT_R8G8B8A8_UNORM lacks optimal color-attachment support."
                );
            }

            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(stack)
                            .sType$Default()
                            .imageType(VK_IMAGE_TYPE_2D)
                            .format(COLOR_FORMAT)
                            .mipLevels(1)
                            .arrayLayers(1)
                            .samples(VK_SAMPLE_COUNT_1_BIT)
                            .tiling(VK_IMAGE_TILING_OPTIMAL)
                            .usage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            imageInfo.extent()
                    .width(TARGET_WIDTH)
                    .height(TARGET_HEIGHT)
                    .depth(1);

            LongBuffer imagePointer = stack.mallocLong(1);
            int result = vkCreateImage(
                    device,
                    imageInfo,
                    null,
                    imagePointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateImage(real arena indirect target) failed with VkResult " + result
                );
            }

            targetImage = imagePointer.get(0);

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(
                    device,
                    targetImage,
                    requirements
            );

            targetMemoryTypeIndex =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    );

            VkMemoryAllocateInfo allocation =
                    VkMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .allocationSize(requirements.size())
                            .memoryTypeIndex(targetMemoryTypeIndex);

            LongBuffer memoryPointer = stack.mallocLong(1);
            result = vkAllocateMemory(
                    device,
                    allocation,
                    null,
                    memoryPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkAllocateMemory(real arena indirect target) failed with VkResult " + result
                );
            }

            targetMemory = memoryPointer.get(0);

            result = vkBindImageMemory(
                    device,
                    targetImage,
                    targetMemory,
                    0L
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkBindImageMemory(real arena indirect target) failed with VkResult " + result
                );
            }

            VkImageViewCreateInfo viewInfo =
                    VkImageViewCreateInfo.calloc(stack)
                            .sType$Default()
                            .image(targetImage)
                            .viewType(VK_IMAGE_VIEW_TYPE_2D)
                            .format(COLOR_FORMAT);

            viewInfo.subresourceRange()
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer viewPointer = stack.mallocLong(1);
            result = vkCreateImageView(
                    device,
                    viewInfo,
                    null,
                    viewPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateImageView(real arena indirect target) failed with VkResult " + result
                );
            }

            targetImageView = viewPointer.get(0);
        }
    }

    private void createPipeline() {
        try (MemoryStack stack = MemoryStack.stackPush();
             VulkanShaderCompiler.SpirvBinary vertex =
                     VulkanShaderCompiler.compileResource(
                             VERTEX_SHADER,
                             shaderc_vertex_shader,
                             report
                     );
             VulkanShaderCompiler.SpirvBinary fragment =
                     VulkanShaderCompiler.compileResource(
                             FRAGMENT_SHADER,
                             shaderc_fragment_shader,
                             report
                     )) {

            long vertexModule = createShaderModule(
                    vertex.bytes(),
                    stack,
                    "REAL_ARENA_INDIRECT_VERTEX"
            );
            long fragmentModule = NULL;

            try {
                fragmentModule = createShaderModule(
                        fragment.bytes(),
                        stack,
                        "REAL_ARENA_INDIRECT_FRAGMENT"
                );

                VkPipelineLayoutCreateInfo layoutInfo =
                        VkPipelineLayoutCreateInfo.calloc(stack)
                                .sType$Default();

                LongBuffer layoutPointer = stack.mallocLong(1);
                int result = vkCreatePipelineLayout(
                        device,
                        layoutInfo,
                        null,
                        layoutPointer
                );

                if (result != VK_SUCCESS) {
                    throw new IllegalStateException(
                            "vkCreatePipelineLayout(real arena indirect) failed with VkResult " + result
                    );
                }

                pipelineLayout = layoutPointer.get(0);

                VkPipelineShaderStageCreateInfo.Buffer stages =
                        VkPipelineShaderStageCreateInfo.calloc(2, stack);

                stages.get(0)
                        .sType$Default()
                        .stage(VK_SHADER_STAGE_VERTEX_BIT)
                        .module(vertexModule)
                        .pName(stack.UTF8("main"));

                stages.get(1)
                        .sType$Default()
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                        .module(fragmentModule)
                        .pName(stack.UTF8("main"));

                VkVertexInputBindingDescription.Buffer bindings =
                        VkVertexInputBindingDescription.calloc(1, stack);

                bindings.get(0)
                        .binding(0)
                        .stride(BLOCK_VERTEX_STRIDE_BYTES)
                        .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attributes =
                        VkVertexInputAttributeDescription.calloc(1, stack);

                attributes.get(0)
                        .location(0)
                        .binding(0)
                        .format(VK_FORMAT_R32G32B32_SFLOAT)
                        .offset(0);

                VkPipelineVertexInputStateCreateInfo vertexInput =
                        VkPipelineVertexInputStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .pVertexBindingDescriptions(bindings)
                                .pVertexAttributeDescriptions(attributes);

                VkPipelineInputAssemblyStateCreateInfo inputAssembly =
                        VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST)
                                .primitiveRestartEnable(false);

                VkPipelineViewportStateCreateInfo viewportState =
                        VkPipelineViewportStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .viewportCount(1)
                                .scissorCount(1);

                VkPipelineRasterizationStateCreateInfo rasterization =
                        VkPipelineRasterizationStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .depthClampEnable(false)
                                .rasterizerDiscardEnable(false)
                                .polygonMode(VK_POLYGON_MODE_FILL)
                                .cullMode(VK_CULL_MODE_NONE)
                                .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                                .depthBiasEnable(false)
                                .lineWidth(1.0f);

                VkPipelineMultisampleStateCreateInfo multisample =
                        VkPipelineMultisampleStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT)
                                .sampleShadingEnable(false);

                VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                        VkPipelineColorBlendAttachmentState.calloc(1, stack);

                blendAttachment.get(0)
                        .blendEnable(false)
                        .colorWriteMask(
                                VK_COLOR_COMPONENT_R_BIT
                                        | VK_COLOR_COMPONENT_G_BIT
                                        | VK_COLOR_COMPONENT_B_BIT
                                        | VK_COLOR_COMPONENT_A_BIT
                        );

                VkPipelineColorBlendStateCreateInfo blendState =
                        VkPipelineColorBlendStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .logicOpEnable(false)
                                .pAttachments(blendAttachment);

                IntBuffer dynamicStates = stack.ints(
                        VK_DYNAMIC_STATE_VIEWPORT,
                        VK_DYNAMIC_STATE_SCISSOR
                );

                VkPipelineDynamicStateCreateInfo dynamicState =
                        VkPipelineDynamicStateCreateInfo.calloc(stack)
                                .sType$Default()
                                .pDynamicStates(dynamicStates);

                VkPipelineRenderingCreateInfo rendering =
                        VkPipelineRenderingCreateInfo.calloc(stack)
                                .sType$Default()
                                .colorAttachmentCount(1)
                                .pColorAttachmentFormats(
                                        stack.ints(COLOR_FORMAT)
                                );

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo =
                        VkGraphicsPipelineCreateInfo.calloc(1, stack);

                pipelineInfo.get(0)
                        .sType$Default()
                        .pNext(rendering.address())
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(inputAssembly)
                        .pViewportState(viewportState)
                        .pRasterizationState(rasterization)
                        .pMultisampleState(multisample)
                        .pColorBlendState(blendState)
                        .pDynamicState(dynamicState)
                        .layout(pipelineLayout)
                        .renderPass(NULL)
                        .subpass(0);

                LongBuffer pipelinePointer = stack.mallocLong(1);
                result = vkCreateGraphicsPipelines(
                        device,
                        NULL,
                        pipelineInfo,
                        null,
                        pipelinePointer
                );

                if (result != VK_SUCCESS) {
                    throw new IllegalStateException(
                            "vkCreateGraphicsPipelines(real arena indirect) failed with VkResult " + result
                    );
                }

                pipeline = pipelinePointer.get(0);
            } finally {
                if (fragmentModule != NULL) {
                    vkDestroyShaderModule(
                            device,
                            fragmentModule,
                            null
                    );
                }

                vkDestroyShaderModule(
                        device,
                        vertexModule,
                        null
                );
            }
        }
    }

    private long createShaderModule(
            ByteBuffer spirv,
            MemoryStack stack,
            String label
    ) {
        VkShaderModuleCreateInfo info =
                VkShaderModuleCreateInfo.calloc(stack)
                        .sType$Default()
                        .pCode(spirv);

        LongBuffer pointer = stack.mallocLong(1);
        int result = vkCreateShaderModule(
                device,
                info,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkCreateShaderModule(" + label + ") failed with VkResult " + result
            );
        }

        long module = pointer.get(0);
        if (module == NULL) {
            throw new IllegalStateException(
                    "vkCreateShaderModule(" + label + ") returned NULL."
            );
        }

        return module;
    }

    private void createCommandResources() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo =
                    VkCommandPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                            .queueFamilyIndex(graphicsQueueFamilyIndex);

            LongBuffer poolPointer = stack.mallocLong(1);
            int result = vkCreateCommandPool(
                    device,
                    poolInfo,
                    null,
                    poolPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateCommandPool(real arena indirect) failed with VkResult " + result
                );
            }

            commandPool = poolPointer.get(0);

            VkCommandBufferAllocateInfo allocateInfo =
                    VkCommandBufferAllocateInfo.calloc(stack)
                            .sType$Default()
                            .commandPool(commandPool)
                            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                            .commandBufferCount(1);

            PointerBuffer commandPointer = stack.mallocPointer(1);
            result = vkAllocateCommandBuffers(
                    device,
                    allocateInfo,
                    commandPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkAllocateCommandBuffers(real arena indirect) failed with VkResult " + result
                );
            }

            commandBuffer = new VkCommandBuffer(
                    commandPointer.get(0),
                    device
            );

            VkFenceCreateInfo fenceInfo =
                    VkFenceCreateInfo.calloc(stack)
                            .sType$Default();

            LongBuffer fencePointer = stack.mallocLong(1);
            result = vkCreateFence(
                    device,
                    fenceInfo,
                    null,
                    fencePointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateFence(real arena indirect) failed with VkResult " + result
                );
            }

            fence = fencePointer.get(0);

            VkQueryPoolCreateInfo queryInfo =
                    VkQueryPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .queryType(VK_QUERY_TYPE_OCCLUSION)
                            .queryCount(1);

            LongBuffer queryPointer = stack.mallocLong(1);
            result = vkCreateQueryPool(
                    device,
                    queryInfo,
                    null,
                    queryPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateQueryPool(real arena indirect) failed with VkResult " + result
                );
            }

            queryPool = queryPointer.get(0);
        }
    }

    private int writeRealArenaIndirectCommands(
            int residentCandidateCount,
            long frameSequence
    ) {
        int sampledCandidateCount =
                VulkanSurfaceClusterVisibility.sampledCandidateCount();

        int available =
                Math.min(
                        residentCandidateCount,
                        sampledCandidateCount
                );

        if (available <= 0) {
            return 0;
        }

        int start =
                Math.floorMod(
                        Long.hashCode(frameSequence),
                        available
                );

        int accepted = 0;
        int scanned = 0;

        while (scanned < available
                && accepted < MAX_REAL_PROOF_DRAWS) {
            int candidateIndex =
                    (start + scanned) % available;

            scanned++;

            long arenaOffset =
                    VulkanSurfaceClusterVisibility
                            .candidateArenaOffset(
                                    candidateIndex
                            );

            int vertexCount =
                    VulkanSurfaceClusterVisibility
                            .candidateVertexCount(
                                    candidateIndex
                            );

            int usedBytes =
                    VulkanSurfaceClusterVisibility
                            .candidateUsedBytes(
                                    candidateIndex
                            );

            long sourceGeneration =
                    VulkanSurfaceClusterVisibility
                            .candidateSourceGeneration(
                                    candidateIndex
                            );

            long expectedBytes =
                    (long) vertexCount
                            * BLOCK_VERTEX_STRIDE_BYTES;

            if (arenaOffset < 0L
                    || (arenaOffset % BLOCK_VERTEX_STRIDE_BYTES) != 0L
                    || vertexCount <= 0
                    || (vertexCount & 3) != 0
                    || usedBytes <= 0
                    || expectedBytes != usedBytes
                    || sourceGeneration <= 0L) {
                layoutRejectCount++;
                continue;
            }

            int quadCount =
                    vertexCount / 4;

            if (quadCount <= 0
                    || quadCount > MAX_INDEX_QUADS) {
                layoutRejectCount++;
                continue;
            }

            long firstVertexLong =
                    arenaOffset
                            / BLOCK_VERTEX_STRIDE_BYTES;

            if (firstVertexLong < 0L
                    || firstVertexLong > Integer.MAX_VALUE) {
                layoutRejectCount++;
                continue;
            }

            int indexCount =
                    Math.multiplyExact(
                            quadCount,
                            6
                    );

            int byteBase =
                    accepted
                            * DRAW_INDEXED_INDIRECT_COMMAND_BYTES;

            indirectMapped.putInt(
                    byteBase,
                    indexCount
            );

            indirectMapped.putInt(
                    byteBase + 4,
                    1
            );

            indirectMapped.putInt(
                    byteBase + 8,
                    0
            );

            indirectMapped.putInt(
                    byteBase + 12,
                    (int) firstVertexLong
            );

            indirectMapped.putInt(
                    byteBase + 16,
                    0
            );

            accepted++;
        }

        if (available > accepted) {
            truncatedCandidateCount +=
                    available - accepted;
        }

        return accepted;
    }

    private void recordAndSubmit(int drawCount) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int result = vkResetFences(
                    device,
                    stack.longs(fence)
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkResetFences(real arena indirect) failed with VkResult " + result
                );
            }

            result = vkResetCommandBuffer(
                    commandBuffer,
                    0
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkResetCommandBuffer(real arena indirect) failed with VkResult " + result
                );
            }

            VkCommandBufferBeginInfo beginInfo =
                    VkCommandBufferBeginInfo.calloc(stack)
                            .sType$Default()
                            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            result = vkBeginCommandBuffer(
                    commandBuffer,
                    beginInfo
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkBeginCommandBuffer(real arena indirect) failed with VkResult " + result
                );
            }

            if (!targetLayoutInitialized) {
                transitionTargetToColorAttachment(stack);
                targetLayoutInitialized = true;
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
                    VkRenderingAttachmentInfo.calloc(1, stack);

            colorAttachment.get(0)
                    .sType$Default()
                    .imageView(targetImageView)
                    .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);

            colorAttachment.get(0)
                    .clearValue()
                    .color()
                    .float32(0, 0.01f)
                    .float32(1, 0.01f)
                    .float32(2, 0.015f)
                    .float32(3, 1.0f);

            VkRenderingInfo renderingInfo =
                    VkRenderingInfo.calloc(stack)
                            .sType$Default()
                            .layerCount(1)
                            .pColorAttachments(colorAttachment);

            renderingInfo.renderArea()
                    .offset()
                    .x(0)
                    .y(0);

            renderingInfo.renderArea()
                    .extent()
                    .width(TARGET_WIDTH)
                    .height(TARGET_HEIGHT);

            vkCmdBeginRendering(
                    commandBuffer,
                    renderingInfo
            );

            VkViewport.Buffer viewport =
                    VkViewport.calloc(1, stack);

            viewport.get(0)
                    .x(0.0f)
                    .y(0.0f)
                    .width((float) TARGET_WIDTH)
                    .height((float) TARGET_HEIGHT)
                    .minDepth(0.0f)
                    .maxDepth(1.0f);

            vkCmdSetViewport(
                    commandBuffer,
                    0,
                    viewport
            );

            VkRect2D.Buffer scissor =
                    VkRect2D.calloc(1, stack);

            scissor.get(0)
                    .offset()
                    .x(0)
                    .y(0);

            scissor.get(0)
                    .extent()
                    .width(TARGET_WIDTH)
                    .height(TARGET_HEIGHT);

            vkCmdSetScissor(
                    commandBuffer,
                    0,
                    scissor
            );

            vkCmdBindPipeline(
                    commandBuffer,
                    VK_PIPELINE_BIND_POINT_GRAPHICS,
                    pipeline
            );

            vkCmdBindVertexBuffers(
                    commandBuffer,
                    0,
                    stack.longs(arenaVertexBuffer),
                    stack.longs(0L)
            );

            vkCmdBindIndexBuffer(
                    commandBuffer,
                    quadIndexBuffer,
                    0L,
                    VK_INDEX_TYPE_UINT32
            );

            vkCmdDrawIndexedIndirect(
                    commandBuffer,
                    indirectBuffer,
                    0L,
                    drawCount,
                    DRAW_INDEXED_INDIRECT_COMMAND_BYTES
            );

            executionObserved = true;

            vkCmdEndRendering(
                    commandBuffer
            );

            vkCmdEndQuery(
                    commandBuffer,
                    queryPool,
                    0
            );

            result = vkEndCommandBuffer(
                    commandBuffer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkEndCommandBuffer(real arena indirect) failed with VkResult " + result
                );
            }

            VkSubmitInfo.Buffer submit =
                    VkSubmitInfo.calloc(1, stack);

            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(
                            stack.pointers(
                                    commandBuffer.address()
                            )
                    );

            result = vkQueueSubmit(
                    graphicsQueue,
                    submit,
                    fence
            );

            if (result != VK_SUCCESS) {
                queueSubmitFailureCount++;
                throw new IllegalStateException(
                        "vkQueueSubmit(real arena indirect) failed with VkResult " + result
                );
            }

            submissionCount++;
            inFlight = true;
        }
    }

    private void transitionTargetToColorAttachment(
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barrier =
                VkImageMemoryBarrier.calloc(1, stack);

        barrier.get(0)
                .sType$Default()
                .srcAccessMask(0)
                .dstAccessMask(
                        VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                                | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                )
                .oldLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                .newLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(targetImage);

        barrier.get(0)
                .subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                0,
                null,
                null,
                barrier
        );
    }

    private void harvestCompletion() {
        if (!inFlight
                || fence == NULL
                || device == null) {
            return;
        }

        fencePollCount++;

        int status =
                vkGetFenceStatus(
                        device,
                        fence
                );

        if (status == VK_NOT_READY) {
            fenceNotReadyCount++;
            return;
        }

        if (status != VK_SUCCESS) {
            disableAfterFailure(
                    new IllegalStateException(
                            "vkGetFenceStatus(real arena indirect) returned " + status
                    )
            );
            return;
        }

        readCompletedQuery();
        inFlight = false;
        completedSubmissionCount++;
    }

    private void readCompletedQuery() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer queryData =
                    stack.malloc(Long.BYTES)
                            .order(ByteOrder.nativeOrder());

            int result =
                    vkGetQueryPoolResults(
                            device,
                            queryPool,
                            0,
                            1,
                            queryData,
                            Long.BYTES,
                            VK_QUERY_RESULT_64_BIT
                    );

            if (result != VK_SUCCESS) {
                queryFailureCount++;
                throw new IllegalStateException(
                        "vkGetQueryPoolResults(real arena indirect) returned " + result
                );
            }

            long samples =
                    queryData.getLong(0);

            rasterizedSamples +=
                    samples;

            if (samples > 0L) {
                positiveRasterProofCount++;
            } else {
                throw new IllegalStateException(
                        "Real arena indirect proof completed with zero rasterized samples."
                );
            }
        } catch (Throwable throwable) {
            disableAfterFailure(throwable);
        }
    }

    private int findMemoryType(
            int typeBits,
            int requiredFlags
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties properties =
                    VkPhysicalDeviceMemoryProperties.malloc(stack);

            vkGetPhysicalDeviceMemoryProperties(
                    physicalDevice,
                    properties
            );

            for (int index = 0;
                 index < properties.memoryTypeCount();
                 index++) {

                if ((typeBits & (1 << index)) == 0) {
                    continue;
                }

                int flags =
                        properties.memoryTypes(index)
                                .propertyFlags();

                if ((flags & requiredFlags) == requiredFlags) {
                    return index;
                }
            }
        }

        throw new IllegalStateException(
                "No Vulkan memory type satisfies required flags 0x"
                        + Integer.toHexString(requiredFlags)
        );
    }

    private void disableAfterFailure(
            Throwable throwable
    ) {
        failureCount++;
        disabledAfterFailure = true;
        lastFailure =
                throwable.getClass().getName()
                        + ": "
                        + String.valueOf(
                                throwable.getMessage()
                        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        boolean safeToDestroy =
                !inFlight;

        if (inFlight
                && graphicsQueue != null) {
            shutdownQueueWaitIdleUsed = true;
            shutdownQueueWaitIdleResult =
                    vkQueueWaitIdle(
                            graphicsQueue
                    );

            if (shutdownQueueWaitIdleResult
                    == VK_SUCCESS) {
                readCompletedQuery();
                inFlight = false;
                completedSubmissionCount++;
                safeToDestroy = true;
            }
        }

        closed = true;
        destroyResources(
                safeToDestroy
        );
        enrich(
                report
        );
    }

    private void destroyResources(
            boolean safeToDestroy
    ) {
        if (!safeToDestroy) {
            return;
        }

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

        if (pipeline != NULL) {
            vkDestroyPipeline(
                    device,
                    pipeline,
                    null
            );
            pipeline = NULL;
        }

        if (pipelineLayout != NULL) {
            vkDestroyPipelineLayout(
                    device,
                    pipelineLayout,
                    null
            );
            pipelineLayout = NULL;
        }

        if (targetImageView != NULL) {
            vkDestroyImageView(
                    device,
                    targetImageView,
                    null
            );
            targetImageView = NULL;
        }

        if (targetImage != NULL) {
            vkDestroyImage(
                    device,
                    targetImage,
                    null
            );
            targetImage = NULL;
        }

        if (targetMemory != NULL) {
            vkFreeMemory(
                    device,
                    targetMemory,
                    null
            );
            targetMemory = NULL;
        }

        if (quadIndexBuffer != NULL) {
            vkDestroyBuffer(
                    device,
                    quadIndexBuffer,
                    null
            );
            quadIndexBuffer = NULL;
        }

        if (quadIndexMemory != NULL) {
            vkFreeMemory(
                    device,
                    quadIndexMemory,
                    null
            );
            quadIndexMemory = NULL;
        }

        if (indirectMappedAddress != NULL
                && indirectMemory != NULL) {
            vkUnmapMemory(
                    device,
                    indirectMemory
            );
            indirectMappedAddress = NULL;
            indirectMapped = null;
        }

        if (indirectBuffer != NULL) {
            vkDestroyBuffer(
                    device,
                    indirectBuffer,
                    null
            );
            indirectBuffer = NULL;
        }

        if (indirectMemory != NULL) {
            vkFreeMemory(
                    device,
                    indirectMemory,
                    null
            );
            indirectMemory = NULL;
        }

        resourcesDestroyed = true;
    }
}
