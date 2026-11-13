package com.solarbiscuit.atmosphera.lib;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AtmospheraLib: the shared library of the Atmosphera mods (Gale, Torrente, Lumis, Atmosphera, Stratos).
 * The rendering is client-only. Since 0.9.0 a small common side also loads on a dedicated server: the world clock, the
 * wind and weather services, and the networking that carries the server's weather and settings to clients. The
 * library stays optional on both sides.
 */
@Mod(AtmospheraLib.MOD_ID)
public final class AtmospheraLib {
    public static final String MOD_ID = "atmospheralib";
    public static final Logger LOGGER = LoggerFactory.getLogger("AtmospheraLib");

    public AtmospheraLib() {
        // 0.9.0 common side: the channel and its messages are registered on both sides. The offline shader check
        // (tools/voxy-glsl-check) builds the mods outside Forge's networking and skips it.
        if (!Boolean.getBoolean("atmosphera.offlineDump")) {
            com.solarbiscuit.atmosphera.lib.net.AtmosNet.init();
        }
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.info("AtmospheraLib on a dedicated server: clock, wind, weather and networking only (no rendering).");
            return;
        }
        AtmosClient.init(FMLJavaModLoadingContext.get().getModEventBus());
    }

    public static String version() {
        return ModList.get().getModContainerById(MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }
}
