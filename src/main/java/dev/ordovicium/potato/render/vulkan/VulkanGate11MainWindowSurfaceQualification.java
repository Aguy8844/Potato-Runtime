package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.windows.User32;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Gate-11 Stage 7: turn the proven GLFW_NO_API real-content candidate into a
 * bounded live UI presentation stream while keeping Minecraft's real window as
 * lifecycle owner. The candidate repeatedly presents fresh final Screen frames
 * through Vulkan; Minecraft's stored native handle and OpenGL context remain
 * untouched during this proof.
 *
 * <p>Stages 1-4 already proved the required replacement topology, hidden
 * swapchain execution, and bounded desktop visibility/focus behavior. Stage 5
 * removes the synthetic dark-green clear. The final Gate-10 shared image is
 * published with an external GPU semaphore, claimed here, GPU-blitted into
 * a same-format swapchain image with the visually-proven 180-degree orientation
 * correction, presented, and shown for the same bounded compositor interval.</p>
 *
 * <p>This is deliberately still not final Gate-11 ownership. The UI source is
 * still Minecraft's normally rendered MainTarget; Stage 114 proves live Vulkan
 * presentation of those final pixels, including late glyph/font uploads, before
 * any main-window replacement or OpenGL suppression. No CPU pixel readback or
 * gameplay fence/queue/device wait is introduced.</p>
 */
final class VulkanGate11MainWindowSurfaceQualification
        implements AutoCloseable {

    private static final long VISIBLE_REHEARSAL_DURATION_NANOS =
            22_000_000_000L;

    /*
     * Patch 126 is a development production-topology rehearsal. The previous
     * process freshly proved all 11 gates, so this generation keeps the
     * Vulkan presentation window as the only visible gameplay window until
     * shutdown or a Vulkan presentation failure. OpenGL remains a hidden
     * raster source/fail-open authority; this is deliberately not labelled
     * an OpenGL-free renderer.
     */
    private static final boolean PRODUCTION_SINGLE_VISIBLE_SESSION =
            true;

    private static final int LIVE_PRESENT_RING_SIZE =
            3;

    /**
     * Do not reveal the NO_API gameplay window until Vulkan has already
     * presented multiple fresh Minecraft frames while the candidate is still
     * hidden. This removes the visible blank/title handoff interval.
     */
    private static final int VISIBLE_REVEAL_PREWARM_PRESENTS =
            2;

    private final VkInstance instance;
    private final VkDevice device;
    private final VkPhysicalDevice physicalDevice;
    private final VkQueue graphicsQueue;
    private final VkQueue presentQueue;
    private final VulkanQueueFamilySelector.Selection queues;
    private final int presentQueueFamilyIndex;
    private final VulkanPresentationProbe retainedPresentation;
    private final VulkanFrameSession retainedFrameSession;
    private final JsonObject report;

    private VulkanGate11WindowLifecycleRouter lifecycleRouter;

    private boolean attempted;

    private boolean directQualified;
    private boolean directSurfaceCreated;
    private boolean directSurfaceDestroyed;
    private boolean directCurrentContextPreservedExactly;

    private long minecraftWindowHandle;
    private long directCurrentContextBefore;
    private long directCurrentContextAfter;

    private int minecraftWindowClientApi = Integer.MIN_VALUE;
    private int directCreateSurfaceResult = Integer.MIN_VALUE;
    private int directSurfaceSupportResult = Integer.MIN_VALUE;
    private int directCapabilitiesResult = Integer.MIN_VALUE;
    private int directFormatsResult = Integer.MIN_VALUE;
    private int directPresentModesResult = Integer.MIN_VALUE;
    private int directFormatCount;
    private int directPresentModeCount;
    private boolean directPresentationSupported;
    private boolean directCapabilitiesQueried;
    private boolean directFormatsQueried;
    private boolean directPresentModesQueried;

    private String directFailureReason = "NOT_ATTEMPTED";

    private boolean noApiAttempted;
    private boolean noApiQualified;
    private boolean noApiCandidateCreated;
    private boolean noApiCandidateReused;
    private boolean noApiCandidateDestroyed;
    private boolean noApiCandidateUsesNoApi;
    private boolean noApiSurfaceCreated;
    private boolean noApiSurfaceDestroyed;
    private boolean noApiSurfaceBorrowedFromRuntime;
    private boolean noApiPersistentSwapchainPrepared;
    private boolean noApiPresentationSupported;
    private boolean noApiCapabilitiesQueried;
    private boolean noApiFormatsQueried;
    private boolean noApiPresentModesQueried;
    private boolean noApiCurrentContextPreservedExactly;
    private boolean noApiLogicalSizeMatchesMainWindow;
    private boolean noApiFramebufferSizeMatchesMainWindow;
    private boolean noApiPositionMatched;
    private boolean noApiFullscreenTransferDeferred;

    private long noApiCandidateHandle;
    private long noApiCurrentContextBefore;
    private long noApiCurrentContextAfter;

    private int mainWindowX;
    private int mainWindowY;
    private int mainWindowWidth;
    private int mainWindowHeight;
    private int mainFramebufferWidth;
    private int mainFramebufferHeight;
    private int mainWindowResizable = Integer.MIN_VALUE;
    private int mainWindowDecorated = Integer.MIN_VALUE;

    private int noApiCandidateClientApi = Integer.MIN_VALUE;
    private int noApiCandidateX;
    private int noApiCandidateY;
    private int noApiCandidateWidth;
    private int noApiCandidateHeight;
    private int noApiCandidateFramebufferWidth;
    private int noApiCandidateFramebufferHeight;

    private int noApiCreateSurfaceResult = Integer.MIN_VALUE;
    private int noApiSurfaceSupportResult = Integer.MIN_VALUE;
    private int noApiCapabilitiesResult = Integer.MIN_VALUE;
    private int noApiFormatsResult = Integer.MIN_VALUE;
    private int noApiPresentModesResult = Integer.MIN_VALUE;
    private int noApiFormatCount;
    private int noApiPresentModeCount;

    private String noApiFailureReason = "NOT_ATTEMPTED";

    /* Stage-3 shadow swapchain state. */
    private boolean shadowAttempted;
    private boolean shadowPassed;
    private boolean shadowCandidateCreated;
    private boolean shadowCandidateReused;
    private boolean shadowCandidateUsesNoApi;
    private boolean shadowCandidateHidden;
    private boolean shadowSurfaceBorrowedFromRuntime;
    private boolean shadowSwapchainBorrowedFromRuntime;
    private boolean shadowCandidateNativeNoActivateAttempted;
    private boolean shadowCandidateNativeNoActivateApplied;
    private boolean shadowCandidateNativeNoActivateFrameRefreshAttempted;
    private boolean shadowCandidateNativeNoActivateFrameRefreshApplied;
    private boolean shadowSurfaceCreated;
    private boolean shadowSurfaceQualified;
    private boolean shadowSwapchainCreated;
    private boolean shadowSwapchainImagesEnumerated;
    private boolean shadowAcquireAttempted;
    private boolean shadowAcquireSucceeded;
    private boolean shadowCommandRecorded;
    private boolean shadowQueueSubmitUsed;
    private boolean shadowQueuePresentUsed;
    private boolean shadowPresentAccepted;
    private boolean shadowCurrentContextPreservedExactly;
    private boolean shadowResourcesRetainedUntilShutdown;
    private boolean shadowResourcesDestroyed;
    private boolean shadowGameplayFenceWait;
    private boolean shadowGameplayQueueWaitIdle;
    private boolean shadowGameplayDeviceWaitIdle;
    private boolean shadowShutdownDeviceWaitIdleUsed;
    private boolean shadowClosed;

    private long shadowCandidate;
    private long shadowCandidateNativeHandle;
    private long shadowCandidateExtendedStyleBefore;
    private long shadowCandidateExtendedStyleAfter;
    private long shadowSurface;
    private long shadowSwapchain;
    private long shadowAcquireSemaphore;
    private long shadowRenderFinishedSemaphore;
    private long shadowCommandPool;
    private VkCommandBuffer shadowCommandBuffer;
    private long[] shadowImages;

    private VkCommandBuffer[] liveCommandBuffers;
    private long[] liveAcquireSemaphores;
    private long[] liveFences;
    private long[] liveRenderFinishedSemaphores;
    private final List<long[]>
            retiredLiveRenderFinishedSemaphoreGenerations =
            new ArrayList<>();
    private VulkanGate10VisibleScreenRehearsal.QualifiedFrame livePendingFrame;
    private int liveRingCursor;
    private boolean liveResourcesCreated;
    private boolean liveStreamInstalled = true;
    private boolean liveStreamActive;
    private boolean liveStreamRetired;
    private long liveFrameClaimCount;
    private long liveFrameSubmitCount;
    private long liveFramePresentCount;
    private long liveFrameSubmitAckCount;
    private long liveFencePollCount;
    private long liveFenceNotReadyCount;
    private long liveAcquireNotReadyCount;
    private long liveAcquireSuboptimalCount;
    private long liveBackpressureSkipCount;
    private long liveFailureCount;
    private long liveSourceExtentObservationCount;
    private long liveSourceExtentExactMatchCount;
    private long liveSourceExtentMismatchCount;
    private int liveLastSourceWidth;
    private int liveLastSourceHeight;
    private boolean liveLastSourceExtentMatchesSwapchain;

    private boolean visibleReplacementRevealPending;
    private boolean visibleReplacementRevealDeferredUntilPrewarm;
    private long visibleReplacementPrewarmPresentCount;
    private boolean visibleReplacementColorTransferVerified;
    private int visibleReplacementPresentationFormat =
            VK_FORMAT_UNDEFINED;
    private int visibleReplacementPresentationColorSpace =
            Integer.MIN_VALUE;
    private String visibleReplacementColorTransferPolicy =
            "NOT_EVALUATED";

    private long liveSwapchainGeometryCheckCount;
    private long liveSwapchainGeometryMismatchCount;
    private long liveSwapchainRefreshAttemptCount;
    private long liveSwapchainRefreshSuccessCount;
    private long liveSwapchainRefreshDeferralCount;
    private long liveSwapchainRefreshFenceBusyCount;
    private long liveSwapchainRefreshFailureCount;
    private long liveSwapchainGenerationChangeCount;
    private long liveAcquireOutOfDateCount;
    private long livePresentOutOfDateCount;
    private long liveRecoverablePresentDropCount;
    private long livePresentSuboptimalCount;
    private String liveSwapchainRefreshState =
            "NOT_NEEDED";

    private int liveLastAcquireResult = Integer.MIN_VALUE;
    private int liveLastQueueSubmitResult = Integer.MIN_VALUE;
    private int liveLastPresentResult = Integer.MIN_VALUE;
    private String liveFailureReason = "NOT_STARTED";

    private long shadowCurrentContextBefore;
    private long shadowCurrentContextAfter;

    private int shadowCandidateClientApi = Integer.MIN_VALUE;
    private int shadowCreateSurfaceResult = Integer.MIN_VALUE;
    private int shadowSurfaceSupportResult = Integer.MIN_VALUE;
    private int shadowCapabilitiesResult = Integer.MIN_VALUE;
    private int shadowFormatsResult = Integer.MIN_VALUE;
    private int shadowPresentModesResult = Integer.MIN_VALUE;
    private int shadowCreateSwapchainResult = Integer.MIN_VALUE;
    private int shadowGetImagesCountResult = Integer.MIN_VALUE;
    private int shadowGetImagesResult = Integer.MIN_VALUE;
    private int shadowAcquireResult = Integer.MIN_VALUE;
    private int shadowAcquiredImageIndex = -1;
    private int shadowQueueSubmitResult = Integer.MIN_VALUE;
    private int shadowPresentResult = Integer.MIN_VALUE;
    private int shadowShutdownDeviceWaitIdleResult = Integer.MIN_VALUE;

    private int shadowFormat = VK_FORMAT_UNDEFINED;
    private int shadowColorSpace = Integer.MIN_VALUE;
    private int shadowPresentMode = Integer.MIN_VALUE;
    private int shadowImageCount;
    private int shadowWidth;
    private int shadowHeight;
    private int shadowImageUsage;
    private int shadowCompositeAlpha;

    private String shadowFailureReason = "NOT_ATTEMPTED";

    /* Stage-5 real Minecraft-content transfer into the shadow swapchain. */
    private VulkanGate10VisibleScreenRehearsal.QualifiedFrame realContentFrame;
    private boolean realContentFrameClaimed;
    private boolean realContentFrameSizeMatchesSwapchain;
    private boolean realContentFrameFormatMatchesSwapchain;
    private boolean realContentSemaphoreWaitUsed;
    private boolean realContentCopyRecorded;
    private boolean realContentBlitSupported;
    private boolean realContentBlitRecorded;
    private boolean realContentSourceReturnedToColorAttachmentLayout;
    private boolean realContentPresented;
    private long realContentCopyCount;
    private long realContentBlitCount;
    private int realContentOrientationRotationDegrees = 0;
    private int realContentSourceWidth;
    private int realContentSourceHeight;
    private int realContentSourceFormat = VK_FORMAT_UNDEFINED;
    private boolean realContentExtentSyncAttempted;
    private boolean realContentExtentSyncApplied;
    private boolean realContentExtentSwapchainPrepared;
    private int realContentExtentRequestedLogicalWidth;
    private int realContentExtentRequestedLogicalHeight;
    private int realContentExtentResultFramebufferWidth;
    private int realContentExtentResultFramebufferHeight;
    private String realContentExtentSyncState = "NOT_ATTEMPTED";
    private String realContentFailureReason = "NOT_ATTEMPTED";

    /* Stage-5 visible replacement-window compositor rehearsal. */
    private boolean visibleReplacementAttempted;
    private boolean visibleReplacementShown;
    private boolean visibleReplacementHidePending;
    private boolean visibleReplacementHiddenAfterRehearsal;
    private boolean visibleReplacementPassed;
    private boolean visibleReplacementCandidateFocusedAfterShow;
    private boolean visibleReplacementCurrentContextPreservedAfterShow;
    private boolean visibleReplacementCurrentContextPreservedAfterHide;
    private boolean visibleReplacementMainWindowHandlePreserved;
    private boolean visibleReplacementUsesRealMinecraftContent;
    private boolean visibleReplacementGameplayGpuWait;
    private boolean productionPresentationFocusableStyleAttempted;
    private boolean productionPresentationFocusableStyleApplied;
    private boolean productionPresentationOpenGlOwnerHidden;
    private boolean productionPresentationCandidateFocused;
    private boolean productionPresentationFailOpenTriggered;
    private boolean productionPresentationFallbackRestored;
    private boolean productionPresentationShutdownFinalized;

    /*
     * Patch 147: the visible GLFW_NO_API candidate is the only native
     * fullscreen authority during Vulkan gameplay. F11 is consumed by the
     * lifecycle router and applied directly to that candidate. The hidden
     * OpenGL lifecycle owner is forced to remain windowed+hidden; its geometry
     * only mirrors the visible candidate so the transitional OpenGL raster
     * source keeps exact extent parity.
     */
    private long productionWindowTopologySyncCheckCount;
    private long productionWindowTopologySyncFailureCount;
    private long productionWindowFullscreenEnterMirrorCount;
    private long productionWindowFullscreenExitMirrorCount;
    private long productionWindowWindowedGeometryMirrorCount;
    private long productionWindowOwnerRehideCount;
    private long productionWindowFocusRepairCount;
    private long productionWindowOwnerFullscreenNeutralizationCount;
    private long productionWindowCandidateFullscreenObservedCount;
    private long productionWindowCandidateWindowedObservedCount;
    private boolean productionWindowSingleVisibleInvariantMaintained = true;
    private long productionWindowLastOwnerMonitor;
    private long productionWindowLastCandidateMonitor;
    private int productionWindowLastOwnerX;
    private int productionWindowLastOwnerY;
    private int productionWindowLastOwnerWidth;
    private int productionWindowLastOwnerHeight;
    private int productionWindowLastCandidateX;
    private int productionWindowLastCandidateY;
    private int productionWindowLastCandidateWidth;
    private int productionWindowLastCandidateHeight;
    private String productionWindowTopologySyncState = "NOT_STARTED";

    /*
     * Patch 131: 0.9 transition topology.
     * Vulkan owns the one visible gameplay presentation window only while a
     * Minecraft world is active. The ordinary Minecraft/OpenGL window owns
     * title/menu presentation. Save-and-Quit therefore becomes an explicit
     * world-session handoff instead of leaving a stale Vulkan gameplay window
     * in front of the already-returned TitleScreen.
     */
    private boolean productionPresentationWorldExitHandoffAttempted;
    private boolean productionPresentationWorldExitHandoffCompleted;
    private long productionPresentationWorldExitHandoffAttemptCount;
    private long productionPresentationWorldExitHandoffSuccessCount;
    private long productionPresentationWorldExitHandoffFailureCount;
    private long productionPresentationWorldExitHandoffMillis;
    private String productionPresentationWorldExitHandoffReason =
            "NOT_ATTEMPTED";

    private long productionPresentationExtendedStyleBefore;
    private long productionPresentationExtendedStyleAfter;

    private long visibleReplacementShownNanos;
    private long visibleReplacementDurationMillis;
    private long visibleReplacementContextBefore;
    private long visibleReplacementContextAfterShow;
    private long visibleReplacementContextAfterHide;
    private long visibleReplacementMainWindowHandleBefore;
    private long visibleReplacementMainWindowHandleAfter;

    private int visibleReplacementMainWindowFocusedBefore = Integer.MIN_VALUE;
    private int visibleReplacementMainWindowFocusedAfterShow = Integer.MIN_VALUE;
    private int visibleReplacementMainWindowFocusedAfterHide = Integer.MIN_VALUE;

    private String visibleReplacementFailureReason = "NOT_ATTEMPTED";

    VulkanGate11MainWindowSurfaceQualification(
            VkInstance instance,
            VkDevice device,
            VkPhysicalDevice physicalDevice,
            VkQueue graphicsQueue,
            VkQueue presentQueue,
            VulkanQueueFamilySelector.Selection queues,
            VulkanPresentationProbe retainedPresentation,
            VulkanFrameSession retainedFrameSession,
            JsonObject report
    ) {
        this.instance = instance;
        this.device = device;
        this.physicalDevice = physicalDevice;
        this.graphicsQueue = graphicsQueue;
        this.presentQueue = presentQueue;
        this.queues = queues;
        this.presentQueueFamilyIndex = queues != null
                ? queues.presentFamilyIndex()
                : -1;
        this.retainedPresentation = retainedPresentation;
        this.retainedFrameSession = retainedFrameSession;
        this.report = report;

        enrich();
    }

    synchronized void offer() {
        if (attempted) {
            enrich();
            return;
        }

        attempted = true;

        try {
            captureMinecraftWindow();
            runDirectSurfaceQualification();

            if (!directQualified) {
                runNoApiHandoffRehearsal();
            }

            if (noApiQualified) {
                runShadowSwapchainPresentRehearsal();
            }
        } catch (Throwable throwable) {
            if (directFailureReason.equals("NOT_ATTEMPTED")) {
                directFailureReason = describe(throwable);
            }

            if (noApiAttempted
                    && noApiFailureReason.equals("NOT_ATTEMPTED")) {
                noApiFailureReason = describe(throwable);
            }

            if (shadowAttempted
                    && shadowFailureReason.equals("NOT_ATTEMPTED")) {
                shadowFailureReason = describe(throwable);
            }
        } finally {
            enrich();
        }
    }

    private void captureMinecraftWindow() {
        Minecraft minecraft = Minecraft.getInstance();

        if (minecraft == null
                || minecraft.getWindow() == null) {
            throw new IllegalStateException(
                    "Minecraft window is unavailable."
            );
        }

        minecraftWindowHandle = minecraft.getWindow().getWindow();

        if (minecraftWindowHandle == NULL
                || instance == null
                || device == null
                || physicalDevice == null
                || graphicsQueue == null
                || presentQueue == null
                || queues == null
                || presentQueueFamilyIndex < 0) {
            throw new IllegalStateException(
                    "Gate-11 window/presentation prerequisites are incomplete."
            );
        }

        if (lifecycleRouter == null) {
            lifecycleRouter =
                    new VulkanGate11WindowLifecycleRouter(
                            minecraftWindowHandle,
                            report
                    );
        }

        minecraftWindowClientApi = glfwGetWindowAttrib(
                minecraftWindowHandle,
                GLFW_CLIENT_API
        );

        mainWindowResizable = glfwGetWindowAttrib(
                minecraftWindowHandle,
                GLFW_RESIZABLE
        );

        mainWindowDecorated = glfwGetWindowAttrib(
                minecraftWindowHandle,
                GLFW_DECORATED
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);

            glfwGetWindowSize(
                    minecraftWindowHandle,
                    width,
                    height
            );

            mainWindowWidth = width.get(0);
            mainWindowHeight = height.get(0);

            glfwGetFramebufferSize(
                    minecraftWindowHandle,
                    width,
                    height
            );

            mainFramebufferWidth = width.get(0);
            mainFramebufferHeight = height.get(0);

            glfwGetWindowPos(
                    minecraftWindowHandle,
                    x,
                    y
            );

            mainWindowX = x.get(0);
            mainWindowY = y.get(0);
        }
    }

    private void runDirectSurfaceQualification() {
        long surface = NULL;

        directCurrentContextBefore = glfwGetCurrentContext();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer surfacePointer = stack.mallocLong(1);

            directCreateSurfaceResult = glfwCreateWindowSurface(
                    instance,
                    minecraftWindowHandle,
                    null,
                    surfacePointer
            );

            if (directCreateSurfaceResult != VK_SUCCESS) {
                directFailureReason =
                        "GLFW_CREATE_MAIN_WINDOW_SURFACE_RESULT_"
                                + directCreateSurfaceResult;
                return;
            }

            surface = surfacePointer.get(0);

            if (surface == NULL) {
                directFailureReason =
                        "GLFW_CREATE_MAIN_WINDOW_SURFACE_RETURNED_NULL";
                return;
            }

            directSurfaceCreated = true;
            directQualified = querySurface(
                    surface,
                    false
            );

            directFailureReason = directQualified
                    ? "PASSED_ACTUAL_MINECRAFT_WINDOW_SURFACE_QUALIFICATION"
                    : "ACTUAL_MINECRAFT_WINDOW_SURFACE_QUERY_INCOMPLETE";
        } catch (Throwable throwable) {
            directQualified = false;
            directFailureReason = describe(throwable);
        } finally {
            if (surface != NULL) {
                try {
                    vkDestroySurfaceKHR(
                            instance,
                            surface,
                            null
                    );
                    directSurfaceDestroyed = true;
                } catch (Throwable throwable) {
                    directQualified = false;
                    directFailureReason =
                            "DIRECT_SURFACE_DESTROY_FAILURE: "
                                    + describe(throwable);
                }
            }

            directCurrentContextAfter = glfwGetCurrentContext();
            directCurrentContextPreservedExactly =
                    directCurrentContextBefore
                            == directCurrentContextAfter;

            if (!directCurrentContextPreservedExactly) {
                directQualified = false;
                directFailureReason =
                        "OPENGL_CONTEXT_CHANGED_BY_DIRECT_SURFACE_QUERY";
            }
        }
    }

    private void runNoApiHandoffRehearsal() {
        noApiAttempted = true;

        long candidate = NULL;
        long surface = NULL;

        noApiCurrentContextBefore = glfwGetCurrentContext();

        try {
            candidate = VulkanHandoffCandidate
                    .claimWindowForGameplay();

            if (candidate == NULL) {
                noApiFailureReason =
                        "PERSISTENT_BOOTSTRAP_NO_API_CANDIDATE_NOT_AVAILABLE";
                return;
            }

            noApiCandidateHandle = candidate;
            noApiCandidateCreated = false;
            noApiCandidateReused = true;

            long currentMonitor = glfwGetWindowMonitor(
                    minecraftWindowHandle
            );

            noApiFullscreenTransferDeferred =
                    currentMonitor != NULL;

            if (!noApiFullscreenTransferDeferred) {
                /*
                 * The candidate is created at NeoForge's early 854x480 handoff
                 * size. Gameplay may already have resized/maximized the real
                 * window before Gate 11 qualifies. Synchronize the persistent
                 * candidate to the live logical extent before any WSI use.
                 */
                glfwSetWindowPos(
                        candidate,
                        mainWindowX,
                        mainWindowY
                );
                glfwSetWindowSize(
                        candidate,
                        Math.max(1, mainWindowWidth),
                        Math.max(1, mainWindowHeight)
                );
            }

            noApiCandidateClientApi = glfwGetWindowAttrib(
                    candidate,
                    GLFW_CLIENT_API
            );

            noApiCandidateUsesNoApi =
                    noApiCandidateClientApi
                            == GLFW_NO_API;

            captureCandidateGeometry(
                    candidate
            );

            noApiLogicalSizeMatchesMainWindow =
                    noApiCandidateWidth == mainWindowWidth
                            && noApiCandidateHeight == mainWindowHeight;

            noApiFramebufferSizeMatchesMainWindow =
                    noApiCandidateFramebufferWidth
                                    == mainFramebufferWidth
                            && noApiCandidateFramebufferHeight
                                    == mainFramebufferHeight;

            noApiPositionMatched =
                    noApiFullscreenTransferDeferred
                            || (noApiCandidateX == mainWindowX
                            && noApiCandidateY == mainWindowY);

            /*
             * Patch 139: the probe-owned VkSurfaceKHR was transferred with the
             * persistent runtime. Reusing it is mandatory; creating a second
             * VkSurfaceKHR for the same HWND is precisely the
             * VK_ERROR_NATIVE_WINDOW_IN_USE_KHR topology we are removing.
             */
            if (retainedPresentation != null
                    && retainedPresentation.windowHandle() == candidate
                    && retainedPresentation.surface() != NULL) {
                surface = retainedPresentation.surface();
                noApiCreateSurfaceResult = VK_SUCCESS;
                noApiSurfaceCreated = true;
                noApiSurfaceBorrowedFromRuntime = true;

                querySurface(
                        surface,
                        true
                );
            } else {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    LongBuffer surfacePointer = stack.mallocLong(1);

                    noApiCreateSurfaceResult = glfwCreateWindowSurface(
                            instance,
                            candidate,
                            null,
                            surfacePointer
                    );

                    if (noApiCreateSurfaceResult != VK_SUCCESS) {
                        noApiFailureReason =
                                "GLFW_CREATE_NO_API_CANDIDATE_SURFACE_RESULT_"
                                        + noApiCreateSurfaceResult;
                        return;
                    }

                    surface = surfacePointer.get(0);

                    if (surface == NULL) {
                        noApiFailureReason =
                                "GLFW_CREATE_NO_API_CANDIDATE_SURFACE_RETURNED_NULL";
                        return;
                    }

                    noApiSurfaceCreated = true;
                    querySurface(
                            surface,
                            true
                    );
                }
            }

            noApiPersistentSwapchainPrepared =
                    retainedFrameSession != null
                            && retainedFrameSession
                            .prepareGameplayPresentationSwapchain(
                                    surface,
                                    candidate
                            );

            if (!noApiPersistentSwapchainPrepared) {
                noApiFailureReason =
                        "PERSISTENT_GAMEPLAY_SWAPCHAIN_PREPARATION_DEFERRED_OR_FAILED";
                return;
            }

            noApiQualified =
                    noApiCandidateReused
                            && noApiCandidateUsesNoApi
                            && noApiLogicalSizeMatchesMainWindow
                            && noApiFramebufferSizeMatchesMainWindow
                            && noApiPositionMatched
                            && noApiSurfaceCreated
                            && noApiPersistentSwapchainPrepared
                            && noApiPresentationSupported
                            && noApiCapabilitiesQueried
                            && noApiFormatsQueried
                            && noApiPresentModesQueried;

            noApiFailureReason = noApiQualified
                    ? "PASSED_PERSISTENT_RUNTIME_NO_API_SURFACE_REUSE"
                    : "LIVE_NO_API_MAIN_WINDOW_HANDOFF_REHEARSAL_INCOMPLETE";
        } catch (Throwable throwable) {
            noApiQualified = false;
            noApiFailureReason = describe(throwable);
        } finally {
            try {
                glfwDefaultWindowHints();
            } catch (Throwable ignored) {
            }

            if (surface != NULL
                    && !noApiSurfaceBorrowedFromRuntime) {
                try {
                    vkDestroySurfaceKHR(
                            instance,
                            surface,
                            null
                    );
                    noApiSurfaceDestroyed = true;
                } catch (Throwable throwable) {
                    noApiQualified = false;
                    noApiFailureReason =
                            "NO_API_SURFACE_DESTROY_FAILURE: "
                                    + describe(throwable);
                }
            }

            if (candidate != NULL) {
                noApiCandidateDestroyed = false;
            }

            noApiCurrentContextAfter = glfwGetCurrentContext();
            noApiCurrentContextPreservedExactly =
                    noApiCurrentContextBefore
                            == noApiCurrentContextAfter;

            if (!noApiCurrentContextPreservedExactly) {
                noApiQualified = false;
                noApiFailureReason =
                        "OPENGL_CONTEXT_CHANGED_BY_NO_API_HANDOFF_REHEARSAL";
            }
        }
    }

    private void runShadowSwapchainPresentRehearsal() {
        shadowAttempted = true;
        shadowCurrentContextBefore = glfwGetCurrentContext();

        realContentFrame = VulkanGate10VisibleScreenRehearsal
                .claimQualifiedFrameForGate11();
        realContentFrameClaimed = realContentFrame != null;

        if (!realContentFrameClaimed) {
            shadowFailureReason = "QUALIFIED_GATE10_REAL_CONTENT_FRAME_NOT_AVAILABLE";
            realContentFailureReason = shadowFailureReason;
            return;
        }

        realContentSourceWidth = realContentFrame.width();
        realContentSourceHeight = realContentFrame.height();
        realContentSourceFormat = realContentFrame.format();

        try {
            shadowCandidate = noApiCandidateHandle;

            if (shadowCandidate == NULL
                    || !VulkanHandoffCandidate.isPersistentCandidate(
                    shadowCandidate
            )) {
                shadowFailureReason =
                        "PERSISTENT_BOOTSTRAP_NO_API_CANDIDATE_NOT_AVAILABLE_FOR_PRESENTATION";
                return;
            }

            shadowCandidateCreated = false;
            shadowCandidateReused = true;

            configureShadowCandidateNativeNoActivate();

            if (!shadowCandidateNativeNoActivateApplied) {
                shadowFailureReason =
                        "WIN32_NOACTIVATE_PRESENTATION_STYLE_NOT_APPLIED";
                return;
            }

            if (lifecycleRouter != null) {
                lifecycleRouter.bindPresentationHandle(
                        shadowCandidate
                );
            }

            shadowCandidateClientApi = glfwGetWindowAttrib(
                    shadowCandidate,
                    GLFW_CLIENT_API
            );
            shadowCandidateUsesNoApi =
                    shadowCandidateClientApi == GLFW_NO_API;
            shadowCandidateHidden =
                    glfwGetWindowAttrib(
                            shadowCandidate,
                            GLFW_VISIBLE
                    ) == GLFW_FALSE;

            if (glfwGetWindowMonitor(minecraftWindowHandle) == NULL) {
                glfwSetWindowPos(
                        shadowCandidate,
                        mainWindowX,
                        mainWindowY
                );
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                /*
                 * Patch 139: Gate 11 borrows the already retained runtime WSI
                 * generation. No second VkSurfaceKHR and no second gameplay
                 * swapchain are created for the same GLFW_NO_API candidate.
                 */
                if (retainedPresentation == null
                        || retainedFrameSession == null
                        || retainedPresentation.windowHandle()
                        != shadowCandidate
                        || retainedPresentation.surface() == NULL) {
                    shadowFailureReason =
                            "PERSISTENT_RUNTIME_PRESENTATION_BORROW_UNAVAILABLE";
                    return;
                }

                shadowSurface =
                        retainedPresentation.surface();
                shadowCreateSurfaceResult =
                        VK_SUCCESS;
                shadowSurfaceCreated =
                        true;
                shadowSurfaceBorrowedFromRuntime =
                        true;

                ShadowConfiguration queriedConfiguration =
                        queryShadowConfiguration(
                                shadowSurface,
                                stack
                        );

                if (queriedConfiguration == null) {
                    shadowFailureReason =
                            "BORROWED_RUNTIME_SURFACE_CONFIGURATION_INCOMPLETE";
                    return;
                }

                shadowSurfaceQualified =
                        true;

                VulkanSwapchainSupport.Configuration runtimeConfiguration =
                        retainedFrameSession
                                .presentationConfiguration();

                /*
                 * Patch 140a: Gate 11 is offered while NeoForge may still own
                 * the bootstrap 854x480 geometry, but the qualified Gate-10
                 * MainTarget frame can already be at the final maximized
                 * framebuffer (for example 2560x1334). The real-content extent
                 * is authoritative for the hidden prewarm. Resize the hidden
                 * NO_API candidate and rebuild only the persistent presentation
                 * swapchain before the first real-content acquire.
                 */
                if (runtimeConfiguration == null
                        || runtimeConfiguration.width()
                        != realContentSourceWidth
                        || runtimeConfiguration.height()
                        != realContentSourceHeight) {
                    if (!synchronizePersistentPresentationToRealContentExtent()) {
                        shadowFailureReason =
                                realContentExtentSyncState;
                        realContentFailureReason =
                                shadowFailureReason;
                        return;
                    }

                    runtimeConfiguration =
                            retainedFrameSession
                                    .presentationConfiguration();
                }

                shadowSwapchain =
                        retainedFrameSession
                                .presentationSwapchainHandle();
                shadowImages =
                        retainedFrameSession
                                .presentationSwapchainImagesSnapshot();

                if (runtimeConfiguration == null
                        || shadowSwapchain == NULL
                        || shadowImages.length <= 0) {
                    shadowFailureReason =
                            "PERSISTENT_RUNTIME_SWAPCHAIN_BORROW_INCOMPLETE";
                    return;
                }

                shadowSwapchainBorrowedFromRuntime =
                        true;
                shadowCreateSwapchainResult =
                        VK_SUCCESS;
                shadowGetImagesCountResult =
                        VK_SUCCESS;
                shadowGetImagesResult =
                        VK_SUCCESS;
                shadowSwapchainCreated =
                        true;
                shadowSwapchainImagesEnumerated =
                        true;

                shadowFormat =
                        runtimeConfiguration.format();
                shadowColorSpace =
                        runtimeConfiguration.colorSpace();
                shadowPresentMode =
                        runtimeConfiguration.presentMode();
                shadowImageCount =
                        runtimeConfiguration.imageCount();
                shadowWidth =
                        runtimeConfiguration.width();
                shadowHeight =
                        runtimeConfiguration.height();
                shadowImageUsage =
                        runtimeConfiguration.imageUsage();
                shadowCompositeAlpha =
                        runtimeConfiguration.compositeAlpha();

                realContentFrameSizeMatchesSwapchain =
                        realContentSourceWidth == shadowWidth
                                && realContentSourceHeight == shadowHeight;

                if (!realContentFrameSizeMatchesSwapchain) {
                    realContentFailureReason =
                            "REAL_CONTENT_EXTENT_MISMATCH_"
                                    + realContentSourceWidth
                                    + "x"
                                    + realContentSourceHeight
                                    + "_VS_"
                                    + shadowWidth
                                    + "x"
                                    + shadowHeight;
                    shadowFailureReason =
                            realContentFailureReason;
                    return;
                }

                try {
                    VulkanImageBlitSupport.verify(
                            physicalDevice,
                            realContentSourceFormat,
                            shadowFormat,
                            report
                    );
                    realContentFrameFormatMatchesSwapchain =
                            true;
                } catch (Throwable throwable) {
                    realContentFrameFormatMatchesSwapchain =
                            false;
                    realContentFailureReason =
                            "REAL_CONTENT_FORMAT_CONVERSION_UNSUPPORTED_"
                                    + realContentSourceFormat
                                    + "_TO_"
                                    + shadowFormat
                                    + ": "
                                    + describe(throwable);
                    shadowFailureReason =
                            realContentFailureReason;
                    return;
                }

                createShadowCommandResources(
                        stack
                );

                shadowAcquireAttempted = true;
                IntBuffer imageIndex = stack.ints(-1);

                shadowAcquireResult = vkAcquireNextImageKHR(
                        device,
                        shadowSwapchain,
                        0L,
                        shadowAcquireSemaphore,
                        NULL,
                        imageIndex
                );

                if (shadowAcquireResult != VK_SUCCESS
                        && shadowAcquireResult != VK_SUBOPTIMAL_KHR) {
                    shadowFailureReason =
                            "SHADOW_ACQUIRE_NONBLOCKING_RESULT_"
                                    + shadowAcquireResult;
                    return;
                }

                shadowAcquiredImageIndex = imageIndex.get(0);

                if (shadowAcquiredImageIndex < 0
                        || shadowAcquiredImageIndex >= shadowImages.length) {
                    shadowFailureReason =
                            "SHADOW_ACQUIRE_IMAGE_INDEX_OUT_OF_RANGE_"
                                    + shadowAcquiredImageIndex;
                    return;
                }

                shadowAcquireSucceeded = true;

                recordShadowPresentCommand(
                        shadowImages[shadowAcquiredImageIndex],
                        shadowAcquiredImageIndex,
                        stack
                );

                submitShadowPresentCommand(
                        stack
                );

                /*
                 * A successful queue submit may still be executing even if
                 * presentation subsequently fails. Retain every native WSI
                 * object from this point until shutdown, where the one
                 * finite device-idle teardown is allowed.
                 */
                shadowResourcesRetainedUntilShutdown = true;

                shadowPresentResult = presentShadowImage(
                        shadowAcquiredImageIndex,
                        stack
                );

                shadowQueuePresentUsed = true;
                shadowPresentAccepted =
                        shadowPresentResult == VK_SUCCESS
                                || shadowPresentResult == VK_SUBOPTIMAL_KHR;

                if (!shadowPresentAccepted) {
                    shadowFailureReason =
                            "SHADOW_QUEUE_PRESENT_RESULT_"
                                    + shadowPresentResult;
                    return;
                }

                if (shadowSwapchainBorrowedFromRuntime
                        && retainedFrameSession != null) {
                    retainedFrameSession
                            .markPresentationImagePresented(
                                    shadowAcquiredImageIndex
                            );
                }

                realContentPresented = true;
                realContentFailureReason =
                        "PASSED_GPU_ONLY_REAL_MINECRAFT_CONTENT_PRESENT_FLIP_Y_CORRECTED";
            }

            shadowCurrentContextAfter = glfwGetCurrentContext();
            shadowCurrentContextPreservedExactly =
                    shadowCurrentContextBefore
                            == shadowCurrentContextAfter;

            if (!shadowCurrentContextPreservedExactly) {
                shadowFailureReason =
                        "OPENGL_CONTEXT_CHANGED_BY_SHADOW_PRESENT_REHEARSAL";
                return;
            }

            shadowResourcesRetainedUntilShutdown = true;
            shadowPassed =
                    shadowCandidateReused
                            && shadowCandidateUsesNoApi
                            && shadowCandidateHidden
                            && shadowSurfaceCreated
                            && shadowSurfaceQualified
                            && shadowSwapchainCreated
                            && shadowSwapchainImagesEnumerated
                            && shadowAcquireSucceeded
                            && shadowCommandRecorded
                            && shadowQueueSubmitUsed
                            && shadowQueueSubmitResult == VK_SUCCESS
                            && shadowQueuePresentUsed
                            && shadowPresentAccepted
                            && shadowCurrentContextPreservedExactly
                            && realContentFrameClaimed
                            && realContentFrameSizeMatchesSwapchain
                            && realContentFrameFormatMatchesSwapchain
                            && realContentSemaphoreWaitUsed
                            && realContentBlitSupported
                            && realContentBlitRecorded
                            && realContentSourceReturnedToColorAttachmentLayout
                            && realContentPresented;

            shadowFailureReason = shadowPassed
                    ? "PASSED_HIDDEN_NO_API_SWAPCHAIN_REAL_MINECRAFT_CONTENT_PRESENT_FLIP_Y_CORRECTED"
                    : "HIDDEN_NO_API_REAL_CONTENT_PRESENT_REHEARSAL_INCOMPLETE";

            if (shadowPassed) {
                armVisibleReplacementRehearsal();
            }
        } catch (Throwable throwable) {
            shadowPassed = false;
            shadowFailureReason = describe(throwable);
        } finally {
            try {
                glfwDefaultWindowHints();
            } catch (Throwable ignored) {
            }

            if (shadowCurrentContextAfter == NULL) {
                shadowCurrentContextAfter = glfwGetCurrentContext();
                shadowCurrentContextPreservedExactly =
                        shadowCurrentContextBefore
                                == shadowCurrentContextAfter;
            }

            /*
             * Deliberately retain WSI resources here. vkQueuePresentKHR may
             * still own them asynchronously; gameplay never waits for it.
             */
            if (!shadowPassed) {
                shadowResourcesRetainedUntilShutdown =
                        shadowSwapchain != NULL
                                || shadowSurface != NULL
                                || shadowCandidate != NULL
                                || shadowCommandPool != NULL
                                || shadowAcquireSemaphore != NULL
                                || shadowRenderFinishedSemaphore != NULL;
            }
        }
    }

    private void armVisibleReplacementRehearsal() {
        if (visibleReplacementAttempted
                || !shadowPassed
                || shadowCandidate == NULL
                || shadowSwapchain == NULL
                || !shadowPresentAccepted) {
            return;
        }

        visibleReplacementAttempted =
                true;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            createLivePresentationResources(
                    stack
            );
        } catch (Throwable throwable) {
            liveFailureCount++;
            liveFailureReason =
                    "LIVE_RESOURCE_INIT_FAILED: "
                            + describe(throwable);
        }

        visibleReplacementContextBefore =
                glfwGetCurrentContext();

        visibleReplacementMainWindowHandleBefore =
                minecraftWindowHandle;

        visibleReplacementMainWindowFocusedBefore =
                glfwGetWindowAttrib(
                        minecraftWindowHandle,
                        GLFW_FOCUSED
                );

        try {
            /*
             * Patch 140: make the handoff window visually neutral. The
             * developer-facing "Vulkan Handoff Candidate" title must never be
             * exposed during a production-topology session.
             */
            glfwSetWindowTitle(
                    shadowCandidate,
                    "Minecraft"
            );

            synchronizeHiddenCandidateToCurrentMinecraftWindow();

            if (PRODUCTION_SINGLE_VISIBLE_SESSION) {
                configureShadowCandidateNativeFocusableForProduction();

                if (!productionPresentationFocusableStyleApplied) {
                    visibleReplacementFailureReason =
                            "PRODUCTION_PRESENTATION_FOCUSABLE_STYLE_NOT_APPLIED";
                    return;
                }
            }

            /*
             * Do NOT show the NO_API candidate yet. Start the live Vulkan
             * consumer while it remains hidden and require multiple fresh,
             * successfully-presented Minecraft frames before the first reveal.
             * This removes the multi-second blank/title handoff observed in
             * Patch 139a.
             */
            liveStreamActive =
                    liveResourcesCreated;

            visibleReplacementRevealPending =
                    liveStreamActive;

            visibleReplacementRevealDeferredUntilPrewarm =
                    true;

            visibleReplacementHidePending =
                    liveStreamActive;

            visibleReplacementUsesRealMinecraftContent =
                    realContentPresented;

            liveFailureReason =
                    liveStreamActive
                            ? "PREWARMING_LIVE_VULKAN_PRESENTATION_BEFORE_VISIBLE_HANDOFF"
                            : liveFailureReason;

            visibleReplacementFailureReason =
                    liveStreamActive
                            ? "PREWARMING_HIDDEN_VULKAN_PRESENTATION_BEFORE_ATOMIC_REVEAL"
                            : "LIVE_VULKAN_PRESENTATION_RESOURCES_NOT_ACTIVE";

            if (liveStreamActive
                    && refreshLivePresentationSwapchainIfNeeded()) {
                pumpLiveUiPresentationFrame();

                visibleReplacementPrewarmPresentCount =
                        liveFramePresentCount;
            }

            if (visibleReplacementPrewarmPresentCount
                    >= VISIBLE_REVEAL_PREWARM_PRESENTS) {
                revealVisibleReplacementWindow();
            }
        } catch (Throwable throwable) {
            visibleReplacementHidePending =
                    false;

            visibleReplacementRevealPending =
                    false;

            visibleReplacementFailureReason =
                    describe(throwable);
        } finally {
            enrich();
        }
    }

    /**
     * Synchronize the still-hidden NO_API candidate with Minecraft's current
     * lifecycle window immediately before the visible ownership switch.
     */
    private void synchronizeHiddenCandidateToCurrentMinecraftWindow() {
        if (shadowCandidate == NULL
                || minecraftWindowHandle == NULL) {
            return;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width =
                    stack.mallocInt(1);
            IntBuffer height =
                    stack.mallocInt(1);
            IntBuffer x =
                    stack.mallocInt(1);
            IntBuffer y =
                    stack.mallocInt(1);

            glfwGetWindowSize(
                    minecraftWindowHandle,
                    width,
                    height
            );

            mainWindowWidth =
                    Math.max(
                            1,
                            width.get(0)
                    );
            mainWindowHeight =
                    Math.max(
                            1,
                            height.get(0)
                    );

            glfwGetFramebufferSize(
                    minecraftWindowHandle,
                    width,
                    height
            );

            mainFramebufferWidth =
                    Math.max(
                            1,
                            width.get(0)
                    );
            mainFramebufferHeight =
                    Math.max(
                            1,
                            height.get(0)
                    );

            glfwGetWindowPos(
                    minecraftWindowHandle,
                    x,
                    y
            );

            mainWindowX =
                    x.get(0);
            mainWindowY =
                    y.get(0);

            if (glfwGetWindowMonitor(
                    minecraftWindowHandle
            ) == NULL) {
                glfwSetWindowPos(
                        shadowCandidate,
                        mainWindowX,
                        mainWindowY
                );
                glfwSetWindowSize(
                        shadowCandidate,
                        mainWindowWidth,
                        mainWindowHeight
                );
            }
        }

        captureCandidateGeometry(
                shadowCandidate
        );
    }

    /**
     * The current Gate-11 producer is still OpenGL's final MainTarget. Its
     * captured bytes are already display-referred. Presenting them through an
     * sRGB Vulkan image format applies a second transfer function and was
     * externally observed as a washed-out/high-brightness frame in Patch 140a.
     * Fail open instead of revealing such a generation.
     */
    private boolean verifyDisplayEncodedPresentationColorTransfer() {
        VulkanSwapchainSupport.Configuration configuration =
                retainedFrameSession != null
                        ? retainedFrameSession.presentationConfiguration()
                        : null;

        visibleReplacementPresentationFormat =
                configuration != null
                        ? configuration.format()
                        : VK_FORMAT_UNDEFINED;

        visibleReplacementPresentationColorSpace =
                configuration != null
                        ? configuration.colorSpace()
                        : Integer.MIN_VALUE;

        visibleReplacementColorTransferVerified =
                configuration != null
                        && (configuration.format()
                        == VK_FORMAT_B8G8R8A8_UNORM
                        || configuration.format()
                        == VK_FORMAT_R8G8B8A8_UNORM)
                        && configuration.colorSpace()
                        == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;

        visibleReplacementColorTransferPolicy =
                visibleReplacementColorTransferVerified
                        ? "DISPLAY_ENCODED_GL_FINAL_PIXELS_TO_UNORM_SWAPCHAIN"
                        : "FAIL_OPEN_SRGB_IMAGE_FORMAT_WOULD_DOUBLE_ENCODE";

        return visibleReplacementColorTransferVerified;
    }

    /**
     * Reveal only after the hidden candidate has already demonstrated fresh
     * live Vulkan presentation. The visible switch is then one desktop
     * compositor operation, not a seconds-long diagnostic window interval.
     */
    private void revealVisibleReplacementWindow() {
        if (!visibleReplacementRevealPending
                || visibleReplacementShown
                || shadowCandidate == NULL) {
            return;
        }

        if (!verifyDisplayEncodedPresentationColorTransfer()) {
            visibleReplacementRevealPending =
                    false;
            visibleReplacementHidePending =
                    false;
            liveStreamActive =
                    false;
            visibleReplacementFailureReason =
                    "DISPLAY_ENCODED_FINAL_PIXEL_COLOR_TRANSFER_UNSAFE";
            restoreOpenGlFallbackWindow(
                    true
            );
            return;
        }

        glfwShowWindow(
                shadowCandidate
        );

        visibleReplacementShown =
                glfwGetWindowAttrib(
                        shadowCandidate,
                        GLFW_VISIBLE
                ) == GLFW_TRUE;

        if (!visibleReplacementShown) {
            visibleReplacementFailureReason =
                    "GLFW_SHOW_VISIBLE_REPLACEMENT_FAILED";
            return;
        }

        if (PRODUCTION_SINGLE_VISIBLE_SESSION) {
            glfwHideWindow(
                    minecraftWindowHandle
            );

            productionPresentationOpenGlOwnerHidden =
                    glfwGetWindowAttrib(
                            minecraftWindowHandle,
                            GLFW_VISIBLE
                    ) == GLFW_FALSE;

            glfwFocusWindow(
                    shadowCandidate
            );

            productionPresentationCandidateFocused =
                    glfwGetWindowAttrib(
                            shadowCandidate,
                            GLFW_FOCUSED
                    ) == GLFW_TRUE;
        }

        visibleReplacementCandidateFocusedAfterShow =
                glfwGetWindowAttrib(
                        shadowCandidate,
                        GLFW_FOCUSED
                ) == GLFW_TRUE;

        visibleReplacementMainWindowFocusedAfterShow =
                glfwGetWindowAttrib(
                        minecraftWindowHandle,
                        GLFW_FOCUSED
                );

        visibleReplacementContextAfterShow =
                glfwGetCurrentContext();

        visibleReplacementCurrentContextPreservedAfterShow =
                visibleReplacementContextBefore
                        == visibleReplacementContextAfterShow;

        if (PRODUCTION_SINGLE_VISIBLE_SESSION
                && (!productionPresentationOpenGlOwnerHidden
                || !productionPresentationCandidateFocused
                || !visibleReplacementCurrentContextPreservedAfterShow)) {
            restoreOpenGlFallbackWindow(
                    true
            );
            visibleReplacementFailureReason =
                    "PRODUCTION_SINGLE_VISIBLE_WINDOW_ACTIVATION_INCOMPLETE";
            return;
        }

        visibleReplacementShownNanos =
                System.nanoTime();

        visibleReplacementRevealPending =
                false;

        liveFailureReason =
                "ACTIVE_LIVE_VULKAN_UI_PRESENTATION_STREAM";

        visibleReplacementFailureReason =
                PRODUCTION_SINGLE_VISIBLE_SESSION
                        ? "PRODUCTION_SINGLE_VISIBLE_VULKAN_PRESENTATION_SESSION_ACTIVE"
                        : "VISIBLE_VULKAN_REPLACEMENT_WINDOW_PRESENTED_AWAITING_HIDE";
    }

    synchronized void tickVisibleReplacementRehearsal() {
        if (!visibleReplacementHidePending) {
            enrich();
            return;
        }

        /*
         * RenderFrameEvent.Post keeps this pump alive through the world/menu
         * boundary. A normal world exit returns authority to Minecraft's
         * lifecycle window without treating it as a Vulkan failure.
         */
        if (PRODUCTION_SINGLE_VISIBLE_SESSION
                && handBackToOpenGlMenuAfterWorldExit()) {
            enrich();
            return;
        }

        if (PRODUCTION_SINGLE_VISIBLE_SESSION) {
            /*
             * Patch 146: Minecraft still toggles fullscreen/geometry through
             * its hidden lifecycle-owner GLFW handle. Mirror that intent onto
             * the one visible Vulkan candidate before swapchain inspection.
             */
            if (visibleReplacementShown
                    && !synchronizeProductionWindowTopology()) {
                failLiveUiPresentation(
                        "PRODUCTION_WINDOW_TOPOLOGY_SYNC_FAILED"
                );
                enrich();
                return;
            }

            if (liveStreamActive) {
                /*
                 * Patch 140: check the actual candidate framebuffer before
                 * every live acquire/present. Resize/maximize is therefore a
                 * non-blocking swapchain-generation change instead of
                 * VK_ERROR_OUT_OF_DATE_KHR -> two-window fail-open.
                 */
                if (refreshLivePresentationSwapchainIfNeeded()) {
                    pumpLiveUiPresentationFrame();
                }
            }

            if (visibleReplacementRevealPending) {
                visibleReplacementPrewarmPresentCount =
                        liveFramePresentCount;

                if (visibleReplacementPrewarmPresentCount
                        >= VISIBLE_REVEAL_PREWARM_PRESENTS) {
                    try {
                        revealVisibleReplacementWindow();
                    } catch (Throwable throwable) {
                        failLiveUiPresentation(
                                "VISIBLE_REVEAL_FAILURE: "
                                        + describe(throwable)
                        );
                    }
                }
            }

            if (visibleReplacementShownNanos > 0L) {
                long elapsed =
                        System.nanoTime()
                                - visibleReplacementShownNanos;

                visibleReplacementDurationMillis =
                        Math.max(
                                0L,
                                elapsed / 1_000_000L
                        );
            } else {
                visibleReplacementDurationMillis =
                        0L;
            }

            enrich();
            return;
        }

        long elapsed =
                System.nanoTime()
                        - visibleReplacementShownNanos;

        visibleReplacementDurationMillis =
                Math.max(
                        0L,
                        elapsed / 1_000_000L
                );

        if (elapsed < VISIBLE_REHEARSAL_DURATION_NANOS) {
            if (liveStreamActive
                    && refreshLivePresentationSwapchainIfNeeded()) {
                pumpLiveUiPresentationFrame();
            }

            enrich();
            return;
        }

        retireLiveUiPresentationStream(
                "BOUNDED_VISIBLE_STREAM_COMPLETE"
        );

        try {
            glfwHideWindow(
                    shadowCandidate
            );

            visibleReplacementHiddenAfterRehearsal =
                    glfwGetWindowAttrib(
                            shadowCandidate,
                            GLFW_VISIBLE
                    ) == GLFW_FALSE;

            visibleReplacementContextAfterHide =
                    glfwGetCurrentContext();

            visibleReplacementCurrentContextPreservedAfterHide =
                    visibleReplacementContextBefore
                            == visibleReplacementContextAfterHide;

            visibleReplacementMainWindowHandleAfter =
                    Minecraft.getInstance() != null
                            && Minecraft.getInstance().getWindow() != null
                            ? Minecraft.getInstance().getWindow().getWindow()
                            : NULL;

            visibleReplacementMainWindowHandlePreserved =
                    visibleReplacementMainWindowHandleBefore != NULL
                            && visibleReplacementMainWindowHandleBefore
                            == visibleReplacementMainWindowHandleAfter;

            visibleReplacementMainWindowFocusedAfterHide =
                    glfwGetWindowAttrib(
                            minecraftWindowHandle,
                            GLFW_FOCUSED
                    );

            visibleReplacementDurationMillis =
                    Math.max(
                            0L,
                            elapsed / 1_000_000L
                    );

            visibleReplacementPassed =
                    visibleReplacementAttempted
                            && visibleReplacementShown
                            && visibleReplacementHiddenAfterRehearsal
                            && !visibleReplacementCandidateFocusedAfterShow
                            && visibleReplacementCurrentContextPreservedAfterShow
                            && visibleReplacementCurrentContextPreservedAfterHide
                            && visibleReplacementMainWindowHandlePreserved
                            && shadowPassed
                            && shadowPresentAccepted
                            && visibleReplacementUsesRealMinecraftContent
                            && liveFramePresentCount > 0L
                            && liveFailureCount == 0L;

            visibleReplacementFailureReason =
                    visibleReplacementPassed
                            ? "PASSED_BOUNDED_LIVE_NO_API_VULKAN_UI_PRESENTATION_STREAM"
                            : "LIVE_NO_API_VULKAN_UI_PRESENTATION_STREAM_INCOMPLETE";
        } catch (Throwable throwable) {
            visibleReplacementPassed =
                    false;

            visibleReplacementFailureReason =
                    describe(throwable);
        } finally {
            visibleReplacementHidePending =
                    false;

            enrich();
        }
    }

    private boolean handBackToOpenGlMenuAfterWorldExit() {
        if (!PRODUCTION_SINGLE_VISIBLE_SESSION
                || productionPresentationWorldExitHandoffCompleted
                || !visibleReplacementShown
                || shadowCandidate == NULL
                || minecraftWindowHandle == NULL) {
            return false;
        }

        Minecraft minecraft =
                Minecraft.getInstance();

        /*
         * level==null is the authoritative session boundary we need here.
         * It is deliberately stronger than checking a Screen class: pause,
         * inventory and options are all Screens while gameplay is still live.
         */
        if (minecraft == null
                || minecraft.level != null) {
            return false;
        }

        productionPresentationWorldExitHandoffAttempted =
                true;
        productionPresentationWorldExitHandoffAttemptCount++;

        long started =
                System.nanoTime();

        try {
            /*
             * Retire producer/consumer ownership first. Gate-10 drains the
             * external return semaphore GPU-side on the render thread; no CPU
             * fence/queue/device wait is introduced at this gameplay boundary.
             */
            retireLiveUiPresentationStream(
                    "WORLD_SESSION_ENDED_OPENGL_MENU_HANDOFF"
            );

            restoreOpenGlFallbackWindow(
                    false
            );

            visibleReplacementHiddenAfterRehearsal =
                    glfwGetWindowAttrib(
                            shadowCandidate,
                            GLFW_VISIBLE
                    ) == GLFW_FALSE;

            visibleReplacementContextAfterHide =
                    glfwGetCurrentContext();

            visibleReplacementCurrentContextPreservedAfterHide =
                    visibleReplacementContextBefore
                            == visibleReplacementContextAfterHide;

            visibleReplacementMainWindowHandleAfter =
                    minecraft.getWindow() != null
                            ? minecraft.getWindow().getWindow()
                            : NULL;

            visibleReplacementMainWindowHandlePreserved =
                    visibleReplacementMainWindowHandleBefore != NULL
                            && visibleReplacementMainWindowHandleBefore
                            == visibleReplacementMainWindowHandleAfter;

            visibleReplacementMainWindowFocusedAfterHide =
                    glfwGetWindowAttrib(
                            minecraftWindowHandle,
                            GLFW_FOCUSED
                    );

            productionPresentationWorldExitHandoffCompleted =
                    visibleReplacementHiddenAfterRehearsal
                            && productionPresentationFallbackRestored
                            && visibleReplacementCurrentContextPreservedAfterHide
                            && visibleReplacementMainWindowHandlePreserved;

            if (productionPresentationWorldExitHandoffCompleted) {
                productionPresentationWorldExitHandoffSuccessCount++;
                productionPresentationWorldExitHandoffReason =
                        "PASSED_WORLD_SESSION_ENDED_OPENGL_MENU_HANDOFF";
            } else {
                productionPresentationWorldExitHandoffFailureCount++;
                productionPresentationWorldExitHandoffReason =
                        "WORLD_SESSION_ENDED_OPENGL_MENU_HANDOFF_INCOMPLETE";
            }
        } catch (Throwable throwable) {
            productionPresentationWorldExitHandoffFailureCount++;
            productionPresentationWorldExitHandoffReason =
                    "WORLD_SESSION_ENDED_OPENGL_MENU_HANDOFF_FAILURE: "
                            + describe(throwable);

            /*
             * If the normal handoff itself fails, escalate to the existing
             * one-way fail-open restore semantics.
             */
            restoreOpenGlFallbackWindow(
                    true
            );
        } finally {
            productionPresentationWorldExitHandoffMillis =
                    Math.max(
                            0L,
                            (System.nanoTime() - started)
                                    / 1_000_000L
                    );
        }

        return true;
    }

    private void createLivePresentationResources(
            MemoryStack stack
    ) {
        if (liveResourcesCreated
                || shadowCommandPool == NULL
                || shadowImages == null
                || shadowImages.length == 0) {
            return;
        }

        liveCommandBuffers =
                new VkCommandBuffer[LIVE_PRESENT_RING_SIZE];
        liveAcquireSemaphores =
                new long[LIVE_PRESENT_RING_SIZE];
        liveFences =
                new long[LIVE_PRESENT_RING_SIZE];
        liveRenderFinishedSemaphores =
                new long[shadowImages.length];

        VkCommandBufferAllocateInfo allocateInfo =
                VkCommandBufferAllocateInfo.calloc(stack)
                        .sType$Default()
                        .commandPool(shadowCommandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(LIVE_PRESENT_RING_SIZE);

        PointerBuffer commandPointers =
                stack.mallocPointer(LIVE_PRESENT_RING_SIZE);

        int result =
                vkAllocateCommandBuffers(
                        device,
                        allocateInfo,
                        commandPointers
                );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkAllocateCommandBuffers(live UI) failed with VkResult "
                            + result
            );
        }

        for (int index = 0;
             index < LIVE_PRESENT_RING_SIZE;
             index++) {
            liveCommandBuffers[index] =
                    new VkCommandBuffer(
                            commandPointers.get(index),
                            device
                    );
            liveAcquireSemaphores[index] =
                    createSemaphore(stack);
            liveFences[index] =
                    createSignaledFence(stack);
        }

        for (int index = 0;
             index < liveRenderFinishedSemaphores.length;
             index++) {
            liveRenderFinishedSemaphores[index] =
                    createSemaphore(stack);
        }

        liveResourcesCreated =
                true;
    }

    private long createSignaledFence(
            MemoryStack stack
    ) {
        VkFenceCreateInfo createInfo =
                VkFenceCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT);

        LongBuffer pointer =
                stack.mallocLong(1);

        int result =
                vkCreateFence(
                        device,
                        createInfo,
                        null,
                        pointer
                );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkCreateFence(live UI) failed with VkResult "
                            + result
            );
        }

        return pointer.get(0);
    }

    /**
     * Align the hidden persistent NO_API presentation generation with the
     * exact framebuffer extent of the qualified real Minecraft frame.
     *
     * <p>This is intentionally performed before the candidate is visible.
     * FrameSession polls its frame-ring fences and returns false when busy;
     * there is no gameplay fence wait, queue wait or device wait.</p>
     */
    private boolean synchronizePersistentPresentationToRealContentExtent() {
        realContentExtentSyncAttempted = true;

        if (shadowCandidate == NULL
                || shadowSurface == NULL
                || retainedFrameSession == null
                || realContentSourceWidth <= 0
                || realContentSourceHeight <= 0) {
            realContentExtentSyncState =
                    "REAL_CONTENT_EXTENT_SYNC_PREREQUISITES_INCOMPLETE";
            return false;
        }

        int logicalWidth;
        int logicalHeight;
        int framebufferWidth;
        int framebufferHeight;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);

            glfwGetWindowSize(
                    shadowCandidate,
                    width,
                    height
            );

            logicalWidth =
                    Math.max(
                            1,
                            width.get(0)
                    );
            logicalHeight =
                    Math.max(
                            1,
                            height.get(0)
                    );

            glfwGetFramebufferSize(
                    shadowCandidate,
                    width,
                    height
            );

            framebufferWidth =
                    Math.max(
                            1,
                            width.get(0)
                    );
            framebufferHeight =
                    Math.max(
                            1,
                            height.get(0)
                    );
        }

        int requestedLogicalWidth =
                Math.max(
                        1,
                        (int) Math.round(
                                (double) realContentSourceWidth
                                        * (double) logicalWidth
                                        / (double) framebufferWidth
                        )
                );

        int requestedLogicalHeight =
                Math.max(
                        1,
                        (int) Math.round(
                                (double) realContentSourceHeight
                                        * (double) logicalHeight
                                        / (double) framebufferHeight
                        )
                );

        realContentExtentRequestedLogicalWidth =
                requestedLogicalWidth;
        realContentExtentRequestedLogicalHeight =
                requestedLogicalHeight;

        glfwSetWindowSize(
                shadowCandidate,
                requestedLogicalWidth,
                requestedLogicalHeight
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);

            glfwGetFramebufferSize(
                    shadowCandidate,
                    width,
                    height
            );

            realContentExtentResultFramebufferWidth =
                    width.get(0);
            realContentExtentResultFramebufferHeight =
                    height.get(0);
        }

        if (realContentExtentResultFramebufferWidth
                != realContentSourceWidth
                || realContentExtentResultFramebufferHeight
                != realContentSourceHeight) {
            realContentExtentSyncState =
                    "REAL_CONTENT_EXTENT_CANDIDATE_FRAMEBUFFER_MISMATCH_"
                            + realContentExtentResultFramebufferWidth
                            + "x"
                            + realContentExtentResultFramebufferHeight
                            + "_EXPECTED_"
                            + realContentSourceWidth
                            + "x"
                            + realContentSourceHeight;
            return false;
        }

        realContentExtentSyncApplied = true;

        boolean prepared =
                retainedFrameSession
                        .prepareGameplayPresentationSwapchain(
                                shadowSurface,
                                shadowCandidate
                        );

        if (!prepared) {
            realContentExtentSyncState =
                    "REAL_CONTENT_EXTENT_SWAPCHAIN_REFRESH_DEFERRED";
            return false;
        }

        VulkanSwapchainSupport.Configuration configuration =
                retainedFrameSession
                        .presentationConfiguration();

        realContentExtentSwapchainPrepared =
                configuration != null
                        && configuration.width()
                        == realContentSourceWidth
                        && configuration.height()
                        == realContentSourceHeight;

        realContentExtentSyncState =
                realContentExtentSwapchainPrepared
                        ? "REAL_CONTENT_EXTENT_PERSISTENT_SWAPCHAIN_ALIGNED"
                        : "REAL_CONTENT_EXTENT_SWAPCHAIN_STILL_MISMATCHED";

        return realContentExtentSwapchainPrepared;
    }

    /**
     * Keep exactly one native window visible during the hybrid production
     * session.
     *
     * <p>Minecraft still owns {@code minecraftWindowHandle}, so vanilla F11
     * changes that hidden handle first. We treat a monitor-state mismatch as a
     * topology intent and mirror it to the visible GLFW_NO_API candidate. Once
     * both windows are windowed, the visible candidate becomes geometry
     * authority and its position/size is mirrored back to the hidden owner so
     * OpenGL fail-open restores at the correct desktop location.</p>
     *
     * <p>No GPU synchronization is performed here. We also never steal focus
     * from another application: candidate focus is repaired only when the
     * hidden Minecraft owner itself acquired focus.</p>
     */
    private boolean synchronizeProductionWindowTopology() {
        if (!PRODUCTION_SINGLE_VISIBLE_SESSION
                || !visibleReplacementShown
                || shadowCandidate == NULL
                || minecraftWindowHandle == NULL) {
            return true;
        }

        productionWindowTopologySyncCheckCount++;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long ownerMonitor =
                    glfwGetWindowMonitor(
                            minecraftWindowHandle
                    );

            long candidateMonitor =
                    glfwGetWindowMonitor(
                            shadowCandidate
                    );

            productionWindowLastOwnerMonitor =
                    ownerMonitor;
            productionWindowLastCandidateMonitor =
                    candidateMonitor;

            IntBuffer ownerX =
                    stack.mallocInt(1);
            IntBuffer ownerY =
                    stack.mallocInt(1);
            IntBuffer ownerWidth =
                    stack.mallocInt(1);
            IntBuffer ownerHeight =
                    stack.mallocInt(1);
            IntBuffer candidateX =
                    stack.mallocInt(1);
            IntBuffer candidateY =
                    stack.mallocInt(1);
            IntBuffer candidateWidth =
                    stack.mallocInt(1);
            IntBuffer candidateHeight =
                    stack.mallocInt(1);

            glfwGetWindowPos(
                    minecraftWindowHandle,
                    ownerX,
                    ownerY
            );
            glfwGetWindowSize(
                    minecraftWindowHandle,
                    ownerWidth,
                    ownerHeight
            );
            glfwGetWindowPos(
                    shadowCandidate,
                    candidateX,
                    candidateY
            );
            glfwGetWindowSize(
                    shadowCandidate,
                    candidateWidth,
                    candidateHeight
            );

            productionWindowLastOwnerX =
                    ownerX.get(0);
            productionWindowLastOwnerY =
                    ownerY.get(0);
            productionWindowLastOwnerWidth =
                    ownerWidth.get(0);
            productionWindowLastOwnerHeight =
                    ownerHeight.get(0);
            productionWindowLastCandidateX =
                    candidateX.get(0);
            productionWindowLastCandidateY =
                    candidateY.get(0);
            productionWindowLastCandidateWidth =
                    candidateWidth.get(0);
            productionWindowLastCandidateHeight =
                    candidateHeight.get(0);

            /*
             * The hidden OpenGL owner is never allowed to stay in native
             * fullscreen. This catches any non-F11 path (for example a future
             * options-screen toggle) without making a fullscreen HWND compete
             * with the one visible Vulkan window.
             */
            if (ownerMonitor != NULL) {
                int restoreX =
                        lifecycleRouter != null
                                && lifecycleRouter
                                .presentationWindowedBoundsCaptured()
                                ? lifecycleRouter
                                .presentationWindowedX()
                                : candidateX.get(0);

                int restoreY =
                        lifecycleRouter != null
                                && lifecycleRouter
                                .presentationWindowedBoundsCaptured()
                                ? lifecycleRouter
                                .presentationWindowedY()
                                : candidateY.get(0);

                int restoreWidth =
                        lifecycleRouter != null
                                && lifecycleRouter
                                .presentationWindowedBoundsCaptured()
                                ? lifecycleRouter
                                .presentationWindowedWidth()
                                : Math.max(
                                1,
                                candidateWidth.get(0)
                        );

                int restoreHeight =
                        lifecycleRouter != null
                                && lifecycleRouter
                                .presentationWindowedBoundsCaptured()
                                ? lifecycleRouter
                                .presentationWindowedHeight()
                                : Math.max(
                                1,
                                candidateHeight.get(0)
                        );

                glfwSetWindowMonitor(
                        minecraftWindowHandle,
                        NULL,
                        restoreX,
                        restoreY,
                        Math.max(
                                1,
                                restoreWidth
                        ),
                        Math.max(
                                1,
                                restoreHeight
                        ),
                        GLFW_DONT_CARE
                );

                productionWindowOwnerFullscreenNeutralizationCount++;

                ownerMonitor =
                        glfwGetWindowMonitor(
                                minecraftWindowHandle
                        );

                if (ownerMonitor != NULL) {
                    productionWindowTopologySyncFailureCount++;
                    productionWindowSingleVisibleInvariantMaintained =
                            false;
                    productionWindowTopologySyncState =
                            "HIDDEN_OPENGL_OWNER_FULLSCREEN_NEUTRALIZATION_FAILED";
                    return false;
                }
            }

            /*
             * Candidate monitor state is authoritative. The hidden OpenGL
             * window remains ordinary windowed native state even while its
             * size mirrors a fullscreen Vulkan candidate for transitional
             * MainTarget/source extent parity.
             */
            int targetX =
                    candidateX.get(0);
            int targetY =
                    candidateY.get(0);
            int targetWidth =
                    Math.max(
                            1,
                            candidateWidth.get(0)
                    );
            int targetHeight =
                    Math.max(
                            1,
                            candidateHeight.get(0)
                    );

            if (candidateMonitor != NULL) {
                productionWindowCandidateFullscreenObservedCount++;

                if (ownerWidth.get(0) != targetWidth
                        || ownerHeight.get(0) != targetHeight) {
                    glfwSetWindowSize(
                            minecraftWindowHandle,
                            targetWidth,
                            targetHeight
                    );

                    productionWindowWindowedGeometryMirrorCount++;
                }

                productionWindowTopologySyncState =
                        "VULKAN_CANDIDATE_FULLSCREEN_HIDDEN_OWNER_WINDOWED_EXTENT_ALIGNED";
            } else {
                productionWindowCandidateWindowedObservedCount++;

                boolean positionDiffers =
                        ownerX.get(0) != targetX
                                || ownerY.get(0) != targetY;

                boolean sizeDiffers =
                        ownerWidth.get(0) != targetWidth
                                || ownerHeight.get(0) != targetHeight;

                if (positionDiffers) {
                    glfwSetWindowPos(
                            minecraftWindowHandle,
                            targetX,
                            targetY
                    );
                }

                if (sizeDiffers) {
                    glfwSetWindowSize(
                            minecraftWindowHandle,
                            targetWidth,
                            targetHeight
                    );
                }

                if (positionDiffers
                        || sizeDiffers) {
                    productionWindowWindowedGeometryMirrorCount++;
                    productionWindowTopologySyncState =
                            "WINDOWED_VULKAN_CANDIDATE_GEOMETRY_MIRRORED_TO_HIDDEN_OWNER";
                } else if (productionWindowOwnerFullscreenNeutralizationCount
                        > 0L) {
                    productionWindowTopologySyncState =
                            "HIDDEN_OPENGL_OWNER_FULLSCREEN_NEUTRALIZED";
                } else {
                    productionWindowTopologySyncState =
                            "WINDOWED_TOPOLOGY_ALREADY_ALIGNED";
                }
            }

            boolean ownerWasFocused =
                    glfwGetWindowAttrib(
                            minecraftWindowHandle,
                            GLFW_FOCUSED
                    ) == GLFW_TRUE;

            if (glfwGetWindowAttrib(
                    minecraftWindowHandle,
                    GLFW_VISIBLE
            ) == GLFW_TRUE) {
                glfwHideWindow(
                        minecraftWindowHandle
                );
                productionWindowOwnerRehideCount++;
            }

            if (ownerWasFocused
                    && glfwGetWindowAttrib(
                    shadowCandidate,
                    GLFW_FOCUSED
            ) != GLFW_TRUE) {
                glfwFocusWindow(
                        shadowCandidate
                );
                productionWindowFocusRepairCount++;
            }

            boolean ownerHidden =
                    glfwGetWindowAttrib(
                            minecraftWindowHandle,
                            GLFW_VISIBLE
                    ) == GLFW_FALSE;

            boolean ownerWindowed =
                    glfwGetWindowMonitor(
                            minecraftWindowHandle
                    ) == NULL;

            if (!ownerHidden
                    || !ownerWindowed) {
                productionWindowTopologySyncFailureCount++;
                productionWindowSingleVisibleInvariantMaintained =
                        false;
                productionWindowTopologySyncState =
                        !ownerWindowed
                                ? "HIDDEN_OPENGL_OWNER_NATIVE_FULLSCREEN_FORBIDDEN"
                                : "HIDDEN_OPENGL_OWNER_COULD_NOT_BE_REHIDDEN";
                return false;
            }

            productionPresentationOpenGlOwnerHidden =
                    true;

            return true;
        } catch (Throwable throwable) {
            productionWindowTopologySyncFailureCount++;
            productionWindowSingleVisibleInvariantMaintained =
                    false;
            productionWindowTopologySyncState =
                    "TOPOLOGY_SYNC_FAILURE: "
                            + describe(throwable);

            return false;
        }
    }

    /**
     * Keep the persistent runtime swapchain synchronized with the actual
     * visible candidate framebuffer. Resize/maximize is handled by generation
     * replacement with fence polling only; gameplay never waits.
     */
    private boolean refreshLivePresentationSwapchainIfNeeded() {
        if (shadowCandidate == NULL
                || shadowSurface == NULL
                || retainedFrameSession == null) {
            return false;
        }

        liveSwapchainGeometryCheckCount++;

        int candidateWidth;
        int candidateHeight;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width =
                    stack.mallocInt(1);
            IntBuffer height =
                    stack.mallocInt(1);

            glfwGetFramebufferSize(
                    shadowCandidate,
                    width,
                    height
            );

            candidateWidth =
                    width.get(0);
            candidateHeight =
                    height.get(0);
        }

        if (candidateWidth <= 0
                || candidateHeight <= 0) {
            liveSwapchainRefreshDeferralCount++;
            liveSwapchainRefreshState =
                    "CANDIDATE_FRAMEBUFFER_ZERO_SIZED";
            return false;
        }

        VulkanSwapchainSupport.Configuration currentConfiguration =
                retainedFrameSession.presentationConfiguration();

        long currentSwapchain =
                retainedFrameSession.presentationSwapchainHandle();

        boolean geometryMatches =
                currentConfiguration != null
                        && currentSwapchain != NULL
                        && currentConfiguration.width()
                        == candidateWidth
                        && currentConfiguration.height()
                        == candidateHeight
                        && currentSwapchain
                        == shadowSwapchain;

        if (geometryMatches) {
            liveSwapchainRefreshState =
                    "LIVE_SWAPCHAIN_MATCHES_CANDIDATE_FRAMEBUFFER";
            return true;
        }

        liveSwapchainGeometryMismatchCount++;
        liveSwapchainRefreshAttemptCount++;

        /*
         * The live Gate-11 ring is independent from FrameSession's original
         * validation ring. Before switching generations, poll every Gate-11
         * fence as well. Busy means defer for another frame; never wait.
         */
        if (liveFences != null) {
            for (long fence : liveFences) {
                if (fence == NULL) {
                    continue;
                }

                int status =
                        vkGetFenceStatus(
                                device,
                                fence
                        );

                if (status == VK_NOT_READY) {
                    liveSwapchainRefreshDeferralCount++;
                    liveSwapchainRefreshFenceBusyCount++;
                    liveSwapchainRefreshState =
                            "LIVE_RING_BUSY_NONBLOCKING_DEFERRAL";
                    return false;
                }

                if (status != VK_SUCCESS) {
                    liveSwapchainRefreshFailureCount++;
                    liveSwapchainRefreshState =
                            "LIVE_RING_FENCE_STATUS_"
                                    + status;
                    return false;
                }
            }
        }

        /*
         * While the Vulkan candidate is the visible window, mirror its logical
         * content size onto Minecraft's hidden lifecycle owner. This keeps the
         * still-fail-open OpenGL MainTarget producer at the same logical size
         * until the later OpenGL-free renderer cutover removes that producer.
         * This is a CPU/window operation only; no GPU wait is introduced.
         */
        if (PRODUCTION_SINGLE_VISIBLE_SESSION
                && visibleReplacementShown
                && minecraftWindowHandle != NULL
                && glfwGetWindowMonitor(minecraftWindowHandle) == NULL) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer candidateLogicalWidth =
                        stack.mallocInt(1);
                IntBuffer candidateLogicalHeight =
                        stack.mallocInt(1);
                IntBuffer ownerLogicalWidth =
                        stack.mallocInt(1);
                IntBuffer ownerLogicalHeight =
                        stack.mallocInt(1);

                glfwGetWindowSize(
                        shadowCandidate,
                        candidateLogicalWidth,
                        candidateLogicalHeight
                );

                glfwGetWindowSize(
                        minecraftWindowHandle,
                        ownerLogicalWidth,
                        ownerLogicalHeight
                );

                int requestedWidth =
                        Math.max(
                                1,
                                candidateLogicalWidth.get(0)
                        );
                int requestedHeight =
                        Math.max(
                                1,
                                candidateLogicalHeight.get(0)
                        );

                if (ownerLogicalWidth.get(0)
                        != requestedWidth
                        || ownerLogicalHeight.get(0)
                        != requestedHeight) {
                    glfwSetWindowSize(
                            minecraftWindowHandle,
                            requestedWidth,
                            requestedHeight
                    );
                }
            }
        }

        boolean prepared =
                retainedFrameSession
                        .prepareGameplayPresentationSwapchain(
                                shadowSurface,
                                shadowCandidate
                        );

        if (!prepared) {
            liveSwapchainRefreshDeferralCount++;
            liveSwapchainRefreshState =
                    "FRAME_SESSION_NONBLOCKING_REFRESH_DEFERRED";
            return false;
        }

        long replacementSwapchain =
                retainedFrameSession
                        .presentationSwapchainHandle();

        long[] replacementImages =
                retainedFrameSession
                        .presentationSwapchainImagesSnapshot();

        VulkanSwapchainSupport.Configuration
                replacementConfiguration =
                retainedFrameSession
                        .presentationConfiguration();

        if (replacementSwapchain == NULL
                || replacementImages.length == 0
                || replacementConfiguration == null) {
            liveSwapchainRefreshFailureCount++;
            liveSwapchainRefreshState =
                    "FRAME_SESSION_REFRESH_RETURNED_INCOMPLETE_GENERATION";
            return false;
        }

        boolean generationChanged =
                replacementSwapchain
                        != shadowSwapchain;

        if (generationChanged) {
            /*
             * Per-image render-finished semaphores may still be owned by the
             * presentation engine for the retired swapchain generation even
             * when the graphics fence has completed. Retain them until the
             * shutdown-only device-idle point and allocate a fresh generation.
             */
            if (liveRenderFinishedSemaphores != null) {
                retiredLiveRenderFinishedSemaphoreGenerations
                        .add(
                                liveRenderFinishedSemaphores
                        );
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                long[] replacementRenderFinished =
                        new long[
                                replacementImages.length
                        ];

                for (int index = 0;
                     index < replacementRenderFinished.length;
                     index++) {
                    replacementRenderFinished[index] =
                            createSemaphore(
                                    stack
                            );
                }

                liveRenderFinishedSemaphores =
                        replacementRenderFinished;
            }

            liveSwapchainGenerationChangeCount++;
            liveRingCursor =
                    0;
        }

        shadowSwapchain =
                replacementSwapchain;
        shadowImages =
                replacementImages;

        shadowImageCount =
                replacementImages.length;
        shadowFormat =
                replacementConfiguration.format();
        shadowColorSpace =
                replacementConfiguration.colorSpace();
        shadowPresentMode =
                replacementConfiguration.presentMode();
        shadowWidth =
                replacementConfiguration.width();
        shadowHeight =
                replacementConfiguration.height();
        shadowImageUsage =
                replacementConfiguration.imageUsage();
        shadowCompositeAlpha =
                replacementConfiguration.compositeAlpha();

        liveSwapchainRefreshSuccessCount++;
        liveSwapchainRefreshState =
                generationChanged
                        ? "PERSISTENT_SWAPCHAIN_GENERATION_REFRESHED_NONBLOCKING"
                        : "PERSISTENT_SWAPCHAIN_CONFIGURATION_REFRESHED_NONBLOCKING";

        return true;
    }

    private void pumpLiveUiPresentationFrame() {
        if (!liveStreamActive
                || !liveResourcesCreated
                || shadowSwapchain == NULL
                || shadowCandidate == NULL) {
            return;
        }

        int slot =
                liveRingCursor;

        long fence =
                liveFences[slot];

        liveFencePollCount++;
        int fenceStatus =
                vkGetFenceStatus(
                        device,
                        fence
                );

        if (fenceStatus == VK_NOT_READY) {
            liveFenceNotReadyCount++;
            liveBackpressureSkipCount++;
            return;
        }

        if (fenceStatus != VK_SUCCESS) {
            failLiveUiPresentation(
                    "LIVE_FENCE_STATUS_"
                            + fenceStatus
            );
            return;
        }

        if (livePendingFrame == null) {
            livePendingFrame =
                    VulkanGate10VisibleScreenRehearsal
                            .claimQualifiedFrameForGate11();

            if (livePendingFrame == null) {
                return;
            }

            liveFrameClaimCount++;

            /*
             * Patch 144 visibility-quality proof: the live producer must
             * converge to the current presentation extent after a resize.
             * This is observation only; Gate 10 owns the nonblocking interop
             * image generation rotation.
             */
            liveSourceExtentObservationCount++;
            liveLastSourceWidth =
                    livePendingFrame.width();
            liveLastSourceHeight =
                    livePendingFrame.height();
            liveLastSourceExtentMatchesSwapchain =
                    liveLastSourceWidth == shadowWidth
                            && liveLastSourceHeight == shadowHeight;

            if (liveLastSourceExtentMatchesSwapchain) {
                liveSourceExtentExactMatchCount++;
            } else {
                liveSourceExtentMismatchCount++;
            }
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer imageIndex =
                    stack.ints(-1);

            liveLastAcquireResult =
                    vkAcquireNextImageKHR(
                            device,
                            shadowSwapchain,
                            0L,
                            liveAcquireSemaphores[slot],
                            NULL,
                            imageIndex
                    );

            if (liveLastAcquireResult
                    == VK_ERROR_OUT_OF_DATE_KHR) {
                liveAcquireOutOfDateCount++;

                if (!refreshLivePresentationSwapchainIfNeeded()) {
                    liveBackpressureSkipCount++;
                } else {
                    liveSwapchainRefreshState =
                            "RECOVERED_ACQUIRE_OUT_OF_DATE";
                }

                /*
                 * The qualified source frame was not submitted, so keep it
                 * claimed and retry it against the refreshed generation.
                 */
                return;
            }

            if (liveLastAcquireResult == VK_NOT_READY) {
                liveAcquireNotReadyCount++;
                liveBackpressureSkipCount++;
                return;
            }

            if (liveLastAcquireResult == VK_SUBOPTIMAL_KHR) {
                liveAcquireSuboptimalCount++;
            } else if (liveLastAcquireResult != VK_SUCCESS) {
                failLiveUiPresentation(
                        "LIVE_ACQUIRE_RESULT_"
                                + liveLastAcquireResult
                );
                return;
            }

            int image =
                    imageIndex.get(0);

            if (image < 0
                    || image >= shadowImages.length) {
                failLiveUiPresentation(
                        "LIVE_ACQUIRE_IMAGE_INDEX_OUT_OF_RANGE_"
                                + image
                );
                return;
            }

            int resetResult =
                    vkResetFences(
                            device,
                            stack.longs(fence)
                    );

            if (resetResult != VK_SUCCESS) {
                failLiveUiPresentation(
                        "LIVE_RESET_FENCE_RESULT_"
                                + resetResult
                );
                return;
            }

            resetResult =
                    vkResetCommandBuffer(
                            liveCommandBuffers[slot],
                            0
                    );

            if (resetResult != VK_SUCCESS) {
                failLiveUiPresentation(
                        "LIVE_RESET_COMMAND_BUFFER_RESULT_"
                                + resetResult
                );
                return;
            }

            recordLiveUiPresentCommand(
                    liveCommandBuffers[slot],
                    livePendingFrame,
                    shadowImages[image],
                    image,
                    stack
            );

            VkSubmitInfo.Buffer submitInfo =
                    VkSubmitInfo.calloc(1, stack);

            submitInfo.get(0)
                    .sType$Default()
                    .pWaitSemaphores(
                            stack.longs(
                                    liveAcquireSemaphores[slot],
                                    livePendingFrame.readySemaphore()
                            )
                    )
                    .pWaitDstStageMask(
                            stack.ints(
                                    VK_PIPELINE_STAGE_TRANSFER_BIT,
                                    VK_PIPELINE_STAGE_TRANSFER_BIT
                            )
                    )
                    .pCommandBuffers(
                            stack.pointers(
                                    liveCommandBuffers[slot].address()
                            )
                    )
                    .pSignalSemaphores(
                            stack.longs(
                                    liveRenderFinishedSemaphores[image],
                                    livePendingFrame.returnSemaphore()
                            )
                    );

            liveLastQueueSubmitResult =
                    vkQueueSubmit(
                            graphicsQueue,
                            submitInfo,
                            fence
                    );

            if (liveLastQueueSubmitResult != VK_SUCCESS) {
                VulkanGate10VisibleScreenRehearsal
                        .rejectGate11Frame(
                                livePendingFrame.sequence(),
                                "LIVE_GATE11_QUEUE_SUBMIT_FAILED_"
                                        + liveLastQueueSubmitResult
                        );

                failLiveUiPresentation(
                        "LIVE_QUEUE_SUBMIT_RESULT_"
                                + liveLastQueueSubmitResult
                );
                return;
            }

            liveFrameSubmitCount++;

            if (!VulkanGate10VisibleScreenRehearsal
                    .acknowledgeGate11FrameSubmitted(
                            livePendingFrame.sequence()
                    )) {
                failLiveUiPresentation(
                        "LIVE_GATE10_SUBMIT_ACK_REJECTED"
                );
                return;
            }

            liveFrameSubmitAckCount++;

            VkPresentInfoKHR presentInfo =
                    VkPresentInfoKHR.calloc(stack)
                            .sType$Default()
                            .pWaitSemaphores(
                                    stack.longs(
                                            liveRenderFinishedSemaphores[image]
                                    )
                            )
                            .swapchainCount(1)
                            .pSwapchains(
                                    stack.longs(shadowSwapchain)
                            )
                            .pImageIndices(
                                    stack.ints(image)
                            );

            liveLastPresentResult =
                    vkQueuePresentKHR(
                            presentQueue,
                            presentInfo
                    );

            if (liveLastPresentResult
                    == VK_ERROR_OUT_OF_DATE_KHR) {
                /*
                 * The source frame was already consumed and acknowledged by
                 * the GPU submission, but the desktop presentation generation
                 * changed between acquire and present. Count this truthfully as
                 * one recoverable presentation drop, retire the old generation,
                 * and continue without exposing the OpenGL fallback window.
                 */
                livePresentOutOfDateCount++;
                liveRecoverablePresentDropCount++;

                livePendingFrame =
                        null;
                liveRingCursor =
                        (liveRingCursor + 1)
                                % LIVE_PRESENT_RING_SIZE;

                if (!refreshLivePresentationSwapchainIfNeeded()) {
                    liveBackpressureSkipCount++;
                } else {
                    liveSwapchainRefreshState =
                            "RECOVERED_PRESENT_OUT_OF_DATE";
                }

                return;
            }

            if (liveLastPresentResult != VK_SUCCESS
                    && liveLastPresentResult != VK_SUBOPTIMAL_KHR) {
                failLiveUiPresentation(
                        "LIVE_QUEUE_PRESENT_RESULT_"
                                + liveLastPresentResult
                );
                return;
            }

            if (liveLastPresentResult == VK_SUBOPTIMAL_KHR) {
                livePresentSuboptimalCount++;
            }

            if (shadowSwapchainBorrowedFromRuntime
                    && retainedFrameSession != null) {
                retainedFrameSession
                        .markPresentationImagePresented(
                                image
                        );
            }

            liveFramePresentCount++;
            livePendingFrame =
                    null;
            liveRingCursor =
                    (liveRingCursor + 1)
                            % LIVE_PRESENT_RING_SIZE;

            if (liveLastAcquireResult == VK_SUBOPTIMAL_KHR
                    || liveLastPresentResult == VK_SUBOPTIMAL_KHR) {
                refreshLivePresentationSwapchainIfNeeded();
            }
        } catch (Throwable throwable) {
            if (livePendingFrame != null) {
                VulkanGate10VisibleScreenRehearsal
                        .rejectGate11Frame(
                                livePendingFrame.sequence(),
                                "LIVE_GATE11_EXCEPTION"
                        );
            }

            failLiveUiPresentation(
                    describe(throwable)
            );
        }
    }

    private void recordLiveUiPresentCommand(
            VkCommandBuffer commandBuffer,
            VulkanGate10VisibleScreenRehearsal.QualifiedFrame frame,
            long image,
            int imageIndex,
            MemoryStack stack
    ) {
        VkCommandBufferBeginInfo beginInfo =
                VkCommandBufferBeginInfo.calloc(stack)
                        .sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

        int result =
                vkBeginCommandBuffer(
                        commandBuffer,
                        beginInfo
                );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkBeginCommandBuffer(live UI) failed with VkResult "
                            + result
            );
        }

        imageBarrier(
                commandBuffer,
                frame.image(),
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                stack
        );
        int destinationOldLayout =
                shadowSwapchainBorrowedFromRuntime
                        && retainedFrameSession != null
                        ? retainedFrameSession
                        .presentationImageOldLayout(
                                imageIndex
                        )
                        : VK_IMAGE_LAYOUT_UNDEFINED;

        imageBarrier(
                commandBuffer,
                image,
                destinationOldLayout,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                stack
        );

        VkImageBlit.Buffer blit =
                VkImageBlit.calloc(1, stack);

        blit.get(0).srcSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        blit.get(0).dstSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

        blit.get(0).srcOffsets(0)
                .set(0, frame.height(), 0);
        blit.get(0).srcOffsets(1)
                .set(frame.width(), 0, 1);
        blit.get(0).dstOffsets(0)
                .set(0, 0, 0);
        blit.get(0).dstOffsets(1)
                .set(shadowWidth, shadowHeight, 1);

        vkCmdBlitImage(
                commandBuffer,
                frame.image(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                blit,
                VK_FILTER_NEAREST
        );

        imageBarrier(
                commandBuffer,
                frame.image(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                stack
        );
        imageBarrier(
                commandBuffer,
                image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                stack
        );

        result =
                vkEndCommandBuffer(
                        commandBuffer
                );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkEndCommandBuffer(live UI) failed with VkResult "
                            + result
            );
        }
    }

    private void failLiveUiPresentation(
            String reason
    ) {
        liveFailureCount++;
        liveFailureReason =
                reason == null || reason.isBlank()
                        ? "LIVE_UI_PRESENTATION_FAILURE"
                        : reason;
        retireLiveUiPresentationStream(
                liveFailureReason
        );

        if (PRODUCTION_SINGLE_VISIBLE_SESSION) {
            restoreOpenGlFallbackWindow(
                    true
            );
        }
    }

    private void retireLiveUiPresentationStream(
            String reason
    ) {
        if (liveStreamRetired) {
            return;
        }

        liveStreamActive =
                false;
        liveStreamRetired =
                true;
        liveFailureReason =
                reason == null || reason.isBlank()
                        ? liveFailureReason
                        : reason;

        VulkanGate10VisibleScreenRehearsal
                .stopGate11LiveStream(
                        liveFailureReason
                );
    }

    private void configureShadowCandidateNativeFocusableForProduction() {
        productionPresentationFocusableStyleAttempted =
                true;

        try {
            if (shadowCandidateNativeHandle == NULL) {
                shadowCandidateNativeHandle =
                        GLFWNativeWin32.glfwGetWin32Window(
                                shadowCandidate
                        );
            }

            if (shadowCandidateNativeHandle == NULL) {
                return;
            }

            productionPresentationExtendedStyleBefore =
                    User32.GetWindowLongPtr(
                            shadowCandidateNativeHandle,
                            User32.GWL_EXSTYLE
                    );

            long noActivateMask =
                    Integer.toUnsignedLong(
                            User32.WS_EX_NOACTIVATE
                    );

            long requestedStyle =
                    productionPresentationExtendedStyleBefore
                            & ~noActivateMask;

            User32.SetWindowLongPtr(
                    shadowCandidateNativeHandle,
                    User32.GWL_EXSTYLE,
                    requestedStyle
            );

            boolean refreshed =
                    User32.SetWindowPos(
                            shadowCandidateNativeHandle,
                            NULL,
                            0,
                            0,
                            0,
                            0,
                            User32.SWP_NOMOVE
                                    | User32.SWP_NOSIZE
                                    | User32.SWP_NOZORDER
                                    | User32.SWP_FRAMECHANGED
                    );

            productionPresentationExtendedStyleAfter =
                    User32.GetWindowLongPtr(
                            shadowCandidateNativeHandle,
                            User32.GWL_EXSTYLE
                    );

            productionPresentationFocusableStyleApplied =
                    refreshed
                            && (productionPresentationExtendedStyleAfter
                            & noActivateMask) == 0L;
        } catch (Throwable throwable) {
            productionPresentationFocusableStyleApplied =
                    false;

            visibleReplacementFailureReason =
                    "PRODUCTION_FOCUSABLE_STYLE_FAILURE: "
                            + describe(throwable);
        }
    }

    private void restoreOpenGlFallbackWindow(
            boolean failure
    ) {
        if (!PRODUCTION_SINGLE_VISIBLE_SESSION) {
            return;
        }

        if (failure) {
            productionPresentationFailOpenTriggered =
                    true;
        }

        try {
            if (lifecycleRouter != null) {
                lifecycleRouter
                        .normalizePresentationWindowedForHide();
            }

            if (shadowCandidate != NULL
                    && glfwGetWindowAttrib(
                    shadowCandidate,
                    GLFW_VISIBLE
            ) == GLFW_TRUE) {
                glfwHideWindow(
                        shadowCandidate
                );
            }

            if (minecraftWindowHandle != NULL) {
                if (lifecycleRouter != null
                        && lifecycleRouter
                        .presentationWindowedBoundsCaptured()) {
                    int restoreX =
                            lifecycleRouter
                                    .presentationWindowedX();
                    int restoreY =
                            lifecycleRouter
                                    .presentationWindowedY();
                    int restoreWidth =
                            Math.max(
                                    1,
                                    lifecycleRouter
                                            .presentationWindowedWidth()
                            );
                    int restoreHeight =
                            Math.max(
                                    1,
                                    lifecycleRouter
                                            .presentationWindowedHeight()
                            );

                    if (glfwGetWindowMonitor(
                            minecraftWindowHandle
                    ) != NULL) {
                        glfwSetWindowMonitor(
                                minecraftWindowHandle,
                                NULL,
                                restoreX,
                                restoreY,
                                restoreWidth,
                                restoreHeight,
                                GLFW_DONT_CARE
                        );
                    } else {
                        glfwSetWindowPos(
                                minecraftWindowHandle,
                                restoreX,
                                restoreY
                        );
                        glfwSetWindowSize(
                                minecraftWindowHandle,
                                restoreWidth,
                                restoreHeight
                        );
                    }
                }

                glfwShowWindow(
                        minecraftWindowHandle
                );
                glfwFocusWindow(
                        minecraftWindowHandle
                );

                productionPresentationFallbackRestored =
                        glfwGetWindowAttrib(
                                minecraftWindowHandle,
                                GLFW_VISIBLE
                        ) == GLFW_TRUE;
            }
        } catch (Throwable throwable) {
            productionPresentationFallbackRestored =
                    false;

            if (visibleReplacementFailureReason.isBlank()
                    || visibleReplacementFailureReason.equals(
                    "NOT_ATTEMPTED"
            )) {
                visibleReplacementFailureReason =
                        "OPENGL_FAILOPEN_RESTORE_FAILURE: "
                                + describe(throwable);
            }
        }

        visibleReplacementHidePending =
                false;
    }

    private void finalizeProductionPresentationSessionAtShutdown() {
        if (!PRODUCTION_SINGLE_VISIBLE_SESSION
                || productionPresentationShutdownFinalized
                || !visibleReplacementAttempted) {
            return;
        }

        productionPresentationShutdownFinalized =
                true;

        long elapsed =
                visibleReplacementShownNanos > 0L
                        ? System.nanoTime()
                        - visibleReplacementShownNanos
                        : 0L;

        visibleReplacementDurationMillis =
                Math.max(
                        0L,
                        elapsed / 1_000_000L
                );

        /*
         * Freeze the producer first, then consume any already-published tail
         * frame before retiring the consumer. This is the session-lifetime
         * equivalent of the earlier bounded two-second terminal drain.
         *
         * Patch 131 exception: a successful world-exit handoff already retired
         * the producer/consumer on the render thread and restored the OpenGL
         * menu window. Do not overwrite that successful stop reason with a
         * synthetic shutdown reason and do not add a second GPU drain.
         *
         * A device-idle wait is used here only during actual runtime shutdown,
         * never during gameplay or the world->menu handoff.
         */
        if (!productionPresentationWorldExitHandoffCompleted) {
            VulkanGate10VisibleScreenRehearsal
                    .stopGate11LiveStream(
                            "PRODUCTION_SESSION_DRAINING"
                    );

            if (device != null
                    && liveStreamActive) {
                try {
                    shadowShutdownDeviceWaitIdleUsed =
                            true;
                    shadowShutdownDeviceWaitIdleResult =
                            vkDeviceWaitIdle(
                                    device
                            );
                } catch (Throwable throwable) {
                    shadowShutdownDeviceWaitIdleResult =
                            Integer.MIN_VALUE;
                }

                for (int attempt = 0;
                     attempt < 4 && liveStreamActive;
                     attempt++) {
                    pumpLiveUiPresentationFrame();
                }
            }

            retireLiveUiPresentationStream(
                    "PRODUCTION_SESSION_SHUTDOWN"
            );
        }

        try {
            if (shadowCandidate != NULL) {
                glfwHideWindow(
                        shadowCandidate
                );

                visibleReplacementHiddenAfterRehearsal =
                        glfwGetWindowAttrib(
                                shadowCandidate,
                                GLFW_VISIBLE
                        ) == GLFW_FALSE;
            }

            if (minecraftWindowHandle != NULL) {
                glfwShowWindow(
                        minecraftWindowHandle
                );

                productionPresentationFallbackRestored =
                        glfwGetWindowAttrib(
                                minecraftWindowHandle,
                                GLFW_VISIBLE
                        ) == GLFW_TRUE;

                visibleReplacementMainWindowFocusedAfterHide =
                        glfwGetWindowAttrib(
                                minecraftWindowHandle,
                                GLFW_FOCUSED
                        );
            }

            visibleReplacementContextAfterHide =
                    glfwGetCurrentContext();

            visibleReplacementCurrentContextPreservedAfterHide =
                    visibleReplacementContextBefore
                            == visibleReplacementContextAfterHide;

            visibleReplacementMainWindowHandleAfter =
                    Minecraft.getInstance() != null
                            && Minecraft.getInstance().getWindow() != null
                            ? Minecraft.getInstance().getWindow().getWindow()
                            : NULL;

            visibleReplacementMainWindowHandlePreserved =
                    visibleReplacementMainWindowHandleBefore != NULL
                            && visibleReplacementMainWindowHandleBefore
                            == visibleReplacementMainWindowHandleAfter;

            visibleReplacementPassed =
                    visibleReplacementShown
                            && productionPresentationFocusableStyleApplied
                            && productionPresentationOpenGlOwnerHidden
                            && productionPresentationCandidateFocused
                            && visibleReplacementHiddenAfterRehearsal
                            && visibleReplacementCurrentContextPreservedAfterShow
                            && visibleReplacementCurrentContextPreservedAfterHide
                            && visibleReplacementMainWindowHandlePreserved
                            && visibleReplacementUsesRealMinecraftContent
                            && liveFramePresentCount >= 120L
                            && liveFailureCount == 0L
                            && productionWindowSingleVisibleInvariantMaintained
                            && productionWindowTopologySyncFailureCount == 0L
                            && !productionPresentationFailOpenTriggered
                            && productionPresentationFallbackRestored;

            visibleReplacementFailureReason =
                    visibleReplacementPassed
                            ? "PASSED_PRODUCTION_SINGLE_VISIBLE_VULKAN_PRESENTATION_SESSION"
                            : "PRODUCTION_SINGLE_VISIBLE_VULKAN_PRESENTATION_SESSION_INCOMPLETE";
        } catch (Throwable throwable) {
            visibleReplacementPassed =
                    false;
            visibleReplacementFailureReason =
                    "PRODUCTION_SESSION_SHUTDOWN_FINALIZE_FAILURE: "
                            + describe(throwable);
        } finally {
            visibleReplacementHidePending =
                    false;
        }
    }

    private void configureShadowCandidateNativeNoActivate() {
        shadowCandidateNativeNoActivateAttempted =
                true;

        try {
            shadowCandidateNativeHandle =
                    GLFWNativeWin32.glfwGetWin32Window(
                            shadowCandidate
                    );

            if (shadowCandidateNativeHandle == NULL) {
                return;
            }

            shadowCandidateExtendedStyleBefore =
                    User32.GetWindowLongPtr(
                            shadowCandidateNativeHandle,
                            User32.GWL_EXSTYLE
                    );

            long requestedStyle =
                    shadowCandidateExtendedStyleBefore
                            | Integer.toUnsignedLong(
                            User32.WS_EX_NOACTIVATE
                    );

            User32.SetWindowLongPtr(
                    shadowCandidateNativeHandle,
                    User32.GWL_EXSTYLE,
                    requestedStyle
            );

            /*
             * SetWindowLongPtr changes the stored style bits immediately, but
             * Windows may keep the old non-client activation behavior until a
             * frame refresh. 122 proved exactly that contradiction: the bit was
             * present while the candidate still generated focus-gain events.
             */
            shadowCandidateNativeNoActivateFrameRefreshAttempted =
                    true;

            shadowCandidateNativeNoActivateFrameRefreshApplied =
                    User32.SetWindowPos(
                            shadowCandidateNativeHandle,
                            NULL,
                            0,
                            0,
                            0,
                            0,
                            User32.SWP_NOMOVE
                                    | User32.SWP_NOSIZE
                                    | User32.SWP_NOZORDER
                                    | User32.SWP_NOACTIVATE
                                    | User32.SWP_FRAMECHANGED
                    );

            shadowCandidateExtendedStyleAfter =
                    User32.GetWindowLongPtr(
                            shadowCandidateNativeHandle,
                            User32.GWL_EXSTYLE
                    );

            shadowCandidateNativeNoActivateApplied =
                    (shadowCandidateExtendedStyleAfter
                            & Integer.toUnsignedLong(
                            User32.WS_EX_NOACTIVATE
                    )) != 0L
                            && shadowCandidateNativeNoActivateFrameRefreshApplied;
        } catch (Throwable throwable) {
            shadowCandidateNativeNoActivateApplied =
                    false;
            shadowCandidateNativeNoActivateFrameRefreshApplied =
                    false;

            if (shadowFailureReason.equals(
                    "NOT_ATTEMPTED"
            )) {
                shadowFailureReason =
                        "WIN32_NOACTIVATE_STYLE_FAILURE: "
                                + describe(throwable);
            }
        }
    }

    private ShadowConfiguration queryShadowConfiguration(
            long surface,
            MemoryStack stack
    ) {
        IntBuffer supported = stack.ints(0);

        shadowSurfaceSupportResult =
                vkGetPhysicalDeviceSurfaceSupportKHR(
                        physicalDevice,
                        presentQueueFamilyIndex,
                        surface,
                        supported
                );

        if (shadowSurfaceSupportResult != VK_SUCCESS
                || supported.get(0) == 0) {
            return null;
        }

        VkSurfaceCapabilitiesKHR capabilities =
                VkSurfaceCapabilitiesKHR.calloc(stack);

        shadowCapabilitiesResult =
                vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                        physicalDevice,
                        surface,
                        capabilities
                );

        if (shadowCapabilitiesResult != VK_SUCCESS
                || (capabilities.supportedUsageFlags()
                & VK_IMAGE_USAGE_TRANSFER_DST_BIT) == 0) {
            return null;
        }

        IntBuffer count = stack.ints(0);

        shadowFormatsResult =
                vkGetPhysicalDeviceSurfaceFormatsKHR(
                        physicalDevice,
                        surface,
                        count,
                        null
                );

        if (shadowFormatsResult != VK_SUCCESS
                || count.get(0) <= 0) {
            return null;
        }

        VkSurfaceFormatKHR.Buffer formats =
                VkSurfaceFormatKHR.calloc(
                        count.get(0),
                        stack
                );

        shadowFormatsResult =
                vkGetPhysicalDeviceSurfaceFormatsKHR(
                        physicalDevice,
                        surface,
                        count,
                        formats
                );

        if (shadowFormatsResult != VK_SUCCESS
                && shadowFormatsResult != VK_INCOMPLETE) {
            return null;
        }

        VkSurfaceFormatKHR selectedFormat =
                selectSurfaceFormat(formats);

        count.put(0, 0);

        shadowPresentModesResult =
                vkGetPhysicalDeviceSurfacePresentModesKHR(
                        physicalDevice,
                        surface,
                        count,
                        null
                );

        if (shadowPresentModesResult != VK_SUCCESS
                || count.get(0) <= 0) {
            return null;
        }

        IntBuffer presentModes =
                stack.mallocInt(count.get(0));

        shadowPresentModesResult =
                vkGetPhysicalDeviceSurfacePresentModesKHR(
                        physicalDevice,
                        surface,
                        count,
                        presentModes
                );

        if (shadowPresentModesResult != VK_SUCCESS
                && shadowPresentModesResult != VK_INCOMPLETE) {
            return null;
        }

        int selectedPresentMode =
                selectPresentMode(presentModes);

        int imageCount =
                Math.max(
                        capabilities.minImageCount(),
                        capabilities.minImageCount() + 1
                );

        if (capabilities.maxImageCount() > 0) {
            imageCount = Math.min(
                    imageCount,
                    capabilities.maxImageCount()
            );
        }

        int width;
        int height;

        if (capabilities.currentExtent().width() != -1) {
            width = capabilities.currentExtent().width();
            height = capabilities.currentExtent().height();
        } else {
            width = clamp(
                    Math.max(1, mainFramebufferWidth),
                    capabilities.minImageExtent().width(),
                    capabilities.maxImageExtent().width()
            );
            height = clamp(
                    Math.max(1, mainFramebufferHeight),
                    capabilities.minImageExtent().height(),
                    capabilities.maxImageExtent().height()
            );
        }

        int compositeAlpha =
                selectCompositeAlpha(
                        capabilities.supportedCompositeAlpha()
                );

        return new ShadowConfiguration(
                selectedFormat.format(),
                selectedFormat.colorSpace(),
                selectedPresentMode,
                imageCount,
                width,
                height,
                capabilities.currentTransform(),
                compositeAlpha
        );
    }

    private void createShadowSwapchain(
            ShadowConfiguration configuration,
            MemoryStack stack
    ) {
        VkSwapchainCreateInfoKHR createInfo =
                VkSwapchainCreateInfoKHR.calloc(stack)
                        .sType$Default()
                        .surface(shadowSurface)
                        .minImageCount(configuration.imageCount())
                        .imageFormat(configuration.format())
                        .imageColorSpace(configuration.colorSpace())
                        .imageArrayLayers(1)
                        .imageUsage(VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .preTransform(configuration.preTransform())
                        .compositeAlpha(configuration.compositeAlpha())
                        .presentMode(configuration.presentMode())
                        .clipped(true)
                        .oldSwapchain(NULL);

        createInfo.imageExtent()
                .width(configuration.width())
                .height(configuration.height());

        if (queues.sharedFamily()) {
            createInfo.imageSharingMode(
                    VK_SHARING_MODE_EXCLUSIVE
            );
        } else {
            createInfo
                    .imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                    .pQueueFamilyIndices(
                            stack.ints(
                                    queues.graphicsFamilyIndex(),
                                    queues.presentFamilyIndex()
                            )
                    );
        }

        LongBuffer swapchainPointer = stack.mallocLong(1);

        shadowCreateSwapchainResult =
                vkCreateSwapchainKHR(
                        device,
                        createInfo,
                        null,
                        swapchainPointer
                );

        if (shadowCreateSwapchainResult != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkCreateSwapchainKHR failed with VkResult "
                            + shadowCreateSwapchainResult
            );
        }

        shadowSwapchain = swapchainPointer.get(0);

        if (shadowSwapchain == NULL) {
            throw new IllegalStateException(
                    "vkCreateSwapchainKHR returned NULL."
            );
        }

        shadowSwapchainCreated = true;

        IntBuffer count = stack.ints(0);

        shadowGetImagesCountResult =
                vkGetSwapchainImagesKHR(
                        device,
                        shadowSwapchain,
                        count,
                        null
                );

        if (shadowGetImagesCountResult != VK_SUCCESS
                || count.get(0) <= 0) {
            throw new IllegalStateException(
                    "vkGetSwapchainImagesKHR(count) failed with VkResult "
                            + shadowGetImagesCountResult
            );
        }

        LongBuffer images = stack.mallocLong(count.get(0));

        shadowGetImagesResult =
                vkGetSwapchainImagesKHR(
                        device,
                        shadowSwapchain,
                        count,
                        images
                );

        if (shadowGetImagesResult != VK_SUCCESS
                && shadowGetImagesResult != VK_INCOMPLETE) {
            throw new IllegalStateException(
                    "vkGetSwapchainImagesKHR(list) failed with VkResult "
                            + shadowGetImagesResult
            );
        }

        shadowImages = new long[count.get(0)];

        for (int index = 0; index < shadowImages.length; index++) {
            shadowImages[index] = images.get(index);
        }

        shadowSwapchainImagesEnumerated =
                shadowImages.length > 0;
    }

    private void createShadowCommandResources(
            MemoryStack stack
    ) {
        shadowAcquireSemaphore = createSemaphore(stack);
        shadowRenderFinishedSemaphore = createSemaphore(stack);

        VkCommandPoolCreateInfo poolInfo =
                VkCommandPoolCreateInfo.calloc(stack)
                        .sType$Default()
                        .flags(
                                VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT
                        )
                        .queueFamilyIndex(
                                queues.graphicsFamilyIndex()
                        );

        LongBuffer poolPointer = stack.mallocLong(1);

        int result = vkCreateCommandPool(
                device,
                poolInfo,
                null,
                poolPointer
        );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkCreateCommandPool failed with VkResult "
                            + result
            );
        }

        shadowCommandPool = poolPointer.get(0);

        VkCommandBufferAllocateInfo allocateInfo =
                VkCommandBufferAllocateInfo.calloc(stack)
                        .sType$Default()
                        .commandPool(shadowCommandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);

        PointerBuffer commandPointer = stack.mallocPointer(1);

        result = vkAllocateCommandBuffers(
                device,
                allocateInfo,
                commandPointer
        );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkAllocateCommandBuffers failed with VkResult "
                            + result
            );
        }

        shadowCommandBuffer =
                new VkCommandBuffer(
                        commandPointer.get(0),
                        device
                );
    }

    private long createSemaphore(
            MemoryStack stack
    ) {
        VkSemaphoreCreateInfo createInfo =
                VkSemaphoreCreateInfo.calloc(stack)
                        .sType$Default();

        LongBuffer pointer = stack.mallocLong(1);

        int result = vkCreateSemaphore(
                device,
                createInfo,
                null,
                pointer
        );

        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkCreateSemaphore failed with VkResult "
                            + result
            );
        }

        return pointer.get(0);
    }

    private void recordShadowPresentCommand(
            long image,
            int imageIndex,
            MemoryStack stack
    ) {
        VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

        int result = vkBeginCommandBuffer(shadowCommandBuffer, beginInfo);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkBeginCommandBuffer failed with VkResult " + result
            );
        }

        imageBarrier(
                shadowCommandBuffer,
                realContentFrame.image(),
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                stack
        );
        int destinationOldLayout =
                shadowSwapchainBorrowedFromRuntime
                        && retainedFrameSession != null
                        ? retainedFrameSession
                        .presentationImageOldLayout(
                                imageIndex
                        )
                        : VK_IMAGE_LAYOUT_UNDEFINED;

        imageBarrier(
                shadowCommandBuffer,
                image,
                destinationOldLayout,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                0,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                stack
        );

        VkFormatProperties formatProperties =
                VkFormatProperties.calloc(stack);
        vkGetPhysicalDeviceFormatProperties(
                physicalDevice,
                realContentFrame.format(),
                formatProperties
        );

        int optimalTilingFeatures =
                formatProperties.optimalTilingFeatures();
        realContentBlitSupported =
                (optimalTilingFeatures & VK_FORMAT_FEATURE_BLIT_SRC_BIT) != 0
                        && (optimalTilingFeatures
                        & VK_FORMAT_FEATURE_BLIT_DST_BIT) != 0;

        if (!realContentBlitSupported) {
            throw new IllegalStateException(
                    "Gate-11 real-content format lacks Vulkan blit support: "
                            + realContentFrame.format()
            );
        }

        VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
        blit.get(0).srcSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);
        blit.get(0).dstSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0)
                .baseArrayLayer(0)
                .layerCount(1);

        /*
         * OpenGL framebuffer content reaches this Stage-5 bridge with the Y
         * axis opposite to the Vulkan presentation convention. The previous
         * 180-degree correction reversed X as well and therefore over-corrected
         * the visible frame. Keep X canonical and reverse only source Y on-GPU.
         * No CPU readback/copy is introduced.
         */
        blit.get(0).srcOffsets(0)
                .set(
                        0,
                        shadowHeight,
                        0
                );
        blit.get(0).srcOffsets(1)
                .set(
                        shadowWidth,
                        0,
                        1
                );
        blit.get(0).dstOffsets(0)
                .set(
                        0,
                        0,
                        0
                );
        blit.get(0).dstOffsets(1)
                .set(
                        shadowWidth,
                        shadowHeight,
                        1
                );

        vkCmdBlitImage(
                shadowCommandBuffer,
                realContentFrame.image(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                blit,
                VK_FILTER_NEAREST
        );
        realContentCopyRecorded = false;
        realContentBlitRecorded = true;
        realContentBlitCount++;

        imageBarrier(
                shadowCommandBuffer,
                realContentFrame.image(),
                VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                VK_ACCESS_TRANSFER_READ_BIT,
                0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                stack
        );
        realContentSourceReturnedToColorAttachmentLayout = true;

        imageBarrier(
                shadowCommandBuffer,
                image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                VK_ACCESS_TRANSFER_WRITE_BIT,
                0,
                VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT,
                stack
        );

        result = vkEndCommandBuffer(shadowCommandBuffer);
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(
                    "vkEndCommandBuffer failed with VkResult " + result
            );
        }
        shadowCommandRecorded = true;
    }

    private void submitShadowPresentCommand(
            MemoryStack stack
    ) {
        VkSubmitInfo.Buffer submitInfo =
                VkSubmitInfo.calloc(1, stack);

        submitInfo.get(0)
                .sType$Default()
                .pWaitSemaphores(
                        stack.longs(
                                shadowAcquireSemaphore,
                                realContentFrame.readySemaphore()
                        )
                )
                .pWaitDstStageMask(
                        stack.ints(
                                VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK_PIPELINE_STAGE_TRANSFER_BIT
                        )
                )
                .pCommandBuffers(
                        stack.pointers(shadowCommandBuffer.address())
                )
                .pSignalSemaphores(
                        stack.longs(
                                shadowRenderFinishedSemaphore,
                                realContentFrame.returnSemaphore()
                        )
                );

        realContentSemaphoreWaitUsed = true;

        shadowQueueSubmitResult = vkQueueSubmit(
                graphicsQueue,
                submitInfo,
                NULL
        );

        shadowQueueSubmitUsed = true;

        if (shadowQueueSubmitResult != VK_SUCCESS) {
            VulkanGate10VisibleScreenRehearsal
                    .rejectGate11Frame(
                            realContentFrame.sequence(),
                            "INITIAL_GATE11_QUEUE_SUBMIT_FAILED_"
                                    + shadowQueueSubmitResult
                    );

            throw new IllegalStateException(
                    "vkQueueSubmit failed with VkResult "
                            + shadowQueueSubmitResult
            );
        }

        if (!VulkanGate10VisibleScreenRehearsal
                .acknowledgeGate11FrameSubmitted(
                        realContentFrame.sequence()
                )) {
            throw new IllegalStateException(
                    "Gate-10 rejected the initial Gate-11 frame submit acknowledgement."
            );
        }
    }

    private int presentShadowImage(
            int imageIndex,
            MemoryStack stack
    ) {
        VkPresentInfoKHR presentInfo =
                VkPresentInfoKHR.calloc(stack)
                        .sType$Default()
                        .pWaitSemaphores(
                                stack.longs(
                                        shadowRenderFinishedSemaphore
                                )
                        )
                        .swapchainCount(1)
                        .pSwapchains(
                                stack.longs(shadowSwapchain)
                        )
                        .pImageIndices(
                                stack.ints(imageIndex)
                        );

        return vkQueuePresentKHR(
                presentQueue,
                presentInfo
        );
    }

    private static void imageBarrier(
            VkCommandBuffer commandBuffer,
            long image,
            int oldLayout,
            int newLayout,
            int srcAccessMask,
            int dstAccessMask,
            int srcStageMask,
            int dstStageMask,
            MemoryStack stack
    ) {
        VkImageMemoryBarrier.Buffer barriers =
                VkImageMemoryBarrier.calloc(1, stack);

        barriers.get(0)
                .sType$Default()
                .srcAccessMask(srcAccessMask)
                .dstAccessMask(dstAccessMask)
                .oldLayout(oldLayout)
                .newLayout(newLayout)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(image);

        barriers.get(0)
                .subresourceRange()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .baseMipLevel(0)
                .levelCount(1)
                .baseArrayLayer(0)
                .layerCount(1);

        vkCmdPipelineBarrier(
                commandBuffer,
                srcStageMask,
                dstStageMask,
                0,
                null,
                null,
                barriers
        );
    }

    private boolean querySurface(
            long surface,
            boolean noApiCandidate
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer supported = stack.ints(0);

            int supportResult = vkGetPhysicalDeviceSurfaceSupportKHR(
                    physicalDevice,
                    presentQueueFamilyIndex,
                    surface,
                    supported
            );

            boolean presentationSupported =
                    supportResult == VK_SUCCESS
                            && supported.get(0) != 0;

            VkSurfaceCapabilitiesKHR capabilities =
                    VkSurfaceCapabilitiesKHR.calloc(
                            stack
                    );

            int capabilitiesResult =
                    vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                            physicalDevice,
                            surface,
                            capabilities
                    );

            boolean capabilitiesQueried =
                    capabilitiesResult == VK_SUCCESS;

            IntBuffer count = stack.ints(0);

            int formatsResult =
                    vkGetPhysicalDeviceSurfaceFormatsKHR(
                            physicalDevice,
                            surface,
                            count,
                            null
                    );

            int formatCount = formatsResult == VK_SUCCESS
                    ? count.get(0)
                    : 0;

            boolean formatsQueried =
                    formatsResult == VK_SUCCESS
                            && formatCount > 0;

            count.put(0, 0);

            int presentModesResult =
                    vkGetPhysicalDeviceSurfacePresentModesKHR(
                            physicalDevice,
                            surface,
                            count,
                            null
                    );

            int presentModeCount =
                    presentModesResult == VK_SUCCESS
                            ? count.get(0)
                            : 0;

            boolean presentModesQueried =
                    presentModesResult == VK_SUCCESS
                            && presentModeCount > 0;

            if (noApiCandidate) {
                noApiSurfaceSupportResult = supportResult;
                noApiPresentationSupported = presentationSupported;
                noApiCapabilitiesResult = capabilitiesResult;
                noApiCapabilitiesQueried = capabilitiesQueried;
                noApiFormatsResult = formatsResult;
                noApiFormatCount = formatCount;
                noApiFormatsQueried = formatsQueried;
                noApiPresentModesResult = presentModesResult;
                noApiPresentModeCount = presentModeCount;
                noApiPresentModesQueried = presentModesQueried;
            } else {
                directSurfaceSupportResult = supportResult;
                directPresentationSupported = presentationSupported;
                directCapabilitiesResult = capabilitiesResult;
                directCapabilitiesQueried = capabilitiesQueried;
                directFormatsResult = formatsResult;
                directFormatCount = formatCount;
                directFormatsQueried = formatsQueried;
                directPresentModesResult = presentModesResult;
                directPresentModeCount = presentModeCount;
                directPresentModesQueried = presentModesQueried;
            }

            return presentationSupported
                    && capabilitiesQueried
                    && formatsQueried
                    && presentModesQueried;
        }
    }

    private void captureCandidateGeometry(
            long candidate
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            IntBuffer x = stack.mallocInt(1);
            IntBuffer y = stack.mallocInt(1);

            glfwGetWindowSize(
                    candidate,
                    width,
                    height
            );

            noApiCandidateWidth = width.get(0);
            noApiCandidateHeight = height.get(0);

            glfwGetFramebufferSize(
                    candidate,
                    width,
                    height
            );

            noApiCandidateFramebufferWidth = width.get(0);
            noApiCandidateFramebufferHeight = height.get(0);

            glfwGetWindowPos(
                    candidate,
                    x,
                    y
            );

            noApiCandidateX = x.get(0);
            noApiCandidateY = y.get(0);
        }
    }

    private VkSurfaceFormatKHR selectSurfaceFormat(
            VkSurfaceFormatKHR.Buffer formats
    ) {
        VkSurfaceFormatKHR fallback = formats.get(0);

        if (realContentFrameClaimed
                && realContentSourceFormat != VK_FORMAT_UNDEFINED) {
            for (int index = 0; index < formats.remaining(); index++) {
                VkSurfaceFormatKHR candidate = formats.get(index);
                if (candidate.format() == realContentSourceFormat
                        && candidate.colorSpace()
                        == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    return candidate;
                }
            }
        }

        for (int index = 0; index < formats.remaining(); index++) {
            VkSurfaceFormatKHR candidate = formats.get(index);
            if (candidate.format() == VK_FORMAT_B8G8R8A8_SRGB
                    && candidate.colorSpace()
                    == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return candidate;
            }
        }

        return fallback;
    }

    private static int selectPresentMode(
            IntBuffer presentModes
    ) {
        for (int index = 0; index < presentModes.remaining(); index++) {
            if (presentModes.get(index) == VK_PRESENT_MODE_FIFO_KHR) {
                return VK_PRESENT_MODE_FIFO_KHR;
            }
        }

        return presentModes.get(0);
    }

    private static int selectCompositeAlpha(
            int supported
    ) {
        int[] preferences = {
                VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
                VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
        };

        for (int value : preferences) {
            if ((supported & value) != 0) {
                return value;
            }
        }

        return VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    }

    private static int clamp(
            int value,
            int minimum,
            int maximum
    ) {
        return Math.max(
                minimum,
                Math.min(maximum, value)
        );
    }

    synchronized void enrich() {
        if (report == null) {
            return;
        }

        report.addProperty(
                "gate11MainWindowSurfaceQualificationInstalled",
                true
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationMode",
                "ACTUAL_OPENGL_WINDOW_QUERY_THEN_BOUNDED_LIVE_VULKAN_UI_PRESENTATION"
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationAttempted",
                attempted
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationPassed",
                directQualified
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationMinecraftWindowHandleNonZero",
                minecraftWindowHandle != NULL
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationMinecraftWindowClientApiRaw",
                minecraftWindowClientApi
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCurrentContextBeforeNonZero",
                directCurrentContextBefore != NULL
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCurrentContextAfterNonZero",
                directCurrentContextAfter != NULL
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCurrentContextPreservedExactly",
                directCurrentContextPreservedExactly
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCreateSurfaceResult",
                directCreateSurfaceResult
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationSurfaceCreated",
                directSurfaceCreated
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationSurfaceDestroyed",
                directSurfaceDestroyed
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationPresentQueueFamilyIndex",
                presentQueueFamilyIndex
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationSurfaceSupportResult",
                directSurfaceSupportResult
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationPresentationSupported",
                directPresentationSupported
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCapabilitiesResult",
                directCapabilitiesResult
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCapabilitiesQueried",
                directCapabilitiesQueried
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationFormatsResult",
                directFormatsResult
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationFormatCount",
                directFormatCount
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationFormatsQueried",
                directFormatsQueried
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationPresentModesResult",
                directPresentModesResult
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationPresentModeCount",
                directPresentModeCount
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationPresentModesQueried",
                directPresentModesQueried
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCreatesSwapchain",
                false
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationCallsVkQueuePresentKHR",
                false
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationMutatesMinecraftWindow",
                false
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationMutatesOpenGlDraws",
                false
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationGameplayGpuWait",
                false
        );
        report.addProperty(
                "gate11MainWindowSurfaceQualificationFailureReason",
                directFailureReason
        );

        report.addProperty(
                "gate11NoApiHandoffRehearsalInstalled",
                true
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMode",
                "HIDDEN_LIVE_SIZE_GLFW_NO_API_REPLACEMENT_CANDIDATE_SURFACE_QUERY"
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalAttempted",
                noApiAttempted
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPassed",
                noApiQualified
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateCreated",
                noApiCandidateCreated
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalBootstrapCandidateReused",
                noApiCandidateReused
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateDestroyed",
                noApiCandidateDestroyed
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateHandleWasNonZero",
                noApiCandidateHandle != NULL
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateUsesNoApi",
                noApiCandidateUsesNoApi
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateClientApiRaw",
                noApiCandidateClientApi
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalSurfaceCreated",
                noApiSurfaceCreated
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalSurfaceDestroyed",
                noApiSurfaceDestroyed
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalBorrowedRuntimeSurface",
                noApiSurfaceBorrowedFromRuntime
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPersistentSwapchainPrepared",
                noApiPersistentSwapchainPrepared
        );
        report.addProperty(
                "gate11CreatesSecondGameplayVkSurface",
                noApiSurfaceCreated
                        && !noApiSurfaceBorrowedFromRuntime
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCreateSurfaceResult",
                noApiCreateSurfaceResult
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPresentQueueFamilyIndex",
                presentQueueFamilyIndex
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalSurfaceSupportResult",
                noApiSurfaceSupportResult
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPresentationSupported",
                noApiPresentationSupported
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCapabilitiesResult",
                noApiCapabilitiesResult
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCapabilitiesQueried",
                noApiCapabilitiesQueried
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalFormatsResult",
                noApiFormatsResult
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalFormatCount",
                noApiFormatCount
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalFormatsQueried",
                noApiFormatsQueried
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPresentModesResult",
                noApiPresentModesResult
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPresentModeCount",
                noApiPresentModeCount
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPresentModesQueried",
                noApiPresentModesQueried
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMainWindowX",
                mainWindowX
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMainWindowY",
                mainWindowY
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMainWindowWidth",
                mainWindowWidth
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMainWindowHeight",
                mainWindowHeight
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMainFramebufferWidth",
                mainFramebufferWidth
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMainFramebufferHeight",
                mainFramebufferHeight
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateX",
                noApiCandidateX
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateY",
                noApiCandidateY
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateWidth",
                noApiCandidateWidth
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateHeight",
                noApiCandidateHeight
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateFramebufferWidth",
                noApiCandidateFramebufferWidth
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCandidateFramebufferHeight",
                noApiCandidateFramebufferHeight
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalLogicalSizeMatchesMainWindow",
                noApiLogicalSizeMatchesMainWindow
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalFramebufferSizeMatchesMainWindow",
                noApiFramebufferSizeMatchesMainWindow
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalPositionMatched",
                noApiPositionMatched
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalFullscreenTransferDeferred",
                noApiFullscreenTransferDeferred
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCurrentContextBeforeNonZero",
                noApiCurrentContextBefore != NULL
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCurrentContextAfterNonZero",
                noApiCurrentContextAfter != NULL
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCurrentContextPreservedExactly",
                noApiCurrentContextPreservedExactly
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCreatesSwapchain",
                false
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalCallsVkQueuePresentKHR",
                false
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalShowsCandidateWindow",
                false
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalMutatesMinecraftWindow",
                false
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalGameplayGpuWait",
                false
        );
        report.addProperty(
                "gate11NoApiHandoffRehearsalFailureReason",
                noApiFailureReason
        );

        report.addProperty(
                "gate11ShadowSwapchainRehearsalInstalled",
                true
        );
        report.addProperty(
                "gate11ShadowSwapchainRehearsalMode",
                "HIDDEN_NO_API_SWAPCHAIN_SINGLE_PRESENT_NO_MAIN_WINDOW_OWNERSHIP"
        );
        report.addProperty(
                "gate11ShadowSwapchainRehearsalAttempted",
                shadowAttempted
        );
        report.addProperty(
                "gate11ShadowSwapchainRehearsalPassed",
                shadowPassed
        );
        report.addProperty(
                "gate11ShadowSwapchainCandidateCreated",
                shadowCandidateCreated
        );
        report.addProperty(
                "gate11ShadowSwapchainBootstrapCandidateReused",
                shadowCandidateReused
        );
        report.addProperty(
                "gate11ShadowSwapchainCandidateUsesNoApi",
                shadowCandidateUsesNoApi
        );
        report.addProperty(
                "gate11ShadowSwapchainCandidateClientApiRaw",
                shadowCandidateClientApi
        );
        report.addProperty(
                "gate11ShadowSwapchainCandidateHidden",
                shadowCandidateHidden
        );
        report.addProperty(
                "gate11ShadowSwapchainNativeNoActivateAttempted",
                shadowCandidateNativeNoActivateAttempted
        );
        report.addProperty(
                "gate11ShadowSwapchainNativeNoActivateApplied",
                shadowCandidateNativeNoActivateApplied
        );
        report.addProperty(
                "gate11ShadowSwapchainNativeNoActivateFrameRefreshAttempted",
                shadowCandidateNativeNoActivateFrameRefreshAttempted
        );
        report.addProperty(
                "gate11ShadowSwapchainNativeNoActivateFrameRefreshApplied",
                shadowCandidateNativeNoActivateFrameRefreshApplied
        );
        report.addProperty(
                "gate11ShadowSwapchainNativeHandleNonZero",
                shadowCandidateNativeHandle != NULL
        );
        report.addProperty(
                "gate11ShadowSwapchainExtendedStyleBefore",
                Long.toUnsignedString(
                        shadowCandidateExtendedStyleBefore
                )
        );
        report.addProperty(
                "gate11ShadowSwapchainExtendedStyleAfter",
                Long.toUnsignedString(
                        shadowCandidateExtendedStyleAfter
                )
        );
        report.addProperty(
                "gate11ShadowSwapchainSurfaceCreated",
                shadowSurfaceCreated
        );
        report.addProperty(
                "gate11ShadowSwapchainBorrowedRuntimeSurface",
                shadowSurfaceBorrowedFromRuntime
        );
        report.addProperty(
                "gate11ShadowSwapchainBorrowedPersistentRuntimeSwapchain",
                shadowSwapchainBorrowedFromRuntime
        );
        report.addProperty(
                "gate11CreatesSecondGameplaySwapchain",
                shadowSwapchainCreated
                        && !shadowSwapchainBorrowedFromRuntime
        );
        report.addProperty(
                "gate11ShadowSwapchainCreateSurfaceResult",
                shadowCreateSurfaceResult
        );
        report.addProperty(
                "gate11ShadowSwapchainSurfaceSupportResult",
                shadowSurfaceSupportResult
        );
        report.addProperty(
                "gate11ShadowSwapchainCapabilitiesResult",
                shadowCapabilitiesResult
        );
        report.addProperty(
                "gate11ShadowSwapchainFormatsResult",
                shadowFormatsResult
        );
        report.addProperty(
                "gate11ShadowSwapchainPresentModesResult",
                shadowPresentModesResult
        );
        report.addProperty(
                "gate11ShadowSwapchainSurfaceQualified",
                shadowSurfaceQualified
        );
        report.addProperty(
                "gate11ShadowSwapchainCreateSwapchainResult",
                shadowCreateSwapchainResult
        );
        report.addProperty(
                "gate11ShadowSwapchainCreated",
                shadowSwapchainCreated
        );
        report.addProperty(
                "gate11ShadowSwapchainGetImagesCountResult",
                shadowGetImagesCountResult
        );
        report.addProperty(
                "gate11ShadowSwapchainGetImagesResult",
                shadowGetImagesResult
        );
        report.addProperty(
                "gate11ShadowSwapchainImagesEnumerated",
                shadowSwapchainImagesEnumerated
        );
        report.addProperty(
                "gate11ShadowSwapchainImageCount",
                shadowImages != null
                        ? shadowImages.length
                        : 0
        );
        report.addProperty(
                "gate11ShadowSwapchainSelectedImageCount",
                shadowImageCount
        );
        report.addProperty(
                "gate11ShadowSwapchainFormat",
                shadowFormat
        );
        report.addProperty(
                "gate11ShadowSwapchainColorSpace",
                shadowColorSpace
        );
        report.addProperty(
                "gate11ShadowSwapchainPresentMode",
                shadowPresentMode
        );
        report.addProperty(
                "gate11ShadowSwapchainWidth",
                shadowWidth
        );
        report.addProperty(
                "gate11ShadowSwapchainHeight",
                shadowHeight
        );
        report.addProperty(
                "gate11ShadowSwapchainImageUsage",
                shadowImageUsage
        );
        report.addProperty(
                "gate11ShadowSwapchainCompositeAlpha",
                shadowCompositeAlpha
        );
        report.addProperty(
                "gate11ShadowSwapchainAcquireAttempted",
                shadowAcquireAttempted
        );
        report.addProperty(
                "gate11ShadowSwapchainAcquireResult",
                shadowAcquireResult
        );
        report.addProperty(
                "gate11ShadowSwapchainAcquireSucceeded",
                shadowAcquireSucceeded
        );
        report.addProperty(
                "gate11ShadowSwapchainAcquiredImageIndex",
                shadowAcquiredImageIndex
        );
        report.addProperty(
                "gate11ShadowSwapchainCommandRecorded",
                shadowCommandRecorded
        );
        report.addProperty(
                "gate11ShadowSwapchainQueueSubmitUsed",
                shadowQueueSubmitUsed
        );
        report.addProperty(
                "gate11ShadowSwapchainQueueSubmitResult",
                shadowQueueSubmitResult
        );
        report.addProperty(
                "gate11ShadowSwapchainCallsVkQueuePresentKHR",
                shadowQueuePresentUsed
        );
        report.addProperty(
                "gate11ShadowSwapchainPresentResult",
                shadowPresentResult
        );
        report.addProperty(
                "gate11ShadowSwapchainPresentAccepted",
                shadowPresentAccepted
        );
        report.addProperty(
                "gate11ShadowSwapchainMainOpenGlContextPreservedExactly",
                shadowCurrentContextPreservedExactly
        );
        report.addProperty(
                "gate11ShadowSwapchainShowsCandidateWindow",
                false
        );
        report.addProperty(
                "gate11ShadowSwapchainMutatesMinecraftWindow",
                false
        );
        report.addProperty(
                "gate11ShadowSwapchainMutatesOpenGlDraws",
                false
        );
        report.addProperty(
                "gate11ShadowSwapchainMainWindowOwnership",
                false
        );
        report.addProperty(
                "gate11ShadowSwapchainGameplayFenceWait",
                shadowGameplayFenceWait
        );
        report.addProperty(
                "gate11ShadowSwapchainGameplayQueueWaitIdle",
                shadowGameplayQueueWaitIdle
        );
        report.addProperty(
                "gate11ShadowSwapchainGameplayDeviceWaitIdle",
                shadowGameplayDeviceWaitIdle
        );
        report.addProperty(
                "gate11ShadowSwapchainResourcesRetainedUntilShutdown",
                shadowResourcesRetainedUntilShutdown
        );
        report.addProperty(
                "gate11ShadowSwapchainShutdownDeviceWaitIdleUsed",
                shadowShutdownDeviceWaitIdleUsed
        );
        report.addProperty(
                "gate11ShadowSwapchainShutdownDeviceWaitIdleResult",
                shadowShutdownDeviceWaitIdleResult
        );
        report.addProperty(
                "gate11ShadowSwapchainResourcesDestroyed",
                shadowResourcesDestroyed
        );
        report.addProperty(
                "gate11ShadowSwapchainClosed",
                shadowClosed
        );
        report.addProperty(
                "gate11ShadowSwapchainFailureReason",
                shadowFailureReason
        );

        report.addProperty(
                "gate11RealContentFrameClaimed",
                realContentFrameClaimed
        );
        report.addProperty(
                "gate11RealContentSourceWidth",
                realContentSourceWidth
        );
        report.addProperty(
                "gate11RealContentSourceHeight",
                realContentSourceHeight
        );
        report.addProperty(
                "gate11RealContentSourceFormat",
                realContentSourceFormat
        );
        report.addProperty(
                "gate11RealContentFrameSizeMatchesSwapchain",
                realContentFrameSizeMatchesSwapchain
        );
        report.addProperty(
                "gate11RealContentExtentSyncAttempted",
                realContentExtentSyncAttempted
        );
        report.addProperty(
                "gate11RealContentExtentSyncApplied",
                realContentExtentSyncApplied
        );
        report.addProperty(
                "gate11RealContentExtentSwapchainPrepared",
                realContentExtentSwapchainPrepared
        );
        report.addProperty(
                "gate11RealContentExtentRequestedLogicalWidth",
                realContentExtentRequestedLogicalWidth
        );
        report.addProperty(
                "gate11RealContentExtentRequestedLogicalHeight",
                realContentExtentRequestedLogicalHeight
        );
        report.addProperty(
                "gate11RealContentExtentResultFramebufferWidth",
                realContentExtentResultFramebufferWidth
        );
        report.addProperty(
                "gate11RealContentExtentResultFramebufferHeight",
                realContentExtentResultFramebufferHeight
        );
        report.addProperty(
                "gate11RealContentExtentSyncState",
                realContentExtentSyncState
        );
        report.addProperty(
                "gate11RealContentFrameFormatMatchesSwapchain",
                realContentFrameFormatMatchesSwapchain
        );
        report.addProperty(
                "gate11RealContentSemaphoreWaitUsed",
                realContentSemaphoreWaitUsed
        );
        report.addProperty(
                "gate11RealContentCopyRecorded",
                realContentCopyRecorded
        );
        report.addProperty(
                "gate11RealContentCopyCount",
                realContentCopyCount
        );
        report.addProperty(
                "gate11RealContentBlitSupported",
                realContentBlitSupported
        );
        report.addProperty(
                "gate11RealContentBlitRecorded",
                realContentBlitRecorded
        );
        report.addProperty(
                "gate11RealContentBlitCount",
                realContentBlitCount
        );
        report.addProperty(
                "gate11RealContentOrientationCorrection",
                "GPU_BLIT_FLIP_Y_ONLY"
        );
        report.addProperty(
                "gate11RealContentOrientationRotationDegrees",
                realContentOrientationRotationDegrees
        );
        report.addProperty(
                "gate11RealContentSourceReturnedToColorAttachmentLayout",
                realContentSourceReturnedToColorAttachmentLayout
        );
        report.addProperty(
                "gate11RealContentPresented",
                realContentPresented
        );
        report.addProperty(
                "gate11RealContentCpuReadback",
                false
        );
        report.addProperty(
                "gate11RealContentGameplayGpuWait",
                false
        );
        report.addProperty(
                "gate11RealContentFailureReason",
                realContentFailureReason
        );

        report.addProperty(
                "gate11VisibleReplacementRehearsalInstalled",
                true
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalMode",
                PRODUCTION_SINGLE_VISIBLE_SESSION
                        ? "PRODUCTION_SINGLE_VISIBLE_NO_API_VULKAN_PRESENTATION_WITH_OPENGL_OFFSCREEN_FAILOPEN_SOURCE_STAGE1"
                        : "VISIBLE_NO_API_LIVE_VULKAN_UI_PRESENTATION_STREAM_NO_MAIN_WINDOW_HIDE"
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalDurationTargetMillis",
                PRODUCTION_SINGLE_VISIBLE_SESSION
                        ? 0L
                        : VISIBLE_REHEARSAL_DURATION_NANOS / 1_000_000L
        );
        report.addProperty(
                "gate11ProductionSingleVisibleSessionEnabled",
                PRODUCTION_SINGLE_VISIBLE_SESSION
        );
        report.addProperty(
                "gate11ProductionPresentationFocusableStyleAttempted",
                productionPresentationFocusableStyleAttempted
        );
        report.addProperty(
                "gate11ProductionPresentationFocusableStyleApplied",
                productionPresentationFocusableStyleApplied
        );
        report.addProperty(
                "gate11ProductionPresentationOpenGlOwnerHidden",
                productionPresentationOpenGlOwnerHidden
        );
        report.addProperty(
                "gate11ProductionPresentationCandidateFocused",
                productionPresentationCandidateFocused
        );
        report.addProperty(
                "gate11ProductionPresentationFailOpenTriggered",
                productionPresentationFailOpenTriggered
        );
        report.addProperty(
                "gate11ProductionPresentationFallbackRestored",
                productionPresentationFallbackRestored
        );
        report.addProperty(
                "gate11ProductionPresentationShutdownFinalized",
                productionPresentationShutdownFinalized
        );
        report.addProperty(
                "gate11ProductionWindowTopologySyncInstalled",
                true
        );
        report.addProperty(
                "gate11ProductionWindowTopologySyncCheckCount",
                productionWindowTopologySyncCheckCount
        );
        report.addProperty(
                "gate11ProductionWindowTopologySyncFailureCount",
                productionWindowTopologySyncFailureCount
        );
        report.addProperty(
                "gate11ProductionWindowFullscreenEnterMirrorCount",
                productionWindowFullscreenEnterMirrorCount
        );
        report.addProperty(
                "gate11ProductionWindowFullscreenExitMirrorCount",
                productionWindowFullscreenExitMirrorCount
        );
        report.addProperty(
                "gate11ProductionWindowWindowedGeometryMirrorCount",
                productionWindowWindowedGeometryMirrorCount
        );
        report.addProperty(
                "gate11ProductionWindowOpenGlOwnerRehideCount",
                productionWindowOwnerRehideCount
        );
        report.addProperty(
                "gate11ProductionWindowCandidateFocusRepairCount",
                productionWindowFocusRepairCount
        );
        report.addProperty(
                "gate11ProductionWindowFullscreenAuthority",
                "VISIBLE_VULKAN_CANDIDATE_ONLY_HIDDEN_OPENGL_OWNER_FORCED_WINDOWED"
        );
        report.addProperty(
                "gate11ProductionWindowHiddenOwnerNativeFullscreenForbidden",
                true
        );
        report.addProperty(
                "gate11ProductionWindowOwnerFullscreenNeutralizationCount",
                productionWindowOwnerFullscreenNeutralizationCount
        );
        report.addProperty(
                "gate11ProductionWindowCandidateFullscreenObservedCount",
                productionWindowCandidateFullscreenObservedCount
        );
        report.addProperty(
                "gate11ProductionWindowCandidateWindowedObservedCount",
                productionWindowCandidateWindowedObservedCount
        );
        report.addProperty(
                "gate11ProductionWindowSingleVisibleInvariantMaintained",
                productionWindowSingleVisibleInvariantMaintained
        );
        report.addProperty(
                "gate11ProductionWindowLastOwnerMonitor",
                productionWindowLastOwnerMonitor
        );
        report.addProperty(
                "gate11ProductionWindowLastCandidateMonitor",
                productionWindowLastCandidateMonitor
        );
        report.addProperty(
                "gate11ProductionWindowLastOwnerX",
                productionWindowLastOwnerX
        );
        report.addProperty(
                "gate11ProductionWindowLastOwnerY",
                productionWindowLastOwnerY
        );
        report.addProperty(
                "gate11ProductionWindowLastOwnerWidth",
                productionWindowLastOwnerWidth
        );
        report.addProperty(
                "gate11ProductionWindowLastOwnerHeight",
                productionWindowLastOwnerHeight
        );
        report.addProperty(
                "gate11ProductionWindowLastCandidateX",
                productionWindowLastCandidateX
        );
        report.addProperty(
                "gate11ProductionWindowLastCandidateY",
                productionWindowLastCandidateY
        );
        report.addProperty(
                "gate11ProductionWindowLastCandidateWidth",
                productionWindowLastCandidateWidth
        );
        report.addProperty(
                "gate11ProductionWindowLastCandidateHeight",
                productionWindowLastCandidateHeight
        );
        report.addProperty(
                "gate11ProductionWindowTopologySyncState",
                productionWindowTopologySyncState
        );
        report.addProperty(
                "gate11ProductionPresentationGameplayOnlyVulkan",
                true
        );
        report.addProperty(
                "gate11ProductionPresentationOpenGlMenuAuthority",
                true
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffAttempted",
                productionPresentationWorldExitHandoffAttempted
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffCompleted",
                productionPresentationWorldExitHandoffCompleted
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffAttemptCount",
                productionPresentationWorldExitHandoffAttemptCount
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffSuccessCount",
                productionPresentationWorldExitHandoffSuccessCount
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffFailureCount",
                productionPresentationWorldExitHandoffFailureCount
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffMillis",
                productionPresentationWorldExitHandoffMillis
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffReason",
                productionPresentationWorldExitHandoffReason
        );
        report.addProperty(
                "gate11ProductionPresentationWorldExitHandoffGameplayGpuWait",
                false
        );
        report.addProperty(
                "gate11ProductionPresentationExtendedStyleBefore",
                productionPresentationExtendedStyleBefore
        );
        report.addProperty(
                "gate11ProductionPresentationExtendedStyleAfter",
                productionPresentationExtendedStyleAfter
        );
        report.addProperty(
                "gate11ProductionPresentationOpenGlRasterSourceStillActive",
                true
        );
        report.addProperty(
                "gate11ProductionPresentationActualVulkanMainTargetOwnership",
                false
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalAttempted",
                visibleReplacementAttempted
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalShown",
                visibleReplacementShown
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalHidePending",
                visibleReplacementHidePending
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalHiddenAfter",
                visibleReplacementHiddenAfterRehearsal
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalPassed",
                visibleReplacementPassed
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalDurationMillis",
                visibleReplacementDurationMillis
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalCandidateFocusedAfterShow",
                visibleReplacementCandidateFocusedAfterShow
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalMainWindowFocusedBefore",
                visibleReplacementMainWindowFocusedBefore == GLFW_TRUE
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalMainWindowFocusedAfterShow",
                visibleReplacementMainWindowFocusedAfterShow == GLFW_TRUE
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalMainWindowFocusedAfterHide",
                visibleReplacementMainWindowFocusedAfterHide == GLFW_TRUE
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalCurrentContextPreservedAfterShow",
                visibleReplacementCurrentContextPreservedAfterShow
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalCurrentContextPreservedAfterHide",
                visibleReplacementCurrentContextPreservedAfterHide
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalMainWindowHandlePreserved",
                visibleReplacementMainWindowHandlePreserved
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalUsesRealMinecraftContent",
                visibleReplacementUsesRealMinecraftContent
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalGameplayGpuWait",
                visibleReplacementGameplayGpuWait
        );
        report.addProperty(
                "gate11VisibleReplacementRehearsalFailureReason",
                visibleReplacementFailureReason
        );
        report.addProperty(
                "gate11VisibleReplacementRevealPending",
                visibleReplacementRevealPending
        );
        report.addProperty(
                "gate11VisibleReplacementRevealDeferredUntilPrewarm",
                visibleReplacementRevealDeferredUntilPrewarm
        );
        report.addProperty(
                "gate11VisibleReplacementPrewarmRequiredPresents",
                VISIBLE_REVEAL_PREWARM_PRESENTS
        );
        report.addProperty(
                "gate11VisibleReplacementPrewarmPresentCount",
                visibleReplacementPrewarmPresentCount
        );
        report.addProperty(
                "gate11VisibleReplacementColorTransferVerified",
                visibleReplacementColorTransferVerified
        );
        report.addProperty(
                "gate11VisibleReplacementPresentationFormat",
                visibleReplacementPresentationFormat
        );
        report.addProperty(
                "gate11VisibleReplacementPresentationColorSpace",
                visibleReplacementPresentationColorSpace
        );
        report.addProperty(
                "gate11VisibleReplacementColorTransferPolicy",
                visibleReplacementColorTransferPolicy
        );
        report.addProperty(
                "gate11VisibleReplacementDeveloperTitleExposed",
                false
        );

        report.addProperty(
                "gate11LiveUiPresentationInstalled",
                liveStreamInstalled
        );
        report.addProperty(
                "gate11LiveUiPresentationResourcesCreated",
                liveResourcesCreated
        );
        report.addProperty(
                "gate11LiveUiPresentationActive",
                liveStreamActive
        );
        report.addProperty(
                "gate11LiveUiPresentationRetired",
                liveStreamRetired
        );
        report.addProperty(
                "gate11LiveUiPresentationRingSize",
                LIVE_PRESENT_RING_SIZE
        );
        report.addProperty(
                "gate11LiveUiPresentationFrameClaimCount",
                liveFrameClaimCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSourceExtentObservationCount",
                liveSourceExtentObservationCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSourceExtentExactMatchCount",
                liveSourceExtentExactMatchCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSourceExtentMismatchCount",
                liveSourceExtentMismatchCount
        );
        report.addProperty(
                "gate11LiveUiPresentationLastSourceWidth",
                liveLastSourceWidth
        );
        report.addProperty(
                "gate11LiveUiPresentationLastSourceHeight",
                liveLastSourceHeight
        );
        report.addProperty(
                "gate11LiveUiPresentationLastSourceExtentMatchesSwapchain",
                liveLastSourceExtentMatchesSwapchain
        );
        report.addProperty(
                "gate11LiveUiPresentationLowResolutionUpscaleIsProductionPolicy",
                false
        );
        report.addProperty(
                "gate11LiveUiPresentationFrameSubmitCount",
                liveFrameSubmitCount
        );
        report.addProperty(
                "gate11LiveUiPresentationFramePresentCount",
                liveFramePresentCount
        );
        report.addProperty(
                "gate11LiveUiPresentationFrameSubmitAckCount",
                liveFrameSubmitAckCount
        );
        report.addProperty(
                "gate11LiveUiPresentationFencePollCount",
                liveFencePollCount
        );
        report.addProperty(
                "gate11LiveUiPresentationFenceNotReadyCount",
                liveFenceNotReadyCount
        );
        report.addProperty(
                "gate11LiveUiPresentationAcquireNotReadyCount",
                liveAcquireNotReadyCount
        );
        report.addProperty(
                "gate11LiveUiPresentationAcquireSuboptimalCount",
                liveAcquireSuboptimalCount
        );
        report.addProperty(
                "gate11LiveUiPresentationBackpressureSkipCount",
                liveBackpressureSkipCount
        );
        report.addProperty(
                "gate11LiveUiPresentationFailureCount",
                liveFailureCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainGeometryCheckCount",
                liveSwapchainGeometryCheckCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainGeometryMismatchCount",
                liveSwapchainGeometryMismatchCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainRefreshAttemptCount",
                liveSwapchainRefreshAttemptCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainRefreshSuccessCount",
                liveSwapchainRefreshSuccessCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainRefreshDeferralCount",
                liveSwapchainRefreshDeferralCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainRefreshFenceBusyCount",
                liveSwapchainRefreshFenceBusyCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainRefreshFailureCount",
                liveSwapchainRefreshFailureCount
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainGenerationChangeCount",
                liveSwapchainGenerationChangeCount
        );
        report.addProperty(
                "gate11LiveUiPresentationAcquireOutOfDateCount",
                liveAcquireOutOfDateCount
        );
        report.addProperty(
                "gate11LiveUiPresentationPresentOutOfDateCount",
                livePresentOutOfDateCount
        );
        report.addProperty(
                "gate11LiveUiPresentationRecoverableOutOfDatePresentCount",
                liveRecoverablePresentDropCount
        );
        report.addProperty(
                "gate11LiveUiPresentationPresentSuboptimalCount",
                livePresentSuboptimalCount
        );
        report.addProperty(
                "gate11LiveUiPresentationRetiredRenderFinishedSemaphoreGenerationCount",
                retiredLiveRenderFinishedSemaphoreGenerations.size()
        );
        report.addProperty(
                "gate11LiveUiPresentationSwapchainRefreshState",
                liveSwapchainRefreshState
        );
        report.addProperty(
                "gate11LiveUiPresentationResizeRecoveryUsesGameplayGpuWait",
                false
        );
        report.addProperty(
                "gate11LiveUiPresentationLastAcquireResult",
                liveLastAcquireResult
        );
        report.addProperty(
                "gate11LiveUiPresentationLastQueueSubmitResult",
                liveLastQueueSubmitResult
        );
        report.addProperty(
                "gate11LiveUiPresentationLastPresentResult",
                liveLastPresentResult
        );
        report.addProperty(
                "gate11LiveUiPresentationFailureReason",
                liveFailureReason
        );
        report.addProperty(
                "gate11LiveUiPresentationUsesFreshFinalScreenFrames",
                true
        );
        report.addProperty(
                "gate11LiveUiPresentationFontGlyphsComeFromFreshMainTargetFrames",
                true
        );
        report.addProperty(
                "gate11LiveUiPresentationIndependentVulkanGuiRasterizer",
                false
        );
        report.addProperty(
                "gate11LiveUiPresentationCpuReadback",
                false
        );
        report.addProperty(
                "gate11LiveUiPresentationGameplayFenceWait",
                false
        );
        report.addProperty(
                "gate11LiveUiPresentationGameplayQueueWaitIdle",
                false
        );
        report.addProperty(
                "gate11LiveUiPresentationGameplayDeviceWaitIdle",
                false
        );

        if (lifecycleRouter != null) {
            lifecycleRouter.enrich();
        }

        report.addProperty(
                "gate11LifecycleSafeCloseRouterStage1",
                true
        );
        report.addProperty(
                "gate11LifecycleSafeCloseRouterRequiresManualCloseEvent",
                true
        );
        report.addProperty(
                "gate11LifecycleSafeCloseRouterVisibleWindowMillis",
                VISIBLE_REHEARSAL_DURATION_NANOS / 1_000_000L
        );
        report.addProperty(
                "gate11LifecycleSafeCloseRouterMainWindowHidden",
                false
        );
        report.addProperty(
                "gate11LifecycleSafeCloseRouterCandidateFocusForced",
                false
        );

        report.addProperty(
                "potatoEngineVulkanMainWindowPresentationReady",
                false
        );
        report.addProperty(
                "gate11UsesBootstrapNoApiCandidate",
                shadowCandidate != NULL
                        && VulkanHandoffCandidate.isPersistentCandidate(
                        shadowCandidate
                )
        );
        report.addProperty(
                "createsSecondGameplayNoApiWindow",
                false
        );
        report.addProperty(
                "gate11GameplayNoApiWindowCreationCount",
                0
        );
        report.addProperty(
                "gate11NextMilestone",
                visibleReplacementPassed
                        ? "POTATO_ENGINE_GATE11_LIVE_UI_PRESENTATION_STREAM_QUALIFICATION"
                        : visibleReplacementAttempted
                        ? "POTATO_ENGINE_GATE11_VISIBLE_NO_API_REPLACEMENT_PRESENT_FIX"
                        : shadowPassed
                        ? "POTATO_ENGINE_GATE11_VISIBLE_NO_API_REPLACEMENT_PRESENT_REHEARSAL"
                        : shadowAttempted
                        ? "POTATO_ENGINE_GATE11_NO_API_SHADOW_SWAPCHAIN_PRESENT_FIX"
                        : noApiQualified
                        ? "POTATO_ENGINE_GATE11_NO_API_SHADOW_SWAPCHAIN_PRESENT_REHEARSAL"
                        : noApiAttempted
                        ? "POTATO_ENGINE_GATE11_NO_API_HANDOFF_REHEARSAL_FIX"
                        : directQualified
                        ? "POTATO_ENGINE_GATE11_ACTUAL_MAIN_WINDOW_SWAPCHAIN_REHEARSAL"
                        : "POTATO_ENGINE_GATE11_NO_API_MAIN_WINDOW_HANDOFF_REQUIRED"
        );
    }

    @Override
    public synchronized void close() {
        if (shadowClosed) {
            enrich();
            return;
        }

        shadowClosed = true;

        if (PRODUCTION_SINGLE_VISIBLE_SESSION) {
            finalizeProductionPresentationSessionAtShutdown();
        } else {
            retireLiveUiPresentationStream(
                    "RUNTIME_SHUTDOWN"
            );
        }

        if (shadowCandidate != NULL
                && visibleReplacementShown
                && !visibleReplacementHiddenAfterRehearsal) {
            try {
                glfwHideWindow(
                        shadowCandidate
                );

                visibleReplacementHiddenAfterRehearsal =
                        glfwGetWindowAttrib(
                                shadowCandidate,
                                GLFW_VISIBLE
                        ) == GLFW_FALSE;
            } catch (Throwable ignored) {
            }

            visibleReplacementHidePending =
                    false;
        }

        if (shadowResourcesRetainedUntilShutdown
                && device != null) {
            try {
                shadowShutdownDeviceWaitIdleUsed = true;
                shadowShutdownDeviceWaitIdleResult =
                        vkDeviceWaitIdle(device);
            } catch (Throwable throwable) {
                shadowShutdownDeviceWaitIdleResult = Integer.MIN_VALUE;
                if (shadowFailureReason.equals(
                        "PASSED_HIDDEN_NO_API_SWAPCHAIN_PRESENT_REHEARSAL"
                )) {
                    shadowFailureReason =
                            "SHUTDOWN_DEVICE_WAIT_FAILURE: "
                                    + describe(throwable);
                }
            }
        }

        if (liveFences != null) {
            for (long fence : liveFences) {
                if (fence != NULL) {
                    vkDestroyFence(
                            device,
                            fence,
                            null
                    );
                }
            }
        }

        if (liveAcquireSemaphores != null) {
            for (long semaphore : liveAcquireSemaphores) {
                if (semaphore != NULL) {
                    vkDestroySemaphore(
                            device,
                            semaphore,
                            null
                    );
                }
            }
        }

        if (liveRenderFinishedSemaphores != null) {
            for (long semaphore : liveRenderFinishedSemaphores) {
                if (semaphore != NULL) {
                    vkDestroySemaphore(
                            device,
                            semaphore,
                            null
                    );
                }
            }
        }

        for (long[] generation
                : retiredLiveRenderFinishedSemaphoreGenerations) {
            if (generation == null) {
                continue;
            }

            for (long semaphore : generation) {
                if (semaphore != NULL) {
                    vkDestroySemaphore(
                            device,
                            semaphore,
                            null
                    );
                }
            }
        }

        retiredLiveRenderFinishedSemaphoreGenerations.clear();

        liveFences = null;
        liveAcquireSemaphores = null;
        liveRenderFinishedSemaphores = null;
        liveCommandBuffers = null;
        livePendingFrame = null;

        if (shadowCommandPool != NULL) {
            vkDestroyCommandPool(
                    device,
                    shadowCommandPool,
                    null
            );
            shadowCommandPool = NULL;
            shadowCommandBuffer = null;
        }

        if (shadowAcquireSemaphore != NULL) {
            vkDestroySemaphore(
                    device,
                    shadowAcquireSemaphore,
                    null
            );
            shadowAcquireSemaphore = NULL;
        }

        if (shadowRenderFinishedSemaphore != NULL) {
            vkDestroySemaphore(
                    device,
                    shadowRenderFinishedSemaphore,
                    null
            );
            shadowRenderFinishedSemaphore = NULL;
        }

        if (shadowSwapchain != NULL) {
            if (!shadowSwapchainBorrowedFromRuntime) {
                vkDestroySwapchainKHR(
                        device,
                        shadowSwapchain,
                        null
                );
            }
            shadowSwapchain = NULL;
        }

        if (shadowSurface != NULL) {
            if (!shadowSurfaceBorrowedFromRuntime) {
                vkDestroySurfaceKHR(
                        instance,
                        shadowSurface,
                        null
                );
            }
            shadowSurface = NULL;
        }

        if (shadowCandidate != NULL) {
            if (lifecycleRouter != null) {
                lifecycleRouter.unbindPresentationHandle();
            }

            /*
             * The persistent GLFW_NO_API window is not a Gate-11 allocation.
             * Runtime shutdown destroys it only after the borrowed swapchain
             * and VkSurfaceKHR owners have already retired.
             */
            report.addProperty(
                    "gate11PersistentCandidateReleasedToRuntimeOwner",
                    true
            );
            shadowCandidate = NULL;
        }

        if (lifecycleRouter != null) {
            lifecycleRouter.close();
        }

        shadowImages = null;
        shadowResourcesDestroyed = true;
        enrich();
    }

    private static String describe(
            Throwable throwable
    ) {
        return throwable.getClass().getName()
                + ": "
                + String.valueOf(
                        throwable.getMessage()
                );
    }

    private record ShadowConfiguration(
            int format,
            int colorSpace,
            int presentMode,
            int imageCount,
            int width,
            int height,
            int preTransform,
            int compositeAlpha
    ) {
    }
}
