package com.solarbiscuit.atmosphera.lib.client;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Developer switches, read once at startup from system properties ({@code -Datmospheralib.debugFeatures=true},
 * {@code -Datmospheralib.statusEvery=N}) or from {@code config/SolConfig/atmosphera/atmospheralib-dev.properties}
 * ({@code debug_features}, {@code status_every}, {@code clock_log}). The file exists for launchers that keep JVM arguments in memory.
 */
public final class DevFlags {
    public static final boolean DEBUG_FEATURES;
    public static final int STATUS_EVERY;
    /** Logs the world clock once a second: raw game time vs the smoothed AtmosClock. */
    public static final boolean CLOCK_LOG;
    /**
     * Test screenshots without keyboard focus: while set, a file {@code atmos_shot.request} in the game directory is
     * taken as a request (deleted, then a screenshot saved as with F2). For tools that cannot focus the window.
     */
    public static final boolean SHOT_FILE;

    static {
        Properties file = new Properties();
        Path path = FMLPaths.CONFIGDIR.get().resolve("SolConfig").resolve("atmosphera").resolve("atmospheralib-dev.properties");
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                file.load(reader);
            } catch (IOException ignored) {
                // No dev flags.
            }
        }
        DEBUG_FEATURES = Boolean.getBoolean("atmospheralib.debugFeatures")
                || Boolean.parseBoolean(file.getProperty("debug_features", "false").trim());
        int every = Integer.getInteger("atmospheralib.statusEvery", 0);
        if (every == 0) {
            try {
                every = Integer.parseInt(file.getProperty("status_every", "0").trim());
            } catch (NumberFormatException e) {
                every = 0;
            }
        }
        STATUS_EVERY = every;
        CLOCK_LOG = Boolean.parseBoolean(file.getProperty("clock_log", "false").trim());
        SHOT_FILE = Boolean.parseBoolean(file.getProperty("shot_file", "false").trim());
    }

    private DevFlags() {
    }
}
