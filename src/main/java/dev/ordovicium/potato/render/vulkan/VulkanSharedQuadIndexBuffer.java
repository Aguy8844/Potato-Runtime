package dev.ordovicium.potato.render.vulkan;

import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Immutable shared sequential QUADS index atlas for section draws.
 *
 * <p>Older Potato generations grew one shared index VkBuffer on demand and
 * immediately destroyed the previous allocation. That was safe only while the
 * renderer blocked after every validation batch. Once section rendering became
 * non-blocking, a growth could retire an index buffer that was still referenced
 * by an in-flight command buffer.</p>
 *
 * <p>The production path therefore never mutates or replaces an index buffer
 * after publication. Two tiny immutable atlases cover the complete accepted
 * section range:</p>
 *
 * <ul>
 *   <li>UINT16 for normal section meshes up to 65,532 vertices.</li>
 *   <li>UINT32 for unusually large/modded section meshes up to the current
 *       8 MiB BLOCK-stream ceiling (262,144 vertices at 32 bytes/vertex).</li>
 * </ul>
 *
 * <p>Each atlas is created at most once, lazily, and lives until renderer
 * teardown. A visible frame can therefore never observe an index allocation
 * being replaced underneath it.</p>
 */
final class VulkanSharedQuadIndexBuffer
        implements AutoCloseable {

    private static final int UINT16_MAX_VERTEX_COUNT =
            0xFFFC;

    private static final int MAX_SUPPORTED_VERTEX_COUNT =
            (8 * 1024 * 1024) / 32;

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;

    private VulkanGeometryBufferAllocation
            uint16Allocation;

    private VulkanGeometryBufferAllocation
            uint32Allocation;

    private int allocationBuildCount;
    private int uint16BuildCount;
    private int uint32BuildCount;

    private int observedMaxVertexCount;
    private int observedMaxIndexCount;

    VulkanSharedQuadIndexBuffer(
            VkDevice device,
            VkPhysicalDevice physicalDevice
    ) {
        this.device = device;
        this.physicalDevice = physicalDevice;
    }

    Binding ensureForMaxVertexCount(
            int requestedVertexCount
    ) {
        validateVertexCount(
                requestedVertexCount
        );

        observedMaxVertexCount =
                Math.max(
                        observedMaxVertexCount,
                        requestedVertexCount
                );

        observedMaxIndexCount =
                Math.max(
                        observedMaxIndexCount,
                        indexCountForVertexCount(
                                requestedVertexCount
                        )
                );

        if (requestedVertexCount
                <= UINT16_MAX_VERTEX_COUNT) {
            boolean created =
                    ensureUint16Atlas();

            return binding(
                    uint16Allocation,
                    VK_INDEX_TYPE_UINT16,
                    created
            );
        }

        boolean created =
                ensureUint32Atlas();

        return binding(
                uint32Allocation,
                VK_INDEX_TYPE_UINT32,
                created
        );
    }

    int indexCountForVertexCount(
            int vertexCount
    ) {
        validateVertexCount(
                vertexCount
        );

        return vertexCount
                / 4
                * 6;
    }

    int rebuildCount() {
        /*
         * Keep the historical telemetry field name for report compatibility.
         * In Patch 072 this counts immutable atlas constructions, not
         * replacement rebuilds. The expected value is 1 for normal worlds and
         * at most 2 if a >65k-vertex section is encountered.
         */
        return allocationBuildCount;
    }

    int maxVertexCount() {
        return observedMaxVertexCount;
    }

    int maxIndexCount() {
        return observedMaxIndexCount;
    }

    boolean immutableAtlas() {
        return true;
    }

    int uint16BuildCount() {
        return uint16BuildCount;
    }

    int uint32BuildCount() {
        return uint32BuildCount;
    }

    int publishedAllocationCount() {
        int count =
                0;

        if (uint16Allocation != null) {
            count++;
        }

        if (uint32Allocation != null) {
            count++;
        }

        return count;
    }

    long immutableAtlasBytes() {
        long bytes =
                0L;

        if (uint16Allocation != null) {
            bytes +=
                    uint16Allocation
                            .bufferCapacityBytes();
        }

        if (uint32Allocation != null) {
            bytes +=
                    uint32Allocation
                            .bufferCapacityBytes();
        }

        return bytes;
    }

    private boolean ensureUint16Atlas() {
        if (uint16Allocation != null
                && uint16Allocation.alive()) {
            return false;
        }

        uint16Allocation =
                createAtlas(
                        UINT16_MAX_VERTEX_COUNT,
                        Short.BYTES,
                        VK_INDEX_TYPE_UINT16,
                        "SHARED_SECTION_QUAD_INDEX_UINT16"
                );

        uint16BuildCount++;
        allocationBuildCount++;

        return true;
    }

    private boolean ensureUint32Atlas() {
        if (uint32Allocation != null
                && uint32Allocation.alive()) {
            return false;
        }

        uint32Allocation =
                createAtlas(
                        MAX_SUPPORTED_VERTEX_COUNT,
                        Integer.BYTES,
                        VK_INDEX_TYPE_UINT32,
                        "SHARED_SECTION_QUAD_INDEX_UINT32"
                );

        uint32BuildCount++;
        allocationBuildCount++;

        return true;
    }

    private VulkanGeometryBufferAllocation createAtlas(
            int vertexCapacity,
            int bytesPerIndex,
            int vkIndexType,
            String label
    ) {
        int indexCount =
                indexCountForVertexCount(
                        vertexCapacity
                );

        long requestedBytes =
                (long) indexCount
                        * bytesPerIndex;

        VulkanGeometryBufferAllocation allocation =
                VulkanGeometryBufferAllocation.create(
                        device,
                        physicalDevice,
                        requestedBytes,
                        VK_BUFFER_USAGE_INDEX_BUFFER_BIT,
                        label
                );

        ByteBuffer indices =
                memAlloc(
                        Math.toIntExact(
                                requestedBytes
                        )
                ).order(
                        ByteOrder.nativeOrder()
                );

        try {
            int quadCount =
                    vertexCapacity
                            / 4;

            for (int quad = 0;
                 quad < quadCount;
                 quad++) {

                int base =
                        quad * 4;

                if (vkIndexType
                        == VK_INDEX_TYPE_UINT16) {
                    indices.putShort(
                            (short) base
                    );
                    indices.putShort(
                            (short) (base + 1)
                    );
                    indices.putShort(
                            (short) (base + 2)
                    );

                    indices.putShort(
                            (short) (base + 2)
                    );
                    indices.putShort(
                            (short) (base + 3)
                    );
                    indices.putShort(
                            (short) base
                    );
                } else {
                    indices.putInt(
                            base
                    );
                    indices.putInt(
                            base + 1
                    );
                    indices.putInt(
                            base + 2
                    );

                    indices.putInt(
                            base + 2
                    );
                    indices.putInt(
                            base + 3
                    );
                    indices.putInt(
                            base
                    );
                }
            }

            indices.flip();

            allocation.upload(
                    indices
            );
        } catch (RuntimeException | Error throwable) {
            allocation.close();
            throw throwable;
        } finally {
            memFree(
                    indices
            );
        }

        return allocation;
    }

    private static Binding binding(
            VulkanGeometryBufferAllocation allocation,
            int vkIndexType,
            boolean builtNow
    ) {
        if (allocation == null
                || !allocation.alive()) {
            throw new VulkanProbeException(
                    "SHARED_SECTION_QUAD_INDEX_BUFFER",
                    "Immutable shared quad index atlas is unavailable."
            );
        }

        return new Binding(
                allocation.buffer(),
                vkIndexType,
                allocation.memoryTypeIndex(),
                allocation.hostCoherent(),
                builtNow
        );
    }

    private static void validateVertexCount(
            int vertexCount
    ) {
        if (vertexCount <= 0
                || (vertexCount & 3) != 0
                || vertexCount
                > MAX_SUPPORTED_VERTEX_COUNT) {
            throw new VulkanProbeException(
                    "SHARED_SECTION_QUAD_INDEX_BUFFER",
                    "QUADS vertex count outside immutable atlas range: "
                            + vertexCount
                            + " / "
                            + MAX_SUPPORTED_VERTEX_COUNT
            );
        }
    }

    @Override
    public void close() {
        if (uint16Allocation != null) {
            uint16Allocation.close();
            uint16Allocation = null;
        }

        if (uint32Allocation != null) {
            uint32Allocation.close();
            uint32Allocation = null;
        }
    }

    record Binding(
            long buffer,
            int vkIndexType,
            int memoryTypeIndex,
            boolean hostCoherent,
            boolean rebuilt
    ) {
    }
}
