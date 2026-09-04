package dev.ordovicium.potato.mixin.client;

import dev.ordovicium.potato.render.vulkan.VulkanGate11ModifierStateBridge;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Supplies the exact modifier mask captured with a Gate-11 NO_API-window mouse
 * event while that event is dispatched to Minecraft's current Screen.
 *
 * <p>Outside the tightly scoped deferred Screen call the bridge returns null and
 * vanilla Screen modifier queries execute unchanged.</p>
 */
@Mixin(Screen.class)
public abstract class ScreenModifierStateMixin {
    @Inject(
            method = "hasShiftDown",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void potato$gate11ShiftDown(
            CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean override =
                VulkanGate11ModifierStateBridge
                        .shiftDownOverride();

        if (override != null) {
            cir.setReturnValue(
                    override
            );
        }
    }

    @Inject(
            method = "hasControlDown",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void potato$gate11ControlDown(
            CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean override =
                VulkanGate11ModifierStateBridge
                        .controlDownOverride();

        if (override != null) {
            cir.setReturnValue(
                    override
            );
        }
    }

    @Inject(
            method = "hasAltDown",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void potato$gate11AltDown(
            CallbackInfoReturnable<Boolean> cir
    ) {
        Boolean override =
                VulkanGate11ModifierStateBridge
                        .altDownOverride();

        if (override != null) {
            cir.setReturnValue(
                    override
            );
        }
    }
}
