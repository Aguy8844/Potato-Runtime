package dev.ordovicium.potato.mixin.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ordovicium.potato.render.vulkan.VulkanGate11ModifierStateBridge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Covers Minecraft 1.21.1 inventory code which polls modifier keys directly
 * through InputConstants.isKeyDown instead of Screen.hasShiftDown().
 *
 * <p>The override exists only inside one Gate-11 deferred Screen dispatch and
 * only for modifier keys queried against Minecraft's real lifecycle window.
 * All ordinary gameplay/native key polling remains vanilla.</p>
 */
@Mixin(InputConstants.class)
public abstract class InputConstantsModifierStateMixin {
    @Inject(
            method = "isKeyDown(JI)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void potato$gate11DirectModifierPoll(
            long window,
            int key,
            CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean override =
                VulkanGate11ModifierStateBridge
                        .directKeyDownOverride(
                                window,
                                key
                        );

        if (override != null) {
            cir.setReturnValue(
                    override
            );
        }
    }
}
