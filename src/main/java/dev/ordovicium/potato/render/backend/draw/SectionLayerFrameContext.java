package dev.ordovicium.potato.render.backend.draw;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;

/**
 * One immutable snapshot for an entire Minecraft chunk RenderType layer.
 *
 * <p>Matrices and global shader state are copied once per layer. Patch 058
 * also carries the effective render distance and framebuffer size so the LOD
 * policy can use screen-space error without querying Minecraft per section.</p>
 */
public record SectionLayerFrameContext(
        String renderTypeName,
        Matrix4f modelView,
        Matrix4f projection,
        double cameraX,
        double cameraY,
        double cameraZ,
        float colorModulatorR,
        float colorModulatorG,
        float colorModulatorB,
        float colorModulatorA,
        float fogStart,
        float fogEnd,
        float fogColorR,
        float fogColorG,
        float fogColorB,
        float fogColorA,
        int fogShape,
        boolean shaderUsesBlockVertexFormat,
        boolean solidLayer,
        int effectiveRenderDistanceChunks,
        int viewportWidth,
        int viewportHeight
) {
    public static SectionLayerFrameContext snapshot(
            RenderType renderType,
            Matrix4f modelView,
            Matrix4f projection,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        float[] color =
                RenderSystem.getShaderColor();

        float[] fogColor =
                RenderSystem.getShaderFogColor();

        FogShape fogShape =
                RenderSystem.getShaderFogShape();

        Minecraft minecraft =
                Minecraft.getInstance();

        int effectiveRenderDistance =
                minecraft == null
                        ? 12
                        : minecraft.options
                                .getEffectiveRenderDistance();

        int viewportWidth =
                minecraft == null
                        || minecraft.getWindow() == null
                        ? 1
                        : minecraft.getWindow()
                                .getWidth();

        int viewportHeight =
                minecraft == null
                        || minecraft.getWindow() == null
                        ? 1
                        : minecraft.getWindow()
                                .getHeight();

        return new SectionLayerFrameContext(
                String.valueOf(renderType),
                modelView == null
                        ? null
                        : new Matrix4f(modelView),
                projection == null
                        ? null
                        : new Matrix4f(projection),
                cameraX,
                cameraY,
                cameraZ,
                component(color, 0, 1.0f),
                component(color, 1, 1.0f),
                component(color, 2, 1.0f),
                component(color, 3, 1.0f),
                RenderSystem.getShaderFogStart(),
                RenderSystem.getShaderFogEnd(),
                component(fogColor, 0, 0.0f),
                component(fogColor, 1, 0.0f),
                component(fogColor, 2, 0.0f),
                component(fogColor, 3, 0.0f),
                fogShape == null
                        ? 0
                        : fogShape.getIndex(),
                shader != null
                        && DefaultVertexFormat.BLOCK.equals(
                        shader.getVertexFormat()
                ),
                renderType
                        == RenderType.solid(),
                Math.max(
                        2,
                        effectiveRenderDistance
                ),
                Math.max(
                        1,
                        viewportWidth
                ),
                Math.max(
                        1,
                        viewportHeight
                )
        );
    }

    public boolean hasMatrices() {
        return modelView != null
                && projection != null;
    }

    private static float component(
            float[] values,
            int index,
            float fallback
    ) {
        return values != null
                && index >= 0
                && index < values.length
                ? values[index]
                : fallback;
    }
}
