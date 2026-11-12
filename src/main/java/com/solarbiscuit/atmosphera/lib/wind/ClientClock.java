package com.solarbiscuit.atmosphera.lib.wind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The client's side of {@link AtmosClock} (split off in 0.9.0, so the clock itself loads on a server): the smoothing.
 * <p>
 * Raw {@code level.getGameTime()} is not smooth on the client: it is overwritten by the server's time packets, and an
 * integrated server that falls behind (worldgen, autosave) catches up by running ticks back to back. With Better Days
 * installed the server sends its time every tick, so a catch-up makes client game time race at several times real
 * speed for seconds, and everything animated from it raced with it.
 * <p>
 * The clock advances with real time while the game is not paused and is only steered towards game time, at most
 * {@link #MAX_SLEW} faster or slower, absorbing an error over about {@link #SLEW_SECONDS}. It snaps only on a new level
 * or an error over {@link #SNAP_SECONDS} (joins, dimension changes), which a loading screen hides.
 * <p>
 * Updated once per frame on the render thread. Never loaded on a dedicated server.
 */
public final class ClientClock {
    public static final double MAX_SLEW = 0.25;
    public static final double SLEW_SECONDS = 8.0;
    public static final double SNAP_SECONDS = 60.0;

    private static long lastNanos;
    // Dev clock log (DevFlags.CLOCK_LOG): per real second, how far game time and this clock moved.
    private static double logReal;
    private static double logGame;
    private static double logClock;
    private static double lastTarget = Double.NaN;
    // Dev clock override (DevFlags.CLOCK_OVERRIDE, 0.6.1): held values, switched every CLOCK_HOLD seconds.
    private static long overrideStart = -1;
    private static int overrideIndex = -1;

    private ClientClock() {
    }

    /** Render thread, once per frame before anything reads the clock. */
    public static void update(Minecraft mc) {
        long now = System.nanoTime();
        float[] held = com.solarbiscuit.atmosphera.lib.client.DevFlags.CLOCK_OVERRIDE;
        if (held.length > 0 && mc.level != null) {
            // Frame-exact test shots: the world clock (and with it the wind, swell heights and Gale's sway) is held.
            if (overrideStart < 0) {
                overrideStart = now;
            }
            int index = (int) Math.min(held.length - 1,
                    (now - overrideStart) / (long) (com.solarbiscuit.atmosphera.lib.client.DevFlags.CLOCK_HOLD * 1e9));
            if (index != overrideIndex) {
                overrideIndex = index;
                com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.info("[dev] clock override now {} s (value {} of {})",
                        held[index], index + 1, held.length);
            }
            AtmosClock.override = held[index];
            AtmosClock.seconds = held[index];
            AtmosClock.tracked = mc.level;
            AtmosClock.delta = 0.0;
            lastNanos = now;
            return;
        }
        AtmosClock.override = Double.NaN;
        double real = lastNanos == 0L ? 0.0 : Math.min((now - lastNanos) / 1.0e9, 0.25);
        lastNanos = now;
        ClientLevel level = mc.level;
        if (level == null) {
            AtmosClock.tracked = null;
            AtmosClock.delta = 0.0;
            return;
        }
        double target = (level.getGameTime() + (double) mc.getFrameTime()) / 20.0;
        double step = mc.isPaused() ? 0.0 : real;
        double seconds = AtmosClock.seconds;
        double error = target - (seconds + step);
        if (level != AtmosClock.tracked || Math.abs(error) > SNAP_SECONDS) {
            AtmosClock.seconds = target;
            AtmosClock.tracked = level;
            AtmosClock.delta = 0.0;
            return;
        }
        double delta = step + Mth.clamp(error / SLEW_SECONDS, -MAX_SLEW, MAX_SLEW) * step;
        AtmosClock.delta = delta;
        AtmosClock.seconds = seconds + delta;
        if (com.solarbiscuit.atmosphera.lib.client.DevFlags.CLOCK_LOG) {
            log(real, target, delta);
        }
    }

    private static void log(double real, double target, double delta) {
        if (!Double.isNaN(lastTarget)) {
            logReal += real;
            logGame += target - lastTarget;
            logClock += delta;
        }
        lastTarget = target;
        if (logReal >= 1.0) {
            com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "[clock] %.2f s real: game time +%.2f s, AtmosClock +%.2f s, game ahead by %+.2f s",
                    logReal, logGame, logClock, target - AtmosClock.seconds));
            logReal = logGame = logClock = 0.0;
        }
    }

    /** For /atmosphera status. Render thread. */
    public static String describe(Level level) {
        float partial = Minecraft.getInstance().getFrameTime();
        double raw = (level.getGameTime() + (double) partial) / 20.0;
        double clock = AtmosClock.worldSeconds(level, partial);
        return String.format(java.util.Locale.ROOT, "World clock: %.1f s (game time %+.2f s from it, slew limit %.0f%%)",
                clock, raw - clock, MAX_SLEW * 100);
    }
}
