package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.platform.GLX;
import dev.ordovicium.potato.render.backend.RendererInitializationRehearsal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts any unexpected baseline GLX._init invocation.
 */
@Mixin(GLX.class)
abstract class GLXInitObservationMixin {
    @Inject(
            method = "_init(IZ)V",
            at = @At("HEAD"),
            require = 1
    )
    private static void potato$observeBaselineGlxInit(
            int debugVerbosity,
            boolean synchronousDebug,
            CallbackInfo callbackInfo
    ) {
        RendererInitializationRehearsal
                .observeGlxInitCall();
    }
}