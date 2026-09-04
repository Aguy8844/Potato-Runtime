package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;

import java.util.HashMap;
import java.util.IdentityHashMap;

/**
 * Shadow-only region-local geometry residency survey.
 *
 * <p>Patch 072 proved that a monolithic 1 GiB buddy arena wastes too much space
 * for a potato-class target. This survey groups admitted section geometry into
 * 8 x 4 x 8 section regions and models independently sized device-local arenas.
 * It allocates no Vulkan memory and changes no visible draw.</p>
 */
public final class VulkanRegionArenaSurvey {
    public static final int REGION_WIDTH_SECTIONS =
            8;
    public static final int REGION_HEIGHT_SECTIONS =
            4;
    public static final int REGION_LENGTH_SECTIONS =
            8;

    private static final long MIN_REGION_ARENA_BYTES =
            512L * 1024L;

    private static final long MAX_REGION_ARENA_BYTES =
            128L * 1024L * 1024L;

    private static final IdentityHashMap<
            DrawBufferBackendState,
            Slot> slots =
            new IdentityHashMap<>();

    private static final HashMap<Long, RegionUsage> regions =
            new HashMap<>();

    private static long uploadObservationCount;
    private static long associationCount;
    private static long reassociationCount;
    private static long closeCount;
    private static long unassociatedUploadCount;
    private static long bytesUpdateCount;

    private static long currentAssignedBytes;
    private static long peakAssignedBytes;
    private static int currentAssociatedSlots;
    private static int peakAssociatedSlots;
    private static int peakActiveRegionCount;

    private static long maxSingleRegionRequestedBytes;
    private static int maxSingleRegionSlotCount;
    private static long currentEstimatedProvisionedBytes;
    private static long peakEstimatedProvisionedBytes;
    private static long peakEstimatedSlackBytes;
    private static long peakPerRegionEstimatedArenaBytes;

    private VulkanRegionArenaSurvey() {
    }

    public static void resetForRuntime() {
        slots.clear();
        regions.clear();

        uploadObservationCount = 0L;
        associationCount = 0L;
        reassociationCount = 0L;
        closeCount = 0L;
        unassociatedUploadCount = 0L;
        bytesUpdateCount = 0L;

        currentAssignedBytes = 0L;
        peakAssignedBytes = 0L;
        currentAssociatedSlots = 0;
        peakAssociatedSlots = 0;
        peakActiveRegionCount = 0;

        maxSingleRegionRequestedBytes = 0L;
        maxSingleRegionSlotCount = 0;
        currentEstimatedProvisionedBytes = 0L;
        peakEstimatedProvisionedBytes = 0L;
        peakEstimatedSlackBytes = 0L;
        peakPerRegionEstimatedArenaBytes = 0L;
    }

    public static void onUpload(
            DrawBufferBackendState state,
            int vertexBytes
    ) {
        if (state == null || vertexBytes <= 0) {
            return;
        }

        uploadObservationCount++;

        Slot slot =
                slots.get(state);

        if (slot == null) {
            slot = new Slot();
            slots.put(state, slot);
        }

        int previousBytes =
                slot.vertexBytes;

        slot.vertexBytes =
                vertexBytes;

        if (slot.regionKey == Long.MIN_VALUE) {
            unassociatedUploadCount++;
            return;
        }

        long delta =
                (long) vertexBytes
                        - previousBytes;

        if (delta != 0L) {
            bytesUpdateCount++;
            RegionUsage region =
                    regions.get(slot.regionKey);

            if (region != null) {
                applyBytesDelta(
                        region,
                        delta
                );
            }
        }
    }

    public static void associate(
            DrawBufferBackendState state,
            BlockPos origin
    ) {
        if (state == null || origin == null) {
            return;
        }

        Slot slot =
                slots.get(state);

        if (slot == null) {
            slot = new Slot();
            slot.vertexBytes =
                    Math.max(
                            0,
                            state.vertexBytes()
                    );
            slots.put(state, slot);
        }

        long newRegionKey =
                regionKey(origin);

        if (slot.regionKey == newRegionKey) {
            return;
        }

        if (slot.regionKey != Long.MIN_VALUE) {
            reassociationCount++;
            removeFromRegion(
                    slot.regionKey,
                    slot.vertexBytes
            );
        } else {
            associationCount++;
        }

        slot.regionKey =
                newRegionKey;

        RegionUsage region =
                regions.computeIfAbsent(
                        newRegionKey,
                        ignored -> new RegionUsage()
                );

        long oldProvisioned =
                region.estimatedProvisionedBytes;

        region.slotCount++;
        region.requestedBytes +=
                slot.vertexBytes;

        currentAssociatedSlots++;
        currentAssignedBytes +=
                slot.vertexBytes;

        recalculateProvisioned(
                region,
                oldProvisioned
        );

        updatePeaks(
                region
        );
    }

    public static void onClose(
            DrawBufferBackendState state
    ) {
        if (state == null) {
            return;
        }

        closeCount++;

        Slot slot =
                slots.remove(state);

        if (slot == null
                || slot.regionKey == Long.MIN_VALUE) {
            return;
        }

        removeFromRegion(
                slot.regionKey,
                slot.vertexBytes
        );
    }

    public static void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "geometryRegionArenaSurveyInstalled",
                true
        );
        report.addProperty(
                "geometryRegionArenaSurveyShadowOnly",
                true
        );
        report.addProperty(
                "geometryRegionArenaSurveyLayout",
                "8x4x8_SECTIONS_REGION_LOCAL"
        );
        report.addProperty(
                "geometryRegionArenaSurveyRegionWidthSections",
                REGION_WIDTH_SECTIONS
        );
        report.addProperty(
                "geometryRegionArenaSurveyRegionHeightSections",
                REGION_HEIGHT_SECTIONS
        );
        report.addProperty(
                "geometryRegionArenaSurveyRegionLengthSections",
                REGION_LENGTH_SECTIONS
        );
        report.addProperty(
                "geometryRegionArenaSurveyUploadObservationCount",
                uploadObservationCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyAssociationCount",
                associationCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyReassociationCount",
                reassociationCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyCloseCount",
                closeCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyUnassociatedUploadCount",
                unassociatedUploadCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyBytesUpdateCount",
                bytesUpdateCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyCurrentAssociatedSlots",
                currentAssociatedSlots
        );
        report.addProperty(
                "geometryRegionArenaSurveyPeakAssociatedSlots",
                peakAssociatedSlots
        );
        report.addProperty(
                "geometryRegionArenaSurveyCurrentActiveRegionCount",
                regions.size()
        );
        report.addProperty(
                "geometryRegionArenaSurveyPeakActiveRegionCount",
                peakActiveRegionCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyCurrentAssignedBytes",
                currentAssignedBytes
        );
        report.addProperty(
                "geometryRegionArenaSurveyPeakAssignedBytes",
                peakAssignedBytes
        );
        report.addProperty(
                "geometryRegionArenaSurveyMaxSingleRegionRequestedBytes",
                maxSingleRegionRequestedBytes
        );
        report.addProperty(
                "geometryRegionArenaSurveyMaxSingleRegionSlotCount",
                maxSingleRegionSlotCount
        );
        report.addProperty(
                "geometryRegionArenaSurveyCurrentEstimatedProvisionedBytes",
                currentEstimatedProvisionedBytes
        );
        report.addProperty(
                "geometryRegionArenaSurveyPeakEstimatedProvisionedBytes",
                peakEstimatedProvisionedBytes
        );
        report.addProperty(
                "geometryRegionArenaSurveyPeakEstimatedSlackBytes",
                peakEstimatedSlackBytes
        );
        report.addProperty(
                "geometryRegionArenaSurveyPeakPerRegionEstimatedArenaBytes",
                peakPerRegionEstimatedArenaBytes
        );
        report.addProperty(
                "geometryRegionArenaSurveyCoversAdmittedMirrorOnly",
                true
        );
        report.addProperty(
                "geometryRegionArenaSurveyAllocatesNativeMemory",
                false
        );
        report.addProperty(
                "geometryRegionArenaSurveyReadyForDeviceLocalPrototype",
                peakAssociatedSlots > 0
                        && peakActiveRegionCount > 0
                        && peakEstimatedProvisionedBytes > 0L
        );
    }

    private static long regionKey(
            BlockPos origin
    ) {
        int sectionX =
                Math.floorDiv(
                        origin.getX(),
                        16
                );
        int sectionY =
                Math.floorDiv(
                        origin.getY(),
                        16
                );
        int sectionZ =
                Math.floorDiv(
                        origin.getZ(),
                        16
                );

        int regionX =
                Math.floorDiv(
                        sectionX,
                        REGION_WIDTH_SECTIONS
                );
        int regionY =
                Math.floorDiv(
                        sectionY,
                        REGION_HEIGHT_SECTIONS
                );
        int regionZ =
                Math.floorDiv(
                        sectionZ,
                        REGION_LENGTH_SECTIONS
                );

        return SectionPos.asLong(
                regionX,
                regionY,
                regionZ
        );
    }

    private static void applyBytesDelta(
            RegionUsage region,
            long delta
    ) {
        long oldProvisioned =
                region.estimatedProvisionedBytes;

        region.requestedBytes =
                Math.max(
                        0L,
                        region.requestedBytes + delta
                );

        currentAssignedBytes =
                Math.max(
                        0L,
                        currentAssignedBytes + delta
                );

        recalculateProvisioned(
                region,
                oldProvisioned
        );

        updatePeaks(
                region
        );
    }

    private static void removeFromRegion(
            long regionKey,
            int vertexBytes
    ) {
        RegionUsage region =
                regions.get(regionKey);

        if (region == null) {
            return;
        }

        long oldProvisioned =
                region.estimatedProvisionedBytes;

        region.slotCount =
                Math.max(
                        0,
                        region.slotCount - 1
                );
        region.requestedBytes =
                Math.max(
                        0L,
                        region.requestedBytes
                                - Math.max(
                                0,
                                vertexBytes
                        )
                );

        currentAssociatedSlots =
                Math.max(
                        0,
                        currentAssociatedSlots - 1
                );
        currentAssignedBytes =
                Math.max(
                        0L,
                        currentAssignedBytes
                                - Math.max(
                                0,
                                vertexBytes
                        )
                );

        if (region.slotCount == 0) {
            currentEstimatedProvisionedBytes =
                    Math.max(
                            0L,
                            currentEstimatedProvisionedBytes
                                    - oldProvisioned
                    );
            regions.remove(regionKey);
            return;
        }

        recalculateProvisioned(
                region,
                oldProvisioned
        );
    }

    private static void recalculateProvisioned(
            RegionUsage region,
            long oldProvisioned
    ) {
        long newProvisioned =
                estimateRegionArenaBytes(
                        region.requestedBytes
                );

        region.estimatedProvisionedBytes =
                newProvisioned;

        currentEstimatedProvisionedBytes =
                Math.max(
                        0L,
                        currentEstimatedProvisionedBytes
                                - oldProvisioned
                                + newProvisioned
                );
    }

    private static void updatePeaks(
            RegionUsage region
    ) {
        peakAssociatedSlots =
                Math.max(
                        peakAssociatedSlots,
                        currentAssociatedSlots
                );
        peakAssignedBytes =
                Math.max(
                        peakAssignedBytes,
                        currentAssignedBytes
                );
        peakActiveRegionCount =
                Math.max(
                        peakActiveRegionCount,
                        regions.size()
                );
        maxSingleRegionRequestedBytes =
                Math.max(
                        maxSingleRegionRequestedBytes,
                        region.requestedBytes
                );
        maxSingleRegionSlotCount =
                Math.max(
                        maxSingleRegionSlotCount,
                        region.slotCount
                );
        peakEstimatedProvisionedBytes =
                Math.max(
                        peakEstimatedProvisionedBytes,
                        currentEstimatedProvisionedBytes
                );
        peakEstimatedSlackBytes =
                Math.max(
                        peakEstimatedSlackBytes,
                        Math.max(
                                0L,
                                currentEstimatedProvisionedBytes
                                        - currentAssignedBytes
                        )
                );
        peakPerRegionEstimatedArenaBytes =
                Math.max(
                        peakPerRegionEstimatedArenaBytes,
                        region.estimatedProvisionedBytes
                );
    }

    private static long estimateRegionArenaBytes(
            long requestedBytes
    ) {
        if (requestedBytes <= 0L) {
            return 0L;
        }

        long target =
                requestedBytes
                        + Math.max(
                        256L * 1024L,
                        requestedBytes / 4L
                );

        long rounded =
                MIN_REGION_ARENA_BYTES;

        while (rounded < target
                && rounded < MAX_REGION_ARENA_BYTES) {
            rounded <<= 1;
        }

        return Math.min(
                MAX_REGION_ARENA_BYTES,
                Math.max(
                        MIN_REGION_ARENA_BYTES,
                        rounded
                )
        );
    }

    private static final class Slot {
        private int vertexBytes;
        private long regionKey =
                Long.MIN_VALUE;
    }

    private static final class RegionUsage {
        private long requestedBytes;
        private int slotCount;
        private long estimatedProvisionedBytes;
    }
}
