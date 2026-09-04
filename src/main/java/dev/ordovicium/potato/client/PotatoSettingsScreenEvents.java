package dev.ordovicium.potato.client;

import dev.ordovicium.potato.settings.PotatoRuntimeMode;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * Release-facing Potato switch inside Video Settings.
 *
 * <p>There is intentionally no secondary Potato settings screen anymore.
 * The single button persists OFF / ON / DYNAMIC for the next process. A restart
 * marker is shown because Mixin application and renderer ownership are safely
 * startup-latched rather than hot-swapped.</p>
 */
@EventBusSubscriber(
        modid = "potato_runtime",
        value = Dist.CLIENT
)
public final class PotatoSettingsScreenEvents {
    private static final int BUTTON_WIDTH = 170;
    private static final int BUTTON_HEIGHT = 20;
    private static final int MARGIN = 8;

    private PotatoSettingsScreenEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(
            ScreenEvent.Init.Post event
    ) {
        Screen screen = event.getScreen();

        if (!isVideoSettingsScreen(screen)) {
            return;
        }

        Button button =
                Button.builder(
                                label(),
                                ignored -> {
                                    PotatoRuntimeMode
                                            .cycleSelectedMode();

                                    ignored.setMessage(
                                            label()
                                    );
                                }
                        )
                        .bounds(
                                Math.max(
                                        MARGIN,
                                        screen.width
                                                - BUTTON_WIDTH
                                                - MARGIN
                                ),
                                MARGIN,
                                BUTTON_WIDTH,
                                BUTTON_HEIGHT
                        )
                        .build();

        event.addListener(button);
    }

    private static boolean isVideoSettingsScreen(
            Screen screen
    ) {
        if (screen instanceof VideoSettingsScreen) {
            return true;
        }

        String name =
                screen.getClass()
                        .getName();

        return name.equals(
                "net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI"
        ) || name.endsWith(
                ".SodiumOptionsGUI"
        );
    }

    private static Component label() {
        PotatoRuntimeMode selected =
                PotatoRuntimeMode.selectedMode();

        String suffix =
                PotatoRuntimeMode.restartRequired()
                        ? " (restart)"
                        : "";

        return Component.literal(
                "Potato: "
                        + selected.displayName()
                        + suffix
        );
    }
}
