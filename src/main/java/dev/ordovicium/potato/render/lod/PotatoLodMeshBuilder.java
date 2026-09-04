package dev.ordovicium.potato.render.lod;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Allocation-bounded worker-thread BLOCK mesh simplifier.
 *
 * <p>The builder is deliberately conservative about geometry coverage:
 * non-axis, partial-height and otherwise complex quads are copied byte-for-byte
 * into every proxy. Only exact axis-aligned 1x1 section-grid faces participate
 * in greedy merging. Tier 1 requires identical visual payload (color, UV,
 * light and normal bytes); Tier 2 may ignore per-vertex lighting/color changes
 * but still requires the same atlas UV/material identity. LOD therefore
 * simplifies broad block surfaces without deleting stairs, slabs, model
 * geometry or merging one block texture into a different texture.</p>
 */
final class PotatoLodMeshBuilder {

    private static final int BLOCK_VERTEX_STRIDE_BYTES = 32;
    private static final int QUAD_VERTEX_COUNT = 4;
    private static final int QUAD_BYTES =
            BLOCK_VERTEX_STRIDE_BYTES
                    * QUAD_VERTEX_COUNT;

    private static final int POSITION_OFFSET = 0;
    private static final int UV0_OFFSET = 16;
    private static final int NORMAL_OFFSET = 28;

    private static final int DIRECTION_COUNT = 6;
    private static final int PLANE_COUNT = 17;
    private static final int GRID_SIZE = 16;
    private static final int CELLS_PER_PLANE =
            GRID_SIZE * GRID_SIZE;
    private static final int CELL_SLOT_COUNT =
            DIRECTION_COUNT
                    * PLANE_COUNT
                    * CELLS_PER_PLANE;

    private static final float POSITION_EPSILON = 0.0005f;

    private static final int MAX_UINT16_QUADS = 16_383;

    private static final double TIER1_MAX_OUTPUT_RATIO = 0.90;

    /*
     * Patch 143 coverage-first emergency LOD. Tier 2's geometry rules are
     * unchanged; we merely keep valid 10%+ reductions instead of throwing them
     * away and falling back to full detail.
     */
    private static final double TIER2_MAX_OUTPUT_RATIO = 0.90;

    private static final int TIER1_MIN_AVOIDED_QUADS = 8;
    private static final int TIER2_MIN_AVOIDED_QUADS = 8;

    /*
     * Stage 1 uses Minecraft's existing block-atlas shader. Atlas subtextures
     * cannot safely repeat across an arbitrarily large merged rectangle, so
     * visible LOD deliberately caps the geometric merge span. This still cuts
     * broad surfaces hard (2x2 -> 75%, 4x4 -> 93.75%) without turning one
     * 16x16 atlas tile into a section-wide stretched billboard.
     */
    private static final int TIER1_MAX_RECTANGLE_SPAN = 2;
    private static final int TIER2_MAX_RECTANGLE_SPAN = 4;

    private static final ThreadLocal<Scratch> SCRATCH =
            ThreadLocal.withInitial(
                    Scratch::new
            );

    private PotatoLodMeshBuilder() {
    }

    static PotatoLodBuildResult build(
            byte[] sourceBytes,
            int vertexCount
    ) {
        long startNanos =
                System.nanoTime();

        if (sourceBytes == null
                || vertexCount <= 0
                || (vertexCount & 3) != 0) {
            return null;
        }

        int requiredBytes =
                vertexCount
                        * BLOCK_VERTEX_STRIDE_BYTES;

        if (requiredBytes <= 0
                || sourceBytes.length < requiredBytes) {
            return null;
        }

        int sourceQuadCount =
                vertexCount
                        / QUAD_VERTEX_COUNT;

        Scratch scratch =
                SCRATCH.get();

        scratch.reset(
                sourceQuadCount
        );

        ByteBuffer source =
                ByteBuffer.wrap(
                        sourceBytes
                )
                        .order(
                                ByteOrder.nativeOrder()
                        );

        for (int quadIndex = 0;
             quadIndex < sourceQuadCount;
             quadIndex++) {

            parseQuad(
                    source,
                    quadIndex,
                    scratch
            );
        }

        int mergeableQuadCount = 0;

        for (int quadIndex = 0;
             quadIndex < sourceQuadCount;
             quadIndex++) {

            if (scratch.candidate[
                    quadIndex
            ]) {
                mergeableQuadCount++;
            }
        }

        int passthroughQuadCount =
                sourceQuadCount
                        - mergeableQuadCount;

        PotatoLodBuildResult.Tier tier1 =
                buildTier(
                        sourceBytes,
                        sourceQuadCount,
                        passthroughQuadCount,
                        scratch,
                        true,
                        TIER1_MAX_OUTPUT_RATIO,
                        TIER1_MIN_AVOIDED_QUADS
                );

        PotatoLodBuildResult.Tier tier2 =
                buildTier(
                        sourceBytes,
                        sourceQuadCount,
                        passthroughQuadCount,
                        scratch,
                        false,
                        TIER2_MAX_OUTPUT_RATIO,
                        TIER2_MIN_AVOIDED_QUADS
                );

        if (tier1 == null
                && tier2 == null) {
            return null;
        }

        return new PotatoLodBuildResult(
                sourceQuadCount,
                mergeableQuadCount,
                passthroughQuadCount,
                tier1,
                tier2,
                System.nanoTime()
                        - startNanos
        );
    }

    private static void parseQuad(
            ByteBuffer source,
            int quadIndex,
            Scratch scratch
    ) {
        int base =
                quadIndex
                        * QUAD_BYTES;

        for (int vertex = 0;
             vertex < QUAD_VERTEX_COUNT;
             vertex++) {

            int vertexBase =
                    base
                            + vertex
                            * BLOCK_VERTEX_STRIDE_BYTES;

            scratch.x[vertex] =
                    source.getFloat(
                            vertexBase
                                    + POSITION_OFFSET
                    );

            scratch.y[vertex] =
                    source.getFloat(
                            vertexBase
                                    + POSITION_OFFSET
                                    + 4
                    );

            scratch.z[vertex] =
                    source.getFloat(
                            vertexBase
                                    + POSITION_OFFSET
                                    + 8
                    );

            scratch.normal[vertex] =
                    packedNormal(
                            source,
                            vertexBase
                                    + NORMAL_OFFSET
                    );
        }

        float minX =
                minimum(
                        scratch.x
                );
        float maxX =
                maximum(
                        scratch.x
                );

        float minY =
                minimum(
                        scratch.y
                );
        float maxY =
                maximum(
                        scratch.y
                );

        float minZ =
                minimum(
                        scratch.z
                );
        float maxZ =
                maximum(
                        scratch.z
                );

        int constantAxis =
                constantAxis(
                        maxX - minX,
                        maxY - minY,
                        maxZ - minZ
                );

        if (constantAxis < 0
                || !allEqual(
                scratch.normal
        )) {
            return;
        }

        int direction =
                directionForNormal(
                        scratch.normal[0],
                        constantAxis
                );

        if (direction < 0) {
            return;
        }

        float planeCoordinate;
        float minA;
        float maxA;
        float minB;
        float maxB;

        if (constantAxis == 0) {
            planeCoordinate = minX;
            minA = minY;
            maxA = maxY;
            minB = minZ;
            maxB = maxZ;
        } else if (constantAxis == 1) {
            planeCoordinate = minY;
            minA = minX;
            maxA = maxX;
            minB = minZ;
            maxB = maxZ;
        } else {
            planeCoordinate = minZ;
            minA = minX;
            maxA = maxX;
            minB = minY;
            maxB = maxY;
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
                || maxCellA != cellA + 1
                || maxCellB != cellB + 1) {
            return;
        }

        int slot =
                cellSlot(
                        direction,
                        plane,
                        cellA,
                        cellB
                );

        if (scratch.occupied[
                slot
        ]) {
            scratch.duplicate[
                    slot
            ] =
                    true;

            int previousQuad =
                    scratch.quadBySlot[
                            slot
                    ];

            if (previousQuad >= 0
                    && previousQuad
                    < scratch.candidate.length) {

                scratch.candidate[
                        previousQuad
                ] =
                        false;
            }

            return;
        }

        scratch.occupied[
                slot
        ] =
                true;

        scratch.quadBySlot[
                slot
        ] =
                quadIndex;

        scratch.materialKey[
                slot
        ] =
                materialKey(
                        source,
                        base
                );

        scratch.visualKey[
                slot
        ] =
                visualKey(
                        source,
                        base
                );

        scratch.candidate[
                quadIndex
        ] =
                true;
    }

    private static PotatoLodBuildResult.Tier buildTier(
            byte[] sourceBytes,
            int sourceQuadCount,
            int passthroughQuadCount,
            Scratch scratch,
            boolean preserveFullVisualPayload,
            double maximumOutputRatio,
            int minimumAvoidedQuads
    ) {
        int maximumRectangleSpan =
                preserveFullVisualPayload
                        ? TIER1_MAX_RECTANGLE_SPAN
                        : TIER2_MAX_RECTANGLE_SPAN;

        int rectangleCount =
                greedyCover(
                        scratch,
                        preserveFullVisualPayload,
                        maximumRectangleSpan,
                        null
                );

        int outputQuadCount =
                passthroughQuadCount
                        + rectangleCount;

        int avoidedQuadCount =
                sourceQuadCount
                        - outputQuadCount;

        if (outputQuadCount <= 0
                || outputQuadCount
                > MAX_UINT16_QUADS
                || avoidedQuadCount
                < minimumAvoidedQuads
                || outputQuadCount
                > Math.floor(
                sourceQuadCount
                        * maximumOutputRatio
        )) {
            return null;
        }

        byte[] vertexBytes =
                new byte[
                        outputQuadCount
                                * QUAD_VERTEX_COUNT
                                * BLOCK_VERTEX_STRIDE_BYTES
                        ];

        byte[] indexBytes =
                new byte[
                        outputQuadCount
                                * 6
                                * Short.BYTES
                        ];

        ByteBuffer vertices =
                ByteBuffer.wrap(
                        vertexBytes
                )
                        .order(
                                ByteOrder.nativeOrder()
                        );

        ByteBuffer indices =
                ByteBuffer.wrap(
                        indexBytes
                )
                        .order(
                                ByteOrder.nativeOrder()
                        );

        int outputQuadIndex =
                0;

        for (int quadIndex = 0;
             quadIndex < sourceQuadCount;
             quadIndex++) {

            if (scratch.candidate[
                    quadIndex
            ]) {
                continue;
            }

            int sourceBase =
                    quadIndex
                            * QUAD_BYTES;

            vertices.put(
                    sourceBytes,
                    sourceBase,
                    QUAD_BYTES
            );

            writeQuadIndices(
                    indices,
                    outputQuadIndex
            );

            outputQuadIndex++;
        }

        final int[] rectangleOutputIndex = {
                outputQuadIndex
        };

        greedyCover(
                scratch,
                preserveFullVisualPayload,
                maximumRectangleSpan,
                (
                        direction,
                        plane,
                        cellA,
                        cellB,
                        width,
                        height,
                        representativeSlot
                ) -> {
                    emitMergedRectangle(
                            sourceBytes,
                            vertices,
                            direction,
                            plane,
                            cellA,
                            cellB,
                            width,
                            height,
                            scratch.quadBySlot[
                                    representativeSlot
                            ]
                    );

                    writeQuadIndices(
                            indices,
                            rectangleOutputIndex[0]
                    );

                    rectangleOutputIndex[0]++;
                }
        );

        if (rectangleOutputIndex[0]
                != outputQuadCount
                || vertices.position()
                != vertexBytes.length
                || indices.position()
                != indexBytes.length) {

            throw new IllegalStateException(
                    "LOD proxy emission count mismatch."
            );
        }

        double reductionPercent =
                avoidedQuadCount
                        * 100.0
                        / sourceQuadCount;

        return new PotatoLodBuildResult.Tier(
                vertexBytes,
                indexBytes,
                outputQuadCount,
                rectangleCount,
                passthroughQuadCount,
                sourceQuadCount,
                reductionPercent
        );
    }

    private static int greedyCover(
            Scratch scratch,
            boolean preserveFullVisualPayload,
            int maximumRectangleSpan,
            RectangleConsumer consumer
    ) {
        Arrays.fill(
                scratch.visited,
                false
        );

        int rectangleCount =
                0;

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

                        if (!available(
                                scratch,
                                startSlot
                        )) {
                            continue;
                        }

                        long startKey =
                                preserveFullVisualPayload
                                        ? scratch.visualKey[
                                        startSlot
                                ]
                                        : scratch.materialKey[
                                        startSlot
                                ];

                        int width =
                                1;

                        while (cellA + width
                                < GRID_SIZE
                                && width
                                < maximumRectangleSpan) {

                            int slot =
                                    cellSlot(
                                            direction,
                                            plane,
                                            cellA + width,
                                            cellB
                                    );

                            if (!available(
                                    scratch,
                                    slot
                            )
                                    || (
                                    preserveFullVisualPayload
                                            ? scratch.visualKey[
                                            slot
                                    ] != startKey
                                            : scratch.materialKey[
                                            slot
                                    ] != startKey
                            )) {
                                break;
                            }

                            width++;
                        }

                        int height =
                                1;

                        while (cellB + height
                                < GRID_SIZE
                                && height
                                < maximumRectangleSpan) {

                            boolean compatible =
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
                                        scratch,
                                        slot
                                )
                                        || (
                                        preserveFullVisualPayload
                                                ? scratch.visualKey[
                                                slot
                                        ] != startKey
                                                : scratch.materialKey[
                                                slot
                                        ] != startKey
                                )) {
                                    compatible =
                                            false;

                                    break;
                                }
                            }

                            if (!compatible) {
                                break;
                            }

                            height++;
                        }

                        for (int y = 0;
                             y < height;
                             y++) {

                            for (int x = 0;
                                 x < width;
                                 x++) {

                                scratch.visited[
                                        cellSlot(
                                                direction,
                                                plane,
                                                cellA + x,
                                                cellB + y
                                        )
                                ] =
                                        true;
                            }
                        }

                        if (consumer != null) {
                            consumer.accept(
                                    direction,
                                    plane,
                                    cellA,
                                    cellB,
                                    width,
                                    height,
                                    startSlot
                            );
                        }

                        rectangleCount++;
                    }
                }
            }
        }

        return rectangleCount;
    }

    private static boolean available(
            Scratch scratch,
            int slot
    ) {
        return scratch.occupied[
                slot
        ]
                && !scratch.duplicate[
                slot
        ]
                && !scratch.visited[
                slot
        ];
    }

    private static void emitMergedRectangle(
            byte[] sourceBytes,
            ByteBuffer output,
            int direction,
            int plane,
            int cellA,
            int cellB,
            int width,
            int height,
            int representativeQuad
    ) {
        if (representativeQuad < 0) {
            throw new IllegalStateException(
                    "Merged rectangle has no representative source quad."
            );
        }

        ByteBuffer source =
                ByteBuffer.wrap(
                        sourceBytes
                )
                        .order(
                                ByteOrder.nativeOrder()
                        );

        int constantAxis =
                direction <= 1
                        ? 0
                        : direction <= 3
                        ? 1
                        : 2;

        int representativeBase =
                representativeQuad
                        * QUAD_BYTES;

        for (int vertex = 0;
             vertex < QUAD_VERTEX_COUNT;
             vertex++) {

            int sourceVertexBase =
                    representativeBase
                            + vertex
                            * BLOCK_VERTEX_STRIDE_BYTES;

            float sourceX =
                    source.getFloat(
                            sourceVertexBase
                    );

            float sourceY =
                    source.getFloat(
                            sourceVertexBase + 4
                    );

            float sourceZ =
                    source.getFloat(
                            sourceVertexBase + 8
                    );

            float sourceA;
            float sourceB;

            if (constantAxis == 0) {
                sourceA = sourceY;
                sourceB = sourceZ;
            } else if (constantAxis == 1) {
                sourceA = sourceX;
                sourceB = sourceZ;
            } else {
                sourceA = sourceX;
                sourceB = sourceY;
            }

            int aBit =
                    endpointBit(
                            sourceA,
                            cellA,
                            cellA + 1.0f
                    );

            int bBit =
                    endpointBit(
                            sourceB,
                            cellB,
                            cellB + 1.0f
                    );

            if (aBit < 0
                    || bBit < 0) {
                throw new IllegalStateException(
                        "LOD rectangle corner mapping failed."
                );
            }

            float a =
                    cellA
                            + (
                            aBit == 0
                                    ? 0.0f
                                    : width
                    );

            float b =
                    cellB
                            + (
                            bBit == 0
                                    ? 0.0f
                                    : height
                    );

            float x;
            float y;
            float z;

            if (constantAxis == 0) {
                x = plane;
                y = a;
                z = b;
            } else if (constantAxis == 1) {
                x = a;
                y = plane;
                z = b;
            } else {
                x = a;
                y = b;
                z = plane;
            }

            output.putFloat(
                    x
            );
            output.putFloat(
                    y
            );
            output.putFloat(
                    z
            );

            output.put(
                    sourceBytes,
                    sourceVertexBase + 12,
                    BLOCK_VERTEX_STRIDE_BYTES - 12
            );
        }
    }

    private static void writeQuadIndices(
            ByteBuffer indices,
            int quadIndex
    ) {
        int firstVertex =
                quadIndex
                        * QUAD_VERTEX_COUNT;

        if (firstVertex + 3
                > 0xFFFF) {
            throw new IllegalStateException(
                    "LOD proxy exceeded UINT16 vertex range."
            );
        }

        indices.putShort(
                (short) (firstVertex)
        );
        indices.putShort(
                (short) (firstVertex + 1)
        );
        indices.putShort(
                (short) (firstVertex + 2)
        );
        indices.putShort(
                (short) (firstVertex + 2)
        );
        indices.putShort(
                (short) (firstVertex + 3)
        );
        indices.putShort(
                (short) (firstVertex)
        );
    }

    private static long materialKey(
            ByteBuffer source,
            int quadBase
    ) {
        long hash =
                0xcbf29ce484222325L;

        for (int vertex = 0;
             vertex < QUAD_VERTEX_COUNT;
             vertex++) {

            int uvBase =
                    quadBase
                            + vertex
                            * BLOCK_VERTEX_STRIDE_BYTES
                            + UV0_OFFSET;

            for (int byteIndex = 0;
                 byteIndex < 8;
                 byteIndex++) {

                hash ^=
                        Byte.toUnsignedLong(
                                source.get(
                                        uvBase
                                                + byteIndex
                                )
                        );

                hash *=
                        0x100000001b3L;
            }
        }

        return hash;
    }

    private static long visualKey(
            ByteBuffer source,
            int quadBase
    ) {
        long hash =
                0xcbf29ce484222325L;

        for (int vertex = 0;
             vertex < QUAD_VERTEX_COUNT;
             vertex++) {

            int payloadBase =
                    quadBase
                            + vertex
                            * BLOCK_VERTEX_STRIDE_BYTES
                            + 12;

            for (int byteIndex = 0;
                 byteIndex
                         < BLOCK_VERTEX_STRIDE_BYTES - 12;
                 byteIndex++) {

                hash ^=
                        Byte.toUnsignedLong(
                                source.get(
                                        payloadBase
                                                + byteIndex
                                )
                        );

                hash *=
                        0x100000001b3L;
            }
        }

        return hash;
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
            ByteBuffer source,
            int offset
    ) {
        return Byte.toUnsignedInt(
                source.get(
                        offset
                )
        )
                | (
                Byte.toUnsignedInt(
                        source.get(
                                offset + 1
                        )
                )
                        << 8
        )
                | (
                Byte.toUnsignedInt(
                        source.get(
                                offset + 2
                        )
                )
                        << 16
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

        return Math.abs(
                value - rounded
        ) <= POSITION_EPSILON
                ? rounded
                : Integer.MIN_VALUE;
    }

    private static int endpointBit(
            float value,
            float minimum,
            float maximum
    ) {
        if (Math.abs(
                value - minimum
        ) <= POSITION_EPSILON) {
            return 0;
        }

        if (Math.abs(
                value - maximum
        ) <= POSITION_EPSILON) {
            return 1;
        }

        return -1;
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

    @FunctionalInterface
    private interface RectangleConsumer {
        void accept(
                int direction,
                int plane,
                int cellA,
                int cellB,
                int width,
                int height,
                int representativeSlot
        );
    }

    private static final class Scratch {
        private final boolean[] occupied =
                new boolean[
                        CELL_SLOT_COUNT
                        ];

        private final boolean[] duplicate =
                new boolean[
                        CELL_SLOT_COUNT
                        ];

        private final boolean[] visited =
                new boolean[
                        CELL_SLOT_COUNT
                        ];

        private final int[] quadBySlot =
                new int[
                        CELL_SLOT_COUNT
                        ];

        private final long[] materialKey =
                new long[
                        CELL_SLOT_COUNT
                        ];

        private final long[] visualKey =
                new long[
                        CELL_SLOT_COUNT
                        ];

        private boolean[] candidate =
                new boolean[0];

        private final float[] x =
                new float[4];

        private final float[] y =
                new float[4];

        private final float[] z =
                new float[4];

        private final int[] normal =
                new int[4];

        void reset(
                int sourceQuadCount
        ) {
            Arrays.fill(
                    occupied,
                    false
            );
            Arrays.fill(
                    duplicate,
                    false
            );
            Arrays.fill(
                    visited,
                    false
            );
            Arrays.fill(
                    quadBySlot,
                    -1
            );
            Arrays.fill(
                    materialKey,
                    0L
            );
            Arrays.fill(
                    visualKey,
                    0L
            );

            if (candidate.length
                    < sourceQuadCount) {
                candidate =
                        new boolean[
                                sourceQuadCount
                                ];
            } else {
                Arrays.fill(
                        candidate,
                        0,
                        sourceQuadCount,
                        false
                );
            }
        }
    }
}
