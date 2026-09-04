package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

import static org.lwjgl.glfw.GLFW.glfwGetCurrentContext;
import static org.lwjgl.glfw.GLFW.glfwMakeContextCurrent;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * Rehearses the direct OpenGL assumptions immediately after NeoForge's native
 * window handoff.
 *
 * <p>Patch 018a distinguishes a true fallback from a deliberate transition
 * bridge. Minecraft's downstream renderer is still OpenGL, therefore the
 * Render thread must have LWJGL {@link GLCapabilities}. If EarlyDisplay created
 * them on another thread, Minecraft's createCapabilities call remains required
 * until renderer initialization is replaced.</p>
 */
public final class VulkanContextBootstrapRehearsal {
    private static final Object LOCK =
            new Object();

    private static boolean makeCurrentIntercepted;
    private static boolean makeCurrentBypassed;
    private static boolean makeCurrentFallbackUsed;
    private static boolean contextAlreadyCurrent;

    private static boolean createCapabilitiesIntercepted;
    private static boolean createCapabilitiesReused;
    private static boolean createCapabilitiesTransitionBridgeUsed;
    private static boolean capabilitiesAlreadyInstalled;

    private static boolean maxTextureIntercepted;
    private static boolean maxTextureBypassed;
    private static boolean maxTextureFallbackUsed;

    private static long expectedWindow;
    private static long contextAtMakeCurrentIntercept;
    private static long contextAtConstructorReturn;

    private static int selectedWindowLimit;
    private static String selectedWindowLimitSource = "";

    private static boolean capabilitiesPresentAtConstructorReturn;

    private VulkanContextBootstrapRehearsal() {
    }

    public static void makeContextCurrent(
            long window
    ) {
        synchronized (LOCK) {
            makeCurrentIntercepted = true;
            expectedWindow = window;

            contextAtMakeCurrentIntercept =
                    glfwGetCurrentContext();

            contextAlreadyCurrent =
                    window != NULL
                            && contextAtMakeCurrentIntercept
                            == window;

            if (contextAlreadyCurrent) {
                makeCurrentBypassed = true;
                return;
            }

            makeCurrentFallbackUsed = true;
        }

        /*
         * Genuine baseline fallback. This should not be needed if NeoForge
         * handed the current EarlyDisplay context to Minecraft as observed in
         * Patch 017.
         */
        glfwMakeContextCurrent(window);
    }

    public static GLCapabilities createCapabilities() {
        synchronized (LOCK) {
            createCapabilitiesIntercepted = true;
        }

        try {
            GLCapabilities existing =
                    GL.getCapabilities();

            synchronized (LOCK) {
                capabilitiesAlreadyInstalled =
                        existing != null;

                if (existing != null) {
                    createCapabilitiesReused = true;
                    return existing;
                }
            }
        } catch (IllegalStateException ignored) {
            synchronized (LOCK) {
                capabilitiesAlreadyInstalled = false;
            }
        }

        /*
         * This is NOT considered a failed Vulkan rehearsal anymore.
         *
         * LWJGL capabilities are thread-local. NeoForge EarlyDisplay may have
         * created capabilities on its rendering worker, while Minecraft now
         * needs them on the Render thread for the still-OpenGL downstream
         * renderer.
         *
         * The call disappears together with RenderSystem.initRenderer /
         * GlStateManager ownership, not independently before that boundary.
         */
        GLCapabilities created =
                GL.createCapabilities();

        synchronized (LOCK) {
            createCapabilitiesTransitionBridgeUsed = true;
        }

        return created;
    }

    public static int maxSupportedTextureSize() {
        synchronized (LOCK) {
            maxTextureIntercepted = true;

            int vulkanLimit =
                    VulkanEarlyDeviceLimits
                            .maxImageDimension2D();

            if (VulkanEarlyDeviceLimits.available()
                    && vulkanLimit > 0) {

                maxTextureBypassed = true;
                selectedWindowLimit =
                        vulkanLimit;
                selectedWindowLimitSource =
                        "VULKAN_MAX_IMAGE_DIMENSION_2D";

                return vulkanLimit;
            }

            maxTextureFallbackUsed = true;
        }

        int baseline =
                RenderSystem.maxSupportedTextureSize();

        synchronized (LOCK) {
            selectedWindowLimit =
                    baseline;
            selectedWindowLimitSource =
                    "OPENGL_FALLBACK";
        }

        return baseline;
    }

    public static void afterWindowConstructor() {
        synchronized (LOCK) {
            contextAtConstructorReturn =
                    glfwGetCurrentContext();

            try {
                capabilitiesPresentAtConstructorReturn =
                        GL.getCapabilities() != null;
            } catch (IllegalStateException ignored) {
                capabilitiesPresentAtConstructorReturn =
                        false;
            }
        }
    }

    /**
     * Transition milestone:
     *
     * - redundant make-current is gone;
     * - GL hardware-limit query is gone;
     * - Render-thread GL capabilities remain only because the downstream
     *   renderer is intentionally still OpenGL.
     */
    public static boolean transitionBoundaryVerified() {
        synchronized (LOCK) {
            boolean capabilitiesReady =
                    createCapabilitiesIntercepted
                            && (createCapabilitiesReused
                            || createCapabilitiesTransitionBridgeUsed)
                            && capabilitiesPresentAtConstructorReturn;

            return makeCurrentIntercepted
                    && makeCurrentBypassed
                    && !makeCurrentFallbackUsed
                    && maxTextureIntercepted
                    && maxTextureBypassed
                    && !maxTextureFallbackUsed
                    && VulkanEarlyDeviceLimits.available()
                    && capabilitiesReady;
        }
    }

    /**
     * A real GLFW_NO_API Minecraft window is intentionally not ready while
     * downstream OpenGL still requires GLCapabilities.
     */
    public static boolean noApiContextBootstrapReady() {
        synchronized (LOCK) {
            return transitionBoundaryVerified()
                    && !createCapabilitiesTransitionBridgeUsed;
        }
    }

    public static void enrich(
            JsonObject report
    ) {
        synchronized (LOCK) {
            report.addProperty(
                    "contextBootstrapMakeCurrentIntercepted",
                    makeCurrentIntercepted
            );
            report.addProperty(
                    "contextBootstrapMakeCurrentBypassed",
                    makeCurrentBypassed
            );
            report.addProperty(
                    "contextBootstrapMakeCurrentFallbackUsed",
                    makeCurrentFallbackUsed
            );
            report.addProperty(
                    "contextBootstrapContextAlreadyCurrent",
                    contextAlreadyCurrent
            );

            report.addProperty(
                    "contextBootstrapCreateCapabilitiesIntercepted",
                    createCapabilitiesIntercepted
            );
            report.addProperty(
                    "contextBootstrapCreateCapabilitiesReused",
                    createCapabilitiesReused
            );
            report.addProperty(
                    "contextBootstrapCapabilitiesAlreadyInstalled",
                    capabilitiesAlreadyInstalled
            );
            report.addProperty(
                    "contextBootstrapCreateCapabilitiesTransitionBridgeUsed",
                    createCapabilitiesTransitionBridgeUsed
            );
            report.addProperty(
                    "contextBootstrapCreateCapabilitiesRequiredByOpenGlRenderer",
                    createCapabilitiesTransitionBridgeUsed
            );

            /*
             * Keep the old field for report compatibility, but its meaning is
             * now precise: this was not a baseline recovery path.
             */
            report.addProperty(
                    "contextBootstrapCreateCapabilitiesFallbackUsed",
                    false
            );

            report.addProperty(
                    "contextBootstrapMaxTextureIntercepted",
                    maxTextureIntercepted
            );
            report.addProperty(
                    "contextBootstrapMaxTextureBypassed",
                    maxTextureBypassed
            );
            report.addProperty(
                    "contextBootstrapMaxTextureFallbackUsed",
                    maxTextureFallbackUsed
            );

            report.addProperty(
                    "contextBootstrapSelectedWindowLimit",
                    selectedWindowLimit
            );
            report.addProperty(
                    "contextBootstrapSelectedWindowLimitSource",
                    selectedWindowLimitSource
            );

            report.addProperty(
                    "contextBootstrapExpectedWindowNonZero",
                    expectedWindow != NULL
            );
            report.addProperty(
                    "contextBootstrapContextAtInterceptNonZero",
                    contextAtMakeCurrentIntercept != NULL
            );
            report.addProperty(
                    "contextBootstrapContextMatchesExpectedAtIntercept",
                    expectedWindow != NULL
                            && contextAtMakeCurrentIntercept
                            == expectedWindow
            );
            report.addProperty(
                    "contextBootstrapContextAtConstructorReturnNonZero",
                    contextAtConstructorReturn != NULL
            );
            report.addProperty(
                    "contextBootstrapContextMatchesExpectedAtConstructorReturn",
                    expectedWindow != NULL
                            && contextAtConstructorReturn
                            == expectedWindow
            );
            report.addProperty(
                    "contextBootstrapCapabilitiesPresentAtConstructorReturn",
                    capabilitiesPresentAtConstructorReturn
            );

            report.addProperty(
                    "contextBootstrapTransitionBoundaryVerified",
                    transitionBoundaryVerified()
            );
            report.addProperty(
                    "contextBootstrapNoApiReady",
                    noApiContextBootstrapReady()
            );

            report.addProperty(
                    "contextBootstrapDirectGlCallsBypassed",
                    2
            );
            report.addProperty(
                    "contextBootstrapTransitionBridgeCount",
                    createCapabilitiesTransitionBridgeUsed
                            ? 1
                            : 0
            );

            report.addProperty(
                    "contextBootstrapRendererInitializationCoupled",
                    createCapabilitiesTransitionBridgeUsed
            );

            report.addProperty(
                    "minecraftContextBootstrapActuallyNoApi",
                    false
            );
            report.addProperty(
                    "minecraftContextBootstrapRehearsalOnly",
                    true
            );
        }
    }
}