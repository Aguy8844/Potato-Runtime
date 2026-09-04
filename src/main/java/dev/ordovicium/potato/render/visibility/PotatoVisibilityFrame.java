package dev.ordovicium.potato.render.visibility;

import dev.ordovicium.potato.render.backend.draw.SectionLayerFrameContext;
import org.joml.Matrix4f;

/**
 * Immutable camera/projective visibility state for one section render layer.
 *
 * <p>The classifier performs homogeneous clip-space AABB tests using the same
 * model-view and projection matrices Minecraft already selected for the
 * current render camera. This means first person, third person and spectator
 * camera changes naturally follow the actual rendered point of view.</p>
 *
 * <p>No objects are allocated per section classification.</p>
 */
public final class PotatoVisibilityFrame {

    private static final float SECTION_SIZE =
            16.0f;

    private static final float HOT_NEAR_DISTANCE_BLOCKS =
            32.0f;

    private static final float WARM_NEAR_DISTANCE_BLOCKS =
            96.0f;

    private static final float WARM_XY_EXPANSION =
            1.85f;

    private static final float WARM_Z_EXPANSION =
            1.25f;

    private final boolean valid;

    /*
     * Combined projection * model-view matrix cached as raw floats so section
     * tests do not allocate vectors/matrices.
     */
    private final float m00;
    private final float m01;
    private final float m02;
    private final float m03;

    private final float m10;
    private final float m11;
    private final float m12;
    private final float m13;

    private final float m20;
    private final float m21;
    private final float m22;
    private final float m23;

    private final float m30;
    private final float m31;
    private final float m32;
    private final float m33;

    private final double cameraX;
    private final double cameraY;
    private final double cameraZ;

    PotatoVisibilityFrame(
            SectionLayerFrameContext context
    ) {
        this.cameraX =
                context == null
                        ? 0.0
                        : context.cameraX();

        this.cameraY =
                context == null
                        ? 0.0
                        : context.cameraY();

        this.cameraZ =
                context == null
                        ? 0.0
                        : context.cameraZ();

        if (context == null
                || !context.hasMatrices()) {

            valid =
                    false;

            m00 = 0.0f;
            m01 = 0.0f;
            m02 = 0.0f;
            m03 = 0.0f;

            m10 = 0.0f;
            m11 = 0.0f;
            m12 = 0.0f;
            m13 = 0.0f;

            m20 = 0.0f;
            m21 = 0.0f;
            m22 = 0.0f;
            m23 = 0.0f;

            m30 = 0.0f;
            m31 = 0.0f;
            m32 = 0.0f;
            m33 = 0.0f;

            return;
        }

        Matrix4f combined =
                new Matrix4f(
                        context.projection()
                )
                        .mul(
                                context.modelView()
                        );

        m00 = combined.m00();
        m01 = combined.m01();
        m02 = combined.m02();
        m03 = combined.m03();

        m10 = combined.m10();
        m11 = combined.m11();
        m12 = combined.m12();
        m13 = combined.m13();

        m20 = combined.m20();
        m21 = combined.m21();
        m22 = combined.m22();
        m23 = combined.m23();

        m30 = combined.m30();
        m31 = combined.m31();
        m32 = combined.m32();
        m33 = combined.m33();

        valid =
                allFinite(
                        m00, m01, m02, m03,
                        m10, m11, m12, m13,
                        m20, m21, m22, m23,
                        m30, m31, m32, m33
                );
    }

    public boolean valid() {
        return valid;
    }

    public double cameraX() {
        return cameraX;
    }

    public double cameraY() {
        return cameraY;
    }

    public double cameraZ() {
        return cameraZ;
    }

    public SectionVisibilityTier classify(
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        if (!valid) {
            return SectionVisibilityTier.HOT;
        }

        float centerX =
                chunkOffsetX
                        + SECTION_SIZE * 0.5f;

        float centerY =
                chunkOffsetY
                        + SECTION_SIZE * 0.5f;

        float centerZ =
                chunkOffsetZ
                        + SECTION_SIZE * 0.5f;

        float distanceSquared =
                centerX * centerX
                        + centerY * centerY
                        + centerZ * centerZ;

        float hotNearSquared =
                HOT_NEAR_DISTANCE_BLOCKS
                        * HOT_NEAR_DISTANCE_BLOCKS;

        if (distanceSquared
                <= hotNearSquared) {
            return SectionVisibilityTier.HOT;
        }

        if (intersectsClipAabb(
                chunkOffsetX,
                chunkOffsetY,
                chunkOffsetZ,
                1.0f,
                1.0f
        )) {
            return SectionVisibilityTier.HOT;
        }

        float warmNearSquared =
                WARM_NEAR_DISTANCE_BLOCKS
                        * WARM_NEAR_DISTANCE_BLOCKS;

        if (distanceSquared
                <= warmNearSquared) {
            return SectionVisibilityTier.WARM;
        }

        if (intersectsClipAabb(
                chunkOffsetX,
                chunkOffsetY,
                chunkOffsetZ,
                WARM_XY_EXPANSION,
                WARM_Z_EXPANSION
        )) {
            return SectionVisibilityTier.WARM;
        }

        return SectionVisibilityTier.COLD;
    }

    public SectionVisibilityReason reasonFor(
            SectionVisibilityTier tier,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        if (!valid) {
            return SectionVisibilityReason
                    .INVALID_FRAME_CONSERVATIVE_FALLBACK;
        }

        float centerX =
                chunkOffsetX
                        + SECTION_SIZE * 0.5f;

        float centerY =
                chunkOffsetY
                        + SECTION_SIZE * 0.5f;

        float centerZ =
                chunkOffsetZ
                        + SECTION_SIZE * 0.5f;

        float distanceSquared =
                centerX * centerX
                        + centerY * centerY
                        + centerZ * centerZ;

        if (tier
                == SectionVisibilityTier.HOT) {

            float hotNearSquared =
                    HOT_NEAR_DISTANCE_BLOCKS
                            * HOT_NEAR_DISTANCE_BLOCKS;

            if (distanceSquared
                    <= hotNearSquared) {
                return SectionVisibilityReason
                        .NEAR_CAMERA_GUARD;
            }

            return SectionVisibilityReason
                    .EXACT_FRUSTUM_INTERSECTION;
        }

        if (tier
                == SectionVisibilityTier.WARM) {

            float warmNearSquared =
                    WARM_NEAR_DISTANCE_BLOCKS
                            * WARM_NEAR_DISTANCE_BLOCKS;

            if (distanceSquared
                    <= warmNearSquared) {
                return SectionVisibilityReason
                        .NEAR_CAMERA_GUARD;
            }

            return SectionVisibilityReason
                    .EXPANDED_FRUSTUM_GUARD;
        }

        return SectionVisibilityReason
                .OUTSIDE_GUARD_BAND;
    }

    /**
     * Conservative homogeneous clip-space AABB test.
     *
     * <p>A box is outside only when all eight corners lie outside the same
     * clip plane. HOT uses the exact clip volume. WARM uses a larger XY/Z
     * guard volume for camera-turn prewarming.</p>
     */
    private boolean intersectsClipAabb(
            float minX,
            float minY,
            float minZ,
            float xyExpansion,
            float zExpansion
    ) {
        boolean allLeft =
                true;

        boolean allRight =
                true;

        boolean allBottom =
                true;

        boolean allTop =
                true;

        boolean allNear =
                true;

        boolean allFar =
                true;

        for (int xIndex = 0;
             xIndex < 2;
             xIndex++) {

            float x =
                    minX
                            + (xIndex == 0
                            ? 0.0f
                            : SECTION_SIZE);

            for (int yIndex = 0;
                 yIndex < 2;
                 yIndex++) {

                float y =
                        minY
                                + (yIndex == 0
                                ? 0.0f
                                : SECTION_SIZE);

                for (int zIndex = 0;
                     zIndex < 2;
                     zIndex++) {

                    float z =
                            minZ
                                    + (zIndex == 0
                                    ? 0.0f
                                    : SECTION_SIZE);

                    float clipX =
                            m00 * x
                                    + m10 * y
                                    + m20 * z
                                    + m30;

                    float clipY =
                            m01 * x
                                    + m11 * y
                                    + m21 * z
                                    + m31;

                    float clipZ =
                            m02 * x
                                    + m12 * y
                                    + m22 * z
                                    + m32;

                    float clipW =
                            m03 * x
                                    + m13 * y
                                    + m23 * z
                                    + m33;

                    float absoluteW =
                            Math.max(
                                    0.0001f,
                                    Math.abs(
                                            clipW
                                    )
                            );

                    float xyLimit =
                            absoluteW
                                    * xyExpansion;

                    float zLimit =
                            absoluteW
                                    * zExpansion;

                    if (clipX
                            >= -xyLimit) {
                        allLeft =
                                false;
                    }

                    if (clipX
                            <= xyLimit) {
                        allRight =
                                false;
                    }

                    if (clipY
                            >= -xyLimit) {
                        allBottom =
                                false;
                    }

                    if (clipY
                            <= xyLimit) {
                        allTop =
                                false;
                    }

                    if (clipZ
                            >= -zLimit) {
                        allNear =
                                false;
                    }

                    if (clipZ
                            <= zLimit) {
                        allFar =
                                false;
                    }
                }
            }
        }

        return !(
                allLeft
                        || allRight
                        || allBottom
                        || allTop
                        || allNear
                        || allFar
        );
    }

    private static boolean allFinite(
            float... values
    ) {
        for (float value : values) {
            if (!Float.isFinite(
                    value
            )) {
                return false;
            }
        }

        return true;
    }
}