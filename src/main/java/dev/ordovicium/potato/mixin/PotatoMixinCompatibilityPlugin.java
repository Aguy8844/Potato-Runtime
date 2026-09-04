package dev.ordovicium.potato.mixin;

import dev.ordovicium.potato.settings.PotatoRuntimeMode;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.net.URL;
import java.util.List;
import java.util.Set;

/**
 * Narrow renderer-ownership compatibility boundary.
 *
 * <p>Sodium replaces Minecraft's LevelRenderer world-rendering implementation.
 * Potato's vanilla LevelRenderer hooks intentionally fail open in that topology
 * instead of injecting against Sodium internals.</p>
 *
 * <p>Iris also depends on the normal Minecraft/OpenGL renderer-initialization
 * lifecycle. When Iris is present, Potato deliberately leaves
 * RenderSystem.initRenderer under vanilla/Iris ownership instead of redirecting
 * it into Potato's Vulkan migration rehearsal. This keeps Iris GLDebug and
 * renderer state initialization ordered before RenderTarget allocation.</p>
 */
public final class PotatoMixinCompatibilityPlugin implements IMixinConfigPlugin {

    private static final String SECTION_LAYER_MIXIN =
            "dev.ordovicium.potato.mixin.client.LevelRendererSectionLayerDrawMixin";

    private static final String CHUNK_BUDGET_MIXIN =
            "dev.ordovicium.potato.mixin.client.LevelRendererChunkWorkBudgetMixin";

    private static final String RENDERER_INITIALIZATION_MIXIN =
            "dev.ordovicium.potato.mixin.client.MinecraftRendererInitializationMixin";

    private boolean sodiumRendererPresent;
    private boolean irisRendererPresent;
    private PotatoRuntimeMode runtimeMode;

    @Override
    public void onLoad(String mixinPackage) {
        runtimeMode = PotatoRuntimeMode.startupMode();
        sodiumRendererPresent = detectSodiumRenderer();
        irisRendererPresent = detectIrisRenderer();

        System.out.println(
                "[Potato/Mode] startup=" + runtimeMode.name()
                        + " mixins=" + (runtimeMode.enabled() ? "ENABLED" : "DISABLED")
        );

        if (sodiumRendererPresent) {
            System.out.println(
                    "[Potato/Compat] Sodium renderer detected: "
                            + "vanilla LevelRenderer ownership hooks will fail open."
            );
        }

        if (irisRendererPresent) {
            System.out.println(
                    "[Potato/Compat] Iris detected: "
                            + "vanilla OpenGL renderer initialization will remain authoritative."
            );
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(
            String targetClassName,
            String mixinClassName
    ) {
        if (runtimeMode == PotatoRuntimeMode.OFF) {
            return false;
        }

        if (sodiumRendererPresent
                && (SECTION_LAYER_MIXIN.equals(mixinClassName)
                || CHUNK_BUDGET_MIXIN.equals(mixinClassName))) {
            System.out.println(
                    "[Potato/Compat] Skipping Sodium-incompatible mixin: "
                            + mixinClassName
            );
            return false;
        }

        if (irisRendererPresent
                && RENDERER_INITIALIZATION_MIXIN.equals(mixinClassName)) {
            System.out.println(
                    "[Potato/Compat] Skipping Iris-incompatible renderer-init ownership mixin: "
                            + mixinClassName
            );
            return false;
        }

        return true;
    }

    @Override
    public void acceptTargets(
            Set<String> myTargets,
            Set<String> otherTargets
    ) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo
    ) {
    }

    @Override
    public void postApply(
            String targetClassName,
            ClassNode targetClass,
            String mixinClassName,
            IMixinInfo mixinInfo
    ) {
    }

    private static boolean detectSodiumRenderer() {
        return resourceVisible("sodium-common.mixins.json")
                || resourceVisible("sodium-neoforge.mixins.json")
                || resourceVisible(
                        "net/caffeinemc/mods/sodium/mixin/core/render/world/"
                                + "LevelRendererMixin.class"
                );
    }

    private static boolean detectIrisRenderer() {
        return resourceVisible("mixins.iris.json")
                || resourceVisible("mixins.iris.forge.json")
                || resourceVisible("net/irisshaders/iris/Iris.class")
                || resourceVisible("net/irisshaders/iris/gl/GLDebug.class");
    }

    private static boolean resourceVisible(String resourceName) {
        ClassLoader ownLoader =
                PotatoMixinCompatibilityPlugin.class.getClassLoader();

        if (hasResource(ownLoader, resourceName)) {
            return true;
        }

        ClassLoader contextLoader =
                Thread.currentThread().getContextClassLoader();

        return contextLoader != ownLoader
                && hasResource(contextLoader, resourceName);
    }

    private static boolean hasResource(
            ClassLoader loader,
            String resourceName
    ) {
        if (loader == null) {
            return false;
        }

        try {
            URL resource = loader.getResource(resourceName);
            return resource != null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
