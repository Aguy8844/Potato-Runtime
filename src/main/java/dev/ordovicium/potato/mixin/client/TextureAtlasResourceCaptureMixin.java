package dev.ordovicium.potato.mixin.client;

import dev.ordovicium.potato.render.resource.BlockResourceCapture;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the finished Minecraft block-atlas CPU representation after vanilla
 * has completed its normal upload.
 */
@Mixin(TextureAtlas.class)
abstract class TextureAtlasResourceCaptureMixin {

    @Shadow
    @Final
    private ResourceLocation location;

    @Inject(
            method = "upload(Lnet/minecraft/client/renderer/texture/SpriteLoader$Preparations;)V",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$captureFinishedBlockAtlas(
            SpriteLoader.Preparations preparations,
            CallbackInfo callbackInfo
    ) {
        if (!TextureAtlas.LOCATION_BLOCKS.equals(
                location
        )) {
            return;
        }

        try {
            BlockResourceCapture
                    .captureBlockAtlas(
                            (TextureAtlas) (Object) this,
                            preparations
                    );
        } catch (Throwable throwable) {
            /*
             * Capture is diagnostics/resource-bridge work. Never poison
             * Minecraft resource loading because Potato's optional backend
             * snapshot failed.
             */
            BlockResourceCapture
                    .recordExternalFailure(
                            "TEXTURE_ATLAS_MIXIN",
                            throwable
                    );
        }
    }
}