package dev.ordovicium.potato.render.backend;

/**
 * Cutover policy for one renderer/platform responsibility.
 */
public enum BackendBoundaryPolicy {
    /**
     * Backend-neutral responsibility. Reuse it instead of replacing it.
     */
    KEEP_COMMON,

    /**
     * A verified interception point exists, but current behavior remains
     * baseline OpenGL.
     */
    SEAM_VERIFIED,

    /**
     * OpenGL-specific responsibility that must be implemented by the Vulkan
     * backend before main-window cutover.
     */
    REPLACE_FOR_VULKAN,

    /**
     * Blocks creation of a GLFW_NO_API Minecraft main window until solved.
     */
    CUTOVER_BLOCKER,

    /**
     * Common operation whose ordering must change to respect Vulkan lifetime.
     */
    ADAPT_LIFETIME
}