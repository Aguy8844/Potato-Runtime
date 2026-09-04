package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ordovicium.potato.render.backend.RendererInitializationRehearsal;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * First real high-level renderer initialization ownership cut.
 *
 * <p>The Minecraft call site no longer invokes RenderSystem.initRenderer.
 * Potato's dispatcher performs the explicitly classified transition
 * responsibilities instead.</p>
 */
@Mixin(Minecraft.class)
abstract class MinecraftRendererInitializationMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;initRenderer(IZ)V"
            ),
            require = 1,
            allow = 1
    )
    private void potato$dispatchRendererInitialization(
            int debugVerbosity,
            boolean synchronousDebug
    ) {
        RendererInitializationRehearsal
                .executeDispatchedTransition(
                        debugVerbosity,
                        synchronousDebug
                );
    }
}