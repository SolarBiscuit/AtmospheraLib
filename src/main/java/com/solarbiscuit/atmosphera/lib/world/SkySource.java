package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;

/**
 * The drawn sky's colours (0.7.2). A sky mod (Stratos 0.3 "Firmament") registers a {@link Provider}; while its feature
 * is active, the frame block's {@code atmos_SkyColor} is the provider's sky colour above the camera instead of
 * vanilla's {@code ClientLevel.getSkyColor}, so water reflections (Torrente) and light (Lumis) follow the sky that is
 * actually on screen. The fog colour stays the game's (biome fog, Atmospherics).
 */
public final class SkySource {
    /** What a sky mod reports, once per frame or more, on the render thread. Keep it cheap. */
    public interface Provider {
        /** The sky's colour high above the camera, linear 0..1 like vanilla's sky colour; false when not drawn now. */
        boolean skyColor(float[] rgb);
    }

    private static volatile Provider provider;
    private static volatile AtmosFeature owner;
    private static final float[] RGB = new float[3];

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
}
