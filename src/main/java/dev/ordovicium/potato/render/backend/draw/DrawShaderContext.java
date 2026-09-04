package dev.ordovicium.potato.render.backend.draw;

import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * Shader/matrix state associated with one actual VertexBuffer shader draw.
 */
public record DrawShaderContext(
        Matrix4f modelView,
        Matrix4f projection,
        ShaderInstance shader
) {
    public static DrawShaderContext snapshot(
            Matrix4f modelView,
            Matrix4f projection,
            ShaderInstance shader
    ) {
        return new DrawShaderContext(
                modelView == null
                        ? null
                        : new Matrix4f(modelView),
                projection == null
                        ? null
                        : new Matrix4f(projection),
                shader
        );
    }
}
