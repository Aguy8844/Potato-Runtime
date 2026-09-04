package dev.ordovicium.potato.render.backend.target;

/**
 * Current GPU-resource owner for a tracked Minecraft render target.
 */
public enum RenderTargetResourceOwner {
    /**
     * Minecraft/GlStateManager owns real OpenGL framebuffer and texture IDs.
     */
    OPENGL_TRANSITION,

    /**
     * Reserved for Potato-owned VkImage/VkImageView/device-memory resources.
     */
    VULKAN
}