package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.ordovicium.potato.render.backend.draw.DrawBufferBackendState;
import dev.ordovicium.potato.render.backend.draw.DrawGeometryView;
import dev.ordovicium.potato.settings.PotatoRuntimeSettings;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.IdentityHashMap;

/**
 * Visible-first Vulkan geometry residency bridge.
 *
 * <p>The transitional renderer used to drop the Vulkan sidecar completely once
 * the per-section mirror hit its native allocation ceiling. At long view
 * distances that left many otherwise valid visible sections with no way to
 * become Vulkan-ready until Minecraft happened to rebuild them again.</p>
 *
 * <p>This bridge keeps only a bounded CPU snapshot for uploads that could not
 * be mirrored immediately. The snapshot is promoted lazily when the section
 * actually enters a visible SOLID preflight. Promotion is count- and byte-
 * budgeted per sweep, so OpenGL remains the atomic fail-open while residency
 * catches up without one giant render-thread burst.</p>
 */
public final class VulkanVisibleGeometryResidency {
    private static final long MIB =
            1024L * 1024L;

    private static final long DEFAULT_DEFERRED_BUDGET_BYTES =
            defaultDeferredBudgetBytes();

    private static final long DEFERRED_BUDGET_BYTES =
            Math.max(
                    8L * MIB,
                    Math.min(
                            256L * MIB,
                            Long.getLong(
                                    "potato.vulkan.visibleDeferredGeometryBytes",
                                    DEFAULT_DEFERRED_BUDGET_BYTES
                            )
                    )
            );

    private static final int DEFAULT_DEFERRED_ENTRY_LIMIT =
            PotatoRuntimeSettings
                    .visibleDeferredEntryLimit();

    private static final int DEFERRED_ENTRY_LIMIT =
            Math.max(
                    256,
                    Math.min(
                            16384,
                            Integer.getInteger(
                                    "potato.vulkan.visibleDeferredGeometryEntries",
                                    DEFAULT_DEFERRED_ENTRY_LIMIT
                            )
                    )
            );

    private static final int DEFAULT_PROMOTIONS_PER_SWEEP =
            defaultPromotionsPerSweep();

    private static final int PROMOTIONS_PER_SWEEP =
            Math.max(
                    4,
                    Math.min(
                            256,
                            Integer.getInteger(
                                    "potato.vulkan.visiblePromotionsPerSweep",
                                    DEFAULT_PROMOTIONS_PER_SWEEP
                            )
                    )
            );

    private static final long DEFAULT_PROMOTION_BYTES_PER_SWEEP =
            defaultPromotionBytesPerSweep();

    private static final long PROMOTION_BYTES_PER_SWEEP =
            Math.max(
                    256L * 1024L,
                    Math.min(
                            8L * MIB,
                            Long.getLong(
                                    "potato.vulkan.visiblePromotionBytesPerSweep",
                                    DEFAULT_PROMOTION_BYTES_PER_SWEEP
                            )
                    )
            );

    private static final IdentityHashMap<
            DrawBufferBackendState,
            DeferredGeometry> deferred =
            new IdentityHashMap<>();

    private static final ArrayDeque<DeferredKey> deferredOrder =
            new ArrayDeque<>();

    private static VulkanGeometryUploadPrototype activePrototype;

    private static long deferredBytes;
    private static long peakDeferredBytes;
    private static int peakDeferredEntries;

    private static long captureCount;
    private static long replacementCount;
    private static long evictionCount;
    private static long staleDiscardCount;
    private static long immediateMirrorReadyCount;
    private static long immediateMirrorFallbackCaptureCount;

    private static long visibleSweepEpoch;
    private static long visibleSweepCount;
    private static int promotionsUsedThisSweep;
    private static long promotionBytesUsedThisSweep;

    private static long visiblePrepareCount;
    private static long visibleReadyHitCount;
    private static long visibleDeferredMissCount;
    private static long promotionAttemptCount;
    private static long promotionSuccessCount;
    private static long promotionFailureCount;
    private static long promotionCountBudgetRejectCount;
    private static long promotionByteBudgetRejectCount;

    private static int peakPromotionsPerSweep;
    private static long peakPromotionBytesPerSweep;

    private VulkanVisibleGeometryResidency() {
    }

    static synchronized void register(
            VulkanGeometryUploadPrototype prototype
    ) {
        clearDeferred();
        activePrototype = prototype;

        deferredBytes = 0L;
        peakDeferredBytes = 0L;
        peakDeferredEntries = 0;

        captureCount = 0L;
        replacementCount = 0L;
        evictionCount = 0L;
        staleDiscardCount = 0L;
        immediateMirrorReadyCount = 0L;
        immediateMirrorFallbackCaptureCount = 0L;

        visibleSweepEpoch = 0L;
        visibleSweepCount = 0L;
        promotionsUsedThisSweep = 0;
        promotionBytesUsedThisSweep = 0L;

        visiblePrepareCount = 0L;
        visibleReadyHitCount = 0L;
        visibleDeferredMissCount = 0L;
        promotionAttemptCount = 0L;
        promotionSuccessCount = 0L;
        promotionFailureCount = 0L;
        promotionCountBudgetRejectCount = 0L;
        promotionByteBudgetRejectCount = 0L;

        peakPromotionsPerSweep = 0;
        peakPromotionBytesPerSweep = 0L;
    }

    static synchronized void unregister(
            VulkanGeometryUploadPrototype prototype
    ) {
        if (activePrototype == prototype) {
            activePrototype = null;
            clearDeferred();
        }
    }

    public static synchronized boolean active() {
        return activePrototype != null;
    }

    /**
     * Called after DrawSubmissionDispatcher attempted the immediate Vulkan
     * mirror. If the resource is already current, no CPU snapshot is retained.
     * If the optional Vulkan upload failed to materialize, the still-valid
     * MeshData view is copied into the bounded deferred cache instead.
     */
    public static synchronized void onImmediateUpload(
            DrawBufferBackendState state,
            DrawGeometryView geometry
    ) {
        if (activePrototype == null
                || state == null
                || geometry == null) {
            return;
        }

        if (activePrototype.readyResource(state) != null) {
            immediateMirrorReadyCount++;
            discardDeferred(state, false);
            return;
        }

        immediateMirrorFallbackCaptureCount++;
        deferUnmirroredUpload(state, geometry);
    }

    /**
     * Capture a generation that the native Vulkan mirror could not admit.
     * The DrawBufferBackendState must already have observed this upload.
     */
    public static synchronized void deferUnmirroredUpload(
            DrawBufferBackendState state,
            DrawGeometryView geometry
    ) {
        if (activePrototype == null
                || state == null
                || geometry == null
                || state.closed()
                || !state.uploaded()
                || state.uploadGeneration() <= 0L
                || geometry.vertexBytes() == null
                || geometry.vertexBytes().remaining() <= 0) {
            return;
        }

        DeferredGeometry replacement =
                DeferredGeometry.copyOf(
                        state.uploadGeneration(),
                        geometry
                );

        if (replacement == null) {
            return;
        }

        DeferredGeometry previous =
                deferred.put(
                        state,
                        replacement
                );

        deferredOrder.addLast(
                new DeferredKey(
                        state,
                        replacement.uploadGeneration
                )
        );

        if (previous == null) {
            captureCount++;
        } else {
            replacementCount++;
            deferredBytes =
                    Math.max(
                            0L,
                            deferredBytes - previous.totalBytes
                    );
            previous.close();
        }

        deferredBytes +=
                replacement.totalBytes;

        enforceDeferredBudget();

        peakDeferredBytes =
                Math.max(
                        peakDeferredBytes,
                        deferredBytes
                );

        peakDeferredEntries =
                Math.max(
                        peakDeferredEntries,
                        deferred.size()
                );
    }

    public static synchronized void onStateClosed(
            DrawBufferBackendState state
    ) {
        if (state == null) {
            return;
        }

        discardDeferred(
                state,
                false
        );
    }

    public static synchronized void beginVisibleSweep() {
        visibleSweepEpoch++;
        visibleSweepCount++;
        promotionsUsedThisSweep = 0;
        promotionBytesUsedThisSweep = 0L;
    }

    /**
     * Mark one state as visible and promote its deferred geometry if needed.
     * The method never waits for the GPU and never exceeds the per-sweep count
     * or byte budget.
     */
    public static synchronized boolean prepareVisible(
            DrawBufferBackendState state
    ) {
        visiblePrepareCount++;

        VulkanGeometryUploadPrototype prototype =
                activePrototype;

        if (prototype == null
                || state == null
                || state.closed()
                || !state.uploaded()) {
            visibleDeferredMissCount++;
            return false;
        }

        prototype.markVisibleResidency(
                state,
                visibleSweepEpoch
        );

        if (prototype.readyResource(state) != null) {
            visibleReadyHitCount++;
            return true;
        }

        DeferredGeometry snapshot =
                deferred.get(state);

        if (snapshot == null) {
            visibleDeferredMissCount++;
            return false;
        }

        if (snapshot.uploadGeneration
                != state.uploadGeneration()) {
            staleDiscardCount++;
            discardDeferred(state, false);
            return false;
        }

        if (promotionsUsedThisSweep
                >= PROMOTIONS_PER_SWEEP) {
            promotionCountBudgetRejectCount++;
            return false;
        }

        long nextPromotionBytes =
                promotionBytesUsedThisSweep
                        + snapshot.totalBytes;

        if (nextPromotionBytes
                > PROMOTION_BYTES_PER_SWEEP) {
            promotionByteBudgetRejectCount++;
            return false;
        }

        promotionAttemptCount++;

        boolean promoted =
                prototype.promoteVisibleResidency(
                        state,
                        snapshot.view(),
                        visibleSweepEpoch
                );

        if (!promoted) {
            promotionFailureCount++;
            return false;
        }

        promotionsUsedThisSweep++;
        promotionBytesUsedThisSweep =
                nextPromotionBytes;

        peakPromotionsPerSweep =
                Math.max(
                        peakPromotionsPerSweep,
                        promotionsUsedThisSweep
                );

        peakPromotionBytesPerSweep =
                Math.max(
                        peakPromotionBytesPerSweep,
                        promotionBytesUsedThisSweep
                );

        promotionSuccessCount++;
        discardDeferred(state, false);
        return true;
    }

    public static synchronized void enrich(
            JsonObject report
    ) {
        report.addProperty(
                "vulkanVisibleResidencyInstalled",
                true
        );
        report.addProperty(
                "vulkanVisibleResidencyMode",
                "VISIBLE_FIRST_BOUNDED_DEFERRED_GENERATION_PROMOTION"
        );
        report.addProperty(
                "vulkanVisibleResidencyActive",
                activePrototype != null
        );
        report.addProperty(
                "vulkanVisibleResidencyDeferredBudgetBytes",
                DEFERRED_BUDGET_BYTES
        );
        report.addProperty(
                "vulkanVisibleResidencyDeferredEntryLimit",
                DEFERRED_ENTRY_LIMIT
        );
        report.addProperty(
                "vulkanVisibleResidencyDeferredEntries",
                deferred.size()
        );
        report.addProperty(
                "vulkanVisibleResidencyDeferredBytes",
                deferredBytes
        );
        report.addProperty(
                "vulkanVisibleResidencyPeakDeferredBytes",
                peakDeferredBytes
        );
        report.addProperty(
                "vulkanVisibleResidencyPeakDeferredEntries",
                peakDeferredEntries
        );
        report.addProperty(
                "vulkanVisibleResidencyCaptureCount",
                captureCount
        );
        report.addProperty(
                "vulkanVisibleResidencyReplacementCount",
                replacementCount
        );
        report.addProperty(
                "vulkanVisibleResidencyEvictionCount",
                evictionCount
        );
        report.addProperty(
                "vulkanVisibleResidencyStaleDiscardCount",
                staleDiscardCount
        );
        report.addProperty(
                "vulkanVisibleResidencyImmediateMirrorReadyCount",
                immediateMirrorReadyCount
        );
        report.addProperty(
                "vulkanVisibleResidencyImmediateMirrorFallbackCaptureCount",
                immediateMirrorFallbackCaptureCount
        );
        report.addProperty(
                "vulkanVisibleResidencySweepCount",
                visibleSweepCount
        );
        report.addProperty(
                "vulkanVisibleResidencyPrepareCount",
                visiblePrepareCount
        );
        report.addProperty(
                "vulkanVisibleResidencyReadyHitCount",
                visibleReadyHitCount
        );
        report.addProperty(
                "vulkanVisibleResidencyDeferredMissCount",
                visibleDeferredMissCount
        );
        report.addProperty(
                "vulkanVisibleResidencyPromotionsPerSweep",
                PROMOTIONS_PER_SWEEP
        );
        report.addProperty(
                "vulkanVisibleResidencyPromotionBytesPerSweep",
                PROMOTION_BYTES_PER_SWEEP
        );
        report.addProperty(
                "vulkanVisibleResidencyPromotionAttemptCount",
                promotionAttemptCount
        );
        report.addProperty(
                "vulkanVisibleResidencyPromotionSuccessCount",
                promotionSuccessCount
        );
        report.addProperty(
                "vulkanVisibleResidencyPromotionFailureCount",
                promotionFailureCount
        );
        report.addProperty(
                "vulkanVisibleResidencyPromotionCountBudgetRejectCount",
                promotionCountBudgetRejectCount
        );
        report.addProperty(
                "vulkanVisibleResidencyPromotionByteBudgetRejectCount",
                promotionByteBudgetRejectCount
        );
        report.addProperty(
                "vulkanVisibleResidencyPeakPromotionsPerSweep",
                peakPromotionsPerSweep
        );
        report.addProperty(
                "vulkanVisibleResidencyPeakPromotionBytesPerSweep",
                peakPromotionBytesPerSweep
        );
        report.addProperty(
                "vulkanVisibleResidencyNoGpuWait",
                true
        );
        report.addProperty(
                "vulkanVisibleResidencyOpenGlFailOpenPreserved",
                true
        );
    }

    private static void enforceDeferredBudget() {
        int guard =
                Math.max(
                        deferredOrder.size() + 1,
                        1
                );

        while ((deferredBytes > DEFERRED_BUDGET_BYTES
                || deferred.size() > DEFERRED_ENTRY_LIMIT)
                && guard-- > 0) {
            DeferredKey oldest =
                    deferredOrder.pollFirst();

            if (oldest == null) {
                break;
            }

            DeferredGeometry snapshot =
                    deferred.get(
                            oldest.state
                    );

            if (snapshot == null
                    || snapshot.uploadGeneration
                    != oldest.uploadGeneration) {
                continue;
            }

            deferred.remove(
                    oldest.state
            );

            deferredBytes =
                    Math.max(
                            0L,
                            deferredBytes - snapshot.totalBytes
                    );

            snapshot.close();
            evictionCount++;
        }
    }

    private static void discardDeferred(
            DrawBufferBackendState state,
            boolean countAsEviction
    ) {
        DeferredGeometry snapshot =
                deferred.remove(state);

        if (snapshot == null) {
            return;
        }

        deferredBytes =
                Math.max(
                        0L,
                        deferredBytes - snapshot.totalBytes
                );

        snapshot.close();

        if (countAsEviction) {
            evictionCount++;
        }
    }

    private static void clearDeferred() {
        for (DeferredGeometry snapshot : deferred.values()) {
            snapshot.close();
        }

        deferred.clear();
        deferredOrder.clear();
        deferredBytes = 0L;
    }

    private static long defaultDeferredBudgetBytes() {
        return PotatoRuntimeSettings
                .visibleDeferredBudgetBytes();
    }

    private static int defaultPromotionsPerSweep() {
        return PotatoRuntimeSettings
                .visiblePromotionsPerSweep();
    }

    private static long defaultPromotionBytesPerSweep() {
        return PotatoRuntimeSettings
                .visiblePromotionBytesPerSweep();
    }


    private static final class DeferredKey {
        private final DrawBufferBackendState state;
        private final long uploadGeneration;

        private DeferredKey(
                DrawBufferBackendState state,
                long uploadGeneration
        ) {
            this.state = state;
            this.uploadGeneration = uploadGeneration;
        }
    }

    private static final class DeferredGeometry
            implements AutoCloseable {
        private final long uploadGeneration;
        private final VertexFormat format;
        private final int vertexCount;
        private final int indexCount;
        private final VertexFormat.Mode mode;
        private final VertexFormat.IndexType indexType;
        private final ByteBuffer vertexBytes;
        private final ByteBuffer indexBytes;
        private final int totalBytes;

        private DeferredGeometry(
                long uploadGeneration,
                VertexFormat format,
                int vertexCount,
                int indexCount,
                VertexFormat.Mode mode,
                VertexFormat.IndexType indexType,
                ByteBuffer vertexBytes,
                ByteBuffer indexBytes,
                int totalBytes
        ) {
            this.uploadGeneration = uploadGeneration;
            this.format = format;
            this.vertexCount = vertexCount;
            this.indexCount = indexCount;
            this.mode = mode;
            this.indexType = indexType;
            this.vertexBytes = vertexBytes;
            this.indexBytes = indexBytes;
            this.totalBytes = totalBytes;
        }

        private static DeferredGeometry copyOf(
                long uploadGeneration,
                DrawGeometryView geometry
        ) {
            ByteBuffer vertexCopy =
                    copyDirect(
                            geometry.vertexBytes()
                    );

            if (vertexCopy == null) {
                return null;
            }

            ByteBuffer indexCopy =
                    copyDirect(
                            geometry.indexBytes()
                    );

            int total =
                    vertexCopy.remaining()
                            + (indexCopy == null
                            ? 0
                            : indexCopy.remaining());

            return new DeferredGeometry(
                    uploadGeneration,
                    geometry.format(),
                    geometry.vertexCount(),
                    geometry.indexCount(),
                    geometry.mode(),
                    geometry.indexType(),
                    vertexCopy,
                    indexCopy,
                    total
            );
        }

        private DrawGeometryView view() {
            return new DrawGeometryView(
                    format,
                    vertexCount,
                    indexCount,
                    mode,
                    indexType,
                    vertexBytes
                            .duplicate()
                            .asReadOnlyBuffer(),
                    indexBytes == null
                            ? null
                            : indexBytes
                            .duplicate()
                            .asReadOnlyBuffer()
            );
        }

        @Override
        public void close() {
            MemoryUtil.memFree(vertexBytes);

            if (indexBytes != null) {
                MemoryUtil.memFree(indexBytes);
            }
        }

        private static ByteBuffer copyDirect(
                ByteBuffer source
        ) {
            if (source == null) {
                return null;
            }

            ByteBuffer input =
                    source.duplicate();

            if (!input.hasRemaining()) {
                return null;
            }

            ByteBuffer copy =
                    MemoryUtil.memAlloc(
                            input.remaining()
                    );

            copy.put(input);
            copy.flip();
            return copy;
        }
    }
}
