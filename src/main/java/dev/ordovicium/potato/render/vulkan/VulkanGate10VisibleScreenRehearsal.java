package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.EXTMemoryObject.*;
import static org.lwjgl.opengl.EXTMemoryObjectWin32.glImportMemoryWin32HandleEXT;
import static org.lwjgl.opengl.EXTSemaphore.*;
import static org.lwjgl.opengl.EXTSemaphoreWin32.glImportSemaphoreWin32HandleEXT;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL13C.*;
import static org.lwjgl.opengl.GL14C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRExternalMemoryWin32.vkGetMemoryWin32HandleKHR;
import static org.lwjgl.vulkan.KHRExternalSemaphoreWin32.vkGetSemaphoreWin32HandleKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Gate-10 Stage 8: bounded full-frame atomic handoff rehearsal.
 *
 * <p>This class still does NOT suppress Minecraft's OpenGL Screen draw and does
 * not claim production Gate-10 ownership. After ScreenEvent.Render.Post it
 * captures the already-rendered MainTarget into the exportable shared image,
 * hands that image to Vulkan with external GPU semaphores, and then presents
 * the Vulkan-returned image across the entire Minecraft MainTarget.</p>
 *
 * <p>The purpose is stricter than the thumbnail rehearsal: for a short bounded
 * window the final visible Screen pixels come from the Vulkan round-trip as one
 * full-frame unit. If any prerequisite or synchronization step is uncertain,
 * the composite is skipped and the original OpenGL Screen remains untouched.
 * No CPU pixel readback and no gameplay blocking GPU wait is introduced.</p>
 *
 * <p>Stage 110 retires the deliberately low-resolution visual canary. The
 * shared rehearsal target is selected from the live Minecraft framebuffer at
 * first use, so successful qualification handoffs remain native-resolution and
 * visually sharp while preserving the same GPU-only fail-open contract.</p>
 *
 * <p>Stage 114 extends the proven Stage-111 one-frame export into a bounded live
 * UI presentation stream. Minecraft still rasterizes Screen content into its
 * normal OpenGL MainTarget during this qualification stage, but each fresh
 * final Screen frame is handed GPU-only to the separate Vulkan swapchain. The
 * same two external binary semaphores form a strict GL -> Vulkan -> GL cycle,
 * so the shared image is never overwritten while Gate 11 is reading it.</p>
 */
final class VulkanGate10VisibleScreenRehearsal
        implements AutoCloseable {

    private static QualifiedFrame publishedQualifiedFrame;
    private static VulkanGate10VisibleScreenRehearsal activeInstance;

    private static final long GATE11_LIVE_STREAM_DURATION_NANOS =
            20_000_000_000L;

    /*
     * Patch 126: after a fresh 11/11 process proof, keep the Gate-10 producer
     * alive for the entire single-visible Vulkan presentation session. The
     * normal duration/frame cap remains in source for non-production rehearsal
     * mode and telemetry, but is not an active stop condition in this stage.
     */
    private static final boolean GATE11_PRODUCTION_PRESENTATION_SESSION =
            true;

    /**
     * True only while Gate 10 has already qualified and the bounded Gate-11
     * live presentation consumer is actively accepting fresh MainTarget frames.
     *
     * <p>Patch 120 uses this as a read-only handoff from the outer
     * RenderFrameEvent boundary. It does not arm Gate 10 early and does not
     * change presentation/window ownership.</p>
     */
    static synchronized boolean interactiveWholeFrameCaptureActive() {
        VulkanGate10VisibleScreenRehearsal instance =
                activeInstance;

        return instance != null
                && !instance.closed
                && !instance.disabledAfterFailure
                && instance.runtimeQualificationOffered
                && instance.gate11LiveStreamActive;
    }

    private static final int GATE11_LIVE_STREAM_MAX_FRAMES =
            3600;

    private static final int FALLBACK_TARGET_WIDTH =
            256;

    private static final int FALLBACK_TARGET_HEIGHT =
            144;

    private int targetWidth =
            FALLBACK_TARGET_WIDTH;

    private int targetHeight =
            FALLBACK_TARGET_HEIGHT;

    private boolean fullResolutionTargetSelected;
    private int targetSelectionFramebufferWidth;
    private int targetSelectionFramebufferHeight;

    private long nativeResolutionResizeCheckCount;
    private long nativeResolutionResizeAttemptCount;
    private long nativeResolutionResizeSuccessCount;
    private long nativeResolutionResizeDeferralCount;
    private long nativeResolutionResizeFailureCount;
    private long nativeResolutionRetainedGenerationCount;
    private long nativeResolutionRetiredGenerationPollCount;
    private long nativeResolutionReclaimedGenerationCount;
    private long nativeResolutionPeakRetainedGenerationCount;
    private int nativeResolutionTargetGeneration =
            1;
    private int nativeResolutionLastRequestedWidth;
    private int nativeResolutionLastRequestedHeight;
    private int nativeResolutionPendingWidth;
    private int nativeResolutionPendingHeight;
    private int nativeResolutionPendingStableObservationCount;
    private static final int NATIVE_RESOLUTION_REQUIRED_STABLE_OBSERVATIONS =
            2;
    private String nativeResolutionResizeState =
            "INITIAL_TARGET";

    private static final int COLOR_FORMAT =
            VK_FORMAT_R8G8B8A8_UNORM;

    private static final int DEPTH_FORMAT =
            VK_FORMAT_D32_SFLOAT;

    /*
     * Patch 132 release admission:
     * keep the hard requirement at 120 real native-resolution full-frame
     * handoffs, but make the proof autonomous. A player must never have to
     * open/close Inventory four times to start Potato Runtime.
     *
     * One continuous world session is enough because the outer-frame producer
     * now captures complete gameplay frames (world + HUD + entities/particles)
     * automatically. Gate 11 still performs its independent long-running WSI,
     * pixel and input proof after the visible Vulkan session starts.
     *
     * The capacity/time ceiling is deliberately much larger than the 120-frame
     * correctness threshold so low-FPS hardware can qualify without user input.
     */
    private static final int MAX_VISIBLE_FRAMES_PER_SESSION =
            240;

    private static final int MAX_PREVIEW_SESSIONS =
            16;

    private static final long QUALIFICATION_MIN_FULL_FRAME_HANDOFFS =
            120L;

    private static final int QUALIFICATION_MIN_SCREEN_SESSIONS =
            1;

    private static final long PREVIEW_SESSION_DURATION_NANOS =
            15_000_000_000L;

    private static final long PREVIEW_SESSION_GAP_NANOS =
            350_000_000L;

    private static final int GL_PREVIEW_INSET_PIXELS =
            16;

    private static final int GL_PREVIEW_DISPLAY_WIDTH =
            320;

    private static final int GL_PREVIEW_DISPLAY_HEIGHT =
            180;

    private static final int GL_HANDLE_TYPE_OPAQUE_WIN32 =
            org.lwjgl.opengl.EXTMemoryObjectWin32
                    .GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;

    private static final int GL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32 =
            org.lwjgl.opengl.EXTSemaphoreWin32
                    .GL_HANDLE_TYPE_OPAQUE_WIN32_EXT;

    private static final String GL_VERTEX_SOURCE = """
            #version 330 core
            out vec2 potatoUv;
            void main() {
                vec2 uv = vec2(
                    float((gl_VertexID << 1) & 2),
                    float(gl_VertexID & 2)
                );
                potatoUv = uv;
                gl_Position = vec4(
                    uv * 2.0 - 1.0,
                    0.0,
                    1.0
                );
            }
            """;

    private static final String GL_FRAGMENT_SOURCE = """
            #version 330 core
            uniform sampler2D potatoCanary;
            in vec2 potatoUv;
            out vec4 potatoFragColor;
            void main() {
                /*
                 * Stage 106 presents the real shared image directly.
                 * The image was filled from Minecraft MainTarget by OpenGL,
                 * ownership-transferred through Vulkan, then handed back via
                 * the external semaphore path. No synthetic canary is added.
                 */
                potatoFragColor = texture(
                    potatoCanary,
                    vec2(
                        potatoUv.x,
                        potatoUv.y
                    )
                );
            }
            """;

    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final int graphicsQueueFamilyIndex;
    private final VulkanGate10DynamicRasterPrecommit rasterPrecommit;
    private final JsonObject report;

    /*
     * Patch 144: Gate 10 used to freeze the shared-image extent at first use.
     * If the Vulkan presentation window was later maximized, a native 4K
     * MainTarget was downsampled into the old 854x480 interop image and Gate 11
     * then nearest-upscaled those pixels back to the 4K swapchain. Retain old
     * interop generations until shutdown and rotate to a fresh exact-size
     * generation without a gameplay CPU/GPU wait.
     */
    private final List<RetiredInteropGeneration> retiredInteropGenerations =
            new ArrayList<>();

    private long colorImage =
            NULL;

    private long colorMemory =
            NULL;

    private long colorView =
            NULL;

    private long colorAllocationBytes;

    private long colorWin32Handle =
            NULL;

    private int colorMemoryTypeIndex =
            -1;

    private long depthImage =
            NULL;

    private long depthMemory =
            NULL;

    private long depthView =
            NULL;

    private int depthMemoryTypeIndex =
            -1;

    private long vkReadySemaphore =
            NULL;

    private long glReleasedSemaphore =
            NULL;

    private int glVkReadySemaphore;
    private int glReleasedSemaphoreObject;

    private int glMemoryObject;
    private int glTexture;
    private int glCaptureFramebuffer;
    private int glProgram;
    private int glVao;
    private int glSamplerLocation =
            -1;

    private long commandPool =
            NULL;

    private long submissionFence =
            NULL;

    private VkCommandBuffer commandBuffer;

    private boolean extensionGateEvaluated;
    private boolean extensionGatePassed;
    private boolean initialized;
    private boolean targetLayoutsInitialized;
    private boolean glImported;
    private boolean frameArmed;
    private boolean awaitingComposite;
    private boolean submissionInFlight;
    private boolean proofRetired;
    private boolean disabledAfterFailure;
    private boolean closed;
    private boolean sessionBoundaryPending =
            true;

    private boolean targetEverCreated;
    private boolean semaphoresEverCreated;
    private boolean glImportEverCompleted;
    private boolean canaryEverComposited;
    private boolean runtimeQualificationOffered;
    private boolean gate11QualifiedFramePublished;
    private boolean gate11LiveStreamActive;
    private boolean gate11FrameClaimedAwaitingSubmit;
    private boolean gate11ReturnSemaphorePending;

    private long gate11QualifiedFramePublishCount;
    private long gate11QualifiedFrameSignalCount;
    private long gate11LiveStreamStartNanos;
    private long gate11LiveFrameSequence;
    private long gate11LiveFramePublishCount;
    private long gate11LiveFrameClaimCount;
    private long gate11LiveFrameSubmitAckCount;
    private long gate11LiveReturnGpuWaitCount;
    private long gate11TerminalReturnDrainCount;
    private long gate11LiveBackpressureSkipCount;
    private long gate11DirectInteropPublishCount;
    private long gate11DirectInteropRecoveryRoundTripCount;
    private long gate11LiveRetireCount;
    private long gate11ShutdownUnclaimedTailDiscardCount;
    private long gate11LastClaimedSequence;
    private String gate11QualifiedFrameFailure =
            "";
    private String gate11LiveStreamStopReason =
            "NOT_STARTED";

    private long beginOfferCount;
    private long endOfferCount;
    private long prerequisiteSkipCount;
    private long duplicatePendingSkipCount;
    private long submissionCount;
    private long compositeAttemptCount;
    private long compositeSuccessCount;
    private long vulkanWaitOnGlReleaseCount;
    private long vulkanSignalReadyCount;
    private long glWaitReadyCount;
    private long glSignalReleaseCount;
    private long mainTargetCaptureCount;
    private long realContentVulkanSubmissionCount;
    private long realContentPreviewCount;
    private long captureFramebufferCompleteCount;
    private long fencePollCount;
    private long fenceNotReadyCount;
    private long fenceCompletionCount;
    private long busySkipCount;
    private long failureCount;
    private long shutdownGlFinishCount;
    private long shutdownDeviceWaitIdleCount;

    private long lastBeginOfferNanos;
    private long previewSessionStartNanos;
    private int previewSessionCount;
    private int previewSessionVisibleFrames;
    private long previewSessionGapDetectedCount;
    private long previewSessionRearmCount;
    private long previewSessionLimitSkipCount;
    private long previewSessionCapRejectCount;

    private long explicitMainTargetBindCount;
    private long mainTargetFramebufferChangedCount;
    private int lastPreviousDrawFramebuffer =
            -1;
    private int lastPreviousReadFramebuffer =
            -1;
    private int lastMainTargetDrawFramebuffer =
            -1;
    private int lastMainTargetReadFramebuffer =
            -1;
    private int lastMainTargetViewportWidth;
    private int lastMainTargetViewportHeight;
    private int lastCanaryViewportWidth;
    private int lastCanaryViewportHeight;
    private int lastCapturedMainTargetWidth;
    private int lastCapturedMainTargetHeight;

    private String lastFailure =
            "";

    VulkanGate10VisibleScreenRehearsal(
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            int graphicsQueueFamilyIndex,
            VulkanGate10DynamicRasterPrecommit rasterPrecommit,
            JsonObject report
    ) {
        this.device =
                device;
        this.physicalDevice =
                physicalDevice;
        this.graphicsQueue =
                graphicsQueue;
        this.graphicsQueueFamilyIndex =
                graphicsQueueFamilyIndex;
        this.rasterPrecommit =
                rasterPrecommit;
        this.report =
                report;

        synchronized (VulkanGate10VisibleScreenRehearsal.class) {
            activeInstance = this;
        }

        enrich(
                report
        );
    }

    synchronized void beginFrame() {
        beginOfferCount++;

        frameArmed =
                false;

        long now =
                System.nanoTime();

        if (lastBeginOfferNanos == 0L
                || now - lastBeginOfferNanos
                >= PREVIEW_SESSION_GAP_NANOS) {
            if (lastBeginOfferNanos != 0L) {
                previewSessionGapDetectedCount++;
            }

            sessionBoundaryPending =
                    true;
        }

        lastBeginOfferNanos =
                now;

        boolean liveStreamWindow =
                runtimeQualificationOffered
                        && gate11LiveStreamActive;

        if (!GATE11_PRODUCTION_PRESENTATION_SESSION
                && liveStreamWindow
                && (now - gate11LiveStreamStartNanos
                >= GATE11_LIVE_STREAM_DURATION_NANOS
                || gate11LiveFramePublishCount
                >= GATE11_LIVE_STREAM_MAX_FRAMES)) {
            retireGate11LiveStream(
                    "BOUNDED_LIVE_STREAM_COMPLETE"
            );
            liveStreamWindow = false;
        }

        if (closed
                || disabledAfterFailure
                || (proofRetired && !liveStreamWindow)) {
            enrich(
                    report
            );
            return;
        }

        if (!"Render thread".equals(
                Thread.currentThread().getName()
        )) {
            fail(
                    new VulkanProbeException(
                            "GATE10_SCREEN_CONTENT_THREAD",
                            "Real SCREEN content round-trip was offered off the render thread."
                    )
            );
            return;
        }

        if (!rasterPrecommit
                .readyForVisibleScreenRehearsal()) {
            prerequisiteSkipCount++;

            if (runtimeQualificationOffered) {
                runtimeQualificationOffered =
                        false;

                retireGate11LiveStream(
                        "SCREEN_RASTER_PREREQUISITE_LOST"
                );

                VulkanGate10ImmediatePath
                        .revokeRuntimeGate10(
                                report,
                                "REVOKED_SCREEN_RASTER_PREREQUISITE_LOST"
                        );
            }

            enrich(
                    report
            );
            return;
        }

        if (!liveStreamWindow) {
            if (sessionBoundaryPending
                    || previewSessionCount == 0) {
                if (previewSessionCount >= MAX_PREVIEW_SESSIONS) {
                    previewSessionCapRejectCount++;
                    proofRetired =
                            true;

                    enrich(
                            report
                    );
                    return;
                }

                previewSessionCount++;
                previewSessionStartNanos =
                        now;
                previewSessionVisibleFrames =
                        0;
                previewSessionRearmCount++;
                sessionBoundaryPending =
                        false;
            }
        }

        if (awaitingComposite) {
            duplicatePendingSkipCount++;
            enrich(
                    report
            );
            return;
        }

        if (liveStreamWindow) {
            if (gate11FrameClaimedAwaitingSubmit
                    || hasPublishedGate11Frame()) {
                gate11LiveBackpressureSkipCount++;
                enrich(
                        report
                );
                return;
            }
        } else if (previewSessionVisibleFrames
                >= MAX_VISIBLE_FRAMES_PER_SESSION
                || now - previewSessionStartNanos
                >= PREVIEW_SESSION_DURATION_NANOS) {
            previewSessionLimitSkipCount++;
            enrich(
                    report
            );
            return;
        }

        try {
            ensureInitialized();
            reclaimCompletedInteropGenerations();

            if (liveStreamWindow
                    && gate11ReturnSemaphorePending) {
                waitForGate11ReturnSemaphore();
            }

            if (!pollSubmissionFence()) {
                busySkipCount++;
                enrich(
                        report
                );
                return;
            }

            frameArmed =
                    true;
        } catch (Throwable throwable) {
            fail(
                    throwable
            );
        }

        enrich(
                report
        );
    }

    synchronized void endFrame() {
        endOfferCount++;

        if (closed
                || disabledAfterFailure
                || proofRetired
                || !frameArmed) {
            frameArmed =
                    false;
            enrich(
                    report
            );
            return;
        }

        frameArmed =
                false;

        try {
            boolean qualifiedBeforeFrame =
                    runtimeQualificationOffered;

            /*
             * Patch 144: resolve the exact MainTarget extent at the last safe
             * point before capture. This also covers a resize/maximize that
             * lands between beginFrame() and endFrame().
             */
            if (!ensureNativeResolutionInteropTarget()) {
                enrich(
                        report
                );
                return;
            }

            captureMainTargetIntoSharedImage();

            /*
             * Patch 144 production stream:
             *
             * Once Gate 10 is already qualified, the capture's GL release
             * semaphore is itself the exact producer-ready signal Gate 11
             * needs. Do not run the historical no-op Vulkan rehearsal and then
             * draw the same full-screen texture back into the hidden OpenGL
             * MainTarget on every frame.
             *
             * Gate 11 consumes glReleasedSemaphore directly, performs the
             * presentation blit, and signals vkReadySemaphore. The next
             * beginFrame() enqueues the existing GPU-side GL wait before the
             * shared image is written again.
             */
            if (qualifiedBeforeFrame
                    && gate11LiveStreamActive) {
                if (publishCapturedLiveFrameForGate11()) {
                    gate11DirectInteropPublishCount++;
                    enrich(
                            report
                    );
                    return;
                }

                /*
                 * A capture has already released the image. If the direct
                 * publication cannot be installed, consume that signal through
                 * the already-proven rehearsal round-trip so ownership returns
                 * to GL instead of leaving the binary semaphore/image stranded.
                 */
                gate11DirectInteropRecoveryRoundTripCount++;
            }

            submitVisibleCanary();

            if (enqueueOpenGlComposite()) {
                awaitingComposite =
                        false;
                compositeSuccessCount++;
                realContentPreviewCount++;
                previewSessionVisibleFrames++;
                canaryEverComposited =
                        true;

                offerRuntimeQualificationIfReady();
            }
        } catch (Throwable throwable) {
            fail(
                    throwable
            );
        }

        enrich(
                report
        );
    }

    private boolean publishQualifiedFrameForGate11() {
        if (gate11QualifiedFramePublished) {
            return true;
        }

        if (!publishFrameForGate11()) {
            return false;
        }

        gate11QualifiedFramePublished =
                true;
        gate11QualifiedFramePublishCount++;

        /*
         * Patch 119: the first qualified frame is also the first frame consumed
         * by the live Gate-11 stream. Claims and submit acknowledgements have
         * always counted it; producer publication accounting must count the
         * same ownership domain instead of starting one frame later.
         */
        gate11LiveFramePublishCount++;
        return true;
    }

    private boolean publishCapturedLiveFrameForGate11() {
        if (!gate11LiveStreamActive
                || !initialized
                || !glImported
                || colorImage == NULL
                || glReleasedSemaphore == NULL
                || vkReadySemaphore == NULL
                || glTexture == 0
                || targetWidth <= 0
                || targetHeight <= 0) {
            gate11QualifiedFrameFailure =
                    "DIRECT_LIVE_FRAME_RESOURCES_INCOMPLETE";
            return false;
        }

        synchronized (VulkanGate10VisibleScreenRehearsal.class) {
            if (publishedQualifiedFrame != null) {
                gate11LiveBackpressureSkipCount++;
                return false;
            }
        }

        if (gate11FrameClaimedAwaitingSubmit
                || gate11ReturnSemaphorePending) {
            gate11LiveBackpressureSkipCount++;
            return false;
        }

        long sequence =
                ++gate11LiveFrameSequence;

        synchronized (VulkanGate10VisibleScreenRehearsal.class) {
            publishedQualifiedFrame = new QualifiedFrame(
                    colorImage,
                    glReleasedSemaphore,
                    vkReadySemaphore,
                    targetWidth,
                    targetHeight,
                    COLOR_FORMAT,
                    sequence
            );
        }

        gate11LiveFramePublishCount++;
        return true;
    }

    private boolean publishFrameForGate11() {
        if (!initialized
                || !glImported
                || colorImage == NULL
                || glReleasedSemaphore == NULL
                || vkReadySemaphore == NULL
                || glReleasedSemaphoreObject == 0
                || glVkReadySemaphore == 0
                || glTexture == 0
                || targetWidth <= 0
                || targetHeight <= 0) {
            gate11QualifiedFrameFailure =
                    "QUALIFIED_FRAME_RESOURCES_INCOMPLETE";
            return false;
        }

        synchronized (VulkanGate10VisibleScreenRehearsal.class) {
            if (publishedQualifiedFrame != null) {
                return false;
            }
        }

        if (gate11FrameClaimedAwaitingSubmit
                || gate11ReturnSemaphorePending) {
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer textures = stack.ints(glTexture);
            IntBuffer bufferBarriers = stack.mallocInt(1);
            bufferBarriers.limit(0);
            IntBuffer layouts = stack.ints(GL_LAYOUT_COLOR_ATTACHMENT_EXT);

            glSignalSemaphoreEXT(
                    glReleasedSemaphoreObject,
                    bufferBarriers,
                    textures,
                    layouts
            );

            validateGl("SIGNAL_REAL_CONTENT_TO_GATE11");
            gate11QualifiedFrameSignalCount++;

            long sequence =
                    ++gate11LiveFrameSequence;

            synchronized (VulkanGate10VisibleScreenRehearsal.class) {
                publishedQualifiedFrame = new QualifiedFrame(
                        colorImage,
                        glReleasedSemaphore,
                        vkReadySemaphore,
                        targetWidth,
                        targetHeight,
                        COLOR_FORMAT,
                        sequence
                );
            }

            return true;
        } catch (Throwable throwable) {
            gate11QualifiedFrameFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(throwable.getMessage());
            return false;
        }
    }

    static synchronized QualifiedFrame claimQualifiedFrameForGate11() {
        QualifiedFrame frame = publishedQualifiedFrame;
        publishedQualifiedFrame = null;

        VulkanGate10VisibleScreenRehearsal instance =
                activeInstance;

        if (frame != null
                && instance != null) {
            instance.onGate11FrameClaimed(
                    frame.sequence()
            );
        }

        return frame;
    }

    static synchronized boolean acknowledgeGate11FrameSubmitted(
            long sequence
    ) {
        VulkanGate10VisibleScreenRehearsal instance =
                activeInstance;

        return instance != null
                && instance.onGate11FrameSubmitted(
                sequence
        );
    }

    static synchronized void rejectGate11Frame(
            long sequence,
            String reason
    ) {
        VulkanGate10VisibleScreenRehearsal instance =
                activeInstance;

        if (instance != null) {
            instance.onGate11FrameRejected(
                    sequence,
                    reason
            );
        }
    }

    static synchronized void stopGate11LiveStream(
            String reason
    ) {
        VulkanGate10VisibleScreenRehearsal instance =
                activeInstance;

        if (instance != null) {
            instance.retireGate11LiveStream(
                    reason
            );
        }
    }

    private synchronized void onGate11FrameClaimed(
            long sequence
    ) {
        if (sequence <= 0L
                || sequence != gate11LiveFrameSequence) {
            return;
        }

        gate11LastClaimedSequence =
                sequence;
        gate11FrameClaimedAwaitingSubmit =
                true;
        gate11LiveFrameClaimCount++;
    }

    private synchronized boolean onGate11FrameSubmitted(
            long sequence
    ) {
        if (sequence <= 0L
                || sequence != gate11LastClaimedSequence
                || !gate11FrameClaimedAwaitingSubmit) {
            return false;
        }

        gate11FrameClaimedAwaitingSubmit =
                false;
        gate11ReturnSemaphorePending =
                true;
        gate11LiveFrameSubmitAckCount++;
        return true;
    }

    private synchronized void onGate11FrameRejected(
            long sequence,
            String reason
    ) {
        if (sequence == gate11LastClaimedSequence) {
            gate11FrameClaimedAwaitingSubmit =
                    false;
        }

        retireGate11LiveStream(
                reason == null || reason.isBlank()
                        ? "GATE11_FRAME_REJECTED"
                        : reason
        );
    }

    private boolean hasPublishedGate11Frame() {
        synchronized (VulkanGate10VisibleScreenRehearsal.class) {
            return publishedQualifiedFrame != null;
        }
    }

    private void waitForGate11ReturnSemaphore() {
        if (!gate11ReturnSemaphorePending) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer textures = stack.ints(glTexture);
            IntBuffer bufferBarriers = stack.mallocInt(1);
            bufferBarriers.limit(0);
            IntBuffer layouts = stack.ints(GL_LAYOUT_COLOR_ATTACHMENT_EXT);

            glWaitSemaphoreEXT(
                    glVkReadySemaphore,
                    bufferBarriers,
                    textures,
                    layouts
            );

            validateGl("WAIT_GATE11_RETURN_SEMAPHORE");
            gate11ReturnSemaphorePending =
                    false;
            gate11LiveReturnGpuWaitCount++;
        }
    }

    private void retireGate11LiveStream(
            String reason
    ) {
        if (gate11LiveStreamActive) {
            gate11LiveRetireCount++;
        }

        /*
         * Patch 119: the bounded stream may stop immediately after Gate 11 has
         * acknowledged the final Vulkan submission. In that state the GL <-
         * Vulkan return semaphore is valid but no next beginFrame exists to
         * consume it. Enqueue the already-proven GPU-side semaphore wait here
         * on the render thread so shutdown ends with zero outstanding image
         * ownership. This is NOT a CPU fence/queue/device wait.
         */
        if (gate11ReturnSemaphorePending
                && "Render thread".equals(
                Thread.currentThread().getName()
        )) {
            waitForGate11ReturnSemaphore();
            gate11TerminalReturnDrainCount++;
        }

        gate11LiveStreamActive =
                false;
        proofRetired =
                runtimeQualificationOffered;
        gate11LiveStreamStopReason =
                reason == null || reason.isBlank()
                        ? "RETIRED"
                        : reason;
    }

    private void offerRuntimeQualificationIfReady() {
        if (runtimeQualificationOffered
                || disabledAfterFailure
                || failureCount != 0L
                || !extensionGatePassed
                || !targetEverCreated
                || !semaphoresEverCreated
                || !glImportEverCompleted
                || realContentPreviewCount
                < QUALIFICATION_MIN_FULL_FRAME_HANDOFFS
                || previewSessionCount
                < QUALIFICATION_MIN_SCREEN_SESSIONS
                || mainTargetCaptureCount
                != realContentVulkanSubmissionCount
                || realContentVulkanSubmissionCount
                != realContentPreviewCount
                || glWaitReadyCount
                != realContentPreviewCount
                || glSignalReleaseCount
                != realContentPreviewCount
                || vulkanWaitOnGlReleaseCount
                != realContentPreviewCount
                || vulkanSignalReadyCount
                != realContentPreviewCount
                || lastCapturedMainTargetWidth <= 0
                || lastCapturedMainTargetHeight <= 0
                || lastCanaryViewportWidth <= 0
                || lastCanaryViewportHeight <= 0
                || !rasterPrecommit
                .readyForVisibleScreenRehearsal()) {
            return;
        }

        if (!publishQualifiedFrameForGate11()) {
            return;
        }

        runtimeQualificationOffered =
                true;
        proofRetired =
                false;

        VulkanGate10ImmediatePath
                .qualifyRuntimeGate10(
                        report,
                        realContentPreviewCount,
                        previewSessionCount
                );

        if (!disabledAfterFailure) {
            gate11LiveStreamActive =
                    true;
            gate11LiveStreamStartNanos =
                    System.nanoTime();
            gate11LiveStreamStopReason =
                    GATE11_PRODUCTION_PRESENTATION_SESSION
                            ? "ACTIVE_PRODUCTION_VULKAN_PRESENTATION_SESSION"
                            : "ACTIVE_BOUNDED_LIVE_UI_STREAM";
        }
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }

        if (!checkExtensionGate()) {
            throw new VulkanProbeException(
                    "GATE10_VISIBLE_REHEARSAL_EXTENSION_GATE",
                    "Vulkan/OpenGL Win32 external memory and semaphore support is unavailable."
            );
        }

        selectFullResolutionTarget();
        createTargets();
        createExternalSemaphores();
        importIntoOpenGl();
        ensureCompositeProgram();
        createCommandState();

        initialized =
                true;

        enrich(
                report
        );
    }

    private void selectFullResolutionTarget() {
        int selectedWidth =
                0;

        int selectedHeight =
                0;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            Minecraft minecraft =
                    Minecraft.getInstance();

            if (minecraft != null
                    && minecraft.getWindow() != null) {
                long window =
                        minecraft.getWindow().getWindow();

                if (window != NULL) {
                    IntBuffer width =
                            stack.ints(0);

                    IntBuffer height =
                            stack.ints(0);

                    org.lwjgl.glfw.GLFW.glfwGetFramebufferSize(
                            window,
                            width,
                            height
                    );

                    selectedWidth =
                            width.get(0);

                    selectedHeight =
                            height.get(0);
                }
            }

            if (selectedWidth <= 0
                    || selectedHeight <= 0) {
                IntBuffer viewport =
                        stack.mallocInt(4);

                glGetIntegerv(
                        GL_VIEWPORT,
                        viewport
                );

                selectedWidth =
                        viewport.get(2);

                selectedHeight =
                        viewport.get(3);
            }
        }

        if (selectedWidth <= 0
                || selectedHeight <= 0) {
            selectedWidth =
                    FALLBACK_TARGET_WIDTH;

            selectedHeight =
                    FALLBACK_TARGET_HEIGHT;
        }

        targetWidth =
                Math.max(1, selectedWidth);

        targetHeight =
                Math.max(1, selectedHeight);

        targetSelectionFramebufferWidth =
                selectedWidth;

        targetSelectionFramebufferHeight =
                selectedHeight;

        fullResolutionTargetSelected =
                targetWidth == selectedWidth
                        && targetHeight == selectedHeight
                        && selectedWidth > 0
                        && selectedHeight > 0;
    }

    private boolean checkExtensionGate() {
        extensionGateEvaluated =
                true;

        if (extensionGatePassed) {
            return true;
        }

        if (!System.getProperty(
                "os.name",
                ""
        ).toLowerCase().contains(
                "windows"
        )) {
            report.addProperty(
                    "gate10VisibleScreenRehearsalPlatformSupported",
                    false
            );
            return false;
        }

        boolean deviceExtensionsEnabled =
                report.has(
                        "vulkanOpenGlWin32InteropDeviceExtensionsEnabled"
                )
                        && report.get(
                        "vulkanOpenGlWin32InteropDeviceExtensionsEnabled"
                ).getAsBoolean();

        if (!deviceExtensionsEnabled) {
            return false;
        }

        GLCapabilities capabilities =
                GL.getCapabilities();

        extensionGatePassed =
                capabilities.GL_EXT_memory_object
                        && capabilities.GL_EXT_memory_object_win32
                        && capabilities.GL_EXT_semaphore
                        && capabilities.GL_EXT_semaphore_win32;

        report.addProperty(
                "gate10VisibleScreenRehearsalPlatformSupported",
                extensionGatePassed
        );

        return extensionGatePassed;
    }

    private void createTargets() {
        ExternalImageAllocation color =
                createExternalColorImage();

        colorImage =
                color.image();
        colorMemory =
                color.memory();
        colorView =
                color.view();
        colorAllocationBytes =
                color.allocationBytes();
        colorWin32Handle =
                color.win32Handle();
        colorMemoryTypeIndex =
                color.memoryTypeIndex();

        ImageAllocation depth =
                createPrivateDepthImage();

        depthImage =
                depth.image();
        depthMemory =
                depth.memory();
        depthView =
                depth.view();
        depthMemoryTypeIndex =
                depth.memoryTypeIndex();

        targetEverCreated =
                colorImage != NULL
                        && colorMemory != NULL
                        && colorView != NULL
                        && depthImage != NULL
                        && depthMemory != NULL
                        && depthView != NULL;
    }

    private ExternalImageAllocation createExternalColorImage() {
        long image =
                NULL;
        long memory =
                NULL;
        long view =
                NULL;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkExternalMemoryImageCreateInfo externalInfo =
                    VkExternalMemoryImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .handleTypes(
                                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                            );

            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .pNext(
                                    externalInfo.address()
                            )
                            .imageType(
                                    VK_IMAGE_TYPE_2D
                            )
                            .format(
                                    COLOR_FORMAT
                            )
                            .mipLevels(
                                    1
                            )
                            .arrayLayers(
                                    1
                            )
                            .samples(
                                    VK_SAMPLE_COUNT_1_BIT
                            )
                            .tiling(
                                    VK_IMAGE_TILING_OPTIMAL
                            )
                            .usage(
                                    VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
                                            | VK_IMAGE_USAGE_SAMPLED_BIT
                                            | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
                            )
                            .sharingMode(
                                    VK_SHARING_MODE_EXCLUSIVE
                            )
                            .initialLayout(
                                    VK_IMAGE_LAYOUT_UNDEFINED
                            );

            imageInfo.extent()
                    .width(
                            targetWidth
                    )
                    .height(
                            targetHeight
                    )
                    .depth(
                            1
                    );

            LongBuffer imagePointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateImage(
                            device,
                            imageInfo,
                            null,
                            imagePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_CREATE_COLOR_IMAGE",
                        "vkCreateImage failed with VkResult "
                                + result
                );
            }

            image =
                    imagePointer.get(
                            0
                    );

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(
                            stack
                    );

            vkGetImageMemoryRequirements(
                    device,
                    image,
                    requirements
            );

            int memoryTypeIndex =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    );

            VkMemoryDedicatedAllocateInfo dedicated =
                    VkMemoryDedicatedAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .image(
                                    image
                            );

            VkExportMemoryAllocateInfo export =
                    VkExportMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .pNext(
                                    dedicated.address()
                            )
                            .handleTypes(
                                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                            );

            VkMemoryAllocateInfo allocation =
                    VkMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .pNext(
                                    export.address()
                            )
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    memoryTypeIndex
                            );

            LongBuffer memoryPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkAllocateMemory(
                            device,
                            allocation,
                            null,
                            memoryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_ALLOCATE_COLOR_MEMORY",
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            memory =
                    memoryPointer.get(
                            0
                    );

            result =
                    vkBindImageMemory(
                            device,
                            image,
                            memory,
                            0L
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_BIND_COLOR_MEMORY",
                        "vkBindImageMemory failed with VkResult "
                                + result
                );
            }

            view =
                    createImageView(
                            image,
                            COLOR_FORMAT,
                            VK_IMAGE_ASPECT_COLOR_BIT,
                            stack,
                            "COLOR"
                    );

            VkMemoryGetWin32HandleInfoKHR handleInfo =
                    VkMemoryGetWin32HandleInfoKHR.calloc(
                            stack
                    )
                            .sType$Default()
                            .memory(
                                    memory
                            )
                            .handleType(
                                    VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_WIN32_BIT
                            );

            PointerBuffer handlePointer =
                    stack.mallocPointer(
                            1
                    );

            result =
                    vkGetMemoryWin32HandleKHR(
                            device,
                            handleInfo,
                            handlePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_EXPORT_COLOR_HANDLE",
                        "vkGetMemoryWin32HandleKHR failed with VkResult "
                                + result
                );
            }

            long win32Handle =
                    handlePointer.get(
                            0
                    );

            if (win32Handle == NULL) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_EXPORT_COLOR_HANDLE",
                        "Vulkan returned a null Win32 color-memory handle."
                );
            }

            return new ExternalImageAllocation(
                    image,
                    memory,
                    view,
                    requirements.size(),
                    win32Handle,
                    memoryTypeIndex
            );
        } catch (Throwable throwable) {
            if (view != NULL) {
                vkDestroyImageView(
                        device,
                        view,
                        null
                );
            }

            if (image != NULL) {
                vkDestroyImage(
                        device,
                        image,
                        null
                );
            }

            if (memory != NULL) {
                vkFreeMemory(
                        device,
                        memory,
                        null
                );
            }

            throw throwable;
        }
    }

    private ImageAllocation createPrivateDepthImage() {
        long image =
                NULL;
        long memory =
                NULL;
        long view =
                NULL;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkImageCreateInfo imageInfo =
                    VkImageCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .imageType(
                                    VK_IMAGE_TYPE_2D
                            )
                            .format(
                                    DEPTH_FORMAT
                            )
                            .mipLevels(
                                    1
                            )
                            .arrayLayers(
                                    1
                            )
                            .samples(
                                    VK_SAMPLE_COUNT_1_BIT
                            )
                            .tiling(
                                    VK_IMAGE_TILING_OPTIMAL
                            )
                            .usage(
                                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT
                            )
                            .sharingMode(
                                    VK_SHARING_MODE_EXCLUSIVE
                            )
                            .initialLayout(
                                    VK_IMAGE_LAYOUT_UNDEFINED
                            );

            imageInfo.extent()
                    .width(
                            targetWidth
                    )
                    .height(
                            targetHeight
                    )
                    .depth(
                            1
                    );

            LongBuffer imagePointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateImage(
                            device,
                            imageInfo,
                            null,
                            imagePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_CREATE_DEPTH_IMAGE",
                        "vkCreateImage failed with VkResult "
                                + result
                );
            }

            image =
                    imagePointer.get(
                            0
                    );

            VkMemoryRequirements requirements =
                    VkMemoryRequirements.malloc(
                            stack
                    );

            vkGetImageMemoryRequirements(
                    device,
                    image,
                    requirements
            );

            int memoryTypeIndex =
                    findMemoryType(
                            requirements.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
                    );

            VkMemoryAllocateInfo allocation =
                    VkMemoryAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .allocationSize(
                                    requirements.size()
                            )
                            .memoryTypeIndex(
                                    memoryTypeIndex
                            );

            LongBuffer memoryPointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkAllocateMemory(
                            device,
                            allocation,
                            null,
                            memoryPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_ALLOCATE_DEPTH_MEMORY",
                        "vkAllocateMemory failed with VkResult "
                                + result
                );
            }

            memory =
                    memoryPointer.get(
                            0
                    );

            result =
                    vkBindImageMemory(
                            device,
                            image,
                            memory,
                            0L
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_BIND_DEPTH_MEMORY",
                        "vkBindImageMemory failed with VkResult "
                                + result
                );
            }

            view =
                    createImageView(
                            image,
                            DEPTH_FORMAT,
                            VK_IMAGE_ASPECT_DEPTH_BIT,
                            stack,
                            "DEPTH"
                    );

            return new ImageAllocation(
                    image,
                    memory,
                    view,
                    memoryTypeIndex
            );
        } catch (Throwable throwable) {
            if (view != NULL) {
                vkDestroyImageView(
                        device,
                        view,
                        null
                );
            }

            if (image != NULL) {
                vkDestroyImage(
                        device,
                        image,
                        null
                );
            }

            if (memory != NULL) {
                vkFreeMemory(
                        device,
                        memory,
                        null
                );
            }

            throw throwable;
        }
    }

    private long createImageView(
            long image,
            int format,
            int aspectMask,
            MemoryStack stack,
            String label
    ) {
        VkImageViewCreateInfo viewInfo =
                VkImageViewCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .image(
                                image
                        )
                        .viewType(
                                VK_IMAGE_VIEW_TYPE_2D
                        )
                        .format(
                                format
                        );

        viewInfo.subresourceRange()
                .aspectMask(
                        aspectMask
                )
                .baseMipLevel(
                        0
                )
                .levelCount(
                        1
                )
                .baseArrayLayer(
                        0
                )
                .layerCount(
                        1
                );

        LongBuffer pointer =
                stack.mallocLong(
                        1
                );

        int result =
                vkCreateImageView(
                        device,
                        viewInfo,
                        null,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "GATE10_VISIBLE_CREATE_" + label + "_VIEW",
                    "vkCreateImageView failed with VkResult "
                            + result
            );
        }

        return pointer.get(
                0
        );
    }

    private int findMemoryType(
            int memoryTypeBits,
            int requiredFlags
    ) {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkPhysicalDeviceMemoryProperties properties =
                    VkPhysicalDeviceMemoryProperties.malloc(
                            stack
                    );

            vkGetPhysicalDeviceMemoryProperties(
                    physicalDevice,
                    properties
            );

            for (int index = 0;
                 index < properties.memoryTypeCount();
                 index++) {
                boolean supported =
                        (memoryTypeBits & (1 << index)) != 0;

                int flags =
                        properties.memoryTypes(
                                index
                        ).propertyFlags();

                if (supported
                        && (flags & requiredFlags)
                        == requiredFlags) {
                    return index;
                }
            }
        }

        throw new VulkanProbeException(
                "GATE10_VISIBLE_MEMORY_TYPE",
                "No compatible DEVICE_LOCAL memory type was found."
        );
    }

    private void createExternalSemaphores() {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            vkReadySemaphore =
                    createExportableSemaphore(
                            stack
                    );

            glReleasedSemaphore =
                    createExportableSemaphore(
                            stack
                    );

            long vkReadyHandle =
                    exportSemaphoreHandle(
                            vkReadySemaphore,
                            stack
                    );

            long glReleasedHandle =
                    exportSemaphoreHandle(
                            glReleasedSemaphore,
                            stack
                    );

            glVkReadySemaphore =
                    glGenSemaphoresEXT();

            glReleasedSemaphoreObject =
                    glGenSemaphoresEXT();

            glImportSemaphoreWin32HandleEXT(
                    glVkReadySemaphore,
                    GL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32,
                    vkReadyHandle
            );

            validateGl(
                    "IMPORT_VK_READY_SEMAPHORE"
            );

            glImportSemaphoreWin32HandleEXT(
                    glReleasedSemaphoreObject,
                    GL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32,
                    glReleasedHandle
            );

            validateGl(
                    "IMPORT_GL_RELEASED_SEMAPHORE"
            );

            semaphoresEverCreated =
                    vkReadySemaphore != NULL
                            && glReleasedSemaphore != NULL
                            && glVkReadySemaphore != 0
                            && glReleasedSemaphoreObject != 0;
        }
    }

    private long createExportableSemaphore(
            MemoryStack stack
    ) {
        VkExportSemaphoreCreateInfo export =
                VkExportSemaphoreCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .handleTypes(
                                VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
                        );

        VkSemaphoreCreateInfo info =
                VkSemaphoreCreateInfo.calloc(
                        stack
                )
                        .sType$Default()
                        .pNext(
                                export.address()
                        );

        LongBuffer pointer =
                stack.mallocLong(
                        1
                );

        int result =
                vkCreateSemaphore(
                        device,
                        info,
                        null,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "GATE10_VISIBLE_CREATE_SEMAPHORE",
                    "vkCreateSemaphore failed with VkResult "
                            + result
            );
        }

        return pointer.get(
                0
        );
    }

    private long exportSemaphoreHandle(
            long semaphore,
            MemoryStack stack
    ) {
        VkSemaphoreGetWin32HandleInfoKHR handleInfo =
                VkSemaphoreGetWin32HandleInfoKHR.calloc(
                        stack
                )
                        .sType$Default()
                        .semaphore(
                                semaphore
                        )
                        .handleType(
                                VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_WIN32_BIT
                        );

        PointerBuffer pointer =
                stack.mallocPointer(
                        1
                );

        int result =
                vkGetSemaphoreWin32HandleKHR(
                        device,
                        handleInfo,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "GATE10_VISIBLE_EXPORT_SEMAPHORE",
                    "vkGetSemaphoreWin32HandleKHR failed with VkResult "
                            + result
            );
        }

        long handle =
                pointer.get(
                        0
                );

        if (handle == NULL) {
            throw new VulkanProbeException(
                    "GATE10_VISIBLE_EXPORT_SEMAPHORE",
                    "Vulkan returned a null Win32 semaphore handle."
            );
        }

        return handle;
    }

    private void importIntoOpenGl() {
        int previousActiveTexture =
                glGetInteger(
                        GL_ACTIVE_TEXTURE
                );

        int previousTexture =
                glGetInteger(
                        GL_TEXTURE_BINDING_2D
                );

        int previousDrawFramebuffer =
                glGetInteger(
                        GL_DRAW_FRAMEBUFFER_BINDING
                );

        int previousReadFramebuffer =
                glGetInteger(
                        GL_READ_FRAMEBUFFER_BINDING
                );

        glActiveTexture(
                GL_TEXTURE0
        );

        glMemoryObject =
                glCreateMemoryObjectsEXT();

        glMemoryObjectParameteriEXT(
                glMemoryObject,
                GL_DEDICATED_MEMORY_OBJECT_EXT,
                GL_TRUE
        );

        glImportMemoryWin32HandleEXT(
                glMemoryObject,
                colorAllocationBytes,
                GL_HANDLE_TYPE_OPAQUE_WIN32,
                colorWin32Handle
        );

        validateGl(
                "IMPORT_COLOR_MEMORY"
        );

        glTexture =
                glGenTextures();

        glBindTexture(
                GL_TEXTURE_2D,
                glTexture
        );

        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_TILING_EXT,
                GL_OPTIMAL_TILING_EXT
        );

        glTexStorageMem2DEXT(
                GL_TEXTURE_2D,
                1,
                GL_RGBA8,
                targetWidth,
                targetHeight,
                glMemoryObject,
                0L
        );

        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MIN_FILTER,
                GL_LINEAR
        );

        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_MAG_FILTER,
                GL_LINEAR
        );

        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_WRAP_S,
                GL_CLAMP_TO_EDGE
        );

        glTexParameteri(
                GL_TEXTURE_2D,
                GL_TEXTURE_WRAP_T,
                GL_CLAMP_TO_EDGE
        );

        glCaptureFramebuffer =
                glGenFramebuffers();

        glBindFramebuffer(
                GL_DRAW_FRAMEBUFFER,
                glCaptureFramebuffer
        );

        glFramebufferTexture2D(
                GL_DRAW_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D,
                glTexture,
                0
        );

        int status =
                glCheckFramebufferStatus(
                        GL_DRAW_FRAMEBUFFER
                );

        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new VulkanProbeException(
                    "GATE10_SCREEN_CONTENT_CAPTURE_FBO",
                    "Imported shared-image capture framebuffer is incomplete: "
                            + status
            );
        }

        captureFramebufferCompleteCount++;

        validateGl(
                "CREATE_IMPORTED_CONTENT_TARGET"
        );

        glBindFramebuffer(
                GL_READ_FRAMEBUFFER,
                previousReadFramebuffer
        );

        glBindFramebuffer(
                GL_DRAW_FRAMEBUFFER,
                previousDrawFramebuffer
        );

        glBindTexture(
                GL_TEXTURE_2D,
                previousTexture
        );

        glActiveTexture(
                previousActiveTexture
        );

        glImported =
                glMemoryObject != 0
                        && glTexture != 0
                        && glCaptureFramebuffer != 0;

        glImportEverCompleted |=
                glImported;
    }

    private void ensureCompositeProgram() {
        if (glProgram != 0
                && glVao != 0) {
            return;
        }

        int vertex =
                compileGlShader(
                        GL_VERTEX_SHADER,
                        GL_VERTEX_SOURCE
                );

        int fragment =
                0;

        try {
            fragment =
                    compileGlShader(
                            GL_FRAGMENT_SHADER,
                            GL_FRAGMENT_SOURCE
                    );

            glProgram =
                    glCreateProgram();

            glAttachShader(
                    glProgram,
                    vertex
            );

            glAttachShader(
                    glProgram,
                    fragment
            );

            glLinkProgram(
                    glProgram
            );

            if (glGetProgrami(
                    glProgram,
                    GL_LINK_STATUS
            ) == GL_FALSE) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_GL_LINK",
                        glGetProgramInfoLog(
                                glProgram
                        )
                );
            }

            glSamplerLocation =
                    glGetUniformLocation(
                            glProgram,
                            "potatoCanary"
                    );

            glVao =
                    glGenVertexArrays();

            validateGl(
                    "CREATE_COMPOSITE_PROGRAM"
            );
        } finally {
            if (fragment != 0) {
                glDeleteShader(
                        fragment
                );
            }

            glDeleteShader(
                    vertex
            );
        }
    }

    private int compileGlShader(
            int type,
            String source
    ) {
        int shader =
                glCreateShader(
                        type
                );

        glShaderSource(
                shader,
                source
        );

        glCompileShader(
                shader
        );

        if (glGetShaderi(
                shader,
                GL_COMPILE_STATUS
        ) == GL_FALSE) {
            String log =
                    glGetShaderInfoLog(
                            shader
                    );

            glDeleteShader(
                    shader
            );

            throw new VulkanProbeException(
                    "GATE10_VISIBLE_GL_SHADER",
                    log
            );
        }

        return shader;
    }

    private void createCommandState() {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            VkCommandPoolCreateInfo poolInfo =
                    VkCommandPoolCreateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                            )
                            .queueFamilyIndex(
                                    graphicsQueueFamilyIndex
                            );

            LongBuffer poolPointer =
                    stack.mallocLong(
                            1
                    );

            int result =
                    vkCreateCommandPool(
                            device,
                            poolInfo,
                            null,
                            poolPointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_COMMAND_POOL",
                        "vkCreateCommandPool failed with VkResult "
                                + result
                );
            }

            commandPool =
                    poolPointer.get(
                            0
                    );

            VkCommandBufferAllocateInfo allocate =
                    VkCommandBufferAllocateInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .commandPool(
                                    commandPool
                            )
                            .level(
                                    VK_COMMAND_BUFFER_LEVEL_PRIMARY
                            )
                            .commandBufferCount(
                                    1
                            );

            PointerBuffer pointer =
                    stack.mallocPointer(
                            1
                    );

            result =
                    vkAllocateCommandBuffers(
                            device,
                            allocate,
                            pointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_COMMAND_BUFFER",
                        "vkAllocateCommandBuffers failed with VkResult "
                                + result
                );
            }

            commandBuffer =
                    new VkCommandBuffer(
                            pointer.get(
                                    0
                            ),
                            device
                    );

            VkFenceCreateInfo fenceInfo =
                    VkFenceCreateInfo.calloc(
                            stack
                    )
                            .sType$Default();

            LongBuffer fencePointer =
                    stack.mallocLong(
                            1
                    );

            result =
                    vkCreateFence(
                            device,
                            fenceInfo,
                            null,
                            fencePointer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_SCREEN_CONTENT_FENCE",
                        "vkCreateFence failed with VkResult "
                                + result
                );
            }

            submissionFence =
                    fencePointer.get(
                            0
                    );
        }
    }

    private boolean pollSubmissionFence() {
        if (!submissionInFlight) {
            return true;
        }

        fencePollCount++;

        int status =
                vkGetFenceStatus(
                        device,
                        submissionFence
                );

        if (status == VK_NOT_READY) {
            fenceNotReadyCount++;
            return false;
        }

        if (status != VK_SUCCESS) {
            throw new VulkanProbeException(
                    "GATE10_SCREEN_CONTENT_FENCE_STATUS",
                    "vkGetFenceStatus failed with VkResult "
                            + status
            );
        }

        fenceCompletionCount++;
        submissionInFlight =
                false;

        return true;
    }

    private boolean ensureNativeResolutionInteropTarget() {
        nativeResolutionResizeCheckCount++;

        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft == null
                || minecraft.getMainRenderTarget() == null) {
            nativeResolutionResizeState =
                    "MAIN_TARGET_UNAVAILABLE";
            return false;
        }

        int requestedWidth =
                minecraft.getMainRenderTarget().viewWidth > 0
                        ? minecraft.getMainRenderTarget().viewWidth
                        : minecraft.getMainRenderTarget().width;

        int requestedHeight =
                minecraft.getMainRenderTarget().viewHeight > 0
                        ? minecraft.getMainRenderTarget().viewHeight
                        : minecraft.getMainRenderTarget().height;

        requestedWidth =
                Math.max(
                        1,
                        requestedWidth
                );

        requestedHeight =
                Math.max(
                        1,
                        requestedHeight
                );

        nativeResolutionLastRequestedWidth =
                requestedWidth;

        nativeResolutionLastRequestedHeight =
                requestedHeight;

        if (requestedWidth == targetWidth
                && requestedHeight == targetHeight) {
            nativeResolutionPendingWidth =
                    0;
            nativeResolutionPendingHeight =
                    0;
            nativeResolutionPendingStableObservationCount =
                    0;
            nativeResolutionResizeState =
                    "EXACT_MAIN_TARGET_EXTENT";
            return true;
        }

        if (requestedWidth != nativeResolutionPendingWidth
                || requestedHeight != nativeResolutionPendingHeight) {
            nativeResolutionPendingWidth =
                    requestedWidth;
            nativeResolutionPendingHeight =
                    requestedHeight;
            nativeResolutionPendingStableObservationCount =
                    1;
            nativeResolutionResizeDeferralCount++;
            nativeResolutionResizeState =
                    "WAITING_FOR_STABLE_MAIN_TARGET_EXTENT";
            return false;
        }

        nativeResolutionPendingStableObservationCount++;

        if (nativeResolutionPendingStableObservationCount
                < NATIVE_RESOLUTION_REQUIRED_STABLE_OBSERVATIONS) {
            nativeResolutionResizeDeferralCount++;
            nativeResolutionResizeState =
                    "WAITING_FOR_STABLE_MAIN_TARGET_EXTENT";
            return false;
        }

        nativeResolutionResizeAttemptCount++;

        /*
         * Never rotate ownership while the active generation still has an
         * unconsumed producer frame. beginFrame() has already polled the Gate-10
         * submission fence and enqueued any pending Gate-11 return semaphore
         * wait before endFrame() reaches this point.
         */
        if (submissionInFlight
                || awaitingComposite
                || gate11FrameClaimedAwaitingSubmit
                || gate11ReturnSemaphorePending
                || hasPublishedGate11Frame()) {
            nativeResolutionResizeDeferralCount++;
            nativeResolutionResizeState =
                    "DEFERRED_ACTIVE_GENERATION_OWNERSHIP";
            return false;
        }

        long retirementGlSync =
                glFenceSync(
                        GL_SYNC_GPU_COMMANDS_COMPLETE,
                        0
                );

        if (retirementGlSync == NULL) {
            nativeResolutionResizeDeferralCount++;
            nativeResolutionResizeState =
                    "DEFERRED_RETIREMENT_GL_SYNC_CREATION_FAILED";
            return false;
        }

        /*
         * Nonblocking flush only: make the retirement fence visible to the GL
         * driver without waiting for it. The fence is polled with timeout zero
         * on later frames.
         */
        glFlush();

        RetiredInteropGeneration previous =
                snapshotActiveInteropGeneration(
                        retirementGlSync
                );

        int previousWidth =
                targetWidth;

        int previousHeight =
                targetHeight;

        clearActiveInteropGenerationHandles();

        targetWidth =
                requestedWidth;

        targetHeight =
                requestedHeight;

        targetSelectionFramebufferWidth =
                requestedWidth;

        targetSelectionFramebufferHeight =
                requestedHeight;

        fullResolutionTargetSelected =
                true;

        targetLayoutsInitialized =
                false;

        glImported =
                false;

        nativeResolutionResizeState =
                "CREATING_EXACT_MAIN_TARGET_GENERATION";

        try {
            createTargets();
            createExternalSemaphores();
            importIntoOpenGl();

            if (!glImported
                    || colorImage == NULL
                    || depthImage == NULL
                    || vkReadySemaphore == NULL
                    || glReleasedSemaphore == NULL
                    || glTexture == 0
                    || glCaptureFramebuffer == 0) {
                throw new VulkanProbeException(
                        "GATE10_NATIVE_RESOLUTION_ROTATION",
                        "The replacement interop generation is incomplete."
                );
            }

            retiredInteropGenerations.add(
                    previous
            );

            nativeResolutionRetainedGenerationCount++;
            nativeResolutionPeakRetainedGenerationCount =
                    Math.max(
                            nativeResolutionPeakRetainedGenerationCount,
                            retiredInteropGenerations.size()
                    );
            nativeResolutionResizeSuccessCount++;
            nativeResolutionTargetGeneration++;
            nativeResolutionPendingWidth =
                    0;
            nativeResolutionPendingHeight =
                    0;
            nativeResolutionPendingStableObservationCount =
                    0;

            nativeResolutionResizeState =
                    "ROTATED_TO_EXACT_MAIN_TARGET_EXTENT";

            return true;
        } catch (Throwable throwable) {
            nativeResolutionResizeFailureCount++;

            /*
             * The replacement generation was never published or submitted, so
             * it can be destroyed immediately. Restore the previous proven
             * generation and leave this frame unarmed. Gate 11 keeps the last
             * successfully presented frame instead of receiving scaled pixels.
             */
            destroyActiveInteropGenerationResources();

            if (previous.retirementGlSync() != NULL) {
                glDeleteSync(
                        previous.retirementGlSync()
                );
            }

            restoreActiveInteropGeneration(
                    previous
            );

            targetWidth =
                    previousWidth;

            targetHeight =
                    previousHeight;

            targetSelectionFramebufferWidth =
                    previousWidth;

            targetSelectionFramebufferHeight =
                    previousHeight;

            fullResolutionTargetSelected =
                    true;

            nativeResolutionPendingStableObservationCount =
                    0;
            nativeResolutionResizeState =
                    "ROTATION_FAILED_PREVIOUS_GENERATION_RESTORED";

            gate11QualifiedFrameFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );

            return false;
        }
    }

    private RetiredInteropGeneration snapshotActiveInteropGeneration(
            long retirementGlSync
    ) {
        return new RetiredInteropGeneration(
                colorImage,
                colorMemory,
                colorView,
                colorAllocationBytes,
                colorMemoryTypeIndex,
                depthImage,
                depthMemory,
                depthView,
                depthMemoryTypeIndex,
                vkReadySemaphore,
                glReleasedSemaphore,
                glVkReadySemaphore,
                glReleasedSemaphoreObject,
                glMemoryObject,
                glTexture,
                glCaptureFramebuffer,
                targetLayoutsInitialized,
                retirementGlSync
        );
    }

    private void clearActiveInteropGenerationHandles() {
        colorImage =
                NULL;
        colorMemory =
                NULL;
        colorView =
                NULL;
        colorAllocationBytes =
                0L;
        colorWin32Handle =
                NULL;
        colorMemoryTypeIndex =
                -1;

        depthImage =
                NULL;
        depthMemory =
                NULL;
        depthView =
                NULL;
        depthMemoryTypeIndex =
                -1;

        vkReadySemaphore =
                NULL;
        glReleasedSemaphore =
                NULL;
        glVkReadySemaphore =
                0;
        glReleasedSemaphoreObject =
                0;
        glMemoryObject =
                0;
        glTexture =
                0;
        glCaptureFramebuffer =
                0;
    }

    private void restoreActiveInteropGeneration(
            RetiredInteropGeneration generation
    ) {
        colorImage =
                generation.colorImage();
        colorMemory =
                generation.colorMemory();
        colorView =
                generation.colorView();
        colorAllocationBytes =
                generation.colorAllocationBytes();
        colorMemoryTypeIndex =
                generation.colorMemoryTypeIndex();

        depthImage =
                generation.depthImage();
        depthMemory =
                generation.depthMemory();
        depthView =
                generation.depthView();
        depthMemoryTypeIndex =
                generation.depthMemoryTypeIndex();

        vkReadySemaphore =
                generation.vkReadySemaphore();
        glReleasedSemaphore =
                generation.glReleasedSemaphore();
        glVkReadySemaphore =
                generation.glVkReadySemaphore();
        glReleasedSemaphoreObject =
                generation.glReleasedSemaphoreObject();
        glMemoryObject =
                generation.glMemoryObject();
        glTexture =
                generation.glTexture();
        glCaptureFramebuffer =
                generation.glCaptureFramebuffer();

        targetLayoutsInitialized =
                generation.targetLayoutsInitialized();

        glImported =
                glMemoryObject != 0
                        && glTexture != 0
                        && glCaptureFramebuffer != 0;
    }

    private void destroyActiveInteropGenerationResources() {
        RetiredInteropGeneration active =
                snapshotActiveInteropGeneration(
                        NULL
                );

        clearActiveInteropGenerationHandles();

        destroyInteropGeneration(
                active
        );
    }

    private void reclaimCompletedInteropGenerations() {
        for (int index =
                     retiredInteropGenerations.size() - 1;
             index >= 0;
             index--) {
            RetiredInteropGeneration generation =
                    retiredInteropGenerations.get(
                            index
                    );

            long sync =
                    generation.retirementGlSync();

            if (sync == NULL) {
                continue;
            }

            nativeResolutionRetiredGenerationPollCount++;

            int status =
                    glClientWaitSync(
                            sync,
                            0,
                            0L
                    );

            if (status != GL_ALREADY_SIGNALED
                    && status != GL_CONDITION_SATISFIED) {
                continue;
            }

            retiredInteropGenerations.remove(
                    index
            );

            destroyInteropGeneration(
                    generation
            );

            nativeResolutionReclaimedGenerationCount++;
        }
    }

    private void captureMainTargetIntoSharedImage() {
        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            IntBuffer viewport =
                    stack.mallocInt(
                            4
                    );

            glGetIntegerv(
                    GL_VIEWPORT,
                    viewport
            );

            int previousViewportX =
                    viewport.get(
                            0
                    );

            int previousViewportY =
                    viewport.get(
                            1
                    );

            int previousViewportWidth =
                    viewport.get(
                            2
                    );

            int previousViewportHeight =
                    viewport.get(
                            3
                    );

            int previousDrawFramebuffer =
                    glGetInteger(
                            GL_DRAW_FRAMEBUFFER_BINDING
                    );

            int previousReadFramebuffer =
                    glGetInteger(
                            GL_READ_FRAMEBUFFER_BINDING
                    );

            boolean scissorEnabled =
                    glIsEnabled(
                            GL_SCISSOR_TEST
                    );

            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(
                            true
                    );

            explicitMainTargetBindCount++;

            int mainTargetFramebuffer =
                    glGetInteger(
                            GL_DRAW_FRAMEBUFFER_BINDING
                    );

            viewport.clear();

            glGetIntegerv(
                    GL_VIEWPORT,
                    viewport
            );

            int mainWidth =
                    viewport.get(
                            2
                    );

            int mainHeight =
                    viewport.get(
                            3
                    );

            lastMainTargetDrawFramebuffer =
                    mainTargetFramebuffer;

            lastMainTargetReadFramebuffer =
                    glGetInteger(
                            GL_READ_FRAMEBUFFER_BINDING
                    );

            lastMainTargetViewportWidth =
                    mainWidth;

            lastMainTargetViewportHeight =
                    mainHeight;

            lastCapturedMainTargetWidth =
                    mainWidth;

            lastCapturedMainTargetHeight =
                    mainHeight;

            if (mainTargetFramebuffer
                    != previousDrawFramebuffer) {
                mainTargetFramebufferChangedCount++;
            }

            if (mainWidth <= 0
                    || mainHeight <= 0) {
                throw new VulkanProbeException(
                        "GATE10_SCREEN_CONTENT_MAIN_TARGET_SIZE",
                        "Minecraft MainTarget has an invalid size "
                                + mainWidth
                                + "x"
                                + mainHeight
                );
            }

            glDisable(
                    GL_SCISSOR_TEST
            );

            glBindFramebuffer(
                    GL_READ_FRAMEBUFFER,
                    mainTargetFramebuffer
            );

            glBindFramebuffer(
                    GL_DRAW_FRAMEBUFFER,
                    glCaptureFramebuffer
            );

            glBlitFramebuffer(
                    0,
                    0,
                    mainWidth,
                    mainHeight,
                    0,
                    0,
                    targetWidth,
                    targetHeight,
                    GL_COLOR_BUFFER_BIT,
                    GL_LINEAR
            );

            validateGl(
                    "CAPTURE_MAIN_TARGET_TO_SHARED_IMAGE"
            );

            IntBuffer textures =
                    stack.ints(
                            glTexture
                    );

            IntBuffer bufferBarriers =
                    stack.mallocInt(
                            1
                    );

            bufferBarriers.limit(
                    0
            );

            IntBuffer layouts =
                    stack.ints(
                            GL_LAYOUT_COLOR_ATTACHMENT_EXT
                    );

            glSignalSemaphoreEXT(
                    glReleasedSemaphoreObject,
                    bufferBarriers,
                    textures,
                    layouts
            );

            glSignalReleaseCount++;
            mainTargetCaptureCount++;

            validateGl(
                    "SIGNAL_REAL_CONTENT_TO_VULKAN"
            );

            restoreCapability(
                    GL_SCISSOR_TEST,
                    scissorEnabled
            );

            glBindFramebuffer(
                    GL_READ_FRAMEBUFFER,
                    previousReadFramebuffer
            );

            glBindFramebuffer(
                    GL_DRAW_FRAMEBUFFER,
                    previousDrawFramebuffer
            );

            glViewport(
                    previousViewportX,
                    previousViewportY,
                    previousViewportWidth,
                    previousViewportHeight
            );
        }
    }

    private void submitVisibleCanary() {
        long pipeline =
                rasterPrecommit
                        .visibleScreenPipeline();

        long pipelineLayout =
                rasterPrecommit
                        .visibleScreenPipelineLayout();

        long descriptorSet =
                rasterPrecommit
                        .visibleScreenDescriptorSet();

        if (pipeline == NULL
                || pipelineLayout == NULL
                || descriptorSet == NULL) {
            throw new VulkanProbeException(
                    "GATE10_VISIBLE_RASTER_HANDLES",
                    "The proven SCREEN raster pipeline handles are unavailable."
            );
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            int result =
                    vkResetFences(
                            device,
                            submissionFence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_SCREEN_CONTENT_RESET_FENCE",
                        "vkResetFences failed with VkResult "
                                + result
                );
            }

            result =
                    vkResetCommandBuffer(
                            commandBuffer,
                            0
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_RESET_COMMAND_BUFFER",
                        "vkResetCommandBuffer failed with VkResult "
                                + result
                );
            }

            VkCommandBufferBeginInfo begin =
                    VkCommandBufferBeginInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .flags(
                                    VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT
                            );

            result =
                    vkBeginCommandBuffer(
                            commandBuffer,
                            begin
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_BEGIN_COMMAND_BUFFER",
                        "vkBeginCommandBuffer failed with VkResult "
                                + result
                );
            }

            recordAcquireAndInitialLayouts(
                    stack
            );

            VkRenderingAttachmentInfo.Buffer colorAttachment =
                    VkRenderingAttachmentInfo.calloc(
                            1,
                            stack
                    );

            colorAttachment.get(
                    0
            )
                    .sType$Default()
                    .imageView(
                            colorView
                    )
                    .imageLayout(
                            VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                    )
                    .loadOp(
                            VK_ATTACHMENT_LOAD_OP_LOAD
                    )
                    .storeOp(
                            VK_ATTACHMENT_STORE_OP_STORE
                    );

            VkRenderingAttachmentInfo depthAttachment =
                    VkRenderingAttachmentInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .imageView(
                                    depthView
                            )
                            .imageLayout(
                                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                            )
                            .loadOp(
                                    VK_ATTACHMENT_LOAD_OP_CLEAR
                            )
                            .storeOp(
                                    VK_ATTACHMENT_STORE_OP_STORE
                            );

            depthAttachment.clearValue()
                    .depthStencil()
                    .depth(
                            1.0f
                    )
                    .stencil(
                            0
                    );

            VkRenderingInfo rendering =
                    VkRenderingInfo.calloc(
                            stack
                    )
                            .sType$Default()
                            .layerCount(
                                    1
                            )
                            .pColorAttachments(
                                    colorAttachment
                            )
                            .pDepthAttachment(
                                    depthAttachment
                            );

            rendering.renderArea()
                    .offset()
                    .x(
                            0
                    )
                    .y(
                            0
                    );

            rendering.renderArea()
                    .extent()
                    .width(
                            targetWidth
                    )
                    .height(
                            targetHeight
                    );

            vkCmdBeginRendering(
                    commandBuffer,
                    rendering
            );

            VkViewport.Buffer viewport =
                    VkViewport.calloc(
                            1,
                            stack
                    );

            viewport.get(
                    0
            )
                    .x(
                            0.0f
                    )
                    .y(
                            0.0f
                    )
                    .width(
                            (float) targetWidth
                    )
                    .height(
                            (float) targetHeight
                    )
                    .minDepth(
                            0.0f
                    )
                    .maxDepth(
                            1.0f
                    );

            vkCmdSetViewport(
                    commandBuffer,
                    0,
                    viewport
            );

            VkRect2D.Buffer scissor =
                    VkRect2D.calloc(
                            1,
                            stack
                    );

            scissor.get(
                    0
            )
                    .offset()
                    .x(
                            0
                    )
                    .y(
                            0
                    );

            scissor.get(
                    0
            )
                    .extent()
                    .width(
                            targetWidth
                    )
                    .height(
                            targetHeight
                    );

            vkCmdSetScissor(
                    commandBuffer,
                    0,
                    scissor
            );

            /*
             * Stage 105 intentionally does not inject the Stage-098 orange
             * triangle. Vulkan still owns the shared image for this command
             * interval through dynamic rendering + external semaphore
             * transitions, but the captured Minecraft pixels are preserved
             * byte-for-pixel at this rehearsal resolution.
             */

            vkCmdEndRendering(
                    commandBuffer
            );

            recordReleaseToOpenGl(
                    stack
            );

            result =
                    vkEndCommandBuffer(
                            commandBuffer
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_END_COMMAND_BUFFER",
                        "vkEndCommandBuffer failed with VkResult "
                                + result
                );
            }

            VkSubmitInfo.Buffer submit =
                    VkSubmitInfo.calloc(
                            1,
                            stack
                    );

            submit.get(
                    0
            )
                    .sType$Default()
                    .pCommandBuffers(
                            stack.pointers(
                                    commandBuffer.address()
                            )
                    )
                    .pSignalSemaphores(
                            stack.longs(
                                    vkReadySemaphore
                            )
                    );

            submit.get(
                    0
            )
                    .pWaitSemaphores(
                            stack.longs(
                                    glReleasedSemaphore
                            )
                    )
                    .pWaitDstStageMask(
                            stack.ints(
                                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                            )
                    );

            vulkanWaitOnGlReleaseCount++;

            result =
                    vkQueueSubmit(
                            graphicsQueue,
                            submit,
                            submissionFence
                    );

            if (result != VK_SUCCESS) {
                throw new VulkanProbeException(
                        "GATE10_VISIBLE_QUEUE_SUBMIT",
                        "vkQueueSubmit failed with VkResult "
                                + result
                );
            }

            submissionCount++;
            realContentVulkanSubmissionCount++;
            submissionInFlight =
                    true;
            vulkanSignalReadyCount++;
            awaitingComposite =
                    true;
            targetLayoutsInitialized =
                    true;
        }
    }

    private void recordAcquireAndInitialLayouts(
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barriers =
                VkImageMemoryBarrier.calloc(
                        2,
                        stack
                );

        barriers.get(
                0
        )
                .sType$Default()
                .srcAccessMask(
                        0
                )
                .dstAccessMask(
                        VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                                | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                )
                .oldLayout(
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                )
                .newLayout(
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                )
                .srcQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .dstQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .image(
                        colorImage
                );

        barriers.get(
                0
        ).subresourceRange()
                .aspectMask(
                        VK_IMAGE_ASPECT_COLOR_BIT
                )
                .baseMipLevel(
                        0
                )
                .levelCount(
                        1
                )
                .baseArrayLayer(
                        0
                )
                .layerCount(
                        1
                );

        barriers.get(
                1
        )
                .sType$Default()
                .srcAccessMask(
                        targetLayoutsInitialized
                                ? VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                                : 0
                )
                .dstAccessMask(
                        VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT
                )
                .oldLayout(
                        targetLayoutsInitialized
                                ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                                : VK_IMAGE_LAYOUT_UNDEFINED
                )
                .newLayout(
                        VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL
                )
                .srcQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .dstQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .image(
                        depthImage
                );

        barriers.get(
                1
        ).subresourceRange()
                .aspectMask(
                        VK_IMAGE_ASPECT_DEPTH_BIT
                )
                .baseMipLevel(
                        0
                )
                .levelCount(
                        1
                )
                .baseArrayLayer(
                        0
                )
                .layerCount(
                        1
                );

        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                        | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT,
                0,
                null,
                null,
                barriers
        );
    }

    private void recordReleaseToOpenGl(
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barrier =
                VkImageMemoryBarrier.calloc(
                        1,
                        stack
                );

        barrier.get(
                0
        )
                .sType$Default()
                .srcAccessMask(
                        VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                )
                .dstAccessMask(
                        0
                )
                .oldLayout(
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                )
                .newLayout(
                        VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL
                )
                .srcQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .dstQueueFamilyIndex(
                        VK_QUEUE_FAMILY_IGNORED
                )
                .image(
                        colorImage
                );

        barrier.get(
                0
        ).subresourceRange()
                .aspectMask(
                        VK_IMAGE_ASPECT_COLOR_BIT
                )
                .baseMipLevel(
                        0
                )
                .levelCount(
                        1
                )
                .baseArrayLayer(
                        0
                )
                .layerCount(
                        1
                );

        vkCmdPipelineBarrier(
                commandBuffer,
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                0,
                null,
                null,
                barrier
        );
    }

    private boolean enqueueOpenGlComposite() {
        compositeAttemptCount++;

        if (!initialized
                || !glImported
                || !awaitingComposite) {
            return false;
        }

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            IntBuffer viewport =
                    stack.mallocInt(
                            4
                    );

            glGetIntegerv(
                    GL_VIEWPORT,
                    viewport
            );

            int previousViewportX =
                    viewport.get(
                            0
                    );

            int previousViewportY =
                    viewport.get(
                            1
                    );

            int previousViewportWidth =
                    viewport.get(
                            2
                    );

            int previousViewportHeight =
                    viewport.get(
                            3
                    );

            int previousDrawFramebuffer =
                    glGetInteger(
                            GL_DRAW_FRAMEBUFFER_BINDING
                    );

            int previousReadFramebuffer =
                    glGetInteger(
                            GL_READ_FRAMEBUFFER_BINDING
                    );

            lastPreviousDrawFramebuffer =
                    previousDrawFramebuffer;

            lastPreviousReadFramebuffer =
                    previousReadFramebuffer;

            int previousProgram =
                    glGetInteger(
                            GL_CURRENT_PROGRAM
                    );

            int previousVao =
                    glGetInteger(
                            GL_VERTEX_ARRAY_BINDING
                    );

            int previousActiveTexture =
                    glGetInteger(
                            GL_ACTIVE_TEXTURE
                    );

            glActiveTexture(
                    GL_TEXTURE0
            );

            int previousTexture =
                    glGetInteger(
                            GL_TEXTURE_BINDING_2D
                    );

            boolean blendEnabled =
                    glIsEnabled(
                            GL_BLEND
                    );

            boolean depthEnabled =
                    glIsEnabled(
                            GL_DEPTH_TEST
                    );

            boolean cullEnabled =
                    glIsEnabled(
                            GL_CULL_FACE
                    );

            boolean scissorEnabled =
                    glIsEnabled(
                            GL_SCISSOR_TEST
                    );

            int previousBlendSrcRgb =
                    glGetInteger(
                            GL_BLEND_SRC_RGB
                    );

            int previousBlendDstRgb =
                    glGetInteger(
                            GL_BLEND_DST_RGB
                    );

            int previousBlendSrcAlpha =
                    glGetInteger(
                            GL_BLEND_SRC_ALPHA
                    );

            int previousBlendDstAlpha =
                    glGetInteger(
                            GL_BLEND_DST_ALPHA
                    );

            int previousBlendEquationRgb =
                    glGetInteger(
                            GL_BLEND_EQUATION_RGB
                    );

            int previousBlendEquationAlpha =
                    glGetInteger(
                            GL_BLEND_EQUATION_ALPHA
                    );

            IntBuffer textures =
                    stack.ints(
                            glTexture
                    );

            IntBuffer bufferBarriers =
                    stack.mallocInt(
                            1
                    );

            bufferBarriers.limit(
                    0
            );

            IntBuffer waitLayouts =
                    stack.ints(
                            GL_LAYOUT_COLOR_ATTACHMENT_EXT
                    );

            glWaitSemaphoreEXT(
                    glVkReadySemaphore,
                    bufferBarriers,
                    textures,
                    waitLayouts
            );

            glWaitReadyCount++;

            validateGl(
                    "WAIT_VK_READY"
            );

            /*
             * Patch 100 submitted its draw into whichever draw framebuffer
             * happened to be current at ScreenEvent.Render.Post. A successful
             * glDrawArrays therefore proved command validity but not that the
             * command targeted Minecraft's final MainTarget.
             *
             * Bind MainTarget explicitly and restore both framebuffer bindings
             * afterward. bindWrite(true) also gives us the authoritative target
             * viewport for this exact frame.
             */
            Minecraft.getInstance()
                    .getMainRenderTarget()
                    .bindWrite(
                            true
                    );

            explicitMainTargetBindCount++;

            lastMainTargetDrawFramebuffer =
                    glGetInteger(
                            GL_DRAW_FRAMEBUFFER_BINDING
                    );

            lastMainTargetReadFramebuffer =
                    glGetInteger(
                            GL_READ_FRAMEBUFFER_BINDING
                    );

            if (lastMainTargetDrawFramebuffer
                    != previousDrawFramebuffer) {
                mainTargetFramebufferChangedCount++;
            }

            viewport.clear();

            glGetIntegerv(
                    GL_VIEWPORT,
                    viewport
            );

            lastMainTargetViewportWidth =
                    viewport.get(
                            2
                    );

            lastMainTargetViewportHeight =
                    viewport.get(
                            3
                    );

            int canaryWidth =
                    lastMainTargetViewportWidth;

            int canaryHeight =
                    lastMainTargetViewportHeight;

            if (canaryWidth <= 0
                    || canaryHeight <= 0) {
                prerequisiteSkipCount++;

                glBindFramebuffer(
                        GL_READ_FRAMEBUFFER,
                        previousReadFramebuffer
                );

                glBindFramebuffer(
                        GL_DRAW_FRAMEBUFFER,
                        previousDrawFramebuffer
                );

                glBindTexture(
                        GL_TEXTURE_2D,
                        previousTexture
                );

                glActiveTexture(
                        previousActiveTexture
                );

                glViewport(
                        previousViewportX,
                        previousViewportY,
                        previousViewportWidth,
                        previousViewportHeight
                );

                return false;
            }

            lastCanaryViewportWidth =
                    canaryWidth;

            lastCanaryViewportHeight =
                    canaryHeight;

            glViewport(
                    0,
                    0,
                    canaryWidth,
                    canaryHeight
            );

            glDisable(
                    GL_DEPTH_TEST
            );

            glDisable(
                    GL_CULL_FACE
            );

            glDisable(
                    GL_SCISSOR_TEST
            );

            glDisable(
                    GL_BLEND
            );

            glUseProgram(
                    glProgram
            );

            if (glSamplerLocation >= 0) {
                glUniform1i(
                        glSamplerLocation,
                        0
                );
            }

            glBindVertexArray(
                    glVao
            );

            glActiveTexture(
                    GL_TEXTURE0
            );

            glBindTexture(
                    GL_TEXTURE_2D,
                    glTexture
            );

            glDrawArrays(
                    GL_TRIANGLES,
                    0,
                    3
            );

            validateGl(
                    "DRAW_REAL_SCREEN_CONTENT_FULL_FRAME_HANDOFF"
            );

            glBindTexture(
                    GL_TEXTURE_2D,
                    previousTexture
            );

            glActiveTexture(
                    previousActiveTexture
            );

            glBindVertexArray(
                    previousVao
            );

            glUseProgram(
                    previousProgram
            );

            glBlendEquationSeparate(
                    previousBlendEquationRgb,
                    previousBlendEquationAlpha
            );

            glBlendFuncSeparate(
                    previousBlendSrcRgb,
                    previousBlendDstRgb,
                    previousBlendSrcAlpha,
                    previousBlendDstAlpha
            );

            restoreCapability(
                    GL_BLEND,
                    blendEnabled
            );

            restoreCapability(
                    GL_DEPTH_TEST,
                    depthEnabled
            );

            restoreCapability(
                    GL_CULL_FACE,
                    cullEnabled
            );

            restoreCapability(
                    GL_SCISSOR_TEST,
                    scissorEnabled
            );

            glBindFramebuffer(
                    GL_READ_FRAMEBUFFER,
                    previousReadFramebuffer
            );

            glBindFramebuffer(
                    GL_DRAW_FRAMEBUFFER,
                    previousDrawFramebuffer
            );

            glViewport(
                    previousViewportX,
                    previousViewportY,
                    previousViewportWidth,
                    previousViewportHeight
            );

            return true;
        }
    }

    private void validateGl(
            String stage
    ) {
        int error =
                glGetError();

        if (error != GL_NO_ERROR) {
            throw new VulkanProbeException(
                    "GATE10_VISIBLE_GL_" + stage,
                    "OpenGL reported error "
                            + error
            );
        }
    }

    private static void restoreCapability(
            int capability,
            boolean enabled
    ) {
        if (enabled) {
            glEnable(
                    capability
            );
        } else {
            glDisable(
                    capability
            );
        }
    }

    private void fail(
            Throwable throwable
    ) {
        failureCount++;
        disabledAfterFailure =
                true;

        if (runtimeQualificationOffered) {
            retireGate11LiveStream(
                    "SCREEN_REHEARSAL_FAILURE"
            );

            runtimeQualificationOffered =
                    false;

            VulkanGate10ImmediatePath
                    .revokeRuntimeGate10(
                            report,
                            "REVOKED_SCREEN_REHEARSAL_FAILURE"
                    );
        }

        lastFailure =
                throwable.getClass()
                        .getName()
                        + ": "
                        + String.valueOf(
                        throwable.getMessage()
                );

        enrich(
                report
        );
    }

    static void enrichAbsent(
            JsonObject target
    ) {
        if (target == null) {
            return;
        }

        target.addProperty(
                "gate10VisibleScreenRehearsalInstalled",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMode",
                "WORLD_OUTER_FRAME_GATE10_AUTOMATIC_QUALIFICATION_NATIVE_RES_NO_OPENGL_SUPPRESSION"
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalInitialized",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFullResolutionTargetSelected",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVisibleCanaryFrameCount",
                0
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFailureCount",
                0
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalSuppressesOpenGl",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVisibleOwnership",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGameplayCpuWait",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalRealScreenContentReplication",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMainTargetRealPixelsCopied",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalRealContentRoundTripComplete",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalExplicitMainTargetBind",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalDiagnosticPanel",
                "FULL_FRAME_NATIVE_RES_GATE10_QUALIFICATION_TEST"
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTelemetryProvesFramebufferCommandOnly",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFullFrameAtomicHandoffRehearsal",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalProductionScreenOwnership",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalAutomaticGate10Qualification",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalQualificationMinFullFrameHandoffs",
                QUALIFICATION_MIN_FULL_FRAME_HANDOFFS
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalQualificationMinScreenSessions",
                QUALIFICATION_MIN_SCREEN_SESSIONS
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalQualificationOffered",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPublishesQualifiedFrameForGate11",
                true
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalQualifiedFramePublishedForGate11",
                false
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalQualifiedFramePublishCount",
                0
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalQualifiedFrameSignalCount",
                0
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamInstalled",
                true
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamActive",
                false
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamMaximumFrames",
                GATE11_LIVE_STREAM_MAX_FRAMES
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamDurationMillis",
                GATE11_LIVE_STREAM_DURATION_NANOS / 1_000_000L
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamCpuReadback",
                false
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamGameplayGpuWait",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalExpectedVisualEffect",
                "AUTONOMOUS_NATIVE_RES_FULL_FRAME_VULKAN_HANDOFF"
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVisualProofRequiresUserObservation",
                false
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalAutomaticWorldFrameBootstrap",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalAutomaticGate10Qualification",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalQualificationMinFullFrameHandoffs",
                QUALIFICATION_MIN_FULL_FRAME_HANDOFFS
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalQualificationMinScreenSessions",
                QUALIFICATION_MIN_SCREEN_SESSIONS
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalQualificationOffered",
                false
        );
    }

    synchronized void enrich(
            JsonObject target
    ) {
        if (target == null) {
            return;
        }

        target.addProperty(
                "gate10VisibleScreenRehearsalInstalled",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMode",
                "WORLD_OUTER_FRAME_GATE10_AUTOMATIC_QUALIFICATION_NATIVE_RES_NO_OPENGL_SUPPRESSION"
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTargetWidth",
                targetWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTargetHeight",
                targetHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeInstalled",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeMode",
                "EXACT_MAIN_TARGET_EXTENT_RETAIN_OLD_GENERATIONS_NONBLOCKING"
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeCheckCount",
                nativeResolutionResizeCheckCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeAttemptCount",
                nativeResolutionResizeAttemptCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeSuccessCount",
                nativeResolutionResizeSuccessCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeDeferralCount",
                nativeResolutionResizeDeferralCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeFailureCount",
                nativeResolutionResizeFailureCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionRetainedGenerationCount",
                nativeResolutionRetainedGenerationCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionRetiredGenerationPollCount",
                nativeResolutionRetiredGenerationPollCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionReclaimedGenerationCount",
                nativeResolutionReclaimedGenerationCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionCurrentRetainedGenerationCount",
                retiredInteropGenerations.size()
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionPeakRetainedGenerationCount",
                nativeResolutionPeakRetainedGenerationCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionRetirementUsesBlockingWait",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionTargetGeneration",
                nativeResolutionTargetGeneration
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionLastRequestedWidth",
                nativeResolutionLastRequestedWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionLastRequestedHeight",
                nativeResolutionLastRequestedHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionPendingWidth",
                nativeResolutionPendingWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionPendingHeight",
                nativeResolutionPendingHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionPendingStableObservationCount",
                nativeResolutionPendingStableObservationCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionRequiredStableObservations",
                NATIVE_RESOLUTION_REQUIRED_STABLE_OBSERVATIONS
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNativeResolutionResizeState",
                nativeResolutionResizeState
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLowResolutionUpscaleAllowed",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalActiveTargetMatchesLastMainTarget",
                targetWidth == lastCapturedMainTargetWidth
                        && targetHeight == lastCapturedMainTargetHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFullResolutionTargetSelected",
                fullResolutionTargetSelected
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTargetSelectionFramebufferWidth",
                targetSelectionFramebufferWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTargetSelectionFramebufferHeight",
                targetSelectionFramebufferHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTargetMatchesLastCapturedMainTarget",
                lastCapturedMainTargetWidth > 0
                        && lastCapturedMainTargetHeight > 0
                        && targetWidth == lastCapturedMainTargetWidth
                        && targetHeight == lastCapturedMainTargetHeight
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalPublishesQualifiedFrameForGate11",
                true
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalQualifiedFramePublishedForGate11",
                gate11QualifiedFramePublished
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalQualifiedFramePublishCount",
                gate11QualifiedFramePublishCount
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalQualifiedFrameSignalCount",
                gate11QualifiedFrameSignalCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamInstalled",
                true
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamActive",
                gate11LiveStreamActive
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamMaximumFrames",
                GATE11_LIVE_STREAM_MAX_FRAMES
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamDurationMillis",
                GATE11_LIVE_STREAM_DURATION_NANOS / 1_000_000L
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamFrameSequence",
                gate11LiveFrameSequence
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamFramePublishCount",
                gate11LiveFramePublishCount
        );

        target.addProperty(
                "gate10Gate11LiveUiStreamProductionDirectInterop",
                true
        );

        target.addProperty(
                "gate10Gate11LiveUiStreamDirectInteropMode",
                "GL_CAPTURE_SIGNAL_TO_GATE11_PRESENT_SIGNAL_BACK_TO_GL"
        );

        target.addProperty(
                "gate10Gate11LiveUiStreamDirectInteropPublishCount",
                gate11DirectInteropPublishCount
        );

        target.addProperty(
                "gate10Gate11LiveUiStreamDirectInteropRecoveryRoundTripCount",
                gate11DirectInteropRecoveryRoundTripCount
        );

        target.addProperty(
                "gate10Gate11LiveUiStreamPerFrameRehearsalVulkanSubmissionAfterQualification",
                false
        );

        target.addProperty(
                "gate10Gate11LiveUiStreamPerFrameOpenGlFullscreenCompositeAfterQualification",
                false
        );

        target.addProperty(
                "gate10Gate11LiveUiStreamInitialQualificationRoundTripPreserved",
                true
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamFrameClaimCount",
                gate11LiveFrameClaimCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamFrameSubmitAckCount",
                gate11LiveFrameSubmitAckCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamReturnGpuWaitCount",
                gate11LiveReturnGpuWaitCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamTerminalReturnDrainCount",
                gate11TerminalReturnDrainCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamInitialQualifiedFrameIncludedInPublishCount",
                true
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamBackpressureSkipCount",
                gate11LiveBackpressureSkipCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamRetireCount",
                gate11LiveRetireCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamShutdownUnclaimedTailDiscardCount",
                gate11ShutdownUnclaimedTailDiscardCount
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamShutdownTailDiscardPolicy",
                "ONLY_AFTER_GL_FINISH_AND_VK_DEVICE_IDLE_DURING_TEARDOWN"
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamFrameClaimedAwaitingSubmit",
                gate11FrameClaimedAwaitingSubmit
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamReturnSemaphorePending",
                gate11ReturnSemaphorePending
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamStopReason",
                gate11LiveStreamStopReason
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamProductionSession",
                GATE11_PRODUCTION_PRESENTATION_SESSION
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamBoundedStopPolicyActive",
                !GATE11_PRODUCTION_PRESENTATION_SESSION
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamCpuReadback",
                false
        );
        target.addProperty(
                "gate10Gate11LiveUiStreamGameplayGpuWait",
                false
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalQualifiedFrameTransferSrcUsage",
                true
        );
        if (!gate11QualifiedFrameFailure.isBlank()) {
            target.addProperty(
                    "gate10VisibleScreenRehearsalQualifiedFrameFailure",
                    gate11QualifiedFrameFailure
            );
        }

        target.addProperty(
                "gate10VisibleScreenRehearsalMaximumVisibleFrames",
                MAX_VISIBLE_FRAMES_PER_SESSION
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMaximumVisibleFramesPerSession",
                MAX_VISIBLE_FRAMES_PER_SESSION
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMaximumPreviewSessions",
                MAX_PREVIEW_SESSIONS
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionDurationMillis",
                PREVIEW_SESSION_DURATION_NANOS / 1_000_000L
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionGapMillis",
                PREVIEW_SESSION_GAP_NANOS / 1_000_000L
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalInitialized",
                initialized
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalExtensionGateEvaluated",
                extensionGateEvaluated
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalExtensionGatePassed",
                extensionGatePassed
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTargetEverCreated",
                targetEverCreated
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalColorMemoryTypeIndex",
                colorMemoryTypeIndex
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalDepthMemoryTypeIndex",
                depthMemoryTypeIndex
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalExternalSemaphoresEverCreated",
                semaphoresEverCreated
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGlImportEverCompleted",
                glImportEverCompleted
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalBeginOfferCount",
                beginOfferCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalEndOfferCount",
                endOfferCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPrerequisiteSkipCount",
                prerequisiteSkipCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalDuplicatePendingSkipCount",
                duplicatePendingSkipCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVulkanSubmissionCount",
                submissionCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalCompositeAttemptCount",
                compositeAttemptCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalCompositeSuccessCount",
                compositeSuccessCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVisibleCanaryFrameCount",
                compositeSuccessCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVulkanWaitOnGlReleaseCount",
                vulkanWaitOnGlReleaseCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVulkanSignalReadyCount",
                vulkanSignalReadyCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGlWaitReadyCount",
                glWaitReadyCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGlSignalReleaseCount",
                glSignalReleaseCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMainTargetCaptureCount",
                mainTargetCaptureCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalRealContentVulkanSubmissionCount",
                realContentVulkanSubmissionCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalRealContentPreviewCount",
                realContentPreviewCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalCaptureFramebufferCompleteCount",
                captureFramebufferCompleteCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFencePollCount",
                fencePollCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFenceNotReadyCount",
                fenceNotReadyCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFenceCompletionCount",
                fenceCompletionCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalBusySkipCount",
                busySkipCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalSessionReplayEnabled",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionCount",
                previewSessionCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionVisibleFrames",
                previewSessionVisibleFrames
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionGapDetectedCount",
                previewSessionGapDetectedCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionRearmCount",
                previewSessionRearmCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionLimitSkipCount",
                previewSessionLimitSkipCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalPreviewSessionCapRejectCount",
                previewSessionCapRejectCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalSessionBoundaryPending",
                sessionBoundaryPending
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalSubmissionInFlight",
                submissionInFlight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMainTargetRealPixelsCopied",
                mainTargetCaptureCount > 0
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalRealContentRoundTripComplete",
                mainTargetCaptureCount > 0
                        && mainTargetCaptureCount == realContentVulkanSubmissionCount
                        && realContentVulkanSubmissionCount == realContentPreviewCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalCanaryEverComposited",
                canaryEverComposited
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalProofRetired",
                proofRetired
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFailureCount",
                failureCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalDisabledAfterFailure",
                disabledAfterFailure
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalReuses098ScreenPipeline",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalConsumes097StateDescriptor",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGpuGpuExternalSemaphoreHandoff",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalAddsDebugCanary",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalRealScreenContentReplication",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGpuOnlyMainTargetCapture",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFullFrameAtomicHandoffRehearsal",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalFullFrameAtomicHandoffCount",
                realContentPreviewCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalOpenGlSourceStillRendered",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalProductionScreenOwnership",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalExpectedVisualEffect",
                "AUTONOMOUS_NATIVE_RES_FULL_FRAME_VULKAN_HANDOFF"
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalSuppressesOpenGl",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVisibleOwnership",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGameplayGlFinish",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGameplayFenceWait",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGameplayFencePollOnly",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGameplayQueueWaitIdle",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGameplayDeviceWaitIdle",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalGameplayCpuWait",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalShutdownGlFinishCount",
                shutdownGlFinishCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalShutdownDeviceWaitIdleCount",
                shutdownDeviceWaitIdleCount
        );


        target.addProperty(
                "gate10VisibleScreenRehearsalExplicitMainTargetBind",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalExplicitMainTargetBindCount",
                explicitMainTargetBindCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalMainTargetFramebufferChangedCount",
                mainTargetFramebufferChangedCount
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastPreviousDrawFramebuffer",
                lastPreviousDrawFramebuffer
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastPreviousReadFramebuffer",
                lastPreviousReadFramebuffer
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastMainTargetDrawFramebuffer",
                lastMainTargetDrawFramebuffer
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastMainTargetReadFramebuffer",
                lastMainTargetReadFramebuffer
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastMainTargetViewportWidth",
                lastMainTargetViewportWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastMainTargetViewportHeight",
                lastMainTargetViewportHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastCanaryViewportWidth",
                lastCanaryViewportWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastCanaryViewportHeight",
                lastCanaryViewportHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastFullFrameHandoffWidth",
                lastCanaryViewportWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastFullFrameHandoffHeight",
                lastCanaryViewportHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastCapturedMainTargetWidth",
                lastCapturedMainTargetWidth
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalLastCapturedMainTargetHeight",
                lastCapturedMainTargetHeight
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalDiagnosticPanel",
                "FULL_FRAME_NATIVE_RES_GATE10_QUALIFICATION_TEST"
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTelemetryProvesFramebufferCommandOnly",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalTelemetryProvesRealPixelRoundTripCommands",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalCpuPixelReadback",
                false
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalVisualProofRequiresUserObservation",
                false
        );
        target.addProperty(
                "gate10VisibleScreenRehearsalAutomaticWorldFrameBootstrap",
                true
        );

        target.addProperty(
                "gate10VisibleScreenRehearsalNextMilestone",
                "POTATO_ENGINE_GATE10_SCREEN_DRAW_CAPTURE_AND_REPLAY"
        );

        if (!lastFailure.isBlank()) {
            target.addProperty(
                    "gate10VisibleScreenRehearsalLastFailure",
                    lastFailure
            );
        }
    }

    private void destroyResources() {
        if (glProgram != 0) {
            glDeleteProgram(
                    glProgram
            );

            glProgram =
                    0;
        }

        if (glVao != 0) {
            glDeleteVertexArrays(
                    glVao
            );

            glVao =
                    0;
        }

        if (glCaptureFramebuffer != 0) {
            glDeleteFramebuffers(
                    glCaptureFramebuffer
            );

            glCaptureFramebuffer =
                    0;
        }

        if (glTexture != 0) {
            glDeleteTextures(
                    glTexture
            );

            glTexture =
                    0;
        }

        if (glMemoryObject != 0) {
            glDeleteMemoryObjectsEXT(
                    glMemoryObject
            );

            glMemoryObject =
                    0;
        }

        if (glVkReadySemaphore != 0) {
            glDeleteSemaphoresEXT(
                    glVkReadySemaphore
            );

            glVkReadySemaphore =
                    0;
        }

        if (glReleasedSemaphoreObject != 0) {
            glDeleteSemaphoresEXT(
                    glReleasedSemaphoreObject
            );

            glReleasedSemaphoreObject =
                    0;
        }

        if (vkReadySemaphore != NULL) {
            vkDestroySemaphore(
                    device,
                    vkReadySemaphore,
                    null
            );

            vkReadySemaphore =
                    NULL;
        }

        if (glReleasedSemaphore != NULL) {
            vkDestroySemaphore(
                    device,
                    glReleasedSemaphore,
                    null
            );

            glReleasedSemaphore =
                    NULL;
        }

        if (submissionFence != NULL) {
            vkDestroyFence(
                    device,
                    submissionFence,
                    null
            );

            submissionFence =
                    NULL;
        }

        if (commandPool != NULL) {
            vkDestroyCommandPool(
                    device,
                    commandPool,
                    null
            );

            commandPool =
                    NULL;
            commandBuffer =
                    null;
        }

        if (colorView != NULL) {
            vkDestroyImageView(
                    device,
                    colorView,
                    null
            );

            colorView =
                    NULL;
        }

        if (depthView != NULL) {
            vkDestroyImageView(
                    device,
                    depthView,
                    null
            );

            depthView =
                    NULL;
        }

        if (colorImage != NULL) {
            vkDestroyImage(
                    device,
                    colorImage,
                    null
            );

            colorImage =
                    NULL;
        }

        if (depthImage != NULL) {
            vkDestroyImage(
                    device,
                    depthImage,
                    null
            );

            depthImage =
                    NULL;
        }

        if (colorMemory != NULL) {
            vkFreeMemory(
                    device,
                    colorMemory,
                    null
            );

            colorMemory =
                    NULL;
        }

        if (depthMemory != NULL) {
            vkFreeMemory(
                    device,
                    depthMemory,
                    null
            );

            depthMemory =
                    NULL;
        }

        glImported =
                false;

        destroyRetiredInteropGenerations();
    }

    private void destroyRetiredInteropGenerations() {
        for (RetiredInteropGeneration generation
                : retiredInteropGenerations) {
            destroyInteropGeneration(
                    generation
            );
        }

        retiredInteropGenerations.clear();
    }

    private void destroyInteropGeneration(
            RetiredInteropGeneration generation
    ) {
        if (generation == null) {
            return;
        }

        if (generation.retirementGlSync() != NULL) {
            glDeleteSync(
                    generation.retirementGlSync()
            );
        }

        if (generation.glCaptureFramebuffer() != 0) {
            glDeleteFramebuffers(
                    generation.glCaptureFramebuffer()
            );
        }

        if (generation.glTexture() != 0) {
            glDeleteTextures(
                    generation.glTexture()
            );
        }

        if (generation.glMemoryObject() != 0) {
            glDeleteMemoryObjectsEXT(
                    generation.glMemoryObject()
            );
        }

        if (generation.glVkReadySemaphore() != 0) {
            glDeleteSemaphoresEXT(
                    generation.glVkReadySemaphore()
            );
        }

        if (generation.glReleasedSemaphoreObject() != 0) {
            glDeleteSemaphoresEXT(
                    generation.glReleasedSemaphoreObject()
            );
        }

        if (generation.vkReadySemaphore() != NULL) {
            vkDestroySemaphore(
                    device,
                    generation.vkReadySemaphore(),
                    null
            );
        }

        if (generation.glReleasedSemaphore() != NULL) {
            vkDestroySemaphore(
                    device,
                    generation.glReleasedSemaphore(),
                    null
            );
        }

        if (generation.colorView() != NULL) {
            vkDestroyImageView(
                    device,
                    generation.colorView(),
                    null
            );
        }

        if (generation.depthView() != NULL) {
            vkDestroyImageView(
                    device,
                    generation.depthView(),
                    null
            );
        }

        if (generation.colorImage() != NULL) {
            vkDestroyImage(
                    device,
                    generation.colorImage(),
                    null
            );
        }

        if (generation.depthImage() != NULL) {
            vkDestroyImage(
                    device,
                    generation.depthImage(),
                    null
            );
        }

        if (generation.colorMemory() != NULL) {
            vkFreeMemory(
                    device,
                    generation.colorMemory(),
                    null
            );
        }

        if (generation.depthMemory() != NULL) {
            vkFreeMemory(
                    device,
                    generation.depthMemory(),
                    null
            );
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        /*
         * Patch 127: shutdown ordering is part of the producer ownership
         * contract. Patch 126b proved 1295 publish/claim/ack handoffs, but
         * Gate 10 unregistered activeInstance before Gate 11's later shutdown
         * finalizer could retire the production stream. That left the final
         * Vulkan -> GL return semaphore reported pending even though the
         * visible Vulkan session itself had been clean for more than a minute.
         *
         * Retire while this object still owns its GL/Vulkan interop resources.
         * retireGate11LiveStream() consumes the already-proven GPU semaphore
         * return on the render thread; this is not a CPU fence/queue/device
         * wait and only occurs during teardown here.
         */
        if (gate11LiveStreamActive
                || gate11ReturnSemaphorePending
                || gate11FrameClaimedAwaitingSubmit
                || hasPublishedGate11Frame()) {
            retireGate11LiveStream(
                    "PRODUCTION_SESSION_SHUTDOWN"
            );
        }

        closed =
                true;

        if (initialized) {
            shutdownGlFinishCount++;

            glFinish();

            shutdownDeviceWaitIdleCount++;

            int result =
                    vkDeviceWaitIdle(
                            device
                    );

            report.addProperty(
                    "gate10VisibleScreenRehearsalShutdownDeviceWaitIdleResult",
                    result
            );

            if (result != VK_SUCCESS) {
                failureCount++;
                lastFailure =
                        "Shutdown vkDeviceWaitIdle returned VkResult "
                                + result;
            }
        }

        synchronized (VulkanGate10VisibleScreenRehearsal.class) {
            if (publishedQualifiedFrame != null
                    && publishedQualifiedFrame.image() == colorImage) {
                /*
                 * Patch 129: after glFinish + vkDeviceWaitIdle there can be one
                 * producer frame that was published immediately before shutdown
                 * but never claimed by Gate 11. It is now fully quiescent and is
                 * safe to discard rather than pretending that a normal shutdown
                 * tail is a runtime presentation failure.
                 */
                publishedQualifiedFrame = null;
                gate11ShutdownUnclaimedTailDiscardCount++;
            }

            if (activeInstance == this) {
                activeInstance = null;
            }
        }

        destroyResources();

        report.addProperty(
                "gate10VisibleScreenRehearsalClosed",
                true
        );

        enrich(
                report
        );
    }

    private record RetiredInteropGeneration(
            long colorImage,
            long colorMemory,
            long colorView,
            long colorAllocationBytes,
            int colorMemoryTypeIndex,
            long depthImage,
            long depthMemory,
            long depthView,
            int depthMemoryTypeIndex,
            long vkReadySemaphore,
            long glReleasedSemaphore,
            int glVkReadySemaphore,
            int glReleasedSemaphoreObject,
            int glMemoryObject,
            int glTexture,
            int glCaptureFramebuffer,
            boolean targetLayoutsInitialized,
            long retirementGlSync
    ) {
    }

    private record ImageAllocation(
            long image,
            long memory,
            long view,
            int memoryTypeIndex
    ) {
    }

    static record QualifiedFrame(
            long image,
            long readySemaphore,
            long returnSemaphore,
            int width,
            int height,
            int format,
            long sequence
    ) {
    }

    private record ExternalImageAllocation(
            long image,
            long memory,
            long view,
            long allocationBytes,
            long win32Handle,
            int memoryTypeIndex
    ) {
    }
}
