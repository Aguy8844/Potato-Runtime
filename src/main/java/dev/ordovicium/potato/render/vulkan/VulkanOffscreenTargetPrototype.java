package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Potato-owned Vulkan offscreen color/depth target prototype.
 *
 * <p>This is intentionally parallel to Minecraft's OpenGL MainTarget. It owns
 * real VkImage, VkDeviceMemory and VkImageView resources but is not yet returned
 * to Minecraft and never writes native Vulkan handles into Mojang's integer
 * OpenGL ID fields.</p>
 */
final class VulkanOffscreenTargetPrototype implements AutoCloseable {
    private static final long FENCE_TIMEOUT_NANOSECONDS =
            10_000_000_000L;

    private static final float CLEAR_RED = 0.08f;
    private static final float CLEAR_GREEN = 0.12f;
    private static final float CLEAR_BLUE = 0.18f;
    private static final float CLEAR_ALPHA = 1.0f;

    private final VkDevice device;
    private final JsonObject report;

    private Attachment color;
    private Attachment depth;

    private final int width;
    private final int height;
    private final boolean useDepth;

    private boolean closed;

    private VulkanOffscreenTargetPrototype(
            VkDevice device,
            JsonObject report,
            int width,
            int height,
            boolean useDepth
    ) {
        this.device = device;
        this.report = report;
        this.width = width;
        this.height = height;
        this.useDepth = useDepth;
    }

    static VulkanOffscreenTargetPrototype create(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            int width,
            int height,
            boolean useDepth,
            JsonObject report
    ) {
        report.addProperty(
                "stage",
                "CREATE_VULKAN_OFFSCREEN_TARGET"
        );

        if (width <= 0 || height <= 0) {
            throw failure(
                    "CREATE_VULKAN_OFFSCREEN_TARGET",
                    "Invalid offscreen target dimensions "
                            + width
                            + "x"
                            + height
            );
        }

        VulkanOffscreenTargetPrototype target =
                new VulkanOffscreenTargetPrototype(
                        device,
                        report,
                        width,
                        height,
                        useDepth
                );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int colorUsage =
                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                            | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;

            int colorFormat =
                    chooseSupportedFormat(
                            physicalDevice,
                            new int[] {
                                    VK_FORMAT_R8G8B8A8_UNORM,
                                    VK_FORMAT_B8G8R8A8_UNORM
                            },
                            colorUsage,
                            stack,
                            "COLOR"
                    );

            target.color =
                    createAttachment(
                            physicalDevice,
                            device,
                            width,
                            height,
                            colorFormat,
                            colorUsage,
                            VK_IMAGE_ASPECT_COLOR_BIT,
                            "Color",
                            stack,
                            report
                    );

            if (useDepth) {
                int depthUsage =
                        VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                                | VK_IMAGE_USAGE_TRANSFER_DST_BIT;

                int depthFormat =
                        chooseSupportedFormat(
                                physicalDevice,
                                new int[] {
                                        VK_FORMAT_D32_SFLOAT,
                                        VK_FORMAT_D32_SFLOAT_S8_UINT,
                                        VK_FORMAT_D24_UNORM_S8_UINT
                                },
                                depthUsage,
                                stack,
                                "DEPTH"
                        );

                target.depth =
                        createAttachment(
                                physicalDevice,
                                device,
                                width,
                                height,
                                depthFormat,
                                depthUsage,
                                VK_IMAGE_ASPECT_DEPTH_BIT,
                                "Depth",
                                stack,
                                report
                        );
            }

            target.initializeOnGpu(
                    graphicsQueue,
                    graphicsQueueFamilyIndex
            );

            report.addProperty(
                    "vulkanOffscreenTargetPrototypeCreated",
                    true
            );
            report.addProperty(
                    "vulkanOffscreenTargetWidth",
                    width
            );
            report.addProperty(
                    "vulkanOffscreenTargetHeight",
                    height
            );
            report.addProperty(
                    "vulkanOffscreenTargetUseDepth",
                    useDepth
            );
            report.addProperty(
                    "vulkanOffscreenTargetColorFormat",
                    target.color.format()
            );
            report.addProperty(
                    "vulkanOffscreenTargetColorFormatName",
                    formatName(
                            target.color.format()
                    )
            );

            if (target.depth != null) {
                report.addProperty(
                        "vulkanOffscreenTargetDepthFormat",
                        target.depth.format()
                );
                report.addProperty(
                        "vulkanOffscreenTargetDepthFormatName",
                        formatName(
                                target.depth.format()
                        )
                );
            }

            report.addProperty(
                    "vulkanOffscreenTargetColorImageCreated",
                    target.color.image() != NULL
            );
            report.addProperty(
                    "vulkanOffscreenTargetColorMemoryAllocated",
                    target.color.memory() != NULL
            );
            report.addProperty(
                    "vulkanOffscreenTargetColorMemoryBound",
                    target.color.memoryBound()
            );
            report.addProperty(
                    "vulkanOffscreenTargetColorImageViewCreated",
                    target.color.view() != NULL
            );
            report.addProperty(
                    "vulkanOffscreenTargetColorMemoryTypeIndex",
                    target.color.memoryTypeIndex()
            );
            report.addProperty(
                    "vulkanOffscreenTargetColorAllocationBytes",
                    target.color.allocationBytes()
            );

            report.addProperty(
                    "vulkanOffscreenTargetDepthImageCreated",
                    !useDepth
                            || target.depth.image() != NULL
            );
            report.addProperty(
                    "vulkanOffscreenTargetDepthMemoryAllocated",
                    !useDepth
                            || target.depth.memory() != NULL
            );
            report.addProperty(
                    "vulkanOffscreenTargetDepthMemoryBound",
                    !useDepth
                            || target.depth.memoryBound()
            );
            report.addProperty(
                    "vulkanOffscreenTargetDepthImageViewCreated",
                    !useDepth
                            || target.depth.view() != NULL
            );

            if (target.depth != null) {
                report.addProperty(
                        "vulkanOffscreenTargetDepthMemoryTypeIndex",
                        target.depth.memoryTypeIndex()
                );
                report.addProperty(
                        "vulkanOffscreenTargetDepthAllocationBytes",
                        target.depth.allocationBytes()
                );
            }

            report.addProperty(
                    "vulkanOffscreenTargetPrototypeVerified",
                    target.color.image() != NULL
                            && target.color.memory() != NULL
                            && target.color.memoryBound()
                            && target.color.view() != NULL
                            && (!useDepth
                            || (target.depth != null
                            && target.depth.image() != NULL
                            && target.depth.memory() != NULL
                            && target.depth.memoryBound()
                            && target.depth.view() != NULL))
            );

            return target;
        } catch (Throwable throwable) {
            target.close();

            if (throwable instanceof VulkanProbeException probeException) {
                throw probeException;
            }

            throw failure(
                    "CREATE_VULKAN_OFFSCREEN_TARGET",
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            )
            );
        }
    }

    private static int chooseSupportedFormat(
            VkPhysicalDevice physicalDevice,
            int[] candidates,
            int usage,
            MemoryStack stack,
            String label
    ) {
        VkImageFormatProperties properties =
                VkImageFormatProperties.calloc(stack);

        for (int candidate : candidates) {
            properties.clear();

            int result =
                    vkGetPhysicalDeviceImageFormatProperties(
                            physicalDevice,
                            candidate,
                            VK_IMAGE_TYPE_2D,
                            VK_IMAGE_TILING_OPTIMAL,
                            usage,
                            0,
                            properties
                    );

            if (result == VK_SUCCESS) {
                return candidate;
            }
        }

        throw failure(
                "SELECT_" + label + "_FORMAT",
                "No candidate format supports the required optimal-tiled image usage."
        );
    }

    private static Attachment createAttachment(
            VkPhysicalDevice physicalDevice,
            VkDevice device,
            int width,
            int height,
            int format,
            int usage,
            int aspectMask,
            String label,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty(
                "stage",
                "CREATE_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_IMAGE"
        );

        long image = NULL;
        long memory = NULL;
        long view = NULL;

        try {
            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(stack)
                            .sType$Default()
                            .imageType(VK_IMAGE_TYPE_2D)
                            .format(format)
                            .mipLevels(1)
                            .arrayLayers(1)
                            .samples(VK_SAMPLE_COUNT_1_BIT)
                            .tiling(VK_IMAGE_TILING_OPTIMAL)
                            .usage(usage)
                            .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                            .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            imageInfo.extent(
                    extent ->
                            extent
                                    .width(width)
                                    .height(height)
                                    .depth(1)
            );

            LongBuffer imagePointer =
                    stack.mallocLong(1);

            int result =
                    vkCreateImage(
                            device,
                            imageInfo,
                            null,
                            imagePointer
                    );

            report.addProperty(
                    "vkCreateOffscreen" + label + "ImageResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw failure(
                        "CREATE_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_IMAGE",
                        "vkCreateImage failed with VkResult " + result
                );
            }

            image =
                    imagePointer.get(0);

            if (image == NULL) {
                throw failure(
                        "CREATE_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_IMAGE",
                        "vkCreateImage returned NULL."
                );
            }

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(stack);

            vkGetImageMemoryRequirements(
                    device,
                    image,
                    requirements
            );

            int memoryTypeIndex =
                    findMemoryType(
                            physicalDevice,
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                            stack
                    );

            VkMemoryAllocateInfo allocationInfo =
                    VkMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    memoryTypeIndex
                            );

            LongBuffer memoryPointer =
                    stack.mallocLong(1);

            result =
                    vkAllocateMemory(
                            device,
                            allocationInfo,
                            null,
                            memoryPointer
                    );

            report.addProperty(
                    "vkAllocateOffscreen" + label + "MemoryResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw failure(
                        "ALLOCATE_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_MEMORY",
                        "vkAllocateMemory failed with VkResult " + result
                );
            }

            memory =
                    memoryPointer.get(0);

            if (memory == NULL) {
                throw failure(
                        "ALLOCATE_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_MEMORY",
                        "vkAllocateMemory returned NULL."
                );
            }

            result =
                    vkBindImageMemory(
                            device,
                            image,
                            memory,
                            0L
                    );

            report.addProperty(
                    "vkBindOffscreen" + label + "ImageMemoryResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw failure(
                        "BIND_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_MEMORY",
                        "vkBindImageMemory failed with VkResult " + result
                );
            }

            VkImageViewCreateInfo viewInfo =
                    VkImageViewCreateInfo.calloc(stack)
                            .sType$Default()
                            .image(image)
                            .viewType(VK_IMAGE_VIEW_TYPE_2D)
                            .format(format);

            viewInfo
                    .subresourceRange()
                    .aspectMask(aspectMask)
                    .baseMipLevel(0)
                    .levelCount(1)
                    .baseArrayLayer(0)
                    .layerCount(1);

            LongBuffer viewPointer =
                    stack.mallocLong(1);

            result =
                    vkCreateImageView(
                            device,
                            viewInfo,
                            null,
                            viewPointer
                    );

            report.addProperty(
                    "vkCreateOffscreen" + label + "ImageViewResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw failure(
                        "CREATE_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_VIEW",
                        "vkCreateImageView failed with VkResult " + result
                );
            }

            view =
                    viewPointer.get(0);

            if (view == NULL) {
                throw failure(
                        "CREATE_VULKAN_OFFSCREEN_" + label.toUpperCase() + "_VIEW",
                        "vkCreateImageView returned NULL."
                );
            }

            return new Attachment(
                    image,
                    memory,
                    view,
                    format,
                    memoryTypeIndex,
                    requirements.size(),
                    true
            );
        } catch (Throwable throwable) {
            if (view != NULL) {
                vkDestroyImageView(
                        device,
                        view,
                        null
                );
            }

            if (image != NULL) {
                vkDestroyImage(
                        device,
                        image,
                        null
                );
            }

            if (memory != NULL) {
                vkFreeMemory(
                        device,
                        memory,
                        null
                );
            }

            throw throwable;
        }
    }

    private static int findMemoryType(
            VkPhysicalDevice physicalDevice,
            int memoryTypeBits,
            int requiredFlags,
            MemoryStack stack
    ) {
        VkPhysicalDeviceMemoryProperties properties =
                VkPhysicalDeviceMemoryProperties.malloc(stack);

        vkGetPhysicalDeviceMemoryProperties(
                physicalDevice,
                properties
        );

        for (int index = 0;
             index < properties.memoryTypeCount();
             index++) {

            boolean allowed =
                    (memoryTypeBits & (1 << index)) != 0;

            int flags =
                    properties
                            .memoryTypes(index)
                            .propertyFlags();

            boolean hasRequiredFlags =
                    (flags & requiredFlags)
                            == requiredFlags;

            if (allowed && hasRequiredFlags) {
                return index;
            }
        }

        throw failure(
                "SELECT_VULKAN_OFFSCREEN_MEMORY_TYPE",
                "No compatible device-local memory type was found."
        );
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    boolean useDepth() {
        return useDepth;
    }

    long colorImage() {
        return color == null
                ? NULL
                : color.image();
    }

    long colorImageView() {
        return color == null
                ? NULL
                : color.view();
    }

    int colorFormat() {
        return color == null
                ? VK_FORMAT_UNDEFINED
                : color.format();
    }

    long depthImage() {
        return depth == null
                ? NULL
                : depth.image();
    }

    long depthImageView() {
        return depth == null
                ? NULL
                : depth.view();
    }

    int depthFormatOrUndefined() {
        return depth == null
                ? VK_FORMAT_UNDEFINED
                : depth.format();
    }

    private void initializeOnGpu(
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex
    ) {
        report.addProperty(
                "stage",
                "INITIALIZE_VULKAN_OFFSCREEN_TARGET_GPU"
        );

        long commandPool = NULL;
        long fence = NULL;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo =
                    VkCommandPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_POOL_CREATE_TRANSIENT_BIT
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

            report.addProperty(
                    "vkCreateOffscreenCommandPoolResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw failure(
                        "CREATE_VULKAN_OFFSCREEN_COMMAND_POOL",
                        "vkCreateCommandPool failed with VkResult "
                                + result
                );
            }

            commandPool =
                    poolPointer.get(0);

            VkCommandBufferAllocateInfo allocationInfo =
                    VkCommandBufferAllocateInfo.calloc(stack)
                            .sType$Default()
                            .commandPool(commandPool)
                            .level(
                                    VK_COMMAND_BUFFER_LEVEL_PRIMARY
                            )
                            .commandBufferCount(1);

            PointerBuffer commandBufferPointer =
                    stack.mallocPointer(1);

            result =
                    vkAllocateCommandBuffers(
                            device,
                            allocationInfo,
                            commandBufferPointer
                    );

            report.addProperty(
                    "vkAllocateOffscreenCommandBufferResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw failure(
                        "ALLOCATE_VULKAN_OFFSCREEN_COMMAND_BUFFER",
                        "vkAllocateCommandBuffers failed with VkResult "
                                + result
                );
            }

            VkCommandBuffer commandBuffer =
                    new VkCommandBuffer(
                            commandBufferPointer.get(0),
                            device
                    );

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
                        "BEGIN_VULKAN_OFFSCREEN_COMMAND_BUFFER",
                        "vkBeginCommandBuffer failed with VkResult "
                                + result
                );
            }

            transitionImage(
                    commandBuffer,
                    color.image(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    0,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
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
                    .float32(0, CLEAR_RED)
                    .float32(1, CLEAR_GREEN)
                    .float32(2, CLEAR_BLUE)
                    .float32(3, CLEAR_ALPHA);

            vkCmdClearColorImage(
                    commandBuffer,
                    color.image(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearColor,
                    colorRange
            );

            transitionImage(
                    commandBuffer,
                    color.image(),
                    VK_IMAGE_ASPECT_COLOR_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                            | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    stack
            );

            if (depth != null) {
                transitionImage(
                        commandBuffer,
                        depth.image(),
                        VK_IMAGE_ASPECT_DEPTH_BIT,
                        VK_IMAGE_LAYOUT_UNDEFINED,
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        0,
                        VK_ACCESS_TRANSFER_WRITE_BIT,
                        VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
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
                        depth.image(),
                        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                        clearDepth,
                        depthRange
                );

                transitionImage(
                        commandBuffer,
                        depth.image(),
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

            result =
                    vkEndCommandBuffer(
                            commandBuffer
                    );

            if (result != VK_SUCCESS) {
                throw failure(
                        "END_VULKAN_OFFSCREEN_COMMAND_BUFFER",
                        "vkEndCommandBuffer failed with VkResult "
                                + result
                );
            }

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
                throw failure(
                        "CREATE_VULKAN_OFFSCREEN_FENCE",
                        "vkCreateFence failed with VkResult "
                                + result
                );
            }

            fence =
                    fencePointer.get(0);

            PointerBuffer submittedBuffers =
                    stack.mallocPointer(1)
                            .put(
                                    0,
                                    commandBuffer.address()
                            );

            VkSubmitInfo.Buffer submitInfo =
                    VkSubmitInfo.calloc(
                            1,
                            stack
                    );

            submitInfo
                    .get(0)
                    .sType$Default()
                    .pCommandBuffers(
                            submittedBuffers
                    );

            result =
                    vkQueueSubmit(
                            graphicsQueue,
                            submitInfo,
                            fence
                    );

            report.addProperty(
                    "vkQueueSubmitOffscreenInitializationResult",
                    result
            );

            if (result != VK_SUCCESS) {
                throw failure(
                        "SUBMIT_VULKAN_OFFSCREEN_INITIALIZATION",
                        "vkQueueSubmit failed with VkResult "
                                + result
                );
            }

            result =
                    vkWaitForFences(
                            device,
                            stack.longs(fence),
                            true,
                            FENCE_TIMEOUT_NANOSECONDS
                    );

            report.addProperty(
                    "vkWaitForOffscreenInitializationFenceResult",
                    result
            );

            if (result == VK_TIMEOUT) {
                throw failure(
                        "WAIT_VULKAN_OFFSCREEN_INITIALIZATION",
                        "Offscreen target initialization fence timed out."
                );
            }

            if (result != VK_SUCCESS) {
                throw failure(
                        "WAIT_VULKAN_OFFSCREEN_INITIALIZATION",
                        "vkWaitForFences failed with VkResult "
                                + result
                );
            }

            report.addProperty(
                    "vulkanOffscreenGpuInitializationSubmitted",
                    true
            );
            report.addProperty(
                    "vulkanOffscreenGpuInitializationComplete",
                    true
            );
            report.addProperty(
                    "vulkanOffscreenColorCleared",
                    true
            );
            report.addProperty(
                    "vulkanOffscreenDepthCleared",
                    depth != null
            );
            report.addProperty(
                    "vulkanOffscreenColorFinalLayout",
                    "COLOR_ATTACHMENT_OPTIMAL"
            );

            if (depth != null) {
                report.addProperty(
                        "vulkanOffscreenDepthFinalLayout",
                        "DEPTH_STENCIL_ATTACHMENT_OPTIMAL"
                );
            }

            report.addProperty(
                    "vulkanOffscreenReadyForDynamicRendering",
                    true
            );
        } finally {
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

            report.addProperty(
                    "vulkanOffscreenInitializationFenceDestroyed",
                    fence != NULL
            );
            report.addProperty(
                    "vulkanOffscreenInitializationCommandPoolDestroyed",
                    commandPool != NULL
            );
        }
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

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;

        boolean depthViewDestroyed = false;
        boolean depthImageDestroyed = false;
        boolean depthMemoryFreed = false;

        boolean colorViewDestroyed = false;
        boolean colorImageDestroyed = false;
        boolean colorMemoryFreed = false;

        if (depth != null) {
            if (depth.view() != NULL) {
                vkDestroyImageView(
                        device,
                        depth.view(),
                        null
                );
                depthViewDestroyed = true;
            }

            if (depth.image() != NULL) {
                vkDestroyImage(
                        device,
                        depth.image(),
                        null
                );
                depthImageDestroyed = true;
            }

            if (depth.memory() != NULL) {
                vkFreeMemory(
                        device,
                        depth.memory(),
                        null
                );
                depthMemoryFreed = true;
            }

            depth = null;
        }

        if (color != null) {
            if (color.view() != NULL) {
                vkDestroyImageView(
                        device,
                        color.view(),
                        null
                );
                colorViewDestroyed = true;
            }

            if (color.image() != NULL) {
                vkDestroyImage(
                        device,
                        color.image(),
                        null
                );
                colorImageDestroyed = true;
            }

            if (color.memory() != NULL) {
                vkFreeMemory(
                        device,
                        color.memory(),
                        null
                );
                colorMemoryFreed = true;
            }

            color = null;
        }

        report.addProperty(
                "vulkanOffscreenDepthImageViewDestroyed",
                !useDepth || depthViewDestroyed
        );
        report.addProperty(
                "vulkanOffscreenDepthImageDestroyed",
                !useDepth || depthImageDestroyed
        );
        report.addProperty(
                "vulkanOffscreenDepthMemoryFreed",
                !useDepth || depthMemoryFreed
        );

        report.addProperty(
                "vulkanOffscreenColorImageViewDestroyed",
                colorViewDestroyed
        );
        report.addProperty(
                "vulkanOffscreenColorImageDestroyed",
                colorImageDestroyed
        );
        report.addProperty(
                "vulkanOffscreenColorMemoryFreed",
                colorMemoryFreed
        );

        report.addProperty(
                "vulkanOffscreenTargetDestroyed",
                colorViewDestroyed
                        && colorImageDestroyed
                        && colorMemoryFreed
                        && (!useDepth
                        || (depthViewDestroyed
                        && depthImageDestroyed
                        && depthMemoryFreed))
        );
    }

    private static String formatName(
            int format
    ) {
        return switch (format) {
            case VK_FORMAT_R8G8B8A8_UNORM ->
                    "VK_FORMAT_R8G8B8A8_UNORM";
            case VK_FORMAT_B8G8R8A8_UNORM ->
                    "VK_FORMAT_B8G8R8A8_UNORM";
            case VK_FORMAT_D32_SFLOAT ->
                    "VK_FORMAT_D32_SFLOAT";
            case VK_FORMAT_D32_SFLOAT_S8_UINT ->
                    "VK_FORMAT_D32_SFLOAT_S8_UINT";
            case VK_FORMAT_D24_UNORM_S8_UINT ->
                    "VK_FORMAT_D24_UNORM_S8_UINT";
            default ->
                    "VkFormat(" + format + ")";
        };
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

    private record Attachment(
            long image,
            long memory,
            long view,
            int format,
            int memoryTypeIndex,
            long allocationBytes,
            boolean memoryBound
    ) {
    }
}