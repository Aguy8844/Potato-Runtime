package dev.ordovicium.potato.render.engine;

public enum PotatoRenderReadinessGate {
    VULKAN_CAPABILITY_PROBE(
            "vulkanCapabilityProbe"
    ),
    NATIVE_VULKAN_DEVICE(
            "nativeVulkanDevice"
    ),
    WINDOW_HANDOFF_CANDIDATE(
            "windowHandoffCandidate"
    ),
    CONTEXT_BOOTSTRAP_BOUNDARY(
            "contextBootstrapBoundary"
    ),
    RENDERER_INITIALIZATION_DISPATCH(
            "rendererInitializationDispatch"
    ),
    MAIN_TARGET_ABSTRACTION(
            "mainTargetAbstraction"
    ),
    PERSISTENT_VULKAN_CORE(
            "persistentVulkanCore"
    ),
    VISIBLE_WORLD_DRAW(
            "visibleWorldDraw"
    ),
    TEXTURE_LIFECYCLE(
            "textureLifecycle"
    ),
    GUI_ENTITY_PARTICLE_PATH(
            "guiEntityParticlePath"
    ),
    MAIN_WINDOW_PRESENTATION(
            "mainWindowPresentation"
    );

    private final String id;

    PotatoRenderReadinessGate(
            String id
    ) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
