package com.solarbiscuit.atmosphera.lib.client;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.AtmosOption;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;

/** AtmospheraLib's own settings ({@code config/SolConfig/atmosphera/atmospheralib-client.toml}). */
public final class LibConfig {
    /** Developer features that exercise the whole API; only declared when {@link DevFlags#DEBUG_FEATURES} is set. */
    public static final boolean DEBUG_FEATURES = DevFlags.DEBUG_FEATURES;

    public static AtmosOption<Boolean> gpuTimers;
    public static AtmosOption<Boolean> dumpShaders;
    public static AtmosOption<Boolean> debugLine;
    public static AtmosOption<com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.Mode> voxyBackend;
    public static AtmosConfig.FeatureSwitch debugLeaf;
    public static AtmosConfig.FeatureSwitch debugWater;
    public static AtmosConfig.FeatureSwitch debugLight;

    private LibConfig() {
    }

    static void init() {
        AtmosConfig config = AtmosConfig.create(AtmospheraLib.MOD_ID, "Atmosphera");
        config.section("general", "AtmospheraLib: shared settings for the Atmosphera mods (Gale, Torrente, Lumis).");
        gpuTimers = config.toggle("gpu_timers", false, ChangeTier.LIVE, "GPU timers",
                "Measure the GPU time of Atmosphera's passes and write it to logs/atmospheralib-gpu.csv every 10 s.");
        debugLine = config.toggle("debug_line", false, ChangeTier.LIVE, "F3 status line",
                "Show how many Atmosphera features run, and the shaderpack state, on the F3 screen.");
        dumpShaders = config.toggle("dump_shaders", false, ChangeTier.RECOMPILE, "Dump patched shaders",
                "Write the patched terrain shaders to logs/atmospheralib-shaders/ when they are compiled (for debugging).");
        voxyBackend = config.choice("voxy_backend", com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.Mode.AUTO, ChangeTier.LIVE,
                "Voxy LOD shading",
                "How Torrente water and Gale sway reach Voxy's distant LOD terrain, so it matches the terrain nearby. "
                        + "AUTO: NeoVoxy's extension API, else patching Voxy's LOD shaders directly. NATIVE / TEXT_PATCH: only that one. "
                        + "OFF: LODs keep Voxy's own look. Restart the game to apply.");
        if (DEBUG_FEATURES) {
            config.section("debug", "Developer test features (only present with debug_features=true in atmospheralib-dev.properties or -Datmospheralib.debugFeatures=true).");
            debugLeaf = config.featureSwitch("leaf_wave", false, "Debug: leaf wave + tint",
                    "Marks leaves with a material bit, sways them with the wind uniform and tints them magenta.");
            debugWater = config.featureSwitch("water_depth", false, "Debug: water depth ramp",
                    "Marks water with a material bit and colours it from cyan (thin) to navy (thick) using the scene depth copy.");
            debugLight = config.featureSwitch("light_bits", false, "Debug: light bits",
                    "Marks light-emitting blocks with light bits: orange on the top half, red on the bottom half (per-vertex bits).");
        }
        config.register();
    }
}
