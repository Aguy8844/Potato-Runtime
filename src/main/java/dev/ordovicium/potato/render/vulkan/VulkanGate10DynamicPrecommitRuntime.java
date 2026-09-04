package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandPoolCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkSubmitInfo;
import org.lwjgl.vulkan.VkClearColorValue;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.EnumSet;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Gate-10 Stage 3: production-seam Vulkan precommit plus bounded dynamic
 * state-packet replication for ENTITY / PARTICLE / HUD / SCREEN.
 *
 * <p>Stage 096b already proved one real private Vulkan GPU submission from all
 * four production seams. Stage 097 keeps that proof intact and, after it is
 * complete, serializes one compact render-state packet per domain into a real
 * DEVICE_LOCAL Vulkan buffer with vkCmdUpdateBuffer. The packet carries the
 * current Minecraft framebuffer extent plus the conservative domain state
 * profile that the later Vulkan raster pipeline must implement.</p>
 *
 * <p>This is deliberately still not a renderer cutover. OpenGL remains the
 * visible authority, no draw is cancelled, and gameplay only polls fences with
 * vkGetFenceStatus. State packets are GPU-resident proof data; they are not yet
 * consumed by a visible graphics pipeline.</p>
 */
final class VulkanGate10DynamicPrecommitRuntime implements AutoCloseable {

    private static final int TARGET_WIDTH =
            64;

    private static final int TARGET_HEIGHT =
            64;

    private static final int STATE_PACKET_BYTES =
            8 * Integer.BYTES;

    private static final long STATE_BUFFER_BYTES =
            (long) STATE_PACKET_BYTES
                    * VulkanGate10DynamicOwnershipContract.Domain
                    .values().length;

    private static final int STATE_PACKET_MAGIC =
            0x47543130;

    private static final int STATE_DEPTH_TEST =
            1 << 0;

    private static final int STATE_DEPTH_WRITE =
            1 << 1;

    private static final int STATE_ALPHA_BLEND =
            1 << 2;

    private static final int STATE_SCREEN_SPACE =
            1 << 3;

    private static final int STATE_WORLD_SPACE =
            1 << 4;

    private static final int STATE_CULL_DISABLED =
            1 << 5;

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamilyIndex;
    private final JsonObject report;

    private final EnumSet<VulkanGate10DynamicOwnershipContract.Domain>
            submittedDomains =
            EnumSet.noneOf(
                    VulkanGate10DynamicOwnershipContract.Domain.class
            );

    private final EnumSet<VulkanGate10DynamicOwnershipContract.Domain>
            completedDomains =
            EnumSet.noneOf(
                    VulkanGate10DynamicOwnershipContract.Domain.class
            );

    private final EnumSet<VulkanGate10DynamicOwnershipContract.Domain>
            statePacketSubmittedDomains =
            EnumSet.noneOf(
                    VulkanGate10DynamicOwnershipContract.Domain.class
            );

    private final EnumSet<VulkanGate10DynamicOwnershipContract.Domain>
            statePacketCompletedDomains =
            EnumSet.noneOf(
                    VulkanGate10DynamicOwnershipContract.Domain.class
            );

    private long image =
            NULL;

    private long memory =
            NULL;

    private long stateBuffer =
            NULL;

    private long stateBufferMemory =
            NULL;

    private VulkanGate10DynamicRasterPrecommit
            dynamicRasterPrecommit;

    private VulkanGate10VisibleScreenRehearsal
            visibleScreenRehearsal;

    private long commandPool =
            NULL;

    private VkCommandBuffer commandBuffer;

    private long fence =
            NULL;

    private boolean initialized;
    private boolean imageInitialized;
    private boolean inFlight;
    private boolean disabledAfterFailure;
    private boolean proofRetired;
    private boolean statePacketProofRetired;
    private boolean privateTargetEverCreated;
    private boolean stateBufferEverCreated;
    private boolean inFlightStatePacket;
    private boolean closed;

    private VulkanGate10DynamicOwnershipContract.Domain inFlightDomain;

    private long offerCount;
    private long nonRenderThreadRejectCount;
    private long duplicateDomainSkipCount;
    private long busySkipCount;
    private long submissionCount;
    private long completionCount;
    private long fencePollCount;
    private long fenceNotReadyCount;
    private long failureCount;
    private long shutdownFenceWaitCount;

    private long statePacketOfferCount;
    private long statePacketDuplicateDomainSkipCount;
    private long statePacketBusySkipCount;
    private long statePacketSubmissionCount;
    private long statePacketCompletionCount;

    private int memoryTypeIndex =
            -1;

    private int stateBufferMemoryTypeIndex =
            -1;

    private int lastStatePacketFramebufferWidth;
    private int lastStatePacketFramebufferHeight;
    private int lastStatePacketFlags;
    private int lastStatePacketBlendSrcFactor;
    private int lastStatePacketBlendDstFactor;

    private String lastFailure =
            "";

    VulkanGate10DynamicPrecommitRuntime(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            JsonObject report
    ) {
        this.device =
                device;
        this.physicalDevice =
                physicalDevice;
        this.graphicsQueue =
                graphicsQueue;
        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;
        this.report =
                report;

        enrich();
    }

    synchronized void offer(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        offerCount++;

        if (closed
                || disabledAfterFailure
                || domain == null) {
            enrich();
            return;
        }

        if (!"Render thread".equals(
                Thread.currentThread().getName()
        )) {
            nonRenderThreadRejectCount++;
            enrich();
            return;
        }

        try {
            pollCompletion();

            if (disabledAfterFailure
                    || closed) {
                enrich();
                return;
            }

            if (inFlight) {
                busySkipCount++;

                if (proofRetired) {
                    statePacketBusySkipCount++;
                }

                enrich();
                return;
            }

            ensureInitialized();

            if (!proofRetired) {
                if (completedDomains.contains(domain)
                        || submittedDomains.contains(domain)) {
                    duplicateDomainSkipCount++;
                    enrich();
                    return;
                }

                submit(domain);
                enrich();
                return;
            }

            if (!statePacketProofRetired) {
                statePacketOfferCount++;

                if (statePacketCompletedDomains.contains(domain)
                        || statePacketSubmittedDomains.contains(domain)) {
                    statePacketDuplicateDomainSkipCount++;
                    enrich();
                    return;
                }

                ensureStateBuffer();

                submitStatePacket(
                        domain
                );

                enrich();
                return;
            }

            ensureDynamicRasterPrecommit();

            dynamicRasterPrecommit.offer(
                    domain
            );
        } catch (Throwable throwable) {
            fail(throwable);
        }

        enrich();
    }

    synchronized void beginVisibleScreenRehearsal() {
        if (closed
                || disabledAfterFailure
                || dynamicRasterPrecommit == null
                || !dynamicRasterPrecommit
                .readyForVisibleScreenRehearsal()) {
            enrich();
            return;
        }

        try {
            if (visibleScreenRehearsal == null) {
                visibleScreenRehearsal =
                        new VulkanGate10VisibleScreenRehearsal(
                                device,
                                physicalDevice,
                                graphicsQueue,
                                graphicsQueueFamilyIndex,
                                dynamicRasterPrecommit,
                                report
                        );
            }

            visibleScreenRehearsal.beginFrame();
        } catch (Throwable throwable) {
            report.addProperty(
                    "gate10VisibleScreenRehearsalOwnerFailure",
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    )
            );
        }

        enrich();
    }

    synchronized void endVisibleScreenRehearsal() {
        if (closed
                || visibleScreenRehearsal == null) {
            enrich();
            return;
        }

        try {
            visibleScreenRehearsal.endFrame();
        } catch (Throwable throwable) {
            report.addProperty(
                    "gate10VisibleScreenRehearsalOwnerFailure",
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    )
            );
        }

        enrich();
    }

    private void ensureDynamicRasterPrecommit() {
        if (dynamicRasterPrecommit != null) {
            return;
        }

        if (!statePacketProofRetired
                || stateBuffer == NULL
                || stateBufferMemory == NULL) {
            throw new VulkanProbeException(
                    "GATE10_RASTER_PRECOMMIT_INPUT",
                    "Gate-10 raster precommit requires the complete DEVICE_LOCAL state packet proof."
            );
        }

        dynamicRasterPrecommit =
                new VulkanGate10DynamicRasterPrecommit(
                        device,
                        physicalDevice,
                        graphicsQueue,
                        graphicsQueueFamilyIndex,
                        stateBuffer,
                        report
                );
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }

        createPrivateImage();
        createCommandResources();

        initialized =
                true;

        enrich();
    }

    private void createPrivateImage() {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .imageType(
                                    VK_IMAGE_TYPE_2D
                            )
                            .format(
                                    VK_FORMAT_R8G8B8A8_UNORM
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
                                    VK_IMAGE_USAGE_TRANSFER_DST_BIT
                            )
                            .sharingMode(
                                    VK_SHARING_MODE_EXCLUSIVE
                            )
                            .initialLayout(
                                    VK_IMAGE_LAYOUT_UNDEFINED
                            );

            imageInfo.extent()
                    .width(
                            TARGET_WIDTH
                    )
                    .height(
                            TARGET_HEIGHT
                    )
                    .depth(
                            1
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

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_CREATE_PRIVATE_IMAGE",
                        "vkCreateImage failed with VkResult "
                                + result
                );
            }

            image =
                    imagePointer.get(
                            0
                    );

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
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    );

            VkMemoryAllocateInfo allocation =
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
                            allocation,
                            null,
                            memoryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_ALLOCATE_PRIVATE_IMAGE",
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            memory =
                    memoryPointer.get(
                            0
                    );

            result =
                    vkBindImageMemory(
                            device,
                            image,
                            memory,
                            0L
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_BIND_PRIVATE_IMAGE",
                        "vkBindImageMemory failed with VkResult "
                                + result
                );
            }

            privateTargetEverCreated =
                    true;
        }
    }

    private void createCommandResources() {
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

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_CREATE_COMMAND_POOL",
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

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_ALLOCATE_COMMAND_BUFFER",
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

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_CREATE_FENCE",
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

    private void ensureStateBuffer() {
        if (stateBuffer != NULL
                && stateBufferMemory != NULL) {
            return;
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo =
                    VkBufferCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .size(
                                    STATE_BUFFER_BYTES
                            )
                            .usage(
                                    VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                            | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                            )
                            .sharingMode(
                                    VK_SHARING_MODE_EXCLUSIVE
                            );

            LongBuffer bufferPointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateBuffer(
                            device,
                            bufferInfo,
                            null,
                            bufferPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_CREATE_STATE_BUFFER",
                        "vkCreateBuffer failed with VkResult "
                                + result
                );
            }

            stateBuffer =
                    bufferPointer.get(
                            0
                    );

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(
                            stack
                    );

            vkGetBufferMemoryRequirements(
                    device,
                    stateBuffer,
                    requirements
            );

            stateBufferMemoryTypeIndex =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    );

            VkMemoryAllocateInfo allocation =
                    VkMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    stateBufferMemoryTypeIndex
                            );

            LongBuffer memoryPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkAllocateMemory(
                            device,
                            allocation,
                            null,
                            memoryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_ALLOCATE_STATE_BUFFER",
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            stateBufferMemory =
                    memoryPointer.get(
                            0
                    );

            result =
                    vkBindBufferMemory(
                            device,
                            stateBuffer,
                            stateBufferMemory,
                            0L
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_BIND_STATE_BUFFER",
                        "vkBindBufferMemory failed with VkResult "
                                + result
                );
            }

            stateBufferEverCreated =
                    true;
        }
    }

    private int findMemoryType(
            int memoryTypeBits,
            int requiredFlags
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
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
                boolean supported =
                        (memoryTypeBits
                                & (1 << index))
                                != 0;

                int flags =
                        properties.memoryTypes(
                                index
                        ).propertyFlags();

                if (supported
                        && (flags & requiredFlags)
                        == requiredFlags) {
                    return index;
                }
            }
        }

        throw new VulkanProbeException(
                "GATE10_FIND_MEMORY_TYPE",
                "No device-local memory type is available."
        );
    }

    private void submit(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            int result =
                    vkResetCommandBuffer(
                            commandBuffer,
                            0
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RESET_COMMAND_BUFFER",
                        "vkResetCommandBuffer failed with VkResult "
                                + result
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

            result =
                    vkBeginCommandBuffer(
                            commandBuffer,
                            beginInfo
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_BEGIN_COMMAND_BUFFER",
                        "vkBeginCommandBuffer failed with VkResult "
                                + result
                );
            }

            VkImageMemoryBarrier.Buffer barriers =
                    VkImageMemoryBarrier.calloc(
                            1,
                            stack
                    );

            barriers.get(
                    0
            )
                    .sType$Default()
                    .srcAccessMask(
                            imageInitialized
                                    ? VK_ACCESS_TRANSFER_WRITE_BIT
                                    : 0
                    )
                    .dstAccessMask(
                            VK_ACCESS_TRANSFER_WRITE_BIT
                    )
                    .oldLayout(
                            imageInitialized
                                    ? VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL
                                    : VK_IMAGE_LAYOUT_UNDEFINED
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

            barriers.get(
                    0
            ).subresourceRange()
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
                    imageInitialized
                            ? VK_PIPELINE_STAGE_TRANSFER_BIT
                            : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0,
                    null,
                    null,
                    barriers
            );

            VkClearColorValue clearColor =
                    VkClearColorValue.calloc(
                            stack
                    );

            float red =
                    domain == VulkanGate10DynamicOwnershipContract.Domain.ENTITY
                            ? 1.0f
                            : 0.0f;

            float green =
                    domain == VulkanGate10DynamicOwnershipContract.Domain.PARTICLE
                            ? 1.0f
                            : 0.0f;

            float blue =
                    domain == VulkanGate10DynamicOwnershipContract.Domain.HUD
                            ? 1.0f
                            : domain
                            == VulkanGate10DynamicOwnershipContract.Domain.SCREEN
                            ? 0.5f
                            : 0.0f;

            clearColor.float32(
                    0,
                    red
            );
            clearColor.float32(
                    1,
                    green
            );
            clearColor.float32(
                    2,
                    blue
            );
            clearColor.float32(
                    3,
                    1.0f
            );

            VkImageSubresourceRange.Buffer ranges =
                    VkImageSubresourceRange.calloc(
                            1,
                            stack
                    );

            ranges.get(
                    0
            )
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

            vkCmdClearColorImage(
                    commandBuffer,
                    image,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    clearColor,
                    ranges
            );

            result =
                    vkEndCommandBuffer(
                            commandBuffer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_END_COMMAND_BUFFER",
                        "vkEndCommandBuffer failed with VkResult "
                                + result
                );
            }

            result =
                    vkResetFences(
                            device,
                            fence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_RESET_FENCE",
                        "vkResetFences failed with VkResult "
                                + result
                );
            }

            PointerBuffer commandBuffers =
                    stack.pointers(
                            commandBuffer.address()
                    );

            VkSubmitInfo.Buffer submits =
                    VkSubmitInfo.calloc(
                            1,
                            stack
                    );

            submits.get(
                    0
            )
                    .sType$Default()
                    .pCommandBuffers(
                            commandBuffers
                    );

            result =
                    vkQueueSubmit(
                            graphicsQueue,
                            submits,
                            fence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_QUEUE_SUBMIT",
                        "vkQueueSubmit failed with VkResult "
                                + result
                );
            }

            imageInitialized =
                    true;
            inFlight =
                    true;
            inFlightStatePacket =
                    false;
            inFlightDomain =
                    domain;
            submittedDomains.add(
                    domain
            );
            submissionCount++;
        }
    }

    private void submitStatePacket(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            int result =
                    vkResetCommandBuffer(
                            commandBuffer,
                            0
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_STATE_RESET_COMMAND_BUFFER",
                        "vkResetCommandBuffer failed with VkResult "
                                + result
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

            result =
                    vkBeginCommandBuffer(
                            commandBuffer,
                            beginInfo
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_STATE_BEGIN_COMMAND_BUFFER",
                        "vkBeginCommandBuffer failed with VkResult "
                                + result
                );
            }

            Minecraft minecraft =
                    Minecraft.getInstance();

            int framebufferWidth =
                    Math.max(
                            1,
                            minecraft.getWindow()
                                    .getWidth()
                    );

            int framebufferHeight =
                    Math.max(
                            1,
                            minecraft.getWindow()
                                    .getHeight()
                    );

            int flags =
                    stateFlags(
                            domain
                    );

            int blendSrc =
                    usesAlphaBlend(
                            domain
                    )
                            ? VK_BLEND_FACTOR_SRC_ALPHA
                            : VK_BLEND_FACTOR_ONE;

            int blendDst =
                    usesAlphaBlend(
                            domain
                    )
                            ? VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA
                            : VK_BLEND_FACTOR_ZERO;

            ByteBuffer packet =
                    stack.malloc(
                            STATE_PACKET_BYTES
                    );

            packet.putInt(
                    STATE_PACKET_MAGIC
            );
            packet.putInt(
                    domain.ordinal()
            );
            packet.putInt(
                    framebufferWidth
            );
            packet.putInt(
                    framebufferHeight
            );
            packet.putInt(
                    flags
            );
            packet.putInt(
                    blendSrc
            );
            packet.putInt(
                    blendDst
            );
            packet.putInt(
                    0
            );
            packet.flip();

            long packetOffset =
                    (long) domain.ordinal()
                            * STATE_PACKET_BYTES;

            vkCmdUpdateBuffer(
                    commandBuffer,
                    stateBuffer,
                    packetOffset,
                    packet
            );

            VkBufferMemoryBarrier.Buffer bufferBarriers =
                    VkBufferMemoryBarrier.calloc(
                            1,
                            stack
                    );

            bufferBarriers.get(
                    0
            )
                    .sType$Default()
                    .srcAccessMask(
                            VK_ACCESS_TRANSFER_WRITE_BIT
                    )
                    .dstAccessMask(
                            VK_ACCESS_MEMORY_READ_BIT
                    )
                    .srcQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .dstQueueFamilyIndex(
                            VK_QUEUE_FAMILY_IGNORED
                    )
                    .buffer(
                            stateBuffer
                    )
                    .offset(
                            packetOffset
                    )
                    .size(
                            STATE_PACKET_BYTES
                    );

            vkCmdPipelineBarrier(
                    commandBuffer,
                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
                    0,
                    null,
                    bufferBarriers,
                    null
            );

            result =
                    vkEndCommandBuffer(
                            commandBuffer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_STATE_END_COMMAND_BUFFER",
                        "vkEndCommandBuffer failed with VkResult "
                                + result
                );
            }

            result =
                    vkResetFences(
                            device,
                            fence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_STATE_RESET_FENCE",
                        "vkResetFences failed with VkResult "
                                + result
                );
            }

            PointerBuffer commandBuffers =
                    stack.pointers(
                            commandBuffer.address()
                    );

            VkSubmitInfo.Buffer submits =
                    VkSubmitInfo.calloc(
                            1,
                            stack
                    );

            submits.get(
                    0
            )
                    .sType$Default()
                    .pCommandBuffers(
                            commandBuffers
                    );

            result =
                    vkQueueSubmit(
                            graphicsQueue,
                            submits,
                            fence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_STATE_QUEUE_SUBMIT",
                        "vkQueueSubmit failed with VkResult "
                                + result
                );
            }

            lastStatePacketFramebufferWidth =
                    framebufferWidth;
            lastStatePacketFramebufferHeight =
                    framebufferHeight;
            lastStatePacketFlags =
                    flags;
            lastStatePacketBlendSrcFactor =
                    blendSrc;
            lastStatePacketBlendDstFactor =
                    blendDst;

            inFlight =
                    true;
            inFlightStatePacket =
                    true;
            inFlightDomain =
                    domain;
            statePacketSubmittedDomains.add(
                    domain
            );
            statePacketSubmissionCount++;
        }
    }

    private static int stateFlags(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        return switch (domain) {
            case ENTITY ->
                    STATE_DEPTH_TEST
                            | STATE_DEPTH_WRITE
                            | STATE_WORLD_SPACE
                            | STATE_CULL_DISABLED;

            case PARTICLE ->
                    STATE_DEPTH_TEST
                            | STATE_ALPHA_BLEND
                            | STATE_WORLD_SPACE
                            | STATE_CULL_DISABLED;

            case HUD, SCREEN ->
                    STATE_ALPHA_BLEND
                            | STATE_SCREEN_SPACE
                            | STATE_CULL_DISABLED;
        };
    }

    private static boolean usesAlphaBlend(
            VulkanGate10DynamicOwnershipContract.Domain domain
    ) {
        return domain
                != VulkanGate10DynamicOwnershipContract.Domain.ENTITY;
    }

    private void pollCompletion() {
        if (!inFlight
                || fence == NULL) {
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
            throw new VulkanProbeException(
                    "GATE10_FENCE_STATUS",
                    "vkGetFenceStatus failed with VkResult "
                            + status
            );
        }

        completeInFlightProof();
    }

    private void completeInFlightProof() {
        if (!inFlight) {
            return;
        }

        if (inFlightStatePacket) {
            if (inFlightDomain != null) {
                statePacketCompletedDomains.add(
                        inFlightDomain
                );
            }

            statePacketCompletionCount++;

            if (statePacketCompletedDomains.size()
                    == VulkanGate10DynamicOwnershipContract.Domain
                    .values().length) {
                statePacketProofRetired =
                        true;
            }
        } else {
            if (inFlightDomain != null) {
                completedDomains.add(
                        inFlightDomain
                );
            }

            completionCount++;

            if (completedDomains.size()
                    == VulkanGate10DynamicOwnershipContract.Domain
                    .values().length) {
                proofRetired =
                        true;
            }
        }

        inFlight =
                false;
        inFlightStatePacket =
                false;
        inFlightDomain =
                null;
    }

    private void fail(
            Throwable throwable
    ) {
        failureCount++;
        disabledAfterFailure =
                true;
        lastFailure =
                throwable.getClass()
                        .getName()
                        + ": "
                        + String.valueOf(
                        throwable.getMessage()
                );

        enrich();
    }

    private void enrich() {
        report.addProperty(
                "gate10DynamicPrecommitInstalled",
                true
        );
        report.addProperty(
                "gate10DynamicPrecommitMode",
                "REAL_PRODUCTION_SEAM_GPU_CLEAR_PLUS_DEVICE_LOCAL_STATE_PACKET_REPLICATION"
        );
        report.addProperty(
                "gate10DynamicPrecommitTargetWidth",
                TARGET_WIDTH
        );
        report.addProperty(
                "gate10DynamicPrecommitTargetHeight",
                TARGET_HEIGHT
        );
        report.addProperty(
                "gate10DynamicPrecommitInitialized",
                initialized
        );
        report.addProperty(
                "gate10DynamicPrecommitDeviceLocalImage",
                image != NULL
                        && memory != NULL
        );
        report.addProperty(
                "gate10DynamicPrecommitPrivateTargetEverCreated",
                privateTargetEverCreated
        );
        report.addProperty(
                "gate10DynamicPrecommitMemoryTypeIndex",
                memoryTypeIndex
        );
        report.addProperty(
                "gate10DynamicPrecommitOfferCount",
                offerCount
        );
        report.addProperty(
                "gate10DynamicPrecommitNonRenderThreadRejectCount",
                nonRenderThreadRejectCount
        );
        report.addProperty(
                "gate10DynamicPrecommitDuplicateDomainSkipCount",
                duplicateDomainSkipCount
        );
        report.addProperty(
                "gate10DynamicPrecommitBusySkipCount",
                busySkipCount
        );
        report.addProperty(
                "gate10DynamicPrecommitSubmissionCount",
                submissionCount
        );
        report.addProperty(
                "gate10DynamicPrecommitCompletionCount",
                completionCount
        );
        report.addProperty(
                "gate10DynamicPrecommitFencePollCount",
                fencePollCount
        );
        report.addProperty(
                "gate10DynamicPrecommitFenceNotReadyCount",
                fenceNotReadyCount
        );
        report.addProperty(
                "gate10DynamicPrecommitFailureCount",
                failureCount
        );
        report.addProperty(
                "gate10DynamicPrecommitDisabledAfterFailure",
                disabledAfterFailure
        );
        report.addProperty(
                "gate10DynamicPrecommitEntitySubmitted",
                submittedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.ENTITY
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitParticleSubmitted",
                submittedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.PARTICLE
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitHudSubmitted",
                submittedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.HUD
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitScreenSubmitted",
                submittedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.SCREEN
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitEntityCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.ENTITY
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitParticleCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.PARTICLE
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitHudCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.HUD
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitScreenCompleted",
                completedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.SCREEN
                )
        );
        report.addProperty(
                "gate10DynamicPrecommitAllDomainsVerified",
                completedDomains.size()
                        == VulkanGate10DynamicOwnershipContract.Domain
                        .values().length
                        && failureCount == 0L
        );
        report.addProperty(
                "gate10DynamicPrecommitProofRetired",
                proofRetired
        );
        report.addProperty(
                "gate10DynamicPrecommitPrivateTargetOnly",
                true
        );
        report.addProperty(
                "gate10DynamicPrecommitMutatesOpenGlDraws",
                false
        );
        report.addProperty(
                "gate10DynamicPrecommitVisibleOwnership",
                false
        );
        report.addProperty(
                "gate10DynamicPrecommitGameplayFenceWait",
                false
        );
        report.addProperty(
                "gate10DynamicPrecommitGameplayQueueWaitIdle",
                false
        );
        report.addProperty(
                "gate10DynamicPrecommitShutdownFenceWaitCount",
                shutdownFenceWaitCount
        );

        report.addProperty(
                "gate10DynamicStatePacketInstalled",
                true
        );
        report.addProperty(
                "gate10DynamicStatePacketMode",
                "PER_DOMAIN_DEVICE_LOCAL_GPU_STATE_PACKET_PRECOMMIT"
        );
        report.addProperty(
                "gate10DynamicStatePacketBytes",
                STATE_PACKET_BYTES
        );
        report.addProperty(
                "gate10DynamicStateBufferBytes",
                STATE_BUFFER_BYTES
        );
        report.addProperty(
                "gate10DynamicStateBufferLive",
                stateBuffer != NULL
                        && stateBufferMemory != NULL
        );
        report.addProperty(
                "gate10DynamicStateBufferEverCreated",
                stateBufferEverCreated
        );
        report.addProperty(
                "gate10DynamicStateBufferDeviceLocalMemoryTypeIndex",
                stateBufferMemoryTypeIndex
        );
        report.addProperty(
                "gate10DynamicStatePacketOfferCount",
                statePacketOfferCount
        );
        report.addProperty(
                "gate10DynamicStatePacketDuplicateDomainSkipCount",
                statePacketDuplicateDomainSkipCount
        );
        report.addProperty(
                "gate10DynamicStatePacketBusySkipCount",
                statePacketBusySkipCount
        );
        report.addProperty(
                "gate10DynamicStatePacketSubmissionCount",
                statePacketSubmissionCount
        );
        report.addProperty(
                "gate10DynamicStatePacketCompletionCount",
                statePacketCompletionCount
        );
        report.addProperty(
                "gate10DynamicStatePacketEntityCompleted",
                statePacketCompletedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.ENTITY
                )
        );
        report.addProperty(
                "gate10DynamicStatePacketParticleCompleted",
                statePacketCompletedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.PARTICLE
                )
        );
        report.addProperty(
                "gate10DynamicStatePacketHudCompleted",
                statePacketCompletedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.HUD
                )
        );
        report.addProperty(
                "gate10DynamicStatePacketScreenCompleted",
                statePacketCompletedDomains.contains(
                        VulkanGate10DynamicOwnershipContract.Domain.SCREEN
                )
        );
        report.addProperty(
                "gate10DynamicStatePacketAllDomainsVerified",
                statePacketCompletedDomains.size()
                        == VulkanGate10DynamicOwnershipContract.Domain
                        .values().length
                        && failureCount == 0L
        );
        report.addProperty(
                "gate10DynamicStatePacketProofRetired",
                statePacketProofRetired
        );
        report.addProperty(
                "gate10DynamicStatePacketLastFramebufferWidth",
                lastStatePacketFramebufferWidth
        );
        report.addProperty(
                "gate10DynamicStatePacketLastFramebufferHeight",
                lastStatePacketFramebufferHeight
        );
        report.addProperty(
                "gate10DynamicStatePacketLastFlags",
                lastStatePacketFlags
        );
        report.addProperty(
                "gate10DynamicStatePacketLastBlendSrcFactor",
                lastStatePacketBlendSrcFactor
        );
        report.addProperty(
                "gate10DynamicStatePacketLastBlendDstFactor",
                lastStatePacketBlendDstFactor
        );
        report.addProperty(
                "gate10DynamicStatePacketEntityProfile",
                "WORLD_DEPTH_TEST_WRITE_OPAQUE_CULL_NONE"
        );
        report.addProperty(
                "gate10DynamicStatePacketParticleProfile",
                "WORLD_DEPTH_TEST_NO_WRITE_ALPHA_BLEND_CULL_NONE"
        );
        report.addProperty(
                "gate10DynamicStatePacketHudProfile",
                "SCREEN_NO_DEPTH_ALPHA_BLEND_CULL_NONE"
        );
        report.addProperty(
                "gate10DynamicStatePacketScreenProfile",
                "SCREEN_NO_DEPTH_ALPHA_BLEND_CULL_NONE"
        );
        report.addProperty(
                "gate10DynamicStatePacketGpuWriteCommand",
                "vkCmdUpdateBuffer"
        );
        report.addProperty(
                "gate10DynamicStatePacketGpuReadableBarrier",
                true
        );
        report.addProperty(
                "gate10DynamicStatePacketMutatesOpenGlDraws",
                false
        );
        report.addProperty(
                "gate10DynamicStatePacketVisibleOwnership",
                false
        );
        report.addProperty(
                "gate10DynamicStatePacketGameplayFenceWait",
                false
        );
        report.addProperty(
                "gate10DynamicStatePacketGameplayQueueWaitIdle",
                false
        );

        if (dynamicRasterPrecommit != null) {
            dynamicRasterPrecommit.enrich(
                    report
            );
        } else {
            VulkanGate10DynamicRasterPrecommit.enrichAbsent(
                    report
            );
        }

        if (visibleScreenRehearsal != null) {
            visibleScreenRehearsal.enrich(
                    report
            );
        } else {
            VulkanGate10VisibleScreenRehearsal.enrichAbsent(
                    report
            );
        }

        report.addProperty(
                "gate10DynamicPrecommitNextMilestone",
                "POTATO_ENGINE_GATE10_SCREEN_REAL_CONTENT_REPLICATION"
        );

        if (!lastFailure.isBlank()) {
            report.addProperty(
                    "gate10DynamicPrecommitLastFailure",
                    lastFailure
            );
        }
    }

    private void destroyResources() {
        if (fence != NULL) {
            vkDestroyFence(
                    device,
                    fence,
                    null
            );

            fence =
                    NULL;
        }

        if (commandPool != NULL) {
            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            commandPool =
                    NULL;
            commandBuffer =
                    null;
        }

        if (stateBuffer != NULL) {
            vkDestroyBuffer(
                    device,
                    stateBuffer,
                    null
            );

            stateBuffer =
                    NULL;
        }

        if (stateBufferMemory != NULL) {
            vkFreeMemory(
                    device,
                    stateBufferMemory,
                    null
            );

            stateBufferMemory =
                    NULL;
        }

        if (image != NULL) {
            vkDestroyImage(
                    device,
                    image,
                    null
            );

            image =
                    NULL;
        }

        if (memory != NULL) {
            vkFreeMemory(
                    device,
                    memory,
                    null
            );

            memory =
                    NULL;
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed =
                true;

        if (inFlight
                && fence != NULL) {
            shutdownFenceWaitCount++;

            int result;

            try (MemoryStack stack =
                         MemoryStack.stackPush()) {
                result =
                        vkWaitForFences(
                                device,
                                stack.longs(
                                        fence
                                ),
                                true,
                                Long.MAX_VALUE
                        );
            }

            if (result == VK_SUCCESS) {
                completeInFlightProof();
            } else {
                failureCount++;
                lastFailure =
                        "Shutdown vkWaitForFences returned VkResult "
                                + result;
            }
        }

        if (visibleScreenRehearsal != null) {
            visibleScreenRehearsal.close();
        }

        if (dynamicRasterPrecommit != null) {
            dynamicRasterPrecommit.close();
        }

        destroyResources();

        report.addProperty(
                "gate10DynamicPrecommitClosed",
                true
        );

        enrich();
    }
}
