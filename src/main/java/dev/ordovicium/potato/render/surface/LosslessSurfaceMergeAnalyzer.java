package dev.ordovicium.potato.render.surface;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.MeshData;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Bounded census for a decoupled Potato surface representation.
 *
 * <p>Patch 047 proved that strict face merging is heavily limited by baked
 * per-vertex color/lightmap values. This analyzer therefore asks a different
 * question:</p>
 *
 * <p>"How much geometry could be merged if each 1x1 surface tile kept its own
 * material and four-corner shading payload in a separate compact buffer?"</p>
 *
 * <p>The geometry layer may then cover many tiles with one large quad while a
 * shader selects the correct atlas subrect and reconstructs the original
 * per-tile vertex color/lightmap interpolation.</p>
 *
 * <p>No visible mesh is modified by this census.</p>
 */
public final class LosslessSurfaceMergeAnalyzer {

    private static final int BLOCK_VERTEX_STRIDE_BYTES =
            32;

    private static final int POSITION_OFFSET =
            0;

    private static final int COLOR_OFFSET =
            12;

    private static final int UV0_OFFSET =
            16;

    private static final int UV2_OFFSET =
            24;

    private static final int NORMAL_OFFSET =
            28;

    private static final int DIRECTION_COUNT =
            6;

    private static final int PLANE_COUNT =
            17;

    private static final int GRID_SIZE =
            16;

    private static final int CELLS_PER_PLANE =
            GRID_SIZE
                    * GRID_SIZE;

    private static final int CELL_SLOT_COUNT =
            DIRECTION_COUNT
                    * PLANE_COUNT
                    * CELLS_PER_PLANE;

    private static final int WARMUP_ELIGIBLE_UPLOADS =
            64;

    private static final int ELIGIBLE_UPLOAD_STRIDE =
            8;

    private static final int MAX_ANALYZED_MESHES =
            48;

    private static final long MAX_ANALYZED_QUADS =
            500_000L;

    private static final int MAX_QUADS_PER_MESH =
            20_000;

    private static final float POSITION_EPSILON =
            0.0005f;

    private static final float UV_EPSILON =
            0.000001f;

    private static final Object LOCK =
            new Object();

    private static volatile boolean retired;

    private static final int[] cellGeneration =
            new int[CELL_SLOT_COUNT];

    private static final int[] duplicateGeneration =
            new int[CELL_SLOT_COUNT];

    private static final int[] topologyVisitedGeneration =
            new int[CELL_SLOT_COUNT];

    private static final int[] homogeneousVisitedGeneration =
            new int[CELL_SLOT_COUNT];

    private static final int[] prototypeVisitedGeneration =
            new int[CELL_SLOT_COUNT];

    /*
     * Complete per-tile attribute signature:
     *
     * - four RGBA8 vertex colors;
     * - four packed UV2/lightmap values;
     * - atlas UV rectangle;
     * - UV orientation;
     * - packed normal.
     *
     * Topology merging deliberately ignores this signature. The homogeneous
     * cover uses it as a comparison baseline.
     */
    private static final long[] attribute0 =
            new long[CELL_SLOT_COUNT];

    private static final long[] attribute1 =
            new long[CELL_SLOT_COUNT];

    private static final long[] attribute2 =
            new long[CELL_SLOT_COUNT];

    private static final long[] attribute3 =
            new long[CELL_SLOT_COUNT];

    private static final long[] attribute4 =
            new long[CELL_SLOT_COUNT];

    private static final long[] attribute5 =
            new long[CELL_SLOT_COUNT];

    private static final int[] tileMinURaw =
            new int[CELL_SLOT_COUNT];

    private static final int[] tileMaxURaw =
            new int[CELL_SLOT_COUNT];

    private static final int[] tileMinVRaw =
            new int[CELL_SLOT_COUNT];

    private static final int[] tileMaxVRaw =
            new int[CELL_SLOT_COUNT];

    private static final int[] tileOrientation =
            new int[CELL_SLOT_COUNT];

    private static final int[] tileNormal =
            new int[CELL_SLOT_COUNT];

    /*
     * Two bits per original QUAD vertex. Each pair stores which canonical
     * tile corner (0..3) that original vertex occupied. The sequential QUAD
     * index pattern can therefore be reconstructed exactly in the shader.
     *
     * This reuses the final 32-bit padding slot already present in the
     * 64-byte Patch 048 tile payload; the stride does not grow.
     */
    private static final int[] tileCornerOrder =
            new int[CELL_SLOT_COUNT];

    private static int currentGeneration =
            1;

    private static final float[] scratchX =
            new float[4];

    private static final float[] scratchY =
            new float[4];

    private static final float[] scratchZ =
            new float[4];

    private static final float[] scratchU =
            new float[4];

    private static final float[] scratchV =
            new float[4];

    private static final int[] scratchColor =
            new int[4];

    private static final int[] scratchLightmap =
            new int[4];

    private static final int[] scratchNormal =
            new int[4];

    private static final int[] scratchCanonicalColor =
            new int[4];

    private static final int[] scratchCanonicalLightmap =
            new int[4];

    private static long eligibleUploadCount;
    private static long warmupSkippedUploadCount;
    private static long strideSkippedUploadCount;

    private static long analyzedMeshCount;
    private static long analyzedQuadCount;
    private static long oversizeMeshSkippedCount;
    private static long parserFailureCount;

    private static long topologyCandidateFaceCount;
    private static long topologyUniqueFaceCount;

    private static long rejectedNonAxisOrNonUnitFaceCount;
    private static long rejectedOutsideSectionGridCount;
    private static long rejectedNormalCount;
    private static long rejectedUvMappingCount;
    private static long duplicateCellCount;

    private static long nonUniformVertexColorFaceCount;
    private static long nonUniformVertexLightmapFaceCount;
    private static long nonUniformVertexShadingFaceCount;

    private static long topologySourceFaceCount;
    private static long topologyOutputQuadCount;
    private static long topologyMergeableSourceFaceCount;
    private static long topologyEliminatedQuadCount;
    private static int topologyMaximumRectangleArea;

    private static long homogeneousSourceFaceCount;
    private static long homogeneousOutputQuadCount;
    private static long homogeneousEliminatedQuadCount;
    private static int homogeneousMaximumRectangleArea;

    private static long topologyRectangleArea2To3Count;
    private static long topologyRectangleArea4To7Count;
    private static long topologyRectangleArea8To15Count;
    private static long topologyRectangleArea16To31Count;
    private static long topologyRectangleArea32To63Count;
    private static long topologyRectangleArea64PlusCount;

    private static long prototypeCandidateMeshCount;
    private static long prototypeSubmissionAttemptCount;
    private static long prototypeSubmissionAcceptedCount;
    private static long prototypeSourceFaceCount;
    private static long prototypeRectangleCount;
    private static long prototypeTileCount;
    private static long prototypeDescriptorBytes;
    private static long prototypeTileAttributeBytes;
    private static int prototypeMaximumRectangleArea;

        private static final boolean RUNTIME_ANALYSIS_ENABLED =
            Boolean.getBoolean(
                    "potato.debug.surfaceTileAnalysis"
            );

    private LosslessSurfaceMergeAnalyzer() {
    }

    public static void observeEligibleUpload(
            MeshData meshData
    ) {
        if (!RUNTIME_ANALYSIS_ENABLED) {
            return;
        }
        if (retired) {
            return;
        }

        synchronized (LOCK) {
            if (retired) {
                return;
            }

            eligibleUploadCount++;

            if (eligibleUploadCount
                    <= WARMUP_ELIGIBLE_UPLOADS) {

                warmupSkippedUploadCount++;

                return;
            }

            long postWarmupIndex =
                    eligibleUploadCount
                            - WARMUP_ELIGIBLE_UPLOADS
                            - 1L;

            if (postWarmupIndex
                    % ELIGIBLE_UPLOAD_STRIDE
                    != 0L) {

                strideSkippedUploadCount++;

                return;
            }

            if (meshData == null
                    || meshData.drawState() == null
                    || meshData.vertexBuffer() == null) {

                parserFailureCount++;

                return;
            }

            int vertexCount =
                    meshData.drawState()
                            .vertexCount();

            if (vertexCount <= 0
                    || vertexCount % 4 != 0) {

                parserFailureCount++;

                return;
            }

            int quadCount =
                    vertexCount
                            / 4;

            if (quadCount > MAX_QUADS_PER_MESH) {
                oversizeMeshSkippedCount++;

                return;
            }

            if (analyzedMeshCount
                    >= MAX_ANALYZED_MESHES
                    || analyzedQuadCount
                    >= MAX_ANALYZED_QUADS) {

                retired =
                        true;

                return;
            }

            try {
                analyzeMesh(
                        meshData,
                        vertexCount,
                        quadCount
                );

                analyzedMeshCount++;
                analyzedQuadCount +=
                        quadCount;
            } catch (Throwable throwable) {
                parserFailureCount++;
            }

            if (analyzedMeshCount
                    >= MAX_ANALYZED_MESHES
                    || analyzedQuadCount
                    >= MAX_ANALYZED_QUADS) {

                retired =
                        true;
            }
        }
    }

    public static boolean verified() {
        return analyzedMeshCount > 0
                && analyzedQuadCount > 0
                && topologyUniqueFaceCount > 0
                && topologySourceFaceCount > 0
                && parserFailureCount == 0;
    }

    public static void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "losslessSurfaceMergingInstalled",
                true
        );
        report.addProperty(
                "losslessSurfaceMergingMode",
                "SURFACE_TILE_ATTRIBUTE_GEOMETRY_DECOUPLING_CENSUS"
        );

        report.addProperty(
                "surfaceTileDecouplingConcept",
                "LARGE_TOPOLOGY_QUAD_PLUS_PER_TILE_ATTRIBUTE_BUFFER"
        );
        report.addProperty(
                "surfaceTileAttributePayloadCarriesFourCornerColor",
                true
        );
        report.addProperty(
                "surfaceTileAttributePayloadCarriesFourCornerLightmap",
                true
        );
        report.addProperty(
                "surfaceTileAttributePayloadCarriesAtlasUvBounds",
                true
        );
        report.addProperty(
                "surfaceTileAttributePayloadCarriesUvOrientation",
                true
        );
        report.addProperty(
                "surfaceTileAttributePayloadCarriesOriginalCornerOrder",
                true
        );
        report.addProperty(
                "surfaceTileAttributePayloadCarriesExactTriangleDiagonal",
                true
        );
        report.addProperty(
                "surfaceTileTileAttributeStrideUnchangedByCornerOrder",
                true
        );

        report.addProperty(
                "losslessSurfaceMergingEligibleUploadCount",
                eligibleUploadCount
        );
        report.addProperty(
                "losslessSurfaceMergingWarmupEligibleUploads",
                WARMUP_ELIGIBLE_UPLOADS
        );
        report.addProperty(
                "losslessSurfaceMergingEligibleUploadStride",
                ELIGIBLE_UPLOAD_STRIDE
        );
        report.addProperty(
                "losslessSurfaceMergingWarmupSkippedUploadCount",
                warmupSkippedUploadCount
        );
        report.addProperty(
                "losslessSurfaceMergingStrideSkippedUploadCount",
                strideSkippedUploadCount
        );

        report.addProperty(
                "losslessSurfaceMergingAnalyzedMeshCount",
                analyzedMeshCount
        );
        report.addProperty(
                "losslessSurfaceMergingAnalyzedQuadCount",
                analyzedQuadCount
        );
        report.addProperty(
                "losslessSurfaceMergingOversizeMeshSkippedCount",
                oversizeMeshSkippedCount
        );
        report.addProperty(
                "losslessSurfaceMergingParserFailureCount",
                parserFailureCount
        );

        report.addProperty(
                "surfaceTileTopologyCandidateFaceCount",
                topologyCandidateFaceCount
        );
        report.addProperty(
                "surfaceTileTopologyUniqueFaceCount",
                topologyUniqueFaceCount
        );

        report.addProperty(
                "surfaceTileRejectedNonAxisOrNonUnitFaceCount",
                rejectedNonAxisOrNonUnitFaceCount
        );
        report.addProperty(
                "surfaceTileRejectedOutsideSectionGridCount",
                rejectedOutsideSectionGridCount
        );
        report.addProperty(
                "surfaceTileRejectedNormalCount",
                rejectedNormalCount
        );
        report.addProperty(
                "surfaceTileRejectedUvMappingCount",
                rejectedUvMappingCount
        );
        report.addProperty(
                "surfaceTileDuplicateCellCount",
                duplicateCellCount
        );

        report.addProperty(
                "surfaceTileNonUniformVertexColorFaceCount",
                nonUniformVertexColorFaceCount
        );
        report.addProperty(
                "surfaceTileNonUniformVertexLightmapFaceCount",
                nonUniformVertexLightmapFaceCount
        );
        report.addProperty(
                "surfaceTileNonUniformVertexShadingFaceCount",
                nonUniformVertexShadingFaceCount
        );

        report.addProperty(
                "surfaceTileTopologySourceFaceCount",
                topologySourceFaceCount
        );
        report.addProperty(
                "surfaceTileTopologyOutputQuadCount",
                topologyOutputQuadCount
        );
        report.addProperty(
                "surfaceTileTopologyMergeableSourceFaceCount",
                topologyMergeableSourceFaceCount
        );
        report.addProperty(
                "surfaceTileTopologyEliminatedQuadCount",
                topologyEliminatedQuadCount
        );

        report.addProperty(
                "surfaceTileTopologyQuadReductionPercent",
                topologySourceFaceCount == 0
                        ? 0.0
                        : topologyEliminatedQuadCount
                        * 100.0
                        / topologySourceFaceCount
        );

        report.addProperty(
                "surfaceTileTopologyCompressionRatio",
                topologyOutputQuadCount == 0
                        ? 0.0
                        : topologySourceFaceCount
                        * 1.0
                        / topologyOutputQuadCount
        );

        report.addProperty(
                "surfaceTileTopologyMaximumRectangleArea",
                topologyMaximumRectangleArea
        );

        report.addProperty(
                "surfaceTileHomogeneousAttributeSourceFaceCount",
                homogeneousSourceFaceCount
        );
        report.addProperty(
                "surfaceTileHomogeneousAttributeOutputQuadCount",
                homogeneousOutputQuadCount
        );
        report.addProperty(
                "surfaceTileHomogeneousAttributeEliminatedQuadCount",
                homogeneousEliminatedQuadCount
        );
        report.addProperty(
                "surfaceTileHomogeneousAttributeQuadReductionPercent",
                homogeneousSourceFaceCount == 0
                        ? 0.0
                        : homogeneousEliminatedQuadCount
                        * 100.0
                        / homogeneousSourceFaceCount
        );
        report.addProperty(
                "surfaceTileHomogeneousAttributeMaximumRectangleArea",
                homogeneousMaximumRectangleArea
        );

        report.addProperty(
                "surfaceTileDecouplingAdditionalEliminatedQuadCount",
                Math.max(
                        0L,
                        topologyEliminatedQuadCount
                                - homogeneousEliminatedQuadCount
                )
        );

        report.addProperty(
                "surfaceTileTopologyRectangleArea2To3Count",
                topologyRectangleArea2To3Count
        );
        report.addProperty(
                "surfaceTileTopologyRectangleArea4To7Count",
                topologyRectangleArea4To7Count
        );
        report.addProperty(
                "surfaceTileTopologyRectangleArea8To15Count",
                topologyRectangleArea8To15Count
        );
        report.addProperty(
                "surfaceTileTopologyRectangleArea16To31Count",
                topologyRectangleArea16To31Count
        );
        report.addProperty(
                "surfaceTileTopologyRectangleArea32To63Count",
                topologyRectangleArea32To63Count
        );
        report.addProperty(
                "surfaceTileTopologyRectangleArea64PlusCount",
                topologyRectangleArea64PlusCount
        );

        report.addProperty(
                "surfaceTilePotentialVertexReductionCount",
                topologyEliminatedQuadCount
                        * 4L
        );
        report.addProperty(
                "surfaceTilePotentialSequentialIndexReductionCount",
                topologyEliminatedQuadCount
                        * 6L
        );

        report.addProperty(
                "surfaceTilePrototypeCandidateMeshCount",
                prototypeCandidateMeshCount
        );
        report.addProperty(
                "surfaceTilePrototypeSubmissionAttemptCount",
                prototypeSubmissionAttemptCount
        );
        report.addProperty(
                "surfaceTilePrototypeSubmissionAcceptedCount",
                prototypeSubmissionAcceptedCount
        );
        report.addProperty(
                "surfaceTilePrototypeSourceFaceCount",
                prototypeSourceFaceCount
        );
        report.addProperty(
                "surfaceTilePrototypeRectangleCount",
                prototypeRectangleCount
        );
        report.addProperty(
                "surfaceTilePrototypeTileCount",
                prototypeTileCount
        );
        report.addProperty(
                "surfaceTilePrototypeDescriptorBytes",
                prototypeDescriptorBytes
        );
        report.addProperty(
                "surfaceTilePrototypeTileAttributeBytes",
                prototypeTileAttributeBytes
        );
        report.addProperty(
                "surfaceTilePrototypeMaximumRectangleArea",
                prototypeMaximumRectangleArea
        );
        report.addProperty(
                "surfaceTilePrototypeDispatcherCompleted",
                SurfaceTileMeshPrototypeDispatcher.completed()
        );

        report.addProperty(
                "surfaceTileObservationRetired",
                retired
        );

        report.addProperty(
                "losslessSurfaceMergingMutatesMeshData",
                false
        );
        report.addProperty(
                "losslessSurfaceMergingCancelsOpenGlDraws",
                false
        );
        report.addProperty(
                "losslessSurfaceMergingMutatesWorldTicking",
                false
        );
        report.addProperty(
                "losslessSurfaceMergingMutatesChunkLoading",
                false
        );
        report.addProperty(
                "losslessSurfaceMergingMutatesSaving",
                false
        );

        report.addProperty(
                "surfaceTileWithinSectionOnly",
                true
        );
        report.addProperty(
                "surfaceTileCrossSectionMergeDeferred",
                true
        );
        report.addProperty(
                "surfaceTileRenderLayerIdentityCaptured",
                false
        );
        report.addProperty(
                "surfaceTileProductionMutationSafe",
                false
        );

        report.addProperty(
                "surfaceTileExactVisualReconstructionRequiresPiecewiseTriangleShading",
                true
        );
        report.addProperty(
                "surfaceTileExactVisualReconstructionRequiresAtlasSubrectSampling",
                true
        );
        report.addProperty(
                "surfaceTileTopologyMergeIgnoresPerTileShadingDifferences",
                true
        );
        report.addProperty(
                "surfaceTileTopologyMergeIgnoresPerTileMaterialDifferences",
                true
        );

        report.addProperty(
                "losslessSurfaceMergingPerQuadObjectAllocation",
                false
        );

        report.addProperty(
                "losslessSurfaceMergingVerified",
                verified()
        );
    }

    private static void analyzeMesh(
            MeshData meshData,
            int vertexCount,
            int quadCount
    ) {
        beginMesh();

        ByteBuffer vertices =
                meshData.vertexBuffer()
                        .duplicate()
                        .order(
                                ByteOrder.nativeOrder()
                        );

        int requiredBytes =
                vertexCount
                        * BLOCK_VERTEX_STRIDE_BYTES;

        if (vertices.remaining()
                < requiredBytes) {

            throw new IllegalStateException(
                    "BLOCK vertex stream shorter than expected"
            );
        }

        int start =
                vertices.position();

        for (int quadIndex = 0;
             quadIndex < quadCount;
             quadIndex++) {

            int base =
                    start
                            + quadIndex
                            * 4
                            * BLOCK_VERTEX_STRIDE_BYTES;

            analyzeQuad(
                    vertices,
                    base
            );
        }

        greedyCoverCurrentMesh(
                false
        );

        greedyCoverCurrentMesh(
                true
        );

        trySubmitSurfaceTilePrototype();
    }

    private static void beginMesh() {
        currentGeneration++;

        if (currentGeneration
                == Integer.MAX_VALUE) {

            for (int index = 0;
                 index < CELL_SLOT_COUNT;
                 index++) {

                cellGeneration[index] =
                        0;

                duplicateGeneration[index] =
                        0;

                topologyVisitedGeneration[index] =
                        0;

                homogeneousVisitedGeneration[index] =
                        0;

                prototypeVisitedGeneration[index] =
                        0;
            }

            currentGeneration =
                    1;
        }
    }

    private static void analyzeQuad(
            ByteBuffer vertices,
            int base
    ) {
        for (int vertex = 0;
             vertex < 4;
             vertex++) {

            int vertexBase =
                    base
                            + vertex
                            * BLOCK_VERTEX_STRIDE_BYTES;

            scratchX[vertex] =
                    vertices.getFloat(
                            vertexBase
                                    + POSITION_OFFSET
                    );

            scratchY[vertex] =
                    vertices.getFloat(
                            vertexBase
                                    + POSITION_OFFSET
                                    + 4
                    );

            scratchZ[vertex] =
                    vertices.getFloat(
                            vertexBase
                                    + POSITION_OFFSET
                                    + 8
                    );

            scratchU[vertex] =
                    vertices.getFloat(
                            vertexBase
                                    + UV0_OFFSET
                    );

            scratchV[vertex] =
                    vertices.getFloat(
                            vertexBase
                                    + UV0_OFFSET
                                    + 4
                    );

            scratchColor[vertex] =
                    vertices.getInt(
                            vertexBase
                                    + COLOR_OFFSET
                    );

            scratchLightmap[vertex] =
                    vertices.getInt(
                            vertexBase
                                    + UV2_OFFSET
                    );

            scratchNormal[vertex] =
                    packedNormal(
                            vertices,
                            vertexBase
                                    + NORMAL_OFFSET
                    );
        }

        boolean colorUniform =
                allEqual(
                        scratchColor
                );

        boolean lightmapUniform =
                allEqual(
                        scratchLightmap
                );

        if (!colorUniform) {
            nonUniformVertexColorFaceCount++;
        }

        if (!lightmapUniform) {
            nonUniformVertexLightmapFaceCount++;
        }

        if (!colorUniform
                || !lightmapUniform) {

            nonUniformVertexShadingFaceCount++;
        }

        float minX =
                minimum(
                        scratchX
                );

        float maxX =
                maximum(
                        scratchX
                );

        float minY =
                minimum(
                        scratchY
                );

        float maxY =
                maximum(
                        scratchY
                );

        float minZ =
                minimum(
                        scratchZ
                );

        float maxZ =
                maximum(
                        scratchZ
                );

        float rangeX =
                maxX
                        - minX;

        float rangeY =
                maxY
                        - minY;

        float rangeZ =
                maxZ
                        - minZ;

        int constantAxis =
                constantAxis(
                        rangeX,
                        rangeY,
                        rangeZ
                );

        if (constantAxis < 0) {
            rejectedNonAxisOrNonUnitFaceCount++;

            return;
        }

        if (!allEqual(
                scratchNormal
        )) {
            rejectedNormalCount++;

            return;
        }

        int direction =
                directionForNormal(
                        scratchNormal[0],
                        constantAxis
                );

        if (direction < 0) {
            rejectedNormalCount++;

            return;
        }

        float planeCoordinate;
        float minA;
        float maxA;
        float minB;
        float maxB;

        if (constantAxis == 0) {
            planeCoordinate =
                    minX;

            minA =
                    minY;

            maxA =
                    maxY;

            minB =
                    minZ;

            maxB =
                    maxZ;
        } else if (constantAxis == 1) {
            planeCoordinate =
                    minY;

            minA =
                    minX;

            maxA =
                    maxX;

            minB =
                    minZ;

            maxB =
                    maxZ;
        } else {
            planeCoordinate =
                    minZ;

            minA =
                    minX;

            maxA =
                    maxX;

            minB =
                    minY;

            maxB =
                    maxY;
        }

        int plane =
                integerCoordinate(
                        planeCoordinate
                );

        int cellA =
                integerCoordinate(
                        minA
                );

        int cellB =
                integerCoordinate(
                        minB
                );

        int maxCellA =
                integerCoordinate(
                        maxA
                );

        int maxCellB =
                integerCoordinate(
                        maxB
                );

        if (plane < 0
                || plane > 16
                || cellA < 0
                || cellA > 15
                || cellB < 0
                || cellB > 15
                || maxCellA
                != cellA + 1
                || maxCellB
                != cellB + 1) {

            rejectedOutsideSectionGridCount++;

            return;
        }

        float minU =
                minimum(
                        scratchU
                );

        float maxU =
                maximum(
                        scratchU
                );

        float minV =
                minimum(
                        scratchV
                );

        float maxV =
                maximum(
                        scratchV
                );

        int orientation =
                uvOrientation(
                        constantAxis,
                        minA,
                        minB,
                        minU,
                        maxU,
                        minV,
                        maxV
                );

        if (orientation < 0) {
            rejectedUvMappingCount++;

            return;
        }

        topologyCandidateFaceCount++;

        int slot =
                cellSlot(
                        direction,
                        plane,
                        cellA,
                        cellB
                );

        if (cellGeneration[slot]
                == currentGeneration) {

            duplicateCellCount++;

            if (duplicateGeneration[slot]
                    != currentGeneration) {

                duplicateGeneration[slot] =
                        currentGeneration;

                topologyUniqueFaceCount--;
            }

            return;
        }

        cellGeneration[slot] =
                currentGeneration;

        tileCornerOrder[slot] =
                canonicalizeCornerAttributes(
                        constantAxis,
                        minA,
                        minB
                );

        attribute0[slot] =
                unsignedPair(
                        scratchCanonicalColor[0],
                        scratchCanonicalColor[1]
                );

        attribute1[slot] =
                unsignedPair(
                        scratchCanonicalColor[2],
                        scratchCanonicalColor[3]
                );

        attribute2[slot] =
                unsignedPair(
                        scratchCanonicalLightmap[0],
                        scratchCanonicalLightmap[1]
                );

        attribute3[slot] =
                unsignedPair(
                        scratchCanonicalLightmap[2],
                        scratchCanonicalLightmap[3]
                );

        attribute4[slot] =
                unsignedPair(
                        Float.floatToRawIntBits(
                                minU
                        ),
                        Float.floatToRawIntBits(
                                maxU
                        )
                );

        attribute5[slot] =
                unsignedPair(
                        Float.floatToRawIntBits(
                                minV
                        ),
                        Float.floatToRawIntBits(
                                maxV
                        )
                )
                        ^ (
                        ((long) orientation
                                & 0xFFL)
                                << 48
                )
                        ^ (
                        (
                                (long) scratchNormal[0]
                                        & 0xFFFFFFL
                        )
                                << 24
                );

        tileMinURaw[slot] =
                Float.floatToRawIntBits(
                        minU
                );

        tileMaxURaw[slot] =
                Float.floatToRawIntBits(
                        maxU
                );

        tileMinVRaw[slot] =
                Float.floatToRawIntBits(
                        minV
                );

        tileMaxVRaw[slot] =
                Float.floatToRawIntBits(
                        maxV
                );

        tileOrientation[slot] =
                orientation;

        tileNormal[slot] =
                scratchNormal[0];

        topologyUniqueFaceCount++;
    }

    private static void greedyCoverCurrentMesh(
            boolean requireHomogeneousAttributes
    ) {
        for (int direction = 0;
             direction < DIRECTION_COUNT;
             direction++) {

            for (int plane = 0;
                 plane < PLANE_COUNT;
                 plane++) {

                greedyCoverPlane(
                        direction,
                        plane,
                        requireHomogeneousAttributes
                );
            }
        }
    }

    private static void greedyCoverPlane(
            int direction,
            int plane,
            boolean requireHomogeneousAttributes
    ) {
        for (int cellB = 0;
             cellB < GRID_SIZE;
             cellB++) {

            for (int cellA = 0;
                 cellA < GRID_SIZE;
                 cellA++) {

                int startSlot =
                        cellSlot(
                                direction,
                                plane,
                                cellA,
                                cellB
                        );

                if (!available(
                        startSlot,
                        requireHomogeneousAttributes
                )) {
                    continue;
                }

                int width =
                        1;

                while (cellA + width
                        < GRID_SIZE) {

                    int slot =
                            cellSlot(
                                    direction,
                                    plane,
                                    cellA + width,
                                    cellB
                            );

                    if (!available(
                            slot,
                            requireHomogeneousAttributes
                    )
                            || (
                            requireHomogeneousAttributes
                                    && !sameAttributes(
                                    startSlot,
                                    slot
                            )
                    )) {
                        break;
                    }

                    width++;
                }

                int height =
                        1;

                while (cellB + height
                        < GRID_SIZE) {

                    boolean rowCompatible =
                            true;

                    for (int x = 0;
                         x < width;
                         x++) {

                        int slot =
                                cellSlot(
                                        direction,
                                        plane,
                                        cellA + x,
                                        cellB + height
                                );

                        if (!available(
                                slot,
                                requireHomogeneousAttributes
                        )
                                || (
                                requireHomogeneousAttributes
                                        && !sameAttributes(
                                        startSlot,
                                        slot
                                )
                        )) {

                            rowCompatible =
                                    false;

                            break;
                        }
                    }

                    if (!rowCompatible) {
                        break;
                    }

                    height++;
                }

                int area =
                        width
                                * height;

                for (int y = 0;
                     y < height;
                     y++) {

                    for (int x = 0;
                         x < width;
                         x++) {

                        int slot =
                                cellSlot(
                                        direction,
                                        plane,
                                        cellA + x,
                                        cellB + y
                                );

                        markVisited(
                                slot,
                                requireHomogeneousAttributes
                        );
                    }
                }

                if (requireHomogeneousAttributes) {
                    homogeneousSourceFaceCount +=
                            area;

                    homogeneousOutputQuadCount++;

                    homogeneousMaximumRectangleArea =
                            Math.max(
                                    homogeneousMaximumRectangleArea,
                                    area
                            );

                    if (area > 1) {
                        homogeneousEliminatedQuadCount +=
                                area - 1L;
                    }

                    continue;
                }

                topologySourceFaceCount +=
                        area;

                topologyOutputQuadCount++;

                topologyMaximumRectangleArea =
                        Math.max(
                                topologyMaximumRectangleArea,
                                area
                        );

                if (area > 1) {
                    topologyMergeableSourceFaceCount +=
                            area;

                    topologyEliminatedQuadCount +=
                            area - 1L;

                    recordTopologyRectangleArea(
                            area
                    );
                }
            }
        }
    }

    private static void trySubmitSurfaceTilePrototype() {
        if (SurfaceTileMeshPrototypeDispatcher.completed()) {
            return;
        }

        int currentSourceFaceCount =
                currentUniqueFaceCount();

        if (currentSourceFaceCount < 64) {
            return;
        }

        SurfaceTileMeshSnapshot snapshot =
                buildSurfaceTileSnapshot(
                        currentSourceFaceCount
                );

        if (snapshot == null
                || snapshot.rectangleCount()
                >= snapshot.tileCount()) {

            return;
        }

        prototypeCandidateMeshCount++;
        prototypeSubmissionAttemptCount++;

        prototypeSourceFaceCount =
                snapshot.sourceFaceCount();

        prototypeRectangleCount =
                snapshot.rectangleCount();

        prototypeTileCount =
                snapshot.tileCount();

        prototypeDescriptorBytes =
                snapshot.rectangleDescriptorBytes();

        prototypeTileAttributeBytes =
                snapshot.tileAttributeBytes();

        prototypeMaximumRectangleArea =
                snapshot.maximumRectangleArea();

        if (SurfaceTileMeshPrototypeDispatcher.submit(
                snapshot
        )) {
            prototypeSubmissionAcceptedCount++;
        }
    }

    private static SurfaceTileMeshSnapshot buildSurfaceTileSnapshot(
            int sourceFaceCount
    ) {
        ByteBuffer descriptors =
                ByteBuffer.allocateDirect(
                        sourceFaceCount
                                * SurfaceTileMeshSnapshot
                                .RECTANGLE_DESCRIPTOR_STRIDE_BYTES
                )
                        .order(
                                ByteOrder.nativeOrder()
                        );

        ByteBuffer tiles =
                ByteBuffer.allocateDirect(
                        sourceFaceCount
                                * SurfaceTileMeshSnapshot
                                .TILE_ATTRIBUTE_STRIDE_BYTES
                )
                        .order(
                                ByteOrder.nativeOrder()
                        );

        int rectangleCount =
                0;

        int tileCount =
                0;

        int maximumRectangleArea =
                1;

        for (int direction = 0;
             direction < DIRECTION_COUNT;
             direction++) {

            for (int plane = 0;
                 plane < PLANE_COUNT;
                 plane++) {

                for (int cellB = 0;
                     cellB < GRID_SIZE;
                     cellB++) {

                    for (int cellA = 0;
                         cellA < GRID_SIZE;
                         cellA++) {

                        int startSlot =
                                cellSlot(
                                        direction,
                                        plane,
                                        cellA,
                                        cellB
                                );

                        if (!prototypeAvailable(
                                startSlot
                        )) {
                            continue;
                        }

                        int width =
                                1;

                        while (cellA + width
                                < GRID_SIZE) {

                            int slot =
                                    cellSlot(
                                            direction,
                                            plane,
                                            cellA + width,
                                            cellB
                                    );

                            if (!prototypeAvailable(
                                    slot
                            )) {
                                break;
                            }

                            width++;
                        }

                        int height =
                                1;

                        while (cellB + height
                                < GRID_SIZE) {

                            boolean rowAvailable =
                                    true;

                            for (int x = 0;
                                 x < width;
                                 x++) {

                                int slot =
                                        cellSlot(
                                                direction,
                                                plane,
                                                cellA + x,
                                                cellB + height
                                        );

                                if (!prototypeAvailable(
                                        slot
                                )) {
                                    rowAvailable =
                                            false;

                                    break;
                                }
                            }

                            if (!rowAvailable) {
                                break;
                            }

                            height++;
                        }

                        int area =
                                width
                                        * height;

                        int tileBase =
                                tileCount;

                        descriptors.putInt(
                                direction
                        );
                        descriptors.putInt(
                                plane
                        );
                        descriptors.putInt(
                                cellA
                        );
                        descriptors.putInt(
                                cellB
                        );
                        descriptors.putInt(
                                width
                        );
                        descriptors.putInt(
                                height
                        );
                        descriptors.putInt(
                                tileBase
                        );
                        descriptors.putInt(
                                area
                        );

                        for (int y = 0;
                             y < height;
                             y++) {

                            for (int x = 0;
                                 x < width;
                                 x++) {

                                int slot =
                                        cellSlot(
                                                direction,
                                                plane,
                                                cellA + x,
                                                cellB + y
                                        );

                                prototypeVisitedGeneration[slot] =
                                        currentGeneration;

                                putTileAttribute(
                                        tiles,
                                        slot,
                                        direction
                                );

                                tileCount++;
                            }
                        }

                        rectangleCount++;

                        maximumRectangleArea =
                                Math.max(
                                        maximumRectangleArea,
                                        area
                                );
                    }
                }
            }
        }

        if (rectangleCount <= 0
                || tileCount <= 0) {

            return null;
        }

        descriptors.flip();
        tiles.flip();

        return new SurfaceTileMeshSnapshot(
                descriptors,
                tiles,
                sourceFaceCount,
                rectangleCount,
                tileCount,
                maximumRectangleArea
        );
    }

    private static void putTileAttribute(
            ByteBuffer destination,
            int slot,
            int direction
    ) {
        destination.putInt(
                (int) attribute0[slot]
        );
        destination.putInt(
                (int) (
                        attribute0[slot]
                                >>> 32
                )
        );
        destination.putInt(
                (int) attribute1[slot]
        );
        destination.putInt(
                (int) (
                        attribute1[slot]
                                >>> 32
                )
        );

        destination.putInt(
                (int) attribute2[slot]
        );
        destination.putInt(
                (int) (
                        attribute2[slot]
                                >>> 32
                )
        );
        destination.putInt(
                (int) attribute3[slot]
        );
        destination.putInt(
                (int) (
                        attribute3[slot]
                                >>> 32
                )
        );

        destination.putInt(
                tileMinURaw[slot]
        );
        destination.putInt(
                tileMaxURaw[slot]
        );
        destination.putInt(
                tileMinVRaw[slot]
        );
        destination.putInt(
                tileMaxVRaw[slot]
        );

        destination.putInt(
                tileOrientation[slot]
        );
        destination.putInt(
                tileNormal[slot]
        );
        destination.putInt(
                direction
        );
        destination.putInt(
                tileCornerOrder[slot]
        );
    }

    private static int currentUniqueFaceCount() {
        int count =
                0;

        for (int slot = 0;
             slot < CELL_SLOT_COUNT;
             slot++) {

            if (cellGeneration[slot]
                    == currentGeneration
                    && duplicateGeneration[slot]
                    != currentGeneration) {

                count++;
            }
        }

        return count;
    }

    private static boolean prototypeAvailable(
            int slot
    ) {
        return cellGeneration[slot]
                == currentGeneration
                && duplicateGeneration[slot]
                != currentGeneration
                && prototypeVisitedGeneration[slot]
                != currentGeneration;
    }

    private static void recordTopologyRectangleArea(
            int area
    ) {
        if (area <= 3) {
            topologyRectangleArea2To3Count++;
        } else if (area <= 7) {
            topologyRectangleArea4To7Count++;
        } else if (area <= 15) {
            topologyRectangleArea8To15Count++;
        } else if (area <= 31) {
            topologyRectangleArea16To31Count++;
        } else if (area <= 63) {
            topologyRectangleArea32To63Count++;
        } else {
            topologyRectangleArea64PlusCount++;
        }
    }

    private static boolean available(
            int slot,
            boolean homogeneous
    ) {
        if (cellGeneration[slot]
                != currentGeneration
                || duplicateGeneration[slot]
                == currentGeneration) {

            return false;
        }

        if (homogeneous) {
            return homogeneousVisitedGeneration[slot]
                    != currentGeneration;
        }

        return topologyVisitedGeneration[slot]
                != currentGeneration;
    }

    private static void markVisited(
            int slot,
            boolean homogeneous
    ) {
        if (homogeneous) {
            homogeneousVisitedGeneration[slot] =
                    currentGeneration;

            return;
        }

        topologyVisitedGeneration[slot] =
                currentGeneration;
    }

    private static boolean sameAttributes(
            int first,
            int second
    ) {
        return attribute0[first]
                == attribute0[second]
                && attribute1[first]
                == attribute1[second]
                && attribute2[first]
                == attribute2[second]
                && attribute3[first]
                == attribute3[second]
                && attribute4[first]
                == attribute4[second]
                && attribute5[first]
                == attribute5[second]
                && tileCornerOrder[first]
                == tileCornerOrder[second];
    }

    private static int canonicalizeCornerAttributes(
            int constantAxis,
            float minA,
            float minB
    ) {
        for (int index = 0;
             index < 4;
             index++) {

            scratchCanonicalColor[index] =
                    0;

            scratchCanonicalLightmap[index] =
                    0;
        }

        int cornerMask =
                0;

        int cornerOrder =
                0;

        for (int vertex = 0;
             vertex < 4;
             vertex++) {

            float a;
            float b;

            if (constantAxis == 0) {
                a =
                        scratchY[vertex];

                b =
                        scratchZ[vertex];
            } else if (constantAxis == 1) {
                a =
                        scratchX[vertex];

                b =
                        scratchZ[vertex];
            } else {
                a =
                        scratchX[vertex];

                b =
                        scratchY[vertex];
            }

            int aBit =
                    endpointBit(
                            a,
                            minA,
                            minA + 1.0f
                    );

            int bBit =
                    endpointBit(
                            b,
                            minB,
                            minB + 1.0f
                    );

            if (aBit < 0
                    || bBit < 0) {

                throw new IllegalStateException(
                        "Canonical surface corner mapping failed."
                );
            }

            int corner =
                    aBit
                            | (bBit << 1);

            cornerOrder |=
                    (corner & 0x3)
                            << (vertex * 2);

            int cornerFlag =
                    1
                            << corner;

            if ((cornerMask
                    & cornerFlag) != 0) {

                throw new IllegalStateException(
                        "Duplicate canonical surface corner."
                );
            }

            cornerMask |=
                    cornerFlag;

            scratchCanonicalColor[corner] =
                    scratchColor[vertex];

            scratchCanonicalLightmap[corner] =
                    scratchLightmap[vertex];
        }

        if (cornerMask != 0xF) {
            throw new IllegalStateException(
                    "Incomplete canonical surface corner mapping."
            );
        }

        return cornerOrder;
    }

    private static int uvOrientation(
            int constantAxis,
            float minA,
            float minB,
            float minU,
            float maxU,
            float minV,
            float maxV
    ) {
        if (!Float.isFinite(
                minU
        )
                || !Float.isFinite(
                maxU
        )
                || !Float.isFinite(
                minV
        )
                || !Float.isFinite(
                maxV
        )
                || Math.abs(
                maxU - minU
        ) <= UV_EPSILON
                || Math.abs(
                maxV - minV
        ) <= UV_EPSILON) {

            return -1;
        }

        int cornerMask =
                0;

        int orientation =
                0;

        for (int vertex = 0;
             vertex < 4;
             vertex++) {

            float a;
            float b;

            if (constantAxis == 0) {
                a =
                        scratchY[vertex];

                b =
                        scratchZ[vertex];
            } else if (constantAxis == 1) {
                a =
                        scratchX[vertex];

                b =
                        scratchZ[vertex];
            } else {
                a =
                        scratchX[vertex];

                b =
                        scratchY[vertex];
            }

            int aBit =
                    endpointBit(
                            a,
                            minA,
                            minA + 1.0f
                    );

            int bBit =
                    endpointBit(
                            b,
                            minB,
                            minB + 1.0f
                    );

            int uBit =
                    endpointBit(
                            scratchU[vertex],
                            minU,
                            maxU
                    );

            int vBit =
                    endpointBit(
                            scratchV[vertex],
                            minV,
                            maxV
                    );

            if (aBit < 0
                    || bBit < 0
                    || uBit < 0
                    || vBit < 0) {

                return -1;
            }

            int corner =
                    aBit
                            | (bBit << 1);

            int cornerFlag =
                    1
                            << corner;

            if ((cornerMask
                    & cornerFlag) != 0) {

                return -1;
            }

            cornerMask |=
                    cornerFlag;

            int uvCode =
                    uBit
                            | (vBit << 1);

            orientation |=
                    uvCode
                            << (corner * 2);
        }

        if (cornerMask != 0xF) {
            return -1;
        }

        return orientation;
    }

    private static int constantAxis(
            float rangeX,
            float rangeY,
            float rangeZ
    ) {
        boolean xConstant =
                nearZero(
                        rangeX
                );

        boolean yConstant =
                nearZero(
                        rangeY
                );

        boolean zConstant =
                nearZero(
                        rangeZ
                );

        if (xConstant
                && nearOne(
                rangeY
        )
                && nearOne(
                rangeZ
        )) {

            return 0;
        }

        if (yConstant
                && nearOne(
                rangeX
        )
                && nearOne(
                rangeZ
        )) {

            return 1;
        }

        if (zConstant
                && nearOne(
                rangeX
        )
                && nearOne(
                rangeY
        )) {

            return 2;
        }

        return -1;
    }

    private static int directionForNormal(
            int packedNormal,
            int constantAxis
    ) {
        int nx =
                (byte) (
                        packedNormal
                                & 0xFF
                );

        int ny =
                (byte) (
                        (packedNormal
                                >>> 8)
                                & 0xFF
                );

        int nz =
                (byte) (
                        (packedNormal
                                >>> 16)
                                & 0xFF
                );

        if (constantAxis == 0
                && ny == 0
                && nz == 0
                && Math.abs(
                nx
        ) >= 126) {

            return nx < 0
                    ? 0
                    : 1;
        }

        if (constantAxis == 1
                && nx == 0
                && nz == 0
                && Math.abs(
                ny
        ) >= 126) {

            return ny < 0
                    ? 2
                    : 3;
        }

        if (constantAxis == 2
                && nx == 0
                && ny == 0
                && Math.abs(
                nz
        ) >= 126) {

            return nz < 0
                    ? 4
                    : 5;
        }

        return -1;
    }

    private static int packedNormal(
            ByteBuffer vertices,
            int offset
    ) {
        return Byte.toUnsignedInt(
                vertices.get(
                        offset
                )
        )
                | (
                Byte.toUnsignedInt(
                        vertices.get(
                                offset + 1
                        )
                )
                        << 8
        )
                | (
                Byte.toUnsignedInt(
                        vertices.get(
                                offset + 2
                        )
                )
                        << 16
        );
    }

    private static long unsignedPair(
            int low,
            int high
    ) {
        return Integer.toUnsignedLong(
                low
        )
                | (
                Integer.toUnsignedLong(
                        high
                )
                        << 32
        );
    }

    private static int integerCoordinate(
            float value
    ) {
        if (!Float.isFinite(
                value
        )) {
            return Integer.MIN_VALUE;
        }

        int rounded =
                Math.round(
                        value
                );

        if (Math.abs(
                value - rounded
        ) > POSITION_EPSILON) {

            return Integer.MIN_VALUE;
        }

        return rounded;
    }

    private static int endpointBit(
            float value,
            float minimum,
            float maximum
    ) {
        if (Math.abs(
                value - minimum
        ) <= UV_EPSILON) {

            return 0;
        }

        if (Math.abs(
                value - maximum
        ) <= UV_EPSILON) {

            return 1;
        }

        return -1;
    }

    private static boolean allEqual(
            int[] values
    ) {
        int first =
                values[0];

        for (int index = 1;
             index < values.length;
             index++) {

            if (values[index]
                    != first) {

                return false;
            }
        }

        return true;
    }

    private static boolean nearZero(
            float value
    ) {
        return Math.abs(
                value
        ) <= POSITION_EPSILON;
    }

    private static boolean nearOne(
            float value
    ) {
        return Math.abs(
                value - 1.0f
        ) <= POSITION_EPSILON;
    }

    private static float minimum(
            float[] values
    ) {
        float result =
                values[0];

        for (int index = 1;
             index < values.length;
             index++) {

            result =
                    Math.min(
                            result,
                            values[index]
                    );
        }

        return result;
    }

    private static float maximum(
            float[] values
    ) {
        float result =
                values[0];

        for (int index = 1;
             index < values.length;
             index++) {

            result =
                    Math.max(
                            result,
                            values[index]
                    );
        }

        return result;
    }

    private static int cellSlot(
            int direction,
            int plane,
            int cellA,
            int cellB
    ) {
        return (
                (
                        direction
                                * PLANE_COUNT
                                + plane
                )
                        * CELLS_PER_PLANE
        )
                + cellB
                * GRID_SIZE
                + cellA;
    }
}