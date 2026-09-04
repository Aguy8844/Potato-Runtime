package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWCursorPosCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWWindowCloseCallback;
import org.lwjgl.glfw.GLFWWindowFocusCallback;
import org.lwjgl.system.MemoryStack;

import java.nio.DoubleBuffer;
import java.nio.IntBuffer;
import java.util.ArrayDeque;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Gate-11 lifecycle router.
 *
 * <p>The Vulkan presentation candidate is a separate GLFW_NO_API window, but
 * Minecraft's real OpenGL window remains the lifecycle owner until Gate 11 is
 * actually cut over. This router makes that distinction explicit instead of
 * treating "the window that currently presents pixels" as automatically owning
 * close/lifetime semantics.</p>
 *
 * <p>Stage 1 routes an actual close request from the NO_API candidate directly
 * to Minecraft's real lifecycle-owner window by setting the real window's
 * should-close flag from the GLFW close callback itself. Stage 2 keeps the
 * candidate presentation-only while relaying pointer input to Minecraft's
 * already-installed GLFW callbacks, so a live Vulkan UI preview can visibly
 * react without replacing Minecraft's stored window handle.</p>
 */
final class VulkanGate11WindowLifecycleRouter
        implements AutoCloseable {

    /*
     * Patch 129: Screen actions are no longer executed re-entrantly from the
     * native GLFW mouse callback. Lifecycle-changing buttons such as
     * "Save and Quit to Title" can disconnect worlds, swap Screens and begin
     * renderer teardown. Running that code inside the presentation-window
     * callback can freeze the two-window transition topology.
     *
     * Native callbacks enqueue at most a tiny bounded input queue. Patch 130
     * drains at most one queued Screen event from RenderFrameEvent.Post, after
     * the current GameRenderer frame and final-pixel capture are complete.
     */
    private static final int MAX_DEFERRED_SCREEN_MOUSE_EVENTS =
            32;

    private static VulkanGate11WindowLifecycleRouter activeRouter;

    private record DeferredScreenMouseEvent(
            Screen targetScreen,
            long lifecycleEpoch,
            double x,
            double y,
            int button,
            int action,
            int modifiers
    ) {
    }

    private final long lifecycleOwnerHandle;
    private final JsonObject report;

    private final ArrayDeque<DeferredScreenMouseEvent>
            deferredScreenMouseEvents =
            new ArrayDeque<>();

    private long presentationHandle;

    private GLFWWindowCloseCallback closeCallback;
    private GLFWWindowFocusCallback focusCallback;
    private GLFWCursorPosCallback cursorPosCallback;
    private GLFWMouseButtonCallback mouseButtonCallback;
    private GLFWScrollCallback scrollCallback;
    private GLFWKeyCallback keyCallback;
    private GLFWCharModsCallback charModsCallback;

    /*
     * These callbacks belong to Minecraft/GLFW. We temporarily query them by
     * swapping null -> previous callback -> restore, but NEVER free them.
     */
    private GLFWCursorPosCallback lifecycleOwnerCursorPosCallback;
    private GLFWMouseButtonCallback lifecycleOwnerMouseButtonCallback;
    private GLFWScrollCallback lifecycleOwnerScrollCallback;
    private GLFWWindowFocusCallback lifecycleOwnerFocusCallback;
    private GLFWKeyCallback lifecycleOwnerKeyCallback;
    private GLFWCharModsCallback lifecycleOwnerCharModsCallback;

    private boolean presentationHandleBound;
    private boolean closeCallbackInstalled;
    private boolean focusCallbackInstalled;
    private boolean cursorPosCallbackInstalled;
    private boolean mouseButtonCallbackInstalled;
    private boolean scrollCallbackInstalled;
    private boolean keyCallbackInstalled;
    private boolean charModsCallbackInstalled;

    private boolean lifecycleOwnerCursorPosCallbackCaptured;
    private boolean lifecycleOwnerMouseButtonCallbackCaptured;
    private boolean lifecycleOwnerScrollCallbackCaptured;
    private boolean lifecycleOwnerFocusCallbackCaptured;
    private boolean lifecycleOwnerKeyCallbackCaptured;
    private boolean lifecycleOwnerCharModsCallbackCaptured;

    private long presentationFocusGainCount;
    private long presentationFocusLossCount;
    private long presentationCloseCallbackCount;
    private long closeForwardAttemptCount;
    private long closeForwardSuccessCount;

    private long cursorRelayEventCount;
    private long mouseButtonRelayEventCount;
    private long scrollRelayEventCount;
    private long inputRelayDropCount;
    private long focusReturnAttemptCount;
    private long focusReturnSuccessCount;

    private long cursorModeSyncAttemptCount;
    private long cursorModeSyncSuccessCount;
    private long cursorModeMismatchCount;
    private long cursorPositionSyncAttemptCount;
    private long cursorPositionSyncSuccessCount;
    private long menuCursorRelayEventCount;

    /*
     * Patch 143: gameplay cursor deltas cross two native GLFW windows.
     * Absolute disabled-cursor coordinates are not comparable across those
     * windows after takeover/resize, so gameplay uses a relative delta bridge.
     * Menu coordinates remain exact absolute presentation coordinates.
     */
    private boolean gameplayCursorDeltaSeeded;
    private double gameplayCursorLastPresentationX;
    private double gameplayCursorLastPresentationY;
    private double gameplayCursorSyntheticLifecycleX;
    private double gameplayCursorSyntheticLifecycleY;
    private double gameplayCursorScaleX = 1.0;
    private double gameplayCursorScaleY = 1.0;
    private long gameplayCursorDeltaSeedCount;
    private long gameplayCursorDeltaForwardCount;
    private long gameplayCursorDeltaOutlierReseedCount;
    private long gameplayCursorDeltaScaleRefreshCount;
    private long mouseButtonCoordinateSyncAttemptCount;
    private long mouseButtonCoordinateSyncSuccessCount;
    private long gameplayMouseButtonAbsoluteCursorSyncSuppressedCount;
    private long gameplayMouseButtonDeltaReseedCount;
    private long gameplayMouseButtonCursorStateUntouchedCount;
    private long postScreenGameplayDeltaResetCount;
    private long presentationNativeFocusRetentionCount;
    private long nativeOwnerCursorWarpCount;
    private long directScreenMousePressAttemptCount;
    private long directScreenMousePressAcceptedCount;
    private long directScreenMouseReleaseCount;
    private long directScreenMouseDispatchFailureCount;
    private long deferredScreenMouseEnqueueCount;
    private long deferredScreenMouseFlushCount;
    private long deferredScreenMouseStaleReleaseDiscardCount;
    private long deferredScreenMouseStalePressDiscardCount;
    private long deferredScreenMouseCausalStaleDiscardCount;
    private long deferredScreenMouseLifecycleTransitionCount;
    private long deferredScreenMouseLifecycleEpoch;
    private long deferredScreenMouseOverflowDropCount;
    private int deferredScreenMouseHighWaterMark;

    /*
     * Patch 135: high-frequency slider dragging is coalesced into one primitive
     * sample and dispatched on RenderFrameEvent.Post. No cursor-move objects are
     * allocated, and lifecycle-changing press/release events keep the proven
     * one-event-per-completed-frame contract from Patch 130.
     */
    private boolean deferredScreenDragActive;
    private Screen deferredScreenDragScreen;
    private int deferredScreenDragButton =
            -1;
    private double deferredScreenDragLastGuiX;
    private double deferredScreenDragLastGuiY;
    private boolean deferredScreenDragPending;
    private int deferredScreenDragModifiers;
    private double deferredScreenDragPendingX;
    private double deferredScreenDragPendingY;
    private long deferredScreenDragArmCount;
    private long deferredScreenDragCursorSampleCount;
    private long deferredScreenDragCoalescedCount;
    private long deferredScreenDragFlushCount;
    private long deferredScreenDragAcceptedCount;
    private long deferredScreenDragStaleDiscardCount;
    private long deferredScreenDragDispatchFailureCount;

    private long presentationMenuFocusLeaseCount;
    private long presentationGameplayFocusReturnCount;
    private boolean presentationMenuFocusLeaseActive;
    private long deferredModifierBridgeIntegrationProbeCount;
    private boolean deferredModifierBridgeIntegrationProbePassed;
    private long keyRelayEventCount;
    private long charModsRelayEventCount;
    private long keyboardRelayDropCount;
    private long keyboardPostCloseTailIgnoredCount;
    private long charPostCloseTailIgnoredCount;
    private long escapeKeyPressRelayCount;

    /*
     * Patch 147: while Vulkan owns the one visible gameplay window, F11 is a
     * presentation-window operation. Forwarding F11 into Minecraft's hidden
     * lifecycle owner makes that hidden OpenGL HWND enter native fullscreen,
     * where Windows/GLFW may refuse to hide it. Keep the hidden owner windowed
     * and virtualize F11 directly on the visible GLFW_NO_API candidate.
     */
    private long presentationF11EventConsumedCount;
    private long presentationF11PressConsumedCount;
    private long presentationF11ToggleAttemptCount;
    private long presentationF11ToggleSuccessCount;
    private long presentationF11ToggleFailureCount;
    private long presentationFullscreenEnterCount;
    private long presentationFullscreenExitCount;
    private long presentationFullscreenForcedWindowedForHandoffCount;
    private long presentationFullscreenCursorDeltaResetCount;
    private boolean presentationFullscreenOwnedByRouter;
    private boolean presentationWindowedBoundsCaptured;
    private int presentationWindowedX;
    private int presentationWindowedY;
    private int presentationWindowedWidth;
    private int presentationWindowedHeight;
    private String presentationFullscreenState =
            "WINDOWED_NOT_TOGGLED";

    private long logicalFocusRelayEventCount;
    private long logicalFocusRelayFailureCount;
    private int lastRelayedKey = -1;
    private int lastRelayedKeyAction = -1;
    private String lastDirectScreenClass = "";
    private int lastLifecycleOwnerCursorMode =
            -1;
    private int lastPresentationCursorMode =
            -1;

    private boolean candidateHiddenInsideCloseCallback;
    private boolean lifecycleOwnerShouldCloseAfterForward;
    private boolean closeForwardImmediateNoRenderCallback;
    private boolean closed;

    private String lastFailure = "";

    VulkanGate11WindowLifecycleRouter(
            long lifecycleOwnerHandle,
            JsonObject report
    ) {
        this.lifecycleOwnerHandle = lifecycleOwnerHandle;
        this.report = report;

        VulkanGate11ModifierStateBridge
                .resetSession(
                        lifecycleOwnerHandle
                );

        synchronized (VulkanGate11WindowLifecycleRouter.class) {
            activeRouter = this;
        }

        enrich();
    }

    static void flushDeferredScreenInputForRenderThread() {
        VulkanGate11WindowLifecycleRouter router;

        synchronized (VulkanGate11WindowLifecycleRouter.class) {
            router = activeRouter;
        }

        if (router != null) {
            router.flushDeferredScreenInput();
        }
    }

    synchronized void bindPresentationHandle(
            long candidate
    ) {
        if (closed) {
            throw new IllegalStateException(
                    "Gate-11 lifecycle router is already closed."
            );
        }

        if (candidate == NULL) {
            throw new IllegalArgumentException(
                    "Gate-11 presentation handle is null."
            );
        }

        if (presentationHandleBound) {
            if (presentationHandle == candidate) {
                enrich();
                return;
            }

            throw new IllegalStateException(
                    "Gate-11 lifecycle router already owns another presentation handle."
            );
        }

        presentationHandle =
                candidate;

        closeCallback =
                GLFWWindowCloseCallback.create(
                        this::onPresentationClose
                );

        focusCallback =
                GLFWWindowFocusCallback.create(
                        this::onPresentationFocus
                );

        cursorPosCallback =
                GLFWCursorPosCallback.create(
                        this::onPresentationCursorPos
                );

        mouseButtonCallback =
                GLFWMouseButtonCallback.create(
                        this::onPresentationMouseButton
                );

        scrollCallback =
                GLFWScrollCallback.create(
                        this::onPresentationScroll
                );

        keyCallback =
                GLFWKeyCallback.create(
                        this::onPresentationKey
                );

        charModsCallback =
                GLFWCharModsCallback.create(
                        this::onPresentationCharMods
                );

        captureLifecycleOwnerPointerCallbacks();

        glfwSetWindowCloseCallback(
                presentationHandle,
                closeCallback
        );

        closeCallbackInstalled =
                true;

        glfwSetWindowFocusCallback(
                presentationHandle,
                focusCallback
        );

        focusCallbackInstalled =
                true;

        glfwSetCursorPosCallback(
                presentationHandle,
                cursorPosCallback
        );

        cursorPosCallbackInstalled =
                true;

        glfwSetMouseButtonCallback(
                presentationHandle,
                mouseButtonCallback
        );

        mouseButtonCallbackInstalled =
                true;

        glfwSetScrollCallback(
                presentationHandle,
                scrollCallback
        );

        scrollCallbackInstalled =
                true;

        glfwSetKeyCallback(
                presentationHandle,
                keyCallback
        );

        keyCallbackInstalled =
                true;

        glfwSetCharModsCallback(
                presentationHandle,
                charModsCallback
        );

        charModsCallbackInstalled =
                true;

        presentationHandleBound =
                true;

        verifyDeferredModifierBridgeIntegration();
        synchronizePresentationCursorMode();
        enrich();
    }

    private synchronized void onPresentationClose(
            long window
    ) {
        presentationCloseCallbackCount++;

        if (closed
                || window == NULL
                || window != presentationHandle
                || lifecycleOwnerHandle == NULL) {
            lastFailure =
                    "INVALID_PRESENTATION_CLOSE_ROUTE";
            enrich();
            return;
        }

        closeForwardAttemptCount++;

        try {
            /*
             * GLFW sets the candidate's should-close flag before invoking this
             * callback. The candidate itself is not Minecraft's lifecycle
             * owner, so clear that local flag and hide the candidate now.
             */
            glfwSetWindowShouldClose(
                    presentationHandle,
                    false
            );

            glfwHideWindow(
                    presentationHandle
            );

            candidateHiddenInsideCloseCallback =
                    glfwGetWindowAttrib(
                            presentationHandle,
                            GLFW_VISIBLE
                    ) == GLFW_FALSE;

            /*
             * Route the close request directly to Minecraft's actual window.
             * Minecraft's normal main-loop shutdown path remains responsible
             * for world save, renderer teardown and final GLFW destruction.
             */
            glfwSetWindowShouldClose(
                    lifecycleOwnerHandle,
                    true
            );

            lifecycleOwnerShouldCloseAfterForward =
                    glfwWindowShouldClose(
                            lifecycleOwnerHandle
                    );

            closeForwardImmediateNoRenderCallback =
                    lifecycleOwnerShouldCloseAfterForward;

            if (lifecycleOwnerShouldCloseAfterForward) {
                closeForwardSuccessCount++;
            } else {
                lastFailure =
                        "MINECRAFT_LIFECYCLE_OWNER_DID_NOT_ACCEPT_SHOULD_CLOSE";
            }
        } catch (Throwable throwable) {
            lastFailure =
                    throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        } finally {
            enrich();
        }
    }

    private synchronized void onPresentationFocus(
            long window,
            boolean focused
    ) {
        if (window != presentationHandle) {
            return;
        }

        try {
            if (lifecycleOwnerFocusCallback != null) {
                /*
                 * Keep Minecraft's logical focus state in sync with the one
                 * visible presentation window, but never hand native Win32/
                 * GLFW focus back to the hidden OpenGL lifecycle owner while
                 * the Vulkan gameplay candidate is visible.
                 */
                lifecycleOwnerFocusCallback.invoke(
                        lifecycleOwnerHandle,
                        focused
                );
                logicalFocusRelayEventCount++;
            }
        } catch (Throwable throwable) {
            logicalFocusRelayFailureCount++;
            recordInputRelayFailure(
                    "LOGICAL_FOCUS",
                    throwable
            );
        }

        if (focused) {
            presentationFocusGainCount++;
            synchronizePresentationCursorMode();

            Minecraft minecraft =
                    Minecraft.getInstance();

            boolean screenOpen =
                    minecraft != null
                            && minecraft.screen != null;

            presentationMenuFocusLeaseActive =
                    screenOpen;

            if (screenOpen) {
                presentationMenuFocusLeaseCount++;
            } else {
                presentationNativeFocusRetentionCount++;
            }
        } else {
            presentationFocusLossCount++;
            presentationMenuFocusLeaseActive =
                    false;
        }

        enrich();
    }

    private synchronized void captureLifecycleOwnerPointerCallbacks() {
        if (lifecycleOwnerHandle == NULL) {
            lastFailure =
                    "MINECRAFT_LIFECYCLE_OWNER_HANDLE_NULL_DURING_INPUT_CAPTURE";
            return;
        }

        try {
            lifecycleOwnerCursorPosCallback =
                    glfwSetCursorPosCallback(
                            lifecycleOwnerHandle,
                            null
                    );

            glfwSetCursorPosCallback(
                    lifecycleOwnerHandle,
                    lifecycleOwnerCursorPosCallback
            );

            lifecycleOwnerCursorPosCallbackCaptured =
                    lifecycleOwnerCursorPosCallback != null;

            lifecycleOwnerMouseButtonCallback =
                    glfwSetMouseButtonCallback(
                            lifecycleOwnerHandle,
                            null
                    );

            glfwSetMouseButtonCallback(
                    lifecycleOwnerHandle,
                    lifecycleOwnerMouseButtonCallback
            );

            lifecycleOwnerMouseButtonCallbackCaptured =
                    lifecycleOwnerMouseButtonCallback != null;

            lifecycleOwnerScrollCallback =
                    glfwSetScrollCallback(
                            lifecycleOwnerHandle,
                            null
                    );

            glfwSetScrollCallback(
                    lifecycleOwnerHandle,
                    lifecycleOwnerScrollCallback
            );

            lifecycleOwnerScrollCallbackCaptured =
                    lifecycleOwnerScrollCallback != null;

            lifecycleOwnerFocusCallback =
                    glfwSetWindowFocusCallback(
                            lifecycleOwnerHandle,
                            null
                    );

            glfwSetWindowFocusCallback(
                    lifecycleOwnerHandle,
                    lifecycleOwnerFocusCallback
            );

            lifecycleOwnerFocusCallbackCaptured =
                    lifecycleOwnerFocusCallback != null;

            lifecycleOwnerKeyCallback =
                    glfwSetKeyCallback(
                            lifecycleOwnerHandle,
                            null
                    );

            glfwSetKeyCallback(
                    lifecycleOwnerHandle,
                    lifecycleOwnerKeyCallback
            );

            lifecycleOwnerKeyCallbackCaptured =
                    lifecycleOwnerKeyCallback != null;

            lifecycleOwnerCharModsCallback =
                    glfwSetCharModsCallback(
                            lifecycleOwnerHandle,
                            null
                    );

            glfwSetCharModsCallback(
                    lifecycleOwnerHandle,
                    lifecycleOwnerCharModsCallback
            );

            lifecycleOwnerCharModsCallbackCaptured =
                    lifecycleOwnerCharModsCallback != null;
        } catch (Throwable throwable) {
            lastFailure =
                    "INPUT_CALLBACK_CAPTURE_"
                            + throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        }
    }

    private synchronized void onPresentationCursorPos(
            long window,
            double x,
            double y
    ) {
        if (!pointerRelayActive(window)
                || lifecycleOwnerCursorPosCallback == null) {
            inputRelayDropCount++;
            enrich();
            return;
        }

        try {
            synchronizePresentationCursorMode();

            int lifecycleCursorMode =
                    glfwGetInputMode(
                            lifecycleOwnerHandle,
                            GLFW_CURSOR
                    );

            boolean menuCursor =
                    lifecycleCursorMode != GLFW_CURSOR_DISABLED;

            if (menuCursor) {
                /*
                 * Menu/screen coordinates are absolute and already proven.
                 * Do not warp the hidden native owner.
                 */
                resetGameplayCursorDeltaBridge();

                cursorPositionSyncAttemptCount++;
                menuCursorRelayEventCount++;

                lifecycleOwnerCursorPosCallback.invoke(
                        lifecycleOwnerHandle,
                        x,
                        y
                );

                cursorPositionSyncSuccessCount++;
            } else {
                relayGameplayCursorDelta(
                        x,
                        y
                );
            }

            cursorRelayEventCount++;

            Minecraft minecraft =
                    Minecraft.getInstance();

            Screen currentScreen =
                    minecraft != null
                            ? minecraft.screen
                            : null;

            if (menuCursor
                    && deferredScreenDragActive
                    && currentScreen != null
                    && currentScreen == deferredScreenDragScreen
                    && glfwGetMouseButton(
                    presentationHandle,
                    deferredScreenDragButton
            ) == GLFW_PRESS) {
                deferredScreenDragCursorSampleCount++;

                if (deferredScreenDragPending) {
                    deferredScreenDragCoalescedCount++;
                }

                deferredScreenDragPendingX =
                        x;
                deferredScreenDragPendingY =
                        y;
                deferredScreenDragPending =
                        true;
            }
        } catch (Throwable throwable) {
            inputRelayDropCount++;
            resetGameplayCursorDeltaBridge();
            recordInputRelayFailure(
                    "CURSOR",
                    throwable
            );
        }

        enrich();
    }

    private synchronized void onPresentationMouseButton(
            long window,
            int button,
            int action,
            int mods
    ) {
        if (!pointerRelayActive(window)
                || lifecycleOwnerMouseButtonCallback == null
                || lifecycleOwnerCursorPosCallback == null) {
            inputRelayDropCount++;
            enrich();
            return;
        }

        try {
            Minecraft minecraft =
                    Minecraft.getInstance();

            Screen screen =
                    minecraft != null
                            ? minecraft.screen
                            : null;

            if (screen != null
                    && minecraft.getWindow() != null) {
                double candidateX;
                double candidateY;

                try (MemoryStack stack = MemoryStack.stackPush()) {
                    DoubleBuffer actualX =
                            stack.mallocDouble(1);
                    DoubleBuffer actualY =
                            stack.mallocDouble(1);

                    glfwGetCursorPos(
                            presentationHandle,
                            actualX,
                            actualY
                    );

                    candidateX = actualX.get(0);
                    candidateY = actualY.get(0);
                }

                /*
                 * Screen/menu input is the only mouse-button path that needs an
                 * absolute cursor position. It is safe here because Minecraft
                 * Screen coordinates are intentionally absolute.
                 */
                mouseButtonCoordinateSyncAttemptCount++;

                lifecycleOwnerCursorPosCallback.invoke(
                        lifecycleOwnerHandle,
                        candidateX,
                        candidateY
                );

                mouseButtonCoordinateSyncSuccessCount++;

                if (deferredScreenMouseEvents.size()
                        >= MAX_DEFERRED_SCREEN_MOUSE_EVENTS) {
                    deferredScreenMouseOverflowDropCount++;
                    directScreenMouseDispatchFailureCount++;
                    inputRelayDropCount++;
                    lastFailure =
                            "DEFERRED_SCREEN_MOUSE_QUEUE_OVERFLOW";
                    enrich();
                    return;
                }

                if (action == GLFW_PRESS) {
                    directScreenMousePressAttemptCount++;
                }

                deferredScreenMouseEvents.addLast(
                        new DeferredScreenMouseEvent(
                                screen,
                                deferredScreenMouseLifecycleEpoch,
                                candidateX,
                                candidateY,
                                button,
                                action,
                                mods
                        )
                );

                deferredScreenMouseEnqueueCount++;

                deferredScreenMouseHighWaterMark =
                        Math.max(
                                deferredScreenMouseHighWaterMark,
                                deferredScreenMouseEvents.size()
                        );

                lastDirectScreenClass =
                        screen.getClass().getName();

                mouseButtonRelayEventCount++;
                presentationMenuFocusLeaseActive =
                        true;
            } else {
                /*
                 * Patch 146: a gameplay mouse button is cursor-state-neutral.
                 *
                 * The presentation cursor stream already supplies continuous
                 * relative movement. Re-polling, resetting or re-seeding that
                 * stream at button time creates an asynchronous discontinuity
                 * precisely when the player is turning while clicking. Native
                 * focus transfer to the hidden OpenGL window is equally
                 * forbidden here.
                 *
                 * Forward only the button edge. Cursor/focus state is left
                 * completely untouched.
                 */
                gameplayMouseButtonAbsoluteCursorSyncSuppressedCount++;
                gameplayMouseButtonCursorStateUntouchedCount++;

                lifecycleOwnerMouseButtonCallback.invoke(
                        lifecycleOwnerHandle,
                        button,
                        action,
                        mods
                );

                mouseButtonRelayEventCount++;
                presentationMenuFocusLeaseActive =
                        false;
                presentationNativeFocusRetentionCount++;
            }
        } catch (Throwable throwable) {
            directScreenMouseDispatchFailureCount++;
            inputRelayDropCount++;
            resetGameplayCursorDeltaBridge();
            recordInputRelayFailure(
                    "MOUSE_BUTTON",
                    throwable
            );
        }

        enrich();
    }

    private synchronized void flushDeferredScreenInput() {
        if (closed
                || (deferredScreenMouseEvents.isEmpty()
                && !deferredScreenDragPending)) {
            enrich();
            return;
        }

        Minecraft minecraft =
                Minecraft.getInstance();

        if (minecraft == null
                || minecraft.getWindow() == null) {
            long discardedButtonEvents =
                    deferredScreenMouseEvents.size();

            directScreenMouseDispatchFailureCount +=
                    discardedButtonEvents;
            inputRelayDropCount +=
                    discardedButtonEvents;
            deferredScreenMouseEvents.clear();

            if (deferredScreenDragPending
                    || deferredScreenDragActive) {
                deferredScreenDragDispatchFailureCount++;
                inputRelayDropCount++;
                clearDeferredScreenDragState();
            }

            if (lastFailure.isEmpty()) {
                lastFailure =
                        "DEFERRED_SCREEN_INPUT_MINECRAFT_WINDOW_UNAVAILABLE";
            }

            enrich();
            return;
        }

        /*
         * Drag movement is a coalesced render-thread action, never a native
         * callback action. While a drag is active, dispatch at most one latest
         * movement sample per completed frame. Once GLFW reports RELEASE, no new
         * drag samples are accepted; the final pending movement is flushed once
         * and the queued RELEASE follows on the next completed frame.
         */
        if (deferredScreenDragPending
                && deferredScreenDragActive) {
            Screen currentScreen =
                    minecraft.screen;

            if (currentScreen == null
                    || currentScreen != deferredScreenDragScreen) {
                deferredScreenDragStaleDiscardCount++;
                clearDeferredScreenDragState();
            } else {
                try {
                    double guiX =
                            deferredScreenDragPendingX
                                    * minecraft.getWindow()
                                    .getGuiScaledWidth()
                                    / Math.max(
                                    1.0,
                                    minecraft.getWindow()
                                            .getScreenWidth()
                            );

                    double guiY =
                            deferredScreenDragPendingY
                                    * minecraft.getWindow()
                                    .getGuiScaledHeight()
                                    / Math.max(
                                    1.0,
                                    minecraft.getWindow()
                                            .getScreenHeight()
                            );

                    double dragX =
                            guiX - deferredScreenDragLastGuiX;

                    double dragY =
                            guiY - deferredScreenDragLastGuiY;

                    VulkanGate11ModifierStateBridge
                            .beginScreenDispatch(
                                    deferredScreenDragModifiers
                            );

                    try {
                        if (currentScreen.mouseDragged(
                                guiX,
                                guiY,
                                deferredScreenDragButton,
                                dragX,
                                dragY
                        )) {
                            deferredScreenDragAcceptedCount++;
                        }
                    } finally {
                        VulkanGate11ModifierStateBridge
                                .endScreenDispatch();
                    }

                    deferredScreenDragLastGuiX =
                            guiX;
                    deferredScreenDragLastGuiY =
                            guiY;
                    deferredScreenDragPending =
                            false;
                    deferredScreenDragFlushCount++;
                } catch (Throwable throwable) {
                    deferredScreenDragDispatchFailureCount++;
                    inputRelayDropCount++;
                    clearDeferredScreenDragState();
                    recordInputRelayFailure(
                            "DEFERRED_SCREEN_DRAG",
                            throwable
                    );
                }

                enrich();
                return;
            }
        }

        if (deferredScreenMouseEvents.isEmpty()) {
            enrich();
            return;
        }

        /*
         * Patch 130: process at most one queued Screen press/release after a
         * complete rendered frame. Patch 135 preserves that lifecycle barrier
         * and layers the coalesced drag path above it.
         */
        DeferredScreenMouseEvent event =
                deferredScreenMouseEvents.removeFirst();

        Screen screen =
                minecraft.screen;

        if (screen == null
                || screen != event.targetScreen()) {
            /*
             * Patch 136 release closure:
             * a lifecycle-changing click may replace the Screen while additional
             * native button events from the OLD Screen are already queued.
             *
             * The queue now carries the exact lifecycle epoch captured at
             * enqueue time. Only events which are provably older than a Screen
             * transition observed by THIS deferred dispatcher are benign stale
             * input. An unexplained same-epoch Screen mismatch remains a hard
             * input-relay failure.
             */
            boolean causallyStale =
                    event.lifecycleEpoch()
                            < deferredScreenMouseLifecycleEpoch;

            if (event.action() == GLFW_RELEASE
                    || causallyStale) {
                if (event.action() == GLFW_RELEASE) {
                    deferredScreenMouseStaleReleaseDiscardCount++;
                } else {
                    deferredScreenMouseStalePressDiscardCount++;
                }

                if (causallyStale) {
                    deferredScreenMouseCausalStaleDiscardCount++;
                }

                deferredScreenMouseFlushCount++;
                clearDeferredScreenDragState();
                enrich();
                return;
            }

            directScreenMouseDispatchFailureCount++;
            inputRelayDropCount++;

            if (lastFailure.isEmpty()) {
                lastFailure =
                        "DEFERRED_SCREEN_MOUSE_UNEXPLAINED_SAME_EPOCH_TARGET_CHANGE";
            }

            deferredScreenMouseFlushCount++;
            clearDeferredScreenDragState();
            enrich();
            return;
        }

        boolean pressAccepted =
                false;

        try {
            double guiX =
                    event.x()
                            * minecraft.getWindow().getGuiScaledWidth()
                            / Math.max(
                            1.0,
                            minecraft.getWindow().getScreenWidth()
                    );

            double guiY =
                    event.y()
                            * minecraft.getWindow().getGuiScaledHeight()
                            / Math.max(
                            1.0,
                            minecraft.getWindow().getScreenHeight()
                    );

            lastDirectScreenClass =
                    screen.getClass().getName();

            VulkanGate11ModifierStateBridge
                    .beginScreenDispatch(
                            event.modifiers()
                    );

            try {
                if (event.action() == GLFW_PRESS) {
                    pressAccepted =
                            screen.mouseClicked(
                                    guiX,
                                    guiY,
                                    event.button()
                            );

                    if (pressAccepted) {
                        directScreenMousePressAcceptedCount++;
                    }
                } else if (event.action() == GLFW_RELEASE) {
                    screen.mouseReleased(
                            guiX,
                            guiY,
                            event.button()
                    );

                    directScreenMouseReleaseCount++;
                }
            } finally {
                VulkanGate11ModifierStateBridge
                        .endScreenDispatch();
            }

            deferredScreenMouseFlushCount++;

            if (event.action() == GLFW_PRESS
                    && pressAccepted) {
                deferredScreenDragActive =
                        true;
                deferredScreenDragScreen =
                        screen;
                deferredScreenDragButton =
                        event.button();
                deferredScreenDragModifiers =
                        event.modifiers();
                deferredScreenDragLastGuiX =
                        guiX;
                deferredScreenDragLastGuiY =
                        guiY;
                deferredScreenDragPending =
                        false;
                deferredScreenDragArmCount++;
            } else if (event.action() == GLFW_RELEASE
                    && deferredScreenDragActive
                    && deferredScreenDragScreen == screen
                    && deferredScreenDragButton == event.button()) {
                clearDeferredScreenDragState();
            }
        } catch (Throwable throwable) {
            directScreenMouseDispatchFailureCount++;
            inputRelayDropCount++;
            clearDeferredScreenDragState();
            recordInputRelayFailure(
                    "DEFERRED_SCREEN_MOUSE",
                    throwable
            );
        }

        Minecraft afterDispatch =
                Minecraft.getInstance();

        Screen screenAfterDispatch =
                afterDispatch != null
                        ? afterDispatch.screen
                        : null;

        if (screenAfterDispatch != screen) {
            deferredScreenMouseLifecycleTransitionCount++;
            deferredScreenMouseLifecycleEpoch++;
            clearDeferredScreenDragState();
        }

        if (screenAfterDispatch != null) {
            presentationMenuFocusLeaseActive =
                    true;
        } else {
            presentationMenuFocusLeaseActive =
                    false;

            /*
             * Screen close changes native cursor mode/focus. Do not carry a
             * menu-era relative seed into gameplay; the first subsequent
             * gameplay cursor event seeds without producing camera movement.
             */
            resetGameplayCursorDeltaBridge();
            postScreenGameplayDeltaResetCount++;
            presentationNativeFocusRetentionCount++;
        }

        enrich();
    }

    private void clearDeferredScreenDragState() {
        deferredScreenDragActive =
                false;
        deferredScreenDragScreen =
                null;
        deferredScreenDragButton =
                -1;
        deferredScreenDragModifiers =
                0;
        deferredScreenDragPending =
                false;
        deferredScreenDragPendingX =
                0.0;
        deferredScreenDragPendingY =
                0.0;
        deferredScreenDragLastGuiX =
                0.0;
        deferredScreenDragLastGuiY =
                0.0;
    }

    private synchronized void onPresentationScroll(
            long window,
            double xOffset,
            double yOffset
    ) {
        if (!pointerRelayActive(window)
                || lifecycleOwnerScrollCallback == null) {
            inputRelayDropCount++;
            enrich();
            return;
        }

        try {
            lifecycleOwnerScrollCallback.invoke(
                    lifecycleOwnerHandle,
                    xOffset,
                    yOffset
            );

            scrollRelayEventCount++;
        } catch (Throwable throwable) {
            inputRelayDropCount++;
            recordInputRelayFailure(
                    "SCROLL",
                    throwable
            );
        }

        enrich();
    }

    /**
     * F11 belongs to the one visible presentation window during the hybrid
     * Gate-11 session. Minecraft's hidden OpenGL lifecycle owner must never
     * enter native fullscreen while it is expected to stay hidden.
     */
    private boolean togglePresentationFullscreen() {
        presentationF11ToggleAttemptCount++;

        if (presentationHandle == NULL) {
            presentationF11ToggleFailureCount++;
            presentationFullscreenState =
                    "TOGGLE_REJECTED_PRESENTATION_HANDLE_NULL";
            return false;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            long currentMonitor =
                    glfwGetWindowMonitor(
                            presentationHandle
                    );

            if (currentMonitor == NULL) {
                capturePresentationWindowedBounds(
                        stack
                );

                long targetMonitor =
                        selectPresentationMonitor(
                                stack
                        );

                if (targetMonitor == NULL) {
                    presentationF11ToggleFailureCount++;
                    presentationFullscreenState =
                            "ENTER_FAILED_NO_MONITOR";
                    return false;
                }

                org.lwjgl.glfw.GLFWVidMode videoMode =
                        glfwGetVideoMode(
                                targetMonitor
                        );

                if (videoMode == null) {
                    presentationF11ToggleFailureCount++;
                    presentationFullscreenState =
                            "ENTER_FAILED_VIDEO_MODE_UNAVAILABLE";
                    return false;
                }

                glfwSetWindowMonitor(
                        presentationHandle,
                        targetMonitor,
                        0,
                        0,
                        videoMode.width(),
                        videoMode.height(),
                        videoMode.refreshRate()
                );

                if (glfwGetWindowMonitor(
                        presentationHandle
                ) != targetMonitor) {
                    presentationF11ToggleFailureCount++;
                    presentationFullscreenState =
                            "ENTER_FAILED_MONITOR_NOT_APPLIED";
                    return false;
                }

                presentationFullscreenOwnedByRouter =
                        true;
                presentationFullscreenEnterCount++;
                presentationFullscreenState =
                        "FULLSCREEN_PRESENTATION_ROUTER_OWNED";
            } else {
                if (!presentationWindowedBoundsCaptured) {
                    capturePresentationWindowedBoundsFromLifecycleOwner(
                            stack
                    );
                }

                glfwSetWindowMonitor(
                        presentationHandle,
                        NULL,
                        presentationWindowedX,
                        presentationWindowedY,
                        Math.max(
                                1,
                                presentationWindowedWidth
                        ),
                        Math.max(
                                1,
                                presentationWindowedHeight
                        ),
                        GLFW_DONT_CARE
                );

                if (glfwGetWindowMonitor(
                        presentationHandle
                ) != NULL) {
                    presentationF11ToggleFailureCount++;
                    presentationFullscreenState =
                            "EXIT_FAILED_MONITOR_STILL_ATTACHED";
                    return false;
                }

                presentationFullscreenOwnedByRouter =
                        false;
                presentationFullscreenExitCount++;
                presentationFullscreenState =
                        "WINDOWED_PRESENTATION_ROUTER_OWNED";
            }

            /*
             * A monitor switch legitimately changes the native cursor space.
             * Reset once at the topology boundary, never at mouse-button time.
             */
            resetGameplayCursorDeltaBridge();
            presentationFullscreenCursorDeltaResetCount++;

            synchronizePresentationCursorMode();

            presentationF11ToggleSuccessCount++;

            return true;
        } catch (Throwable throwable) {
            presentationF11ToggleFailureCount++;
            presentationFullscreenState =
                    "TOGGLE_FAILURE: "
                            + throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );

            recordInputRelayFailure(
                    "F11_PRESENTATION_FULLSCREEN",
                    throwable
            );

            return false;
        }
    }

    private void capturePresentationWindowedBounds(
            MemoryStack stack
    ) {
        IntBuffer x =
                stack.mallocInt(1);
        IntBuffer y =
                stack.mallocInt(1);
        IntBuffer width =
                stack.mallocInt(1);
        IntBuffer height =
                stack.mallocInt(1);

        glfwGetWindowPos(
                presentationHandle,
                x,
                y
        );

        glfwGetWindowSize(
                presentationHandle,
                width,
                height
        );

        presentationWindowedX =
                x.get(0);
        presentationWindowedY =
                y.get(0);
        presentationWindowedWidth =
                Math.max(
                        1,
                        width.get(0)
                );
        presentationWindowedHeight =
                Math.max(
                        1,
                        height.get(0)
                );
        presentationWindowedBoundsCaptured =
                true;
    }

    private void capturePresentationWindowedBoundsFromLifecycleOwner(
            MemoryStack stack
    ) {
        IntBuffer x =
                stack.mallocInt(1);
        IntBuffer y =
                stack.mallocInt(1);
        IntBuffer width =
                stack.mallocInt(1);
        IntBuffer height =
                stack.mallocInt(1);

        glfwGetWindowPos(
                lifecycleOwnerHandle,
                x,
                y
        );

        glfwGetWindowSize(
                lifecycleOwnerHandle,
                width,
                height
        );

        presentationWindowedX =
                x.get(0);
        presentationWindowedY =
                y.get(0);
        presentationWindowedWidth =
                Math.max(
                        1,
                        width.get(0)
                );
        presentationWindowedHeight =
                Math.max(
                        1,
                        height.get(0)
                );
        presentationWindowedBoundsCaptured =
                true;
    }

    private long selectPresentationMonitor(
            MemoryStack stack
    ) {
        PointerBuffer monitors =
                glfwGetMonitors();

        if (monitors == null
                || monitors.limit() == 0) {
            return glfwGetPrimaryMonitor();
        }

        IntBuffer windowX =
                stack.mallocInt(1);
        IntBuffer windowY =
                stack.mallocInt(1);
        IntBuffer windowWidth =
                stack.mallocInt(1);
        IntBuffer windowHeight =
                stack.mallocInt(1);
        IntBuffer monitorX =
                stack.mallocInt(1);
        IntBuffer monitorY =
                stack.mallocInt(1);

        glfwGetWindowPos(
                presentationHandle,
                windowX,
                windowY
        );
        glfwGetWindowSize(
                presentationHandle,
                windowWidth,
                windowHeight
        );

        int left =
                windowX.get(0);
        int top =
                windowY.get(0);
        int right =
                left
                        + Math.max(
                        1,
                        windowWidth.get(0)
                );
        int bottom =
                top
                        + Math.max(
                        1,
                        windowHeight.get(0)
                );

        long bestMonitor =
                glfwGetPrimaryMonitor();
        long bestIntersectionArea =
                -1L;

        for (int index = 0;
             index < monitors.limit();
             index++) {
            long monitor =
                    monitors.get(index);

            org.lwjgl.glfw.GLFWVidMode videoMode =
                    glfwGetVideoMode(
                            monitor
                    );

            if (videoMode == null) {
                continue;
            }

            glfwGetMonitorPos(
                    monitor,
                    monitorX,
                    monitorY
            );

            int monitorLeft =
                    monitorX.get(0);
            int monitorTop =
                    monitorY.get(0);
            int monitorRight =
                    monitorLeft
                            + videoMode.width();
            int monitorBottom =
                    monitorTop
                            + videoMode.height();

            int intersectionWidth =
                    Math.max(
                            0,
                            Math.min(
                                    right,
                                    monitorRight
                            )
                                    - Math.max(
                                    left,
                                    monitorLeft
                            )
                    );

            int intersectionHeight =
                    Math.max(
                            0,
                            Math.min(
                                    bottom,
                                    monitorBottom
                            )
                                    - Math.max(
                                    top,
                                    monitorTop
                            )
                    );

            long intersectionArea =
                    (long) intersectionWidth
                            * (long) intersectionHeight;

            if (intersectionArea
                    > bestIntersectionArea) {
                bestIntersectionArea =
                        intersectionArea;
                bestMonitor =
                        monitor;
            }
        }

        return bestMonitor;
    }

    /**
     * A fullscreen GLFW window may not reliably hide on Windows. Normalize the
     * presentation candidate back to its saved windowed bounds before any
     * Vulkan -> OpenGL handoff/fail-open hide.
     */
    synchronized boolean normalizePresentationWindowedForHide() {
        if (presentationHandle == NULL) {
            return true;
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (glfwGetWindowMonitor(
                    presentationHandle
            ) == NULL) {
                presentationFullscreenOwnedByRouter =
                        false;
                return true;
            }

            if (!presentationWindowedBoundsCaptured) {
                capturePresentationWindowedBoundsFromLifecycleOwner(
                        stack
                );
            }

            glfwSetWindowMonitor(
                    presentationHandle,
                    NULL,
                    presentationWindowedX,
                    presentationWindowedY,
                    Math.max(
                            1,
                            presentationWindowedWidth
                    ),
                    Math.max(
                            1,
                            presentationWindowedHeight
                    ),
                    GLFW_DONT_CARE
            );

            boolean normalized =
                    glfwGetWindowMonitor(
                            presentationHandle
                    ) == NULL;

            if (normalized) {
                presentationFullscreenOwnedByRouter =
                        false;
                presentationFullscreenForcedWindowedForHandoffCount++;
                presentationFullscreenState =
                        "WINDOWED_FOR_OPENGL_HANDOFF";
                resetGameplayCursorDeltaBridge();
                presentationFullscreenCursorDeltaResetCount++;
            } else {
                presentationF11ToggleFailureCount++;
                presentationFullscreenState =
                        "HANDOFF_NORMALIZATION_FAILED";
            }

            enrich();

            return normalized;
        } catch (Throwable throwable) {
            presentationF11ToggleFailureCount++;
            presentationFullscreenState =
                    "HANDOFF_NORMALIZATION_FAILURE: "
                            + throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
            enrich();
            return false;
        }
    }

    synchronized boolean presentationWindowedBoundsCaptured() {
        return presentationWindowedBoundsCaptured;
    }

    synchronized int presentationWindowedX() {
        return presentationWindowedX;
    }

    synchronized int presentationWindowedY() {
        return presentationWindowedY;
    }

    synchronized int presentationWindowedWidth() {
        return presentationWindowedWidth;
    }

    synchronized int presentationWindowedHeight() {
        return presentationWindowedHeight;
    }

    private synchronized void onPresentationKey(
            long window,
            int key,
            int scancode,
            int action,
            int mods
    ) {
        if (!keyboardRelayActive(window)
                || lifecycleOwnerKeyCallback == null) {
            /*
             * Patch 127: Windows may deliver the RELEASE tail of Alt/F4 after
             * the candidate close callback already hid the Vulkan window and
             * successfully forwarded should-close to Minecraft's real lifecycle
             * owner. Those events are no longer gameplay input and must not turn
             * a proven clean close route into a synthetic keyboard-drop failure.
             */
            if (closeForwardSuccessCount > 0L
                    && candidateHiddenInsideCloseCallback
                    && lifecycleOwnerShouldCloseAfterForward) {
                keyboardPostCloseTailIgnoredCount++;
            } else {
                keyboardRelayDropCount++;
            }

            enrich();
            return;
        }

        try {
            /*
             * Patch 147: F11 must never reach Minecraft's hidden OpenGL GLFW
             * owner while the Vulkan candidate is the one visible gameplay
             * window. Consume the complete key edge/tail and apply fullscreen
             * directly to the presentation candidate.
             */
            if (key == GLFW_KEY_F11) {
                presentationF11EventConsumedCount++;

                if (action == GLFW_PRESS) {
                    presentationF11PressConsumedCount++;
                    togglePresentationFullscreen();
                }

                enrich();
                return;
            }

            lifecycleOwnerKeyCallback.invoke(
                    lifecycleOwnerHandle,
                    key,
                    scancode,
                    action,
                    mods
            );

            keyRelayEventCount++;
            lastRelayedKey =
                    key;
            lastRelayedKeyAction =
                    action;

            if (key == GLFW_KEY_ESCAPE
                    && action == GLFW_PRESS) {
                escapeKeyPressRelayCount++;
            }

            /*
             * Escape and other key actions can open/close Screens, which also
             * changes Minecraft's requested cursor mode. Mirror that mode back
             * onto the visible Vulkan window immediately.
             */
            synchronizePresentationCursorMode();
        } catch (Throwable throwable) {
            keyboardRelayDropCount++;
            recordInputRelayFailure(
                    "KEYBOARD_KEY",
                    throwable
            );
        }

        enrich();
    }

    private synchronized void onPresentationCharMods(
            long window,
            int codepoint,
            int mods
    ) {
        if (!keyboardRelayActive(window)
                || lifecycleOwnerCharModsCallback == null) {
            if (closeForwardSuccessCount > 0L
                    && candidateHiddenInsideCloseCallback
                    && lifecycleOwnerShouldCloseAfterForward) {
                charPostCloseTailIgnoredCount++;
            } else {
                keyboardRelayDropCount++;
            }

            enrich();
            return;
        }

        try {
            lifecycleOwnerCharModsCallback.invoke(
                    lifecycleOwnerHandle,
                    codepoint,
                    mods
            );

            charModsRelayEventCount++;
        } catch (Throwable throwable) {
            keyboardRelayDropCount++;
            recordInputRelayFailure(
                    "KEYBOARD_CHAR_MODS",
                    throwable
            );
        }

        enrich();
    }

    private boolean keyboardRelayActive(
            long window
    ) {
        return !closed
                && presentationHandleBound
                && window != NULL
                && window == presentationHandle
                && lifecycleOwnerHandle != NULL
                && glfwGetWindowAttrib(
                presentationHandle,
                GLFW_VISIBLE
        ) == GLFW_TRUE;
    }

    private boolean pointerRelayActive(
            long window
    ) {
        return !closed
                && presentationHandleBound
                && window != NULL
                && window == presentationHandle
                && lifecycleOwnerHandle != NULL
                && glfwGetWindowAttrib(
                presentationHandle,
                GLFW_VISIBLE
        ) == GLFW_TRUE;
    }

    private void resetGameplayCursorDeltaBridge() {
        gameplayCursorDeltaSeeded =
                false;

        gameplayCursorScaleX =
                1.0;

        gameplayCursorScaleY =
                1.0;
    }

    private void relayGameplayCursorDelta(
            double presentationX,
            double presentationY
    ) {
        if (!gameplayCursorDeltaSeeded) {
            seedGameplayCursorDeltaBridge(
                    presentationX,
                    presentationY
            );

            return;
        }

        double deltaX =
                presentationX
                        - gameplayCursorLastPresentationX;

        double deltaY =
                presentationY
                        - gameplayCursorLastPresentationY;

        gameplayCursorLastPresentationX =
                presentationX;

        gameplayCursorLastPresentationY =
                presentationY;

        if (!Double.isFinite(deltaX)
                || !Double.isFinite(deltaY)
                || Math.abs(deltaX) > 2048.0
                || Math.abs(deltaY) > 2048.0) {
            gameplayCursorDeltaOutlierReseedCount++;

            seedGameplayCursorDeltaBridge(
                    presentationX,
                    presentationY
            );

            return;
        }

        gameplayCursorSyntheticLifecycleX +=
                deltaX
                        * gameplayCursorScaleX;

        gameplayCursorSyntheticLifecycleY +=
                deltaY
                        * gameplayCursorScaleY;

        lifecycleOwnerCursorPosCallback.invoke(
                lifecycleOwnerHandle,
                gameplayCursorSyntheticLifecycleX,
                gameplayCursorSyntheticLifecycleY
        );

        gameplayCursorDeltaForwardCount++;
    }

    private void seedGameplayCursorDeltaBridge(
            double presentationX,
            double presentationY
    ) {
        gameplayCursorLastPresentationX =
                presentationX;

        gameplayCursorLastPresentationY =
                presentationY;

        gameplayCursorScaleX =
                1.0;

        gameplayCursorScaleY =
                1.0;

        try (MemoryStack stack =
                     MemoryStack.stackPush()) {
            DoubleBuffer lifecycleX =
                    stack.mallocDouble(
                            1
                    );

            DoubleBuffer lifecycleY =
                    stack.mallocDouble(
                            1
                    );

            glfwGetCursorPos(
                    lifecycleOwnerHandle,
                    lifecycleX,
                    lifecycleY
            );

            gameplayCursorSyntheticLifecycleX =
                    lifecycleX.get(
                            0
                    );

            gameplayCursorSyntheticLifecycleY =
                    lifecycleY.get(
                            0
                    );

            IntBuffer lifecycleWidth =
                    stack.mallocInt(
                            1
                    );

            IntBuffer lifecycleHeight =
                    stack.mallocInt(
                            1
                    );

            IntBuffer presentationWidth =
                    stack.mallocInt(
                            1
                    );

            IntBuffer presentationHeight =
                    stack.mallocInt(
                            1
                    );

            glfwGetWindowSize(
                    lifecycleOwnerHandle,
                    lifecycleWidth,
                    lifecycleHeight
            );

            glfwGetWindowSize(
                    presentationHandle,
                    presentationWidth,
                    presentationHeight
            );

            int lifecycleLogicalWidth =
                    lifecycleWidth.get(
                            0
                    );

            int lifecycleLogicalHeight =
                    lifecycleHeight.get(
                            0
                    );

            int presentationLogicalWidth =
                    presentationWidth.get(
                            0
                    );

            int presentationLogicalHeight =
                    presentationHeight.get(
                            0
                    );

            if (lifecycleLogicalWidth > 0
                    && lifecycleLogicalHeight > 0
                    && presentationLogicalWidth > 0
                    && presentationLogicalHeight > 0) {
                gameplayCursorScaleX =
                        boundedCursorDeltaScale(
                                (double) lifecycleLogicalWidth
                                        / presentationLogicalWidth
                        );

                gameplayCursorScaleY =
                        boundedCursorDeltaScale(
                                (double) lifecycleLogicalHeight
                                        / presentationLogicalHeight
                        );

                gameplayCursorDeltaScaleRefreshCount++;
            }
        }

        gameplayCursorDeltaSeeded =
                true;

        gameplayCursorDeltaSeedCount++;
    }

    private static double boundedCursorDeltaScale(
            double candidate
    ) {
        if (!Double.isFinite(candidate)
                || candidate <= 0.0) {
            return 1.0;
        }

        return Math.max(
                0.125,
                Math.min(
                        8.0,
                        candidate
                )
        );
    }

    private void synchronizePresentationCursorMode() {
        if (closed
                || !presentationHandleBound
                || lifecycleOwnerHandle == NULL
                || presentationHandle == NULL) {
            return;
        }

        cursorModeSyncAttemptCount++;

        try {
            int lifecycleMode =
                    glfwGetInputMode(
                            lifecycleOwnerHandle,
                            GLFW_CURSOR
                    );

            int presentationMode =
                    glfwGetInputMode(
                            presentationHandle,
                            GLFW_CURSOR
                    );

            lastLifecycleOwnerCursorMode =
                    lifecycleMode;

            if (presentationMode != lifecycleMode) {
                glfwSetInputMode(
                        presentationHandle,
                        GLFW_CURSOR,
                        lifecycleMode
                );
            }

            lastPresentationCursorMode =
                    glfwGetInputMode(
                            presentationHandle,
                            GLFW_CURSOR
                    );

            if (lastPresentationCursorMode == lifecycleMode) {
                cursorModeSyncSuccessCount++;
            } else {
                cursorModeMismatchCount++;
            }
        } catch (Throwable throwable) {
            cursorModeMismatchCount++;
            recordInputRelayFailure(
                    "CURSOR_MODE_SYNC",
                    throwable
            );
        }
    }

    /**
     * Verify both modifier boundaries used by Minecraft 1.21.1. The ordinary
     * Screen helper path is covered, but inventory quick-move also polls
     * InputConstants.isKeyDown(...) directly against Minecraft's hidden
     * lifecycle window. A green callback counter is not sufficient.
     */
    private void verifyDeferredModifierBridgeIntegration() {
        deferredModifierBridgeIntegrationProbeCount++;

        boolean helperShiftDown =
                false;
        boolean helperControlDown =
                true;
        boolean helperAltDown =
                true;
        boolean directLeftShiftDown =
                false;
        boolean directRightShiftDown =
                true;
        boolean directControlDown =
                true;

        VulkanGate11ModifierStateBridge
                .beginScreenDispatch(
                        GLFW_MOD_SHIFT
                );

        try {
            helperShiftDown =
                    Screen.hasShiftDown();
            helperControlDown =
                    Screen.hasControlDown();
            helperAltDown =
                    Screen.hasAltDown();

            directLeftShiftDown =
                    InputConstants.isKeyDown(
                            lifecycleOwnerHandle,
                            GLFW_KEY_LEFT_SHIFT
                    );

            directRightShiftDown =
                    InputConstants.isKeyDown(
                            lifecycleOwnerHandle,
                            GLFW_KEY_RIGHT_SHIFT
                    );

            directControlDown =
                    InputConstants.isKeyDown(
                            lifecycleOwnerHandle,
                            GLFW_KEY_LEFT_CONTROL
                    );
        } finally {
            VulkanGate11ModifierStateBridge
                    .endScreenDispatch();
        }

        deferredModifierBridgeIntegrationProbePassed =
                helperShiftDown
                        && !helperControlDown
                        && !helperAltDown
                        && directLeftShiftDown
                        && directRightShiftDown
                        && !directControlDown
                        && VulkanGate11ModifierStateBridge
                        .healthy();

        if (!deferredModifierBridgeIntegrationProbePassed
                && lastFailure.isEmpty()) {
            lastFailure =
                    "DEFERRED_SCREEN_MODIFIER_BRIDGE_DIRECT_POLL_PROBE_FAILED";
        }
    }

    private boolean cursorStateParityVerified() {
        return cursorModeSyncAttemptCount > 0L
                && cursorModeSyncSuccessCount == cursorModeSyncAttemptCount
                && cursorModeMismatchCount == 0L
                && menuCursorRelayEventCount > 0L
                && cursorPositionSyncAttemptCount > 0L
                && cursorPositionSyncSuccessCount == cursorPositionSyncAttemptCount
                && nativeOwnerCursorWarpCount == 0L;
    }

    private void recordInputRelayFailure(
            String stage,
            Throwable throwable
    ) {
        if (lastFailure.isEmpty()) {
            lastFailure =
                    "INPUT_RELAY_"
                            + stage
                            + "_"
                            + throwable.getClass().getName()
                            + ": "
                            + String.valueOf(
                            throwable.getMessage()
                    );
        }
    }

    private boolean keyboardRelayVerified() {
        return lifecycleOwnerFocusCallbackCaptured
                && lifecycleOwnerKeyCallbackCaptured
                && lifecycleOwnerCharModsCallbackCaptured
                && keyRelayEventCount > 0L
                && escapeKeyPressRelayCount > 0L
                && logicalFocusRelayEventCount > 0L
                && logicalFocusRelayFailureCount == 0L
                && keyboardRelayDropCount == 0L;
    }

    synchronized boolean inputRelayVerified() {
        return lifecycleOwnerCursorPosCallbackCaptured
                && lifecycleOwnerMouseButtonCallbackCaptured
                && cursorRelayEventCount > 0L
                && mouseButtonRelayEventCount > 0L
                && mouseButtonCoordinateSyncAttemptCount
                == mouseButtonCoordinateSyncSuccessCount
                && mouseButtonCoordinateSyncSuccessCount
                + gameplayMouseButtonAbsoluteCursorSyncSuppressedCount
                == mouseButtonRelayEventCount
                && gameplayMouseButtonDeltaReseedCount == 0L
                && gameplayMouseButtonCursorStateUntouchedCount
                == gameplayMouseButtonAbsoluteCursorSyncSuppressedCount
                && focusReturnAttemptCount == 0L
                && focusReturnSuccessCount == 0L
                && presentationGameplayFocusReturnCount == 0L
                && directScreenMousePressAttemptCount > 0L
                && directScreenMousePressAcceptedCount > 0L
                && directScreenMouseDispatchFailureCount == 0L
                && deferredModifierBridgeIntegrationProbePassed
                && VulkanGate11ModifierStateBridge.healthy()
                && keyboardRelayVerified()
                && inputRelayDropCount == 0L
                && cursorStateParityVerified()
                && lastFailure.isEmpty();
    }

    synchronized boolean closeForwardVerified() {
        return presentationCloseCallbackCount > 0
                && closeForwardAttemptCount > 0
                && closeForwardSuccessCount == closeForwardAttemptCount
                && candidateHiddenInsideCloseCallback
                && lifecycleOwnerShouldCloseAfterForward
                && closeForwardImmediateNoRenderCallback
                && lastFailure.isEmpty();
    }

    synchronized void unbindPresentationHandle() {
        if (!presentationHandleBound) {
            enrich();
            return;
        }

        try {
            if (presentationHandle != NULL) {
                glfwSetWindowCloseCallback(
                        presentationHandle,
                        null
                );

                glfwSetWindowFocusCallback(
                        presentationHandle,
                        null
                );

                glfwSetCursorPosCallback(
                        presentationHandle,
                        null
                );

                glfwSetMouseButtonCallback(
                        presentationHandle,
                        null
                );

                glfwSetScrollCallback(
                        presentationHandle,
                        null
                );

                glfwSetKeyCallback(
                        presentationHandle,
                        null
                );

                glfwSetCharModsCallback(
                        presentationHandle,
                        null
                );
            }
        } catch (Throwable throwable) {
            if (lastFailure.isEmpty()) {
                lastFailure =
                        throwable.getClass().getName()
                                + ": "
                                + String.valueOf(
                                throwable.getMessage()
                        );
            }
        }

        if (closeCallback != null) {
            closeCallback.free();
            closeCallback =
                    null;
        }

        if (focusCallback != null) {
            focusCallback.free();
            focusCallback =
                    null;
        }

        if (cursorPosCallback != null) {
            cursorPosCallback.free();
            cursorPosCallback =
                    null;
        }

        if (mouseButtonCallback != null) {
            mouseButtonCallback.free();
            mouseButtonCallback =
                    null;
        }

        if (scrollCallback != null) {
            scrollCallback.free();
            scrollCallback =
                    null;
        }

        if (keyCallback != null) {
            keyCallback.free();
            keyCallback =
                    null;
        }

        if (charModsCallback != null) {
            charModsCallback.free();
            charModsCallback =
                    null;
        }

        /*
         * Minecraft owns these callback objects. Drop our references only.
         */
        lifecycleOwnerCursorPosCallback =
                null;
        lifecycleOwnerMouseButtonCallback =
                null;
        lifecycleOwnerScrollCallback =
                null;
        lifecycleOwnerFocusCallback =
                null;
        lifecycleOwnerKeyCallback =
                null;
        lifecycleOwnerCharModsCallback =
                null;

        closeCallbackInstalled =
                false;

        focusCallbackInstalled =
                false;

        cursorPosCallbackInstalled =
                false;

        mouseButtonCallbackInstalled =
                false;

        scrollCallbackInstalled =
                false;
        keyCallbackInstalled =
                false;
        charModsCallbackInstalled =
                false;

        presentationHandleBound =
                false;

        /*
         * Patch 141: every real deferred Screen dispatch owns its modifier
         * scope through a local try/finally pair. Unbinding the presentation
         * window is not a Screen dispatch and must not synthesize one extra
         * endScreenDispatch() call. The old teardown call was the sole source
         * of the otherwise-functional 140c scopeLeak/unbalanced telemetry.
         */
        presentationHandle =
                NULL;

        deferredScreenMouseEvents.clear();
        clearDeferredScreenDragState();

        synchronized (VulkanGate11WindowLifecycleRouter.class) {
            if (activeRouter == this) {
                activeRouter = null;
            }
        }

        enrich();
    }

    synchronized void enrich() {
        if (report == null) {
            return;
        }

        report.addProperty(
                "gate11WindowLifecycleRouterInstalled",
                true
        );
        report.addProperty(
                "gate11WindowLifecycleRouterMode",
                "VULKAN_SINGLE_VISIBLE_PRESENTATION_INPUT_AUTHORITY_WITH_OPENGL_OFFSCREEN_FAILOPEN_STAGE6"
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerHandleNonZero",
                lifecycleOwnerHandle != NULL
        );
        report.addProperty(
                "gate11WindowPresentationHandleBound",
                presentationHandleBound
        );
        report.addProperty(
                "gate11WindowCloseCallbackInstalled",
                closeCallbackInstalled
        );
        report.addProperty(
                "gate11WindowFocusCallbackInstalled",
                focusCallbackInstalled
        );
        report.addProperty(
                "gate11WindowCursorPosCallbackInstalled",
                cursorPosCallbackInstalled
        );
        report.addProperty(
                "gate11WindowMouseButtonCallbackInstalled",
                mouseButtonCallbackInstalled
        );
        report.addProperty(
                "gate11WindowScrollCallbackInstalled",
                scrollCallbackInstalled
        );
        report.addProperty(
                "gate11WindowKeyCallbackInstalled",
                keyCallbackInstalled
        );
        report.addProperty(
                "gate11WindowCharModsCallbackInstalled",
                charModsCallbackInstalled
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerCursorPosCallbackCaptured",
                lifecycleOwnerCursorPosCallbackCaptured
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerMouseButtonCallbackCaptured",
                lifecycleOwnerMouseButtonCallbackCaptured
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerScrollCallbackCaptured",
                lifecycleOwnerScrollCallbackCaptured
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerFocusCallbackCaptured",
                lifecycleOwnerFocusCallbackCaptured
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerKeyCallbackCaptured",
                lifecycleOwnerKeyCallbackCaptured
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerCharModsCallbackCaptured",
                lifecycleOwnerCharModsCallbackCaptured
        );
        report.addProperty(
                "gate11WindowPointerRelayCursorEventCount",
                cursorRelayEventCount
        );
        report.addProperty(
                "gate11WindowPointerRelayMouseButtonEventCount",
                mouseButtonRelayEventCount
        );
        report.addProperty(
                "gate11WindowPointerRelayScrollEventCount",
                scrollRelayEventCount
        );
        report.addProperty(
                "gate11WindowPointerRelayDropCount",
                inputRelayDropCount
        );
        report.addProperty(
                "gate11WindowPointerRelayVerified",
                inputRelayVerified()
        );
        report.addProperty(
                "gate11WindowFocusReturnAttemptCount",
                focusReturnAttemptCount
        );
        report.addProperty(
                "gate11WindowFocusReturnSuccessCount",
                focusReturnSuccessCount
        );
        report.addProperty(
                "gate11WindowCursorModeSyncAttemptCount",
                cursorModeSyncAttemptCount
        );
        report.addProperty(
                "gate11WindowCursorModeSyncSuccessCount",
                cursorModeSyncSuccessCount
        );
        report.addProperty(
                "gate11WindowCursorModeMismatchCount",
                cursorModeMismatchCount
        );
        report.addProperty(
                "gate11WindowLastLifecycleOwnerCursorMode",
                lastLifecycleOwnerCursorMode
        );
        report.addProperty(
                "gate11WindowLastPresentationCursorMode",
                lastPresentationCursorMode
        );
        report.addProperty(
                "gate11WindowCursorPositionSyncAttemptCount",
                cursorPositionSyncAttemptCount
        );
        report.addProperty(
                "gate11WindowCursorPositionSyncSuccessCount",
                cursorPositionSyncSuccessCount
        );
        report.addProperty(
                "gate11WindowMenuCursorRelayEventCount",
                menuCursorRelayEventCount
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaBridge",
                true
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaMode",
                "RELATIVE_PRESENTATION_DELTA_LOGICAL_SIZE_PARITY"
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaSeedCount",
                gameplayCursorDeltaSeedCount
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaForwardCount",
                gameplayCursorDeltaForwardCount
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaOutlierReseedCount",
                gameplayCursorDeltaOutlierReseedCount
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaScaleRefreshCount",
                gameplayCursorDeltaScaleRefreshCount
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaScaleX",
                gameplayCursorScaleX
        );
        report.addProperty(
                "gate11WindowGameplayCursorDeltaScaleY",
                gameplayCursorScaleY
        );
        report.addProperty(
                "gate11WindowGameplayCursorMutatesSensitivityOption",
                false
        );
        report.addProperty(
                "gate11WindowMouseButtonCoordinateSyncAttemptCount",
                mouseButtonCoordinateSyncAttemptCount
        );
        report.addProperty(
                "gate11WindowMouseButtonCoordinateSyncSuccessCount",
                mouseButtonCoordinateSyncSuccessCount
        );
        report.addProperty(
                "gate11WindowGameplayMouseButtonAbsoluteCursorSyncSuppressedCount",
                gameplayMouseButtonAbsoluteCursorSyncSuppressedCount
        );
        report.addProperty(
                "gate11WindowGameplayMouseButtonDeltaReseedCount",
                gameplayMouseButtonDeltaReseedCount
        );
        report.addProperty(
                "gate11WindowGameplayMouseButtonCursorStateUntouchedCount",
                gameplayMouseButtonCursorStateUntouchedCount
        );
        report.addProperty(
                "gate11WindowPostScreenGameplayDeltaResetCount",
                postScreenGameplayDeltaResetCount
        );
        report.addProperty(
                "gate11WindowMouseButtonCursorPolicy",
                "ABSOLUTE_ONLY_FOR_SCREEN_GAMEPLAY_BUTTON_CURSOR_STATE_UNTOUCHED"
        );
        report.addProperty(
                "gate11WindowNativeOwnerCursorWarpCount",
                nativeOwnerCursorWarpCount
        );
        report.addProperty(
                "gate11WindowDirectScreenMousePressAttemptCount",
                directScreenMousePressAttemptCount
        );
        report.addProperty(
                "gate11WindowDirectScreenMousePressAcceptedCount",
                directScreenMousePressAcceptedCount
        );
        report.addProperty(
                "gate11WindowDirectScreenMouseReleaseCount",
                directScreenMouseReleaseCount
        );
        report.addProperty(
                "gate11WindowDirectScreenMouseDispatchFailureCount",
                directScreenMouseDispatchFailureCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseEnqueueCount",
                deferredScreenMouseEnqueueCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseFlushCount",
                deferredScreenMouseFlushCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseStaleReleaseDiscardCount",
                deferredScreenMouseStaleReleaseDiscardCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseStalePressDiscardCount",
                deferredScreenMouseStalePressDiscardCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseCausalStaleDiscardCount",
                deferredScreenMouseCausalStaleDiscardCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseLifecycleTransitionCount",
                deferredScreenMouseLifecycleTransitionCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseLifecycleEpoch",
                deferredScreenMouseLifecycleEpoch
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseStalePolicy",
                "DISCARD_ONLY_RELEASE_OR_EVENT_FROM_DISPATCHER_OBSERVED_OLDER_SCREEN_EPOCH"
        );
        report.addProperty(
                "gate11WindowDeferredScreenMousePendingCount",
                deferredScreenMouseEvents.size()
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseOverflowDropCount",
                deferredScreenMouseOverflowDropCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseHighWaterMark",
                deferredScreenMouseHighWaterMark
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseMaxDispatchPerFrame",
                1
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragInstalled",
                true
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragDispatchBoundary",
                "RENDER_FRAME_POST_COALESCED_ONE_LATEST_SAMPLE_PER_FRAME"
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragActive",
                deferredScreenDragActive
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragButton",
                deferredScreenDragButton
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragPending",
                deferredScreenDragPending
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragArmCount",
                deferredScreenDragArmCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragCursorSampleCount",
                deferredScreenDragCursorSampleCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragCoalescedCount",
                deferredScreenDragCoalescedCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragFlushCount",
                deferredScreenDragFlushCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragAcceptedCount",
                deferredScreenDragAcceptedCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragStaleDiscardCount",
                deferredScreenDragStaleDiscardCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenDragDispatchFailureCount",
                deferredScreenDragDispatchFailureCount
        );
        report.addProperty(
                "gate11WindowDeferredScreenMouseDispatchBoundary",
                "RENDER_FRAME_POST_AFTER_FINAL_PIXEL_CAPTURE"
        );
        report.addProperty(
                "gate11WindowDirectScreenLastClass",
                lastDirectScreenClass
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeIntegrationProbeCount",
                deferredModifierBridgeIntegrationProbeCount
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeIntegrationProbePassed",
                deferredModifierBridgeIntegrationProbePassed
        );
        report.addProperty(
                "gate11WindowDeferredModifierBridgeSyntheticTeardownEnd",
                false
        );

        VulkanGate11ModifierStateBridge
                .enrich(
                        report
                );

        report.addProperty(
                "gate11WindowKeyboardRelayInstalled",
                keyCallbackInstalled
                        || keyRelayEventCount > 0L
        );
        report.addProperty(
                "gate11WindowKeyboardRelayVerified",
                keyboardRelayVerified()
        );
        report.addProperty(
                "gate11WindowKeyboardRelayKeyEventCount",
                keyRelayEventCount
        );
        report.addProperty(
                "gate11WindowKeyboardRelayCharModsEventCount",
                charModsRelayEventCount
        );
        report.addProperty(
                "gate11WindowKeyboardRelayDropCount",
                keyboardRelayDropCount
        );
        report.addProperty(
                "gate11WindowKeyboardPostCloseTailIgnoredCount",
                keyboardPostCloseTailIgnoredCount
        );
        report.addProperty(
                "gate11WindowCharPostCloseTailIgnoredCount",
                charPostCloseTailIgnoredCount
        );
        report.addProperty(
                "gate11WindowKeyboardPostCloseTailPolicy",
                "IGNORE_ONLY_AFTER_CLOSE_FORWARD_SUCCESS_AND_CANDIDATE_HIDDEN"
        );
        report.addProperty(
                "gate11WindowKeyboardRelayEscapePressCount",
                escapeKeyPressRelayCount
        );
        report.addProperty(
                "gate11WindowF11OwnershipPolicy",
                "PRESENTATION_ROUTER_CONSUMES_F11_HIDDEN_OPENGL_OWNER_NEVER_NATIVE_FULLSCREEN"
        );
        report.addProperty(
                "gate11WindowF11EventConsumedCount",
                presentationF11EventConsumedCount
        );
        report.addProperty(
                "gate11WindowF11PressConsumedCount",
                presentationF11PressConsumedCount
        );
        report.addProperty(
                "gate11WindowF11ToggleAttemptCount",
                presentationF11ToggleAttemptCount
        );
        report.addProperty(
                "gate11WindowF11ToggleSuccessCount",
                presentationF11ToggleSuccessCount
        );
        report.addProperty(
                "gate11WindowF11ToggleFailureCount",
                presentationF11ToggleFailureCount
        );
        report.addProperty(
                "gate11WindowPresentationFullscreenEnterCount",
                presentationFullscreenEnterCount
        );
        report.addProperty(
                "gate11WindowPresentationFullscreenExitCount",
                presentationFullscreenExitCount
        );
        report.addProperty(
                "gate11WindowPresentationFullscreenForcedWindowedForHandoffCount",
                presentationFullscreenForcedWindowedForHandoffCount
        );
        report.addProperty(
                "gate11WindowPresentationFullscreenCursorDeltaResetCount",
                presentationFullscreenCursorDeltaResetCount
        );
        report.addProperty(
                "gate11WindowPresentationFullscreenOwnedByRouter",
                presentationFullscreenOwnedByRouter
        );
        report.addProperty(
                "gate11WindowPresentationWindowedBoundsCaptured",
                presentationWindowedBoundsCaptured
        );
        report.addProperty(
                "gate11WindowPresentationWindowedX",
                presentationWindowedX
        );
        report.addProperty(
                "gate11WindowPresentationWindowedY",
                presentationWindowedY
        );
        report.addProperty(
                "gate11WindowPresentationWindowedWidth",
                presentationWindowedWidth
        );
        report.addProperty(
                "gate11WindowPresentationWindowedHeight",
                presentationWindowedHeight
        );
        report.addProperty(
                "gate11WindowPresentationFullscreenState",
                presentationFullscreenState
        );
        report.addProperty(
                "gate11WindowLogicalFocusRelayEventCount",
                logicalFocusRelayEventCount
        );
        report.addProperty(
                "gate11WindowLogicalFocusRelayFailureCount",
                logicalFocusRelayFailureCount
        );
        report.addProperty(
                "gate11WindowLogicalFocusRelayTargetsMinecraftLifecycleCallback",
                true
        );
        report.addProperty(
                "gate11WindowKeyboardRelayLastKey",
                lastRelayedKey
        );
        report.addProperty(
                "gate11WindowKeyboardRelayLastAction",
                lastRelayedKeyAction
        );
        report.addProperty(
                "gate11WindowPresentationMenuFocusLeaseCount",
                presentationMenuFocusLeaseCount
        );
        report.addProperty(
                "gate11WindowPresentationMenuFocusLeaseActive",
                presentationMenuFocusLeaseActive
        );
        report.addProperty(
                "gate11WindowPresentationGameplayFocusReturnCount",
                presentationGameplayFocusReturnCount
        );
        report.addProperty(
                "gate11WindowPresentationNativeFocusRetentionCount",
                presentationNativeFocusRetentionCount
        );
        report.addProperty(
                "gate11WindowPresentationFocusPolicy",
                "VULKAN_PRESENTATION_OWNS_NATIVE_FOCUS_FOR_ALL_VISIBLE_GAMEPLAY_AND_SCREENS"
        );
        report.addProperty(
                "gate11WindowCursorStateParityVerified",
                cursorStateParityVerified()
        );
        report.addProperty(
                "gate11WindowPresentationFocusGainCount",
                presentationFocusGainCount
        );
        report.addProperty(
                "gate11WindowPresentationFocusLossCount",
                presentationFocusLossCount
        );
        report.addProperty(
                "gate11WindowPresentationCloseCallbackCount",
                presentationCloseCallbackCount
        );
        report.addProperty(
                "gate11WindowCloseForwardAttemptCount",
                closeForwardAttemptCount
        );
        report.addProperty(
                "gate11WindowCloseForwardSuccessCount",
                closeForwardSuccessCount
        );
        report.addProperty(
                "gate11WindowCandidateHiddenInsideCloseCallback",
                candidateHiddenInsideCloseCallback
        );
        report.addProperty(
                "gate11WindowLifecycleOwnerShouldCloseAfterForward",
                lifecycleOwnerShouldCloseAfterForward
        );
        report.addProperty(
                "gate11WindowCloseForwardImmediateNoRenderCallback",
                closeForwardImmediateNoRenderCallback
        );
        report.addProperty(
                "gate11WindowCloseForwardVerified",
                closeForwardVerified()
        );
        report.addProperty(
                "gate11WindowRouterHidesMinecraftMainWindow",
                false
        );
        report.addProperty(
                "gate11WindowRouterMutatesMinecraftStoredHandle",
                false
        );
        report.addProperty(
                "gate11WindowRouterSuppressesGlfwSwapBuffers",
                false
        );
        report.addProperty(
                "gate11WindowRouterGameplayGpuWait",
                false
        );
        report.addProperty(
                "gate11WindowPointerRelayChangesWindowOwnership",
                false
        );
        report.addProperty(
                "gate11WindowPointerRelayInjectsKeyboardEvents",
                false
        );
        report.addProperty(
                "gate11WindowKeyboardRelayChangesWindowOwnership",
                false
        );
        report.addProperty(
                "gate11WindowKeyboardRelayTargetsMinecraftLifecycleCallbacks",
                true
        );
        report.addProperty(
                "gate11WindowLifecycleRouterClosed",
                closed
        );
        report.addProperty(
                "gate11WindowLifecycleRouterLastFailure",
                lastFailure
        );
    }

    @Override
    public synchronized void close() {
        if (closed) {
            enrich();
            return;
        }

        unbindPresentationHandle();

        closed =
                true;

        enrich();
    }
}
