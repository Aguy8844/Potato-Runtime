package dev.ordovicium.potato.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;
import dev.ordovicium.potato.diagnostics.PotatoWindowBootstrapTrace;
import dev.ordovicium.potato.render.vulkan.VulkanContextBootstrapRehearsal;
import dev.ordovicium.potato.render.vulkan.VulkanEarlyDeviceLimits;
import dev.ordovicium.potato.render.vulkan.VulkanHandoffCandidate;
import org.lwjgl.opengl.GLCapabilities;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * NeoForge/Minecraft window bootstrap seam plus context-bootstrap rehearsal.
 *
 * <p>Patch 018 still returns the original OpenGL EarlyDisplay window. It
 * verifies that Minecraft's three immediate OpenGL bootstrap assumptions can
 * be removed without breaking the baseline.</p>
 */
@Mixin(Window.class)
abstract class WindowBootstrapMixin {
    @WrapOperation(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/fml/loading/ImmediateWindowHandler;setupMinecraftWindow(Ljava/util/function/IntSupplier;Ljava/util/function/IntSupplier;Ljava/util/function/Supplier;Ljava/util/function/LongSupplier;)J"
            )
    )
    private long potato$observeAndPrepareReplacement(
            IntSupplier width,
            IntSupplier height,
            Supplier<String> title,
            LongSupplier monitor,
            Operation<Long> original
    ) {
        PotatoWindowBootstrapTrace
                .beforeMinecraftWindowHandoff();

        long earlyDisplayWindow =
                original.call(
                        width,
                        height,
                        title,
                        monitor
                );

        PotatoWindowBootstrapTrace
                .afterMinecraftWindowHandoff(
                        earlyDisplayWindow
                );

        VulkanHandoffCandidate.prepare(
                earlyDisplayWindow
        );

        /*
         * Capture the Vulkan image-dimension limit before Minecraft reaches
         * RenderSystem.maxSupportedTextureSize().
         */
        VulkanEarlyDeviceLimits.capture();

        return earlyDisplayWindow;
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/glfw/GLFW;glfwMakeContextCurrent(J)V"
            ),
            require = 1,
            allow = 1
    )
    private void potato$rehearseContextCurrentBypass(
            long window
    ) {
        VulkanContextBootstrapRehearsal
                .makeContextCurrent(window);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lorg/lwjgl/opengl/GL;createCapabilities()Lorg/lwjgl/opengl/GLCapabilities;"
            ),
            require = 1,
            allow = 1
    )
    private GLCapabilities potato$rehearseCapabilitiesReuse() {
        return VulkanContextBootstrapRehearsal
                .createCapabilities();
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/systems/RenderSystem;maxSupportedTextureSize()I"
            ),
            require = 1,
            allow = 1
    )
    private int potato$rehearseVulkanWindowLimit() {
        return VulkanContextBootstrapRehearsal
                .maxSupportedTextureSize();
    }

    @Inject(
            method = "<init>",
            at = @At("RETURN"),
            require = 1
    )
    private void potato$observeCompletedWindowBootstrap(
            CallbackInfo callbackInfo
    ) {
        VulkanContextBootstrapRehearsal
                .afterWindowConstructor();

        PotatoWindowBootstrapTrace
                .afterWindowConstructor();
    }
}