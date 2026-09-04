package dev.ordovicium.potato.render.vulkan;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One persistent sampled RGBA8 Vulkan image fed from a Potato-owned CPU
 * snapshot.
 *
 * <p>Patch 066 preserves the initial upload path and adds coalesced live
 * generation updates that piggyback on the world-render command buffer. Live
 * updates create no extra queue submission and perform no gameplay GPU wait.</p>
 */
final class VulkanTextureImageResource
        implements AutoCloseable {

    private static final long FENCE_TIMEOUT_NANOSECONDS =
            10_000_000_000L;

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamilyIndex;

    private final String label;

    private long image = NULL;
    private long memory = NULL;
    private long view = NULL;

    private VulkanGeometryBufferAllocation staging;

    private long commandPool = NULL;
    private VkCommandBuffer commandBuffer;
    private long fence = NULL;

    private int width;
    private int height;
    private int format =
            VK_FORMAT_R8G8B8A8_UNORM;

    private long uploadedGeneration;
    private int uploadedBytes;

    private int uploadCount;

    private int lastQueueSubmitResult =
            Integer.MIN_VALUE;

    private int lastFenceWaitResult =
            Integer.MIN_VALUE;

    private int finalLayout =
            VK_IMAGE_LAYOUT_UNDEFINED;

    private int memoryTypeIndex =
            -1;

    private long allocationBytes;

    private long lastUploadMillis;

    private long preparedGeneration;
    private int preparedBytes;

    private long submittedGeneration;
    private int submittedBytes;

    private long livePrepareCount;
    private long liveRecordCount;
    private long liveCompleteCount;
    private long livePrepareMillis;

    /*
     * Historical lifetime proof is kept separately from current native-handle
     * state. Shutdown diagnostics must not confuse "destroyed correctly" with
     * "never created".
     */
    private boolean verifiedBeforeClose;

    private boolean imageCreated;
    private boolean memoryAllocated;
    private boolean viewCreated;

    private boolean teardownVerified;

    private boolean closed;

    VulkanTextureImageResource(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            String label
    ) {
        this.device = device;
        this.physicalDevice = physicalDevice;
        this.graphicsQueue = graphicsQueue;
        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;
        this.label = label;
    }

    synchronized void uploadInitial(
            ByteBuffer rgbaSource,
            int width,
            int height,
            long generation
    ) {
        if (closed) {
            throw failure(
                    "Texture resource is closed."
            );
        }

        if (uploadedGeneration > 0L) {
            return;
        }

        if (rgbaSource == null) {
            throw failure(
                    "RGBA source is null."
            );
        }

        if (width <= 0
                || height <= 0) {
            throw failure(
                    "Invalid image extent "
                            + width
                            + "x"
                            + height
            );
        }

        long expectedLong =
                (long) width
                        * (long) height
                        * 4L;

        if (expectedLong
                > Integer.MAX_VALUE) {
            throw failure(
                    "RGBA image exceeds Java buffer limit."
            );
        }

        int expectedBytes =
                (int) expectedLong;

        if (rgbaSource.remaining()
                != expectedBytes) {
            throw failure(
                    "RGBA source size "
                            + rgbaSource.remaining()
                            + " does not match "
                            + expectedBytes
                            + " bytes for "
                            + width
                            + "x"
                            + height
            );
        }

        long started =
                System.nanoTime();

        ensureImage(
                width,
                height
        );

        ensureUploadObjects(
                expectedBytes
        );

        /*
         * BlockAtlasSnapshot/LightmapSnapshot intentionally use ordinary
         * Java-owned read-only buffers. VulkanGeometryBufferAllocation needs
         * an addressable direct source, so make one bounded direct copy here.
         *
         * Patch 041 does this once per texture, not per frame.
         */
        ByteBuffer direct =
                memAlloc(
                        expectedBytes
                );

        try {
            ByteBuffer source =
                    rgbaSource.duplicate();

            direct.put(
                    source
            );
            direct.flip();

            staging.upload(
                    direct
            );
        } finally {
            memFree(
                    direct
            );
        }

        recordUploadCommands(
                width,
                height
        );

        uploadedGeneration =
                generation;

        uploadedBytes =
                expectedBytes;

        uploadCount++;

        finalLayout =
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        lastUploadMillis =
                Math.max(
                        0L,
                        System.nanoTime()
                                - started
                )
                        / 1_000_000L;

        verifiedBeforeClose =
                liveVerified();
    }

    /**
     * Prepare the newest CPU snapshot for a later GPU copy recorded by the
     * world-render command buffer. No queue submission or GPU wait occurs here.
     *
     * <p>The staging allocation is only rewritten when the previous piggybacked
     * upload has completed. Callers therefore get natural coalescing: if a GPU
     * submission is still in flight this method returns false and the newest
     * snapshot can be retried after that submission completes.</p>
     */
    synchronized boolean prepareUpdate(
            ByteBuffer rgbaSource,
            int requestedWidth,
            int requestedHeight,
            long generation
    ) {
        if (closed
                || rgbaSource == null
                || generation <= uploadedGeneration
                || generation <= preparedGeneration
                || generation <= submittedGeneration) {
            return false;
        }

        if (submittedGeneration > uploadedGeneration) {
            return false;
        }

        if (requestedWidth != width
                || requestedHeight != height
                || width <= 0
                || height <= 0) {
            throw failure(
                    "Live texture extent changed from "
                            + width
                            + "x"
                            + height
                            + " to "
                            + requestedWidth
                            + "x"
                            + requestedHeight
            );
        }

        int expectedBytes =
                width * height * 4;

        if (rgbaSource.remaining()
                != expectedBytes) {
            throw failure(
                    "Live RGBA source size "
                            + rgbaSource.remaining()
                            + " does not match "
                            + expectedBytes
            );
        }

        ensureUploadObjects(
                expectedBytes
        );

        long started =
                System.nanoTime();

        ByteBuffer direct =
                memAlloc(
                        expectedBytes
                );

        try {
            ByteBuffer source =
                    rgbaSource.duplicate();

            direct.put(
                    source
            );
            direct.flip();

            staging.upload(
                    direct
            );
        } finally {
            memFree(
                    direct
            );
        }

        preparedGeneration =
                generation;

        preparedBytes =
                expectedBytes;

        livePrepareCount++;

        livePrepareMillis +=
                Math.max(
                        0L,
                        System.nanoTime()
                                - started
                )
                        / 1_000_000L;

        return true;
    }

    /**
     * Record one prepared upload into an already-recording graphics command
     * buffer. The image returns to SHADER_READ_ONLY_OPTIMAL before subsequent
     * BLOCK draws in the same submission.
     */
    synchronized boolean recordPreparedUpload(
            VkCommandBuffer targetCommandBuffer
    ) {
        if (closed
                || targetCommandBuffer == null
                || preparedGeneration <= uploadedGeneration
                || preparedGeneration <= submittedGeneration) {
            return false;
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {

            VkImageMemoryBarrier.Buffer toTransfer =
                    VkImageMemoryBarrier.calloc(
                            1,
                            stack
                    );

            toTransfer
                    .get(0)
                    .sType$Default()
                    .srcAccessMask(
                            VK_ACCESS_SHADER_READ_BIT
                    )
                    .dstAccessMask(
                            VK_ACCESS_TRANSFER_WRITE_BIT
                    )
                    .oldLayout(
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    )
                    .newLayout(
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    )
                    .srcQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .dstQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .image(
                            image
                    );

            toTransfer
                    .get(0)
                    .subresourceRange()
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .baseMipLevel(
                            0
                    )
                    .levelCount(
                            1
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            vkCmdPipelineBarrier(
                    targetCommandBuffer,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    toTransfer
            );

            VkBufferImageCopy.Buffer copy =
                    VkBufferImageCopy.calloc(
                            1,
                            stack
                    );

            copy
                    .get(0)
                    .bufferOffset(
                            0L
                    )
                    .bufferRowLength(
                            0
                    )
                    .bufferImageHeight(
                            0
                    );

            copy
                    .get(0)
                    .imageSubresource()
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .mipLevel(
                            0
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            copy
                    .get(0)
                    .imageOffset()
                    .set(
                            0,
                            0,
                            0
                    );

            copy
                    .get(0)
                    .imageExtent()
                    .set(
                            width,
                            height,
                            1
                    );

            vkCmdCopyBufferToImage(
                    targetCommandBuffer,
                    staging.buffer(),
                    image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copy
            );

            VkImageMemoryBarrier.Buffer toShaderRead =
                    VkImageMemoryBarrier.calloc(
                            1,
                            stack
                    );

            toShaderRead
                    .get(0)
                    .sType$Default()
                    .srcAccessMask(
                            VK_ACCESS_TRANSFER_WRITE_BIT
                    )
                    .dstAccessMask(
                            VK_ACCESS_SHADER_READ_BIT
                    )
                    .oldLayout(
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    )
                    .newLayout(
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    )
                    .srcQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .dstQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .image(
                            image
                    );

            toShaderRead
                    .get(0)
                    .subresourceRange()
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .baseMipLevel(
                            0
                    )
                    .levelCount(
                            1
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            vkCmdPipelineBarrier(
                    targetCommandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                            | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    toShaderRead
            );
        }

        submittedGeneration =
                preparedGeneration;

        submittedBytes =
                preparedBytes;

        preparedGeneration =
                0L;

        preparedBytes =
                0;

        liveRecordCount++;

        return true;
    }

    /**
     * Commit generation telemetry after the command buffer carrying the copy
     * has completed. This performs no Vulkan call.
     */
    synchronized boolean completeSubmittedUpload() {
        if (submittedGeneration <= uploadedGeneration) {
            return false;
        }

        uploadedGeneration =
                submittedGeneration;

        uploadedBytes =
                submittedBytes;

        submittedGeneration =
                0L;

        submittedBytes =
                0;

        uploadCount++;

        finalLayout =
                VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

        liveCompleteCount++;

        verifiedBeforeClose =
                liveVerified();

        return true;
    }

    synchronized boolean hasPreparedOrSubmittedUpload() {
        return preparedGeneration > uploadedGeneration
                || submittedGeneration > uploadedGeneration;
    }

    synchronized long livePrepareCount() {
        return livePrepareCount;
    }

    synchronized long liveRecordCount() {
        return liveRecordCount;
    }

    synchronized long liveCompleteCount() {
        return liveCompleteCount;
    }

    synchronized long livePrepareMillis() {
        return livePrepareMillis;
    }

    synchronized long submittedGeneration() {
        return submittedGeneration;
    }

    synchronized boolean verified() {
        return verifiedBeforeClose
                || liveVerified();
    }

    private boolean liveVerified() {
        return !closed
                && image != NULL
                && memory != NULL
                && view != NULL
                && staging != null
                && staging.alive()
                && commandPool != NULL
                && commandBuffer != null
                && fence != NULL
                && width > 0
                && height > 0
                && uploadedGeneration > 0L
                && uploadedBytes
                == width * height * 4
                && uploadCount >= 1
                && lastQueueSubmitResult
                == VK_SUCCESS
                && lastFenceWaitResult
                == VK_SUCCESS
                && finalLayout
                == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    synchronized long uploadedGeneration() {
        return uploadedGeneration;
    }

    synchronized int uploadedBytes() {
        return uploadedBytes;
    }

    synchronized int width() {
        return width;
    }

    synchronized int height() {
        return height;
    }

    synchronized int format() {
        return format;
    }

    synchronized int uploadCount() {
        return uploadCount;
    }

    synchronized int lastQueueSubmitResult() {
        return lastQueueSubmitResult;
    }

    synchronized int lastFenceWaitResult() {
        return lastFenceWaitResult;
    }

    synchronized int finalLayout() {
        return finalLayout;
    }

    synchronized int memoryTypeIndex() {
        return memoryTypeIndex;
    }

    synchronized long allocationBytes() {
        return allocationBytes;
    }

    synchronized long lastUploadMillis() {
        return lastUploadMillis;
    }

    synchronized boolean imageNonZero() {
        return image != NULL;
    }

    synchronized boolean memoryNonZero() {
        return memory != NULL;
    }

    synchronized boolean viewNonZero() {
        return view != NULL;
    }

    synchronized boolean imageWasCreated() {
        return imageCreated;
    }

    synchronized boolean memoryWasAllocated() {
        return memoryAllocated;
    }

    synchronized boolean viewWasCreated() {
        return viewCreated;
    }

    synchronized boolean verifiedBeforeClose() {
        return verifiedBeforeClose;
    }

    synchronized boolean teardownVerified() {
        return teardownVerified;
    }

    synchronized boolean closed() {
        return closed;
    }

    synchronized long imageView() {
        return view;
    }

    synchronized long image() {
        return image;
    }

    private void ensureImage(
            int requestedWidth,
            int requestedHeight
    ) {
        if (image != NULL) {
            if (width != requestedWidth
                    || height != requestedHeight) {
                throw failure(
                        "Patch 041 initial-upload resource cannot be resized."
                );
            }

            return;
        }

        int usage =
                VK_IMAGE_USAGE_TRANSFER_DST_BIT
                        | VK_IMAGE_USAGE_SAMPLED_BIT;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {

            VkImageFormatProperties formatProperties =
                    VkImageFormatProperties.calloc(
                            stack
                    );

            int formatResult =
                    vkGetPhysicalDeviceImageFormatProperties(
                            physicalDevice,
                            format,
                            VK_IMAGE_TYPE_2D,
                            VK_IMAGE_TILING_OPTIMAL,
                            usage,
                            0,
                            formatProperties
                    );

            if (formatResult
                    != VK_SUCCESS) {
                throw failure(
                        "VK_FORMAT_R8G8B8A8_UNORM does not support sampled transfer-dst usage."
                );
            }

            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .imageType(
                                    VK_IMAGE_TYPE_2D
                            )
                            .format(
                                    format
                            )
                            .mipLevels(
                                    1
                            )
                            .arrayLayers(
                                    1
                            )
                            .samples(
                                    VK_SAMPLE_COUNT_1_BIT
                            )
                            .tiling(
                                    VK_IMAGE_TILING_OPTIMAL
                            )
                            .usage(
                                    usage
                            )
                            .sharingMode(
                                    VK_SHARING_MODE_EXCLUSIVE
                            )
                            .initialLayout(
                                    VK_IMAGE_LAYOUT_UNDEFINED
                            );

            imageInfo.extent(
                    extent ->
                            extent
                                    .width(
                                            requestedWidth
                                    )
                                    .height(
                                            requestedHeight
                                    )
                                    .depth(
                                            1
                                    )
            );

            LongBuffer imagePointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateImage(
                            device,
                            imageInfo,
                            null,
                            imagePointer
                    );

            if (result
                    != VK_SUCCESS) {
                throw failure(
                        "vkCreateImage failed with VkResult "
                                + result
                );
            }

            image =
                    imagePointer.get(
                            0
                    );

            imageCreated =
                    image != NULL;

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(
                            stack
                    );

            vkGetImageMemoryRequirements(
                    device,
                    image,
                    requirements
            );

            memoryTypeIndex =
                    findDeviceLocalMemoryType(
                            requirements.memoryTypeBits(),
                            stack
                    );

            allocationBytes =
                    requirements.size();

            VkMemoryAllocateInfo allocationInfo =
                    VkMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    memoryTypeIndex
                            );

            LongBuffer memoryPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkAllocateMemory(
                            device,
                            allocationInfo,
                            null,
                            memoryPointer
                    );

            if (result
                    != VK_SUCCESS) {
                throw failure(
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            memory =
                    memoryPointer.get(
                            0
                    );

            memoryAllocated =
                    memory != NULL;

            result =
                    vkBindImageMemory(
                            device,
                            image,
                            memory,
                            0L
                    );

            if (result
                    != VK_SUCCESS) {
                throw failure(
                        "vkBindImageMemory failed with VkResult "
                                + result
                );
            }

            VkImageViewCreateInfo viewInfo =
                    VkImageViewCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .image(
                                    image
                            )
                            .viewType(
                                    VK_IMAGE_VIEW_TYPE_2D
                            )
                            .format(
                                    format
                            );

            viewInfo
                    .subresourceRange()
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .baseMipLevel(
                            0
                    )
                    .levelCount(
                            1
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            LongBuffer viewPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateImageView(
                            device,
                            viewInfo,
                            null,
                            viewPointer
                    );

            if (result
                    != VK_SUCCESS) {
                throw failure(
                        "vkCreateImageView failed with VkResult "
                                + result
                );
            }

            view =
                    viewPointer.get(
                            0
                    );

            viewCreated =
                    view != NULL;

            width =
                    requestedWidth;

            height =
                    requestedHeight;
        }
    }

    private void ensureUploadObjects(
            int expectedBytes
    ) {
        long requiredCapacity =
                nextPowerOfTwo(
                        Math.max(
                                256L,
                                expectedBytes
                        )
                );

        if (staging == null
                || staging.bufferCapacityBytes()
                < requiredCapacity) {

            if (staging != null) {
                staging.close();
            }

            staging =
                    VulkanGeometryBufferAllocation
                            .create(
                                    device,
                                    physicalDevice,
                                    requiredCapacity,
                                    VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                    label
                                            + "_TEXTURE_STAGING"
                            );
        }

        if (commandPool == NULL) {
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

                LongBuffer poolPointer =
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

                if (result
                        != VK_SUCCESS) {
                    throw failure(
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

                var commandPointers =
                        stack.mallocPointer(
                                1
                        );

                result =
                        vkAllocateCommandBuffers(
                                device,
                                allocateInfo,
                                commandPointers
                        );

                if (result
                        != VK_SUCCESS) {
                    throw failure(
                            "vkAllocateCommandBuffers failed with VkResult "
                                    + result
                    );
                }

                commandBuffer =
                        new VkCommandBuffer(
                                commandPointers.get(
                                        0
                                ),
                                device
                        );

                VkFenceCreateInfo fenceInfo =
                        VkFenceCreateInfo.calloc(
                                stack
                        )
                                .sType$Default();

                LongBuffer fencePointer =
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

                if (result
                        != VK_SUCCESS) {
                    throw failure(
                            "vkCreateFence failed with VkResult "
                                    + result
                    );
                }

                fence =
                        fencePointer.get(
                                0
                        );
            }
        }
    }

    private void recordUploadCommands(
            int copyWidth,
            int copyHeight
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {

            int resetFenceResult =
                    vkResetFences(
                            device,
                            fence
                    );

            if (resetFenceResult
                    != VK_SUCCESS) {
                throw failure(
                        "vkResetFences failed with VkResult "
                                + resetFenceResult
                );
            }

            int resetCommandResult =
                    vkResetCommandBuffer(
                            commandBuffer,
                            0
                    );

            if (resetCommandResult
                    != VK_SUCCESS) {
                throw failure(
                        "vkResetCommandBuffer failed with VkResult "
                                + resetCommandResult
                );
            }

            VkCommandBufferBeginInfo beginInfo =
                    VkCommandBufferBeginInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            );

            int beginResult =
                    vkBeginCommandBuffer(
                            commandBuffer,
                            beginInfo
                    );

            if (beginResult
                    != VK_SUCCESS) {
                throw failure(
                        "vkBeginCommandBuffer failed with VkResult "
                                + beginResult
                );
            }

            VkImageMemoryBarrier.Buffer toTransfer =
                    VkImageMemoryBarrier.calloc(
                            1,
                            stack
                    );

            toTransfer
                    .get(0)
                    .sType$Default()
                    .srcAccessMask(
                            0
                    )
                    .dstAccessMask(
                            VK_ACCESS_TRANSFER_WRITE_BIT
                    )
                    .oldLayout(
                            VK_IMAGE_LAYOUT_UNDEFINED
                    )
                    .newLayout(
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    )
                    .srcQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .dstQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .image(
                            image
                    );

            toTransfer
                    .get(0)
                    .subresourceRange()
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .baseMipLevel(
                            0
                    )
                    .levelCount(
                            1
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    toTransfer
            );

            VkBufferImageCopy.Buffer copy =
                    VkBufferImageCopy.calloc(
                            1,
                            stack
                    );

            copy
                    .get(0)
                    .bufferOffset(
                            0L
                    )
                    .bufferRowLength(
                            0
                    )
                    .bufferImageHeight(
                            0
                    );

            copy
                    .get(0)
                    .imageSubresource()
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .mipLevel(
                            0
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            copy
                    .get(0)
                    .imageOffset()
                    .set(
                            0,
                            0,
                            0
                    );

            copy
                    .get(0)
                    .imageExtent()
                    .set(
                            copyWidth,
                            copyHeight,
                            1
                    );

            vkCmdCopyBufferToImage(
                    commandBuffer,
                    staging.buffer(),
                    image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    copy
            );

            VkImageMemoryBarrier.Buffer toShaderRead =
                    VkImageMemoryBarrier.calloc(
                            1,
                            stack
                    );

            toShaderRead
                    .get(0)
                    .sType$Default()
                    .srcAccessMask(
                            VK_ACCESS_TRANSFER_WRITE_BIT
                    )
                    .dstAccessMask(
                            VK_ACCESS_SHADER_READ_BIT
                    )
                    .oldLayout(
                            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                    )
                    .newLayout(
                            VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                    )
                    .srcQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .dstQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .image(
                            image
                    );

            toShaderRead
                    .get(0)
                    .subresourceRange()
                    .aspectMask(
                            VK_IMAGE_ASPECT_COLOR_BIT
                    )
                    .baseMipLevel(
                            0
                    )
                    .levelCount(
                            1
                    )
                    .baseArrayLayer(
                            0
                    )
                    .layerCount(
                            1
                    );

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    0,
                    null,
                    null,
                    toShaderRead
            );

            int endResult =
                    vkEndCommandBuffer(
                            commandBuffer
                    );

            if (endResult
                    != VK_SUCCESS) {
                throw failure(
                        "vkEndCommandBuffer failed with VkResult "
                                + endResult
                );
            }

            VkSubmitInfo submitInfo =
                    VkSubmitInfo.calloc(
                            stack
                    )
                            .sType$Default();

            submitInfo.pCommandBuffers(
                    stack.pointers(
                            commandBuffer
                    )
            );

            lastQueueSubmitResult =
                    vkQueueSubmit(
                            graphicsQueue,
                            submitInfo,
                            fence
                    );

            if (lastQueueSubmitResult
                    != VK_SUCCESS) {
                throw failure(
                        "vkQueueSubmit failed with VkResult "
                                + lastQueueSubmitResult
                );
            }

            lastFenceWaitResult =
                    vkWaitForFences(
                            device,
                            fence,
                            true,
                            FENCE_TIMEOUT_NANOSECONDS
                    );

            if (lastFenceWaitResult
                    != VK_SUCCESS) {
                throw failure(
                        "vkWaitForFences failed with VkResult "
                                + lastFenceWaitResult
                );
            }
        }
    }

    private int findDeviceLocalMemoryType(
            int memoryTypeBits,
            MemoryStack stack
    ) {
        VkPhysicalDeviceMemoryProperties properties =
                VkPhysicalDeviceMemoryProperties.malloc(
                        stack
                );

        vkGetPhysicalDeviceMemoryProperties(
                physicalDevice,
                properties
        );

        for (int index = 0;
             index < properties.memoryTypeCount();
             index++) {

            int bit =
                    1 << index;

            if ((memoryTypeBits & bit)
                    == 0) {
                continue;
            }

            int flags =
                    properties
                            .memoryTypes(
                                    index
                            )
                            .propertyFlags();

            if ((flags
                    & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                    != 0) {
                return index;
            }
        }

        for (int index = 0;
             index < properties.memoryTypeCount();
             index++) {

            int bit =
                    1 << index;

            if ((memoryTypeBits & bit)
                    != 0) {
                return index;
            }
        }

        throw failure(
                "No compatible Vulkan memory type."
        );
    }

    private static long nextPowerOfTwo(
            long value
    ) {
        long result =
                1L;

        while (result < value) {
            result <<= 1;

            if (result <= 0L) {
                throw new IllegalStateException(
                        "Texture staging capacity overflow."
                );
            }
        }

        return result;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        if (staging != null) {
            staging.close();
            staging = null;
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

        if (view != NULL) {
            vkDestroyImageView(
                    device,
                    view,
                    null
            );
            view = NULL;
        }

        if (image != NULL) {
            vkDestroyImage(
                    device,
                    image,
                    null
            );
            image = NULL;
        }

        if (memory != NULL) {
            vkFreeMemory(
                    device,
                    memory,
                    null
            );
            memory = NULL;
        }

        teardownVerified =
                image == NULL
                        && memory == NULL
                        && view == NULL
                        && staging == null
                        && commandPool == NULL
                        && commandBuffer == null
                        && fence == NULL;
    }

    private VulkanProbeException failure(
            String detail
    ) {
        return new VulkanProbeException(
                "VULKAN_"
                        + label
                        + "_TEXTURE_IMAGE",
                detail
        );
    }
}