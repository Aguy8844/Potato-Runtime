package dev.ordovicium.potato.render.visibility;

import net.minecraft.client.Minecraft;
import org.joml.Matrix4f;

/**
 * Projective state for one sparse hierarchy calibration sample.
 *
 * <p>The AABB footprint is clipped to normalized device coordinates before it
 * is converted into pixels. Therefore a reported visible pixel span can never
 * exceed the larger framebuffer dimension.</p>
 *
 * <p>Bounds intersecting the camera/near plane are handled conservatively as
 * full-screen. This intentionally prevents a future LOD selector from
 * simplifying geometry close to the camera.</p>
 */
public final class ScreenSpaceClusterFrame {

    private static final float MIN_POSITIVE_CLIP_W =
            0.001f;

    private final boolean valid;

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

    private final int viewportWidth;
    private final int viewportHeight;
    private final int maximumViewportSpan;

    public ScreenSpaceClusterFrame(
            Matrix4f modelView,
            Matrix4f projection,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        this.cameraX =
                cameraX;

        this.cameraY =
                cameraY;

        this.cameraZ =
                cameraZ;

        Minecraft minecraft =
                Minecraft.getInstance();

        int width =
                minecraft == null
                        || minecraft.getWindow() == null
                        ? 1
                        : Math.max(
                                1,
                                minecraft.getWindow().getWidth()
                        );

        int height =
                minecraft == null
                        || minecraft.getWindow() == null
                        ? 1
                        : Math.max(
                                1,
                                minecraft.getWindow().getHeight()
                        );

        viewportWidth =
                width;

        viewportHeight =
                height;

        maximumViewportSpan =
                Math.max(
                        width,
                        height
                );

        if (modelView == null
                || projection == null) {

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
                        projection
                )
                        .mul(
                                modelView
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

    public int viewportWidth() {
        return viewportWidth;
    }

    public int viewportHeight() {
        return viewportHeight;
    }

    public int maximumViewportSpan() {
        return maximumViewportSpan;
    }

    /**
     * Returns a viewport-clipped maximum X/Y span in pixels.
     *
     * <p>The metric describes possible visible screen occupancy, not geometric
     * simplification error. Future LOD selection will additionally need a
     * measured surface-error bound.</p>
     */
    public float projectedPixelSpan(
            int minWorldX,
            int minWorldY,
            int minWorldZ,
            int maxWorldX,
            int maxWorldY,
            int maxWorldZ
    ) {
        if (!valid) {
            return maximumViewportSpan;
        }

        float minScreenX =
                Float.POSITIVE_INFINITY;

        float maxScreenX =
                Float.NEGATIVE_INFINITY;

        float minScreenY =
                Float.POSITIVE_INFINITY;

        float maxScreenY =
                Float.NEGATIVE_INFINITY;

        for (int xIndex = 0;
             xIndex < 2;
             xIndex++) {

            float x =
                    (float) (
                            (xIndex == 0
                                    ? minWorldX
                                    : maxWorldX)
                                    - cameraX
                    );

            for (int yIndex = 0;
                 yIndex < 2;
                 yIndex++) {

                float y =
                        (float) (
                                (yIndex == 0
                                        ? minWorldY
                                        : maxWorldY)
                                        - cameraY
                        );

                for (int zIndex = 0;
                     zIndex < 2;
                     zIndex++) {

                    float z =
                            (float) (
                                    (zIndex == 0
                                            ? minWorldZ
                                            : maxWorldZ)
                                            - cameraZ
                            );

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

                    float clipW =
                            m03 * x
                                    + m13 * y
                                    + m23 * z
                                    + m33;

                    if (!Float.isFinite(
                            clipW
                    )
                            || clipW
                            <= MIN_POSITIVE_CLIP_W) {

                        return maximumViewportSpan;
                    }

                    float inverseW =
                            1.0f
                                    / clipW;

                    float ndcX =
                            clipX
                                    * inverseW;

                    float ndcY =
                            clipY
                                    * inverseW;

                    if (!Float.isFinite(
                            ndcX
                    )
                            || !Float.isFinite(
                            ndcY
                    )) {

                        return maximumViewportSpan;
                    }

                    /*
                     * The old Patch 046 metric used raw NDC coordinates.
                     * A point just in front of the camera can have enormous
                     * finite NDC values, producing impossible 60k-pixel spans.
                     *
                     * For visible screen occupancy, clip to the viewport.
                     */
                    float screenX =
                            clamp(
                                    ndcX,
                                    -1.0f,
                                    1.0f
                            );

                    float screenY =
                            clamp(
                                    ndcY,
                                    -1.0f,
                                    1.0f
                            );

                    minScreenX =
                            Math.min(
                                    minScreenX,
                                    screenX
                            );

                    maxScreenX =
                            Math.max(
                                    maxScreenX,
                                    screenX
                            );

                    minScreenY =
                            Math.min(
                                    minScreenY,
                                    screenY
                            );

                    maxScreenY =
                            Math.max(
                                    maxScreenY,
                                    screenY
                            );
                }
            }
        }

        float pixelWidth =
                Math.abs(
                        maxScreenX
                                - minScreenX
                )
                        * 0.5f
                        * viewportWidth;

        float pixelHeight =
                Math.abs(
                        maxScreenY
                                - minScreenY
                )
                        * 0.5f
                        * viewportHeight;

        float span =
                Math.max(
                        pixelWidth,
                        pixelHeight
                );

        if (!Float.isFinite(
                span
        )) {
            return maximumViewportSpan;
        }

        return clamp(
                span,
                0.0f,
                maximumViewportSpan
        );
    }

    private static float clamp(
            float value,
            float minimum,
            float maximum
    ) {
        return Math.max(
                minimum,
                Math.min(
                        maximum,
                        value
                )
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