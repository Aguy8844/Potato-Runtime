package dev.ordovicium.potato.render.lod;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.MeshData;
import dev.ordovicium.potato.PotatoRuntime;
import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;
import dev.ordovicium.potato.render.visibility.ChunkWorkBudgetPolicy;
import dev.ordovicium.potato.render.visibility.PotatoPredictiveStreamingRuntime;
import dev.ordovicium.potato.render.visibility.PotatoViewTurnRelief;
import net.minecraft.client.renderer.RenderType;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Shared adaptive LOD policy + bounded asynchronous proxy build service.
 *
 * <p>This subsystem is renderer-only. It never changes Minecraft's configured
 * render distance, chunk loading, ticking, entities or saving. During the
 * OpenGL transition it substitutes only distant SOLID section geometry with
 * conservative proxy meshes; unsupported/unfinished resources fall back to the
 * original VertexBuffer draw immediately.</p>
 */
public final class PotatoLodRuntime {

    private static final int BLOCK_VERTEX_STRIDE_BYTES = 32;
    private static final int MIN_SOURCE_QUADS_DEFAULT = 160;
    private static final int MAX_CAPTURE_BYTES_DEFAULT =
            4 * 1024 * 1024;

    private static final long NANOS_PER_MILLI =
            1_000_000L;

    /*
     * Patch 141: 140c still measured ~61.5 ms whole-frame EMA in a broad
     * fully-loaded 32-chunk view while the SOLID layer EMA itself ended around
     * 18.5 ms. Level 3 was already saturated, so the adaptive LOD controller
     * had no stronger response available. Level 4 is an emergency renderer-only
     * pressure state; it never changes Minecraft's configured render distance.
     */
    private static final int MAX_ADAPTIVE_PRESSURE_LEVEL =
            4;

    private static final double EMERGENCY_PRESSURE_EMA_NANOS =
            12.0 * NANOS_PER_MILLI;

    private static final long EMERGENCY_PRESSURE_SINGLE_LAYER_NANOS =
            24L * NANOS_PER_MILLI;

    private static final double MIN_USEFUL_REDUCTION_PERCENT =
            6.0;

    private static final long LOD_INSTALL_BUDGET_CLEAR_NANOS =
            700_000L;

    private static final long LOD_INSTALL_BUDGET_MILD_NANOS =
            250_000L;

    private static final long SMALL_INSTALL_BYTES =
            96L * 1024L;

    private static final long LARGE_INSTALL_HEADROOM_EMA_NANOS =
            3L * NANOS_PER_MILLI;

    private static final long RESULT_TIER1_COMPACTION_THRESHOLD_BYTES =
            64L * 1024L;

    /*
     * Patch 082: admission recovery is deliberately bounded. A worker may
     * reclaim only a small number of already-superseded ready results before
     * retrying one useful build; it never performs an unbounded queue sweep.
     */
    private static final int PENDING_SUPERSEDED_RECLAIM_LIMIT =
            96;

    /*
     * Patch 076: 28+ chunk views are not a reason to keep full-detail SOLID
     * geometry almost to the horizon. These values only move the existing
     * lossless/relaxed proxy thresholds inward; render distance is untouched.
     */
    private static final int LONG_VIEW_DISTANCE_START_CHUNKS =
            28;

    private static final float LONG_VIEW_LOD1_FRACTION =
            0.44f;

    private static final float LONG_VIEW_LOD2_FRACTION =
            0.63f;

    private static final float LONG_VIEW_LOD1_PIXEL_FLOOR =
            144.0f;

    private static final float LONG_VIEW_LOD2_PIXEL_FLOOR =
            72.0f;

    private static final Object LIFECYCLE_LOCK =
            new Object();

    private static final AtomicLong QUEUED_INPUT_BYTES =
            new AtomicLong();

    private static final AtomicLong DEFERRED_CAPTURE_BYTES =
            new AtomicLong();

    private static final AtomicLong PENDING_RESULT_BYTES =
            new AtomicLong();

    private static final AtomicLong PROXY_GPU_BYTES =
            new AtomicLong();

    private static final ConcurrentLinkedQueue<PendingInstall> READY_INSTALLS_SMALL =
            new ConcurrentLinkedQueue<>();

    private static final ConcurrentLinkedQueue<PendingInstall> READY_INSTALLS_LARGE =
            new ConcurrentLinkedQueue<>();

    private static final ConcurrentLinkedQueue<DeferredBuild> DEFERRED_BUILDS =
            new ConcurrentLinkedQueue<>();

    /*
     * Latest useful CPU result observed per VertexBuffer bridge. This tracker
     * lets the pending-install queues discard obsolete generations before they
     * consume byte budget or render-thread OpenGL installation time.
     */
    private static final ConcurrentHashMap<PotatoLodProxyBridge, Long>
            LATEST_PENDING_GENERATION =
            new ConcurrentHashMap<>();

    private static final Set<PotatoOpenGlLodProxy> ACTIVE_PROXIES =
            Collections.newSetFromMap(
                    new IdentityHashMap<>()
            );

    private static final LongAdder buildScheduledCount =
            new LongAdder();
    private static final LongAdder buildCompletedCount =
            new LongAdder();
    private static final LongAdder buildNoReductionCount =
            new LongAdder();
    private static final LongAdder buildQueueRejectedCount =
            new LongAdder();
    private static final LongAdder buildQueueByteRejectedCount =
            new LongAdder();
    private static final LongAdder buildResultByteRejectedCount =
            new LongAdder();
    private static final LongAdder buildResultAdmissionRetryCount =
            new LongAdder();
    private static final LongAdder buildResultAdmissionRescuedCount =
            new LongAdder();
    private static final LongAdder buildResultAdmissionForcedTier2Count =
            new LongAdder();
    private static final LongAdder buildResultAdmissionForcedTier2Bytes =
            new LongAdder();
    private static final LongAdder buildResultAdmissionReclaimedSupersededCount =
            new LongAdder();
    private static final LongAdder buildResultAdmissionReclaimedSupersededBytes =
            new LongAdder();
    private static final LongAdder buildSupersededPendingInstallDropCount =
            new LongAdder();
    private static final LongAdder buildResultTier1CompactedCount =
            new LongAdder();
    private static final LongAdder buildResultTier1CompactedBytes =
            new LongAdder();
    private static final LongAdder buildStaleResultCount =
            new LongAdder();
    private static final LongAdder buildFailureCount =
            new LongAdder();

    private static final LongAdder buildSuppressedForStreamingCount =
            new LongAdder();
    private static final LongAdder buildDeferredForStreamingCount =
            new LongAdder();
    private static final LongAdder buildDeferredDrainedCount =
            new LongAdder();
    private static final LongAdder buildDeferredDroppedCount =
            new LongAdder();
    private static final LongAdder buildDeferredByteRejectedCount =
            new LongAdder();
    private static final LongAdder buildSuppressedForLowValueCount =
            new LongAdder();
    private static final LongAdder installYieldedForStreamingCount =
            new LongAdder();
    private static final LongAdder installDeferredForTurnReliefCount =
            new LongAdder();
    private static final LongAdder installDrainedCount =
            new LongAdder();
    private static final LongAdder installBatchCount =
            new LongAdder();
    private static final LongAdder installBatchNanosTotal =
            new LongAdder();
    private static final LongAdder installSmallCount =
            new LongAdder();
    private static final LongAdder installLargeCount =
            new LongAdder();
    private static final LongAdder installLargeDeferredForFrameTimeCount =
            new LongAdder();
    private static final LongAdder proxySingleTierCompactionCount =
            new LongAdder();
    private static final LongAdder proxyTier2FallbackForTier1Count =
            new LongAdder();
    private static final LongAdder proxyLiveTierCompactionCount =
            new LongAdder();
    private static final LongAdder proxyLiveTierCompactionBytes =
            new LongAdder();

    private static final LongAdder sourceQuadCount =
            new LongAdder();
    private static final LongAdder mergeableQuadCount =
            new LongAdder();
    private static final LongAdder tier1BuiltCount =
            new LongAdder();
    private static final LongAdder tier2BuiltCount =
            new LongAdder();
    private static final LongAdder tier1BuiltAvoidedQuadCount =
            new LongAdder();
    private static final LongAdder tier2BuiltAvoidedQuadCount =
            new LongAdder();
    private static final LongAdder buildNanosTotal =
            new LongAdder();

    private static final LongAdder proxyInstalledCount =
            new LongAdder();
    private static final LongAdder proxyInstallFailureCount =
            new LongAdder();
    private static final LongAdder proxyBudgetRejectedCount =
            new LongAdder();
    private static final LongAdder integratedIntelOpenGlProxySuppressedCount =
            new LongAdder();
    private static final LongAdder tier1InstalledCount =
            new LongAdder();
    private static final LongAdder tier2InstalledCount =
            new LongAdder();
    private static final LongAdder tier1ClosedCount =
            new LongAdder();
    private static final LongAdder tier2ClosedCount =
            new LongAdder();

    /*
     * These counters are mutated only by the Minecraft render thread. Using
     * LongAdder here paid striped-atomic overhead millions of times in large
     * loaded views without providing any concurrency benefit. Worker/build
     * counters above remain LongAdder because those are genuinely concurrent.
     */
    private static long detailSelectionCount;
    private static long desiredFullCount;
    private static long desiredTier1Count;
    private static long desiredTier2Count;
    private static long proxyFallbackToBaselineCount;

    private static long visibleProxyDrawCount;
    private static long visibleTier1DrawCount;
    private static long visibleTier2DrawCount;
    private static long visibleBaselineDrawSuppressedCount;
    private static long visibleSourceQuadCount;
    private static long visibleProxyQuadCount;
    private static long visibleAvoidedQuadCount;

    /*
     * Exact per-layer wall-clock telemetry. This is intentionally one counter
     * update per complete render layer, never per section draw. It gives the
     * next runtime test enough evidence to distinguish foliage/cutout cost
     * from SOLID or translucent cost without profiling instrumentation in the
     * hot section loop.
     */
    private static long sectionLayerSolidCount;
    private static long sectionLayerSolidNanosTotal;
    private static long sectionLayerSolidNanosPeak;
    private static double sectionLayerSolidEmaNanos;
    private static long sectionLayerCutoutCount;
    private static long sectionLayerCutoutNanosTotal;
    private static long sectionLayerCutoutNanosPeak;
    private static double sectionLayerCutoutEmaNanos;
    private static long sectionLayerCutoutMippedCount;
    private static long sectionLayerCutoutMippedNanosTotal;
    private static long sectionLayerCutoutMippedNanosPeak;
    private static double sectionLayerCutoutMippedEmaNanos;
    private static long sectionLayerTranslucentCount;
    private static long sectionLayerTranslucentNanosTotal;
    private static long sectionLayerTranslucentNanosPeak;
    private static double sectionLayerTranslucentEmaNanos;
    private static long sectionLayerOtherCount;
    private static long sectionLayerOtherNanosTotal;
    private static long sectionLayerOtherNanosPeak;
    private static double sectionLayerOtherEmaNanos;

    private static volatile JsonObject report;
    private static volatile ThreadPoolExecutor executor;

    private static volatile PotatoLodProfile profile =
            PotatoLodProfile.ADAPTIVE;

    private static volatile boolean closed =
            true;

    private static int workerThreads;
    private static int minimumSourceQuads;
    private static int maximumCaptureBytes;
    private static long maximumQueuedInputBytes;
    private static long maximumDeferredCaptureBytes;
    private static long maximumPendingResultBytes;
    private static long proxyBudgetBytes;

    /*
     * Patch 156: the OpenGL transition must size Potato-owned GL proxy
     * buffers for the GPU that actually owns the visible OpenGL context.
     * A Vulkan-selected discrete GPU is not evidence that the active OpenGL
     * driver has equivalent allocation headroom.
     */
    private static String activeOpenGlRendererDescription =
            "";
    private static int uncappedDefaultProxyBudgetMiB;
    private static int activeOpenGlDefaultProxyBudgetCapMiB;
    private static boolean activeOpenGlDefaultProxyBudgetCapApplied;
    private static boolean explicitProxyBudgetOverride;

    /*
     * Patch 157: Patch 156 proved that a 96 MiB cap alone does not stop the
     * Intel UHD OpenGL allocation failures on the affected machine. Keep the
     * original Minecraft VertexBuffer path authoritative and temporarily
     * suppress Potato's optional GL proxy allocation/build path on Intel
     * UHD/HD while the root GL allocator/driver path is isolated.
     */
    private static boolean integratedIntelOpenGlProxyIsolationActive;

    /*
     * Render-thread-only current layer state.
     */
    private static boolean currentLayerSolid;
    private static boolean currentLayerValid;
    private static int currentRenderDistanceChunks;
    private static int currentViewportHeight;
    private static float currentProjectionScaleY;

    private static int currentLod1StartChunks;
    private static int currentLod2StartChunks;
    private static float currentLod1PixelThreshold;
    private static float currentLod2PixelThreshold;

    /*
     * Precomputed squared enter/hysteresis distances. The old selector used a
     * sqrt plus projected-pixel division for every visible SOLID section. Both
     * threshold predicates reduce exactly to distance >= max(distance floor,
     * projection-derived floor), so the hot path can compare squared values.
     */
    private static double currentLod1EnterDistanceSquared;
    private static double currentLod2EnterDistanceSquared;
    private static double currentLod1HysteresisDistanceSquared;
    private static double currentLod2HysteresisDistanceSquared;

    private static boolean longViewDistancePolicyActive;

    private static long solidLayerSampleCount;
    private static long solidLayerElapsedNanosTotal;
    private static long solidLayerElapsedNanosPeak;
    private static double solidLayerEmaNanos;

    private static int adaptivePressureLevel;
    private static int pressureUpshiftCount;
    private static int pressureDownshiftCount;
    private static int pressureUpVotes;
    private static int pressureDownVotes;

    private static int activeProxyCount;
    private static int peakActiveProxyCount;
    private static long peakProxyGpuBytes;
    private static int readyInstallPeakCount;
    private static int readyInstallSmallPeakCount;
    private static int readyInstallLargePeakCount;
    private static int deferredBuildPeakCount;
    private static long installBatchNanosPeak;

    private static long turnReliefLayerSampleCount;
    private static long turnReliefLayerElapsedNanosTotal;
    private static long turnReliefLayerElapsedNanosPeak;

    private static String lastBuildFailure =
            "";
    private static String lastProxyInstallFailure =
            "";

    private PotatoLodRuntime() {
    }

    public static void bindReport(
            JsonObject newReport
    ) {
        synchronized (LIFECYCLE_LOCK) {
            if (newReport == null) {
                throw new IllegalArgumentException(
                        "LOD report cannot be null."
                );
            }

            if (!closed
                    && report == newReport
                    && executor != null) {
                return;
            }

            report =
                    newReport;

            profile =
                    PotatoLodProfile.fromProperty(
                            System.getProperty(
                                    "potato.lod.profile",
                                    "ADAPTIVE"
                            )
                    );

            int processors =
                    Math.max(
                            1,
                            Runtime.getRuntime()
                                    .availableProcessors()
                    );

            workerThreads =
                    boundedIntegerProperty(
                            "potato.lod.workerThreads",
                            Math.max(
                                    1,
                                    Math.min(
                                            4,
                                            processors / 6
                                    )
                            ),
                            1,
                            6
                    );

            minimumSourceQuads =
                    boundedIntegerProperty(
                            "potato.lod.minimumSourceQuads",
                            MIN_SOURCE_QUADS_DEFAULT,
                            32,
                            4096
                    );

            maximumCaptureBytes =
                    boundedIntegerProperty(
                            "potato.lod.maximumCaptureBytes",
                            MAX_CAPTURE_BYTES_DEFAULT,
                            256 * 1024,
                            16 * 1024 * 1024
                    );

            long jvmMaxMiB =
                    Math.max(
                            1L,
                            Runtime.getRuntime()
                                    .maxMemory()
                                    / (
                                    1024L
                                            * 1024L
                            )
                    );

            uncappedDefaultProxyBudgetMiB =
                    (int) Math.max(
                            64L,
                            Math.min(
                                    384L,
                                    jvmMaxMiB / 24L
                            )
                    );

            activeOpenGlRendererDescription =
                    readActiveOpenGlRendererDescription(
                            newReport
                    );

            integratedIntelOpenGlProxyIsolationActive =
                    isIntelUhdOrHdOpenGlRenderer(
                            activeOpenGlRendererDescription
                    )
                            && !Boolean.getBoolean(
                            "potato.lod.allowIntelIntegratedOpenGlProxy"
                    );

            activeOpenGlDefaultProxyBudgetCapMiB =
                    integratedOpenGlProxyBudgetCapMiB(
                            activeOpenGlRendererDescription
                    );

            int defaultProxyBudgetMiB =
                    activeOpenGlDefaultProxyBudgetCapMiB > 0
                            ? Math.min(
                            uncappedDefaultProxyBudgetMiB,
                            activeOpenGlDefaultProxyBudgetCapMiB
                    )
                            : uncappedDefaultProxyBudgetMiB;

            activeOpenGlDefaultProxyBudgetCapApplied =
                    defaultProxyBudgetMiB
                            < uncappedDefaultProxyBudgetMiB;

            explicitProxyBudgetOverride =
                    System.getProperty(
                            "potato.lod.proxyBudgetMiB"
                    ) != null;

            int proxyBudgetMiB =
                    boundedIntegerProperty(
                            "potato.lod.proxyBudgetMiB",
                            defaultProxyBudgetMiB,
                            16,
                            512
                    );

            int defaultQueueMiB =
                    Math.max(
                            8,
                            Math.min(
                                    32,
                                    proxyBudgetMiB / 4
                            )
                    );

            int queueMiB =
                    boundedIntegerProperty(
                            "potato.lod.buildQueueMiB",
                            defaultQueueMiB,
                            4,
                            64
                    );

            int defaultDeferredQueueMiB =
                    (int) Math.max(
                            32L,
                            Math.min(
                                    128L,
                                    jvmMaxMiB / 80L
                            )
                    );

            int deferredQueueMiB =
                    boundedIntegerProperty(
                            "potato.lod.deferredQueueMiB",
                            defaultDeferredQueueMiB,
                            16,
                            192
                    );

            proxyBudgetBytes =
                    proxyBudgetMiB
                            * 1024L
                            * 1024L;

            maximumQueuedInputBytes =
                    queueMiB
                            * 1024L
                            * 1024L;

            maximumDeferredCaptureBytes =
                    deferredQueueMiB
                            * 1024L
                            * 1024L;

            maximumPendingResultBytes =
                    Math.max(
                            maximumQueuedInputBytes,
                            Math.min(
                                    192L * 1024L * 1024L,
                                    proxyBudgetBytes * 2L / 3L
                            )
                    );

            ThreadFactory factory =
                    runnable -> {
                        Thread thread =
                                new Thread(
                                        runnable,
                                        "Potato-LOD-Builder"
                                );

                        thread.setDaemon(
                                true
                        );

                        thread.setPriority(
                                Math.max(
                                        Thread.MIN_PRIORITY,
                                        Thread.NORM_PRIORITY - 1
                                )
                        );

                        return thread;
                    };

            ThreadPoolExecutor newExecutor =
                    new ThreadPoolExecutor(
                            workerThreads,
                            workerThreads,
                            30L,
                            TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(
                                    64
                            ),
                            factory,
                            new ThreadPoolExecutor.AbortPolicy()
                    );

            newExecutor.allowCoreThreadTimeOut(
                    true
            );

            executor =
                    newExecutor;

            closed =
                    false;

            newReport.addProperty(
                    "potatoLodRuntimeInstalled",
                    true
            );
            newReport.addProperty(
                    "potatoLodInitialProfile",
                    profile.name()
            );
            newReport.addProperty(
                    "potatoLodVisibleSubstitutionLayer",
                    "SOLID_ONLY"
            );
            newReport.addProperty(
                    "potatoLodAsyncCpuBuildEnabled",
                    true
            );
            newReport.addProperty(
                    "potatoLodOpenGlProxyTransitionBackend",
                    true
            );
            newReport.addProperty(
                    "potatoLodIntegratedIntelOpenGlProxyIsolationActive",
                    integratedIntelOpenGlProxyIsolationActive
            );
            newReport.addProperty(
                    "potatoLodIntegratedIntelOpenGlProxyIsolationReason",
                    integratedIntelOpenGlProxyIsolationActive
                            ? "REPEATED_GL_OUT_OF_MEMORY_AFTER_96_MIB_CAP"
                            : "NOT_REQUIRED_OR_EXPLICITLY_OVERRIDDEN"
            );
        }
    }

    public static PotatoLodProfile profile() {
        return profile;
    }

    /**
     * Runtime setter intentionally exists before the settings UI. The later
     * Potato video screen can call this without rewriting renderer policy.
     */
    public static void setProfile(
            PotatoLodProfile newProfile
    ) {
        if (newProfile != null) {
            profile =
                    newProfile;
        }
    }

    public static boolean enabled() {
        return !closed
                && profile
                != PotatoLodProfile.OFF;
    }

    public static void scheduleBuild(
            PotatoLodProxyBridge target,
            long generation,
            MeshData meshData
    ) {
        if (integratedIntelOpenGlProxyIsolationActive) {
            integratedIntelOpenGlProxySuppressedCount.increment();
            return;
        }

        if (!enabled()
                || target == null
                || meshData == null
                || meshData.drawState() == null
                || meshData.vertexBuffer() == null) {
            return;
        }

        int vertexCount = meshData.drawState().vertexCount();

        if (vertexCount <= 0 || (vertexCount & 3) != 0) {
            return;
        }

        int quadCount = vertexCount / 4;

        if (quadCount < minimumSourceQuads) {
            return;
        }

        int requiredBytes = vertexCount * BLOCK_VERTEX_STRIDE_BYTES;

        if (requiredBytes <= 0 || requiredBytes > maximumCaptureBytes) {
            return;
        }

        ByteBuffer source = meshData.vertexBuffer().duplicate();

        if (source.remaining() < requiredBytes) {
            return;
        }

        boolean yieldForStreaming =
                ChunkWorkBudgetPolicy.shouldYieldLodBuild(quadCount);

        AtomicLong reservationCounter =
                yieldForStreaming
                        ? DEFERRED_CAPTURE_BYTES
                        : QUEUED_INPUT_BYTES;

        long reservationLimit =
                yieldForStreaming
                        ? maximumDeferredCaptureBytes
                        : maximumQueuedInputBytes;

        if (!tryReserveAtomic(
                reservationCounter,
                requiredBytes,
                reservationLimit
        )) {
            if (yieldForStreaming) {
                buildSuppressedForStreamingCount.increment();
                buildDeferredByteRejectedCount.increment();
                buildDeferredDroppedCount.increment();
            } else {
                buildQueueByteRejectedCount.increment();
            }

            return;
        }

        byte[] sourceBytes = new byte[requiredBytes];
        source.get(sourceBytes);

        if (yieldForStreaming) {
            buildSuppressedForStreamingCount.increment();
            buildDeferredForStreamingCount.increment();

            DEFERRED_BUILDS.offer(
                    new DeferredBuild(
                            target,
                            generation,
                            sourceBytes,
                            vertexCount,
                            requiredBytes
                    )
            );

            deferredBuildPeakCount = Math.max(
                    deferredBuildPeakCount,
                    DEFERRED_BUILDS.size()
            );

            return;
        }

        submitCapturedBuild(
                target,
                generation,
                sourceBytes,
                vertexCount,
                requiredBytes,
                QUEUED_INPUT_BYTES
        );
    }

    private static void submitCapturedBuild(
            PotatoLodProxyBridge target,
            long generation,
            byte[] sourceBytes,
            int vertexCount,
            int requiredBytes,
            AtomicLong reservationCounter
    ) {
        ThreadPoolExecutor currentExecutor = executor;

        if (currentExecutor == null || currentExecutor.isShutdown()) {
            reservationCounter.addAndGet(-requiredBytes);
            return;
        }

        buildScheduledCount.increment();

        try {
            currentExecutor.execute(
                    () -> buildOnWorker(
                            target,
                            generation,
                            sourceBytes,
                            vertexCount,
                            requiredBytes,
                            reservationCounter
                    )
            );
        } catch (RejectedExecutionException rejected) {
            reservationCounter.addAndGet(-requiredBytes);
            buildQueueRejectedCount.increment();
        }
    }

    private static void drainDeferredBuilds() {
        if (closed || ChunkWorkBudgetPolicy.streamingPressureLevel() > 0) {
            return;
        }

        ThreadPoolExecutor currentExecutor = executor;

        if (currentExecutor == null || currentExecutor.isShutdown()) {
            return;
        }

        int queueSoftLimit = Math.max(
                8,
                workerThreads * 6
        );

        int maximumSubmissions = Math.max(
                4,
                Math.min(
                        16,
                        workerThreads * 4
                )
        );

        int submitted = 0;

        while (submitted < maximumSubmissions
                && currentExecutor.getQueue().size() < queueSoftLimit) {
            DeferredBuild deferred = DEFERRED_BUILDS.poll();

            if (deferred == null) {
                break;
            }

            buildDeferredDrainedCount.increment();

            submitCapturedBuild(
                    deferred.target(),
                    deferred.generation(),
                    deferred.sourceBytes(),
                    deferred.vertexCount(),
                    deferred.inputBytes(),
                    DEFERRED_CAPTURE_BYTES
            );

            submitted++;
        }
    }

    private static void buildOnWorker(
            PotatoLodProxyBridge target,
            long generation,
            byte[] sourceBytes,
            int vertexCount,
            int inputBytes,
            AtomicLong reservationCounter
    ) {
        try {
            PotatoLodBuildResult result =
                    PotatoLodMeshBuilder.build(
                            sourceBytes,
                            vertexCount
                    );

            buildCompletedCount.increment();

            if (result == null
                    || !result.usable()) {
                buildNoReductionCount.increment();
                return;
            }

            sourceQuadCount.add(
                    result.sourceQuadCount()
            );

            mergeableQuadCount.add(
                    result.mergeableQuadCount()
            );

            if (result.tier1() != null) {
                tier1BuiltCount.increment();
                tier1BuiltAvoidedQuadCount.add(
                        result.tier1().avoidedQuads()
                );
            }

            if (result.tier2() != null) {
                tier2BuiltCount.increment();
                tier2BuiltAvoidedQuadCount.add(
                        result.tier2().avoidedQuads()
                );
            }

            buildNanosTotal.add(
                    result.buildNanos()
            );

            double bestReduction =
                    Math.max(
                            result.tier1() == null
                                    ? 0.0
                                    : result.tier1().quadReductionPercent(),
                            result.tier2() == null
                                    ? 0.0
                                    : result.tier2().quadReductionPercent()
                    );

            if (bestReduction < MIN_USEFUL_REDUCTION_PERCENT) {
                buildSuppressedForLowValueCount.increment();
                return;
            }

            PotatoLodBuildResult queuedResult =
                    compactBuildResultForTransitionPressure(
                            result
                    );

            long resultBytes =
                    resultBytes(
                            queuedResult
                    );

            Long latestGeneration =
                    LATEST_PENDING_GENERATION.merge(
                            target,
                            generation,
                            (current, candidate) ->
                                    Math.max(
                                            current,
                                            candidate
                                    )
                    );

            if (latestGeneration != null
                    && generation < latestGeneration) {
                /*
                 * A newer generation for the same VertexBuffer already
                 * finished useful CPU work. Installing this older result
                 * would only become a stale render-thread operation.
                 */
                buildStaleResultCount.increment();
                return;
            }

            int reclaimed =
                    reclaimOlderPendingForTarget(
                            target,
                            generation,
                            PENDING_SUPERSEDED_RECLAIM_LIMIT
                    );

            boolean retried =
                    false;

            boolean admitted =
                    tryReserveAtomic(
                            PENDING_RESULT_BYTES,
                            resultBytes,
                            maximumPendingResultBytes
                    );

            if (!admitted) {
                retried =
                        true;

                buildResultAdmissionRetryCount.increment();

                reclaimed +=
                        reclaimSupersededPendingResults(
                                PENDING_SUPERSEDED_RECLAIM_LIMIT
                                        - reclaimed
                        );

                admitted =
                        tryReserveAtomic(
                                PENDING_RESULT_BYTES,
                                resultBytes,
                                maximumPendingResultBytes
                        );
            }

            if (!admitted) {
                PotatoLodBuildResult farOnly =
                        forceFarTierForAdmission(
                                queuedResult
                        );

                if (farOnly != queuedResult) {
                    long farOnlyBytes =
                            resultBytes(
                                    farOnly
                            );

                    retried =
                            true;

                    buildResultAdmissionRetryCount.increment();
                    buildResultAdmissionForcedTier2Count.increment();
                    buildResultAdmissionForcedTier2Bytes.add(
                            Math.max(
                                    0L,
                                    resultBytes - farOnlyBytes
                            )
                    );

                    if (tryReserveAtomic(
                            PENDING_RESULT_BYTES,
                            farOnlyBytes,
                            maximumPendingResultBytes
                    )) {
                        queuedResult =
                                farOnly;

                        resultBytes =
                                farOnlyBytes;

                        admitted =
                                true;
                    }
                }
            }

            if (!admitted) {
                LATEST_PENDING_GENERATION.remove(
                        target,
                        generation
                );

                buildResultByteRejectedCount.increment();
                return;
            }

            Long latestAfterAdmission =
                    LATEST_PENDING_GENERATION.get(
                            target
                    );

            if (latestAfterAdmission != null
                    && generation < latestAfterAdmission) {
                PENDING_RESULT_BYTES.addAndGet(
                        -resultBytes
                );

                buildStaleResultCount.increment();
                return;
            }

            if (retried) {
                buildResultAdmissionRescuedCount.increment();
            }

            PendingInstall pendingInstall =
                    new PendingInstall(
                            target,
                            generation,
                            queuedResult,
                            resultBytes,
                            preferredInstallBytes(
                                    queuedResult
                            )
                    );

            if (pendingInstall.preferredInstallBytes() <= SMALL_INSTALL_BYTES) {
                READY_INSTALLS_SMALL.offer(pendingInstall);
                readyInstallSmallPeakCount = Math.max(
                        readyInstallSmallPeakCount,
                        READY_INSTALLS_SMALL.size()
                );
            } else {
                READY_INSTALLS_LARGE.offer(pendingInstall);
                readyInstallLargePeakCount = Math.max(
                        readyInstallLargePeakCount,
                        READY_INSTALLS_LARGE.size()
                );
            }

            readyInstallPeakCount = Math.max(
                    readyInstallPeakCount,
                    READY_INSTALLS_SMALL.size() + READY_INSTALLS_LARGE.size()
            );
        } catch (Throwable throwable) {
            buildFailureCount.increment();

            lastBuildFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(throwable.getMessage());

            PotatoRuntime.LOGGER.warn(
                    "[Potato/LOD] Async proxy build failed; vanilla geometry remains authoritative.",
                    throwable
            );
        } finally {
            reservationCounter.addAndGet(
                    -inputBytes
            );
        }
    }

    public static void beginLayer(
            SectionLayerFrameContext context
    ) {
        if (!closed
                && context != null
                && context.solidLayer()) {
            drainDeferredBuilds();
            drainReadyInstalls();
        }

        if (closed
                || context == null) {
            currentLayerSolid =
                    false;
            currentLayerValid =
                    false;

            return;
        }

        currentLayerSolid =
                context.solidLayer()
                        && context.shaderUsesBlockVertexFormat();

        currentRenderDistanceChunks =
                Math.max(
                        2,
                        context.effectiveRenderDistanceChunks()
                );

        currentViewportHeight =
                Math.max(
                        1,
                        context.viewportHeight()
                );

        currentProjectionScaleY =
                context.projection() == null
                        ? 0.0f
                        : Math.abs(
                                context.projection()
                                        .m11()
                        );

        currentLayerValid =
                currentLayerSolid
                        && Float.isFinite(
                        currentProjectionScaleY
                )
                        && currentProjectionScaleY
                        > 0.0001f;

        recomputeThresholds();
    }

    public static int selectDetailTierFast(
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ,
            int previousTier
    ) {
        if (!enabled()
                || !currentLayerValid) {
            desiredFullCount++;
            return 0;
        }

        double centerX =
                chunkOffsetX
                        + 8.0;

        double centerY =
                chunkOffsetY
                        + 8.0;

        double centerZ =
                chunkOffsetZ
                        + 8.0;

        double distanceSquared =
                centerX * centerX
                        + centerY * centerY
                        + centerZ * centerZ;

        int desiredTier =
                0;

        if (distanceSquared
                >= currentLod2EnterDistanceSquared) {
            desiredTier =
                    2;
        } else if (distanceSquared
                >= currentLod1EnterDistanceSquared) {
            desiredTier =
                    1;
        }

        /*
         * Per-buffer tier hysteresis. Quality returns more slowly than it
         * degrades, eliminating threshold flicker while walking or changing
         * adaptive pressure by one step. The squared thresholds are exactly
         * equivalent to the previous distance/projected-pixel conjunctions.
         */
        if (previousTier >= 2
                && desiredTier < 2
                && distanceSquared
                >= currentLod2HysteresisDistanceSquared) {
            desiredTier =
                    2;
        } else if (
                previousTier >= 1
                        && desiredTier == 0
                        && distanceSquared
                        >= currentLod1HysteresisDistanceSquared
        ) {
            desiredTier =
                    1;
        }

        detailSelectionCount++;

        if (desiredTier >= 2) {
            desiredTier2Count++;
        } else if (desiredTier == 1) {
            desiredTier1Count++;
        } else {
            desiredFullCount++;
        }

        return desiredTier;
    }

    public static void onBaselineFallback(
            int desiredTier
    ) {
        if (desiredTier > 0) {
            proxyFallbackToBaselineCount++;
        }
    }

    /**
     * Records one completed section render-layer duration. This method is
     * render-thread only and deliberately performs no allocation.
     */
    public static void observeSectionLayerTiming(
            RenderType renderType,
            long elapsedNanos
    ) {
        long safeElapsed =
                Math.max(
                        0L,
                        elapsedNanos
                );

        if (renderType == RenderType.solid()) {
            sectionLayerSolidCount++;
            sectionLayerSolidNanosTotal += safeElapsed;
            sectionLayerSolidNanosPeak =
                    Math.max(
                            sectionLayerSolidNanosPeak,
                            safeElapsed
                    );
            sectionLayerSolidEmaNanos =
                    updateLayerEma(
                            sectionLayerSolidEmaNanos,
                            safeElapsed
                    );
        } else if (renderType == RenderType.cutout()) {
            sectionLayerCutoutCount++;
            sectionLayerCutoutNanosTotal += safeElapsed;
            sectionLayerCutoutNanosPeak =
                    Math.max(
                            sectionLayerCutoutNanosPeak,
                            safeElapsed
                    );
            sectionLayerCutoutEmaNanos =
                    updateLayerEma(
                            sectionLayerCutoutEmaNanos,
                            safeElapsed
                    );
        } else if (renderType == RenderType.cutoutMipped()) {
            sectionLayerCutoutMippedCount++;
            sectionLayerCutoutMippedNanosTotal += safeElapsed;
            sectionLayerCutoutMippedNanosPeak =
                    Math.max(
                            sectionLayerCutoutMippedNanosPeak,
                            safeElapsed
                    );
            sectionLayerCutoutMippedEmaNanos =
                    updateLayerEma(
                            sectionLayerCutoutMippedEmaNanos,
                            safeElapsed
                    );
        } else if (renderType == RenderType.translucent()) {
            sectionLayerTranslucentCount++;
            sectionLayerTranslucentNanosTotal += safeElapsed;
            sectionLayerTranslucentNanosPeak =
                    Math.max(
                            sectionLayerTranslucentNanosPeak,
                            safeElapsed
                    );
            sectionLayerTranslucentEmaNanos =
                    updateLayerEma(
                            sectionLayerTranslucentEmaNanos,
                            safeElapsed
                    );
        } else {
            sectionLayerOtherCount++;
            sectionLayerOtherNanosTotal += safeElapsed;
            sectionLayerOtherNanosPeak =
                    Math.max(
                            sectionLayerOtherNanosPeak,
                            safeElapsed
                    );
            sectionLayerOtherEmaNanos =
                    updateLayerEma(
                            sectionLayerOtherEmaNanos,
                            safeElapsed
                    );
        }
    }

    private static double updateLayerEma(
            double previous,
            long elapsedNanos
    ) {
        if (previous <= 0.0) {
            return elapsedNanos;
        }

        return previous
                * 0.90
                + elapsedNanos
                * 0.10;
    }

    public static int adaptivePressureLevelSnapshot() {
        return adaptivePressureLevel;
    }

    public static double solidLayerEmaMillisSnapshot() {
        return solidLayerEmaNanos / NANOS_PER_MILLI;
    }

    public static void endLayer(
            long elapsedNanos
    ) {
        if (closed
                || !currentLayerSolid) {
            currentLayerSolid =
                    false;
            currentLayerValid =
                    false;

            return;
        }

        long safeElapsed =
                Math.max(
                        0L,
                        elapsedNanos
                );

        if (PotatoViewTurnRelief.active()) {
            turnReliefLayerSampleCount++;
            turnReliefLayerElapsedNanosTotal +=
                    safeElapsed;
            turnReliefLayerElapsedNanosPeak =
                    Math.max(
                            turnReliefLayerElapsedNanosPeak,
                            safeElapsed
                    );
        }

        solidLayerSampleCount++;
        solidLayerElapsedNanosTotal +=
                safeElapsed;

        solidLayerElapsedNanosPeak =
                Math.max(
                        solidLayerElapsedNanosPeak,
                        safeElapsed
                );

        if (solidLayerEmaNanos <= 0.0) {
            solidLayerEmaNanos =
                    safeElapsed;
        } else {
            solidLayerEmaNanos =
                    solidLayerEmaNanos
                            * 0.90
                            + safeElapsed
                            * 0.10;
        }

        if (profile
                == PotatoLodProfile.ADAPTIVE) {

            updateAdaptivePressure(
                    safeElapsed
            );
        }

        currentLayerSolid =
                false;

        currentLayerValid =
                false;
    }

    private static void updateAdaptivePressure(
            long latestElapsedNanos
    ) {
        int desiredPressure =
                adaptivePressureLevel;

        if (solidLayerEmaNanos
                >= EMERGENCY_PRESSURE_EMA_NANOS) {
            desiredPressure =
                    MAX_ADAPTIVE_PRESSURE_LEVEL;
        } else if (solidLayerEmaNanos
                >= 8.0
                * NANOS_PER_MILLI) {
            desiredPressure = 3;
        } else if (
                solidLayerEmaNanos
                        >= 5.0
                        * NANOS_PER_MILLI
        ) {
            desiredPressure = 2;
        } else if (
                solidLayerEmaNanos
                        >= 3.0
                        * NANOS_PER_MILLI
        ) {
            desiredPressure = 1;
        } else if (
                solidLayerEmaNanos
                        <= 2.0
                        * NANOS_PER_MILLI
        ) {
            desiredPressure = 0;
        }

        if (latestElapsedNanos
                >= EMERGENCY_PRESSURE_SINGLE_LAYER_NANOS) {
            desiredPressure =
                    Math.min(
                            MAX_ADAPTIVE_PRESSURE_LEVEL,
                            Math.max(
                                    desiredPressure,
                                    adaptivePressureLevel + 1
                            )
                    );
        } else if (latestElapsedNanos
                >= 15L
                * NANOS_PER_MILLI) {
            desiredPressure =
                    Math.min(
                            MAX_ADAPTIVE_PRESSURE_LEVEL,
                            Math.max(
                                    desiredPressure,
                                    adaptivePressureLevel + 1
                            )
                    );
        }

        desiredPressure =
                Math.max(
                        desiredPressure,
                        ChunkWorkBudgetPolicy.streamingPressureLevel()
                );

        if (desiredPressure
                > adaptivePressureLevel) {
            pressureUpVotes++;
            pressureDownVotes = 0;

            if (pressureUpVotes >= 4
                    || latestElapsedNanos
                    >= 20L
                    * NANOS_PER_MILLI) {

                adaptivePressureLevel =
                        Math.min(
                                MAX_ADAPTIVE_PRESSURE_LEVEL,
                                adaptivePressureLevel + 1
                        );

                pressureUpshiftCount++;
                pressureUpVotes = 0;
            }
        } else if (
                desiredPressure
                        < adaptivePressureLevel
        ) {
            pressureDownVotes++;
            pressureUpVotes = 0;

            if (pressureDownVotes >= 180) {
                adaptivePressureLevel =
                        Math.max(
                                0,
                                adaptivePressureLevel - 1
                        );

                pressureDownshiftCount++;
                pressureDownVotes = 0;
            }
        } else {
            pressureUpVotes = 0;
            pressureDownVotes = 0;
        }
    }

    private static void recomputeThresholds() {
        int renderDistance =
                Math.max(
                        2,
                        currentRenderDistanceChunks
                );

        int lod1;
        int lod2;
        float pixel1;
        float pixel2;

        switch (profile) {
            case OFF -> {
                lod1 = renderDistance + 1;
                lod2 = renderDistance + 2;
                pixel1 = 0.0f;
                pixel2 = 0.0f;
            }

            case QUALITY -> {
                lod1 =
                        Math.round(
                                renderDistance
                                        * 0.72f
                        );
                lod2 =
                        Math.round(
                                renderDistance
                                        * 0.90f
                        );
                pixel1 = 72.0f;
                pixel2 = 32.0f;
            }

            case BALANCED -> {
                lod1 =
                        Math.round(
                                renderDistance
                                        * 0.55f
                        );
                lod2 =
                        Math.round(
                                renderDistance
                                        * 0.78f
                        );
                pixel1 = 108.0f;
                pixel2 = 50.0f;
            }

            case POTATO -> {
                lod1 =
                        Math.round(
                                renderDistance
                                        * 0.35f
                        );
                lod2 =
                        Math.round(
                                renderDistance
                                        * 0.55f
                        );
                pixel1 = 170.0f;
                pixel2 = 88.0f;
            }

            case ADAPTIVE -> {
                lod1 =
                        Math.round(
                                renderDistance
                                        * 0.60f
                        );
                lod2 =
                        Math.round(
                                renderDistance
                                        * 0.78f
                        );

                int processors =
                        Math.max(
                                1,
                                Runtime.getRuntime()
                                        .availableProcessors()
                        );

                int hardwareQualityBias =
                        processors >= 16
                                ? 1
                                : processors <= 4
                                ? -1
                                : 0;

                lod1 +=
                        hardwareQualityBias
                                - adaptivePressureLevel
                                * 3;

                lod2 +=
                        hardwareQualityBias
                                - adaptivePressureLevel
                                * 3
                                - adaptivePressureLevel
                                * 2
                                / 3;

                pixel1 =
                        112.0f
                                + adaptivePressureLevel
                                * 40.0f;

                pixel2 =
                        56.0f
                                + adaptivePressureLevel
                                * 22.0f;

                if (adaptivePressureLevel >= 3) {
                    lod1 -= 2;
                    lod2 -= 2;
                }

                /*
                 * Patch 143: 142 proved that 4/7 LOD plus successful Vulkan
                 * commits still leaves the pathological broad loaded view near
                 * 10-15 FPS. Emergency level 4 is frame-survival mode: pull
                 * existing SOLID proxies to roughly 3/5 chunks at RD32.
                 * Minecraft render distance and simulation stay untouched.
                 */
                if (adaptivePressureLevel >= 4) {
                    lod1 -= 3;
                    lod2 -= 5;
                }
            }

            default -> throw new IllegalStateException(
                    "Unhandled Potato LOD profile: "
                            + profile
            );
        }

        boolean longViewDistance =
                profile == PotatoLodProfile.ADAPTIVE
                        && renderDistance
                        >= LONG_VIEW_DISTANCE_START_CHUNKS;

        if (longViewDistance) {
            int longViewLod1 =
                    Math.max(
                            8,
                            Math.round(
                                    renderDistance
                                            * LONG_VIEW_LOD1_FRACTION
                            )
                    );

            int longViewLod2 =
                    Math.max(
                            longViewLod1 + 3,
                            Math.round(
                                    renderDistance
                                            * LONG_VIEW_LOD2_FRACTION
                            )
                    );

            lod1 =
                    Math.min(
                            lod1,
                            longViewLod1
                    );

            lod2 =
                    Math.min(
                            lod2,
                            longViewLod2
                    );

            pixel1 =
                    Math.max(
                            pixel1,
                            LONG_VIEW_LOD1_PIXEL_FLOOR
                    );

            pixel2 =
                    Math.max(
                            pixel2,
                            LONG_VIEW_LOD2_PIXEL_FLOOR
                    );
        }

        longViewDistancePolicyActive =
                longViewDistance;

        /*
         * Geometry-clipmap-inspired motion stability: during FAST/EXTREME
         * travel, ADAPTIVE moves its far-field proxy bands inward by only one
         * or two chunks. That gives async terrain work more time without
         * changing Minecraft's configured render distance. Explicit user
         * threshold overrides below remain authoritative.
         */
        int motionInsetChunks =
                profile == PotatoLodProfile.ADAPTIVE
                        ? PotatoPredictiveStreamingRuntime
                        .lodMotionInsetChunks()
                        : 0;

        lod1 -= motionInsetChunks;
        lod2 -= motionInsetChunks;

        int maximumLod1Start =
                Math.max(
                        3,
                        renderDistance - 1
                );

        lod1 =
                clamp(
                        lod1,
                        3,
                        maximumLod1Start
                );

        lod2 =
                clamp(
                        lod2,
                        Math.min(
                                renderDistance,
                                lod1 + 2
                        ),
                        Math.max(
                                renderDistance,
                                lod1 + 2
                        )
                );

        int overrideLod1 =
                Integer.getInteger(
                        "potato.lod.lod1StartChunks",
                        -1
                );

        int overrideLod2 =
                Integer.getInteger(
                        "potato.lod.lod2StartChunks",
                        -1
                );

        int overridePixel1 =
                Integer.getInteger(
                        "potato.lod.lod1PixelThreshold",
                        -1
                );

        int overridePixel2 =
                Integer.getInteger(
                        "potato.lod.lod2PixelThreshold",
                        -1
                );

        if (overrideLod1 > 0) {
            lod1 =
                    clamp(
                            overrideLod1,
                            2,
                            64
                    );
        }

        if (overrideLod2 > 0) {
            lod2 =
                    clamp(
                            overrideLod2,
                            lod1 + 1,
                            64
                    );
        }

        if (overridePixel1 > 0) {
            pixel1 =
                    overridePixel1;
        }

        if (overridePixel2 > 0) {
            pixel2 =
                    overridePixel2;
        }

        currentLod1StartChunks =
                lod1;

        currentLod2StartChunks =
                lod2;

        currentLod1PixelThreshold =
                pixel1;

        currentLod2PixelThreshold =
                pixel2;

        double projectionNumerator =
                8.0
                        * currentProjectionScaleY
                        * currentViewportHeight;

        currentLod1EnterDistanceSquared =
                combinedLodDistanceSquared(
                        currentLod1StartChunks * 16.0,
                        projectionNumerator,
                        currentLod1PixelThreshold
                );

        currentLod2EnterDistanceSquared =
                combinedLodDistanceSquared(
                        currentLod2StartChunks * 16.0,
                        projectionNumerator,
                        currentLod2PixelThreshold
                );

        double recoveryHysteresisScale =
                PotatoPredictiveStreamingRuntime
                        .lodRecoveryHysteresisScale();

        currentLod1HysteresisDistanceSquared =
                combinedLodDistanceSquared(
                        currentLod1StartChunks
                                * 16.0
                                * recoveryHysteresisScale,
                        projectionNumerator,
                        currentLod1PixelThreshold * 1.15
                );

        currentLod2HysteresisDistanceSquared =
                combinedLodDistanceSquared(
                        currentLod2StartChunks
                                * 16.0
                                * recoveryHysteresisScale,
                        projectionNumerator,
                        currentLod2PixelThreshold * 1.20
                );
    }

    private static double combinedLodDistanceSquared(
            double minimumDistanceBlocks,
            double projectionNumerator,
            double maximumProjectedPixels
    ) {
        if (!(maximumProjectedPixels > 0.0)
                || !Double.isFinite(projectionNumerator)) {
            return Double.POSITIVE_INFINITY;
        }

        double projectionDistance =
                projectionNumerator
                        / maximumProjectedPixels;

        double combinedDistance =
                Math.max(
                        Math.max(
                                1.0,
                                minimumDistanceBlocks
                        ),
                        projectionDistance
                );

        return combinedDistance
                * combinedDistance;
    }

    private static void drainReadyInstalls() {
        compactProxyCacheIfNeeded();

        int streamingPressure =
                ChunkWorkBudgetPolicy.streamingPressureLevel();

        boolean turnReliefActive =
                PotatoViewTurnRelief.active();

        double emaNanos = solidLayerEmaNanos;
        boolean allowLarge =
                streamingPressure == 0
                        && emaNanos > 0.0
                        && emaNanos <= LARGE_INSTALL_HEADROOM_EMA_NANOS;

        int maximumInstalls;
        long budgetNanos;

        if (turnReliefActive) {
            /*
             * A sudden camera reversal already exposes a new SOLID workload.
             * Do not add optional OpenGL proxy creation to those reveal
             * frames: one GL install is not preemptible once the driver call
             * starts, and the 080 run observed a 28.49 ms install batch peak.
             * Existing proxies remain usable; queued installs resume as soon
             * as the short turn-relief window ends.
             */
            maximumInstalls = 0;
            budgetNanos = 0L;
        } else if (streamingPressure >= 3) {
            maximumInstalls = 0;
            budgetNanos = 0L;
        } else if (streamingPressure == 2) {
            maximumInstalls = 1;
            budgetNanos = 120_000L;
        } else if (streamingPressure == 1) {
            maximumInstalls = 1;
            budgetNanos = LOD_INSTALL_BUDGET_MILD_NANOS;
        } else if (emaNanos >= 8.0 * NANOS_PER_MILLI) {
            maximumInstalls = 1;
            budgetNanos = 120_000L;
        } else if (emaNanos >= 4.0 * NANOS_PER_MILLI) {
            maximumInstalls = 2;
            budgetNanos = 220_000L;
        } else {
            maximumInstalls = 4;
            budgetNanos = LOD_INSTALL_BUDGET_CLEAR_NANOS;
        }

        if (maximumInstalls == 0) {
            if (!READY_INSTALLS_SMALL.isEmpty()
                    || !READY_INSTALLS_LARGE.isEmpty()) {
                if (turnReliefActive) {
                    installDeferredForTurnReliefCount.increment();
                } else {
                    installYieldedForStreamingCount.increment();
                }
            }

            return;
        }

        long started = System.nanoTime();
        int installed = 0;
        boolean largeInstalled = false;

        while (installed < maximumInstalls) {
            PendingInstall pending = READY_INSTALLS_SMALL.poll();

            if (pending == null && allowLarge && !largeInstalled) {
                pending = READY_INSTALLS_LARGE.poll();

                if (pending != null) {
                    largeInstalled = true;
                }
            }

            if (pending == null) {
                if (!READY_INSTALLS_LARGE.isEmpty() && !allowLarge) {
                    installLargeDeferredForFrameTimeCount.increment();
                }

                break;
            }

            if (isSupersededPending(pending)) {
                PENDING_RESULT_BYTES.addAndGet(
                        -pending.resultBytes()
                );

                buildSupersededPendingInstallDropCount.increment();
                continue;
            }

            try {
                if (closed) {
                    buildStaleResultCount.increment();
                } else {
                    pending.target().potato$installLodBuild(
                            pending.generation(),
                            pending.result()
                    );

                    installDrainedCount.increment();

                    if (pending.preferredInstallBytes() <= SMALL_INSTALL_BYTES) {
                        installSmallCount.increment();
                    } else {
                        installLargeCount.increment();
                    }
                }
            } catch (Throwable throwable) {
                onProxyInstallFailure(throwable);
            } finally {
                PENDING_RESULT_BYTES.addAndGet(-pending.resultBytes());

                LATEST_PENDING_GENERATION.remove(
                        pending.target(),
                        pending.generation()
                );
            }

            installed++;

            if (System.nanoTime() - started >= budgetNanos) {
                break;
            }

            if (ChunkWorkBudgetPolicy.shouldYieldLodInstall()) {
                installYieldedForStreamingCount.increment();
                break;
            }
        }

        if (installed > 0) {
            long elapsed = Math.max(0L, System.nanoTime() - started);
            installBatchCount.increment();
            installBatchNanosTotal.add(elapsed);
            installBatchNanosPeak = Math.max(installBatchNanosPeak, elapsed);
        }
    }

    private static PotatoLodBuildResult compactBuildResultForTransitionPressure(
            PotatoLodBuildResult result
    ) {
        if (result == null
                || result.tier1() == null
                || result.tier2() == null) {
            return result;
        }

        long tier1Bytes =
                result.tier1()
                        .gpuBytes();

        /*
         * Patch 145 quality band:
         * Tier-1 preserves exact per-quad visual keys and is the quality bridge
         * between full geometry and aggressive far-field Tier-2 merging.
         *
         * Do not delete it merely because the frame is under ordinary renderer
         * pressure. The 144 runtime installed zero Tier-1 proxies and therefore
         * routed every mid-distance request through stretched Tier-2 fallback.
         *
         * Compaction is now reserved for an actual memory/streaming emergency.
         */
        boolean emergencyTransitionPressure =
                proxyBudgetUsagePermille() >= 900
                        || (ChunkWorkBudgetPolicy.streamingPressureLevel() >= 3
                        && solidLayerEmaNanos >= 16.0 * NANOS_PER_MILLI);

        if (!emergencyTransitionPressure
                || tier1Bytes
                < RESULT_TIER1_COMPACTION_THRESHOLD_BYTES) {
            return result;
        }

        buildResultTier1CompactedCount.increment();
        buildResultTier1CompactedBytes.add(
                tier1Bytes
        );

        return new PotatoLodBuildResult(
                result.sourceQuadCount(),
                result.mergeableQuadCount(),
                result.passthroughQuadCount(),
                null,
                result.tier2(),
                result.buildNanos()
        );
    }

    private static PotatoLodBuildResult forceFarTierForAdmission(
            PotatoLodBuildResult result
    ) {
        if (result == null
                || result.tier1() == null
                || result.tier2() == null) {
            return result;
        }

        return new PotatoLodBuildResult(
                result.sourceQuadCount(),
                result.mergeableQuadCount(),
                result.passthroughQuadCount(),
                null,
                result.tier2(),
                result.buildNanos()
        );
    }

    private static int reclaimOlderPendingForTarget(
            PotatoLodProxyBridge target,
            long generation,
            int limit
    ) {
        if (target == null || limit <= 0) {
            return 0;
        }

        int reclaimed =
                reclaimOlderPendingForTarget(
                        READY_INSTALLS_SMALL,
                        target,
                        generation,
                        limit
                );

        if (reclaimed < limit) {
            reclaimed +=
                    reclaimOlderPendingForTarget(
                            READY_INSTALLS_LARGE,
                            target,
                            generation,
                            limit - reclaimed
                    );
        }

        return reclaimed;
    }

    private static int reclaimOlderPendingForTarget(
            ConcurrentLinkedQueue<PendingInstall> queue,
            PotatoLodProxyBridge target,
            long generation,
            int limit
    ) {
        if (limit <= 0) {
            return 0;
        }

        int reclaimed =
                0;

        for (PendingInstall pending : queue) {
            if (reclaimed >= limit) {
                break;
            }

            if (pending.target() != target
                    || pending.generation() >= generation) {
                continue;
            }

            if (queue.remove(pending)) {
                PENDING_RESULT_BYTES.addAndGet(
                        -pending.resultBytes()
                );

                buildResultAdmissionReclaimedSupersededCount.increment();
                buildResultAdmissionReclaimedSupersededBytes.add(
                        pending.resultBytes()
                );

                reclaimed++;
            }
        }

        return reclaimed;
    }

    private static int reclaimSupersededPendingResults(
            int limit
    ) {
        if (limit <= 0) {
            return 0;
        }

        int reclaimed =
                reclaimSupersededPendingResults(
                        READY_INSTALLS_SMALL,
                        limit
                );

        if (reclaimed < limit) {
            reclaimed +=
                    reclaimSupersededPendingResults(
                            READY_INSTALLS_LARGE,
                            limit - reclaimed
                    );
        }

        return reclaimed;
    }

    private static int reclaimSupersededPendingResults(
            ConcurrentLinkedQueue<PendingInstall> queue,
            int limit
    ) {
        if (limit <= 0) {
            return 0;
        }

        int reclaimed =
                0;

        for (PendingInstall pending : queue) {
            if (reclaimed >= limit) {
                break;
            }

            if (!isSupersededPending(pending)) {
                continue;
            }

            if (queue.remove(pending)) {
                PENDING_RESULT_BYTES.addAndGet(
                        -pending.resultBytes()
                );

                buildResultAdmissionReclaimedSupersededCount.increment();
                buildResultAdmissionReclaimedSupersededBytes.add(
                        pending.resultBytes()
                );

                reclaimed++;
            }
        }

        return reclaimed;
    }

    private static boolean isSupersededPending(
            PendingInstall pending
    ) {
        if (pending == null) {
            return false;
        }

        Long latest =
                LATEST_PENDING_GENERATION.get(
                        pending.target()
                );

        return latest != null
                && pending.generation() < latest;
    }

    private static long preferredInstallBytes(PotatoLodBuildResult result) {
        if (result == null) {
            return Long.MAX_VALUE;
        }

        if (result.tier2() != null) {
            return result.tier2().gpuBytes();
        }

        return result.tier1() == null
                ? Long.MAX_VALUE
                : result.tier1().gpuBytes();
    }

    private static void compactProxyCacheIfNeeded() {
        if (proxyBudgetUsagePermille() < 800) {
            return;
        }

        int compacted = 0;

        synchronized (LIFECYCLE_LOCK) {
            if (closed || ACTIVE_PROXIES.isEmpty()) {
                return;
            }

            for (PotatoOpenGlLodProxy proxy : ACTIVE_PROXIES) {
                long reclaimed =
                        proxy.compactToFarTier();

                if (reclaimed > 0L) {
                    proxyLiveTierCompactionCount.increment();
                    proxyLiveTierCompactionBytes.add(
                            reclaimed
                    );

                    compacted++;
                }

                if (compacted >= 48
                        || proxyBudgetUsagePermille() <= 650) {
                    break;
                }
            }
        }
    }

    static int proxyBudgetUsagePermille() {
        long budget =
                Math.max(
                        1L,
                        proxyBudgetBytes
                );

        return (int) Math.min(
                1000L,
                PROXY_GPU_BYTES.get()
                        * 1000L
                        / budget
        );
    }

    static boolean preferSingleTierProxy(
            PotatoLodBuildResult result
    ) {
        /*
         * Preserve the visual Tier-1 band during normal and even heavy frame
         * pressure. Fall back to a Tier-2-only allocation only when native
         * proxy memory is genuinely near exhaustion, or when severe streaming
         * pressure and a badly over-budget SOLID layer occur together.
         */
        return proxyBudgetUsagePermille() >= 900
                || (ChunkWorkBudgetPolicy.streamingPressureLevel() >= 3
                && solidLayerEmaNanos >= 16.0 * NANOS_PER_MILLI);
    }

    static boolean allowTier2FallbackForTier1() {
        /*
         * A Tier-1 request is a quality contract. Tier-2 uses the aggressive
         * far-field merge and can visibly stretch detailed atlas texels.
         * If Tier-1 is unavailable, use the exact full-detail baseline instead
         * of silently substituting Tier-2.
         */
        return false;
    }

    static void onSingleTierCompaction() {
        proxySingleTierCompactionCount.increment();
    }

    static void onTier2FallbackForTier1() {
        proxyTier2FallbackForTier1Count.increment();
    }

    public static boolean tryReserveProxyBytes(
            long bytes
    ) {
        if (integratedIntelOpenGlProxyIsolationActive) {
            integratedIntelOpenGlProxySuppressedCount.increment();
            return false;
        }

        return tryReserveAtomic(
                PROXY_GPU_BYTES,
                bytes,
                proxyBudgetBytes
        );
    }

    public static void releaseProxyBytes(
            long bytes
    ) {
        if (bytes <= 0L) {
            return;
        }

        long after =
                PROXY_GPU_BYTES.addAndGet(
                        -bytes
                );

        if (after < 0L) {
            PROXY_GPU_BYTES.set(
                    0L
            );
        }
    }

    static void registerProxy(
            PotatoOpenGlLodProxy proxy
    ) {
        if (proxy == null) {
            return;
        }

        synchronized (LIFECYCLE_LOCK) {
            if (closed) {
                proxy.close();
                return;
            }

            ACTIVE_PROXIES.add(
                    proxy
            );

            activeProxyCount =
                    ACTIVE_PROXIES.size();

            peakActiveProxyCount =
                    Math.max(
                            peakActiveProxyCount,
                            activeProxyCount
                    );

            peakProxyGpuBytes =
                    Math.max(
                            peakProxyGpuBytes,
                            PROXY_GPU_BYTES.get()
                    );

            proxyInstalledCount.increment();
        }
    }

    static void unregisterProxy(
            PotatoOpenGlLodProxy proxy
    ) {
        if (proxy == null) {
            return;
        }

        synchronized (LIFECYCLE_LOCK) {
            ACTIVE_PROXIES.remove(
                    proxy
            );

            activeProxyCount =
                    ACTIVE_PROXIES.size();
        }
    }

    static void onProxyTierInstalled(
            int tier,
            int sourceQuads,
            int outputQuads,
            double reductionPercent,
            long gpuBytes
    ) {
        if (tier >= 2) {
            tier2InstalledCount.increment();
        } else {
            tier1InstalledCount.increment();
        }

        JsonObject currentReport =
                report;

        if (currentReport != null) {
            currentReport.addProperty(
                    "potatoLodLastInstalledTier",
                    tier
            );
            currentReport.addProperty(
                    "potatoLodLastInstalledSourceQuads",
                    sourceQuads
            );
            currentReport.addProperty(
                    "potatoLodLastInstalledOutputQuads",
                    outputQuads
            );
            currentReport.addProperty(
                    "potatoLodLastInstalledReductionPercent",
                    reductionPercent
            );
            currentReport.addProperty(
                    "potatoLodLastInstalledGpuBytes",
                    gpuBytes
            );
        }
    }

    static void onProxyTierClosed(
            int tier,
            long gpuBytes
    ) {
        if (tier >= 2) {
            tier2ClosedCount.increment();
        } else {
            tier1ClosedCount.increment();
        }
    }

    static void onProxyBudgetRejected(
            int tier,
            long requestedBytes
    ) {
        proxyBudgetRejectedCount.increment();

        JsonObject currentReport =
                report;

        if (currentReport != null) {
            currentReport.addProperty(
                    "potatoLodLastProxyBudgetRejectedTier",
                    tier
            );
            currentReport.addProperty(
                    "potatoLodLastProxyBudgetRejectedBytes",
                    requestedBytes
            );
        }
    }

    public static void onProxyInstallFailure(
            Throwable throwable
    ) {
        proxyInstallFailureCount.increment();

        lastProxyInstallFailure =
                throwable == null
                        ? "unknown"
                        : throwable.getClass()
                        .getName()
                        + ": "
                        + String.valueOf(
                                throwable.getMessage()
                        );

        if (throwable != null) {
            PotatoRuntime.LOGGER.warn(
                    "[Potato/LOD] OpenGL LOD proxy install failed; original Minecraft draw remains active.",
                    throwable
            );
        }
    }

    public static void onStaleBuildResult() {
        buildStaleResultCount.increment();
    }

    public static void onVisibleProxyDraw(
            int tier,
            int sourceQuads,
            int proxyQuads
    ) {
        visibleProxyDrawCount++;
        visibleBaselineDrawSuppressedCount++;

        if (tier >= 2) {
            visibleTier2DrawCount++;
        } else {
            visibleTier1DrawCount++;
        }

        visibleSourceQuadCount +=
                sourceQuads;

        visibleProxyQuadCount +=
                proxyQuads;

        visibleAvoidedQuadCount +=
                Math.max(
                        0,
                        sourceQuads
                                - proxyQuads
                );
    }

    private static void addLayerTiming(
            JsonObject target,
            String layerName,
            long count,
            long totalNanos,
            long peakNanos
    ) {
        target.addProperty(
                "potatoSectionLayer" + layerName + "Count",
                count
        );
        target.addProperty(
                "potatoSectionLayer" + layerName + "AverageMillis",
                count == 0L
                        ? 0.0
                        : totalNanos
                        / 1_000_000.0
                        / count
        );
        target.addProperty(
                "potatoSectionLayer" + layerName + "PeakMillis",
                peakNanos
                        / 1_000_000.0
        );
    }

    public static boolean verified() {
        return !closed
                && visibleProxyDrawCount > 0
                && visibleAvoidedQuadCount > 0
                && proxyInstallFailureCount.sum() == 0;
    }

    public static void enrich(
            JsonObject target
    ) {
        if (target == null) {
            return;
        }

        target.addProperty(
                "potatoLodRuntimeInstalled",
                true
        );
        target.addProperty(
                "potatoLodMode",
                "LOADED_VIEW_FRAME_PACED_SOLID_LOD_STAGE4_EMERGENCY_PRESSURE"
        );
        target.addProperty(
                "potatoLodProfile",
                profile.name()
        );
        target.addProperty(
                "potatoLodProfileRuntimeMutable",
                true
        );
        target.addProperty(
                "potatoLodProfileProperty",
                "potato.lod.profile"
        );
        target.addProperty(
                "potatoLodProfileValues",
                "OFF,QUALITY,BALANCED,POTATO,ADAPTIVE"
        );
        target.addProperty(
                "potatoLodPreservesMinecraftRenderDistance",
                true
        );
        target.addProperty(
                "potatoLodStreamingPriority",
                "SEPARATE_DEFERRED_CAPTURE_POOL_FRAME_PACED_PROXY_INSTALL"
        );
        target.addProperty(
                "potatoLodDeferredCatchupEnabled",
                true
        );
        target.addProperty(
                "potatoLodStreamingPressureLevel",
                ChunkWorkBudgetPolicy.streamingPressureLevel()
        );
        target.addProperty(
                "potatoLodStreamingUploadQueueSize",
                ChunkWorkBudgetPolicy.currentUploadQueueSize()
        );
        target.addProperty(
                "potatoLodMinimumUsefulReductionPercent",
                MIN_USEFUL_REDUCTION_PERCENT
        );
        target.addProperty(
                "potatoLodMutatesChunkLoading",
                false
        );
        target.addProperty(
                "potatoLodMutatesWorldTicking",
                false
        );
        target.addProperty(
                "potatoLodMutatesEntitySimulation",
                false
        );
        target.addProperty(
                "potatoLodMutatesSaving",
                false
        );
        target.addProperty(
                "potatoLodVisibleLayerCoverage",
                "SOLID_ONLY"
        );
        target.addProperty(
                "potatoLodCutoutDeferred",
                true
        );
        target.addProperty(
                "potatoLodTranslucentDeferred",
                true
        );
        target.addProperty(
                "potatoLodComplexQuadPolicy",
                "BYTE_EXACT_PASSTHROUGH"
        );
        target.addProperty(
                "potatoLodTier1Policy",
                "SAME_VISUAL_PAYLOAD_GREEDY_RECTANGLES"
        );
        target.addProperty(
                "potatoLodTier2Policy",
                "SAME_ATLAS_MATERIAL_RELAXED_LIGHTING_GREEDY_RECTANGLES"
        );
        target.addProperty(
                "potatoLodTier1MaximumRectangleSpanBlocks",
                2
        );
        target.addProperty(
                "potatoLodTier2MaximumRectangleSpanBlocks",
                4
        );
        target.addProperty(
                "potatoLodAtlasSubtextureRepeatLimitationMitigatedBySpanCaps",
                true
        );
        target.addProperty(
                "potatoLodAsyncWorkerThreads",
                workerThreads
        );
        target.addProperty(
                "potatoLodMinimumSourceQuads",
                minimumSourceQuads
        );
        target.addProperty(
                "potatoLodMaximumCaptureBytes",
                maximumCaptureBytes
        );
        target.addProperty(
                "potatoLodBuildQueueBytes",
                QUEUED_INPUT_BYTES.get()
        );
        target.addProperty(
                "potatoLodBuildQueueMaximumBytes",
                maximumQueuedInputBytes
        );
        target.addProperty(
                "potatoLodDeferredCaptureBytes",
                DEFERRED_CAPTURE_BYTES.get()
        );
        target.addProperty(
                "potatoLodDeferredCaptureMaximumBytes",
                maximumDeferredCaptureBytes
        );
        target.addProperty(
                "potatoLodDeferredCapturePoolSeparatedFromActiveBuildQueue",
                true
        );
        target.addProperty(
                "potatoLodPendingResultBytes",
                PENDING_RESULT_BYTES.get()
        );
        target.addProperty(
                "potatoLodPendingResultMaximumBytes",
                maximumPendingResultBytes
        );
        target.addProperty(
                "potatoLodProxyGpuBytes",
                PROXY_GPU_BYTES.get()
        );
        target.addProperty(
                "potatoLodProxyBudgetBytes",
                proxyBudgetBytes
        );
        target.addProperty(
                "potatoLodProxyBudgetMiB",
                proxyBudgetBytes
                        / (
                        1024L * 1024L
                )
        );
        target.addProperty(
                "potatoLodProxyBudgetHeuristic",
                "ACTIVE_OPENGL_RENDERER_AWARE_JVM_CAP_PLUS_RESULT_COMPACTION"
        );
        target.addProperty(
                "potatoLodOpenGlRendererDescription",
                activeOpenGlRendererDescription
        );
        target.addProperty(
                "potatoLodUncappedDefaultProxyBudgetMiB",
                uncappedDefaultProxyBudgetMiB
        );
        target.addProperty(
                "potatoLodActiveOpenGlDefaultProxyBudgetCapMiB",
                activeOpenGlDefaultProxyBudgetCapMiB
        );
        target.addProperty(
                "potatoLodActiveOpenGlDefaultProxyBudgetCapApplied",
                activeOpenGlDefaultProxyBudgetCapApplied
        );
        target.addProperty(
                "potatoLodExplicitProxyBudgetOverride",
                explicitProxyBudgetOverride
        );
        target.addProperty(
                "potatoLodIntegratedIntelOpenGlProxyIsolationActive",
                integratedIntelOpenGlProxyIsolationActive
        );
        target.addProperty(
                "potatoLodIntegratedIntelOpenGlProxyIsolationReason",
                integratedIntelOpenGlProxyIsolationActive
                        ? "REPEATED_GL_OUT_OF_MEMORY_AFTER_96_MIB_CAP"
                        : "NOT_REQUIRED_OR_EXPLICITLY_OVERRIDDEN"
        );
        target.addProperty(
                "potatoLodIntegratedIntelOpenGlProxyIsolationOverrideProperty",
                "potato.lod.allowIntelIntegratedOpenGlProxy"
        );
        target.addProperty(
                "potatoLodIntegratedIntelOpenGlProxySuppressedCount",
                integratedIntelOpenGlProxySuppressedCount.sum()
        );
        target.addProperty(
                "potatoLodActiveProxyCount",
                activeProxyCount
        );
        target.addProperty(
                "potatoLodPeakActiveProxyCount",
                peakActiveProxyCount
        );
        target.addProperty(
                "potatoLodPeakProxyGpuBytes",
                peakProxyGpuBytes
        );

        target.addProperty(
                "potatoLodBuildScheduledCount",
                buildScheduledCount.sum()
        );
        target.addProperty(
                "potatoLodBuildCompletedCount",
                buildCompletedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildNoReductionCount",
                buildNoReductionCount.sum()
        );
        target.addProperty(
                "potatoLodBuildQueueRejectedCount",
                buildQueueRejectedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildQueueByteRejectedCount",
                buildQueueByteRejectedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildResultByteRejectedCount",
                buildResultByteRejectedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildResultAdmissionRetryCount",
                buildResultAdmissionRetryCount.sum()
        );
        target.addProperty(
                "potatoLodBuildResultAdmissionRescuedCount",
                buildResultAdmissionRescuedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildResultAdmissionForcedTier2Count",
                buildResultAdmissionForcedTier2Count.sum()
        );
        target.addProperty(
                "potatoLodBuildResultAdmissionForcedTier2Bytes",
                buildResultAdmissionForcedTier2Bytes.sum()
        );
        target.addProperty(
                "potatoLodBuildResultAdmissionReclaimedSupersededCount",
                buildResultAdmissionReclaimedSupersededCount.sum()
        );
        target.addProperty(
                "potatoLodBuildResultAdmissionReclaimedSupersededBytes",
                buildResultAdmissionReclaimedSupersededBytes.sum()
        );
        target.addProperty(
                "potatoLodBuildSupersededPendingInstallDropCount",
                buildSupersededPendingInstallDropCount.sum()
        );
        target.addProperty(
                "potatoLodPendingGenerationTrackerCount",
                LATEST_PENDING_GENERATION.size()
        );
        target.addProperty(
                "potatoLodResultAdmissionPolicy",
                "LATEST_GENERATION_RECLAIM_THEN_FAR_TIER_RETRY"
        );
        target.addProperty(
                "potatoLodBuildResultTier1CompactedCount",
                buildResultTier1CompactedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildResultTier1CompactedBytes",
                buildResultTier1CompactedBytes.sum()
        );
        target.addProperty(
                "potatoLodTransitionResultCompactionThresholdBytes",
                RESULT_TIER1_COMPACTION_THRESHOLD_BYTES
        );
        target.addProperty(
                "potatoLodBuildStaleResultCount",
                buildStaleResultCount.sum()
        );
        target.addProperty(
                "potatoLodBuildFailureCount",
                buildFailureCount.sum()
        );
        target.addProperty(
                "potatoLodBuildSuppressedForStreamingCount",
                buildSuppressedForStreamingCount.sum()
        );
        target.addProperty(
                "potatoLodBuildDeferredForStreamingCount",
                buildDeferredForStreamingCount.sum()
        );
        target.addProperty(
                "potatoLodBuildDeferredDrainedCount",
                buildDeferredDrainedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildDeferredDroppedCount",
                buildDeferredDroppedCount.sum()
        );
        target.addProperty(
                "potatoLodBuildDeferredByteRejectedCount",
                buildDeferredByteRejectedCount.sum()
        );
        target.addProperty(
                "potatoLodDeferredBuildQueueCount",
                DEFERRED_BUILDS.size()
        );
        target.addProperty(
                "potatoLodDeferredBuildQueuePeakCount",
                deferredBuildPeakCount
        );
        target.addProperty(
                "potatoLodBuildSuppressedForLowValueCount",
                buildSuppressedForLowValueCount.sum()
        );
        target.addProperty(
                "potatoLodReadyInstallQueueCount",
                READY_INSTALLS_SMALL.size() + READY_INSTALLS_LARGE.size()
        );
        target.addProperty(
                "potatoLodReadyInstallSmallQueueCount",
                READY_INSTALLS_SMALL.size()
        );
        target.addProperty(
                "potatoLodReadyInstallLargeQueueCount",
                READY_INSTALLS_LARGE.size()
        );
        target.addProperty(
                "potatoLodReadyInstallSmallQueuePeakCount",
                readyInstallSmallPeakCount
        );
        target.addProperty(
                "potatoLodReadyInstallLargeQueuePeakCount",
                readyInstallLargePeakCount
        );
        target.addProperty(
                "potatoLodReadyInstallQueuePeakCount",
                readyInstallPeakCount
        );
        target.addProperty(
                "potatoLodInstallYieldedForStreamingCount",
                installYieldedForStreamingCount.sum()
        );
        target.addProperty(
                "potatoLodTurnFrameInstallGuardEnabled",
                true
        );
        target.addProperty(
                "potatoLodInstallDeferredForTurnReliefCount",
                installDeferredForTurnReliefCount.sum()
        );
        target.addProperty(
                "potatoLodTurnReliefLayerSampleCount",
                turnReliefLayerSampleCount
        );
        target.addProperty(
                "potatoLodTurnReliefLayerAverageMillis",
                turnReliefLayerSampleCount == 0L
                        ? 0.0
                        : turnReliefLayerElapsedNanosTotal
                        / 1_000_000.0
                        / turnReliefLayerSampleCount
        );
        target.addProperty(
                "potatoLodTurnReliefLayerPeakMillis",
                turnReliefLayerElapsedNanosPeak
                        / 1_000_000.0
        );
        target.addProperty(
                "potatoLodInstallDrainedCount",
                installDrainedCount.sum()
        );
        target.addProperty(
                "potatoLodInstallBatchCount",
                installBatchCount.sum()
        );
        target.addProperty(
                "potatoLodInstallBatchAverageMillis",
                installBatchCount.sum() == 0
                        ? 0.0
                        : installBatchNanosTotal.sum()
                        / 1_000_000.0
                        / installBatchCount.sum()
        );
        target.addProperty(
                "potatoLodInstallBatchPeakMillis",
                installBatchNanosPeak
                        / 1_000_000.0
        );
        target.addProperty(
                "potatoLodInstallSmallCount",
                installSmallCount.sum()
        );
        target.addProperty(
                "potatoLodInstallLargeCount",
                installLargeCount.sum()
        );
        target.addProperty(
                "potatoLodInstallLargeDeferredForFrameTimeCount",
                installLargeDeferredForFrameTimeCount.sum()
        );
        target.addProperty(
                "potatoLodInstallSmallThresholdBytes",
                SMALL_INSTALL_BYTES
        );
        target.addProperty(
                "potatoLodBuildAverageMillis",
                buildCompletedCount.sum() == 0
                        ? 0.0
                        : buildNanosTotal.sum()
                        / 1_000_000.0
                        / buildCompletedCount.sum()
        );
        target.addProperty(
                "potatoLodSourceQuadCount",
                sourceQuadCount.sum()
        );
        target.addProperty(
                "potatoLodMergeableQuadCount",
                mergeableQuadCount.sum()
        );
        target.addProperty(
                "potatoLodTier1BuiltCount",
                tier1BuiltCount.sum()
        );
        target.addProperty(
                "potatoLodTier2BuiltCount",
                tier2BuiltCount.sum()
        );
        target.addProperty(
                "potatoLodTier1BuiltAvoidedQuadCount",
                tier1BuiltAvoidedQuadCount.sum()
        );
        target.addProperty(
                "potatoLodTier2BuiltAvoidedQuadCount",
                tier2BuiltAvoidedQuadCount.sum()
        );

        target.addProperty(
                "potatoLodProxyInstalledCount",
                proxyInstalledCount.sum()
        );
        target.addProperty(
                "potatoLodProxyInstallFailureCount",
                proxyInstallFailureCount.sum()
        );
        target.addProperty(
                "potatoLodProxyBudgetRejectedCount",
                proxyBudgetRejectedCount.sum()
        );
        target.addProperty(
                "potatoLodProxyBudgetUsagePermille",
                proxyBudgetUsagePermille()
        );
        target.addProperty(
                "potatoLodProxySingleTierCompactionCount",
                proxySingleTierCompactionCount.sum()
        );
        target.addProperty(
                "potatoLodProxyLiveTierCompactionCount",
                proxyLiveTierCompactionCount.sum()
        );
        target.addProperty(
                "potatoLodProxyLiveTierCompactionBytes",
                proxyLiveTierCompactionBytes.sum()
        );
        target.addProperty(
                "potatoLodProxyTier2FallbackForTier1Count",
                proxyTier2FallbackForTier1Count.sum()
        );
        target.addProperty(
                "potatoLodProxyAllocationPolicy",
                "TIER1_QUALITY_BAND_PRESERVED_TIER2_FAR_FIELD_EMERGENCY_SINGLE_TIER_ONLY"
        );
        target.addProperty(
                "potatoLodTier1QualityBandPreserved",
                true
        );
        target.addProperty(
                "potatoLodTier2FallbackForTier1Enabled",
                false
        );
        target.addProperty(
                "potatoLodSingleTierEmergencyBudgetPermille",
                900
        );
        target.addProperty(
                "potatoLodSingleTierEmergencyStreamingPressureLevel",
                3
        );
        target.addProperty(
                "potatoLodSingleTierEmergencySolidEmaMillis",
                16.0
        );
        target.addProperty(
                "potatoLodTier1InstalledCount",
                tier1InstalledCount.sum()
        );
        target.addProperty(
                "potatoLodTier2InstalledCount",
                tier2InstalledCount.sum()
        );
        target.addProperty(
                "potatoLodTier1ClosedCount",
                tier1ClosedCount.sum()
        );
        target.addProperty(
                "potatoLodTier2ClosedCount",
                tier2ClosedCount.sum()
        );

        target.addProperty(
                "potatoSectionLayerTimingInstalled",
                true
        );
        target.addProperty(
                "potatoSectionLayerNonSolidPotatoPerDrawBypass",
                true
        );
        target.addProperty(
                "potatoSectionLayerBackendSidecarReadDeferred",
                true
        );
        target.addProperty(
                "potatoLodRenderHotCountersAtomic",
                false
        );
        target.addProperty(
                "potatoLodDetailSelectionSqrtFree",
                true
        );
        addLayerTiming(
                target,
                "Solid",
                sectionLayerSolidCount,
                sectionLayerSolidNanosTotal,
                sectionLayerSolidNanosPeak
        );
        addLayerTiming(
                target,
                "Cutout",
                sectionLayerCutoutCount,
                sectionLayerCutoutNanosTotal,
                sectionLayerCutoutNanosPeak
        );
        addLayerTiming(
                target,
                "CutoutMipped",
                sectionLayerCutoutMippedCount,
                sectionLayerCutoutMippedNanosTotal,
                sectionLayerCutoutMippedNanosPeak
        );
        addLayerTiming(
                target,
                "Translucent",
                sectionLayerTranslucentCount,
                sectionLayerTranslucentNanosTotal,
                sectionLayerTranslucentNanosPeak
        );
        addLayerTiming(
                target,
                "Other",
                sectionLayerOtherCount,
                sectionLayerOtherNanosTotal,
                sectionLayerOtherNanosPeak
        );
        target.addProperty(
                "potatoSectionLayerSolidEmaMillis",
                sectionLayerSolidEmaNanos / 1_000_000.0
        );
        target.addProperty(
                "potatoSectionLayerCutoutEmaMillis",
                sectionLayerCutoutEmaNanos / 1_000_000.0
        );
        target.addProperty(
                "potatoSectionLayerCutoutMippedEmaMillis",
                sectionLayerCutoutMippedEmaNanos / 1_000_000.0
        );
        target.addProperty(
                "potatoSectionLayerTranslucentEmaMillis",
                sectionLayerTranslucentEmaNanos / 1_000_000.0
        );
        target.addProperty(
                "potatoSectionLayerOtherEmaMillis",
                sectionLayerOtherEmaNanos / 1_000_000.0
        );

        target.addProperty(
                "potatoLodDetailSelectionCount",
                detailSelectionCount
        );
        target.addProperty(
                "potatoLodDesiredFullCount",
                desiredFullCount
        );
        target.addProperty(
                "potatoLodDesiredTier1Count",
                desiredTier1Count
        );
        target.addProperty(
                "potatoLodDesiredTier2Count",
                desiredTier2Count
        );
        target.addProperty(
                "potatoLodProxyFallbackToBaselineCount",
                proxyFallbackToBaselineCount
        );

        target.addProperty(
                "potatoLodVisibleProxyDrawCount",
                visibleProxyDrawCount
        );
        target.addProperty(
                "potatoLodVisibleTier1DrawCount",
                visibleTier1DrawCount
        );
        target.addProperty(
                "potatoLodVisibleTier2DrawCount",
                visibleTier2DrawCount
        );
        target.addProperty(
                "potatoLodVisibleBaselineDrawSuppressedCount",
                visibleBaselineDrawSuppressedCount
        );
        target.addProperty(
                "potatoLodVisibleSourceQuadCount",
                visibleSourceQuadCount
        );
        target.addProperty(
                "potatoLodVisibleProxyQuadCount",
                visibleProxyQuadCount
        );
        target.addProperty(
                "potatoLodVisibleAvoidedQuadCount",
                visibleAvoidedQuadCount
        );
        target.addProperty(
                "potatoLodVisibleQuadReductionPercent",
                visibleSourceQuadCount == 0
                        ? 0.0
                        : visibleAvoidedQuadCount
                        * 100.0
                        / visibleSourceQuadCount
        );

        target.addProperty(
                "potatoLodCurrentRenderDistanceChunks",
                currentRenderDistanceChunks
        );
        target.addProperty(
                "potatoLodCurrentLod1StartChunks",
                currentLod1StartChunks
        );
        target.addProperty(
                "potatoLodCurrentLod2StartChunks",
                currentLod2StartChunks
        );
        target.addProperty(
                "potatoLodCurrentLod1PixelThreshold",
                currentLod1PixelThreshold
        );
        target.addProperty(
                "potatoLodCurrentLod2PixelThreshold",
                currentLod2PixelThreshold
        );
        target.addProperty(
                "potatoLodLongViewDistancePolicyEnabled",
                true
        );
        target.addProperty(
                "potatoLodLongViewDistancePolicyActive",
                longViewDistancePolicyActive
        );
        target.addProperty(
                "potatoLodLongViewDistanceStartChunks",
                LONG_VIEW_DISTANCE_START_CHUNKS
        );
        target.addProperty(
                "potatoLodLongViewLod1Fraction",
                LONG_VIEW_LOD1_FRACTION
        );
        target.addProperty(
                "potatoLodLongViewLod2Fraction",
                LONG_VIEW_LOD2_FRACTION
        );
        target.addProperty(
                "potatoLodAdaptivePressureLevel",
                adaptivePressureLevel
        );
        target.addProperty(
                "potatoLodAdaptivePressureMaximumLevel",
                MAX_ADAPTIVE_PRESSURE_LEVEL
        );
        target.addProperty(
                "potatoLodAdaptiveEmergencyLevel4ExtraLod1Chunks",
                3
        );
        target.addProperty(
                "potatoLodAdaptiveEmergencyLevel4ExtraLod2Chunks",
                5
        );
        target.addProperty(
                "potatoLodAdaptiveEmergencyPressureActive",
                adaptivePressureLevel
                        >= MAX_ADAPTIVE_PRESSURE_LEVEL
        );
        target.addProperty(
                "potatoLodMotionStableBandsInstalled",
                true
        );
        target.addProperty(
                "potatoLodMotionStableSpeedBand",
                PotatoPredictiveStreamingRuntime
                        .speedBand()
                        .name()
        );
        target.addProperty(
                "potatoLodMotionStableInsetChunks",
                profile == PotatoLodProfile.ADAPTIVE
                        ? PotatoPredictiveStreamingRuntime
                        .lodMotionInsetChunks()
                        : 0
        );
        target.addProperty(
                "potatoLodMotionStableRecoveryHysteresisScale",
                PotatoPredictiveStreamingRuntime
                        .lodRecoveryHysteresisScale()
        );
        target.addProperty(
                "potatoLodMotionStablePolicy",
                "CLIPMAP_INSPIRED_VIEWER_CENTERED_BANDS_WITH_HYSTERESIS"
        );
        target.addProperty(
                "potatoLodAdaptiveEmergencyEmaThresholdMillis",
                EMERGENCY_PRESSURE_EMA_NANOS
                        / NANOS_PER_MILLI
        );
        target.addProperty(
                "potatoLodAdaptivePressureUpshiftCount",
                pressureUpshiftCount
        );
        target.addProperty(
                "potatoLodAdaptivePressureDownshiftCount",
                pressureDownshiftCount
        );
        target.addProperty(
                "potatoLodSolidLayerSampleCount",
                solidLayerSampleCount
        );
        target.addProperty(
                "potatoLodSolidLayerAverageMillis",
                solidLayerSampleCount == 0
                        ? 0.0
                        : solidLayerElapsedNanosTotal
                        / 1_000_000.0
                        / solidLayerSampleCount
        );
        target.addProperty(
                "potatoLodSolidLayerEmaMillis",
                solidLayerEmaNanos
                        / 1_000_000.0
        );
        target.addProperty(
                "potatoLodSolidLayerPeakMillis",
                solidLayerElapsedNanosPeak
                        / 1_000_000.0
        );
        target.addProperty(
                "potatoLodVerified",
                visibleProxyDrawCount > 0
                        && visibleAvoidedQuadCount > 0
                        && proxyInstallFailureCount.sum() == 0
        );

        if (!lastBuildFailure.isBlank()) {
            target.addProperty(
                    "potatoLodLastBuildFailure",
                    lastBuildFailure
            );
        }

        if (!lastProxyInstallFailure.isBlank()) {
            target.addProperty(
                    "potatoLodLastProxyInstallFailure",
                    lastProxyInstallFailure
            );
        }
    }

    private static String readActiveOpenGlRendererDescription(
            JsonObject sourceReport
    ) {
        if (sourceReport == null) {
            return "";
        }

        String[] keys = {
                "rendererInitApiDescriptionAfter",
                "rendererInitApiDescriptionBefore"
        };

        for (String key : keys) {
            try {
                if (sourceReport.has(key)
                        && !sourceReport.get(key).isJsonNull()) {
                    String value =
                            sourceReport.get(key).getAsString();

                    if (value != null
                            && !value.isBlank()
                            && !"Unknown".equalsIgnoreCase(value)) {
                        return value;
                    }
                }
            } catch (RuntimeException ignored) {
                // Diagnostics are best effort; an unknown renderer is fail-open.
            }
        }

        return "";
    }

    private static boolean isIntelUhdOrHdOpenGlRenderer(
            String rendererDescription
    ) {
        if (rendererDescription == null
                || rendererDescription.isBlank()) {
            return false;
        }

        String normalized =
                rendererDescription.toUpperCase(
                        java.util.Locale.ROOT
                );

        return normalized.contains("INTEL")
                && (
                normalized.contains("UHD GRAPHICS")
                        || normalized.contains("HD GRAPHICS")
        );
    }

    private static int integratedOpenGlProxyBudgetCapMiB(
            String rendererDescription
    ) {
        if (rendererDescription == null
                || rendererDescription.isBlank()) {
            return 0;
        }

        String normalized =
                rendererDescription.toUpperCase(
                        java.util.Locale.ROOT
                );

        if (!normalized.contains("INTEL")) {
            return 0;
        }

        if (normalized.contains("UHD GRAPHICS")
                || normalized.contains("HD GRAPHICS")) {
            return 96;
        }

        if (normalized.contains("IRIS")) {
            return 128;
        }

        return 0;
    }

    public static void close() {
        synchronized (LIFECYCLE_LOCK) {
            if (closed) {
                return;
            }

            closed =
                    true;

            ThreadPoolExecutor currentExecutor =
                    executor;

            executor =
                    null;

            if (currentExecutor != null) {
                currentExecutor.shutdownNow();
            }

            DeferredBuild deferredBuild;

            while ((deferredBuild = DEFERRED_BUILDS.poll()) != null) {
                DEFERRED_CAPTURE_BYTES.addAndGet(
                        -deferredBuild.inputBytes()
                );

                buildDeferredDroppedCount.increment();
            }

            PendingInstall pending;

            while ((pending = READY_INSTALLS_SMALL.poll()) != null) {
                PENDING_RESULT_BYTES.addAndGet(-pending.resultBytes());
                buildStaleResultCount.increment();
            }

            while ((pending = READY_INSTALLS_LARGE.poll()) != null) {
                PENDING_RESULT_BYTES.addAndGet(-pending.resultBytes());
                buildStaleResultCount.increment();
            }

            LATEST_PENDING_GENERATION.clear();

            if (!ACTIVE_PROXIES.isEmpty()) {
                ArrayList<PotatoOpenGlLodProxy> snapshot =
                        new ArrayList<>(
                                ACTIVE_PROXIES
                        );

                for (PotatoOpenGlLodProxy proxy : snapshot) {
                    try {
                        proxy.close();
                    } catch (Throwable throwable) {
                        onProxyInstallFailure(
                                throwable
                        );
                    }
                }

                ACTIVE_PROXIES.clear();
                activeProxyCount = 0;
            }

            JsonObject currentReport =
                    report;

            if (currentReport != null) {
                enrich(
                        currentReport
                );

                currentReport.addProperty(
                        "potatoLodRuntimeClosed",
                        true
                );
            }
        }
    }

    private record DeferredBuild(
            PotatoLodProxyBridge target,
            long generation,
            byte[] sourceBytes,
            int vertexCount,
            int inputBytes
    ) {
    }

    private record PendingInstall(
            PotatoLodProxyBridge target,
            long generation,
            PotatoLodBuildResult result,
            long resultBytes,
            long preferredInstallBytes
    ) {
    }

    private static boolean tryReserveAtomic(
            AtomicLong counter,
            long amount,
            long limit
    ) {
        if (amount <= 0L
                || limit <= 0L
                || amount > limit) {
            return false;
        }

        while (true) {
            long current =
                    counter.get();

            long next =
                    current
                            + amount;

            if (next < current
                    || next > limit) {
                return false;
            }

            if (counter.compareAndSet(
                    current,
                    next
            )) {
                return true;
            }
        }
    }

    private static long resultBytes(
            PotatoLodBuildResult result
    ) {
        long total =
                0L;

        if (result.tier1() != null) {
            total +=
                    result.tier1()
                            .gpuBytes();
        }

        if (result.tier2() != null) {
            total +=
                    result.tier2()
                            .gpuBytes();
        }

        return total;
    }

    private static int boundedIntegerProperty(
            String name,
            int fallback,
            int minimum,
            int maximum
    ) {
        int value =
                Integer.getInteger(
                        name,
                        fallback
                );

        return clamp(
                value,
                minimum,
                maximum
        );
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum
    ) {
        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
        );
    }
}
