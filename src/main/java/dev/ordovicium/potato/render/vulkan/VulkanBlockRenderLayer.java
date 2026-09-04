package dev.ordovicium.potato.render.vulkan;

import net.minecraft.client.renderer.RenderType;

/**
 * BLOCK layer variants currently implemented by the production shader.
 */
enum VulkanBlockRenderLayer {
    SOLID(
            "RenderType[solid:",
            0.0f
    ),
    CUTOUT(
            "RenderType[cutout:",
            0.1f
    );

    private final String prefix;
    private final float alphaCutoff;

    VulkanBlockRenderLayer(
            String prefix,
            float alphaCutoff
    ) {
        this.prefix = prefix;
        this.alphaCutoff = alphaCutoff;
    }

    float alphaCutoff() {
        return alphaCutoff;
    }

    static VulkanBlockRenderLayer from(
            RenderType renderType
    ) {
        return fromName(
                String.valueOf(
                        renderType
                )
        );
    }

    static VulkanBlockRenderLayer fromName(
            String renderTypeName
    ) {
        if (renderTypeName == null) {
            return null;
        }

        for (VulkanBlockRenderLayer layer
                : values()) {
            if (renderTypeName.startsWith(
                    layer.prefix
            )) {
                return layer;
            }
        }

        return null;
    }
}
