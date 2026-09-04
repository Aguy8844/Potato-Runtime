package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;

import java.util.HashSet;
import java.util.IdentityHashMap;

/**
 * Zero-native-memory shadow planner for the next Potato geometry engine.
 *
 * <p>The current renderer still owns one Vulkan sidecar allocation per
 * Minecraft section. Patch 072 does not cut over that storage model blindly.
 * Instead it runs a low-cost buddy allocator against the exact real upload and
 * close stream and reports how a single device-local geometry arena would have
 * behaved.</p>
 *
 * <p>No VkBuffer is allocated here. No visible draw is changed. The planner is
 * deliberately telemetry-only so the next patch can size the real arena from
 * measured peak residency, internal fragmentation, relocation pressure and
 * allocation failures rather than guesses.</p>
 */
final class VulkanSectionArenaPlanner
        implements AutoCloseable {

    private static final long MIB =
            1024L * 1024L;

    private static final int MIN_BLOCK_SHIFT =
            8;

    private static final long MIN_BLOCK_BYTES =
            1L << MIN_BLOCK_SHIFT;

    private static final long DEFAULT_ARENA_BYTES =
            defaultArenaBytes();

    private static final long ARENA_BYTES =
            normalizeArenaBytes(
                    Long.getLong(
                            "potato.vulkan.geometry.arenaPlannerBytes",
                            DEFAULT_ARENA_BYTES
                    )
            );

    private final IdentityHashMap<
            DrawBufferBackendState,
            Slot>
            slots =
            new IdentityHashMap<>();

    private final HashSet<Long>[] freeByOrder;

    private final int maxOrder;

    private long observedUploadCount;
    private long observedCloseCount;

    private long newSlotAllocationCount;
    private long inPlaceUpdateCount;
    private long relocationCount;
    private long allocationFailureCount;

    private long splitCount;
    private long mergeCount;

    private long requestedLiveBytes;
    private long reservedLiveBytes;

    private long peakRequestedLiveBytes;
    private long peakReservedLiveBytes;

    private long cumulativeRequestedUploadBytes;

    private int peakLiveSlotCount;

    private long forcedReleaseAtCloseCount;

    private boolean closed;

    @SuppressWarnings("unchecked")
    VulkanSectionArenaPlanner() {
        long blocks =
                ARENA_BYTES
                        / MIN_BLOCK_BYTES;

        this.maxOrder =
                Long.numberOfTrailingZeros(
                        Long.highestOneBit(
                                blocks
                        )
                );

        this.freeByOrder =
                new HashSet[
                        maxOrder + 1
                ];

        for (int order = 0;
             order <= maxOrder;
             order++) {
            freeByOrder[order] =
                    new HashSet<>();
        }

        freeByOrder[maxOrder]
                .add(
                        0L
                );
    }

    synchronized void onUpload(
            DrawBufferBackendState state,
            int requestedBytes
    ) {
        if (closed
                || state == null
                || requestedBytes <= 0) {
            return;
        }

        observedUploadCount++;

        cumulativeRequestedUploadBytes +=
                requestedBytes;

        long alignedRequested =
                align(
                        requestedBytes,
                        MIN_BLOCK_BYTES
                );

        int requiredOrder =
                orderForBytes(
                        alignedRequested
                );

        Slot existing =
                slots.get(
                        state
                );

        if (existing != null
                && existing.order
                >= requiredOrder) {
            requestedLiveBytes -=
                    existing.requestedBytes;

            existing.requestedBytes =
                    requestedBytes;

            requestedLiveBytes +=
                    requestedBytes;

            inPlaceUpdateCount++;

            updatePeaks();
            return;
        }

        Allocation allocation =
                acquireBlock(
                        requiredOrder
                );

        if (allocation == null) {
            allocationFailureCount++;
            return;
        }

        if (existing != null) {
            /*
             * Model frame-stable copy-on-write: the replacement must exist
             * before the old slot may retire. Count that transient overlap in
             * the peak recommendation so the real arena is not sized only for
             * steady state.
             */
            peakRequestedLiveBytes =
                    Math.max(
                            peakRequestedLiveBytes,
                            requestedLiveBytes
                                    + requestedBytes
                    );

            peakReservedLiveBytes =
                    Math.max(
                            peakReservedLiveBytes,
                            reservedLiveBytes
                                    + blockBytes(
                                            allocation.order
                                    )
                    );

            slots.remove(
                    state
            );

            requestedLiveBytes -=
                    existing.requestedBytes;

            reservedLiveBytes -=
                    blockBytes(
                            existing.order
                    );

            releaseBlock(
                    existing.offset,
                    existing.order
            );

            relocationCount++;
        }

        Slot slot =
                new Slot(
                        allocation.offset,
                        allocation.order,
                        requestedBytes
                );

        slots.put(
                state,
                slot
        );

        requestedLiveBytes +=
                requestedBytes;

        reservedLiveBytes +=
                blockBytes(
                        allocation.order
                );

        newSlotAllocationCount++;

        updatePeaks();
    }

    synchronized void onClose(
            DrawBufferBackendState state
    ) {
        if (state == null) {
            return;
        }

        observedCloseCount++;

        Slot slot =
                slots.remove(
                        state
                );

        if (slot == null) {
            return;
        }

        requestedLiveBytes -=
                slot.requestedBytes;

        reservedLiveBytes -=
                blockBytes(
                        slot.order
                );

        releaseBlock(
                slot.offset,
                slot.order
        );
    }

    synchronized void enrich(
            JsonObject report
    ) {
        if (report == null) {
            return;
        }

        report.addProperty(
                "geometryArenaPlannerInstalled",
                true
        );
        report.addProperty(
                "geometryArenaPlannerShadowOnly",
                true
        );
        report.addProperty(
                "geometryArenaPlannerAllocator",
                "BUDDY_256B_LOG2"
        );
        report.addProperty(
                "geometryArenaPlannerVirtualArenaBytes",
                ARENA_BYTES
        );
        report.addProperty(
                "geometryArenaPlannerVirtualArenaMiB",
                ARENA_BYTES / MIB
        );
        report.addProperty(
                "geometryArenaPlannerObservedUploadCount",
                observedUploadCount
        );
        report.addProperty(
                "geometryArenaPlannerObservedCloseCount",
                observedCloseCount
        );
        report.addProperty(
                "geometryArenaPlannerNewSlotAllocationCount",
                newSlotAllocationCount
        );
        report.addProperty(
                "geometryArenaPlannerInPlaceUpdateCount",
                inPlaceUpdateCount
        );
        report.addProperty(
                "geometryArenaPlannerRelocationCount",
                relocationCount
        );
        report.addProperty(
                "geometryArenaPlannerAllocationFailureCount",
                allocationFailureCount
        );
        report.addProperty(
                "geometryArenaPlannerBuddySplitCount",
                splitCount
        );
        report.addProperty(
                "geometryArenaPlannerBuddyMergeCount",
                mergeCount
        );
        report.addProperty(
                "geometryArenaPlannerCurrentLiveSlotCount",
                slots.size()
        );
        report.addProperty(
                "geometryArenaPlannerPeakLiveSlotCount",
                peakLiveSlotCount
        );
        report.addProperty(
                "geometryArenaPlannerCurrentRequestedLiveBytes",
                requestedLiveBytes
        );
        report.addProperty(
                "geometryArenaPlannerCurrentReservedLiveBytes",
                reservedLiveBytes
        );
        report.addProperty(
                "geometryArenaPlannerPeakRequestedLiveBytes",
                peakRequestedLiveBytes
        );
        report.addProperty(
                "geometryArenaPlannerPeakReservedLiveBytes",
                peakReservedLiveBytes
        );
        report.addProperty(
                "geometryArenaPlannerPeakInternalFragmentationBytes",
                Math.max(
                        0L,
                        peakReservedLiveBytes
                                - peakRequestedLiveBytes
                )
        );
        report.addProperty(
                "geometryArenaPlannerCumulativeRequestedUploadBytes",
                cumulativeRequestedUploadBytes
        );
        report.addProperty(
                "geometryArenaPlannerRecommendedArenaBytes",
                recommendedArenaBytes()
        );
        report.addProperty(
                "geometryArenaPlannerRecommendedArenaMiB",
                recommendedArenaBytes()
                        / MIB
        );
        report.addProperty(
                "geometryArenaPlannerIndirectCommandBytesAtPeak",
                (long) peakLiveSlotCount
                        * 20L
        );
        report.addProperty(
                "geometryArenaPlannerForcedReleaseAtCloseCount",
                forcedReleaseAtCloseCount
        );
        report.addProperty(
                "geometryArenaPlannerReadyForDeviceLocalPrototype",
                observedUploadCount > 0L
                        && peakLiveSlotCount > 0
        );
        report.addProperty(
                "geometryArenaPlannerClosed",
                closed
        );
    }

    private Allocation acquireBlock(
            int requiredOrder
    ) {
        if (requiredOrder
                > maxOrder) {
            return null;
        }

        int sourceOrder =
                requiredOrder;

        while (sourceOrder
                <= maxOrder
                && freeByOrder[sourceOrder]
                .isEmpty()) {
            sourceOrder++;
        }

        if (sourceOrder
                > maxOrder) {
            return null;
        }

        long offset =
                takeAny(
                        freeByOrder[sourceOrder]
                );

        while (sourceOrder
                > requiredOrder) {
            sourceOrder--;

            long halfBytes =
                    blockBytes(
                            sourceOrder
                    );

            long buddyOffset =
                    offset
                            + halfBytes;

            freeByOrder[sourceOrder]
                    .add(
                            buddyOffset
                    );

            splitCount++;
        }

        return new Allocation(
                offset,
                requiredOrder
        );
    }

    private void releaseBlock(
            long offset,
            int order
    ) {
        long currentOffset =
                offset;

        int currentOrder =
                order;

        while (currentOrder
                < maxOrder) {
            long blockBytes =
                    blockBytes(
                            currentOrder
                    );

            long buddyOffset =
                    currentOffset
                            ^ blockBytes;

            if (!freeByOrder[currentOrder]
                    .remove(
                            buddyOffset
                    )) {
                break;
            }

            currentOffset =
                    Math.min(
                            currentOffset,
                            buddyOffset
                    );

            currentOrder++;
            mergeCount++;
        }

        freeByOrder[currentOrder]
                .add(
                        currentOffset
                );
    }

    private int orderForBytes(
            long bytes
    ) {
        long blocks =
                Math.max(
                        1L,
                        (bytes
                                + MIN_BLOCK_BYTES
                                - 1L)
                                / MIN_BLOCK_BYTES
                );

        long blockCount =
                1L;

        int order =
                0;

        while (blockCount
                < blocks) {
            blockCount <<= 1;
            order++;
        }

        return order;
    }

    private long blockBytes(
            int order
    ) {
        return MIN_BLOCK_BYTES
                << order;
    }

    private void updatePeaks() {
        peakRequestedLiveBytes =
                Math.max(
                        peakRequestedLiveBytes,
                        requestedLiveBytes
                );

        peakReservedLiveBytes =
                Math.max(
                        peakReservedLiveBytes,
                        reservedLiveBytes
                );

        peakLiveSlotCount =
                Math.max(
                        peakLiveSlotCount,
                        slots.size()
                );
    }

    private long recommendedArenaBytes() {
        if (peakReservedLiveBytes <= 0L) {
            return 0L;
        }

        long withHeadroom =
                peakReservedLiveBytes
                        + peakReservedLiveBytes
                        / 4L;

        return normalizeArenaBytes(
                withHeadroom
        );
    }

    private static long takeAny(
            HashSet<Long> set
    ) {
        Long value =
                set.iterator()
                        .next();

        set.remove(
                value
        );

        return value;
    }

    private static long align(
            long value,
            long alignment
    ) {
        return (
                value
                        + alignment
                        - 1L
        ) & -alignment;
    }

    private static long defaultArenaBytes() {
        long jvmMaxMiB =
                Math.max(
                        1L,
                        Runtime.getRuntime()
                                .maxMemory()
                                / MIB
                );

        if (jvmMaxMiB < 3_072L) {
            return 128L * MIB;
        }

        if (jvmMaxMiB < 4_096L) {
            return 256L * MIB;
        }

        if (jvmMaxMiB < 6_144L) {
            return 512L * MIB;
        }

        return 1_024L * MIB;
    }

    private static long normalizeArenaBytes(
            long requestedBytes
    ) {
        long bounded =
                Math.max(
                        32L * MIB,
                        Math.min(
                                2_048L * MIB,
                                requestedBytes
                        )
                );

        long blocks =
                Math.max(
                        1L,
                        (bounded
                                + MIN_BLOCK_BYTES
                                - 1L)
                                / MIN_BLOCK_BYTES
                );

        long powerOfTwoBlocks =
                1L;

        while (powerOfTwoBlocks
                < blocks) {
            powerOfTwoBlocks <<= 1;
        }

        return powerOfTwoBlocks
                * MIN_BLOCK_BYTES;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        forcedReleaseAtCloseCount +=
                slots.size();

        slots.clear();

        requestedLiveBytes =
                0L;

        reservedLiveBytes =
                0L;

        for (HashSet<Long> freeSet
                : freeByOrder) {
            freeSet.clear();
        }

        freeByOrder[maxOrder]
                .add(
                        0L
                );
    }

    private static final class Slot {
        private final long offset;
        private final int order;
        private long requestedBytes;

        private Slot(
                long offset,
                int order,
                long requestedBytes
        ) {
            this.offset =
                    offset;
            this.order =
                    order;
            this.requestedBytes =
                    requestedBytes;
        }
    }

    private record Allocation(
            long offset,
            int order
    ) {
    }
}
