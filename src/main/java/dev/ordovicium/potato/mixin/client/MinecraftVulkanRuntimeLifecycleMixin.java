package dev.ordovicium.potato.mixin.client;

import dev.ordovicium.potato.render.visibility.PotatoChunkCompileController;
import dev.ordovicium.potato.render.vulkan.VulkanRuntimeManager;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runtime-lifetime shutdown seam for Potato's persistent Vulkan backend
 * skeleton.
 */
@Mixin(Minecraft.class)
abstract class MinecraftVulkanRuntimeLifecycleMixin {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/RenderBuffers;<init>(I)V"
            ),
            index = 0,
            require = 1
    )
    private int potato$expandParallelSectionBuilderPool(
            int vanillaRequested
    ) {
        return PotatoChunkCompileController
                .adjustBuilderPoolSize(
                        vanillaRequested
                );
    }

    @Inject(
            method = "stop()V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$closePersistentVulkanRuntime(
            CallbackInfo callbackInfo
    ) {
        try {
            VulkanRuntimeManager
                    .closeFromMinecraftStop();
        } finally {
            PotatoChunkCompileController
                    .close();
        }
    }
}
