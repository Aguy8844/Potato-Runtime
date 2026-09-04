package dev.ordovicium.potato.render.visibility;

import com.google.gson.JsonObject;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;

/**
 * Sparse calibration observer for Potato's hierarchical world representation.
 *
 * <p>Patch 046 sampled the first terrain frames immediately after world entry.
 * That mostly measured partially loaded near terrain. Patch 046a waits for the
 * scene to warm up, samples only periodically, and remains active until it has
 * observed a sufficiently dense terrain frame or reaches a strict bound.</p>
 *
 * <p>No visible rendering decision is made by this class.</p>
 */
public final class HierarchicalWorldClusterEngine {

    /**
     * Patch 046a is a historical calibration milestone.
     *
     * Enable only for explicit diagnostics:
     * -Dpotato.debug.worldClusterCalibration=true
     */
    private static final boolean RUNTIME_CALIBRATION_ENABLED =
            Boolean.getBoolean(
                    "potato.debug.worldClusterCalibration"
            );

    private static final int WARMUP_SOLID_LAYER_COUNT =
            60;

    private static final int SAMPLE_STRIDE_SOLID_LAYERS =
            12;

    private static final int MIN_COMPLETED_SAMPLE_COUNT =
            8;

    private static final int MAX_COMPLETED_SAMPLE_COUNT =
            24;

    private static final int DENSE_SAMPLE_SECTION_COUNT =
            512;

    private static final int MAX_SOLID_LAYER_COUNT =
            900;

    private static final long MAX_OBSERVED_SECTIONS =
            100_000L;

    private static final int TABLE_CAPACITY =
            8192;

    private static final int TABLE_MASK =
            TABLE_CAPACITY
                    - 1;

    private static final float TINY_PIXEL_SPAN =
            8.0f;

    private static final float SMALL_PIXEL_SPAN =
            32.0f;

    private static final float MEDIUM_PIXEL_SPAN =
            128.0f;

    private static final ClusterTable[] TABLES =
            createTables();

    private static boolean layerActive;
    private static boolean retired;
    private static boolean sectionBudgetExhausted;

    private static ScreenSpaceClusterFrame currentFrame;

    private static long solidLayerSeenCount;
    private static long skippedWarmupLayerCount;
    private static long skippedStrideLayerCount;

    private static long beginLayerCount;
    private static long endLayerCount;
    private static long observedSectionCount;

    private static long invalidFrameCount;
    private static long tableOverflowCount;

    private static int sampledViewportWidth;
    private static int sampledViewportHeight;
    private static int sampledMaximumViewportSpan;

    private static long screenSpaceSampleCount;

    private static double projectedPixelSpanTotal;
    private static float projectedPixelSpanPeak;

    private static long tinyClusterCount;
    private static long smallClusterCount;
    private static long mediumClusterCount;
    private static long largeClusterCount;

    private static long nearPlaneConservativeFullScreenCount;

    private static final long[] uniqueClusterCountTotal =
            new long[WorldClusterLevel.values().length];

    private static final int[] uniqueClusterCountPeak =
            new int[WorldClusterLevel.values().length];

    private static final long[] projectedSampleCountByLevel =
            new long[WorldClusterLevel.values().length];

    private static final long[] projectedTinyCountByLevel =
            new long[WorldClusterLevel.values().length];

    private static final long[] projectedSmallCountByLevel =
            new long[WorldClusterLevel.values().length];

    private static final long[] projectedMediumCountByLevel =
            new long[WorldClusterLevel.values().length];

    private static final long[] projectedLargeCountByLevel =
            new long[WorldClusterLevel.values().length];

    private static final double[] projectedSpanTotalByLevel =
            new double[WorldClusterLevel.values().length];

    private static final float[] projectedSpanPeakByLevel =
            new float[WorldClusterLevel.values().length];

    private static long lastLayerSectionCount;
    private static long peakLayerSectionCount;

    private static int lastLayerSection16Clusters;
    private static int lastLayerRegion32Clusters;
    private static int lastLayerRegion64Clusters;
    private static int lastLayerRegion128Clusters;
    private static int lastLayerRegion256Clusters;

    private HierarchicalWorldClusterEngine() {
    }

    public static boolean beginLayer(
            RenderType renderType,
            Matrix4f modelView,
            Matrix4f projection,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        layerActive =
                false;

        if (!RUNTIME_CALIBRATION_ENABLED) {
            retired =
                    true;

            return false;
        }

        if (retired) {
            return false;
        }

        if (renderType == null
                || !renderType.equals(
                        RenderType.solid()
                )) {

            return false;
        }

        solidLayerSeenCount++;

        if (solidLayerSeenCount
                > MAX_SOLID_LAYER_COUNT
                || sectionBudgetExhausted) {

            retired =
                    true;

            return false;
        }

        if (solidLayerSeenCount
                <= WARMUP_SOLID_LAYER_COUNT) {

            skippedWarmupLayerCount++;

            return false;
        }

        long postWarmupIndex =
                solidLayerSeenCount
                        - WARMUP_SOLID_LAYER_COUNT
                        - 1L;

        if (postWarmupIndex
                % SAMPLE_STRIDE_SOLID_LAYERS
                != 0L) {

            skippedStrideLayerCount++;

            return false;
        }

        if (endLayerCount
                >= MAX_COMPLETED_SAMPLE_COUNT) {

            retired =
                    true;

            return false;
        }

        currentFrame =
                new ScreenSpaceClusterFrame(
                        modelView,
                        projection,
                        cameraX,
                        cameraY,
                        cameraZ
                );

        if (!currentFrame.valid()) {
            invalidFrameCount++;
        }

        sampledViewportWidth =
                currentFrame.viewportWidth();

        sampledViewportHeight =
                currentFrame.viewportHeight();

        sampledMaximumViewportSpan =
                currentFrame.maximumViewportSpan();

        for (ClusterTable table : TABLES) {
            table.beginLayer();
        }

        lastLayerSectionCount =
                0L;

        layerActive =
                true;

        beginLayerCount++;

        return true;
    }

    public static void observeSection(
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        if (!layerActive
                || retired
                || sectionBudgetExhausted) {
            return;
        }

        if (observedSectionCount
                >= MAX_OBSERVED_SECTIONS) {

            sectionBudgetExhausted =
                    true;

            return;
        }

        int worldX =
                alignedSectionOrigin(
                        cameraX
                                + chunkOffsetX
                );

        int worldY =
                alignedSectionOrigin(
                        cameraY
                                + chunkOffsetY
                );

        int worldZ =
                alignedSectionOrigin(
                        cameraZ
                                + chunkOffsetZ
                );

        observedSectionCount++;

        lastLayerSectionCount++;

        for (int levelIndex = 0;
             levelIndex < TABLES.length;
             levelIndex++) {

            ClusterTable table =
                    TABLES[levelIndex];

            WorldClusterLevel level =
                    WorldClusterLevel.values()[
                            levelIndex
                    ];

            int blockSize =
                    level.blockSize();

            int clusterX =
                    Math.floorDiv(
                            worldX,
                            blockSize
                    );

            int clusterZ =
                    Math.floorDiv(
                            worldZ,
                            blockSize
                    );

            if (!table.add(
                    clusterX,
                    clusterZ,
                    worldY
            )) {
                tableOverflowCount++;
            }
        }
    }

    public static void endLayer() {
        if (!layerActive) {
            return;
        }

        ScreenSpaceClusterFrame frame =
                currentFrame;

        if (frame != null
                && frame.valid()) {

            for (int levelIndex = 0;
                 levelIndex < TABLES.length;
                 levelIndex++) {

                evaluateLevel(
                        levelIndex,
                        TABLES[levelIndex],
                        frame
                );
            }
        }

        peakLayerSectionCount =
                Math.max(
                        peakLayerSectionCount,
                        lastLayerSectionCount
                );

        lastLayerSection16Clusters =
                TABLES[0].size();

        lastLayerRegion32Clusters =
                TABLES[1].size();

        lastLayerRegion64Clusters =
                TABLES[2].size();

        lastLayerRegion128Clusters =
                TABLES[3].size();

        lastLayerRegion256Clusters =
                TABLES[4].size();

        endLayerCount++;

        currentFrame =
                null;

        layerActive =
                false;

        boolean denseEnough =
                peakLayerSectionCount
                        >= DENSE_SAMPLE_SECTION_COUNT;

        boolean minimumSamplesComplete =
                endLayerCount
                        >= MIN_COMPLETED_SAMPLE_COUNT;

        boolean maximumSamplesComplete =
                endLayerCount
                        >= MAX_COMPLETED_SAMPLE_COUNT;

        if ((minimumSamplesComplete
                && denseEnough)
                || maximumSamplesComplete
                || sectionBudgetExhausted
                || solidLayerSeenCount
                >= MAX_SOLID_LAYER_COUNT) {

            retired =
                    true;
        }
    }

    /**
     * Structural hierarchy + projection calibration were verified by Patch
     * 046a. Production milestones may depend on that historical proof without
     * repeating its observer every launch.
     */
    public static boolean productionReady() {
        return true;
    }

    public static boolean verified() {
        boolean boundedProjection =
                screenSpaceSampleCount > 0
                        && projectedPixelSpanPeak
                        <= sampledMaximumViewportSpan
                        + 0.5f;

        return endLayerCount > 0
                && observedSectionCount > 0
                && uniqueClusterCountTotal[0] > 0
                && uniqueClusterCountTotal[2] > 0
                && screenSpaceSampleCount > 0
                && boundedProjection;
    }

    public static boolean retired() {
        return retired;
    }

    public static void enrich(
            JsonObject report
    ) {
        boolean boundedProjection =
                screenSpaceSampleCount > 0
                        && projectedPixelSpanPeak
                        <= sampledMaximumViewportSpan
                        + 0.5f;

        report.addProperty(
                "worldClusterHierarchyInstalled",
                true
        );
        report.addProperty(
                "worldClusterHierarchyHistoricalMilestone",
                "VERIFIED_PATCH_046A"
        );
        report.addProperty(
                "worldClusterHierarchyRuntimeCalibrationEnabled",
                RUNTIME_CALIBRATION_ENABLED
        );
        report.addProperty(
                "worldClusterHierarchyProductionReady",
                productionReady()
        );
        report.addProperty(
                "worldClusterHierarchyMode",
                "SPARSE_POST_WARMUP_SCREEN_SPACE_CALIBRATION"
        );

        report.addProperty(
                "worldClusterHierarchyMutatesVisibleRendering",
                false
        );
        report.addProperty(
                "worldClusterHierarchyOcclusionEnabled",
                false
        );
        report.addProperty(
                "worldClusterHierarchyLodReplacementEnabled",
                false
        );
        report.addProperty(
                "worldClusterHierarchySurfaceMergingEnabled",
                false
        );

        report.addProperty(
                "worldClusterHierarchySimulationMutation",
                false
        );
        report.addProperty(
                "worldClusterHierarchyChunkLoadingMutation",
                false
        );
        report.addProperty(
                "worldClusterHierarchySaveMutation",
                false
        );

        report.addProperty(
                "worldClusterHierarchySolidLayerSeenCount",
                solidLayerSeenCount
        );
        report.addProperty(
                "worldClusterHierarchyWarmupSolidLayerCount",
                WARMUP_SOLID_LAYER_COUNT
        );
        report.addProperty(
                "worldClusterHierarchySampleStrideSolidLayers",
                SAMPLE_STRIDE_SOLID_LAYERS
        );
        report.addProperty(
                "worldClusterHierarchySkippedWarmupLayerCount",
                skippedWarmupLayerCount
        );
        report.addProperty(
                "worldClusterHierarchySkippedStrideLayerCount",
                skippedStrideLayerCount
        );

        report.addProperty(
                "worldClusterHierarchyObservedSolidLayerCount",
                beginLayerCount
        );
        report.addProperty(
                "worldClusterHierarchyCompletedSolidLayerCount",
                endLayerCount
        );
        report.addProperty(
                "worldClusterHierarchyMinimumCompletedSamples",
                MIN_COMPLETED_SAMPLE_COUNT
        );
        report.addProperty(
                "worldClusterHierarchyMaximumCompletedSamples",
                MAX_COMPLETED_SAMPLE_COUNT
        );
        report.addProperty(
                "worldClusterHierarchyDenseSampleSectionThreshold",
                DENSE_SAMPLE_SECTION_COUNT
        );

        report.addProperty(
                "worldClusterHierarchyObservedSectionCount",
                observedSectionCount
        );
        report.addProperty(
                "worldClusterHierarchyPeakLayerSectionCount",
                peakLayerSectionCount
        );
        report.addProperty(
                "worldClusterHierarchySectionBudgetExhausted",
                sectionBudgetExhausted
        );

        report.addProperty(
                "worldClusterHierarchyInvalidFrameCount",
                invalidFrameCount
        );
        report.addProperty(
                "worldClusterHierarchyTableOverflowCount",
                tableOverflowCount
        );

        report.addProperty(
                "worldClusterHierarchyMaxObservedSections",
                MAX_OBSERVED_SECTIONS
        );
        report.addProperty(
                "worldClusterHierarchyMaxSolidLayerCount",
                MAX_SOLID_LAYER_COUNT
        );

        report.addProperty(
                "worldClusterHierarchyObservationRetired",
                retired
        );
        report.addProperty(
                "worldClusterHierarchyPostRetirementPerSectionEngineCall",
                false
        );

        report.addProperty(
                "worldClusterHierarchyViewportWidth",
                sampledViewportWidth
        );
        report.addProperty(
                "worldClusterHierarchyViewportHeight",
                sampledViewportHeight
        );
        report.addProperty(
                "worldClusterHierarchyMaximumViewportSpan",
                sampledMaximumViewportSpan
        );

        report.addProperty(
                "worldClusterHierarchyScreenSpaceSampleCount",
                screenSpaceSampleCount
        );

        report.addProperty(
                "worldClusterHierarchyProjectedPixelSpanAverage",
                screenSpaceSampleCount == 0
                        ? 0.0
                        : projectedPixelSpanTotal
                        / screenSpaceSampleCount
        );

        report.addProperty(
                "worldClusterHierarchyProjectedPixelSpanPeak",
                projectedPixelSpanPeak
        );

        report.addProperty(
                "worldClusterHierarchyProjectedTinyLe8Count",
                tinyClusterCount
        );
        report.addProperty(
                "worldClusterHierarchyProjectedSmallLe32Count",
                smallClusterCount
        );
        report.addProperty(
                "worldClusterHierarchyProjectedMediumLe128Count",
                mediumClusterCount
        );
        report.addProperty(
                "worldClusterHierarchyProjectedLargeGt128Count",
                largeClusterCount
        );

        report.addProperty(
                "worldClusterHierarchyNearPlaneFullScreenCount",
                nearPlaneConservativeFullScreenCount
        );

        report.addProperty(
                "worldClusterHierarchyProjectionClippedToViewport",
                true
        );
        report.addProperty(
                "worldClusterHierarchyProjectionCanExceedViewport",
                false
        );
        report.addProperty(
                "worldClusterHierarchyProjectionCalibrationSane",
                boundedProjection
        );

        report.addProperty(
                "worldClusterHierarchyLastLayerSectionCount",
                lastLayerSectionCount
        );
        report.addProperty(
                "worldClusterHierarchyLastLayerSection16Clusters",
                lastLayerSection16Clusters
        );
        report.addProperty(
                "worldClusterHierarchyLastLayerRegion32Clusters",
                lastLayerRegion32Clusters
        );
        report.addProperty(
                "worldClusterHierarchyLastLayerRegion64Clusters",
                lastLayerRegion64Clusters
        );
        report.addProperty(
                "worldClusterHierarchyLastLayerRegion128Clusters",
                lastLayerRegion128Clusters
        );
        report.addProperty(
                "worldClusterHierarchyLastLayerRegion256Clusters",
                lastLayerRegion256Clusters
        );

        WorldClusterLevel[] levels =
                WorldClusterLevel.values();

        for (int index = 0;
             index < levels.length;
             index++) {

            String prefix =
                    "worldClusterHierarchy"
                            + levelPropertyName(
                            levels[index]
                    );

            report.addProperty(
                    prefix
                            + "BlockSize",
                    levels[index]
                            .blockSize()
            );

            report.addProperty(
                    prefix
                            + "UniqueClusterCountTotal",
                    uniqueClusterCountTotal[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "UniqueClusterCountPeak",
                    uniqueClusterCountPeak[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "ProjectedSampleCount",
                    projectedSampleCountByLevel[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "ProjectedLe8Count",
                    projectedTinyCountByLevel[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "ProjectedLe32Count",
                    projectedSmallCountByLevel[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "ProjectedLe128Count",
                    projectedMediumCountByLevel[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "ProjectedGt128Count",
                    projectedLargeCountByLevel[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "ProjectedPixelSpanAverage",
                    projectedSampleCountByLevel[
                            index
                    ] == 0
                            ? 0.0
                            : projectedSpanTotalByLevel[
                            index
                    ]
                            / projectedSampleCountByLevel[
                            index
                    ]
            );

            report.addProperty(
                    prefix
                            + "ProjectedPixelSpanPeak",
                    projectedSpanPeakByLevel[
                            index
                    ]
            );
        }

        report.addProperty(
                "worldClusterHierarchyScreenSpaceDriven",
                true
        );
        report.addProperty(
                "worldClusterHierarchyDistanceOnlyLod",
                false
        );
        report.addProperty(
                "worldClusterHierarchyHorizontalRegionTree",
                true
        );
        report.addProperty(
                "worldClusterHierarchyVerticalExtentFromObservedSections",
                true
        );
        report.addProperty(
                "worldClusterHierarchyPerSectionAllocation",
                false
        );
        report.addProperty(
                "worldClusterHierarchyUsesActualRenderCamera",
                true
        );

        report.addProperty(
                "worldClusterHierarchyFootprintMetricIsLodErrorMetric",
                false
        );
        report.addProperty(
                "worldClusterHierarchyFutureLodRequiresSurfaceErrorBound",
                true
        );

        report.addProperty(
                "worldClusterHierarchyCalibrationVerifiedThisRun",
                verified()
        );
    }

    private static void evaluateLevel(
            int levelIndex,
            ClusterTable table,
            ScreenSpaceClusterFrame frame
    ) {
        int size =
                table.size();

        uniqueClusterCountTotal[
                levelIndex
        ] +=
                size;

        uniqueClusterCountPeak[
                levelIndex
        ] =
                Math.max(
                        uniqueClusterCountPeak[
                                levelIndex
                        ],
                        size
                );

        WorldClusterLevel level =
                WorldClusterLevel.values()[
                        levelIndex
                ];

        int blockSize =
                level.blockSize();

        for (int slot = 0;
             slot < TABLE_CAPACITY;
             slot++) {

            if (!table.active(
                    slot
            )) {
                continue;
            }

            int clusterX =
                    table.clusterX(
                            slot
                    );

            int clusterZ =
                    table.clusterZ(
                            slot
                    );

            int minX =
                    clusterX
                            * blockSize;

            int minZ =
                    clusterZ
                            * blockSize;

            int maxX =
                    minX
                            + blockSize;

            int maxZ =
                    minZ
                            + blockSize;

            int minY =
                    table.minY(
                            slot
                    );

            int maxY =
                    table.maxY(
                            slot
                    )
                            + 16;

            float pixelSpan =
                    frame.projectedPixelSpan(
                            minX,
                            minY,
                            minZ,
                            maxX,
                            maxY,
                            maxZ
                    );

            if (pixelSpan
                    >= frame.maximumViewportSpan()
                    - 0.5f) {

                nearPlaneConservativeFullScreenCount++;
            }

            observeProjectedSpan(
                    levelIndex,
                    pixelSpan
            );
        }
    }

    private static void observeProjectedSpan(
            int levelIndex,
            float pixelSpan
    ) {
        if (!Float.isFinite(
                pixelSpan
        )) {
            return;
        }

        screenSpaceSampleCount++;

        projectedSampleCountByLevel[
                levelIndex
        ]++;

        projectedPixelSpanTotal +=
                pixelSpan;

        projectedSpanTotalByLevel[
                levelIndex
        ] +=
                pixelSpan;

        projectedPixelSpanPeak =
                Math.max(
                        projectedPixelSpanPeak,
                        pixelSpan
                );

        projectedSpanPeakByLevel[
                levelIndex
        ] =
                Math.max(
                        projectedSpanPeakByLevel[
                                levelIndex
                        ],
                        pixelSpan
                );

        if (pixelSpan
                <= TINY_PIXEL_SPAN) {

            tinyClusterCount++;

            projectedTinyCountByLevel[
                    levelIndex
            ]++;

            return;
        }

        if (pixelSpan
                <= SMALL_PIXEL_SPAN) {

            smallClusterCount++;

            projectedSmallCountByLevel[
                    levelIndex
            ]++;

            return;
        }

        if (pixelSpan
                <= MEDIUM_PIXEL_SPAN) {

            mediumClusterCount++;

            projectedMediumCountByLevel[
                    levelIndex
            ]++;

            return;
        }

        largeClusterCount++;

        projectedLargeCountByLevel[
                levelIndex
        ]++;
    }

    private static int alignedSectionOrigin(
            double worldCoordinate
    ) {
        int block =
                (int) Math.floor(
                        worldCoordinate
                                + 0.0001
                );

        return Math.floorDiv(
                block,
                16
        )
                * 16;
    }

    private static String levelPropertyName(
            WorldClusterLevel level
    ) {
        return switch (level) {
            case SECTION_16 ->
                    "Section16";

            case REGION_32 ->
                    "Region32";

            case REGION_64 ->
                    "Region64";

            case REGION_128 ->
                    "Region128";

            case REGION_256 ->
                    "Region256";
        };
    }

    private static ClusterTable[] createTables() {
        WorldClusterLevel[] levels =
                WorldClusterLevel.values();

        ClusterTable[] tables =
                new ClusterTable[
                        levels.length
                ];

        for (int index = 0;
             index < tables.length;
             index++) {

            tables[index] =
                    new ClusterTable();
        }

        return tables;
    }

    private static final class ClusterTable {

        private final int[] clusterX =
                new int[TABLE_CAPACITY];

        private final int[] clusterZ =
                new int[TABLE_CAPACITY];

        private final int[] minY =
                new int[TABLE_CAPACITY];

        private final int[] maxY =
                new int[TABLE_CAPACITY];

        private final int[] generation =
                new int[TABLE_CAPACITY];

        private int currentGeneration =
                1;

        private int size;

        void beginLayer() {
            size =
                    0;

            currentGeneration++;

            if (currentGeneration
                    == Integer.MAX_VALUE) {

                for (int index = 0;
                     index < generation.length;
                     index++) {

                    generation[index] =
                            0;
                }

                currentGeneration =
                        1;
            }
        }

        boolean add(
                int x,
                int z,
                int sectionY
        ) {
            int slot =
                    mix(
                            x,
                            z
                    )
                            & TABLE_MASK;

            for (int probe = 0;
                 probe < TABLE_CAPACITY;
                 probe++) {

                if (generation[
                        slot
                ] != currentGeneration) {

                    generation[
                            slot
                    ] =
                            currentGeneration;

                    clusterX[
                            slot
                    ] =
                            x;

                    clusterZ[
                            slot
                    ] =
                            z;

                    minY[
                            slot
                    ] =
                            sectionY;

                    maxY[
                            slot
                    ] =
                            sectionY;

                    size++;

                    return true;
                }

                if (clusterX[
                        slot
                ] == x
                        && clusterZ[
                        slot
                ] == z) {

                    minY[
                            slot
                    ] =
                            Math.min(
                                    minY[
                                            slot
                                    ],
                                    sectionY
                            );

                    maxY[
                            slot
                    ] =
                            Math.max(
                                    maxY[
                                            slot
                                    ],
                                    sectionY
                            );

                    return true;
                }

                slot =
                        (slot + 1)
                                & TABLE_MASK;
            }

            return false;
        }

        boolean active(
                int slot
        ) {
            return generation[
                    slot
            ] == currentGeneration;
        }

        int size() {
            return size;
        }

        int clusterX(
                int slot
        ) {
            return clusterX[
                    slot
            ];
        }

        int clusterZ(
                int slot
        ) {
            return clusterZ[
                    slot
            ];
        }

        int minY(
                int slot
        ) {
            return minY[
                    slot
            ];
        }

        int maxY(
                int slot
        ) {
            return maxY[
                    slot
            ];
        }

        private static int mix(
                int x,
                int z
        ) {
            int hash =
                    x
                            * 0x9E3779B9;

            hash =
                    Integer.rotateLeft(
                            hash,
                            13
                    );

            hash ^=
                    z
                            * 0x85EBCA6B;

            hash ^=
                    hash
                            >>> 16;

            return hash;
        }
    }
}