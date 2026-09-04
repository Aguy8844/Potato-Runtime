package dev.ordovicium.potato.mixin.client;

import dev.ordovicium.potato.render.visibility.ChunkWorkBudgetPolicy;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Queue;

/**
 * Converts SectionRenderDispatcher.uploadAllPendingUploads() from an
 * unbounded queue drain into a small frame-budgeted drain.
 *
 * <p>Returning null from the redirected Queue.poll() terminates vanilla's
 * existing loop. Remaining Runnables stay in the same queue for the next
 * render pass.</p>
 */
@Mixin(SectionRenderDispatcher.class)
abstract class SectionRenderDispatcherUploadBudgetMixin {

    @Inject(
            method = "uploadAllPendingUploads()V",
            at = @At("HEAD"),
            require = 1
    )
    private void potato$beginUploadBudget(
            CallbackInfo callbackInfo
    ) {
        ChunkWorkBudgetPolicy
                .beginUploadPass();
    }

    @Redirect(
            method = "uploadAllPendingUploads()V",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Queue;poll()Ljava/lang/Object;"
            ),
            require = 1
    )
    private Object potato$budgetUploadPoll(
            Queue<?> queue
    ) {
        return ChunkWorkBudgetPolicy
                .pollUpload(
                        queue
                );
    }
}