package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Strategy-B rehearsal window created at the verified NeoForge -> Minecraft
 * handoff seam.
 *
 * <p>NeoForge's real EarlyDisplay OpenGL window remains untouched and is still
 * returned to Minecraft. Potato creates a second hidden GLFW_NO_API window from
 * the real handoff geometry, then the normal Vulkan probe adopts that exact
 * window for surface/swapchain validation.</p>
 */
public final class VulkanHandoffCandidate {
    private static final Object LOCK = new Object();

    private static boolean prepared;
    private static boolean creationAttempted;
    private static boolean created;
    private static boolean claimed;
    private static boolean probeBorrowed;
    private static boolean probeReleased;
    private static boolean probePromotedToGameplay;
    private static boolean gameplayClaimed;
    private static boolean destroyed;

    private static long earlyWindow;
    private static long candidateWindow;

    private static long contextBeforeCreate;
    private static long contextAfterCreate;

    private static int earlyWidth;
    private static int earlyHeight;
    private static int earlyFramebufferWidth;
    private static int earlyFramebufferHeight;
    private static int earlyX;
    private static int earlyY;

    private static int candidateWidth;
    private static int candidateHeight;
    private static int candidateFramebufferWidth;
    private static int candidateFramebufferHeight;
    private static int candidateClientApi;

    private static boolean earlyFullscreen;
    private static boolean candidateLogicalSizeMatches;
    private static boolean candidateContextUnaffected;
    private static String creationError = "";

    private VulkanHandoffCandidate() {
    }

    public static void prepare(long handedOffEarlyWindow) {
        synchronized (LOCK) {
            if (prepared || creationAttempted) {
                return;
            }

            creationAttempted = true;
            earlyWindow = handedOffEarlyWindow;

            if (handedOffEarlyWindow == NULL) {
                creationError = "NeoForge handed Minecraft a null GLFW window.";
                return;
            }

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);
                IntBuffer x = stack.mallocInt(1);
                IntBuffer y = stack.mallocInt(1);

                glfwGetWindowSize(
                        handedOffEarlyWindow,
                        width,
                        height
                );

                earlyWidth = width.get(0);
                earlyHeight = height.get(0);

                glfwGetFramebufferSize(
                        handedOffEarlyWindow,
                        width,
                        height
                );

                earlyFramebufferWidth = width.get(0);
                earlyFramebufferHeight = height.get(0);

                glfwGetWindowPos(
                        handedOffEarlyWindow,
                        x,
                        y
                );

                earlyX = x.get(0);
                earlyY = y.get(0);

                earlyFullscreen =
                        glfwGetWindowMonitor(handedOffEarlyWindow)
                                != NULL;
            }

            contextBeforeCreate = glfwGetCurrentContext();

            try {
                glfwDefaultWindowHints();
                glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
                glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
                glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
                glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);

                candidateWindow = glfwCreateWindow(
                        Math.max(1, earlyWidth),
                        Math.max(1, earlyHeight),
                        "Minecraft",
                        NULL,
                        NULL
                );
            } finally {
                /*
                 * GLFW window hints are process-global. Never leave NO_API
                 * policy behind for unrelated later window creation.
                 */
                glfwDefaultWindowHints();
            }

            contextAfterCreate = glfwGetCurrentContext();
            candidateContextUnaffected =
                    contextBeforeCreate == contextAfterCreate;

            if (candidateWindow == NULL) {
                creationError =
                        "glfwCreateWindow returned NULL for the NO_API handoff candidate.";
                return;
            }

            created = true;

            /*
             * Keep it hidden, but mirror the real window position so a future
             * actual cutover already has a tested geometry-transfer path.
             */
            glfwSetWindowPos(
                    candidateWindow,
                    earlyX,
                    earlyY
            );

            candidateClientApi =
                    glfwGetWindowAttrib(
                            candidateWindow,
                            GLFW_CLIENT_API
                    );

            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer width = stack.mallocInt(1);
                IntBuffer height = stack.mallocInt(1);

                glfwGetWindowSize(
                        candidateWindow,
                        width,
                        height
                );

                candidateWidth = width.get(0);
                candidateHeight = height.get(0);

                glfwGetFramebufferSize(
                        candidateWindow,
                        width,
                        height
                );

                candidateFramebufferWidth = width.get(0);
                candidateFramebufferHeight = height.get(0);
            }

            candidateLogicalSizeMatches =
                    candidateWidth == earlyWidth
                            && candidateHeight == earlyHeight;

            prepared =
                    candidateClientApi == GLFW_NO_API
                            && candidateContextUnaffected;

            PotatoRuntime.LOGGER.info(
                    "[Potato/Vulkan] Prepared hidden NO_API handoff candidate {}x{} from NeoForge window {}x{}.",
                    candidateWidth,
                    candidateHeight,
                    earlyWidth,
                    earlyHeight
            );
        }
    }

    static long claimWindow() {
        synchronized (LOCK) {
            if (!prepared
                    || !created
                    || probeBorrowed
                    || gameplayClaimed
                    || candidateWindow == NULL) {
                return NULL;
            }

            claimed = true;
            probeBorrowed = true;
            return candidateWindow;
        }
    }

    static void releaseProbeBorrow(long handle) {
        synchronized (LOCK) {
            if (handle != NULL
                    && handle == candidateWindow
                    && probeBorrowed) {
                probeBorrowed = false;
                probeReleased = true;
            }
        }
    }

    /**
     * Atomically converts the probe's temporary window borrow into gameplay
     * ownership when the validated presentation/runtime is retained.
     *
     * <p>This is intentionally different from releaseProbeBorrow(): there must
     * never be a frame where Gate 11 sees the persistent NO_API window as
     * unowned while the retained VkSurfaceKHR/swapchain still refer to it.</p>
     */
    static boolean promoteProbeBorrowToGameplay(long handle) {
        synchronized (LOCK) {
            if (!prepared
                    || !created
                    || destroyed
                    || candidateWindow == NULL
                    || handle == NULL
                    || handle != candidateWindow) {
                return false;
            }

            if (gameplayClaimed) {
                return true;
            }

            if (!probeBorrowed) {
                return false;
            }

            probeBorrowed = false;
            probeReleased = true;
            probePromotedToGameplay = true;
            gameplayClaimed = true;
            return true;
        }
    }

    /**
     * Idempotent gameplay claim.
     *
     * <p>After the probe borrow has been promoted at runtime adoption, Gate 11
     * obtains the same native window again without creating a second gameplay
     * GLFW_NO_API window.</p>
     */
    static long claimWindowForGameplay() {
        synchronized (LOCK) {
            if (!prepared
                    || !created
                    || probeBorrowed
                    || destroyed
                    || candidateWindow == NULL) {
                return NULL;
            }

            if (gameplayClaimed) {
                return candidateWindow;
            }

            gameplayClaimed = true;
            return candidateWindow;
        }
    }

    static boolean isPersistentCandidate(long handle) {
        synchronized (LOCK) {
            return handle != NULL
                    && handle == candidateWindow
                    && created
                    && !destroyed;
        }
    }

    static boolean isKnownCandidate(long handle) {
        synchronized (LOCK) {
            return handle != NULL
                    && handle == candidateWindow
                    && created;
        }
    }

    static void destroyAfterPresentationShutdown() {
        synchronized (LOCK) {
            if (!created
                    || destroyed
                    || candidateWindow == NULL) {
                return;
            }

            long closingWindow = candidateWindow;
            glfwDestroyWindow(closingWindow);
            destroyed = true;
        }
    }

    static void markDestroyed(long handle) {
        synchronized (LOCK) {
            if (handle != NULL
                    && handle == candidateWindow) {
                destroyed = true;
                candidateWindow = NULL;
            }
        }
    }

    public static boolean readyForVulkanProbe() {
        synchronized (LOCK) {
            return prepared
                    && created
                    && candidateWindow != NULL
                    && candidateClientApi == GLFW_NO_API;
        }
    }

    public static void enrich(JsonObject report) {
        synchronized (LOCK) {
            report.addProperty(
                    "earlyDisplayIsolationStrategy",
                    "TEMPORARY_OPENGL_WINDOW_THEN_NO_API_REPLACEMENT"
            );

            report.addProperty(
                    "handoffCandidatePreparationAttempted",
                    creationAttempted
            );
            report.addProperty(
                    "handoffCandidatePrepared",
                    prepared
            );
            report.addProperty(
                    "handoffCandidateCreated",
                    created
            );
            report.addProperty(
                    "handoffCandidateClaimed",
                    claimed
            );
            report.addProperty(
                    "handoffCandidateProbeBorrowed",
                    probeBorrowed
            );
            report.addProperty(
                    "handoffCandidateProbeReleased",
                    probeReleased
            );
            report.addProperty(
                    "handoffCandidateProbePromotedToGameplay",
                    probePromotedToGameplay
            );
            report.addProperty(
                    "handoffCandidateGameplayClaimed",
                    gameplayClaimed
            );
            report.addProperty(
                    "handoffCandidatePersistentUntilGameplayShutdown",
                    created && !destroyed
            );
            report.addProperty(
                    "createsSecondGameplayNoApiWindow",
                    false
            );
            report.addProperty(
                    "handoffCandidateDeveloperTitleExposed",
                    false
            );
            report.addProperty(
                    "handoffCandidateDestroyed",
                    destroyed
            );

            report.addProperty(
                    "handoffEarlyWindowHandleNonZero",
                    earlyWindow != NULL
            );
            report.addProperty(
                    "handoffEarlyWindowWidth",
                    earlyWidth
            );
            report.addProperty(
                    "handoffEarlyWindowHeight",
                    earlyHeight
            );
            report.addProperty(
                    "handoffEarlyFramebufferWidth",
                    earlyFramebufferWidth
            );
            report.addProperty(
                    "handoffEarlyFramebufferHeight",
                    earlyFramebufferHeight
            );
            report.addProperty(
                    "handoffEarlyWindowX",
                    earlyX
            );
            report.addProperty(
                    "handoffEarlyWindowY",
                    earlyY
            );
            report.addProperty(
                    "handoffEarlyWindowFullscreen",
                    earlyFullscreen
            );

            report.addProperty(
                    "handoffCandidateWidth",
                    candidateWidth
            );
            report.addProperty(
                    "handoffCandidateHeight",
                    candidateHeight
            );
            report.addProperty(
                    "handoffCandidateFramebufferWidth",
                    candidateFramebufferWidth
            );
            report.addProperty(
                    "handoffCandidateFramebufferHeight",
                    candidateFramebufferHeight
            );
            report.addProperty(
                    "handoffCandidateClientApiRaw",
                    candidateClientApi
            );
            report.addProperty(
                    "handoffCandidateUsesNoApi",
                    candidateClientApi == GLFW_NO_API
            );
            report.addProperty(
                    "handoffCandidateLogicalSizeMatchesEarlyWindow",
                    candidateLogicalSizeMatches
            );
            report.addProperty(
                    "handoffCandidateCurrentContextUnaffected",
                    candidateContextUnaffected
            );

            report.addProperty(
                    "handoffContextBeforeCandidateNonZero",
                    contextBeforeCreate != NULL
            );
            report.addProperty(
                    "handoffContextAfterCandidateNonZero",
                    contextAfterCreate != NULL
            );
            report.addProperty(
                    "handoffContextPreservedExactly",
                    contextBeforeCreate == contextAfterCreate
            );

            report.addProperty(
                    "handoffCandidateFullscreenTransferDeferred",
                    earlyFullscreen
            );

            if (!creationError.isBlank()) {
                report.addProperty(
                        "handoffCandidateCreationError",
                        creationError
                );
            }

            report.addProperty(
                    "earlyDisplayWindowRetired",
                    false
            );
            report.addProperty(
                    "earlyDisplayWindowStillReturnedToMinecraft",
                    true
            );
            report.addProperty(
                    "minecraftMainWindowActuallyReplaced",
                    false
            );
        }
    }
}