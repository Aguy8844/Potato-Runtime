package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import dev.ordovicium.potato.render.backend.target.RenderTargetBackendBridge;
import dev.ordovicium.potato.render.backend.target.RenderTargetBackendState;
import dev.ordovicium.potato.render.backend.target.RenderTargetOperationDispatcher;
import dev.ordovicium.potato.render.backend.target.RenderTargetOwnershipDiagnostics;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-instance backend state seam for Minecraft RenderTarget.
 *
 * <p>Patch 027 keeps every original OpenGL method intact and additionally
 * dispatches completed MainTarget operations into the currently installed
 * backend operation sink.</p>
 */
@Mixin(RenderTarget.class)
abstract class RenderTargetOwnershipMixin
        implements RenderTargetBackendBridge {

    @Shadow
    @Final
    private float[] clearChannels;

    @Unique
    private RenderTargetBackendState
            potato$backendState;

    @Override
    public RenderTargetBackendState
    potato$backendState() {
        if (potato$backendState == null) {
            potato$backendState =
                    new RenderTargetBackendState();
        }

        return potato$backendState;
    }

    @Inject(
            method = "<init>(Z)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$observeConstruction(
            boolean useDepth,
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .initialize(useDepth);

        RenderTargetOwnershipDiagnostics
                .observeRenderTargetInstance();

        potato$snapshot();
    }

    @Inject(
            method = "resize(IIZ)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeResize(
            int width,
            int height,
            boolean clearError,
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeResizeRequest();
    }

    /**
     * _resize is the actual render-thread execution path. Public resize() may
     * merely queue a RenderCall when invoked off-thread.
     */
    @Inject(
            method = "_resize(IIZ)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchCompletedResize(
            int width,
            int height,
            boolean clearError,
            CallbackInfo callbackInfo
    ) {
        potato$snapshot();

        RenderTargetOperationDispatcher
                .resize(
                        potato$backendState(),
                        ((RenderTarget) (Object) this).width,
                        ((RenderTarget) (Object) this).height
                );
    }

    @Inject(
            method = "createBuffers(IIZ)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$beforeCreateBuffers(
            int width,
            int height,
            boolean clearError,
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeCreateBuffers();
    }

    @Inject(
            method = "createBuffers(IIZ)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$afterCreateBuffers(
            int width,
            int height,
            boolean clearError,
            CallbackInfo callbackInfo
    ) {
        potato$snapshot();

        potato$backendState()
                .observeCreateBuffersComplete();
    }

    @Inject(
            method = "destroyBuffers()V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$beforeDestroyBuffers(
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeDestroyBuffers();
    }

    @Inject(
            method = "destroyBuffers()V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$afterDestroyBuffers(
            CallbackInfo callbackInfo
    ) {
        potato$snapshot();

        potato$backendState()
                .observeDestroyBuffersComplete();
    }

    @Inject(
            method = "bindRead()V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeBindRead(
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeBindRead();
    }

    @Inject(
            method = "bindRead()V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchBindRead(
            CallbackInfo callbackInfo
    ) {
        RenderTargetOperationDispatcher
                .bindRead(
                        potato$backendState()
                );
    }

    @Inject(
            method = "unbindRead()V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeUnbindRead(
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeUnbindRead();
    }

    @Inject(
            method = "unbindRead()V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchUnbindRead(
            CallbackInfo callbackInfo
    ) {
        RenderTargetOperationDispatcher
                .unbindRead(
                        potato$backendState()
                );
    }

    @Inject(
            method = "bindWrite(Z)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeBindWrite(
            boolean updateViewport,
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeBindWrite();
    }

    /**
     * As with resize, dispatch from the actual private render-thread path.
     */
    @Inject(
            method = "_bindWrite(Z)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchBindWrite(
            boolean updateViewport,
            CallbackInfo callbackInfo
    ) {
        RenderTargetOperationDispatcher
                .bindWrite(
                        potato$backendState(),
                        updateViewport
                );
    }

    @Inject(
            method = "unbindWrite()V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeUnbindWrite(
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeUnbindWrite();
    }

    @Inject(
            method = "setClearColor(FFFF)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchSetClearColor(
            float red,
            float green,
            float blue,
            float alpha,
            CallbackInfo callbackInfo
    ) {
        RenderTargetOperationDispatcher
                .setClearColor(
                        potato$backendState(),
                        red,
                        green,
                        blue,
                        alpha
                );
    }

    @Inject(
            method = "clear(Z)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeClear(
            boolean clearError,
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeClear();
    }

    @Inject(
            method = "clear(Z)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchClear(
            boolean clearError,
            CallbackInfo callbackInfo
    ) {
        RenderTargetOperationDispatcher
                .clear(
                        potato$backendState(),
                        clearChannels[0],
                        clearChannels[1],
                        clearChannels[2],
                        clearChannels[3]
                );
    }

    @Inject(
            method = "blitToScreen(IIZ)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeBlit(
            int width,
            int height,
            boolean disableBlend,
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeBlit();
    }

    /**
     * _blitToScreen is the actual rendering implementation called by both
     * public overloads.
     */
    @Inject(
            method = "_blitToScreen(IIZ)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchBlit(
            int destinationWidth,
            int destinationHeight,
            boolean disableBlend,
            CallbackInfo callbackInfo
    ) {
        RenderTargetOperationDispatcher
                .blitToScreen(
                        potato$backendState(),
                        destinationWidth,
                        destinationHeight,
                        disableBlend
                );
    }

    @Inject(
            method = "copyDepthFrom(Lcom/mojang/blaze3d/pipeline/RenderTarget;)V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$observeDepthCopy(
            RenderTarget source,
            CallbackInfo callbackInfo
    ) {
        potato$backendState()
                .observeCopyDepth();
    }

    @Inject(
            method = "copyDepthFrom(Lcom/mojang/blaze3d/pipeline/RenderTarget;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$dispatchDepthCopy(
            RenderTarget source,
            CallbackInfo callbackInfo
    ) {
        RenderTargetOperationDispatcher
                .copyDepth(
                        potato$backendState()
                );
    }

    @Unique
    private void potato$snapshot() {
        RenderTarget self =
                (RenderTarget) (Object) this;

        potato$backendState()
                .observeSnapshot(
                        self.width,
                        self.height,
                        self.viewWidth,
                        self.viewHeight,
                        self.frameBufferId,
                        self.getColorTextureId(),
                        self.getDepthTextureId()
                );
    }
}
