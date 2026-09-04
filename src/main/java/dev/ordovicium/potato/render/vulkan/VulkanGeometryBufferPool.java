package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Reuse pool for persistently mapped Vulkan geometry allocations.
 *
 * <p>Minecraft churns section VertexBuffer objects aggressively while flying
 * through a 24+ chunk world. Creating and destroying one VkBuffer/VkDeviceMemory
 * pair for every section upload turns that lifecycle churn into render-thread
 * driver work. The pool keeps a bounded set of exact-capacity allocations alive
 * and hands them to later section resources without changing mesh contents or
 * visual fidelity.</p>
 */
final class VulkanGeometryBufferPool
        implements AutoCloseable {

    private static final long MIB =
            1024L * 1024L;

    private static final long DEFAULT_CACHE_BYTES =
            defaultCacheBytes();

    private static final long MAX_CACHE_BYTES =
            Math.max(
                    0L,
                    Math.min(
                            512L * MIB,
                            Long.getLong(
                                    "potato.vulkan.geometry.poolCacheBytes",
                                    DEFAULT_CACHE_BYTES
                            )
                    )
            );

    private final org.lwjgl.vulkan.VkDevice device;
    private final org.lwjgl.vulkan.VkPhysicalDevice physicalDevice;

    private final Map<
            PoolKey,
            ArrayDeque<VulkanGeometryBufferAllocation>>
            free =
            new HashMap<>();

    private long acquireCount;
    private long releaseCount;
    private long reuseHitCount;
    private long reuseMissCount;
    private long createdAllocationCount;
    private long cachedReleaseCount;
    private long destroyedInsteadOfCachedCount;
    private long invalidCachedAllocationCount;

    private long submissionPinAcquireCount;
    private long submissionPinReleaseCount;
    private long submissionCopyOnWriteCount;
    private long deferredRetireCount;
    private long deferredCloseCount;

    private int pinnedAllocationCount;
    private int peakPinnedAllocationCount;

    private long cachedBytes;
    private long peakCachedBytes;

    private int inUseAllocationCount;
    private int peakInUseAllocationCount;
    private int cachedAllocationCount;
    private int peakCachedAllocationCount;

    private boolean closed;

    VulkanGeometryBufferPool(
            org.lwjgl.vulkan.VkDevice device,
            org.lwjgl.vulkan.VkPhysicalDevice physicalDevice
    ) {
        this.device =
                device;

        this.physicalDevice =
                physicalDevice;
    }

    synchronized VulkanGeometryBufferAllocation acquireVertex(
            long capacityBytes
    ) {
        return acquire(
                capacityBytes,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "VULKAN_GEOMETRY_VERTEX_POOL"
        );
    }

    synchronized VulkanGeometryBufferAllocation acquireIndex(
            long capacityBytes
    ) {
        return acquire(
                capacityBytes,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                "VULKAN_GEOMETRY_INDEX_POOL"
        );
    }

    synchronized void releaseVertex(
            VulkanGeometryBufferAllocation allocation
    ) {
        release(
                allocation,
                VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        );
    }

    synchronized void releaseIndex(
            VulkanGeometryBufferAllocation allocation
    ) {
        release(
                allocation,
                VK_BUFFER_USAGE_INDEX_BUFFER_BIT
                        | VK_BUFFER_USAGE_TRANSFER_DST_BIT
        );
    }

    synchronized void onSubmissionPinAcquired(
            int allocationCount
    ) {
        if (allocationCount <= 0) {
            return;
        }

        submissionPinAcquireCount++;
        pinnedAllocationCount +=
                allocationCount;

        peakPinnedAllocationCount =
                Math.max(
                        peakPinnedAllocationCount,
                        pinnedAllocationCount
                );
    }

    synchronized void onSubmissionPinReleased(
            int allocationCount
    ) {
        if (allocationCount <= 0) {
            return;
        }

        submissionPinReleaseCount++;
        pinnedAllocationCount =
                Math.max(
                        0,
                        pinnedAllocationCount
                                - allocationCount
                );
    }

    synchronized void onSubmissionCopyOnWrite() {
        submissionCopyOnWriteCount++;
    }

    synchronized void onDeferredRetire() {
        deferredRetireCount++;
    }

    synchronized void onDeferredClose() {
        deferredCloseCount++;
    }

    synchronized long createdAllocationCount() {
        return createdAllocationCount;
    }

    synchronized long reuseHitCount() {
        return reuseHitCount;
    }

    synchronized long cachedBytes() {
        return cachedBytes;
    }

    synchronized void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        report.addProperty(
                "geometryBufferPoolInstalled",
                true
        );
        report.addProperty(
                "geometryBufferPoolMode",
                "FRAME_STABLE_EXACT_CAPACITY_REUSE"
        );
        report.addProperty(
                "geometryBufferPoolMaxCacheBytes",
                MAX_CACHE_BYTES
        );
        report.addProperty(
                "geometryBufferPoolMaxCacheMiB",
                MAX_CACHE_BYTES / MIB
        );
        report.addProperty(
                "geometryBufferPoolAcquireCount",
                acquireCount
        );
        report.addProperty(
                "geometryBufferPoolReleaseCount",
                releaseCount
        );
        report.addProperty(
                "geometryBufferPoolReuseHitCount",
                reuseHitCount
        );
        report.addProperty(
                "geometryBufferPoolReuseMissCount",
                reuseMissCount
        );
        report.addProperty(
                "geometryBufferPoolCreatedAllocationCount",
                createdAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolCachedReleaseCount",
                cachedReleaseCount
        );
        report.addProperty(
                "geometryBufferPoolDestroyedInsteadOfCachedCount",
                destroyedInsteadOfCachedCount
        );
        report.addProperty(
                "geometryBufferPoolInvalidCachedAllocationCount",
                invalidCachedAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolSubmissionPinAcquireCount",
                submissionPinAcquireCount
        );
        report.addProperty(
                "geometryBufferPoolSubmissionPinReleaseCount",
                submissionPinReleaseCount
        );
        report.addProperty(
                "geometryBufferPoolSubmissionCopyOnWriteCount",
                submissionCopyOnWriteCount
        );
        report.addProperty(
                "geometryBufferPoolDeferredRetireCount",
                deferredRetireCount
        );
        report.addProperty(
                "geometryBufferPoolDeferredCloseCount",
                deferredCloseCount
        );
        report.addProperty(
                "geometryBufferPoolPinnedAllocationCount",
                pinnedAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolPeakPinnedAllocationCount",
                peakPinnedAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolFrameStableLifetimeEnabled",
                true
        );
        report.addProperty(
                "geometryBufferPoolCachedBytes",
                cachedBytes
        );
        report.addProperty(
                "geometryBufferPoolPeakCachedBytes",
                peakCachedBytes
        );
        report.addProperty(
                "geometryBufferPoolInUseAllocationCount",
                inUseAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolPeakInUseAllocationCount",
                peakInUseAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolCachedAllocationCount",
                cachedAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolPeakCachedAllocationCount",
                peakCachedAllocationCount
        );
        report.addProperty(
                "geometryBufferPoolActualVkBufferCreateReductionObserved",
                reuseHitCount > 0L
        );
        report.addProperty(
                "geometryBufferPoolClosed",
                closed
        );
    }

    private VulkanGeometryBufferAllocation acquire(
            long capacityBytes,
            int usageFlags,
            String label
    ) {
        if (closed) {
            throw new VulkanProbeException(
                    "ACQUIRE_GEOMETRY_BUFFER_POOL",
                    "Geometry buffer pool is closed."
            );
        }

        acquireCount++;

        PoolKey key =
                new PoolKey(
                        capacityBytes,
                        usageFlags
                );

        ArrayDeque<VulkanGeometryBufferAllocation> bin =
                free.get(
                        key
                );

        while (bin != null
                && !bin.isEmpty()) {
            VulkanGeometryBufferAllocation allocation =
                    bin.removeLast();

            cachedAllocationCount =
                    Math.max(
                            0,
                            cachedAllocationCount - 1
                    );

            cachedBytes =
                    Math.max(
                            0L,
                            cachedBytes
                                    - allocation.allocationBytes()
                    );

            if (allocation.alive()) {
                reuseHitCount++;

                inUseAllocationCount++;
                peakInUseAllocationCount =
                        Math.max(
                                peakInUseAllocationCount,
                                inUseAllocationCount
                        );

                if (bin.isEmpty()) {
                    free.remove(
                            key
                    );
                }

                return allocation;
            }

            invalidCachedAllocationCount++;

            try {
                allocation.close();
            } catch (Throwable ignored) {
                // The caller still gets a fresh allocation below.
            }
        }

        if (bin != null
                && bin.isEmpty()) {
            free.remove(
                    key
            );
        }

        reuseMissCount++;

        VulkanGeometryBufferAllocation allocation =
                VulkanGeometryBufferAllocation.create(
                        device,
                        physicalDevice,
                        capacityBytes,
                        usageFlags,
                        label
                );

        createdAllocationCount++;

        inUseAllocationCount++;
        peakInUseAllocationCount =
                Math.max(
                        peakInUseAllocationCount,
                        inUseAllocationCount
                );

        return allocation;
    }

    private void release(
            VulkanGeometryBufferAllocation allocation,
            int usageFlags
    ) {
        if (allocation == null) {
            return;
        }

        releaseCount++;

        inUseAllocationCount =
                Math.max(
                        0,
                        inUseAllocationCount - 1
                );

        if (closed
                || !allocation.alive()) {
            destroyedInsteadOfCachedCount++;

            allocation.close();
            return;
        }

        long bytes =
                allocation.allocationBytes();

        if (MAX_CACHE_BYTES <= 0L
                || bytes > MAX_CACHE_BYTES
                || cachedBytes + bytes
                > MAX_CACHE_BYTES) {
            destroyedInsteadOfCachedCount++;

            allocation.close();
            return;
        }

        PoolKey key =
                new PoolKey(
                        allocation.bufferCapacityBytes(),
                        usageFlags
                );

        free.computeIfAbsent(
                key,
                ignored -> new ArrayDeque<>()
        ).addLast(
                allocation
        );

        cachedReleaseCount++;
        cachedAllocationCount++;

        cachedBytes +=
                bytes;

        peakCachedBytes =
                Math.max(
                        peakCachedBytes,
                        cachedBytes
                );

        peakCachedAllocationCount =
                Math.max(
                        peakCachedAllocationCount,
                        cachedAllocationCount
                );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed =
                true;

        for (ArrayDeque<VulkanGeometryBufferAllocation> bin
                : free.values()) {
            while (!bin.isEmpty()) {
                VulkanGeometryBufferAllocation allocation =
                        bin.removeLast();

                try {
                    allocation.close();
                } catch (Throwable ignored) {
                    // Shutdown is best-effort; runtime diagnostics own errors.
                }
            }
        }

        free.clear();

        cachedBytes =
                0L;

        cachedAllocationCount =
                0;
    }

    private static long defaultCacheBytes() {
        int processors =
                Math.max(
                        1,
                        Runtime.getRuntime()
                                .availableProcessors()
                );

        long jvmMaxMiB =
                Math.max(
                        1L,
                        Runtime.getRuntime()
                                .maxMemory()
                                / MIB
                );

        if (processors <= 4
                || jvmMaxMiB < 3_072L) {
            return 16L * MIB;
        }

        if (processors <= 8
                || jvmMaxMiB < 4_096L) {
            return 32L * MIB;
        }

        if (processors <= 12
                || jvmMaxMiB < 6_144L) {
            return 64L * MIB;
        }

        return 128L * MIB;
    }

    private record PoolKey(
            long capacityBytes,
            int usageFlags
    ) {
    }
}
