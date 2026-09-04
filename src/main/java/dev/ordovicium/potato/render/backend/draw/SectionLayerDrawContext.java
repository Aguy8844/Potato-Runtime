package dev.ordovicium.potato.render.backend.draw;

import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * Snapshot of the direct LevelRenderer section-layer draw state proven by the
 * Patch 035 bytecode census.
 *
 * <p>No OpenGL handle and no Vulkan handle is stored here.</p>
 */
public record SectionLayerDrawContext(
        RenderType renderType,
        Matrix4f modelView,
        Matrix4f projection,
        ShaderInstance shader,
        double cameraX,
        double cameraY,
        double cameraZ,
        float chunkOffsetX,
        float chunkOffsetY,
        float chunkOffsetZ
) {
    public static SectionLayerDrawContext snapshot(
            RenderType renderType,
            Matrix4f modelView,
            Matrix4f projection,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ,
            float chunkOffsetX,
            float chunkOffsetY,
            float chunkOffsetZ
    ) {
        return new SectionLayerDrawContext(
                renderType,
                modelView == null
                        ? null
                        : new Matrix4f(modelView),
                projection == null
                        ? null
                        : new Matrix4f(projection),
                shader,
                cameraX,
                cameraY,
                cameraZ,
                chunkOffsetX,
                chunkOffsetY,
                chunkOffsetZ
        );
    }

    public boolean hasMatrices() {
        return modelView != null
                && projection != null;
    }

    public boolean hasShader() {
        return shader != null;
    }

    public boolean hasNonZeroChunkOffset() {
        return chunkOffsetX != 0.0f
                || chunkOffsetY != 0.0f
                || chunkOffsetZ != 0.0f;
    }
}
