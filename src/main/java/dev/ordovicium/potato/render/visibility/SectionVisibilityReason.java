package dev.ordovicium.potato.render.visibility;

/**
 * Why one section received its current visibility tier.
 */
public enum SectionVisibilityReason {
    EXACT_FRUSTUM_INTERSECTION,
    NEAR_CAMERA_GUARD,
    EXPANDED_FRUSTUM_GUARD,
    OUTSIDE_GUARD_BAND,
    INVALID_FRAME_CONSERVATIVE_FALLBACK
}