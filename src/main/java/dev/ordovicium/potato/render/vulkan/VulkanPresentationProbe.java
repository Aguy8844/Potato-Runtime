package dev.ordovicium.potato.render.vulkan;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.ordovicium.potato.PotatoRuntime;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkInstance;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.vkDestroySurfaceKHR;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;

/**
 * Owns Potato's Vulkan presentation window/surface for bootstrap validation.
 *
 * <p>Patch 017 no longer invents a generic 64x64 probe window. It adopts the
 * GLFW_NO_API candidate created at NeoForge's actual Minecraft handoff seam.</p>
 */
final class VulkanPresentationProbe implements AutoCloseable {
    private static final int RESIZE_TEST_WIDTH = 800;
    private static final int RESIZE_TEST_HEIGHT = 450;

    private final VkInstance instance;
    private final JsonObject report;
    private final VulkanProbeOptions options;
    private final boolean handoffCandidate;

    private long window;
    private long surface;

    private boolean transferredToRuntime;
    private boolean destroyed;

    private VulkanPresentationProbe(
            VkInstance instance,
            JsonObject report,
            VulkanProbeOptions options,
            long window,
            long surface,
            boolean handoffCandidate
    ) {
        this.instance = instance;
        this.report = report;
        this.options = options;
        this.window = window;
        this.surface = surface;
        this.handoffCandidate = handoffCandidate;
    }

    static PointerBuffer requiredInstanceExtensions(JsonObject report) {
        report.addProperty("stage", "QUERY_GLFW_VULKAN_EXTENSIONS");

        PointerBuffer extensions = glfwGetRequiredInstanceExtensions();

        if (extensions == null || !extensions.hasRemaining()) {
            throw new VulkanProbeException(
                    "QUERY_GLFW_VULKAN_EXTENSIONS",
                    "GLFW reported no Vulkan instance extensions for surface creation."
            );
        }

        JsonArray json = new JsonArray();

        for (int index = extensions.position(); index < extensions.limit(); index++) {
            json.add(MemoryUtil.memUTF8(extensions.get(index)));
        }

        report.addProperty(
                "glfwRequiredInstanceExtensionCount",
                extensions.remaining()
        );
        report.add(
                "glfwRequiredInstanceExtensions",
                json
        );

        return extensions;
    }

    static VulkanPresentationProbe create(
            VkInstance instance,
            MemoryStack stack,
            JsonObject report,
            VulkanProbeOptions options
    ) {
        report.addProperty(
                "stage",
                "ADOPT_HANDOFF_NO_API_WINDOW"
        );

        long window =
                VulkanHandoffCandidate.claimWindow();

        if (window == NULL) {
            VulkanHandoffCandidate.enrich(report);

            throw new VulkanProbeException(
                    "ADOPT_HANDOFF_NO_API_WINDOW",
                    "Patch 017 expected a prepared GLFW_NO_API handoff candidate."
            );
        }

        int clientApi =
                glfwGetWindowAttrib(
                        window,
                        GLFW_CLIENT_API
                );

        report.addProperty(
                "handoffCandidateAdoptedByVulkanProbe",
                true
        );
        report.addProperty(
                "handoffCandidateClientApiAtAdoption",
                clientApi
        );

        if (clientApi != GLFW_NO_API) {
            glfwDestroyWindow(window);
            VulkanHandoffCandidate.markDestroyed(window);

            throw new VulkanProbeException(
                    "ADOPT_HANDOFF_NO_API_WINDOW",
                    "Handoff candidate is not a GLFW_NO_API window."
            );
        }

        try (MemoryStack dimensions = MemoryStack.stackPush()) {
            IntBuffer width = dimensions.mallocInt(1);
            IntBuffer height = dimensions.mallocInt(1);

            glfwGetWindowSize(
                    window,
                    width,
                    height
            );

            report.addProperty(
                    "probeWindowRequestedWidth",
                    width.get(0)
            );
            report.addProperty(
                    "probeWindowRequestedHeight",
                    height.get(0)
            );
        }

        report.addProperty(
                "noApiProbeWindowCreated",
                true
        );
        report.addProperty(
                "noApiProbeWindowSource",
                "NEOFORGE_HANDOFF_CANDIDATE"
        );
        report.addProperty(
                "probeWindowResizable",
                true
        );
        report.addProperty(
                "visibleFrameVerificationRequested",
                options.visibleFrameVerification()
        );

        report.addProperty(
                "stage",
                "CREATE_VULKAN_SURFACE"
        );

        LongBuffer surfacePointer = stack.mallocLong(1);

        int result = glfwCreateWindowSurface(
                instance,
                window,
                null,
                surfacePointer
        );

        report.addProperty(
                "glfwCreateWindowSurfaceResult",
                result
        );

        if (result != VK_SUCCESS) {
            glfwDestroyWindow(window);
            VulkanHandoffCandidate.markDestroyed(window);

            throw new VulkanProbeException(
                    "CREATE_VULKAN_SURFACE",
                    "glfwCreateWindowSurface failed for the handoff candidate with VkResult "
                            + result
            );
        }

        long surface = surfacePointer.get(0);

        if (surface == NULL) {
            glfwDestroyWindow(window);
            VulkanHandoffCandidate.markDestroyed(window);

            throw new VulkanProbeException(
                    "CREATE_VULKAN_SURFACE",
                    "glfwCreateWindowSurface returned a null VkSurfaceKHR for the handoff candidate."
            );
        }

        report.addProperty(
                "vulkanSurfaceCreated",
                true
        );
        report.addProperty(
                "handoffCandidateVulkanSurfaceCreated",
                true
        );

        return new VulkanPresentationProbe(
                instance,
                report,
                options,
                window,
                surface,
                true
        );
    }

    long surface() {
        return surface;
    }

    long windowHandle() {
        return window;
    }

    void showForVerificationIfRequested() {
        /*
         * The handoff candidate is deliberately hidden. A visual probe would
         * no longer be a faithful rehearsal of the future seamless handoff.
         */
        report.addProperty(
                "visibleProbeWindowShown",
                false
        );

        if (options.visibleFrameVerification()) {
            report.addProperty(
                    "visibleProbeSuppressedForHandoffRehearsal",
                    true
            );
        }
    }

    void bringToFrontAfterPresentIfRequested() {
        /*
         * Intentionally no-op for the hidden handoff candidate.
         */
    }

    void requestSwapchainResizeVerification() {
        if (!options.visibleFrameVerification()) {
            return;
        }

        report.addProperty(
                "stage",
                "REQUEST_SWAPCHAIN_RESIZE"
        );

        glfwSetWindowSize(
                window,
                RESIZE_TEST_WIDTH,
                RESIZE_TEST_HEIGHT
        );

        glfwPollEvents();

        report.addProperty(
                "swapchainResizeRequested",
                true
        );
        report.addProperty(
                "swapchainResizeRequestedWidth",
                RESIZE_TEST_WIDTH
        );
        report.addProperty(
                "swapchainResizeRequestedHeight",
                RESIZE_TEST_HEIGHT
        );
    }

    boolean framebufferExtentDiffers(
            int width,
            int height
    ) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer actualWidth = stack.mallocInt(1);
            IntBuffer actualHeight = stack.mallocInt(1);

            glfwGetFramebufferSize(
                    window,
                    actualWidth,
                    actualHeight
            );

            return actualWidth.get(0) != width
                    || actualHeight.get(0) != height;
        }
    }

    void awaitRenderableFramebuffer() {
        report.addProperty(
                "stage",
                "WAIT_RENDERABLE_FRAMEBUFFER"
        );

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);

            while (!glfwWindowShouldClose(window)) {
                glfwGetFramebufferSize(
                        window,
                        width,
                        height
                );

                if (width.get(0) > 0
                        && height.get(0) > 0) {
                    report.addProperty(
                            "renderableFramebufferWidth",
                            width.get(0)
                    );
                    report.addProperty(
                            "renderableFramebufferHeight",
                            height.get(0)
                    );
                    return;
                }

                glfwWaitEventsTimeout(0.05);
            }
        }

        throw new VulkanProbeException(
                "WAIT_RENDERABLE_FRAMEBUFFER",
                "Handoff candidate closed while waiting for a non-zero framebuffer."
        );
    }

    void transferOwnershipToRuntime() {
        if (destroyed) {
            throw new IllegalStateException(
                    "Cannot transfer an already destroyed Vulkan presentation."
            );
        }

        if (handoffCandidate) {
            boolean promoted =
                    VulkanHandoffCandidate
                            .promoteProbeBorrowToGameplay(
                                    window
                            );

            report.addProperty(
                    "handoffCandidateProbeBorrowPromotedAtRuntimeAdoption",
                    promoted
            );

            if (!promoted) {
                throw new IllegalStateException(
                        "Persistent NO_API handoff candidate could not be promoted from probe borrow to gameplay ownership."
                );
            }
        }

        transferredToRuntime = true;

        report.addProperty(
                "vulkanPresentationOwnershipTransferredToRuntime",
                true
        );
    }

    void closeRuntimeOwnedResources() {
        transferredToRuntime = false;
        close();
    }

    @Override
    public void close() {
        if (destroyed) {
            return;
        }

        if (transferredToRuntime) {
            report.addProperty(
                    "vulkanSurfaceRetainedByRuntime",
                    surface != NULL
            );
            report.addProperty(
                    "probeWindowRetainedByRuntime",
                    window != NULL
            );
            report.addProperty(
                    "vulkanSurfaceDestroyed",
                    false
            );
            report.addProperty(
                    "probeWindowDestroyed",
                    false
            );
            report.addProperty(
                    "handoffCandidateDestroyedByVulkanProbe",
                    false
            );

            VulkanHandoffCandidate.enrich(report);
            return;
        }

        destroyed = true;

        boolean surfaceDestroyed = false;
        boolean windowDestroyed = false;

        if (surface != NULL) {
            try {
                vkDestroySurfaceKHR(
                        instance,
                        surface,
                        null
                );

                surfaceDestroyed = true;
            } catch (Throwable throwable) {
                report.addProperty(
                        "surfaceDestroyError",
                        String.valueOf(
                                throwable.getMessage()
                        )
                );
            } finally {
                surface = NULL;
            }
        }

        report.addProperty(
                "vulkanSurfaceDestroyed",
                surfaceDestroyed
        );

        if (window != NULL) {
            long closingWindow = window;

            if (handoffCandidate
                    && VulkanHandoffCandidate.isKnownCandidate(
                    closingWindow
            )) {
                /*
                 * Runtime adoption promotes the probe borrow to gameplay
                 * ownership. Presentation teardown therefore releases only the
                 * VkSurfaceKHR here. The GLFW_NO_API window is destroyed by the
                 * candidate owner after every WSI borrower has already closed.
                 */
                VulkanHandoffCandidate.releaseProbeBorrow(
                        closingWindow
                );
                report.addProperty(
                        "handoffCandidateRetainedAfterProbe",
                        true
                );
                report.addProperty(
                        "handoffCandidateWindowDestroyDeferredUntilPresentationShutdown",
                        true
                );
                window = NULL;
            } else {
                try {
                    glfwDestroyWindow(window);
                    windowDestroyed = true;
                } catch (Throwable throwable) {
                    report.addProperty(
                            "probeWindowDestroyError",
                            String.valueOf(
                                    throwable.getMessage()
                            )
                    );
                } finally {
                    window = NULL;

                    if (handoffCandidate) {
                        VulkanHandoffCandidate.markDestroyed(
                                closingWindow
                        );
                    }
                }
            }
        }

        report.addProperty(
                "probeWindowDestroyed",
                windowDestroyed
        );
        report.addProperty(
                "handoffCandidateDestroyedByVulkanProbe",
                handoffCandidate && windowDestroyed
        );
        report.addProperty(
                "handoffCandidateBorrowReleasedByVulkanProbe",
                handoffCandidate && !windowDestroyed
        );

        VulkanHandoffCandidate.enrich(report);
    }
}