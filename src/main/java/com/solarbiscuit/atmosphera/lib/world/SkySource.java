package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;

/**
 * The drawn sky's colours (0.7.2). A sky mod (Atmosphera 0.3 "Firmament") registers a {@link Provider}; while its feature
 * is active, the frame block's {@code atmos_SkyColor} is the provider's sky colour above the camera instead of
 * vanilla's {@code ClientLevel.getSkyColor}, so water reflections (Torrente) and light (Lumis) follow the sky that is
 * actually on screen. The fog colour stays the game's (biome fog, Atmospherics).
 */
public final class SkySource {
    /** What a sky mod reports, once per frame or more, on the render thread. Keep it cheap. */
    public interface Provider {
        /** The sky's colour high above the camera, linear 0..1 like vanilla's sky colour; false when not drawn now. */
        boolean skyColor(float[] rgb);

        /**
         * The moon's colour as a multiplier on its usual light (0.8.3), for events that change it: a lunar eclipse
         * gives a dim copper red. Return false while the moon is its usual colour.
         */
        default boolean moonTint(float[] rgb) {
            return false;
        }

        /**
         * Light a sky event adds to the night (0.9.2), such as a bright nebula: rgb its colour (linear) times how
         * strongly it shows now, each about 0..1. Return false while there is none. The moon is not taken into account:
         * a light mod weighs it against the moonlight itself.
         */
        default boolean nightLight(float[] rgb) {
            return false;
        }

        /**
         * The colour of direct sunlight at a height (0.10.1): the sun's light after its path through the sky mod's
         * atmosphere to {@code altitude} (blocks, world y), linear, 1 = full noon sun. Reddens and dims towards sunset
         * and goes to 0 once the Earth's shadow covers that height. False when the sky mod does not model it.
         */
        default boolean sunColor(float altitude, float[] rgb) {
            return false;
        }
    }

    private static volatile Provider provider;
    private static volatile AtmosFeature owner;
    private static final float[] RGB = new float[3];
    private static final float[] TINT = new float[3];
    private static final float[] NIGHT = new float[3];
    private static final float[] SUN = new float[3];

    private SkySource() {
    }

    /** Registers the sky provider, active while {@code feature} is active. One provider; the last one wins. */
    public static synchronized void register(AtmosFeature feature, Provider candidate) {
        owner = feature;
        provider = candidate;
    }

    /** True while a provider's feature is active. Any thread. */
    public static boolean active() {
        AtmosFeature feature = owner;
        return provider != null && feature != null && feature.isActive();
    }

    /** Fills out[0..2] with the drawn sky's colour and returns true, or returns false (use vanilla's). Render thread. */
    public static boolean skyColor(float[] out) {
        if (!active() || !provider.skyColor(RGB)) {
            return false;
        }
        out[0] = RGB[0];
        out[1] = RGB[1];
        out[2] = RGB[2];
        return true;
    }

    /**
     * Fills out[0..2] with the moon's tint (0.8.3) and returns true while a sky event changes it; otherwise leaves
     * {@code out} alone and returns false (the moon is its usual colour). For night light (Lumis). Render thread.
     */
    public static boolean moonTint(float[] out) {
        if (!active() || !provider.moonTint(TINT)) {
            return false;
        }
        out[0] = TINT[0];
        out[1] = TINT[1];
        out[2] = TINT[2];
        return true;
    }

    /**
     * Fills out[0..2] with the light a sky event adds to the night (0.9.2, see {@link Provider#nightLight}) and returns
     * true; otherwise leaves {@code out} alone and returns false. For night light (Lumis). Render thread.
     */
    public static boolean nightLight(float[] out) {
        if (!active() || !provider.nightLight(NIGHT)) {
            return false;
        }
        out[0] = NIGHT[0];
        out[1] = NIGHT[1];
        out[2] = NIGHT[2];
        return true;
    }

    /**
     * Fills out[0..2] with the colour of sunlight at {@code altitude} (0.10.1, see {@link Provider#sunColor}) and
     * returns true; otherwise leaves {@code out} alone and returns false (no sky mod, or it does not model it). Shared
     * by clouds and light (Lumis) so both have one sunset colour. Render thread.
     */
    public static boolean sunColor(float altitude, float[] out) {
        if (!active() || !provider.sunColor(altitude, SUN)) {
            return false;
        }
        out[0] = SUN[0];
        out[1] = SUN[1];
        out[2] = SUN[2];
        return true;
    }
}
