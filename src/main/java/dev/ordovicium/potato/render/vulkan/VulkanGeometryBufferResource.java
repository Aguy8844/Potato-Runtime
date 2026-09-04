package dev.ordovicium.potato.render.vulkan;

import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.lwjgl.system.MemoryUtil.memAlloc;
import static org.lwjgl.system.MemoryUtil.memFree;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Backend-specific Vulkan sidecar for one Minecraft VertexBuffer lifecycle.
 *
 * <p>Patch 071 makes the sidecar frame-stable. A Vulkan submission pins the
 * exact native allocation and upload generation it recorded. If Minecraft
 * rebuilds or closes the same section before the GPU fence completes, the old
 * allocation is retained and the CPU upload path switches to copy-on-write.
 * No VkBuffer that is still referenced by an in-flight command buffer may be
 * overwritten, pooled, or destroyed.</p>
 */
final class VulkanGeometryBufferResource
        implements AutoCloseable {

    private static final long MIN_CAPACITY_BYTES =
            256L;

    private final VulkanGeometryBufferPool pool;

    private VulkanGeometryBufferAllocation
            vertexAllocation;

    private VulkanGeometryBufferAllocation
            indexAllocation;

    /*
     * Only one Vulkan world submission can be in flight today, but a resource
     * may be referenced more than once in a future batch. Keep an explicit pin
     * count so the lifecycle remains correct when that changes.
     */
    private VulkanGeometryBufferAllocation
            pinnedVertexAllocation;

    private VulkanGeometryBufferAllocation
            pinnedIndexAllocation;

    private VulkanGeometryBufferAllocation
            retiredPinnedVertexAllocation;

    private VulkanGeometryBufferAllocation
            retiredPinnedIndexAllocation;

    private int submissionPinCount;
    private long pinnedUploadGeneration;

    private long mirroredUploadGeneration;
    private long lastUploadNanos;

    private boolean lastUploadUsedExplicitIndices;
    private boolean closed;

    VulkanGeometryBufferResource(
            VulkanGeometryBufferPool pool
    ) {
        this.pool = pool;
    }

    synchronized UploadOutcome upload(
            DrawBufferBackendState state,
            DrawGeometryView geometry
    ) {
        if (closed) {
            throw new VulkanProbeException(
                    "UPLOAD_VULKAN_GEOMETRY_RESOURCE",
                    "Geometry resource is closed."
            );
        }

        ByteBuffer vertices =
                geometry.vertexBytes();

        int vertexBytes =
                vertices.remaining();

        if (vertexBytes <= 0) {
            throw new VulkanProbeException(
                    "UPLOAD_VULKAN_GEOMETRY_RESOURCE",
                    "Vertex upload contained no bytes."
            );
        }

        /*
         * The previous generation may already be referenced by the GPU.
         * Never mutate that allocation in-place. Detach it and let the
         * submission fence retire it later.
         */
        prepareForMutation();

        boolean vertexReallocated =
                ensureVertexCapacity(
                        vertexBytes
                );

        vertexAllocation.upload(
                vertices
        );

        ByteBuffer indices =
                geometry.indexBytes();

        int indexBytes =
                indices == null
                        ? 0
                        : indices.remaining();

        boolean indexReallocated =
                false;

        if (indices != null
                && indexBytes > 0) {
            indexReallocated =
                    ensureIndexCapacity(
                            indexBytes
                    );

            indexAllocation.upload(
                    indices
            );
        }

        mirroredUploadGeneration =
                state.uploadGeneration();

        lastUploadNanos =
                System.nanoTime();

        lastUploadUsedExplicitIndices =
                indices != null
                        && indexBytes > 0;

        return new UploadOutcome(
                vertexBytes,
                indexBytes,
                vertexReallocated,
                indexReallocated,
                vertexAllocation.hostCoherent(),
                indexAllocation == null
                        || indexAllocation.hostCoherent(),
                vertexAllocation.memoryTypeIndex(),
                indexAllocation == null
                        ? -1
                        : indexAllocation.memoryTypeIndex()
        );
    }

    synchronized boolean readyFor(
            DrawBufferBackendState state
    ) {
        if (closed
                || vertexAllocation == null
                || !vertexAllocation.alive()) {
            return false;
        }

        if (mirroredUploadGeneration
                != state.uploadGeneration()) {
            return false;
        }

        if (state.explicitIndexBuffer()) {
            return indexAllocation != null
                    && indexAllocation.alive()
                    && lastUploadUsedExplicitIndices;
        }

        return true;
    }

    synchronized boolean pinForSubmission(
            DrawBufferBackendState state,
            SubmissionBinding binding
    ) {
        if (binding == null
                || binding.active()
                || !readyFor(state)) {
            return false;
        }

        if (submissionPinCount == 0) {
            pinnedVertexAllocation =
                    vertexAllocation;

            pinnedIndexAllocation =
                    state.explicitIndexBuffer()
                            ? indexAllocation
                            : null;

            pinnedUploadGeneration =
                    mirroredUploadGeneration;
        } else if (pinnedVertexAllocation
                != vertexAllocation
                || pinnedUploadGeneration
                != mirroredUploadGeneration) {
            /*
             * One world batch may not mix two generations of the same sidecar.
             * Fail open rather than creating an ambiguous lifetime graph.
             */
            return false;
        }

        submissionPinCount++;

        int allocationCount =
                1
                        + (pinnedIndexAllocation == null
                        ? 0
                        : 1);

        pool.onSubmissionPinAcquired(
                allocationCount
        );

        binding.assign(
                this,
                pinnedVertexAllocation.buffer(),
                pinnedIndexAllocation == null
                        ? 0L
                        : pinnedIndexAllocation.buffer(),
                pinnedUploadGeneration,
                state.vertexCount(),
                state.indexCount(),
                allocationCount
        );

        return true;
    }

    synchronized boolean shouldThrottleDynamicUpload(
            long nowNanos,
            long minimumIntervalNanos
    ) {
        return lastUploadNanos != 0L
                && nowNanos - lastUploadNanos
                < minimumIntervalNanos;
    }

    synchronized long mirroredUploadGeneration() {
        return mirroredUploadGeneration;
    }

    synchronized long vertexBuffer() {
        return vertexAllocation == null
                ? 0L
                : vertexAllocation.buffer();
    }

    synchronized long indexBuffer() {
        return indexAllocation == null
                ? 0L
                : indexAllocation.buffer();
    }

    synchronized long vertexCapacityBytes() {
        return vertexAllocation == null
                ? 0L
                : vertexAllocation.bufferCapacityBytes();
    }

    synchronized long indexCapacityBytes() {
        return indexAllocation == null
                ? 0L
                : indexAllocation.bufferCapacityBytes();
    }

    synchronized long allocationBytes() {
        long total = 0L;

        if (vertexAllocation != null) {
            total +=
                    vertexAllocation.allocationBytes();
        }

        if (indexAllocation != null) {
            total +=
                    indexAllocation.allocationBytes();
        }

        if (retiredPinnedVertexAllocation != null) {
            total +=
                    retiredPinnedVertexAllocation
                            .allocationBytes();
        }

        if (retiredPinnedIndexAllocation != null) {
            total +=
                    retiredPinnedIndexAllocation
                            .allocationBytes();
        }

        return total;
    }

    synchronized boolean lastUploadUsedExplicitIndices() {
        return lastUploadUsedExplicitIndices;
    }

    synchronized SequentialIndexBinding
    ensureSequentialQuadIndexBuffer(
            DrawBufferBackendState state
    ) {
        if (closed) {
            throw new VulkanProbeException(
                    "CREATE_SEQUENTIAL_QUAD_INDEX_BUFFER",
                    "Geometry resource is closed."
            );
        }

        if (state.explicitIndexBuffer()) {
            throw new VulkanProbeException(
                    "CREATE_SEQUENTIAL_QUAD_INDEX_BUFFER",
                    "Patch 037 expects Minecraft sequential quad indices."
            );
        }

        if (state.mode()
                != com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) {
            throw new VulkanProbeException(
                    "CREATE_SEQUENTIAL_QUAD_INDEX_BUFFER",
                    "Patch 037 supports QUADS only."
            );
        }

        if (state.indexType()
                != com.mojang.blaze3d.vertex.VertexFormat.IndexType.SHORT) {
            throw new VulkanProbeException(
                    "CREATE_SEQUENTIAL_QUAD_INDEX_BUFFER",
                    "Patch 037 supports SHORT indices only."
            );
        }

        int vertexCount =
                state.vertexCount();

        int expectedIndexCount =
                (vertexCount / 4)
                        * 6;

        if (vertexCount <= 0
                || (vertexCount & 3) != 0
                || vertexCount > 0xFFFF) {
            throw new VulkanProbeException(
                    "CREATE_SEQUENTIAL_QUAD_INDEX_BUFFER",
                    "Invalid UINT16 quad vertex count: "
                            + vertexCount
            );
        }

        if (state.indexCount()
                != expectedIndexCount) {
            throw new VulkanProbeException(
                    "CREATE_SEQUENTIAL_QUAD_INDEX_BUFFER",
                    "Minecraft quad index count mismatch: expected "
                            + expectedIndexCount
                            + ", observed "
                            + state.indexCount()
            );
        }

        int byteCount =
                expectedIndexCount
                        * Short.BYTES;

        prepareForMutation();

        boolean reallocated =
                ensureIndexCapacity(
                        byteCount
                );

        ByteBuffer generated =
                memAlloc(
                        byteCount
                ).order(
                        ByteOrder.nativeOrder()
                );

        try {
            for (int base = 0;
                 base < vertexCount;
                 base += 4) {

                generated.putShort(
                        (short) base
                );
                generated.putShort(
                        (short) (base + 1)
                );
                generated.putShort(
                        (short) (base + 2)
                );

                generated.putShort(
                        (short) (base + 2)
                );
                generated.putShort(
                        (short) (base + 3)
                );
                generated.putShort(
                        (short) base
                );
            }

            generated.flip();

            indexAllocation.upload(
                    generated
            );
        } finally {
            memFree(
                    generated
            );
        }

        return new SequentialIndexBinding(
                indexAllocation.buffer(),
                expectedIndexCount,
                VK_INDEX_TYPE_UINT16,
                byteCount,
                reallocated,
                indexAllocation.hostCoherent(),
                indexAllocation.memoryTypeIndex()
        );
    }

    private void prepareForMutation() {
        if (submissionPinCount <= 0) {
            return;
        }

        boolean copied =
                false;

        if (vertexAllocation != null
                && vertexAllocation
                == pinnedVertexAllocation) {

            if (retiredPinnedVertexAllocation != null
                    && retiredPinnedVertexAllocation
                    != vertexAllocation) {
                throw new VulkanProbeException(
                        "MUTATE_PINNED_VULKAN_GEOMETRY",
                        "More than one retired pinned vertex allocation was requested."
                );
            }

            retiredPinnedVertexAllocation =
                    vertexAllocation;

            vertexAllocation =
                    null;

            copied =
                    true;

            pool.onDeferredRetire();
        }

        if (indexAllocation != null
                && indexAllocation
                == pinnedIndexAllocation) {

            if (retiredPinnedIndexAllocation != null
                    && retiredPinnedIndexAllocation
                    != indexAllocation) {
                throw new VulkanProbeException(
                        "MUTATE_PINNED_VULKAN_GEOMETRY",
                        "More than one retired pinned index allocation was requested."
                );
            }

            retiredPinnedIndexAllocation =
                    indexAllocation;

            indexAllocation =
                    null;

            copied =
                    true;

            pool.onDeferredRetire();
        }

        if (copied) {
            pool.onSubmissionCopyOnWrite();
        }
    }

    private boolean ensureVertexCapacity(
            int requiredBytes
    ) {
        if (vertexAllocation != null
                && vertexAllocation.bufferCapacityBytes()
                >= requiredBytes) {
            return false;
        }

        if (vertexAllocation != null) {
            pool.releaseVertex(
                    vertexAllocation
            );
        }

        vertexAllocation =
                pool.acquireVertex(
                        capacityFor(
                                requiredBytes
                        )
                );

        return true;
    }

    private boolean ensureIndexCapacity(
            int requiredBytes
    ) {
        if (indexAllocation != null
                && indexAllocation.bufferCapacityBytes()
                >= requiredBytes) {
            return false;
        }

        if (indexAllocation != null) {
            pool.releaseIndex(
                    indexAllocation
            );
        }

        indexAllocation =
                pool.acquireIndex(
                        capacityFor(
                                requiredBytes
                        )
                );

        return true;
    }

    private static long capacityFor(
            int requiredBytes
    ) {
        long capacity =
                MIN_CAPACITY_BYTES;

        while (capacity < requiredBytes) {
            capacity <<= 1;

            if (capacity <= 0L) {
                throw new VulkanProbeException(
                        "SIZE_VULKAN_GEOMETRY_BUFFER",
                        "Geometry buffer capacity overflow."
                );
            }
        }

        return capacity;
    }

    private synchronized void releaseSubmissionPin(
            SubmissionBinding binding
    ) {
        if (binding == null
                || binding.owner != this
                || !binding.active) {
            return;
        }

        int allocationCount =
                binding.allocationCount;

        binding.clear();

        submissionPinCount =
                Math.max(
                        0,
                        submissionPinCount - 1
                );

        pool.onSubmissionPinReleased(
                allocationCount
        );

        if (submissionPinCount > 0) {
            return;
        }

        if (retiredPinnedIndexAllocation != null) {
            pool.releaseIndex(
                    retiredPinnedIndexAllocation
            );
            retiredPinnedIndexAllocation =
                    null;
        }

        if (retiredPinnedVertexAllocation != null) {
            pool.releaseVertex(
                    retiredPinnedVertexAllocation
            );
            retiredPinnedVertexAllocation =
                    null;
        }

        /*
         * A close that happened while this generation was in flight could not
         * return the pinned current allocations earlier.
         */
        if (closed) {
            if (indexAllocation != null) {
                pool.releaseIndex(
                        indexAllocation
                );
                indexAllocation =
                        null;
            }

            if (vertexAllocation != null) {
                pool.releaseVertex(
                        vertexAllocation
                );
                vertexAllocation =
                        null;
            }
        }

        pinnedVertexAllocation =
                null;

        pinnedIndexAllocation =
                null;

        pinnedUploadGeneration =
                0L;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed =
                true;

        if (submissionPinCount > 0) {
            pool.onDeferredClose();

            /*
             * Current allocations not referenced by the in-flight batch can be
             * returned immediately. Pinned generations remain alive until the
             * Vulkan fence harvest calls SubmissionBinding.release().
             */
            if (indexAllocation != null
                    && indexAllocation
                    != pinnedIndexAllocation) {
                pool.releaseIndex(
                        indexAllocation
                );
                indexAllocation =
                        null;
            }

            if (vertexAllocation != null
                    && vertexAllocation
                    != pinnedVertexAllocation) {
                pool.releaseVertex(
                        vertexAllocation
                );
                vertexAllocation =
                        null;
            }

            return;
        }

        if (indexAllocation != null) {
            pool.releaseIndex(
                    indexAllocation
            );
            indexAllocation = null;
        }

        if (vertexAllocation != null) {
            pool.releaseVertex(
                    vertexAllocation
            );
            vertexAllocation = null;
        }
    }

    static final class SubmissionBinding {
        private VulkanGeometryBufferResource owner;
        private long vertexBuffer;
        private long indexBuffer;
        private long uploadGeneration;
        private int vertexCount;
        private int indexCount;
        private int allocationCount;
        private boolean active;

        SubmissionBinding() {
        }

        private void assign(
                VulkanGeometryBufferResource owner,
                long vertexBuffer,
                long indexBuffer,
                long uploadGeneration,
                int vertexCount,
                int indexCount,
                int allocationCount
        ) {
            if (active) {
                throw new VulkanProbeException(
                        "ASSIGN_VULKAN_GEOMETRY_SUBMISSION_BINDING",
                        "Submission binding scratch is already active."
                );
            }

            this.owner =
                    owner;

            this.vertexBuffer =
                    vertexBuffer;

            this.indexBuffer =
                    indexBuffer;

            this.uploadGeneration =
                    uploadGeneration;

            this.vertexCount =
                    vertexCount;

            this.indexCount =
                    indexCount;

            this.allocationCount =
                    allocationCount;

            this.active =
                    true;
        }

        boolean active() {
            return active;
        }

        long vertexBuffer() {
            return vertexBuffer;
        }

        long indexBuffer() {
            return indexBuffer;
        }

        long uploadGeneration() {
            return uploadGeneration;
        }

        int vertexCount() {
            return vertexCount;
        }

        int indexCount() {
            return indexCount;
        }

        void release() {
            VulkanGeometryBufferResource currentOwner =
                    owner;

            if (currentOwner != null) {
                currentOwner.releaseSubmissionPin(
                        this
                );
            }
        }

        private void clear() {
            owner =
                    null;

            vertexBuffer =
                    0L;

            indexBuffer =
                    0L;

            uploadGeneration =
                    0L;

            vertexCount =
                    0;

            indexCount =
                    0;

            allocationCount =
                    0;

            active =
                    false;
        }
    }

    record SequentialIndexBinding(
            long buffer,
            int indexCount,
            int vkIndexType,
            int bytes,
            boolean reallocated,
            boolean hostCoherent,
            int memoryTypeIndex
    ) {
    }

    record UploadOutcome(
            int vertexBytes,
            int indexBytes,
            boolean vertexReallocated,
            boolean indexReallocated,
            boolean vertexHostCoherent,
            boolean indexHostCoherent,
            int vertexMemoryTypeIndex,
            int indexMemoryTypeIndex
    ) {
    }
}
