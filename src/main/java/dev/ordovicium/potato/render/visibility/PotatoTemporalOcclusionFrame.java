package dev.ordovicium.potato.render.visibility;

import org.joml.Matrix4f;

/**
 * One immutable SOLID-layer camera snapshot used by the conservative temporal
 * occlusion query path. Query results are reused only when this frame remains
 * sufficiently close to the frame that produced the evidence.
 */
public record PotatoTemporalOcclusionFrame(
        long sequence,
        double cameraX,
        double cameraY,
        double cameraZ,
        float m00,
        float m01,
        float m02,
        float m10,
        float m11,
        float m12,
        float m20,
        float m21,
        float m22,
        float projectionM00,
        float projectionM11,
        int pressureLevel,
        int maximumQueries
) {
    private static final double MAX_TRANSLATION_BLOCKS = 0.35;
    private static final float MAX_VIEW_MATRIX_DELTA = 0.020f;
    private static final float MAX_PROJECTION_DELTA = 0.012f;

    public static PotatoTemporalOcclusionFrame capture(
            long sequence,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            int pressureLevel,
            int maximumQueries
    ) {
        if (modelView == null || projection == null) {
            return null;
        }

        return new PotatoTemporalOcclusionFrame(
                sequence,
                cameraX,
                cameraY,
                cameraZ,
                modelView.m00(),
                modelView.m01(),
                modelView.m02(),
                modelView.m10(),
                modelView.m11(),
                modelView.m12(),
                modelView.m20(),
                modelView.m21(),
                modelView.m22(),
                projection.m00(),
                projection.m11(),
                pressureLevel,
                maximumQueries
        );
    }

    public boolean compatibleWith(
            PotatoTemporalOcclusionFrame evidence
    ) {
        if (evidence == null) {
            return false;
        }

        double dx = cameraX - evidence.cameraX;
        double dy = cameraY - evidence.cameraY;
        double dz = cameraZ - evidence.cameraZ;

        if (dx * dx + dy * dy + dz * dz
                > MAX_TRANSLATION_BLOCKS * MAX_TRANSLATION_BLOCKS) {
            return false;
        }

        return close(m00, evidence.m00, MAX_VIEW_MATRIX_DELTA)
                && close(m01, evidence.m01, MAX_VIEW_MATRIX_DELTA)
                && close(m02, evidence.m02, MAX_VIEW_MATRIX_DELTA)
                && close(m10, evidence.m10, MAX_VIEW_MATRIX_DELTA)
                && close(m11, evidence.m11, MAX_VIEW_MATRIX_DELTA)
                && close(m12, evidence.m12, MAX_VIEW_MATRIX_DELTA)
                && close(m20, evidence.m20, MAX_VIEW_MATRIX_DELTA)
                && close(m21, evidence.m21, MAX_VIEW_MATRIX_DELTA)
                && close(m22, evidence.m22, MAX_VIEW_MATRIX_DELTA)
                && close(projectionM00, evidence.projectionM00, MAX_PROJECTION_DELTA)
                && close(projectionM11, evidence.projectionM11, MAX_PROJECTION_DELTA);
    }

    private static boolean close(
            float left,
            float right,
            float maximumDelta
    ) {
        return Float.isFinite(left)
                && Float.isFinite(right)
                && Math.abs(left - right) <= maximumDelta;
    }
}
