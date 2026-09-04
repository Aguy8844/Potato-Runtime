package dev.ordovicium.potato.render.backend;

/**
 * Evidence-backed ownership boundaries for Minecraft 1.21.1 / NeoForge.
 *
 * <p>These are responsibilities, not individual OpenGL function calls. Potato
 * replaces whole responsibilities so the Vulkan backend does not become a pile
 * of one-off call suppressions.</p>
 */
public enum RenderBackendBoundary {
    GLFW_PLATFORM_SERVICES(
            "glfw-platform-services",
            "RenderSystem.initBackendSystem / GLX._initGlfw / GLFW event utilities",
            BackendBoundaryPolicy.KEEP_COMMON,
            "Keep GLFW initialization, timing, event polling and platform services shared between backends."
    ),

    NEOFORGE_EARLY_DISPLAY(
            "neoforge-early-display",
            "NeoForge DisplayWindow",
            BackendBoundaryPolicy.CUTOVER_BLOCKER,
            "EarlyDisplay creates an OpenGL GLFW window, installs a GL context/capabilities, renders with GL32C and swaps buffers before Minecraft window bootstrap."
    ),

    MINECRAFT_WINDOW_HANDOFF(
            "minecraft-window-handoff",
            "ImmediateWindowHandler.setupMinecraftWindow",
            BackendBoundaryPolicy.SEAM_VERIFIED,
            "Potato already observes the NeoForge provider handoff without mutating arguments or the returned native window."
    ),

    MINECRAFT_CONTEXT_BOOTSTRAP(
            "minecraft-context-bootstrap",
            "Window constructor: glfwMakeContextCurrent + GL.createCapabilities + GL-backed texture-limit query",
            BackendBoundaryPolicy.REPLACE_FOR_VULKAN,
            "A GLFW_NO_API window has no OpenGL context. Vulkan must replace this context/capability bootstrap and source hardware limits from Vulkan."
    ),

    RENDERER_INITIALIZATION(
            "renderer-initialization",
            "Minecraft constructor: RenderSystem.initRenderer / GLX._init",
            BackendBoundaryPolicy.REPLACE_FOR_VULKAN,
            "Renderer initialization and API description are OpenGL-specific and require a backend-owned Vulkan bootstrap."
    ),

    RENDER_TARGETS_AND_STATE(
            "render-targets-and-state",
            "MainTarget / RenderTarget / RenderSystem / GlStateManager",
            BackendBoundaryPolicy.REPLACE_FOR_VULKAN,
            "Framebuffer objects, mutable OpenGL state, texture binding and draw submission must become Vulkan resource, pipeline and command-buffer responsibilities."
    ),

    DEFAULT_RENDER_STATE(
            "default-render-state",
            "RenderSystem.setupDefaultState",
            BackendBoundaryPolicy.REPLACE_FOR_VULKAN,
            "The baseline method directly programs OpenGL state. Vulkan expresses equivalent state in pipelines, attachments and command recording."
    ),

    VSYNC_POLICY(
            "vsync-policy",
            "Window.updateVsync -> glfwSwapInterval",
            BackendBoundaryPolicy.REPLACE_FOR_VULKAN,
            "Vulkan VSync is a swapchain present-mode policy, not a GLFW swap interval."
    ),

    FRAME_PRESENTATION(
            "frame-presentation",
            "Window.updateDisplay -> RenderSystem.flipFrame -> glfwSwapBuffers",
            BackendBoundaryPolicy.REPLACE_FOR_VULKAN,
            "Keep event/replay responsibilities as appropriate, but replace OpenGL buffer swapping with Vulkan acquire/submit/present."
    ),

    READBACK_AND_DIAGNOSTICS(
            "readback-and-diagnostics",
            "RenderSystem.readPixels / GlDebug / OpenGL API strings",
            BackendBoundaryPolicy.REPLACE_FOR_VULKAN,
            "Screenshots, readback and renderer diagnostics need backend-specific Vulkan implementations."
    ),

    WINDOW_LIFETIME(
            "window-lifetime",
            "Window.close / GLFW native window destruction",
            BackendBoundaryPolicy.ADAPT_LIFETIME,
            "GLFW destruction remains common, but Vulkan device/swapchain/surface teardown must complete before destroying the native window."
    );

    private final String id;
    private final String currentOwner;
    private final BackendBoundaryPolicy policy;
    private final String rationale;

    RenderBackendBoundary(
            String id,
            String currentOwner,
            BackendBoundaryPolicy policy,
            String rationale
    ) {
        this.id = id;
        this.currentOwner = currentOwner;
        this.policy = policy;
        this.rationale = rationale;
    }

    public String id() {
        return id;
    }

    public String currentOwner() {
        return currentOwner;
    }

    public BackendBoundaryPolicy policy() {
        return policy;
    }

    public String rationale() {
        return rationale;
    }
}