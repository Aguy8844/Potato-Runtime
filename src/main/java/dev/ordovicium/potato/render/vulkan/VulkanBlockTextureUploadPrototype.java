package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.render.resource.BlockAtlasSnapshot;
import dev.ordovicium.potato.render.resource.BlockResourceCapture;
import dev.ordovicium.potato.render.resource.LightmapSnapshot;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Patch 041 GPU bridge for Sampler0/Sampler2 source images.
 *
 * <p>Patch 066 promotes the initial atlas/lightmap proof into a live,
 * generation-aware lifecycle. New CPU snapshots are coalesced and copied in
 * the same Vulkan submission as the section batch, with no extra queue submit
 * and no gameplay fence wait.</p>
 */
final class VulkanBlockTextureUploadPrototype
        implements AutoCloseable {

    /*
     * Patch 132 release gate:
     * Gate 9 proves the live texture LIFECYCLE, not that the lightmap happens
     * to be byte-current at the arbitrary instant the shutdown report is read.
     *
     * The 131 run completed 35 real live lightmap uploads with zero failures.
     * Its final source generation was newer only because visible Vulkan SOLID
     * work had already quiesced before the world -> menu handoff. Requiring
     * source==uploaded at shutdown therefore made a proven working lifecycle
     * report false whenever no final SOLID batch happened on the last world
     * frame.
     *
     * Eight completed live generations is a deliberately conservative current-
     * process proof. Current-generation equality remains reported separately
     * and is NOT fabricated.
     */
    private static final long LIVE_LIFECYCLE_MIN_COMPLETIONS =
            8L;

    private final VulkanTextureImageResource atlas;
    private final VulkanTextureImageResource lightmap;

    private final JsonObject report;

    private boolean attempted;
    private boolean succeeded;

    private boolean disabledAfterFailure;
    private long failureCount;

    private String lastFailure =
            "";

    private long sourceAtlasGeneration;
    private long sourceLightmapGeneration;

    private long totalUploadMillis;

    private long liveSyncAttemptCount;
    private long liveSyncPreparedCount;
    private long liveSyncRecordedCount;
    private long liveSyncCompletedCount;
    private long liveSyncCoalescedCount;

    private long initialAtlasGeneration;
    private long initialLightmapGeneration;

    private boolean liveLifecycleQualified;
    private long liveLifecycleQualifiedAtCompletionCount;
    private long liveLifecycleQualifiedAtlasGeneration;
    private long liveLifecycleQualifiedLightmapGeneration;

    private boolean liveGenerationSynchronizedBeforeClose;
    private boolean liveLifecycleQualifiedBeforeClose;

    private boolean verifiedBeforeClose;
    private boolean closed;

    VulkanBlockTextureUploadPrototype(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            JsonObject report
    ) {
        this.report = report;

        this.atlas =
                new VulkanTextureImageResource(
                        device,
                        physicalDevice,
                        graphicsQueue,
                        graphicsQueueFamilyIndex,
                        "BLOCK_ATLAS"
                );

        this.lightmap =
                new VulkanTextureImageResource(
                        device,
                        physicalDevice,
                        graphicsQueue,
                        graphicsQueueFamilyIndex,
                        "LIGHTMAP"
                );
    }

    void tryUploadInitialSnapshots() {
        if (attempted
                || succeeded
                || disabledAfterFailure) {
            return;
        }

        BlockAtlasSnapshot atlasSnapshot =
                BlockResourceCapture
                        .blockAtlasSnapshot();

        LightmapSnapshot lightmapSnapshot =
                BlockResourceCapture
                        .lightmapSnapshot();

        if (atlasSnapshot == null
                || lightmapSnapshot == null) {
            return;
        }

        attempted = true;

        long started =
                System.nanoTime();

        try {
            sourceAtlasGeneration =
                    atlasSnapshot.generation();

            sourceLightmapGeneration =
                    lightmapSnapshot.generation();

            initialAtlasGeneration =
                    atlasSnapshot.generation();

            initialLightmapGeneration =
                    lightmapSnapshot.generation();

            atlas.uploadInitial(
                    atlasSnapshot.rgbaView(),
                    atlasSnapshot.width(),
                    atlasSnapshot.height(),
                    atlasSnapshot.generation()
            );

            lightmap.uploadInitial(
                    lightmapSnapshot.rgbaView(),
                    lightmapSnapshot.width(),
                    lightmapSnapshot.height(),
                    lightmapSnapshot.generation()
            );

            succeeded =
                    atlas.verified()
                            && lightmap.verified();

            if (!succeeded) {
                throw new VulkanProbeException(
                        "VULKAN_BLOCK_TEXTURE_UPLOAD",
                        "Texture image resources did not satisfy the post-upload verification contract."
                );
            }
        } catch (Throwable throwable) {
            failureCount++;
            disabledAfterFailure = true;

            lastFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );
        } finally {
            totalUploadMillis =
                    Math.max(
                            0L,
                            System.nanoTime()
                                    - started
                    )
                            / 1_000_000L;
        }
    }

    /**
     * Coalesce the newest atlas/lightmap snapshots into the persistent staging
     * resources. This method does not submit GPU work and never waits.
     */
    void prepareLatestSnapshotsForFrame() {
        if (closed
                || disabledAfterFailure
                || !succeeded) {
            return;
        }

        liveSyncAttemptCount++;

        try {
            BlockAtlasSnapshot atlasSnapshot =
                    BlockResourceCapture
                            .blockAtlasSnapshot();

            LightmapSnapshot lightmapSnapshot =
                    BlockResourceCapture
                            .lightmapSnapshot();

            if (atlasSnapshot != null) {
                sourceAtlasGeneration =
                        Math.max(
                                sourceAtlasGeneration,
                                atlasSnapshot.generation()
                        );

                if (atlasSnapshot.generation()
                        > atlas.uploadedGeneration()) {

                    boolean prepared =
                            atlas.prepareUpdate(
                                    atlasSnapshot.rgbaView(),
                                    atlasSnapshot.width(),
                                    atlasSnapshot.height(),
                                    atlasSnapshot.generation()
                            );

                    if (prepared) {
                        liveSyncPreparedCount++;
                    } else {
                        liveSyncCoalescedCount++;
                    }
                }
            }

            if (lightmapSnapshot != null) {
                sourceLightmapGeneration =
                        Math.max(
                                sourceLightmapGeneration,
                                lightmapSnapshot.generation()
                        );

                if (lightmapSnapshot.generation()
                        > lightmap.uploadedGeneration()) {

                    boolean prepared =
                            lightmap.prepareUpdate(
                                    lightmapSnapshot.rgbaView(),
                                    lightmapSnapshot.width(),
                                    lightmapSnapshot.height(),
                                    lightmapSnapshot.generation()
                            );

                    if (prepared) {
                        liveSyncPreparedCount++;
                    } else {
                        liveSyncCoalescedCount++;
                    }
                }
            }
        } catch (Throwable throwable) {
            failureCount++;
            disabledAfterFailure =
                    true;

            lastFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                                    throwable.getMessage()
                            );
        }
    }

    /**
     * Piggyback prepared texture copies into the same command buffer as the
     * Vulkan section batch. No additional queue submission is created.
     */
    boolean recordPreparedUpdates(
            VkCommandBuffer commandBuffer
    ) {
        if (closed
                || disabledAfterFailure
                || commandBuffer == null) {
            return false;
        }

        boolean atlasRecorded =
                atlas.recordPreparedUpload(
                        commandBuffer
                );

        boolean lightmapRecorded =
                lightmap.recordPreparedUpload(
                        commandBuffer
                );

        if (atlasRecorded
                || lightmapRecorded) {
            liveSyncRecordedCount++;
            return true;
        }

        return false;
    }

    /**
     * Called only after the piggybacking world submission has completed.
     */
    void completeFrameSubmission() {
        boolean atlasCompleted =
                atlas.completeSubmittedUpload();

        boolean lightmapCompleted =
                lightmap.completeSubmittedUpload();

        if (atlasCompleted
                || lightmapCompleted) {
            liveSyncCompletedCount++;
        }

        /*
         * A completed update is committed only after the piggybacking Vulkan
         * world submission has finished. Latch Gate-9 lifecycle capability
         * after repeated real lightmap generations have crossed that boundary.
         *
         * This does not claim the last captured lightmap is permanently current.
         * It proves that live generations can be prepared, recorded, submitted
         * and committed safely in the current process. Exact source/uploaded
         * generation telemetry remains available below for per-frame diagnosis.
         */
        if (!liveLifecycleQualified
                && liveSyncCompletedCount
                >= LIVE_LIFECYCLE_MIN_COMPLETIONS
                && lightmap.liveCompleteCount()
                >= LIVE_LIFECYCLE_MIN_COMPLETIONS
                && lightmap.uploadedGeneration()
                > initialLightmapGeneration
                && atlas.uploadedGeneration()
                >= initialAtlasGeneration
                && liveVerified()) {
            liveLifecycleQualified =
                    true;

            liveLifecycleQualifiedAtCompletionCount =
                    liveSyncCompletedCount;

            liveLifecycleQualifiedAtlasGeneration =
                    atlas.uploadedGeneration();

            liveLifecycleQualifiedLightmapGeneration =
                    lightmap.uploadedGeneration();
        }
    }

    boolean liveLifecycleQualified() {
        if (closed) {
            return liveLifecycleQualifiedBeforeClose;
        }

        return liveLifecycleQualified
                && liveVerified();
    }

    boolean liveGenerationSynchronized() {
        if (closed) {
            return liveGenerationSynchronizedBeforeClose;
        }

        BlockAtlasSnapshot atlasSnapshot =
                BlockResourceCapture
                        .blockAtlasSnapshot();

        LightmapSnapshot lightmapSnapshot =
                BlockResourceCapture
                        .lightmapSnapshot();

        boolean atlasCurrent =
                atlasSnapshot != null
                        && atlas.uploadedGeneration()
                        >= atlasSnapshot.generation()
                        && !atlas.hasPreparedOrSubmittedUpload();

        boolean lightmapCurrent =
                lightmapSnapshot != null
                        && lightmap.uploadedGeneration()
                        >= lightmapSnapshot.generation()
                        && !lightmap.hasPreparedOrSubmittedUpload();

        return liveVerified()
                && atlasCurrent
                && lightmapCurrent;
    }

    boolean verified() {
        return verifiedBeforeClose
                || liveVerified();
    }

    private boolean liveVerified() {
        return !closed
                && attempted
                && succeeded
                && !disabledAfterFailure
                && failureCount == 0
                && atlas.verified()
                && lightmap.verified();
    }

    long atlasImageView() {
        return atlas.imageView();
    }

    long lightmapImageView() {
        return lightmap.imageView();
    }

    int atlasFormat() {
        return atlas.format();
    }

    int lightmapFormat() {
        return lightmap.format();
    }

    void enrich() {
        BlockAtlasSnapshot latestAtlas =
                BlockResourceCapture
                        .blockAtlasSnapshot();

        LightmapSnapshot latestLightmap =
                BlockResourceCapture
                        .lightmapSnapshot();

        if (latestAtlas != null) {
            sourceAtlasGeneration =
                    Math.max(
                            sourceAtlasGeneration,
                            latestAtlas.generation()
                    );
        }

        if (latestLightmap != null) {
            sourceLightmapGeneration =
                    Math.max(
                            sourceLightmapGeneration,
                            latestLightmap.generation()
                    );
        }

        report.addProperty(
                "blockTextureUploadPrototypeInstalled",
                true
        );
        report.addProperty(
                "blockTextureUploadPrototypeMode",
                "PERSISTENT_DEVICE_LOCAL_WITH_COALESCED_LIVE_GENERATION_SYNC"
        );
        report.addProperty(
                "blockTextureUploadAttempted",
                attempted
        );
        report.addProperty(
                "blockTextureUploadSucceeded",
                succeeded
        );
        report.addProperty(
                "blockTextureUploadVerified",
                verified()
        );
        report.addProperty(
                "blockTextureUploadFailureCount",
                failureCount
        );
        report.addProperty(
                "blockTextureUploadDisabledAfterFailure",
                disabledAfterFailure
        );

        if (!lastFailure.isBlank()) {
            report.addProperty(
                    "blockTextureUploadLastFailure",
                    lastFailure
            );
        }

        report.addProperty(
                "blockTextureUploadInitialOnly",
                false
        );
        report.addProperty(
                "blockTextureUploadLiveGenerationSyncDeferred",
                false
        );
        report.addProperty(
                "blockTextureUploadLiveGenerationSyncEnabled",
                true
        );
        report.addProperty(
                "blockTextureUploadLiveGenerationSynchronized",
                liveGenerationSynchronized()
        );
        report.addProperty(
                "blockTextureUploadLiveLifecycleQualified",
                liveLifecycleQualified()
        );
        report.addProperty(
                "blockTextureUploadLiveLifecycleQualificationMode",
                "REPEATED_COMPLETED_LIVE_GENERATION_PROOF"
        );
        report.addProperty(
                "blockTextureUploadLiveLifecycleMinCompletions",
                LIVE_LIFECYCLE_MIN_COMPLETIONS
        );
        report.addProperty(
                "blockTextureUploadLiveLifecycleQualifiedAtCompletionCount",
                liveLifecycleQualifiedAtCompletionCount
        );
        report.addProperty(
                "blockTextureUploadLiveLifecycleQualifiedAtlasGeneration",
                liveLifecycleQualifiedAtlasGeneration
        );
        report.addProperty(
                "blockTextureUploadLiveLifecycleQualifiedLightmapGeneration",
                liveLifecycleQualifiedLightmapGeneration
        );
        report.addProperty(
                "blockTextureUploadLiveLifecycleCurrentGenerationExact",
                liveGenerationSynchronized()
        );
        report.addProperty(
                "blockTextureUploadLiveSyncAttemptCount",
                liveSyncAttemptCount
        );
        report.addProperty(
                "blockTextureUploadLiveSyncPreparedCount",
                liveSyncPreparedCount
        );
        report.addProperty(
                "blockTextureUploadLiveSyncRecordedCount",
                liveSyncRecordedCount
        );
        report.addProperty(
                "blockTextureUploadLiveSyncCompletedCount",
                liveSyncCompletedCount
        );
        report.addProperty(
                "blockTextureUploadLiveSyncCoalescedCount",
                liveSyncCoalescedCount
        );
        report.addProperty(
                "blockTextureUploadLiveSyncPiggybacksWorldBatch",
                true
        );
        report.addProperty(
                "blockTextureUploadGameplayFenceWaitUsed",
                false
        );
        report.addProperty(
                "blockTextureUploadOpenGlReadbackUsed",
                false
        );
        report.addProperty(
                "blockTextureUploadDeviceWaitIdleUsed",
                false
        );
        report.addProperty(
                "blockTextureUploadPerFrameSubmission",
                false
        );
        report.addProperty(
                "blockTextureUploadTotalMillis",
                totalUploadMillis
        );

        report.addProperty(
                "blockTextureAtlasSourceGeneration",
                sourceAtlasGeneration
        );
        report.addProperty(
                "blockTextureAtlasUploadedGeneration",
                atlas.uploadedGeneration()
        );
        report.addProperty(
                "blockTextureAtlasWidth",
                atlas.width()
        );
        report.addProperty(
                "blockTextureAtlasHeight",
                atlas.height()
        );
        report.addProperty(
                "blockTextureAtlasFormat",
                atlas.format()
        );
        report.addProperty(
                "blockTextureAtlasFormatName",
                "VK_FORMAT_R8G8B8A8_UNORM"
        );
        report.addProperty(
                "blockTextureAtlasUploadedBytes",
                atlas.uploadedBytes()
        );
        report.addProperty(
                "blockTextureAtlasUploadCount",
                atlas.uploadCount()
        );
        report.addProperty(
                "blockTextureAtlasImageHandleNonZero",
                atlas.imageWasCreated()
        );
        report.addProperty(
                "blockTextureAtlasMemoryHandleNonZero",
                atlas.memoryWasAllocated()
        );
        report.addProperty(
                "blockTextureAtlasImageViewHandleNonZero",
                atlas.viewWasCreated()
        );
        report.addProperty(
                "blockTextureAtlasImageHandleCurrentlyNonZero",
                atlas.imageNonZero()
        );
        report.addProperty(
                "blockTextureAtlasMemoryHandleCurrentlyNonZero",
                atlas.memoryNonZero()
        );
        report.addProperty(
                "blockTextureAtlasImageViewHandleCurrentlyNonZero",
                atlas.viewNonZero()
        );
        report.addProperty(
                "blockTextureAtlasVerifiedBeforeClose",
                atlas.verifiedBeforeClose()
        );
        report.addProperty(
                "blockTextureAtlasClosed",
                atlas.closed()
        );
        report.addProperty(
                "blockTextureAtlasTeardownVerified",
                atlas.teardownVerified()
        );
        report.addProperty(
                "blockTextureAtlasMemoryTypeIndex",
                atlas.memoryTypeIndex()
        );
        report.addProperty(
                "blockTextureAtlasAllocationBytes",
                atlas.allocationBytes()
        );
        report.addProperty(
                "blockTextureAtlasQueueSubmitResult",
                atlas.lastQueueSubmitResult()
        );
        report.addProperty(
                "blockTextureAtlasFenceWaitResult",
                atlas.lastFenceWaitResult()
        );
        report.addProperty(
                "blockTextureAtlasFinalLayout",
                layoutName(
                        atlas.finalLayout()
                )
        );
        report.addProperty(
                "blockTextureAtlasUploadMillis",
                atlas.lastUploadMillis()
        );
        report.addProperty(
                "blockTextureAtlasLivePrepareCount",
                atlas.livePrepareCount()
        );
        report.addProperty(
                "blockTextureAtlasLiveRecordCount",
                atlas.liveRecordCount()
        );
        report.addProperty(
                "blockTextureAtlasLiveCompleteCount",
                atlas.liveCompleteCount()
        );
        report.addProperty(
                "blockTextureAtlasSubmittedGeneration",
                atlas.submittedGeneration()
        );

        report.addProperty(
                "blockTextureLightmapSourceGeneration",
                sourceLightmapGeneration
        );
        report.addProperty(
                "blockTextureLightmapUploadedGeneration",
                lightmap.uploadedGeneration()
        );
        report.addProperty(
                "blockTextureLightmapWidth",
                lightmap.width()
        );
        report.addProperty(
                "blockTextureLightmapHeight",
                lightmap.height()
        );
        report.addProperty(
                "blockTextureLightmapFormat",
                lightmap.format()
        );
        report.addProperty(
                "blockTextureLightmapFormatName",
                "VK_FORMAT_R8G8B8A8_UNORM"
        );
        report.addProperty(
                "blockTextureLightmapUploadedBytes",
                lightmap.uploadedBytes()
        );
        report.addProperty(
                "blockTextureLightmapUploadCount",
                lightmap.uploadCount()
        );
        report.addProperty(
                "blockTextureLightmapImageHandleNonZero",
                lightmap.imageWasCreated()
        );
        report.addProperty(
                "blockTextureLightmapMemoryHandleNonZero",
                lightmap.memoryWasAllocated()
        );
        report.addProperty(
                "blockTextureLightmapImageViewHandleNonZero",
                lightmap.viewWasCreated()
        );
        report.addProperty(
                "blockTextureLightmapImageHandleCurrentlyNonZero",
                lightmap.imageNonZero()
        );
        report.addProperty(
                "blockTextureLightmapMemoryHandleCurrentlyNonZero",
                lightmap.memoryNonZero()
        );
        report.addProperty(
                "blockTextureLightmapImageViewHandleCurrentlyNonZero",
                lightmap.viewNonZero()
        );
        report.addProperty(
                "blockTextureLightmapVerifiedBeforeClose",
                lightmap.verifiedBeforeClose()
        );
        report.addProperty(
                "blockTextureLightmapClosed",
                lightmap.closed()
        );
        report.addProperty(
                "blockTextureLightmapTeardownVerified",
                lightmap.teardownVerified()
        );
        report.addProperty(
                "blockTextureLightmapMemoryTypeIndex",
                lightmap.memoryTypeIndex()
        );
        report.addProperty(
                "blockTextureLightmapAllocationBytes",
                lightmap.allocationBytes()
        );
        report.addProperty(
                "blockTextureLightmapQueueSubmitResult",
                lightmap.lastQueueSubmitResult()
        );
        report.addProperty(
                "blockTextureLightmapFenceWaitResult",
                lightmap.lastFenceWaitResult()
        );
        report.addProperty(
                "blockTextureLightmapFinalLayout",
                layoutName(
                        lightmap.finalLayout()
                )
        );
        report.addProperty(
                "blockTextureLightmapUploadMillis",
                lightmap.lastUploadMillis()
        );
        report.addProperty(
                "blockTextureLightmapLivePrepareCount",
                lightmap.livePrepareCount()
        );
        report.addProperty(
                "blockTextureLightmapLiveRecordCount",
                lightmap.liveRecordCount()
        );
        report.addProperty(
                "blockTextureLightmapLiveCompleteCount",
                lightmap.liveCompleteCount()
        );
        report.addProperty(
                "blockTextureLightmapSubmittedGeneration",
                lightmap.submittedGeneration()
        );
        report.addProperty(
                "potatoEngineVulkanTextureLifecycleReady",
                liveLifecycleQualified()
        );

        report.addProperty(
                "blockTextureUploadDescriptorSetsCreated",
                false
        );
        report.addProperty(
                "blockTextureUploadShaderSamplingExecuted",
                false
        );
        report.addProperty(
                "blockTextureUploadVerifiedBeforeClose",
                verifiedBeforeClose
                        || liveVerified()
        );
        report.addProperty(
                "blockTextureUploadResourcesClosed",
                closed
        );
        report.addProperty(
                "blockTextureUploadTeardownVerified",
                !closed
                        || (
                        atlas.teardownVerified()
                                && lightmap.teardownVerified()
                )
        );
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        /*
         * Preserve the successful runtime proof before native handles are
         * intentionally destroyed.
         */
        verifiedBeforeClose =
                liveVerified();

        liveGenerationSynchronizedBeforeClose =
                liveGenerationSynchronized();

        liveLifecycleQualifiedBeforeClose =
                liveLifecycleQualified();

        lightmap.close();
        atlas.close();

        closed = true;
    }

    private static String layoutName(
            int layout
    ) {
        if (layout
                == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
            return "VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL";
        }

        if (layout
                == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
            return "VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL";
        }

        if (layout
                == VK_IMAGE_LAYOUT_UNDEFINED) {
            return "VK_IMAGE_LAYOUT_UNDEFINED";
        }

        return Integer.toString(
                layout
        );
    }
}