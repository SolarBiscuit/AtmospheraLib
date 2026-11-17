package com.solarbiscuit.atmosphera.lib.util;

/**
 * Small maths the Atmosphera mods share (0.11.0 "Shared"; each mod had its own copies). Pure functions, any thread,
 * no allocation. Import them statically: {@code import static com.solarbiscuit.atmosphera.lib.util.AtmosMath.lerp;}.
 */
public final class AtmosMath {
    private AtmosMath() {
    }

    /** {@code a} at {@code t = 0} to {@code b} at {@code t = 1} (not clamped). */
    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 0 to 1, clamped. */
    public static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /** Hermite step from {@code a} to {@code b}, as GLSL's {@code smoothstep}. */
    public static float smoothstep(float a, float b, float x) {
        float t = clamp01((x - a) / (b - a));
        return t * t * (3f - 2f * t);
    }

    /**
     * Exponential easing: {@code value} moved towards {@code target} as over {@code dt} seconds of a first-order lag
     * with time constant {@code tau} (63% of the way after {@code tau}). Frame-rate independent.
     */
    public static float approach(float value, float target, float dt, float tau) {
        return value + (target - value) * (1f - (float) Math.exp(-dt / tau));
    }

    /** SplitMix64's finaliser: a well-mixed 64-bit hash of {@code z}. */
    public static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** The top 24 bits of a hash as a uniform number in [0, 1). */
    public static float unit(long h) {
        return (h >>> 40) / (float) (1L << 24);
    }

    /** Rec. 709 luminance of a linear colour (the weights every Atmosphera mod and shader use). */
    public static float luminance(float r, float g, float b) {
        return 0.2126f * r + 0.7152f * g + 0.0722f * b;
    }
}
