package dev.ordovicium.potato.render.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * One persistently mapped Vulkan buffer allocation used by the Patch 034
 * geometry-upload prototype.
 *
 * <p>The prototype prefers HOST_VISIBLE | HOST_COHERENT memory and falls back
 * to HOST_VISIBLE memory with an explicit whole-allocation flush.</p>
 */
final class VulkanGeometryBufferAllocation
        implements AutoCloseable {

    private final VkDevice device;

    private final long buffer;
    private final long memory;

    private final long bufferCapacityBytes;
    private final long allocationBytes;

    private final int memoryTypeIndex;
    private final int memoryPropertyFlags;

    private final boolean hostCoherent;

    private long mappedAddress;

    private boolean closed;

    private VulkanGeometryBufferAllocation(
            VkDevice device,
            long buffer,
            long memory,
            long bufferCapacityBytes,
            long allocationBytes,
            int memoryTypeIndex,
            int memoryPropertyFlags,
            boolean hostCoherent,
            long mappedAddress
    ) {
        this.device = device;
        this.buffer = buffer;
        this.memory = memory;
        this.bufferCapacityBytes = bufferCapacityBytes;
        this.allocationBytes = allocationBytes;
        this.memoryTypeIndex = memoryTypeIndex;
        this.memoryPropertyFlags = memoryPropertyFlags;
        this.hostCoherent = hostCoherent;
        this.mappedAddress = mappedAddress;
    }

    static VulkanGeometryBufferAllocation create(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            long capacityBytes,
            int usageFlags,
            String label
    ) {
        if (device == null) {
            throw failure(
                    "CREATE_" + label + "_BUFFER",
                    "Logical device is unavailable."
            );
        }

        if (physicalDevice == null) {
            throw failure(
                    "CREATE_" + label + "_BUFFER",
                    "Physical device is unavailable."
            );
        }

        if (capacityBytes <= 0L) {
            throw failure(
                    "CREATE_" + label + "_BUFFER",
                    "Buffer capacity must be positive."
            );
        }

        long buffer = NULL;
        long memory = NULL;
        long mappedAddress = NULL;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo =
                    VkBufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .size(capacityBytes)
                            .usage(usageFlags)
                            .sharingMode(
                                    VK_SHARING_MODE_EXCLUSIVE
                            );

            LongBuffer bufferPointer =
                    stack.mallocLong(1);

            int result =
                    vkCreateBuffer(
                            device,
                            bufferInfo,
                            null,
                            bufferPointer
                    );

            if (result != VK_SUCCESS) {
                throw failure(
                        "CREATE_" + label + "_BUFFER",
                        "vkCreateBuffer failed with VkResult "
                                + result
                );
            }

            buffer =
                    bufferPointer.get(0);

            if (buffer == NULL) {
                throw failure(
                        "CREATE_" + label + "_BUFFER",
                        "vkCreateBuffer returned NULL."
                );
            }

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(stack);

            vkGetBufferMemoryRequirements(
                    device,
                    buffer,
                    requirements
            );

            MemoryTypeSelection selection =
                    findHostVisibleMemoryType(
                            physicalDevice,
                            requirements.memoryTypeBits(),
                            stack
                    );

            VkMemoryAllocateInfo allocationInfo =
                    VkMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    selection.index()
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

            if (result != VK_SUCCESS) {
                throw failure(
                        "ALLOCATE_" + label + "_MEMORY",
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            memory =
                    memoryPointer.get(0);

            if (memory == NULL) {
                throw failure(
                        "ALLOCATE_" + label + "_MEMORY",
                        "vkAllocateMemory returned NULL."
                );
            }

            result =
                    vkBindBufferMemory(
                            device,
                            buffer,
                            memory,
                            0L
                    );

            if (result != VK_SUCCESS) {
                throw failure(
                        "BIND_" + label + "_BUFFER_MEMORY",
                        "vkBindBufferMemory failed with VkResult "
                                + result
                );
            }

            PointerBuffer mappedPointer =
                    stack.mallocPointer(1);

            result =
                    vkMapMemory(
                            device,
                            memory,
                            0L,
                            requirements.size(),
                            0,
                            mappedPointer
                    );

            if (result != VK_SUCCESS) {
                throw failure(
                        "MAP_" + label + "_BUFFER_MEMORY",
                        "vkMapMemory failed with VkResult "
                                + result
                );
            }

            mappedAddress =
                    mappedPointer.get(0);

            if (mappedAddress == NULL) {
                throw failure(
                        "MAP_" + label + "_BUFFER_MEMORY",
                        "vkMapMemory returned NULL."
                );
            }

            return new VulkanGeometryBufferAllocation(
                    device,
                    buffer,
                    memory,
                    capacityBytes,
                    requirements.size(),
                    selection.index(),
                    selection.propertyFlags(),
                    selection.hostCoherent(),
                    mappedAddress
            );
        } catch (Throwable throwable) {
            if (mappedAddress != NULL
                    && memory != NULL) {
                vkUnmapMemory(
                        device,
                        memory
                );
            }

            if (buffer != NULL) {
                vkDestroyBuffer(
                        device,
                        buffer,
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

    synchronized void upload(
            ByteBuffer source
    ) {
        if (closed) {
            throw failure(
                    "UPLOAD_VULKAN_GEOMETRY_BUFFER",
                    "Geometry buffer allocation is closed."
            );
        }

        if (source == null) {
            throw failure(
                    "UPLOAD_VULKAN_GEOMETRY_BUFFER",
                    "Source ByteBuffer is null."
            );
        }

        int byteCount =
                source.remaining();

        if (byteCount <= 0) {
            return;
        }

        if ((long) byteCount
                > bufferCapacityBytes) {
            throw failure(
                    "UPLOAD_VULKAN_GEOMETRY_BUFFER",
                    "Upload size "
                            + byteCount
                            + " exceeds buffer capacity "
                            + bufferCapacityBytes
                            + "."
            );
        }

        long sourceAddress =
                memAddress(
                        source
                );

        memCopy(
                sourceAddress,
                mappedAddress,
                byteCount
        );

        if (!hostCoherent) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMappedMemoryRange.Buffer range =
                        VkMappedMemoryRange.calloc(
                                1,
                                stack
                        );

                range
                        .get(0)
                        .sType$Default()
                        .memory(memory)
                        .offset(0L)
                        .size(VK_WHOLE_SIZE);

                int result =
                        vkFlushMappedMemoryRanges(
                                device,
                                range
                        );

                if (result != VK_SUCCESS) {
                    throw failure(
                            "FLUSH_VULKAN_GEOMETRY_BUFFER",
                            "vkFlushMappedMemoryRanges failed with VkResult "
                                    + result
                    );
                }
            }
        }

        validateSample(
                source,
                byteCount
        );
    }

    private void validateSample(
            ByteBuffer source,
            int byteCount
    ) {
        if (byteCount <= 0) {
            return;
        }

        int first =
                0;

        int middle =
                byteCount / 2;

        int last =
                byteCount - 1;

        validateByte(
                source,
                first
        );

        if (middle != first
                && middle != last) {
            validateByte(
                    source,
                    middle
            );
        }

        if (last != first) {
            validateByte(
                    source,
                    last
            );
        }
    }

    private void validateByte(
            ByteBuffer source,
            int relativeIndex
    ) {
        byte expected =
                source.get(
                        source.position()
                                + relativeIndex
                );

        byte actual =
                memGetByte(
                        mappedAddress
                                + relativeIndex
                );

        if (expected != actual) {
            throw failure(
                    "VALIDATE_VULKAN_GEOMETRY_BUFFER_UPLOAD",
                    "Mapped Vulkan buffer sample mismatch at byte "
                            + relativeIndex
                            + "."
            );
        }
    }

    long buffer() {
        return buffer;
    }

    long bufferCapacityBytes() {
        return bufferCapacityBytes;
    }

    long allocationBytes() {
        return allocationBytes;
    }

    int memoryTypeIndex() {
        return memoryTypeIndex;
    }

    int memoryPropertyFlags() {
        return memoryPropertyFlags;
    }

    boolean hostCoherent() {
        return hostCoherent;
    }

    boolean alive() {
        return !closed
                && buffer != NULL
                && memory != NULL
                && mappedAddress != NULL;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        if (mappedAddress != NULL) {
            vkUnmapMemory(
                    device,
                    memory
            );

            mappedAddress = NULL;
        }

        if (buffer != NULL) {
            vkDestroyBuffer(
                    device,
                    buffer,
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
    }

    private static MemoryTypeSelection
    findHostVisibleMemoryType(
            VkPhysicalDevice physicalDevice,
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

        int coherentFlags =
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                        | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;

        for (int index = 0;
             index < properties.memoryTypeCount();
             index++) {

            boolean allowed =
                    (memoryTypeBits
                            & (1 << index))
                            != 0;

            int flags =
                    properties
                            .memoryTypes(index)
                            .propertyFlags();

            if (allowed
                    && (flags & coherentFlags)
                    == coherentFlags) {
                return new MemoryTypeSelection(
                        index,
                        flags,
                        true
                );
            }
        }

        for (int index = 0;
             index < properties.memoryTypeCount();
             index++) {

            boolean allowed =
                    (memoryTypeBits
                            & (1 << index))
                            != 0;

            int flags =
                    properties
                            .memoryTypes(index)
                            .propertyFlags();

            if (allowed
                    && (flags
                    & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)
                    != 0) {
                return new MemoryTypeSelection(
                        index,
                        flags,
                        false
                );
            }
        }

        throw failure(
                "SELECT_VULKAN_GEOMETRY_MEMORY_TYPE",
                "No compatible HOST_VISIBLE memory type was found."
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

    private record MemoryTypeSelection(
            int index,
            int propertyFlags,
            boolean hostCoherent
    ) {
    }
}
