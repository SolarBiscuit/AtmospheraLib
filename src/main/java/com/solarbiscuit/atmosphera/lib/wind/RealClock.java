package com.solarbiscuit.atmosphera.lib.wind;

/**
 * Real time between frames for one caller (0.11.0 "Shared"), for easing that should run on while the game is paused
 * or its world clock stands still. {@link AtmosClock#deltaSeconds()} is world time instead. One instance per caller.
 */
public final class RealClock {
    private final float maxStep;
    private long last;

    /** @param maxStep the longest step returned, in seconds (a hitch or a pause does not jump the easing) */
    public RealClock(float maxStep) {
        this.maxStep = maxStep;
    }

    /** Seconds since the last call (at most {@code maxStep}), or 0 on the first call and after {@link #reset}. */
    public float step() {
        long now = System.nanoTime();
        float dt = last == 0L ? 0f : Math.min(maxStep, (now - last) / 1e9f);
        last = now;
        return dt;
    }

    /** The next {@link #step} returns 0. */
    public void reset() {
        last = 0L;
    }
}
