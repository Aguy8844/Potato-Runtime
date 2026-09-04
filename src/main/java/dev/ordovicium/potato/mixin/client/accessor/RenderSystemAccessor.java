package dev.ordovicium.potato.mixin.client.accessor;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Explicit compatibility seam for RenderSystem's backend API description.
 */
@Mixin(RenderSystem.class)
public interface RenderSystemAccessor {
    @Accessor("apiDescription")
    static void potato$setApiDescription(
            String value
    ) {
        throw new AssertionError();
    }
}