package dev.ordovicium.potato.render.visibility;

/**
 * Render-work priority only.
 *
 * <p>These tiers never change world simulation, entity ticking, saving or
 * chunk loading. They describe how urgently the renderer needs a section.</p>
 */
public enum SectionVisibilityTier {
    /**
     * Intersects the exact current camera frustum and should be rendered in
     * full detail.
     */
    HOT,

    /**
     * Outside the exact current frustum but inside a generous guard band, or
     * very near the camera. Suitable for predictive/prewarm work.
     */
    WARM,

    /**
     * Outside the current view and its guard band. Full-detail render work may
     * be deferred by later scheduler milestones.
     */
    COLD
}