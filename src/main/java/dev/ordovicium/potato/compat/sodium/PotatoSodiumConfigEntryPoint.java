package dev.ordovicium.potato.compat.sodium;

import dev.ordovicium.potato.settings.PotatoRuntimeMode;
import net.caffeinemc.mods.sodium.api.config.ConfigEntryPoint;
import net.caffeinemc.mods.sodium.api.config.structure.ConfigBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Optional Sodium Config API bridge.
 *
 * <p>Sodium replaces Minecraft's normal Video Settings screen. Registering
 * through Sodium's public API keeps Potato out of Sodium GUI internals while
 * preserving PotatoRuntimeMode as the single source of truth.</p>
 */
public final class PotatoSodiumConfigEntryPoint
        implements ConfigEntryPoint {

    private static final ResourceLocation OPTION_ID =
            ResourceLocation.parse(
                    "potato_runtime:runtime_mode"
            );

    @Override
    public void registerConfigLate(
            ConfigBuilder builder
    ) {
        builder.registerOwnModOptions()
                .setIcon(
                        ResourceLocation.parse(
                                "potato_runtime:textures/gui/potato_logo_single.png"
                        )
                )
                .addPage(
                        builder.createOptionPage()
                                .setName(
                                        Component.literal(
                                                "Potato Runtime"
                                        )
                                )
                                .addOptionGroup(
                                        builder.createOptionGroup()
                                                .addOption(
                                                        builder.createEnumOption(
                                                                        OPTION_ID,
                                                                        PotatoRuntimeMode.class
                                                                )
                                                                .setName(
                                                                        Component.literal(
                                                                                "Potato"
                                                                        )
                                                                )
                                                                .setTooltip(
                                                                        mode -> Component.literal(
                                                                                tooltip(mode)
                                                                        )
                                                                )
                                                                .setStorageHandler(
                                                                        () -> {
                                                                            /*
                                                                             * Persistence belongs to
                                                                             * PotatoRuntimeMode's existing
                                                                             * release-facing mutator.
                                                                             */
                                                                        }
                                                                )
                                                                .setBinding(
                                                                        PotatoSodiumConfigEntryPoint::setSelectedMode,
                                                                        PotatoRuntimeMode::selectedMode
                                                                )
                                                                .setDefaultValue(
                                                                        PotatoRuntimeMode.DYNAMIC
                                                                )
                                                                .setElementNameProvider(
                                                                        mode -> Component.literal(
                                                                                mode.name()
                                                                        )
                                                                )
                                                )
                                )
                );
    }

    private static String tooltip(
            PotatoRuntimeMode mode
    ) {
        return switch (mode) {
            case DYNAMIC ->
                    "Automatic Potato Runtime policy. Recommended. "
                            + "Mode changes become active after restart.";
            case ON ->
                    "Keep Potato Runtime enabled with hardware-safe automatic "
                            + "resource sizing but without the dynamic soft-view policy. "
                            + "Mode changes become active after restart.";
            case OFF ->
                    "Disable Potato runtime mixins on the next start for a clean "
                            + "A/B baseline. The Potato jar itself remains loaded. "
                            + "Mode changes become active after restart.";
        };
    }

    private static void setSelectedMode(
            PotatoRuntimeMode requested
    ) {
        for (int attempt = 0;
             attempt < PotatoRuntimeMode.values().length
                     && PotatoRuntimeMode.selectedMode() != requested;
             attempt++) {
            PotatoRuntimeMode.cycleSelectedMode();
        }

        if (PotatoRuntimeMode.selectedMode() != requested) {
            throw new IllegalStateException(
                    "Potato Runtime mode cycling did not reach " + requested
            );
        }
    }
}