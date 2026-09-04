package dev.ordovicium.potato.render.engine;

public enum PotatoRenderBackendId {
    VULKAN("VULKAN"),
    OPENGL_COMPATIBILITY("OPENGL_COMPATIBILITY");

    private final String id;

    PotatoRenderBackendId(
            String id
    ) {
        this.id = id;
    }

    public String id() {
        return id;
    }
}
