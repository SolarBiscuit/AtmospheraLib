package com.solarbiscuit.atmosphera.lib.wind;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * Smoothed world clock for everything that animates with world time (waving, waves, wind gusts).
 * <p>
 * Raw {@code level.getGameTime()} is not smooth on the client: it is overwritten by the server's time packets, and an
 * integrated server that falls behind (worldgen, autosave) catches up by running ticks back to back. With Better Days
 * installed the server sends its time every tick, so a catch-up makes client game time race at several times real
 * speed for seconds, and everything animated from it raced with it.
 * <p>
 * This clock advances with real time while the game is not paused and is only steered towards game time, at most
 * {@link #MAX_SLEW} faster or slower, absorbing an error over about {@link #SLEW_SECONDS}. It snaps only on a new level
 * or an error over {@link #SNAP_SECONDS} (joins, dimension changes), which a loading screen hides.
 * <p>
 * Updated once per frame on the render thread; {@link #worldSeconds} is a volatile read, safe from any thread.
 */
public final class AtmosClock {
    public static final double MAX_SLEW = 0.25;
    public static final double SLEW_SECONDS = 8.0;
    public static final double SNAP_SECONDS = 60.0;

    private static volatile double seconds;
    private static volatile Level tracked;
    // Render thread only.
    private static double delta;
    private static long lastNanos;
    // Dev clock log (DevFlags.CLOCK_LOG): per real second, how far game time and this clock moved.
    private static double logReal;
    private static double logGame;
    private static double logClock;
    private static double lastTarget = Double.NaN;

    private AtmosClock() {
    }

    /** Render thread, once per frame before anything reads the clock. */
    public static void update(Minecraft mc) {
        long now = System.nanoTime();
        double real = lastNanos == 0L ? 0.0 : Math.min((now - lastNanos) / 1.0e9, 0.25);
        lastNanos = now;
        ClientLevel level = mc.level;
        if (level == null) {
            tracked = null;
            delta = 0.0;
            return;
        }
        double target = (level.getGameTime() + (double) mc.getFrameTime()) / 20.0;
        double step = mc.isPaused() ? 0.0 : real;
        double error = target - (seconds + step);
        if (level != tracked || Math.abs(error) > SNAP_SECONDS) {
            seconds = target;
            tracked = level;
            delta = 0.0;
            return;
        }
        delta = step + Mth.clamp(error / SLEW_SECONDS, -MAX_SLEW, MAX_SLEW) * step;
        seconds = seconds + delta;
        if (com.solarbiscuit.atmosphera.lib.client.DevFlags.CLOCK_LOG) {
            log(real, target);
        }
    }

    private static void log(double real, double target) {
        if (!Double.isNaN(lastTarget)) {
            logReal += real;
            logGame += target - lastTarget;
            logClock += delta;
        }
        lastTarget = target;
        if (logReal >= 1.0) {
            com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.info(String.format(java.util.Locale.ROOT,
                    "[clock] %.2f s real: game time +%.2f s, AtmosClock +%.2f s, game ahead by %+.2f s",
                    logReal, logGame, logClock, target - seconds));
            logReal = logGame = logClock = 0.0;
        }
    }

    /** For /atmosphera status. Render thread. */
    public static String describe(Level level) {
        double raw = (level.getGameTime() + (double) Minecraft.getInstance().getFrameTime()) / 20.0;
        return String.format(java.util.Locale.ROOT, "World clock: %.1f s (game time %+.2f s from it, slew limit %.0f%%)",
                worldSeconds(level, Minecraft.getInstance().getFrameTime()), raw - worldSeconds(level, Minecraft.getInstance().getFrameTime()),
                MAX_SLEW * 100);
    }

    /** Smoothed world seconds of the client level; raw game time for any other level. Any thread. */
    public static double worldSeconds(Level level, float partialTick) {
        return level == tracked ? seconds : (level.getGameTime() + (double) partialTick) / 20.0;
    }

    /** World seconds the clock advanced this frame (0 while paused). Render thread. */
    public static double deltaSeconds() {
        return delta;
    }
}
