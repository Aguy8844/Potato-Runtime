package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Owns per-frame-in-flight host/GPU synchronization and command buffers.
 *
 * <p>The probe is single-threaded, so one resettable command pool is sufficient.
 * The production multi-threaded renderer will move to command pools per
 * recording thread.</p>
 */
final class VulkanFrameRing implements AutoCloseable {
    static final int MAX_FRAMES_IN_FLIGHT = 2;

    private final VkDevice device;
    private final JsonObject report;

    private long commandPool;
    private Frame[] frames;

    private VulkanFrameRing(
            VkDevice device,
            JsonObject report,
            long commandPool,
            Frame[] frames
    ) {
        this.device = device;
        this.report = report;
        this.commandPool = commandPool;
        this.frames = frames;
    }

    static VulkanFrameRing create(
            VkDevice device,
            int graphicsQueueFamily,
            MemoryStack stack,
            JsonObject report
    ) {
        report.addProperty("stage", "CREATE_FRAME_RING");

        VkCommandPoolCreateInfo poolInfo =
                VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(
                                VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                        )
                        .queueFamilyIndex(graphicsQueueFamily);

        LongBuffer poolPointer = stack.mallocLong(1);

        int result = vkCreateCommandPool(
                device,
                poolInfo,
                null,
                poolPointer
        );

        if (result != VK_SUCCESS) {
            throw failure(
                    "CREATE_FRAME_RING",
                    "vkCreateCommandPool failed with VkResult " + result
            );
        }

        long commandPool = poolPointer.get(0);

        VkCommandBufferAllocateInfo allocateInfo =
                VkCommandBufferAllocateInfo.calloc(stack)
                        .sType$Default()
                        .commandPool(commandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(MAX_FRAMES_IN_FLIGHT);

        PointerBuffer commandPointers =
                stack.mallocPointer(MAX_FRAMES_IN_FLIGHT);

        result = vkAllocateCommandBuffers(
                device,
                allocateInfo,
                commandPointers
        );

        if (result != VK_SUCCESS) {
            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            throw failure(
                    "CREATE_FRAME_RING",
                    "vkAllocateCommandBuffers failed with VkResult " + result
            );
        }

        Frame[] frames =
                new Frame[MAX_FRAMES_IN_FLIGHT];

        try {
            for (int index = 0; index < MAX_FRAMES_IN_FLIGHT; index++) {
                VkCommandBuffer commandBuffer =
                        new VkCommandBuffer(
                                commandPointers.get(index),
                                device
                        );

                long imageAvailable =
                        createSemaphore(
                                device,
                                stack
                        );

                long fence =
                        createSignaledFence(
                                device,
                                stack
                        );

                frames[index] =
                        new Frame(
                                commandBuffer,
                                imageAvailable,
                                fence
                        );
            }
        } catch (Throwable throwable) {
            for (Frame frame : frames) {
                if (frame != null) {
                    frame.destroy(device);
                }
            }

            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            throw throwable;
        }

        report.addProperty(
                "maxFramesInFlight",
                MAX_FRAMES_IN_FLIGHT
        );
        report.addProperty(
                "frameCommandBuffersAllocated",
                MAX_FRAMES_IN_FLIGHT
        );
        report.addProperty(
                "frameAcquireSemaphoresCreated",
                MAX_FRAMES_IN_FLIGHT
        );
        report.addProperty(
                "frameFencesCreated",
                MAX_FRAMES_IN_FLIGHT
        );

        return new VulkanFrameRing(
                device,
                report,
                commandPool,
                frames
        );
    }

    Frame frame(int index) {
        return frames[index];
    }

    int size() {
        return frames.length;
    }

    private static long createSemaphore(
            VkDevice device,
            MemoryStack stack
    ) {
        VkSemaphoreCreateInfo info =
                VkSemaphoreCreateInfo.calloc(stack)
                        .sType$Default();

        LongBuffer pointer = stack.mallocLong(1);

        int result = vkCreateSemaphore(
                device,
                info,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw failure(
                    "CREATE_FRAME_RING",
                    "vkCreateSemaphore failed with VkResult " + result
            );
        }

        return pointer.get(0);
    }

    private static long createSignaledFence(
            VkDevice device,
            MemoryStack stack
    ) {
        VkFenceCreateInfo info =
                VkFenceCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT);

        LongBuffer pointer = stack.mallocLong(1);

        int result = vkCreateFence(
                device,
                info,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw failure(
                    "CREATE_FRAME_RING",
                    "vkCreateFence failed with VkResult " + result
            );
        }

        return pointer.get(0);
    }

    @Override
    public void close() {
        if (frames != null) {
            for (Frame frame : frames) {
                if (frame != null) {
                    frame.destroy(device);
                }
            }

            frames = null;

            report.addProperty(
                    "frameSynchronizationDestroyed",
                    true
            );
        }

        if (commandPool != NULL) {
            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            commandPool = NULL;

            report.addProperty(
                    "frameCommandPoolDestroyed",
                    true
            );
        }
    }

    record Frame(
            VkCommandBuffer commandBuffer,
            long imageAvailableSemaphore,
            long inFlightFence
    ) {
        void destroy(VkDevice device) {
            if (imageAvailableSemaphore != NULL) {
                vkDestroySemaphore(
                        device,
                        imageAvailableSemaphore,
                        null
                );
            }

            if (inFlightFence != NULL) {
                vkDestroyFence(
                        device,
                        inFlightFence,
                        null
                );
            }
        }
    }

    private static VulkanProbeException failure(
            String stage,
            String message
    ) {
        return new VulkanProbeException(stage, message);
    }
}