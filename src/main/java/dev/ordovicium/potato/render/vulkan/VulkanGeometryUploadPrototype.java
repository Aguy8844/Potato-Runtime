package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;
import dev.ordovicium.potato.render.backend.draw.DrawShaderContext;
import dev.ordovicium.potato.render.backend.draw.DrawSubmissionSink;
import dev.ordovicium.potato.render.backend.draw.SectionLayerDrawContext;
import dev.ordovicium.potato.render.lod.PotatoLodRuntime;
import dev.ordovicium.potato.render.visibility.PotatoTemporalOcclusionRuntime;
import dev.ordovicium.potato.settings.PotatoAdaptiveViewController;
import dev.ordovicium.potato.settings.PotatoRuntimeSettings;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.util.ArrayDeque;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Bounded real Minecraft MeshData -> Vulkan VkBuffer upload prototype.
 *
 * <p>The production Vulkan path keeps a bounded adaptive mirror of static BLOCK
 * VertexBuffer lifecycles. Patch 069 sizes that working set for real 24+ chunk
 * visibility instead of the earlier fixed 1024-resource validation ceiling.</p>
 */
final class VulkanGeometryUploadPrototype
        implements DrawSubmissionSink, AutoCloseable {

    /*
     * BLOCK section rendering is STATIC in the verified LevelRenderer path.
     * DYNAMIC immediate buffers are pure overhead for the next milestone.
     */
    private static final int MAX_DYNAMIC_ADMISSIONS =
            0;

    private static final int DEFAULT_STATIC_ADMISSIONS =
            defaultStaticAdmissions();

    private static final int MAX_STATIC_ADMISSIONS =
            Math.max(
                    64,
                    Math.min(
                            16384,
                            Integer.getInteger(
                                    "potato.vulkan.geometry.maxStaticAdmissions",
                                    DEFAULT_STATIC_ADMISSIONS
                            )
                    )
            );

    private static final long MAX_SINGLE_STREAM_BYTES =
            8L * 1024L * 1024L;

    private static final long DYNAMIC_UPLOAD_MIN_INTERVAL_NANOS =
            100_000_000L;

    private static final int VISIBLE_RESIDENCY_WARM_SWEEPS =
            Math.max(
                    30,
                    Math.min(
                            600,
                            Integer.getInteger(
                                    "potato.vulkan.visibleResidencyWarmSweeps",
                                    PotatoRuntimeSettings
                                            .visibleResidencyWarmSweeps()
                            )
                    )
            );

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final JsonObject report;
    private final VulkanGeometryBufferPool bufferPool;
    private final VulkanSectionArenaPlanner arenaPlanner;
    private final VulkanDeviceLocalRegionArena regionArena;

    /*
     * Patch 076 keeps the native per-section mirror bounded, but changes
     * admission from "first loaded buffers win forever" to a visible-first
     * residency working set. A tiny record is allocated once per resident
     * resource; touches only update primitive epochs.
     */
    private final IdentityHashMap<
            DrawBufferBackendState,
            ResidencyRecord> residencyRecords =
            new IdentityHashMap<>();

    private final ArrayDeque<DrawBufferBackendState> residencyOrder =
            new ArrayDeque<>();

    private long visibleResidencyEpoch;
    private long visibleResidencyTouchCount;
    private long visibleResidencyPromotionAttemptCount;
    private long visibleResidencyPromotionSuccessCount;
    private long visibleResidencyPromotionFailureCount;
    private long visibleResidencyColdEvictionCount;
    private long visibleResidencyPinSafeCloseCount;
    private long visibleResidencyNoColdVictimCount;

    private final IdentityHashMap<
            DrawBufferBackendState,
            VulkanGeometryBufferResource>
            resources =
            new IdentityHashMap<>();

    private long observedUploadCount;
    private long admittedUploadCount;
    private long throttledUploadCount;
    private long skippedUnadmittedUploadCount;
    private long skippedOversizeUploadCount;

    private long dynamicAdmissionCount;
    private long staticAdmissionCount;

    private int activeDynamicResourceCount;
    private int activeStaticResourceCount;

    private long vertexBytesUploaded;
    private long indexBytesUploaded;

    private long vertexAllocationCreateOrGrowCount;
    private long indexAllocationCreateOrGrowCount;

    private long hostCoherentVertexUploadCount;
    private long nonCoherentVertexUploadCount;
    private long hostCoherentIndexUploadCount;
    private long nonCoherentIndexUploadCount;

    private long drawReadyCount;
    private long drawStaleCount;
    private long drawUnmirroredCount;

    private long sectionLayerObservedCount;
    private long sectionLayerReadyResourceCount;
    private long sectionLayerStaticReadyResourceCount;
    private long sectionLayerQuadReadyResourceCount;
    private long sectionLayerUnmirroredResourceCount;
    private long sectionLayerStaleResourceCount;

    private String lastSectionLayerCandidateRenderType =
            "";
    private String lastSectionLayerCandidateFormat =
            "";
    private String lastSectionLayerCandidateMode =
            "";
    private String lastSectionLayerCandidateIndexType =
            "";

    private int lastSectionLayerCandidateVertexStrideBytes;
    private int lastSectionLayerCandidateVertexCount;
    private int lastSectionLayerCandidateIndexCount;
    private long lastSectionLayerCandidateUploadGeneration;

    private long resourceReleasedByMinecraftCount;
    private long resourceReleasedAtShutdownCount;

    private long staticAdmissionCapacityDeniedCount;

    private int peakActiveResourceCount;

    private long failureCount;
    private boolean disabledAfterFailure;
    private String lastFailure =
            "";

    private boolean closed;

    VulkanGeometryUploadPrototype(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            JsonObject report
    ) {
        this.device = device;
        this.physicalDevice = physicalDevice;
        this.report = report;

        this.bufferPool =
                new VulkanGeometryBufferPool(
                        device,
                        physicalDevice
                );

        this.arenaPlanner =
                new VulkanSectionArenaPlanner();

        this.regionArena =
                new VulkanDeviceLocalRegionArena(
                        device,
                        physicalDevice,
                        report
                );

        VulkanRegionArenaIngress.register(
                regionArena
        );

        VulkanVisiblePublicationGate
                .resetForRuntime();
        VulkanRegionArenaSurvey
                .resetForRuntime();

        VulkanVisibleGeometryResidency
                .register(this);

        /*
         * Branch convergence after the settings work accidentally dropped the
         * lifecycle binding that the earlier headless gameplay backend owned.
         * Without this, the LOD code exists but remains closed forever
         * (workerThreads=0, builds=0). Bind both OpenGL-side optimization
         * runtimes to the active gameplay report explicitly.
         */
        PotatoLodRuntime.bindReport(
                report
        );

        PotatoTemporalOcclusionRuntime.bindReport(
                report
        );
    }

    synchronized boolean canAdmitNewStaticResource() {
        boolean healthy =
                !closed
                        && !disabledAfterFailure;

        if (healthy
                && activeStaticResourceCount
                >= MAX_STATIC_ADMISSIONS
                && visibleResidencyEpoch > 2L) {
            evictOneColdResidency(
                    visibleResidencyEpoch
            );
        }

        boolean admitted =
                healthy
                        && activeStaticResourceCount
                        < MAX_STATIC_ADMISSIONS;

        if (healthy
                && !admitted) {
            staticAdmissionCapacityDeniedCount++;
        }

        return admitted;
    }

    public boolean wantsUpload(
            DrawBufferBackendState state
    ) {
        if (closed
                || disabledAfterFailure
                || state == null) {
            return false;
        }

        if (resources.containsKey(
                state
        )) {
            return true;
        }

        return canAdmit(
                state
        );
    }

    public boolean wantsSectionLayerDraw(
            DrawBufferBackendState state
    ) {
        return !closed
                && !disabledAfterFailure
                && state != null
                && resources.containsKey(
                        state
                );
    }

    public boolean wantsClose(
            DrawBufferBackendState state
    ) {
        return state != null
                && resources.containsKey(
                        state
                );
    }

    VulkanGeometryBufferResource readyResource(
            DrawBufferBackendState state
    ) {
        if (closed
                || disabledAfterFailure
                || state == null) {
            return null;
        }

        VulkanGeometryBufferResource resource =
                resources.get(
                        state
                );

        if (resource == null
                || !resource.readyFor(
                state
        )) {
            return null;
        }

        return resource;
    }

    @Override
    public void onBufferCreated(
            DrawBufferBackendState state
    ) {
        // Admission is deliberately lazy at the first real MeshData upload.
    }

    @Override
    public void onUpload(
            DrawBufferBackendState state,
            DrawGeometryView geometry
    ) {
        observedUploadCount++;

        VulkanVisiblePublicationGate
                .onGeometryUploadDispatch();

        if (closed
                || disabledAfterFailure) {
            return;
        }

        int vertexBytes =
                geometry.vertexBytes()
                        .remaining();

        int indexBytes =
                geometry.indexBytes() == null
                        ? 0
                        : geometry.indexBytes()
                                .remaining();

        if (vertexBytes <= 0
                || vertexBytes > MAX_SINGLE_STREAM_BYTES
                || indexBytes > MAX_SINGLE_STREAM_BYTES) {
            skippedOversizeUploadCount++;
            return;
        }

        /*
         * Shadow the complete real upload stream, including resources that the
         * legacy per-section Vulkan mirror later rejects at its admission
         * ceiling. The planner allocates no native memory and never changes
         * visible rendering.
         */
        arenaPlanner.onUpload(
                state,
                vertexBytes
        );

        VulkanRegionArenaSurvey.onUpload(
                state,
                vertexBytes
        );

        VulkanGeometryBufferResource resource =
                resources.get(
                        state
                );

        if (resource == null) {
            if (!canAdmit(
                    state
            )) {
                skippedUnadmittedUploadCount++;
                return;
            }

            resource =
                    new VulkanGeometryBufferResource(
                            bufferPool
                    );

            resources.put(
                    state,
                    resource
            );

            ResidencyRecord residencyRecord =
                    new ResidencyRecord();

            /*
             * An upload is not evidence that the section is visible. New
             * resources start cold and are promoted to the warm set by the
             * complete visible SOLID preflight.
             */
            residencyRecord.lastVisibleEpoch =
                    Math.max(
                            0L,
                            visibleResidencyEpoch
                                    - VISIBLE_RESIDENCY_WARM_SWEEPS
                                    - 1L
                    );

            residencyRecords.put(
                    state,
                    residencyRecord
            );

            residencyOrder.addLast(
                    state
            );

            if ("DYNAMIC".equals(
                    state.usageName()
            )) {
                dynamicAdmissionCount++;
                activeDynamicResourceCount++;
            } else {
                staticAdmissionCount++;
                activeStaticResourceCount++;
            }

            peakActiveResourceCount =
                    Math.max(
                            peakActiveResourceCount,
                            resources.size()
                    );
        } else if (
                "DYNAMIC".equals(
                        state.usageName()
                )
                        && resource
                        .shouldThrottleDynamicUpload(
                                System.nanoTime(),
                                DYNAMIC_UPLOAD_MIN_INTERVAL_NANOS
                        )
        ) {
            throttledUploadCount++;
            return;
        }

        try {
            VulkanGeometryBufferResource.UploadOutcome
                    outcome =
                    resource.upload(
                            state,
                            geometry
                    );

            admittedUploadCount++;

            vertexBytesUploaded +=
                    outcome.vertexBytes();

            indexBytesUploaded +=
                    outcome.indexBytes();

            if (outcome.vertexReallocated()) {
                vertexAllocationCreateOrGrowCount++;
            }

            if (outcome.indexReallocated()) {
                indexAllocationCreateOrGrowCount++;
            }

            if (outcome.vertexHostCoherent()) {
                hostCoherentVertexUploadCount++;
            } else {
                nonCoherentVertexUploadCount++;
            }

            if (outcome.indexBytes() > 0) {
                if (outcome.indexHostCoherent()) {
                    hostCoherentIndexUploadCount++;
                } else {
                    nonCoherentIndexUploadCount++;
                }
            }

            report.addProperty(
                    "geometryUploadPrototypeLastVertexMemoryTypeIndex",
                    outcome.vertexMemoryTypeIndex()
            );
            report.addProperty(
                    "geometryUploadPrototypeLastIndexMemoryTypeIndex",
                    outcome.indexMemoryTypeIndex()
            );
            report.addProperty(
                    "geometryUploadPrototypeLastVertexBytes",
                    outcome.vertexBytes()
            );
            report.addProperty(
                    "geometryUploadPrototypeLastIndexBytes",
                    outcome.indexBytes()
            );
            report.addProperty(
                    "geometryUploadPrototypeLastUploadGeneration",
                    state.uploadGeneration()
            );
            report.addProperty(
                    "geometryUploadPrototypeLastUsage",
                    state.usageName()
            );
            report.addProperty(
                    "geometryUploadPrototypeLastMode",
                    geometry.mode().name()
            );
            report.addProperty(
                    "geometryUploadPrototypeLastIndexType",
                    geometry.indexType().name()
            );
        } catch (Throwable throwable) {
            disableAfterFailure(
                    throwable
            );
        }
    }

    @Override
    public void onShaderDraw(
            DrawBufferBackendState state,
            DrawShaderContext context
    ) {
        // Retired from the release hot path in Patch 040b.
    }

    @Override
    public void onPlainDraw(
            DrawBufferBackendState state
    ) {
        // Retired from the release hot path in Patch 040b.
    }

    @Override
    public void onSectionLayerDraw(
            DrawBufferBackendState state,
            SectionLayerDrawContext context
    ) {
        sectionLayerObservedCount++;

        if (closed
                || disabledAfterFailure) {
            return;
        }

        VulkanGeometryBufferResource resource =
                resources.get(
                        state
                );

        if (resource == null) {
            sectionLayerUnmirroredResourceCount++;
            return;
        }

        if (!resource.readyFor(
                state
        )) {
            sectionLayerStaleResourceCount++;
            return;
        }

        sectionLayerReadyResourceCount++;

        if ("STATIC".equals(
                state.usageName()
        )) {
            sectionLayerStaticReadyResourceCount++;
        }

        if (state.mode()
                == com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS) {
            sectionLayerQuadReadyResourceCount++;
        }

        lastSectionLayerCandidateRenderType =
                String.valueOf(
                        context.renderType()
                );

        lastSectionLayerCandidateFormat =
                state.formatDescription();

        lastSectionLayerCandidateMode =
                state.modeName();

        lastSectionLayerCandidateIndexType =
                state.indexTypeName();

        lastSectionLayerCandidateVertexStrideBytes =
                state.vertexStrideBytes();

        lastSectionLayerCandidateVertexCount =
                state.vertexCount();

        lastSectionLayerCandidateIndexCount =
                state.indexCount();

        lastSectionLayerCandidateUploadGeneration =
                state.uploadGeneration();

        /*
         * Patch 037's one-shot magenta draw was already proven. Do not repeat
         * a fence/query GPU proof during every normal client run.
         */
    }

    @Override
    public void onClose(
            DrawBufferBackendState state
    ) {
        arenaPlanner.onClose(
                state
        );

        VulkanRegionArenaSurvey.onClose(
                state
        );

        VulkanGeometryBufferResource resource =
                resources.remove(
                        state
                );

        residencyRecords.remove(
                state
        );

        VulkanVisibleGeometryResidency
                .onStateClosed(
                        state
                );

        if (resource == null) {
            return;
        }

        try {
            resource.close();
            resourceReleasedByMinecraftCount++;

            if ("DYNAMIC".equals(
                    state.usageName()
            )) {
                activeDynamicResourceCount =
                        Math.max(
                                0,
                                activeDynamicResourceCount - 1
                        );
            } else if ("STATIC".equals(
                    state.usageName()
            )) {
                activeStaticResourceCount =
                        Math.max(
                                0,
                                activeStaticResourceCount - 1
                        );
            }
        } catch (Throwable throwable) {
            disableAfterFailure(
                    throwable
            );
        }
    }

    synchronized void markVisibleResidency(
            DrawBufferBackendState state,
            long visibilityEpoch
    ) {
        if (state == null) {
            return;
        }

        visibleResidencyEpoch =
                Math.max(
                        visibleResidencyEpoch,
                        visibilityEpoch
                );

        ResidencyRecord record =
                residencyRecords.get(
                        state
                );

        if (record != null) {
            record.lastVisibleEpoch =
                    visibleResidencyEpoch;

            visibleResidencyTouchCount++;
        }
    }

    synchronized boolean promoteVisibleResidency(
            DrawBufferBackendState state,
            DrawGeometryView geometry,
            long visibilityEpoch
    ) {
        visibleResidencyPromotionAttemptCount++;

        if (closed
                || disabledAfterFailure
                || state == null
                || geometry == null
                || state.closed()
                || !state.uploaded()) {
            visibleResidencyPromotionFailureCount++;
            return false;
        }

        markVisibleResidency(
                state,
                visibilityEpoch
        );

        VulkanGeometryBufferResource existing =
                resources.get(
                        state
                );

        if (existing != null
                && existing.readyFor(state)) {
            visibleResidencyPromotionSuccessCount++;
            return true;
        }

        if (existing == null
                && activeStaticResourceCount
                >= MAX_STATIC_ADMISSIONS) {
            evictOneColdResidency(
                    visibleResidencyEpoch
            );
        }

        if (existing == null
                && activeStaticResourceCount
                >= MAX_STATIC_ADMISSIONS) {
            visibleResidencyPromotionFailureCount++;
            return false;
        }

        /*
         * DrawBufferBackendState already observed this MeshData generation in
         * the VertexBuffer seam. Calling onUpload here mirrors exactly those
         * bytes without incrementing the state generation a second time.
         */
        onUpload(
                state,
                geometry
        );

        VulkanGeometryBufferResource promoted =
                resources.get(
                        state
                );

        boolean ready =
                promoted != null
                        && promoted.readyFor(state);

        if (ready) {
            ResidencyRecord record =
                    residencyRecords.get(
                            state
                    );

            if (record != null) {
                record.lastVisibleEpoch =
                        visibleResidencyEpoch;
            }

            visibleResidencyPromotionSuccessCount++;
        } else {
            visibleResidencyPromotionFailureCount++;
        }

        return ready;
    }

    private boolean evictOneColdResidency(
            long currentEpoch
    ) {
        int attempts =
                residencyOrder.size();

        while (attempts-- > 0) {
            DrawBufferBackendState victimState =
                    residencyOrder.pollFirst();

            if (victimState == null) {
                break;
            }

            ResidencyRecord record =
                    residencyRecords.get(
                            victimState
                    );

            VulkanGeometryBufferResource resource =
                    resources.get(
                            victimState
                    );

            if (record == null
                    || resource == null) {
                continue;
            }

            /*
             * Keep recently visible front/back geometry resident for a bounded
             * turn-around window. Only genuinely cold loaded sections may
             * surrender scarce per-section mirror slots.
             */
            if (record.lastVisibleEpoch
                    + VISIBLE_RESIDENCY_WARM_SWEEPS
                    >= currentEpoch) {
                residencyOrder.addLast(
                        victimState
                );
                continue;
            }

            resources.remove(
                    victimState
            );

            residencyRecords.remove(
                    victimState
            );

            try {
                resource.close();

                /*
                 * Patch 071 makes close pin-safe. If a command buffer still
                 * references this allocation, retirement is deferred until its
                 * non-blocking fence harvest.
                 */
                visibleResidencyPinSafeCloseCount++;
            } catch (Throwable throwable) {
                disableAfterFailure(
                        throwable
                );
                return false;
            }

            if ("STATIC".equals(
                    victimState.usageName()
            )) {
                activeStaticResourceCount =
                        Math.max(
                                0,
                                activeStaticResourceCount - 1
                        );
            } else if ("DYNAMIC".equals(
                    victimState.usageName()
            )) {
                activeDynamicResourceCount =
                        Math.max(
                                0,
                                activeDynamicResourceCount - 1
                        );
            }

            visibleResidencyColdEvictionCount++;
            return true;
        }

        visibleResidencyNoColdVictimCount++;
        return false;
    }

    synchronized boolean sectionLayerCandidateVerified() {
        return sectionLayerObservedCount > 0
                && sectionLayerReadyResourceCount > 0
                && sectionLayerStaticReadyResourceCount > 0
                && sectionLayerQuadReadyResourceCount > 0
                && failureCount == 0
                && !disabledAfterFailure;
    }

    synchronized boolean verified() {
        /*
         * Patch 043's production layer path validates ready geometry inside
         * VulkanTexturedMultiSectionFrame rather than through the historical
         * per-draw SectionLayerDrawContext observer.
         */
        return admittedUploadCount > 0
                && vertexBytesUploaded > 0
                && vertexAllocationCreateOrGrowCount > 0
                && failureCount == 0
                && !disabledAfterFailure;
    }

    synchronized void enrich() {
        regionArena.enrich();

        long activeAllocationBytes =
                0L;

        int activeExplicitIndexResources =
                0;

        int readyResourceCount =
                0;

        for (Map.Entry<
                DrawBufferBackendState,
                VulkanGeometryBufferResource>
                entry : resources.entrySet()) {

            VulkanGeometryBufferResource resource =
                    entry.getValue();

            activeAllocationBytes +=
                    resource.allocationBytes();

            if (resource
                    .lastUploadUsedExplicitIndices()) {
                activeExplicitIndexResources++;
            }

            if (resource.readyFor(
                    entry.getKey()
            )) {
                readyResourceCount++;
            }
        }

        report.addProperty(
                "geometryUploadPrototypeInstalled",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeMode",
                "POOLED_PERSISTENTLY_MAPPED_HOST_VISIBLE_VKBUFFER_MIRROR"
        );

        report.addProperty(
                "geometryUploadPrototypeMaxDynamicAdmissions",
                MAX_DYNAMIC_ADMISSIONS
        );
        report.addProperty(
                "geometryUploadPrototypeDefaultStaticAdmissions",
                DEFAULT_STATIC_ADMISSIONS
        );
        report.addProperty(
                "geometryUploadPrototypeMaxStaticAdmissions",
                MAX_STATIC_ADMISSIONS
        );
        report.addProperty(
                "geometryUploadPrototypeStaticAdmissionProfile",
                "ADAPTIVE_HIGH_RESIDENCY_WORKING_SET"
        );
        report.addProperty(
                "geometryUploadPrototypeStaticAdmissionCapacityDeniedCount",
                staticAdmissionCapacityDeniedCount
        );
        report.addProperty(
                "geometryUploadPrototypeDynamicThrottleMillis",
                DYNAMIC_UPLOAD_MIN_INTERVAL_NANOS
                        / 1_000_000L
        );
        report.addProperty(
                "geometryUploadPrototypeMaxSingleStreamBytes",
                MAX_SINGLE_STREAM_BYTES
        );

        report.addProperty(
                "geometryUploadPrototypeDispatcherPrefilterEnabled",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeVertexMixinBlockMetadataFilterEnabled",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeLazySidecarAllocationEnabled",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeDynamicHotPathDisabled",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeAcceptedUploadClass",
                "STATIC_BLOCK_QUADS_SEQUENTIAL_ONLY"
        );
        report.addProperty(
                "geometryUploadPrototypeGlobalDrawObservationRetired",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeProductionFrameBatchConsumer",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeProductionStaticAdmissionWindow",
                MAX_STATIC_ADMISSIONS
        );
        report.addProperty(
                "geometryUploadPrototypeOneShotSectionDrawRetired",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeAllocationReusePoolEnabled",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeActualVkBufferCreateCount",
                bufferPool.createdAllocationCount()
        );
        report.addProperty(
                "geometryUploadPrototypeAllocationReuseHitCount",
                bufferPool.reuseHitCount()
        );
        report.addProperty(
                "geometryUploadPrototypeActiveDynamicResourceCount",
                activeDynamicResourceCount
        );
        report.addProperty(
                "geometryUploadPrototypeActiveStaticResourceCount",
                activeStaticResourceCount
        );

        report.addProperty(
                "geometryUploadPrototypeObservedUploadCount",
                observedUploadCount
        );
        report.addProperty(
                "geometryUploadPrototypeMirroredUploadCount",
                admittedUploadCount
        );
        report.addProperty(
                "geometryUploadPrototypeThrottledUploadCount",
                throttledUploadCount
        );
        report.addProperty(
                "geometryUploadPrototypeSkippedUnadmittedUploadCount",
                skippedUnadmittedUploadCount
        );
        report.addProperty(
                "geometryUploadPrototypeSkippedOversizeUploadCount",
                skippedOversizeUploadCount
        );

        report.addProperty(
                "geometryUploadPrototypeDynamicAdmissionCount",
                dynamicAdmissionCount
        );
        report.addProperty(
                "geometryUploadPrototypeStaticAdmissionCount",
                staticAdmissionCount
        );

        report.addProperty(
                "geometryUploadPrototypeVertexBytesUploaded",
                vertexBytesUploaded
        );
        report.addProperty(
                "geometryUploadPrototypeIndexBytesUploaded",
                indexBytesUploaded
        );

        report.addProperty(
                "geometryUploadPrototypeVertexAllocationCreateOrGrowCount",
                vertexAllocationCreateOrGrowCount
        );
        report.addProperty(
                "geometryUploadPrototypeIndexAllocationCreateOrGrowCount",
                indexAllocationCreateOrGrowCount
        );

        report.addProperty(
                "geometryUploadPrototypeHostCoherentVertexUploadCount",
                hostCoherentVertexUploadCount
        );
        report.addProperty(
                "geometryUploadPrototypeNonCoherentVertexUploadCount",
                nonCoherentVertexUploadCount
        );
        report.addProperty(
                "geometryUploadPrototypeHostCoherentIndexUploadCount",
                hostCoherentIndexUploadCount
        );
        report.addProperty(
                "geometryUploadPrototypeNonCoherentIndexUploadCount",
                nonCoherentIndexUploadCount
        );

        report.addProperty(
                "geometryUploadPrototypeDrawReadyCount",
                drawReadyCount
        );
        report.addProperty(
                "geometryUploadPrototypeDrawStaleCount",
                drawStaleCount
        );
        report.addProperty(
                "geometryUploadPrototypeDrawUnmirroredCount",
                drawUnmirroredCount
        );

        report.addProperty(
                "geometryUploadPrototypeActiveResourceCount",
                resources.size()
        );
        report.addProperty(
                "geometryUploadPrototypePeakActiveResourceCount",
                peakActiveResourceCount
        );
        report.addProperty(
                "geometryUploadPrototypeReadyResourceCount",
                readyResourceCount
        );
        report.addProperty(
                "geometryUploadPrototypeActiveExplicitIndexResourceCount",
                activeExplicitIndexResources
        );
        report.addProperty(
                "geometryUploadPrototypeActiveAllocationBytes",
                activeAllocationBytes
        );

        report.addProperty(
                "geometryUploadPrototypeResourceReleasedByMinecraftCount",
                resourceReleasedByMinecraftCount
        );
        report.addProperty(
                "geometryUploadPrototypeResourceReleasedAtShutdownCount",
                resourceReleasedAtShutdownCount
        );

        report.addProperty(
                "geometryUploadPrototypeSectionLayerObservedCount",
                sectionLayerObservedCount
        );
        report.addProperty(
                "geometryUploadPrototypeSectionLayerReadyResourceCount",
                sectionLayerReadyResourceCount
        );
        report.addProperty(
                "geometryUploadPrototypeSectionLayerStaticReadyResourceCount",
                sectionLayerStaticReadyResourceCount
        );
        report.addProperty(
                "geometryUploadPrototypeSectionLayerQuadReadyResourceCount",
                sectionLayerQuadReadyResourceCount
        );
        report.addProperty(
                "geometryUploadPrototypeSectionLayerUnmirroredResourceCount",
                sectionLayerUnmirroredResourceCount
        );
        report.addProperty(
                "geometryUploadPrototypeSectionLayerStaleResourceCount",
                sectionLayerStaleResourceCount
        );

        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateRenderType",
                lastSectionLayerCandidateRenderType
        );
        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateFormat",
                lastSectionLayerCandidateFormat
        );
        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateMode",
                lastSectionLayerCandidateMode
        );
        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateIndexType",
                lastSectionLayerCandidateIndexType
        );
        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateVertexStrideBytes",
                lastSectionLayerCandidateVertexStrideBytes
        );
        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateVertexCount",
                lastSectionLayerCandidateVertexCount
        );
        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateIndexCount",
                lastSectionLayerCandidateIndexCount
        );
        report.addProperty(
                "geometryUploadPrototypeLastSectionLayerCandidateUploadGeneration",
                lastSectionLayerCandidateUploadGeneration
        );
        report.addProperty(
                "geometryUploadPrototypeSectionLayerCandidateVerified",
                sectionLayerCandidateVerified()
        );

        report.addProperty(
                "geometryUploadPrototypeFailureCount",
                failureCount
        );
        report.addProperty(
                "geometryUploadPrototypeDisabledAfterFailure",
                disabledAfterFailure
        );
        report.addProperty(
                "geometryUploadPrototypeClosed",
                closed
        );

        if (!lastFailure.isBlank()) {
            report.addProperty(
                    "geometryUploadPrototypeLastFailure",
                    lastFailure
            );
        }

        report.addProperty(
                "geometryUploadPrototypeUsesRealVkBuffer",
                vertexAllocationCreateOrGrowCount > 0
        );
        report.addProperty(
                "geometryUploadPrototypeUsesRealVkDeviceMemory",
                vertexAllocationCreateOrGrowCount > 0
        );
        report.addProperty(
                "geometryUploadPrototypePersistentHostMapping",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeSampleValidationPassed",
                admittedUploadCount > 0
                        && failureCount == 0
        );
        report.addProperty(
                "geometryUploadPrototypeQueueSubmissionPerUpload",
                false
        );
        report.addProperty(
                "geometryUploadPrototypeDeviceWaitIdlePerUpload",
                false
        );
        report.addProperty(
                "geometryUploadPrototypeOpenGlStillAuthoritative",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeDrawExecutionEnabled",
                false
        );
        report.addProperty(
                "geometryUploadPrototypeVerified",
                verified()
        );

        report.addProperty(
                "geometryUploadPrototypeVisibleFirstResidency",
                true
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyEpoch",
                visibleResidencyEpoch
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyTouchCount",
                visibleResidencyTouchCount
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyPromotionAttemptCount",
                visibleResidencyPromotionAttemptCount
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyPromotionSuccessCount",
                visibleResidencyPromotionSuccessCount
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyPromotionFailureCount",
                visibleResidencyPromotionFailureCount
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyColdEvictionCount",
                visibleResidencyColdEvictionCount
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyPinSafeCloseCount",
                visibleResidencyPinSafeCloseCount
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyNoColdVictimCount",
                visibleResidencyNoColdVictimCount
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyRecordCount",
                residencyRecords.size()
        );
        report.addProperty(
                "geometryUploadPrototypeVisibleResidencyWarmSweeps",
                VISIBLE_RESIDENCY_WARM_SWEEPS
        );

        VulkanVisiblePublicationGate.enrich(
                report
        );

        VulkanVisibleGeometryResidency.enrich(
                report
        );

        PotatoLodRuntime.enrich(
                report
        );

        PotatoRuntimeSettings.enrich(
                report
        );

        PotatoAdaptiveViewController.enrich(
                report
        );

        VulkanRegionArenaSurvey.enrich(
                report
        );

        arenaPlanner.enrich(
                report
        );

        bufferPool.enrich(
                report
        );
    }

    private static int defaultStaticAdmissions() {
        return PotatoRuntimeSettings
                .staticAdmissionBudget();
    }

    private boolean canAdmit(
            DrawBufferBackendState state
    ) {
        String usage =
                state.usageName();

        if ("DYNAMIC".equals(
                usage
        )) {
            return activeDynamicResourceCount
                    < MAX_DYNAMIC_ADMISSIONS;
        }

        if ("STATIC".equals(
                usage
        )) {
            return activeStaticResourceCount
                    < MAX_STATIC_ADMISSIONS;
        }

        return false;
    }

    private void observeDraw(
            DrawBufferBackendState state
    ) {
        if (closed
                || disabledAfterFailure) {
            return;
        }

        VulkanGeometryBufferResource resource =
                resources.get(
                        state
                );

        if (resource == null) {
            drawUnmirroredCount++;
            return;
        }

        if (resource.readyFor(
                state
        )) {
            drawReadyCount++;
        } else {
            drawStaleCount++;
        }
    }

    private void disableAfterFailure(
            Throwable throwable
    ) {
        failureCount++;
        disabledAfterFailure = true;

        lastFailure =
                throwable.getClass().getName()
                        + ": "
                        + String.valueOf(
                                throwable.getMessage()
                        );

        /*
         * Do not rethrow. The draw contract remains active and OpenGL remains
         * authoritative even if this optional Vulkan prototype disables itself.
         */
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;

        for (Map.Entry<
                DrawBufferBackendState,
                VulkanGeometryBufferResource> entry
                : resources.entrySet()) {
            try {
                entry.getValue().close();
                resourceReleasedAtShutdownCount++;
            } catch (Throwable throwable) {
                failureCount++;

                lastFailure =
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                        throwable.getMessage()
                                );
            } finally {
                arenaPlanner.onClose(
                        entry.getKey()
                );
                VulkanRegionArenaSurvey.onClose(
                        entry.getKey()
                );
            }
        }

        resources.clear();
        residencyRecords.clear();
        residencyOrder.clear();

        VulkanRegionArenaIngress.unregister(
                regionArena
        );
        regionArena.close();

        arenaPlanner.close();
        bufferPool.close();

        enrich();

        PotatoTemporalOcclusionRuntime.close();
        PotatoLodRuntime.close();

        VulkanVisibleGeometryResidency
                .unregister(this);
    }

    private static final class ResidencyRecord {
        private long lastVisibleEpoch;
    }
}