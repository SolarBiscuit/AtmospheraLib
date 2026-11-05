package com.solarbiscuit.atmosphera.lib.compat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraftforge.fml.loading.LoadingModList;
import net.minecraftforge.fml.loading.moddiscovery.ModFileInfo;
import net.minecraftforge.forgespi.language.IModInfo;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which relevant mods are installed, resolved once from {@link LoadingModList} (works before ModList exists, so the
 * mixin plugin can use it), plus a few live probes. New mod-gated features must add their mod id to {@link #KEY_MODS}.
 */
public final class AtmosCompat {
    public static final String[] KEY_MODS = {
            "embeddium", "xenon", "oculus", "amdium", "voxy", "atmospherics", "bathymetry", "cheaplighting",
            "fallingleavesforged", "solix", "collections_of_optimizations", "particle_core", "distanthorizons",
            "badoptimizations", "polytone"
    };
    // Own logger: this class is also used by the mixin plugin, before the mod class may be loaded.
    private static final Logger LOGGER = LoggerFactory.getLogger("AtmospheraLib");
    private static final String SODIUM_TERRAIN_SHADER = "assets/sodium/shaders/blocks/block_layer_opaque.vsh";

    private static volatile Map<String, String> present;
    private static volatile String shaderOverride;
    private static Field amdiumEnabled;
    private static boolean amdiumProbed;

    private AtmosCompat() {
    }

    public static boolean isLoaded(String modId) {
        return mods().containsKey(modId);
    }

    /** Any mod id, not only {@link #KEY_MODS} (used for data-driven overlap lists). */
    public static boolean isLoadedAny(String modId) {
        if (isLoaded(modId)) {
            return true;
        }
        LoadingModList list = LoadingModList.get();
        return list != null && list.getModFileById(modId) != null;
    }

    public static String version(String modId) {
        return mods().get(modId);
    }

    /** True when a Sodium port with the Embeddium API (Xenon, Embeddium) is installed. */
    public static boolean hasSodiumPort() {
        return isLoaded("embeddium");
    }

    public static Map<String, String> mods() {
        Map<String, String> map = present;
        if (map == null) {
            synchronized (AtmosCompat.class) {
                map = present;
                if (map == null) {
                    map = resolve();
                    present = map;
                }
            }
        }
        return map;
    }

    private static Map<String, String> resolve() {
        Map<String, String> map = new LinkedHashMap<>();
        LoadingModList list = LoadingModList.get();
        if (list == null) {
            return map;
        }
        for (String id : KEY_MODS) {
            ModFileInfo file = list.getModFileById(id);
            if (file == null) {
                continue;
            }
            String version = "?";
            for (IModInfo mod : file.getMods()) {
                if (id.equals(mod.getModId())) {
                    version = mod.getVersion().toString();
                }
            }
            map.put(id, version);
        }
        String override = null;
        for (ModFileInfo file : list.getModFiles()) {
            boolean sodiumPort = file.getMods().stream().anyMatch(m -> "embeddium".equals(m.getModId()));
            if (sodiumPort) {
                continue;
            }
            Path resource = file.getFile().findResource(SODIUM_TERRAIN_SHADER);
            if (resource != null && Files.exists(resource)) {
                override = file.getFile().getFileName();
            }
        }
        shaderOverride = override;
        if (override != null) {
            LOGGER.warn("{} ships its own copy of Xenon/Embeddium's terrain shader; Atmosphera terrain "
                    + "features stay off (their shader anchors cannot be trusted).", override);
        }
        return map;
    }

    /** Jar shipping {@code assets/sodium/shaders/blocks/block_layer_opaque.vsh} other than the Sodium port, or null. */
    public static String sodiumShaderOverride() {
        mods();
        return shaderOverride;
    }

    /** AMDium draws terrain with its own mesh shaders when enabled, bypassing Xenon's DefaultChunkRenderer. */
    public static boolean isAmdiumActive() {
        if (!isLoaded("amdium")) {
            return false;
        }
        if (!amdiumProbed) {
            amdiumProbed = true;
            try {
                amdiumEnabled = Class.forName("me.cortex.amdium.Nvidium").getField("IS_ENABLED");
            } catch (ReflectiveOperationException | LinkageError e) {
                LOGGER.warn("Could not read AMDium's state ({}); assuming it is active.", e.toString());
            }
        }
        try {
            return amdiumEnabled == null || amdiumEnabled.getBoolean(null);
        } catch (IllegalAccessException e) {
            return true;
        }
    }

    public static List<String> describe() {
        return mods().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).toList();
    }
}
