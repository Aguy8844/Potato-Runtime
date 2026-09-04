package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ordovicium.potato.render.backend.RendererInitializationRehearsal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts any unexpected baseline RenderSystem.initRenderer invocation.
 */
@Mixin(RenderSystem.class)
abstract class RenderSystemInitObservationMixin {
    @Inject(
            method = "initRenderer(IZ)V",
            at = @At("HEAD"),
            require = 1
    )
    private static void potato$observeBaselineRendererInit(
            int debugVerbosity,
            boolean synchronousDebug,
            CallbackInfo callbackInfo
    ) {
        RendererInitializationRehearsal
                .observeRenderSystemInitRendererCall();
    }
}