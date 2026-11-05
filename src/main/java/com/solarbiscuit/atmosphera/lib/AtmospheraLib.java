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
 * AtmospheraLib: the shared client rendering library of the Atmosphera mods (Gale, Torrente, Lumis).
 * Client-only; on a dedicated server it does nothing.
 */
@Mod(AtmospheraLib.MOD_ID)
public final class AtmospheraLib {
    public static final String MOD_ID = "atmospheralib";
    public static final Logger LOGGER = LoggerFactory.getLogger("AtmospheraLib");

    public AtmospheraLib() {
        if (FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            LOGGER.info("AtmospheraLib is client-only; inactive on this dedicated server.");
            return;
        }
        AtmosClient.init(FMLJavaModLoadingContext.get().getModEventBus());
    }

    public static String version() {
        return ModList.get().getModContainerById(MOD_ID).map(c -> c.getModInfo().getVersion().toString()).orElse("?");
    }
}
