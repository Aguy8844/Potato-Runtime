package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.TreeMap;

import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Production staging + transfer foundation for Potato's region-owned Vulkan
 * terrain geometry.
 *
 * <p>Patch 084 turns Patch 083's real DEVICE_LOCAL arena into actual GPU
 * residency. STATIC BLOCK vertex payloads are copied into a persistently
 * mapped staging ring, recorded as VkBufferCopy commands and submitted in
 * bounded batches to the transfer queue that Potato already selected when the
 * Vulkan logical device was created.</p>
 *
 * <p>Gameplay never waits for a transfer fence. The staging ring is split into
 * independent fence-owned segments. A segment is reused only after
 * vkGetFenceStatus reports completion; if all segments are busy, the upload is
 * skipped fail-open instead of blocking the render thread. OpenGL remains the
 * visible SOLID authority in this patch.</p>
 *
 * <p>The arena is intentionally a cache, not a requirement that every loaded
 * section must fit in VRAM forever. When capacity is exhausted, the oldest
 * hidden-stage slot is evicted and may be repopulated by a later generation.
 * The future visible renderer will replace this upload-recency policy with the
 * HOT/WARM/COLD visibility policy and exact frame/generation/camera tokens.</p>
 */
final class VulkanDeviceLocalRegionArena implements AutoCloseable {
    private static final long MIB = 1024L * 1024L;
    private static final long SLOT_ALIGNMENT = 256L;

    private static final long MIN_ARENA_MIB = 32L;
    private static final long MAX_ARENA_MIB = 1024L;
    private static final long MIN_STAGING_MIB = 8L;
    private static final long MAX_STAGING_MIB = 96L;

    private static final int MAX_TRANSFER_SEGMENTS = 3;
    private static final int MAX_COPIES_PER_BATCH = 96;
    private static final long MAX_BYTES_PER_BATCH = 8L * MIB;

    /*
     * Patch 086: completed terrain that was actually visible recently is
     * WARM, not an equally-good eviction victim as never-seen/cold residency.
     */
    private static final long VISIBLE_WARM_NANOS =
            Math.max(
                    0L,
                    Long.getLong(
                            "potato.vulkan.regionVisibleWarmMillis",
                            10000L
                    )
            ) * 1_000_000L;

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final JsonObject report;

    private final IdentityHashMap<Object, Slot> slots =
            new IdentityHashMap<>();
    private final TreeMap<Long, Long> freeRanges =
            new TreeMap<>();
    private final ArrayDeque<ResidencyKey> residencyOrder =
            new ArrayDeque<>();
    private final ArrayList<RetiredRange> retiredRanges =
            new ArrayList<>();

    private long detectedDeviceLocalHeapBytes;
    private long requestedArenaBytes;
    private long arenaBytes;
    private long requestedStagingBytes;
    private long stagingBytes;

    private long arenaBuffer = NULL;
    private long arenaMemory = NULL;
    private int arenaMemoryTypeIndex = -1;
    private int arenaMemoryPropertyFlags;

    private long stagingBuffer = NULL;
    private long stagingMemory = NULL;
    private int stagingMemoryTypeIndex = -1;
    private int stagingMemoryPropertyFlags;
    private long stagingMappedAddress = NULL;
    private ByteBuffer stagingMapped;

    private VkQueue transferQueue;
    private int transferQueueFamilyIndex = -1;
    private int graphicsQueueFamilyIndex = -1;
    private boolean transferQueueDedicated;
    private boolean arenaConcurrentQueueSharing;

    /*
     * Patch 087 owns a tiny hidden graphics proof beside the transfer arena.
     * It consumes the real resident-candidate count but never cancels OpenGL.
     */
    private VulkanRegionIndirectDrawRuntime indirectDrawRuntime;

    private long transferCommandPool = NULL;
    private TransferSegment[] transferSegments =
            new TransferSegment[0];
    private int currentTransferSegment;

    private long observedUploadCount;
    private long stagedUploadCount;
    private long stagedVertexBytes;
    private long oversizeStagingRejectCount;
    private long transferBackpressureRejectCount;
    private long transferRecordFailureCount;

    private long slotAllocationCount;
    private long slotReuseCount;
    private long slotRelocationCount;
    private long slotReleaseCount;
    private long slotAllocationFailureCount;
    private long pressureEvictionCount;
    private long pressureEvictedBytes;
    private long visibleResidencyTouchCount;
    private long warmPressureEvictionSkipCount;
    private long coldPressureEvictionCount;
    private long peakReservedArenaBytes;
    private int peakActiveSlotCount;
    private long uploadGeneration;

    private long gpuCopyCommandCount;
    private long gpuCopyCommandBytes;
    private long gpuCopySubmissionCount;
    private long gpuCopyCompletedSubmissionCount;
    private long gpuCopyCompletedCommandCount;
    private long gpuCopyCompletedBytes;
    private long transferFencePollCount;
    private long transferFenceNotReadyCount;
    private long transferSegmentReuseCount;
    private int peakCopiesPerSubmission;
    private long peakBytesPerSubmission;

    private int arenaAllocationAttemptCount;
    private int arenaAllocationFallbackCount;
    private int stagingAllocationAttemptCount;
    private int stagingAllocationFallbackCount;

    private boolean initialized;
    private boolean deviceLocalBufferCreated;
    private boolean stagingBufferCreated;
    private boolean stagingPersistentlyMapped;
    private boolean transferResourcesCreated;
    private boolean transferDisabledAfterFailure;
    private String lastTransferFailure = "";
    private boolean shutdownQueueWaitIdleUsed;
    private int shutdownQueueWaitIdleResult = Integer.MIN_VALUE;
    private boolean verifiedBeforeClose;
    private boolean disabledAfterFailure;
    private String lastFailure = "";
    private boolean closed;

    VulkanDeviceLocalRegionArena(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            JsonObject report
    ) {
        this.device = device;
        this.physicalDevice = physicalDevice;
        this.report = report;

        try {
            detectedDeviceLocalHeapBytes =
                    detectLargestDeviceLocalHeapBytes();

            attachSelectedTransferQueue();

            long automaticArenaMiB =
                    clamp(
                            detectedDeviceLocalHeapBytes / MIB / 8L,
                            MIN_ARENA_MIB,
                            MAX_ARENA_MIB
                    );

            long configuredArenaMiB =
                    configuredMiB(
                            "potato.vulkan.regionArenaMiB",
                            automaticArenaMiB,
                            MIN_ARENA_MIB,
                            MAX_ARENA_MIB
                    );

            requestedArenaBytes =
                    align(configuredArenaMiB * MIB);

            BufferAllocation arenaAllocation =
                    allocateArenaWithFallback(
                            configuredArenaMiB
                    );

            arenaBuffer = arenaAllocation.buffer();
            arenaMemory = arenaAllocation.memory();
            arenaMemoryTypeIndex = arenaAllocation.memoryTypeIndex();
            arenaMemoryPropertyFlags = arenaAllocation.memoryPropertyFlags();
            arenaBytes = arenaAllocation.sizeBytes();
            deviceLocalBufferCreated = arenaBuffer != NULL;

            long automaticStagingMiB =
                    clamp(
                            Math.max(
                                    MIN_STAGING_MIB,
                                    (arenaBytes / MIB) / 8L
                            ),
                            MIN_STAGING_MIB,
                            MAX_STAGING_MIB
                    );

            long configuredStagingMiB =
                    configuredMiB(
                            "potato.vulkan.stagingRingMiB",
                            automaticStagingMiB,
                            MIN_STAGING_MIB,
                            MAX_STAGING_MIB
                    );

            requestedStagingBytes =
                    align(configuredStagingMiB * MIB);

            BufferAllocation stagingAllocation =
                    allocateStagingWithFallback(
                            configuredStagingMiB
                    );

            stagingBuffer = stagingAllocation.buffer();
            stagingMemory = stagingAllocation.memory();
            stagingMemoryTypeIndex = stagingAllocation.memoryTypeIndex();
            stagingMemoryPropertyFlags = stagingAllocation.memoryPropertyFlags();
            stagingBytes = stagingAllocation.sizeBytes();
            stagingBufferCreated = stagingBuffer != NULL;

            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer mapped = stack.mallocPointer(1);

                int result = vkMapMemory(
                        device,
                        stagingMemory,
                        0L,
                        stagingBytes,
                        0,
                        mapped
                );

                if (result != VK_SUCCESS) {
                    throw new IllegalStateException(
                            "vkMapMemory(staging ring) failed with VkResult "
                                    + result
                    );
                }

                stagingMappedAddress = mapped.get(0);
            }

            if (stagingMappedAddress == NULL) {
                throw new IllegalStateException(
                        "vkMapMemory(staging ring) returned a null address"
                );
            }

            stagingMapped = MemoryUtil.memByteBuffer(
                    stagingMappedAddress,
                    Math.toIntExact(stagingBytes)
            );
            stagingPersistentlyMapped = true;

            createTransferResources();

            indirectDrawRuntime =
                    new VulkanRegionIndirectDrawRuntime(
                            device,
                            physicalDevice,
                            graphicsQueueFamilyIndex,
                            arenaBuffer,
                            report
                    );

            freeRanges.put(0L, arenaBytes);
            initialized = true;
        } catch (Throwable throwable) {
            disabledAfterFailure = true;
            lastFailure = describe(throwable);
            destroyNativeResources();
        }

        enrich();
    }

    synchronized void stage(
            Object owner,
            long sourceGeneration,
            ByteBuffer vertexBytes
    ) {
        observedUploadCount++;

        if (!initialized
                || disabledAfterFailure
                || closed
                || owner == null
                || sourceGeneration <= 0L
                || vertexBytes == null) {
            return;
        }

        int byteCount = vertexBytes.remaining();
        if (byteCount <= 0) {
            return;
        }

        if ((long) byteCount > maximumTransferSegmentBytes()) {
            oversizeStagingRejectCount++;
            return;
        }

        harvestCompletedTransfers();

        if (transferDisabledAfterFailure) {
            return;
        }

        TransferSegment segment =
                acquireWritableSegment(byteCount);

        if (segment == null) {
            transferBackpressureRejectCount++;
            return;
        }

        long requiredCapacity = align(byteCount);
        Slot previous = slots.get(owner);
        Slot slot = previous;
        boolean newAllocation = false;

        /*
         * Do not overwrite a destination range whose previous transfer is
         * still pending/in flight. OpenGL remains authoritative, so skipping a
         * mirror refresh is preferable to a write-after-write lifetime race.
         */
        if (slot != null
                && slot.generation > 0L
                && slot.completedGeneration != slot.generation) {
            transferBackpressureRejectCount++;
            return;
        }

        if (slot != null && slot.capacity >= requiredCapacity) {
            slotReuseCount++;
        } else {
            long offset = allocateWithPressureEviction(
                    requiredCapacity,
                    owner
            );

            if (offset < 0L) {
                slotAllocationFailureCount++;
                return;
            }

            slot = new Slot(offset, requiredCapacity);

            if (previous != null) {
                slot.lastVisibleNanos =
                        previous.lastVisibleNanos;
            }

            newAllocation = true;
        }

        long stagingOffset =
                segment.startOffset + segment.head;

        ByteBuffer source = vertexBytes.duplicate();
        ByteBuffer destination = stagingMapped.duplicate();
        destination.position(Math.toIntExact(stagingOffset));
        destination.limit(Math.toIntExact(stagingOffset + byteCount));
        destination.slice().put(source);

        if (!recordCopy(
                segment,
                stagingOffset,
                slot.offset,
                byteCount
        )) {
            transferRecordFailureCount++;

            if (newAllocation) {
                freeRange(slot.offset, slot.capacity);
            }

            return;
        }

        if (newAllocation) {
            if (previous != null) {
                retireOrFree(previous, owner);
                slotRelocationCount++;
            }

            slots.put(owner, slot);
            slotAllocationCount++;
        }

        segment.head = align(segment.head + byteCount);
        segment.pendingCopyCount++;
        segment.pendingBytes += byteCount;

        uploadGeneration++;
        slot.usedBytes = byteCount;
        slot.generation = uploadGeneration;
        slot.sourceGeneration = sourceGeneration;
        slot.submittedGeneration = 0L;
        slot.completedGeneration = 0L;
        slot.completedSourceGeneration = 0L;

        segment.tickets.add(
                new CopyTicket(
                        owner,
                        uploadGeneration,
                        sourceGeneration,
                        byteCount
                )
        );

        residencyOrder.addLast(
                new ResidencyKey(
                        owner,
                        uploadGeneration
                )
        );

        stagedUploadCount++;
        stagedVertexBytes += byteCount;
        gpuCopyCommandCount++;
        gpuCopyCommandBytes += byteCount;

        long reserved = reservedArenaBytes();
        peakReservedArenaBytes =
                Math.max(peakReservedArenaBytes, reserved);
        peakActiveSlotCount =
                Math.max(peakActiveSlotCount, slots.size());

        if (segment.pendingCopyCount >= MAX_COPIES_PER_BATCH
                || segment.pendingBytes >= MAX_BYTES_PER_BATCH) {
            flushSegment(segment);
            currentTransferSegment =
                    (segment.index + 1) % transferSegments.length;
        }
    }

    synchronized void pollTransferCompletions() {
        if (!closed) {
            harvestCompletedTransfers();
        }
    }

    synchronized long completedSourceGeneration(
            Object owner
    ) {
        if (owner == null || closed) {
            return 0L;
        }

        Slot slot = slots.get(owner);

        if (slot == null
                || slot.generation <= 0L
                || slot.completedGeneration
                != slot.generation) {
            return 0L;
        }

        return slot.completedSourceGeneration;
    }

    synchronized ResidentSpan touchVisibleAndResidentSpan(
            Object owner
    ) {
        if (owner == null || closed) {
            return null;
        }

        Slot slot = slots.get(owner);

        if (slot == null
                || slot.generation <= 0L
                || slot.completedGeneration
                != slot.generation
                || slot.completedSourceGeneration <= 0L) {
            return null;
        }

        slot.lastVisibleNanos =
                System.nanoTime();

        visibleResidencyTouchCount++;

        return new ResidentSpan(
                slot.offset,
                slot.usedBytes,
                slot.completedSourceGeneration
        );
    }

    synchronized void release(Object owner) {
        if (owner == null) {
            return;
        }

        Slot slot = slots.remove(owner);
        if (slot == null) {
            return;
        }

        retireOrFree(slot, owner);
        slotReleaseCount++;
    }

    synchronized void trySubmitIndirectCandidateBatch(
            int residentCandidateCount,
            long frameSequence,
            long cameraFingerprint
    ) {
        if (indirectDrawRuntime == null) {
            return;
        }

        indirectDrawRuntime.trySubmit(
                residentCandidateCount,
                frameSequence,
                cameraFingerprint
        );
    }

    synchronized boolean indirectDrawExecutionObserved() {
        return indirectDrawRuntime != null
                && indirectDrawRuntime.executionObserved();
    }

    synchronized boolean indirectDrawVerified() {
        return indirectDrawRuntime != null
                && indirectDrawRuntime.verified();
    }

    synchronized void enrich() {
        if (!closed) {
            harvestCompletedTransfers();
        }

        VulkanExactFramePublicationToken.enrich(
                report
        );

        if (indirectDrawRuntime != null) {
            indirectDrawRuntime.enrich(
                    report
            );
        }

        report.addProperty(
                "vulkanRegionArenaInstalled",
                true
        );
        report.addProperty(
                "vulkanRegionArenaMode",
                "DEVICE_LOCAL_REGION_ARENA_WITH_REAL_GEOMETRY_INDIRECT_PROOF"
        );
        report.addProperty(
                "vulkanRegionArenaInitialized",
                initialized
        );
        report.addProperty(
                "vulkanRegionArenaDisabledAfterFailure",
                disabledAfterFailure
        );
        report.addProperty(
                "vulkanRegionArenaLastFailure",
                lastFailure
        );
        report.addProperty(
                "vulkanRegionArenaTransferDisabledAfterFailure",
                transferDisabledAfterFailure
        );
        report.addProperty(
                "vulkanRegionArenaLastTransferFailure",
                lastTransferFailure
        );
        report.addProperty(
                "vulkanRegionArenaDetectedDeviceLocalHeapBytes",
                detectedDeviceLocalHeapBytes
        );
        report.addProperty(
                "vulkanRegionArenaRequestedBytes",
                requestedArenaBytes
        );
        report.addProperty(
                "vulkanRegionArenaBytes",
                arenaBytes
        );
        report.addProperty(
                "vulkanRegionArenaRequestedStagingRingBytes",
                requestedStagingBytes
        );
        report.addProperty(
                "vulkanRegionArenaStagingRingBytes",
                stagingBytes
        );
        report.addProperty(
                "vulkanRegionArenaArenaAllocationAttemptCount",
                arenaAllocationAttemptCount
        );
        report.addProperty(
                "vulkanRegionArenaArenaAllocationFallbackCount",
                arenaAllocationFallbackCount
        );
        report.addProperty(
                "vulkanRegionArenaStagingAllocationAttemptCount",
                stagingAllocationAttemptCount
        );
        report.addProperty(
                "vulkanRegionArenaStagingAllocationFallbackCount",
                stagingAllocationFallbackCount
        );
        report.addProperty(
                "vulkanRegionArenaDeviceLocalBufferCreated",
                deviceLocalBufferCreated
        );
        report.addProperty(
                "vulkanRegionArenaDeviceLocalBufferAlive",
                arenaBuffer != NULL
        );
        report.addProperty(
                "vulkanRegionArenaDeviceLocalMemoryTypeIndex",
                arenaMemoryTypeIndex
        );
        report.addProperty(
                "vulkanRegionArenaDeviceLocalMemoryPropertyFlags",
                arenaMemoryPropertyFlags
        );
        report.addProperty(
                "vulkanRegionArenaDeviceLocalMemoryConfirmed",
                (arenaMemoryPropertyFlags
                        & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0
        );
        report.addProperty(
                "vulkanRegionArenaStagingBufferCreated",
                stagingBufferCreated
        );
        report.addProperty(
                "vulkanRegionArenaStagingBufferAlive",
                stagingBuffer != NULL
        );
        report.addProperty(
                "vulkanRegionArenaStagingMemoryTypeIndex",
                stagingMemoryTypeIndex
        );
        report.addProperty(
                "vulkanRegionArenaStagingMemoryPropertyFlags",
                stagingMemoryPropertyFlags
        );
        report.addProperty(
                "vulkanRegionArenaStagingPersistentlyMapped",
                stagingPersistentlyMapped
        );
        report.addProperty(
                "vulkanRegionArenaStagingMappingAlive",
                stagingMappedAddress != NULL
        );
        report.addProperty(
                "vulkanRegionArenaTransferQueueFamilyIndex",
                transferQueueFamilyIndex
        );
        report.addProperty(
                "vulkanRegionArenaGraphicsQueueFamilyIndex",
                graphicsQueueFamilyIndex
        );
        report.addProperty(
                "vulkanRegionArenaTransferQueueDedicated",
                transferQueueDedicated
        );
        report.addProperty(
                "vulkanRegionArenaArenaConcurrentQueueSharing",
                arenaConcurrentQueueSharing
        );
        report.addProperty(
                "vulkanRegionArenaTransferResourcesCreated",
                transferResourcesCreated
        );
        report.addProperty(
                "vulkanRegionArenaTransferSegmentCount",
                transferSegments.length
        );
        report.addProperty(
                "vulkanRegionArenaTransferSegmentBytes",
                minimumTransferSegmentBytes()
        );
        report.addProperty(
                "vulkanRegionArenaSlotAlignmentBytes",
                SLOT_ALIGNMENT
        );
        report.addProperty(
                "vulkanRegionArenaObservedUploadCount",
                observedUploadCount
        );
        report.addProperty(
                "vulkanRegionArenaStagedUploadCount",
                stagedUploadCount
        );
        report.addProperty(
                "vulkanRegionArenaStagedVertexBytes",
                stagedVertexBytes
        );
        report.addProperty(
                "vulkanRegionArenaOversizeStagingRejectCount",
                oversizeStagingRejectCount
        );
        report.addProperty(
                "vulkanRegionArenaTransferBackpressureRejectCount",
                transferBackpressureRejectCount
        );
        report.addProperty(
                "vulkanRegionArenaTransferRecordFailureCount",
                transferRecordFailureCount
        );
        report.addProperty(
                "vulkanRegionArenaSlotAllocationCount",
                slotAllocationCount
        );
        report.addProperty(
                "vulkanRegionArenaSlotReuseCount",
                slotReuseCount
        );
        report.addProperty(
                "vulkanRegionArenaSlotRelocationCount",
                slotRelocationCount
        );
        report.addProperty(
                "vulkanRegionArenaSlotReleaseCount",
                slotReleaseCount
        );
        report.addProperty(
                "vulkanRegionArenaSlotAllocationFailureCount",
                slotAllocationFailureCount
        );
        report.addProperty(
                "vulkanRegionArenaPressureEvictionCount",
                pressureEvictionCount
        );
        report.addProperty(
                "vulkanRegionArenaPressureEvictedBytes",
                pressureEvictedBytes
        );
        report.addProperty(
                "vulkanRegionArenaVisibleWarmMillis",
                VISIBLE_WARM_NANOS / 1_000_000L
        );
        report.addProperty(
                "vulkanRegionArenaLargeViewTuningInstalled",
                true
        );
        report.addProperty(
                "vulkanRegionArenaAutomaticHeapDivisor",
                8
        );
        report.addProperty(
                "vulkanRegionArenaMaximumArenaMiB",
                MAX_ARENA_MIB
        );
        report.addProperty(
                "vulkanRegionArenaMaximumStagingMiB",
                MAX_STAGING_MIB
        );
        report.addProperty(
                "vulkanRegionArenaMaximumCopiesPerBatch",
                MAX_COPIES_PER_BATCH
        );
        report.addProperty(
                "vulkanRegionArenaMaximumBytesPerBatch",
                MAX_BYTES_PER_BATCH
        );
        report.addProperty(
                "vulkanRegionArenaLargeViewTuningMode",
                "HEAP_1_8_CAP_1G_STAGING_96M_BATCH_96_8M_WARM_10S"
        );
        report.addProperty(
                "vulkanRegionArenaVisibleResidencyTouchCount",
                visibleResidencyTouchCount
        );
        report.addProperty(
                "vulkanRegionArenaWarmPressureEvictionSkipCount",
                warmPressureEvictionSkipCount
        );
        report.addProperty(
                "vulkanRegionArenaColdPressureEvictionCount",
                coldPressureEvictionCount
        );
        report.addProperty(
                "vulkanRegionArenaPendingRetiredRangeCount",
                retiredRanges.size()
        );
        report.addProperty(
                "vulkanRegionArenaActiveSlotCount",
                slots.size()
        );
        report.addProperty(
                "vulkanRegionArenaPeakActiveSlotCount",
                peakActiveSlotCount
        );
        report.addProperty(
                "vulkanRegionArenaReservedBytes",
                reservedArenaBytes()
        );
        report.addProperty(
                "vulkanRegionArenaPeakReservedBytes",
                peakReservedArenaBytes
        );
        report.addProperty(
                "vulkanRegionArenaLargestFreeRangeBytes",
                largestFreeRangeBytes()
        );
        report.addProperty(
                "vulkanRegionArenaGpuCopyCommandCount",
                gpuCopyCommandCount
        );
        report.addProperty(
                "vulkanRegionArenaGpuCopyCommandBytes",
                gpuCopyCommandBytes
        );
        report.addProperty(
                "vulkanRegionArenaGpuCopySubmissionCount",
                gpuCopySubmissionCount
        );
        report.addProperty(
                "vulkanRegionArenaGpuCopyCompletedSubmissionCount",
                gpuCopyCompletedSubmissionCount
        );
        report.addProperty(
                "vulkanRegionArenaGpuCopyCompletedCommandCount",
                gpuCopyCompletedCommandCount
        );
        report.addProperty(
                "vulkanRegionArenaGpuCopyCompletedBytes",
                gpuCopyCompletedBytes
        );
        report.addProperty(
                "vulkanRegionArenaTransferFencePollCount",
                transferFencePollCount
        );
        report.addProperty(
                "vulkanRegionArenaTransferFenceNotReadyCount",
                transferFenceNotReadyCount
        );
        report.addProperty(
                "vulkanRegionArenaTransferSegmentReuseCount",
                transferSegmentReuseCount
        );
        report.addProperty(
                "vulkanRegionArenaPeakCopiesPerSubmission",
                peakCopiesPerSubmission
        );
        report.addProperty(
                "vulkanRegionArenaPeakBytesPerSubmission",
                peakBytesPerSubmission
        );
        report.addProperty(
                "vulkanRegionArenaDeviceLocalContentReady",
                gpuCopyCompletedCommandCount > 0L
                        && !transferDisabledAfterFailure
        );
        report.addProperty(
                "vulkanRegionArenaExposedToIndirectGraphicsProof",
                indirectDrawRuntime != null
                        && arenaBuffer != NULL
        );
        report.addProperty(
                "vulkanRegionArenaVisibleOwnership",
                false
        );
        report.addProperty(
                "vulkanRegionArenaNoGameplayGpuWait",
                true
        );
        report.addProperty(
                "vulkanRegionArenaShutdownQueueWaitIdleUsed",
                shutdownQueueWaitIdleUsed
        );
        report.addProperty(
                "vulkanRegionArenaShutdownQueueWaitIdleResult",
                shutdownQueueWaitIdleResult
        );
        report.addProperty(
                "vulkanRegionArenaStage1Verified",
                verifiedBeforeClose || liveVerified()
        );
        report.addProperty(
                "vulkanRegionArenaStage2TransferVerified",
                stage2Verified()
        );
        report.addProperty(
                "vulkanRegionArenaClosed",
                closed
        );
        report.addProperty(
                "vulkanRegionArenaNextMilestone",
                "POTATO_ENGINE_VISIBLE_REGION_INDIRECT_SOLID"
        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        try {
            for (TransferSegment segment : transferSegments) {
                if (segment.recording && segment.pendingCopyCount > 0) {
                    flushSegment(segment);
                }
            }

            if (transferQueue != null
                    && gpuCopySubmissionCount > gpuCopyCompletedSubmissionCount) {
                shutdownQueueWaitIdleUsed = true;
                shutdownQueueWaitIdleResult =
                        vkQueueWaitIdle(transferQueue);

                if (shutdownQueueWaitIdleResult == VK_SUCCESS) {
                    completeAllSubmittedSegmentsAfterShutdownWait();
                }
            }
        } catch (Throwable throwable) {
            lastTransferFailure = describe(throwable);
        }

        if (indirectDrawRuntime != null) {
            try {
                indirectDrawRuntime.close();
            } catch (Throwable throwable) {
                lastFailure = describe(throwable);
            }
        }

        verifiedBeforeClose =
                verifiedBeforeClose || liveVerified();

        closed = true;
        slots.clear();
        freeRanges.clear();
        residencyOrder.clear();
        retiredRanges.clear();
        destroyNativeResources();
        enrich();
    }

    private void attachSelectedTransferQueue() {
        transferQueueFamilyIndex =
                reportInt(
                        "selectedTransferQueueFamilyIndex",
                        -1
                );
        graphicsQueueFamilyIndex =
                reportInt(
                        "selectedGraphicsQueueFamilyIndex",
                        transferQueueFamilyIndex
                );
        transferQueueDedicated =
                reportBoolean(
                        "transferQueueDedicated",
                        transferQueueFamilyIndex >= 0
                                && transferQueueFamilyIndex
                                != graphicsQueueFamilyIndex
                );

        if (transferQueueFamilyIndex < 0) {
            transferQueueFamilyIndex = graphicsQueueFamilyIndex;
        }

        if (transferQueueFamilyIndex < 0) {
            throw new IllegalStateException(
                    "No selected Vulkan transfer/graphics queue family is available"
            );
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pointer = stack.mallocPointer(1);

            vkGetDeviceQueue(
                    device,
                    transferQueueFamilyIndex,
                    0,
                    pointer
            );

            long handle = pointer.get(0);
            if (handle == NULL) {
                throw new IllegalStateException(
                        "vkGetDeviceQueue returned a null transfer queue handle"
                );
            }

            transferQueue = new VkQueue(handle, device);
        }
    }

    private BufferAllocation allocateArenaWithFallback(
            long requestedMiB
    ) {
        long candidateMiB =
                clamp(
                        requestedMiB,
                        MIN_ARENA_MIB,
                        MAX_ARENA_MIB
                );
        Throwable last = null;

        while (candidateMiB >= MIN_ARENA_MIB) {
            arenaAllocationAttemptCount++;
            long bytes = align(candidateMiB * MIB);

            try {
                int[] families =
                        concurrentArenaFamilies();

                return createBufferAllocation(
                        bytes,
                        VK_BUFFER_USAGE_TRANSFER_DST_BIT
                                | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT
                                | VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        families
                );
            } catch (Throwable throwable) {
                last = throwable;
            }

            if (candidateMiB == MIN_ARENA_MIB) {
                break;
            }

            arenaAllocationFallbackCount++;
            candidateMiB =
                    Math.max(
                            MIN_ARENA_MIB,
                            candidateMiB / 2L
                    );
        }

        throw new IllegalStateException(
                "Could not allocate Vulkan device-local region arena",
                last
        );
    }

    private BufferAllocation allocateStagingWithFallback(
            long requestedMiB
    ) {
        long candidateMiB =
                clamp(
                        requestedMiB,
                        MIN_STAGING_MIB,
                        MAX_STAGING_MIB
                );
        Throwable last = null;

        while (candidateMiB >= MIN_STAGING_MIB) {
            stagingAllocationAttemptCount++;
            long bytes = align(candidateMiB * MIB);

            try {
                return createBufferAllocation(
                        bytes,
                        VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
                                | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                        new int[]{transferQueueFamilyIndex}
                );
            } catch (Throwable throwable) {
                last = throwable;
            }

            if (candidateMiB == MIN_STAGING_MIB) {
                break;
            }

            stagingAllocationFallbackCount++;
            candidateMiB =
                    Math.max(
                            MIN_STAGING_MIB,
                            candidateMiB / 2L
                    );
        }

        throw new IllegalStateException(
                "Could not allocate Vulkan staging ring",
                last
        );
    }

    private int[] concurrentArenaFamilies() {
        if (graphicsQueueFamilyIndex >= 0
                && graphicsQueueFamilyIndex
                != transferQueueFamilyIndex) {
            arenaConcurrentQueueSharing = true;
            return new int[]{
                    transferQueueFamilyIndex,
                    graphicsQueueFamilyIndex
            };
        }

        arenaConcurrentQueueSharing = false;
        return new int[]{transferQueueFamilyIndex};
    }

    private void createTransferResources() {
        int segmentCount;
        if (stagingBytes >= 24L * MIB) {
            segmentCount = MAX_TRANSFER_SEGMENTS;
        } else if (stagingBytes >= 16L * MIB) {
            segmentCount = 2;
        } else {
            segmentCount = 1;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo =
                    VkCommandPoolCreateInfo.calloc(stack)
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                            )
                            .queueFamilyIndex(
                                    transferQueueFamilyIndex
                            );

            LongBuffer poolPointer = stack.mallocLong(1);
            int result = vkCreateCommandPool(
                    device,
                    poolInfo,
                    null,
                    poolPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateCommandPool(region transfer) failed with VkResult "
                                + result
                );
            }

            transferCommandPool = poolPointer.get(0);

            VkCommandBufferAllocateInfo allocateInfo =
                    VkCommandBufferAllocateInfo.calloc(stack)
                            .sType$Default()
                            .commandPool(transferCommandPool)
                            .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                            .commandBufferCount(segmentCount);

            PointerBuffer commandPointers =
                    stack.mallocPointer(segmentCount);

            result = vkAllocateCommandBuffers(
                    device,
                    allocateInfo,
                    commandPointers
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkAllocateCommandBuffers(region transfer) failed with VkResult "
                                + result
                );
            }

            long nominalSegmentBytes =
                    alignDown(stagingBytes / segmentCount);

            transferSegments =
                    new TransferSegment[segmentCount];

            for (int index = 0;
                 index < segmentCount;
                 index++) {

                long start = nominalSegmentBytes * index;
                long capacity =
                        index == segmentCount - 1
                                ? stagingBytes - start
                                : nominalSegmentBytes;

                VkFenceCreateInfo fenceInfo =
                        VkFenceCreateInfo.calloc(stack)
                                .sType$Default()
                                .flags(VK_FENCE_CREATE_SIGNALED_BIT);

                LongBuffer fencePointer = stack.mallocLong(1);
                result = vkCreateFence(
                        device,
                        fenceInfo,
                        null,
                        fencePointer
                );

                if (result != VK_SUCCESS) {
                    throw new IllegalStateException(
                            "vkCreateFence(region transfer) failed with VkResult "
                                    + result
                    );
                }

                transferSegments[index] =
                        new TransferSegment(
                                index,
                                start,
                                capacity,
                                new VkCommandBuffer(
                                        commandPointers.get(index),
                                        device
                                ),
                                fencePointer.get(0)
                        );
            }
        }

        transferResourcesCreated =
                transferCommandPool != NULL
                        && transferSegments.length > 0;
    }

    private TransferSegment acquireWritableSegment(
            int byteCount
    ) {
        if (transferSegments.length == 0) {
            return null;
        }

        for (int attempt = 0;
             attempt < transferSegments.length;
             attempt++) {

            int index =
                    (currentTransferSegment + attempt)
                            % transferSegments.length;
            TransferSegment segment = transferSegments[index];

            if (segment.submitted) {
                harvestSegment(segment);
            }

            if (segment.submitted) {
                continue;
            }

            if (segment.recording
                    && segment.head + byteCount > segment.capacity) {
                flushSegment(segment);
                continue;
            }

            if (!segment.recording
                    && byteCount > segment.capacity) {
                continue;
            }

            if (!segment.recording) {
                beginSegment(segment);
            }

            if (segment.head + byteCount <= segment.capacity) {
                currentTransferSegment = index;
                return segment;
            }
        }

        return null;
    }

    private void beginSegment(
            TransferSegment segment
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            int resetFence =
                    vkResetFences(
                            device,
                            segment.fence
                    );

            if (resetFence != VK_SUCCESS) {
                throw transferFailure(
                        "vkResetFences(region transfer) failed with VkResult "
                                + resetFence
                );
            }

            int resetCommand =
                    vkResetCommandBuffer(
                            segment.commandBuffer,
                            0
                    );

            if (resetCommand != VK_SUCCESS) {
                throw transferFailure(
                        "vkResetCommandBuffer(region transfer) failed with VkResult "
                                + resetCommand
                );
            }

            VkCommandBufferBeginInfo beginInfo =
                    VkCommandBufferBeginInfo.calloc(stack)
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            );

            int begin = vkBeginCommandBuffer(
                    segment.commandBuffer,
                    beginInfo
            );

            if (begin != VK_SUCCESS) {
                throw transferFailure(
                        "vkBeginCommandBuffer(region transfer) failed with VkResult "
                                + begin
                );
            }
        }

        if (segment.everSubmitted) {
            transferSegmentReuseCount++;
        }

        segment.recording = true;
        segment.head = 0L;
        segment.pendingCopyCount = 0;
        segment.pendingBytes = 0L;
        segment.tickets.clear();
    }

    private boolean recordCopy(
            TransferSegment segment,
            long sourceOffset,
            long destinationOffset,
            int byteCount
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy =
                    VkBufferCopy.calloc(1, stack);

            copy.get(0)
                    .srcOffset(sourceOffset)
                    .dstOffset(destinationOffset)
                    .size(byteCount);

            vkCmdCopyBuffer(
                    segment.commandBuffer,
                    stagingBuffer,
                    arenaBuffer,
                    copy
            );

            return true;
        } catch (Throwable throwable) {
            disableTransfer(throwable);
            return false;
        }
    }

    private void flushSegment(
            TransferSegment segment
    ) {
        if (segment == null
                || !segment.recording
                || segment.pendingCopyCount <= 0
                || transferDisabledAfterFailure) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int end =
                    vkEndCommandBuffer(
                            segment.commandBuffer
                    );

            if (end != VK_SUCCESS) {
                throw transferFailure(
                        "vkEndCommandBuffer(region transfer) failed with VkResult "
                                + end
                );
            }

            VkSubmitInfo.Buffer submit =
                    VkSubmitInfo.calloc(1, stack);

            submit.get(0)
                    .sType$Default()
                    .pCommandBuffers(
                            stack.pointers(
                                    segment.commandBuffer.address()
                            )
                    );

            int result = vkQueueSubmit(
                    transferQueue,
                    submit,
                    segment.fence
            );

            if (result != VK_SUCCESS) {
                throw transferFailure(
                        "vkQueueSubmit(region transfer) failed with VkResult "
                                + result
                );
            }
        } catch (Throwable throwable) {
            disableTransfer(throwable);
            return;
        }

        for (CopyTicket ticket : segment.tickets) {
            Slot slot = slots.get(ticket.owner());
            if (slot != null
                    && slot.generation == ticket.generation()) {
                slot.submittedGeneration = ticket.generation();
            }
        }

        gpuCopySubmissionCount++;
        peakCopiesPerSubmission =
                Math.max(
                        peakCopiesPerSubmission,
                        segment.pendingCopyCount
                );
        peakBytesPerSubmission =
                Math.max(
                        peakBytesPerSubmission,
                        segment.pendingBytes
                );

        segment.recording = false;
        segment.submitted = true;
        segment.everSubmitted = true;
    }

    private void harvestCompletedTransfers() {
        if (transferDisabledAfterFailure) {
            return;
        }

        for (TransferSegment segment : transferSegments) {
            if (segment.submitted) {
                harvestSegment(segment);
            }
        }
    }

    private void harvestSegment(
            TransferSegment segment
    ) {
        transferFencePollCount++;

        int result =
                vkGetFenceStatus(
                        device,
                        segment.fence
                );

        if (result == VK_NOT_READY) {
            transferFenceNotReadyCount++;
            return;
        }

        if (result != VK_SUCCESS) {
            disableTransfer(
                    transferFailure(
                            "vkGetFenceStatus(region transfer) failed with VkResult "
                                    + result
                    )
            );
            return;
        }

        completeSegment(segment);
    }

    private void completeSegment(
            TransferSegment segment
    ) {
        if (!segment.submitted) {
            return;
        }

        for (CopyTicket ticket : segment.tickets) {
            Slot slot = slots.get(ticket.owner());

            if (slot != null
                    && slot.generation == ticket.generation()
                    && slot.submittedGeneration == ticket.generation()
                    && slot.sourceGeneration
                    == ticket.sourceGeneration()) {
                slot.completedGeneration = ticket.generation();
                slot.completedSourceGeneration =
                        ticket.sourceGeneration();
            }

            releaseRetiredRanges(
                    ticket.owner(),
                    ticket.generation()
            );
        }

        gpuCopyCompletedSubmissionCount++;
        gpuCopyCompletedCommandCount +=
                segment.pendingCopyCount;
        gpuCopyCompletedBytes +=
                segment.pendingBytes;

        segment.submitted = false;
        segment.head = 0L;
        segment.pendingCopyCount = 0;
        segment.pendingBytes = 0L;
        segment.tickets.clear();
    }

    private void completeAllSubmittedSegmentsAfterShutdownWait() {
        for (TransferSegment segment : transferSegments) {
            if (segment.submitted) {
                completeSegment(segment);
            }
        }
    }

    private void retireOrFree(
            Slot slot,
            Object owner
    ) {
        if (slot == null) {
            return;
        }

        if (slot.generation <= 0L
                || slot.completedGeneration == slot.generation) {
            freeRange(slot.offset, slot.capacity);
            return;
        }

        retiredRanges.add(
                new RetiredRange(
                        owner,
                        slot.generation,
                        slot.offset,
                        slot.capacity
                )
        );
    }

    private void releaseRetiredRanges(
            Object owner,
            long generation
    ) {
        for (int index = retiredRanges.size() - 1;
             index >= 0;
             index--) {

            RetiredRange retired = retiredRanges.get(index);

            if (retired.owner() == owner
                    && retired.generation() == generation) {
                freeRange(
                        retired.offset(),
                        retired.capacity()
                );
                retiredRanges.remove(index);
            }
        }
    }

    private long allocateWithPressureEviction(
            long requestedBytes,
            Object protectedOwner
    ) {
        long offset = allocateRange(requestedBytes);
        if (offset >= 0L) {
            return offset;
        }

        while (evictOldestSlot(protectedOwner)) {
            offset = allocateRange(requestedBytes);
            if (offset >= 0L) {
                return offset;
            }
        }

        return -1L;
    }

    private boolean evictOldestSlot(
            Object protectedOwner
    ) {
        int attempts = residencyOrder.size();

        while (attempts-- > 0) {
            ResidencyKey key = residencyOrder.pollFirst();
            if (key == null) {
                return false;
            }

            Slot slot = slots.get(key.owner());
            if (slot == null
                    || slot.generation != key.generation()) {
                continue;
            }

            if (key.owner() == protectedOwner
                    || slot.completedGeneration != slot.generation) {
                residencyOrder.addLast(key);
                continue;
            }

            if (slot.lastVisibleNanos > 0L
                    && System.nanoTime()
                    - slot.lastVisibleNanos
                    <= VISIBLE_WARM_NANOS) {

                warmPressureEvictionSkipCount++;
                residencyOrder.addLast(key);
                continue;
            }

            slots.remove(key.owner());
            freeRange(slot.offset, slot.capacity);
            pressureEvictionCount++;
            coldPressureEvictionCount++;
            pressureEvictedBytes += slot.capacity;
            return true;
        }

        return false;
    }

    private boolean liveVerified() {
        return initialized
                && deviceLocalBufferCreated
                && stagingBufferCreated
                && stagingPersistentlyMapped
                && transferResourcesCreated
                && transferQueue != null
                && (arenaMemoryPropertyFlags
                        & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) != 0
                && !disabledAfterFailure;
    }

    private boolean stage2Verified() {
        return (verifiedBeforeClose || liveVerified())
                && gpuCopySubmissionCount > 0L
                && gpuCopyCommandCount > 0L
                && gpuCopyCompletedCommandCount > 0L
                && !transferDisabledAfterFailure;
    }

    private BufferAllocation createBufferAllocation(
            long size,
            int usage,
            int requiredMemoryFlags,
            int[] queueFamilyIndices
    ) {
        long buffer = NULL;
        long memory = NULL;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo bufferInfo =
                    VkBufferCreateInfo.calloc(stack)
                            .sType$Default()
                            .size(size)
                            .usage(usage);

            if (queueFamilyIndices != null
                    && queueFamilyIndices.length > 1) {
                IntBuffer families =
                        stack.mallocInt(queueFamilyIndices.length);

                for (int family : queueFamilyIndices) {
                    families.put(family);
                }
                families.flip();

                bufferInfo
                        .sharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(families);
            } else {
                bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            LongBuffer bufferPointer = stack.mallocLong(1);
            int result = vkCreateBuffer(
                    device,
                    bufferInfo,
                    null,
                    bufferPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateBuffer failed with VkResult " + result
                );
            }

            buffer = bufferPointer.get(0);

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(
                    device,
                    buffer,
                    requirements
            );

            MemoryTypeSelection selection =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            requiredMemoryFlags
                    );

            VkMemoryAllocateInfo allocationInfo =
                    VkMemoryAllocateInfo.calloc(stack)
                            .sType$Default()
                            .allocationSize(requirements.size())
                            .memoryTypeIndex(selection.index());

            LongBuffer memoryPointer = stack.mallocLong(1);
            result = vkAllocateMemory(
                    device,
                    allocationInfo,
                    null,
                    memoryPointer
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkAllocateMemory failed with VkResult " + result
                );
            }

            memory = memoryPointer.get(0);

            result = vkBindBufferMemory(
                    device,
                    buffer,
                    memory,
                    0L
            );

            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkBindBufferMemory failed with VkResult " + result
                );
            }

            return new BufferAllocation(
                    buffer,
                    memory,
                    selection.index(),
                    selection.propertyFlags(),
                    size
            );
        } catch (Throwable throwable) {
            if (memory != NULL) {
                vkFreeMemory(device, memory, null);
            }
            if (buffer != NULL) {
                vkDestroyBuffer(device, buffer, null);
            }
            throw new IllegalStateException(
                    "Vulkan region arena buffer allocation failed",
                    throwable
            );
        }
    }

    private MemoryTypeSelection findMemoryType(
            int memoryTypeBits,
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

                if ((memoryTypeBits & (1 << index)) == 0) {
                    continue;
                }

                VkMemoryType memoryType =
                        properties.memoryTypes(index);
                int flags = memoryType.propertyFlags();

                if ((flags & requiredFlags) == requiredFlags) {
                    return new MemoryTypeSelection(index, flags);
                }
            }
        }

        throw new IllegalStateException(
                "No Vulkan memory type satisfies required flags 0x"
                        + Integer.toHexString(requiredFlags)
        );
    }

    private long detectLargestDeviceLocalHeapBytes() {
        long largest = 0L;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties properties =
                    VkPhysicalDeviceMemoryProperties.malloc(stack);

            vkGetPhysicalDeviceMemoryProperties(
                    physicalDevice,
                    properties
            );

            for (int index = 0;
                 index < properties.memoryHeapCount();
                 index++) {

                VkMemoryHeap heap = properties.memoryHeaps(index);
                if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) {
                    continue;
                }

                largest = Math.max(largest, heap.size());
            }
        }

        return largest;
    }

    private long allocateRange(long requestedBytes) {
        Map.Entry<Long, Long> candidate = null;
        long alignedOffset = -1L;

        for (Map.Entry<Long, Long> entry : freeRanges.entrySet()) {
            long offset = align(entry.getKey());
            long rangeEnd = entry.getKey() + entry.getValue();

            if (offset + requestedBytes <= rangeEnd) {
                candidate = entry;
                alignedOffset = offset;
                break;
            }
        }

        if (candidate == null) {
            return -1L;
        }

        long rangeOffset = candidate.getKey();
        long rangeBytes = candidate.getValue();
        long rangeEnd = rangeOffset + rangeBytes;
        freeRanges.remove(rangeOffset);

        if (alignedOffset > rangeOffset) {
            freeRanges.put(
                    rangeOffset,
                    alignedOffset - rangeOffset
            );
        }

        long allocationEnd = alignedOffset + requestedBytes;
        if (allocationEnd < rangeEnd) {
            freeRanges.put(
                    allocationEnd,
                    rangeEnd - allocationEnd
            );
        }

        return alignedOffset;
    }

    private void freeRange(long offset, long bytes) {
        if (bytes <= 0L) {
            return;
        }

        long mergedOffset = offset;
        long mergedBytes = bytes;

        Map.Entry<Long, Long> lower =
                freeRanges.floorEntry(offset);
        if (lower != null
                && lower.getKey() + lower.getValue() == offset) {
            mergedOffset = lower.getKey();
            mergedBytes += lower.getValue();
            freeRanges.remove(lower.getKey());
        }

        Map.Entry<Long, Long> higher =
                freeRanges.ceilingEntry(mergedOffset);
        if (higher != null
                && mergedOffset + mergedBytes == higher.getKey()) {
            mergedBytes += higher.getValue();
            freeRanges.remove(higher.getKey());
        }

        freeRanges.put(mergedOffset, mergedBytes);
    }

    private long reservedArenaBytes() {
        long reserved = 0L;
        for (Slot slot : slots.values()) {
            reserved += slot.capacity;
        }
        return reserved;
    }

    private long largestFreeRangeBytes() {
        long largest = 0L;
        for (long bytes : freeRanges.values()) {
            largest = Math.max(largest, bytes);
        }
        return largest;
    }

    private long maximumTransferSegmentBytes() {
        long largest = 0L;
        for (TransferSegment segment : transferSegments) {
            largest = Math.max(largest, segment.capacity);
        }
        return largest;
    }

    private long minimumTransferSegmentBytes() {
        if (transferSegments.length == 0) {
            return 0L;
        }

        long minimum = Long.MAX_VALUE;
        for (TransferSegment segment : transferSegments) {
            minimum = Math.min(minimum, segment.capacity);
        }
        return minimum == Long.MAX_VALUE ? 0L : minimum;
    }

    private void disableTransfer(
            Throwable throwable
    ) {
        transferDisabledAfterFailure = true;
        lastTransferFailure = describe(throwable);
    }

    private IllegalStateException transferFailure(
            String message
    ) {
        return new IllegalStateException(message);
    }

    private void destroyNativeResources() {
        for (TransferSegment segment : transferSegments) {
            if (segment != null && segment.fence != NULL) {
                try {
                    vkDestroyFence(device, segment.fence, null);
                } catch (Throwable ignored) {
                    // Process/device teardown remains the final authority.
                }
            }
        }
        transferSegments = new TransferSegment[0];

        if (transferCommandPool != NULL) {
            try {
                vkDestroyCommandPool(
                        device,
                        transferCommandPool,
                        null
                );
            } catch (Throwable ignored) {
                // Fail-open shutdown cleanup.
            }
            transferCommandPool = NULL;
        }

        if (stagingMappedAddress != NULL
                && stagingMemory != NULL) {
            try {
                vkUnmapMemory(device, stagingMemory);
            } catch (Throwable ignored) {
                // Fail-open shutdown cleanup.
            }
        }

        stagingMapped = null;
        stagingMappedAddress = NULL;

        if (stagingBuffer != NULL) {
            vkDestroyBuffer(device, stagingBuffer, null);
            stagingBuffer = NULL;
        }
        if (stagingMemory != NULL) {
            vkFreeMemory(device, stagingMemory, null);
            stagingMemory = NULL;
        }
        if (arenaBuffer != NULL) {
            vkDestroyBuffer(device, arenaBuffer, null);
            arenaBuffer = NULL;
        }
        if (arenaMemory != NULL) {
            vkFreeMemory(device, arenaMemory, null);
            arenaMemory = NULL;
        }
    }

    private int reportInt(
            String property,
            int fallback
    ) {
        try {
            if (report != null
                    && report.has(property)) {
                return report.get(property).getAsInt();
            }
        } catch (Throwable ignored) {
            // Fall through to conservative fallback.
        }
        return fallback;
    }

    private boolean reportBoolean(
            String property,
            boolean fallback
    ) {
        try {
            if (report != null
                    && report.has(property)) {
                return report.get(property).getAsBoolean();
            }
        } catch (Throwable ignored) {
            // Fall through to conservative fallback.
        }
        return fallback;
    }

    private static long configuredMiB(
            String property,
            long fallback,
            long minimum,
            long maximum
    ) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return clamp(fallback, minimum, maximum);
        }

        try {
            return clamp(
                    Long.parseLong(raw.trim()),
                    minimum,
                    maximum
            );
        } catch (NumberFormatException ignored) {
            return clamp(fallback, minimum, maximum);
        }
    }

    private static long clamp(
            long value,
            long minimum,
            long maximum
    ) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static long align(long value) {
        return (value + SLOT_ALIGNMENT - 1L)
                & -SLOT_ALIGNMENT;
    }

    private static long alignDown(long value) {
        return value & -SLOT_ALIGNMENT;
    }

    private static String describe(Throwable throwable) {
        return throwable.getClass().getName()
                + ": "
                + String.valueOf(throwable.getMessage());
    }

    private static final class Slot {
        private final long offset;
        private final long capacity;
        private int usedBytes;
        private long generation;
        private long sourceGeneration;
        private long submittedGeneration;
        private long completedGeneration;
        private long completedSourceGeneration;
        private long lastVisibleNanos;

        private Slot(long offset, long capacity) {
            this.offset = offset;
            this.capacity = capacity;
        }
    }

    record ResidentSpan(
            long offsetBytes,
            int usedBytes,
            long sourceGeneration
    ) {
    }

    private static final class TransferSegment {
        private final int index;
        private final long startOffset;
        private final long capacity;
        private final VkCommandBuffer commandBuffer;
        private final long fence;
        private final ArrayList<CopyTicket> tickets =
                new ArrayList<>(MAX_COPIES_PER_BATCH);

        private long head;
        private int pendingCopyCount;
        private long pendingBytes;
        private boolean recording;
        private boolean submitted;
        private boolean everSubmitted;

        private TransferSegment(
                int index,
                long startOffset,
                long capacity,
                VkCommandBuffer commandBuffer,
                long fence
        ) {
            this.index = index;
            this.startOffset = startOffset;
            this.capacity = capacity;
            this.commandBuffer = commandBuffer;
            this.fence = fence;
        }
    }

    private record CopyTicket(
            Object owner,
            long generation,
            long sourceGeneration,
            int byteCount
    ) {
    }

    private record ResidencyKey(
            Object owner,
            long generation
    ) {
    }

    private record RetiredRange(
            Object owner,
            long generation,
            long offset,
            long capacity
    ) {
    }

    private record BufferAllocation(
            long buffer,
            long memory,
            int memoryTypeIndex,
            int memoryPropertyFlags,
            long sizeBytes
    ) {
    }

    private record MemoryTypeSelection(
            int index,
            int propertyFlags
    ) {
    }
}
