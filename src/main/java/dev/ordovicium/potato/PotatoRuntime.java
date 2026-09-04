package dev.ordovicium.potato;

import com.mojang.logging.LogUtils;
import dev.ordovicium.potato.diagnostics.PotatoDiagnostics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import org.slf4j.Logger;

@Mod(value = PotatoRuntime.MOD_ID, dist = Dist.CLIENT)
public final class PotatoRuntime {
    public static final String MOD_ID = "potato_runtime";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PotatoRuntime(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[Potato] Bootstrap loaded.");
        modEventBus.addListener(this::onClientSetup);
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        LOGGER.info("[Potato] Client setup reached.");
        event.enqueueWork(PotatoDiagnostics::writeStartupReport);
    }
}
