package com.solarbiscuit.atmosphera.lib.wind;

import net.minecraft.world.level.Level;

/**
 * World clock for everything that follows world time (waving, waves, wind gusts, weather cells). Common code since
 * 0.9.0: it loads on a dedicated server too.
 * <p>
 * On the client the clock of the level being drawn is smoothed ({@link ClientClock}, once per frame): raw
 * {@code level.getGameTime()} is overwritten by the server's time packets and can race for seconds after a server
 * hitch. Any other level, and every level on a server, reads raw game time: {@code gameTime / 20}. The two differ by
 * the smoothing's lag, usually well under a tick.
 * <p>
 * {@link #worldSeconds} is a volatile read, safe from any thread.
 */
public final class AtmosClock {
    static volatile double seconds;
    static volatile Level tracked;
    // Render thread only (written by ClientClock).
    static double delta;
    static double override = Double.NaN;

    private AtmosClock() {
    }

    /** The held clock value while the dev clock override is on (NaN otherwise). Render thread. */
    public static double overrideSeconds() {
        return override;
    }

    /** Smoothed world seconds of the client's level; raw game time for any other level and on a server. Any thread. */
    public static double worldSeconds(Level level, float partialTick) {
        return level == tracked ? seconds : (level.getGameTime() + (double) partialTick) / 20.0;
    }

    /** World seconds the client's clock advanced this frame (0 while paused, and always 0 on a server). Render thread. */
    public static double deltaSeconds() {
        return delta;
    }
}
