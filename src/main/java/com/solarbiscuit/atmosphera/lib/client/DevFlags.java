package com.solarbiscuit.atmosphera.lib.client;

import com.solarbiscuit.atmosphera.lib.options.AtmosPaths;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Developer switches, read once at startup from system properties ({@code -Datmospheralib.debugFeatures=true},
 * {@code -Datmospheralib.statusEvery=N}) or from {@code config/Atmosphera/atmospheralib-dev.properties}
 * ({@code debug_features}, {@code status_every}, {@code clock_log}, {@code shot_file}, {@code hide_gui}, ...). The file exists for launchers that keep JVM arguments in memory.
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
    /**
     * Test screenshots without the HUD ({@code hide_gui=true}, 0.6.2): the GUI is hidden (as with F1) on every tick in
     * a world, which also hides minimap overlays and the hand. For shots of the screen centre (sun, beams).
     */
    public static final boolean HIDE_GUI;
    /** Caps the texture units the lib may assign ({@code max_units}, 0 = the driver's), to test the fallback (0.4.0). */
    public static final int MAX_UNITS;
    /**
     * Test screenshots of animated effects ({@code clock_override=v1,v2,...}, seconds): atmos_Time.x and .y are held
     * at v1, then v2, ..., each for {@code clock_hold} seconds (default 20) from the first frame in a world, the last
     * value staying. Empty = the real clocks. For frame-exact A/Bs of water and the hourly-wrap check (0.4.0).
     */
    public static final float[] CLOCK_OVERRIDE;
    public static final float CLOCK_HOLD;

    static {
        Properties file = new Properties();
        Path path = AtmosPaths.file("atmospheralib-dev.properties");
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
        HIDE_GUI = Boolean.parseBoolean(file.getProperty("hide_gui", "false").trim());
        int units;
        try {
            units = Integer.parseInt(file.getProperty("max_units", "0").trim());
        } catch (NumberFormatException e) {
            units = 0;
        }
        MAX_UNITS = units;
        String clocks = file.getProperty("clock_override", "").trim();
        float[] values = new float[0];
        if (!clocks.isEmpty()) {
            try {
                String[] parts = clocks.split(",");
                values = new float[parts.length];
                for (int i = 0; i < parts.length; i++) {
                    values[i] = Float.parseFloat(parts[i].trim());
                }
            } catch (NumberFormatException e) {
                values = new float[0];
            }
        }
        CLOCK_OVERRIDE = values;
        float hold;
        try {
            hold = Float.parseFloat(file.getProperty("clock_hold", "20").trim());
        } catch (NumberFormatException e) {
            hold = 20f;
        }
        CLOCK_HOLD = Math.max(1f, hold);
    }

    private DevFlags() {
    }
}
