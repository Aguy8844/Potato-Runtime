package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ordovicium.potato.render.backend.target.RenderTargetBackendBridge;
import dev.ordovicium.potato.render.backend.target.RenderTargetBackendState;
import dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Identifies Minecraft's main render target after MainTarget has completed its
 * immediate OpenGL framebuffer/color/depth allocation.
 */
@Mixin(MainTarget.class)
abstract class MainTargetOwnershipMixin {
    @Inject(
            method = "<init>(II)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$observeInitialMainAllocation(
            int requestedWidth,
            int requestedHeight,
            CallbackInfo callbackInfo
    ) {
        MainTarget self =
                (MainTarget) (Object) this;

        RenderTargetBackendState state =
                ((RenderTargetBackendBridge) self)
                        .potato$backendState();

        state.markMain();

        state.observeSnapshot(
                self.width,
                self.height,
                self.viewWidth,
                self.viewHeight,
                self.frameBufferId,
                self.getColorTextureId(),
                self.getDepthTextureId()
        );

        state.observeInitialMainAllocation();

        RenderTargetOwnershipDiagnostics
                .registerMainTarget(state);
    }
}