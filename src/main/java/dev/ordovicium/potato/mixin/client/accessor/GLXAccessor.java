package dev.ordovicium.potato.mixin.client.accessor;

import com.mojang.blaze3d.platform.GLX;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Explicit compatibility seam for GLX's diagnostic CPU string.
 */
@Mixin(GLX.class)
public interface GLXAccessor {
    @Accessor("cpuInfo")
    static String potato$getCpuInfo() {
        throw new AssertionError();
    }

    @Accessor("cpuInfo")
    static void potato$setCpuInfo(
            String value
    ) {
        throw new AssertionError();
    }
}