package dev.ordovicium.potato.mixin.client;

import dev.ordovicium.potato.render.visibility.PotatoChunkCompileController;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.concurrent.Executor;

/**
 * Keeps SectionRenderDispatcher's task and priority model intact while
 * replacing only its asynchronous executor.
 */
@Mixin(SectionRenderDispatcher.class)
abstract class SectionRenderDispatcherCompileExecutorMixin {

    @ModifyVariable(
            method = "<init>",
            at = @At("HEAD"),
            argsOnly = true,
            ordinal = 0,
            require = 1
    )
    private static Executor potato$useParallelTerrainCompileExecutor(
            Executor vanillaExecutor
    ) {
        return PotatoChunkCompileController
                .wrapCompileExecutor(
                        vanillaExecutor
                );
    }
}
