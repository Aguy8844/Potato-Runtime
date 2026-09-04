package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.platform.NativeImage;
import dev.ordovicium.potato.render.resource.BlockResourceCapture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mirrors only real Minecraft lightmap uploads.
 *
 * <p>LightTexture.updateLightTexture returns early when its dirty flag is
 * false. Injecting at method RETURN therefore caused one 16x16 copy/CRC per
 * render call. Patch 040d injects immediately after DynamicTexture.upload()
 * instead, so no-op calls cost Potato nothing.</p>
 */
@Mixin(LightTexture.class)
abstract class LightTextureResourceCaptureMixin {

    @Shadow
    @Final
    private NativeImage lightPixels;

    @Inject(
            method = "<init>(Lnet/minecraft/client/renderer/GameRenderer;Lnet/minecraft/client/Minecraft;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$captureInitialLightmap(
            GameRenderer gameRenderer,
            Minecraft minecraft,
            CallbackInfo callbackInfo
    ) {
        potato$captureLightmapSafely(
                "LIGHT_TEXTURE_CONSTRUCTOR"
        );
    }

    @Inject(
            method = "updateLightTexture(F)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;upload()V",
                    shift = At.Shift.AFTER
            ),
            require = 1
    )
    private void potato$captureOnlyAfterRealVanillaUpload(
            float partialTick,
            CallbackInfo callbackInfo
    ) {
        potato$captureLightmapSafely(
                "LIGHT_TEXTURE_REAL_UPLOAD"
        );
    }

    private void potato$captureLightmapSafely(
            String stage
    ) {
        try {
            BlockResourceCapture.captureLightmap(
                    lightPixels
            );
        } catch (Throwable throwable) {
            BlockResourceCapture.recordExternalFailure(
                    stage,
                    throwable
            );
        }
    }
}