package com.solarbiscuit.atmosphera.lib.wind;

/** Mutable wind sample, filled by a {@link WindProvider}; reuse one per caller/thread to avoid allocation. */
public final class WindVector {
    /** Unit direction the wind blows towards, on the horizontal plane. */
    public float dirX = 1f;
    public float dirZ;
    /** 0 (calm) to 1 (storm). */
    public float strength;
    /** 0..1, how far into a gust this moment is. */
    public float gust;

    public void set(float dirX, float dirZ, float strength, float gust) {
        this.dirX = dirX;
        this.dirZ = dirZ;
        this.strength = strength;
        this.gust = gust;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT, "dir (%.2f, %.2f) strength %.2f gust %.2f", dirX, dirZ, strength, gust);
    }
}
