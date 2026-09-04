package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;

import java.util.IdentityHashMap;

/**
 * GPU-driven surface-cluster / real arena indirect planner.
 *
 * <p>Patch 086 deliberately starts coarse: one Minecraft SOLID section is the
 * resident draw cluster, while its quad count is also partitioned into virtual
 * 64-quad surface clusters for the later spatial meshlet extractor. This gives
 * the Vulkan renderer a stable HOT/WARM/COLD ownership model and an exact
 * arena-backed indirect candidate list without parsing every vertex again on
 * the render thread.</p>
 *
 * <p>Normal OpenGL rendering is not changed. Candidate capture is sampled every
 * eight SOLID layers so this preparation does not become a new per-frame
 * bottleneck before Vulkan owns the visible layer.</p>
 */
public final class VulkanSurfaceClusterVisibility {
    private static final int TARGET_QUADS_PER_VIRTUAL_CLUSTER =
            64;

    private static final int MAX_SAMPLED_CANDIDATES =
            Math.max(
                    256,
                    Math.min(
                            32768,
                            Integer.getInteger(
                                    "potato.vulkan.surfaceClusterCandidates",
                                    8192
                            )
                    )
            );

    private static final int SAMPLE_STRIDE_SOLID_LAYERS =
            Math.max(
                    1,
                    Integer.getInteger(
                            "potato.vulkan.surfaceClusterSampleStride",
                            8
                    )
            );

    /*
     * Once the 087 vkCmdDrawIndirect proof has succeeded, the planner no
     * longer needs to touch the IdentityHashMap on every SOLID section of
     * every frame. The 10-second arena visibility warmth from 092 gives a
     * large safety margin, so post-proof metadata sampling can be much more
     * sparse while retaining conservative residency behavior.
     */
    private static final int POST_PROOF_SAMPLE_STRIDE_SOLID_LAYERS =
            Math.max(
                    SAMPLE_STRIDE_SOLID_LAYERS,
                    Integer.getInteger(
                            "potato.vulkan.surfaceClusterPostProofStride",
                            128
                    )
            );

    private static final long WARM_LAYER_WINDOW =
            Math.max(
                    1L,
                    Long.getLong(
                            "potato.vulkan.surfaceClusterWarmLayers",
                            120L
                    )
            );

    private static final IdentityHashMap<Object, SectionClusterRecord>
            records =
            new IdentityHashMap<>();

    private static final long[] candidateArenaOffsets =
            new long[MAX_SAMPLED_CANDIDATES];

    private static final int[] candidateVertexCounts =
            new int[MAX_SAMPLED_CANDIDATES];

    private static final int[] candidateUsedBytes =
            new int[MAX_SAMPLED_CANDIDATES];

    private static final long[] candidateSourceGenerations =
            new long[MAX_SAMPLED_CANDIDATES];

    private static final float[] candidateChunkOffsets =
            new float[MAX_SAMPLED_CANDIDATES * 3];

    private static boolean active;
    private static boolean sampleCurrentLayer;
    private static boolean indirectProofObserved;
    private static boolean realArenaGeometryProofObserved;
    private static int activeSampleStride = SAMPLE_STRIDE_SOLID_LAYERS;
    private static long activeFrameSequence;
    private static long activeCameraFingerprint;
    private static int activeSampledCandidateCount;

    private static long uploadObservationCount;
    private static long uploadedVertexCount;
    private static long uploadedQuadCount;
    private static long plannedVirtualClusterCount;
    private static long solidLayerBeginCount;
    private static long solidLayerEndCount;
    private static long sampledSolidLayerCount;
    private static long sectionDrawObservationCount;
    private static long hotPathSkippedObservationCount;
    private static long postProofSampledLayerCount;
    private static long metadataHitCount;
    private static long metadataMissingCount;
    private static long staleGenerationRejectCount;
    private static long residentCandidateCount;
    private static long nonResidentCandidateCount;
    private static long residentVirtualClusterCount;
    private static long indirectCandidateCount;
    private static long indirectCandidateOverflowCount;
    private static long estimatedCpuDrawCallsAvoidable;
    private static long arenaVisibleTouchCount;
    private static long frameTokenMismatchCount;
    private static int peakSampledCandidateCount;
    private static long closeCount;

    private VulkanSurfaceClusterVisibility() {
    }

    public static void onUpload(
            Object owner,
            long sourceGeneration,
            DrawGeometryView geometry
    ) {
        if (owner == null
                || sourceGeneration <= 0L
                || geometry == null) {
            return;
        }

        int vertexCount =
                Math.max(
                        0,
                        geometry.vertexCount()
                );

        int quadCount =
                vertexCount / 4;

        int virtualClusterCount =
                quadCount <= 0
                        ? 0
                        : Math.max(
                                1,
                                (quadCount
                                        + TARGET_QUADS_PER_VIRTUAL_CLUSTER
                                        - 1)
                                        / TARGET_QUADS_PER_VIRTUAL_CLUSTER
                        );

        SectionClusterRecord record =
                records.get(owner);

        if (record == null) {
            record =
                    new SectionClusterRecord();

            records.put(
                    owner,
                    record
            );
        }

        record.sourceGeneration =
                sourceGeneration;
        record.vertexCount =
                vertexCount;
        record.quadCount =
                quadCount;
        record.virtualClusterCount =
                virtualClusterCount;

        uploadObservationCount++;
        uploadedVertexCount +=
                vertexCount;
        uploadedQuadCount +=
                quadCount;
        plannedVirtualClusterCount +=
                virtualClusterCount;
    }

    public static void onClose(Object owner) {
        if (owner == null) {
            return;
        }

        if (records.remove(owner) != null) {
            closeCount++;
        }
    }

    public static void beginSolidLayer() {
        if (active) {
            frameTokenMismatchCount++;
        }

        active = true;
        activeFrameSequence =
                VulkanExactFramePublicationToken
                        .currentFrameSequence();

        activeCameraFingerprint =
                VulkanExactFramePublicationToken
                        .currentCameraFingerprint();

        boolean realArenaGeometryVerified =
                VulkanRegionArenaIngress
                        .indirectDrawVerified();

        realArenaGeometryProofObserved =
                realArenaGeometryProofObserved
                        || realArenaGeometryVerified;

        indirectProofObserved =
                indirectProofObserved
                        || realArenaGeometryVerified
                        || VulkanRegionArenaIngress.indirectDrawExecutionObserved();

        activeSampleStride =
                indirectProofObserved
                        ? POST_PROOF_SAMPLE_STRIDE_SOLID_LAYERS
                        : SAMPLE_STRIDE_SOLID_LAYERS;

        sampleCurrentLayer =
                activeFrameSequence > 0L
                        && activeFrameSequence
                        % activeSampleStride == 0L;

        activeSampledCandidateCount =
                0;

        solidLayerBeginCount++;

        if (sampleCurrentLayer) {
            sampledSolidLayerCount++;

            if (indirectProofObserved) {
                postProofSampledLayerCount++;
            }
        }
    }

    /**
     * Cheap caller-side gate for the section draw hot path.
     *
     * <p>After the real arena indirect proof is sticky, 127 of every 128 SOLID
     * layers do not need per-section mesh-generation reads at all. Callers can
     * use this before even touching the VertexBuffer sidecar.</p>
     */
    public static boolean shouldObserveSectionDraw() {
        return active
                && sampleCurrentLayer;
    }

    public static void observeSectionDraw(
            Object owner,
            long meshGeneration,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        if (!active
                || owner == null
                || meshGeneration <= 0L) {
            return;
        }

        sectionDrawObservationCount++;

        /*
         * Patch 093 hot path: on a non-sampled layer this used to perform an
         * IdentityHashMap lookup plus generation/metadata writes for every
         * visible section even though no candidate list was produced. The
         * 092 run did this roughly 8.8 million times. Return before the map
         * lookup; sampled layers still do the full conservative validation.
         */
        if (!sampleCurrentLayer) {
            hotPathSkippedObservationCount++;
            return;
        }

        SectionClusterRecord record =
                records.get(owner);

        if (record == null) {
            metadataMissingCount++;
            return;
        }

        if (record.sourceGeneration
                != meshGeneration) {
            staleGenerationRejectCount++;
            return;
        }

        metadataHitCount++;
        record.lastVisibleFrame =
                activeFrameSequence;
        record.lastChunkOffsetX =
                chunkOffsetX;
        record.lastChunkOffsetY =
                chunkOffsetY;
        record.lastChunkOffsetZ =
                chunkOffsetZ;

        if (activeFrameSequence
                != VulkanExactFramePublicationToken
                .currentFrameSequence()
                || activeCameraFingerprint
                != VulkanExactFramePublicationToken
                .currentCameraFingerprint()) {

            frameTokenMismatchCount++;
            return;
        }

        VulkanRegionArenaIngress.ResidentSpan span =
                VulkanRegionArenaIngress
                        .touchVisibleAndGetResidentSpan(
                                owner
                        );

        arenaVisibleTouchCount++;

        if (span == null
                || span.sourceGeneration()
                != meshGeneration) {
            nonResidentCandidateCount++;
            return;
        }

        residentCandidateCount++;
        residentVirtualClusterCount +=
                record.virtualClusterCount;

        if (activeSampledCandidateCount
                >= candidateArenaOffsets.length) {
            indirectCandidateOverflowCount++;
            return;
        }

        int index =
                activeSampledCandidateCount++;

        candidateArenaOffsets[index] =
                span.offsetBytes();

        candidateVertexCounts[index] =
                record.vertexCount;

        candidateUsedBytes[index] =
                span.usedBytes();

        candidateSourceGenerations[index] =
                meshGeneration;

        int offsetBase =
                index * 3;

        candidateChunkOffsets[offsetBase] =
                chunkOffsetX;
        candidateChunkOffsets[offsetBase + 1] =
                chunkOffsetY;
        candidateChunkOffsets[offsetBase + 2] =
                chunkOffsetZ;

        indirectCandidateCount++;
    }

    public static void endSolidLayer() {
        if (!active) {
            return;
        }

        if (sampleCurrentLayer) {
            peakSampledCandidateCount =
                    Math.max(
                            peakSampledCandidateCount,
                            activeSampledCandidateCount
                    );

            if (activeSampledCandidateCount > 1) {
                estimatedCpuDrawCallsAvoidable +=
                        activeSampledCandidateCount - 1L;
            }

            /*
             * Patch 087 feeds the exact resident candidate count into one
             * hidden vkCmdDrawIndirect precommit. The publication token is
             * still active here; LevelRenderer ends it immediately after this
             * method returns. Any mismatch remains fail-open and leaves the
             * visible OpenGL SOLID layer untouched.
             */
            if (activeSampledCandidateCount > 0
                    && activeFrameSequence
                    == VulkanExactFramePublicationToken
                    .currentFrameSequence()
                    && activeCameraFingerprint
                    == VulkanExactFramePublicationToken
                    .currentCameraFingerprint()) {

                VulkanRegionArenaIngress
                        .submitIndirectCandidateBatch(
                                activeSampledCandidateCount,
                                activeFrameSequence,
                                activeCameraFingerprint
                        );
            } else if (activeSampledCandidateCount > 0) {
                frameTokenMismatchCount++;
            }
        }

        solidLayerEndCount++;
        active = false;
        sampleCurrentLayer = false;
        activeFrameSequence = 0L;
        activeCameraFingerprint = 0L;
        activeSampledCandidateCount = 0;
    }

    static int sampledCandidateCount() {
        return activeSampledCandidateCount;
    }

    static long candidateArenaOffset(int index) {
        return candidateArenaOffsets[index];
    }

    static int candidateVertexCount(int index) {
        return candidateVertexCounts[index];
    }

    static int candidateUsedBytes(int index) {
        return candidateUsedBytes[index];
    }

    static long candidateSourceGeneration(int index) {
        return candidateSourceGenerations[index];
    }

    static float candidateChunkOffsetX(int index) {
        return candidateChunkOffsets[index * 3];
    }

    static float candidateChunkOffsetY(int index) {
        return candidateChunkOffsets[index * 3 + 1];
    }

    static float candidateChunkOffsetZ(int index) {
        return candidateChunkOffsets[index * 3 + 2];
    }

    public static void enrich(JsonObject report) {
        long hot = 0L;
        long warm = 0L;
        long cold = 0L;
        long latestFrame =
                VulkanExactFramePublicationToken
                        .lastCompletedFrameSequence();

        for (SectionClusterRecord record
                : records.values()) {
            if (record.lastVisibleFrame <= 0L
                    || latestFrame <= 0L) {
                cold++;
                continue;
            }

            long age =
                    Math.max(
                            0L,
                            latestFrame
                                    - record.lastVisibleFrame
                    );

            if (age <= 1L) {
                hot++;
            } else if (age <= WARM_LAYER_WINDOW) {
                warm++;
            } else {
                cold++;
            }
        }

        report.addProperty(
                "vulkanSurfaceClusterInstalled",
                true
        );
        report.addProperty(
                "vulkanSurfaceClusterMode",
                "GPU_DRIVEN_REGION_INDIRECT_REAL_ARENA_GEOMETRY_PROOF_STAGE2_SPARSE_POST_PROOF"
        );
        report.addProperty(
                "vulkanSurfaceClusterTargetQuadsPerVirtualCluster",
                TARGET_QUADS_PER_VIRTUAL_CLUSTER
        );
        report.addProperty(
                "vulkanSurfaceClusterSampleStrideSolidLayers",
                SAMPLE_STRIDE_SOLID_LAYERS
        );
        report.addProperty(
                "vulkanSurfaceClusterPostProofSampleStrideSolidLayers",
                POST_PROOF_SAMPLE_STRIDE_SOLID_LAYERS
        );
        report.addProperty(
                "vulkanSurfaceClusterCallerSideSamplingGate",
                true
        );
        report.addProperty(
                "vulkanSurfaceClusterActiveSampleStrideSolidLayers",
                activeSampleStride
        );
        report.addProperty(
                "vulkanSurfaceClusterIndirectProofObservedSticky",
                indirectProofObserved
        );
        report.addProperty(
                "vulkanSurfaceClusterWarmLayerWindow",
                WARM_LAYER_WINDOW
        );
        report.addProperty(
                "vulkanSurfaceClusterMetadataRecordCount",
                records.size()
        );
        report.addProperty(
                "vulkanSurfaceClusterUploadObservationCount",
                uploadObservationCount
        );
        report.addProperty(
                "vulkanSurfaceClusterUploadedVertexCount",
                uploadedVertexCount
        );
        report.addProperty(
                "vulkanSurfaceClusterUploadedQuadCount",
                uploadedQuadCount
        );
        report.addProperty(
                "vulkanSurfaceClusterPlannedVirtualClusterCount",
                plannedVirtualClusterCount
        );
        report.addProperty(
                "vulkanSurfaceClusterSolidLayerBeginCount",
                solidLayerBeginCount
        );
        report.addProperty(
                "vulkanSurfaceClusterSolidLayerEndCount",
                solidLayerEndCount
        );
        report.addProperty(
                "vulkanSurfaceClusterSampledSolidLayerCount",
                sampledSolidLayerCount
        );
        report.addProperty(
                "vulkanSurfaceClusterSectionDrawObservationCount",
                sectionDrawObservationCount
        );
        report.addProperty(
                "vulkanSurfaceClusterHotPathSkippedObservationCount",
                hotPathSkippedObservationCount
        );
        report.addProperty(
                "vulkanSurfaceClusterPostProofSampledLayerCount",
                postProofSampledLayerCount
        );
        report.addProperty(
                "vulkanSurfaceClusterMetadataLookupAvoidedCount",
                hotPathSkippedObservationCount
        );
        report.addProperty(
                "vulkanSurfaceClusterMetadataHitCount",
                metadataHitCount
        );
        report.addProperty(
                "vulkanSurfaceClusterMetadataMissingCount",
                metadataMissingCount
        );
        report.addProperty(
                "vulkanSurfaceClusterStaleGenerationRejectCount",
                staleGenerationRejectCount
        );
        report.addProperty(
                "vulkanSurfaceClusterResidentCandidateCount",
                residentCandidateCount
        );
        report.addProperty(
                "vulkanSurfaceClusterNonResidentCandidateCount",
                nonResidentCandidateCount
        );
        report.addProperty(
                "vulkanSurfaceClusterResidentVirtualClusterCount",
                residentVirtualClusterCount
        );
        report.addProperty(
                "vulkanSurfaceClusterIndirectCandidateCount",
                indirectCandidateCount
        );
        report.addProperty(
                "vulkanSurfaceClusterPeakSampledCandidateCount",
                peakSampledCandidateCount
        );
        report.addProperty(
                "vulkanSurfaceClusterIndirectCandidateOverflowCount",
                indirectCandidateOverflowCount
        );
        report.addProperty(
                "vulkanSurfaceClusterEstimatedCpuDrawCallsAvoidable",
                estimatedCpuDrawCallsAvoidable
        );
        report.addProperty(
                "vulkanSurfaceClusterArenaVisibleTouchCount",
                arenaVisibleTouchCount
        );
        report.addProperty(
                "vulkanSurfaceClusterFrameTokenMismatchCount",
                frameTokenMismatchCount
        );
        report.addProperty(
                "vulkanSurfaceClusterHotRecordCount",
                hot
        );
        report.addProperty(
                "vulkanSurfaceClusterWarmRecordCount",
                warm
        );
        report.addProperty(
                "vulkanSurfaceClusterColdRecordCount",
                cold
        );
        report.addProperty(
                "vulkanSurfaceClusterSpatialMeshletExtraction",
                false
        );
        report.addProperty(
                "vulkanSurfaceClusterGpuComputeCulling",
                false
        );
        report.addProperty(
                "vulkanSurfaceClusterIndirectDrawExecution",
                VulkanRegionArenaIngress
                        .indirectDrawExecutionObserved()
        );
        report.addProperty(
                "vulkanSurfaceClusterRealArenaGeometryProof",
                realArenaGeometryProofObserved
        );
        report.addProperty(
                "vulkanSurfaceClusterRealArenaGeometryProofSticky",
                true
        );
        report.addProperty(
                "vulkanSurfaceClusterVisibleIndirectOwnership",
                false
        );
        report.addProperty(
                "vulkanSurfaceClusterIndirectPrecommitVerified",
                realArenaGeometryProofObserved
        );
        report.addProperty(
                "vulkanSurfaceClusterOpenGlVisibleAuthority",
                true
        );
        report.addProperty(
                "vulkanSurfaceClusterMutatesOpenGlDraws",
                false
        );
        report.addProperty(
                "vulkanSurfaceClusterVerified",
                uploadObservationCount > 0L
                        && solidLayerBeginCount > 0L
                        && solidLayerBeginCount
                        == solidLayerEndCount
                        && metadataHitCount > 0L
                        && residentCandidateCount > 0L
                        && frameTokenMismatchCount == 0L
        );
        report.addProperty(
                "vulkanSurfaceClusterNextMilestone",
                "POTATO_ENGINE_VISIBLE_REGION_INDIRECT_SOLID"
        );

        VulkanVisiblePreflightGovernor.enrich(report);
    }

    private static final class SectionClusterRecord {
        private long sourceGeneration;
        private int vertexCount;
        private int quadCount;
        private int virtualClusterCount;
        private long lastVisibleFrame;
        private float lastChunkOffsetX;
        private float lastChunkOffsetY;
        private float lastChunkOffsetZ;
    }
}
