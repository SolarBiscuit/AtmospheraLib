package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;

/**
 * Clouds as other mods see them (0.7.0). A cloud mod (Atmosphera) registers a {@link Provider}; consumers (Lumis's rays,
 * lens flare and sky cover) read how much of the sun is hidden and, in post passes, a screen-space cloud
 * transmittance, without depending on the cloud mod. With no provider, or while its feature is off, everything reads
 * "clear sky": cover 0, transmittance 1.
 * <p>
 * Frame slot {@code atmos_Cloud}: x cover at the sun (0 clear .. 1 hidden), y cover at the moon, z the low deck's base
 * (world y), w 1 while a provider is active. Post programs include {@link #GLSL} after the frame block, bind
 * {@link #transmittanceTexture()} to a unit of their own and set the sampler {@code atmos_CloudT} to it.
 */
public final class CloudCover {
    /** What a cloud mod reports. Called on the render thread, once per frame or more; keep it cheap. */
    public interface Provider {
        /** 0..1: how much of the sun disc the clouds hide (smoothed by the provider). */
        float sunCover();

        /** 0..1: how much of the moon disc the clouds hide. */
        float moonCover();

        /** World y of the lowest cloud deck's base, for consumers that fade effects near it. */
        float deckBase();

        /**
         * The current screen-space cloud texture, or 0 when there is none this frame. Its alpha is the clouds'
         * transmittance along each view ray (1 = clear, 0 = opaque cloud) at the full screen's uv (it may be smaller
         * than the screen; sample it with linear filtering). Its id may change every frame: never cache it.
         */
        int transmittanceTexture();

        /**
         * The cloud shadow map (0.7.1): an R8 texture of the clouds' transmittance along the sun on a horizontal
         * reference plane, world-locked; 0 when there is none this frame. Render thread.
         */
        /** Lightning inside the clouds right now, 0 (none) to about 1.3 (a bolt nearby) (0.8.0). Any thread. */
        default float flash() {
            return 0f;
        }

        default int shadowTexture() {
            return 0;
        }

        /**
         * The shadow map's placement (0.7.1): out[0], out[1] its centre minus the camera (blocks, x and z), out[2] its
         * size (blocks), out[3] the reference plane's world height. Return false while there is no map.
         */
        default boolean shadowParams(float[] out) {
            return false;
        }
    }

    private static volatile Provider provider;
    private static volatile AtmosFeature owner;

    private CloudCover() {
    }

    /** Registers the cloud provider, active while {@code feature} is active. One provider; the last one wins. */
    public static synchronized void register(AtmosFeature feature, Provider candidate) {
        owner = feature;
        provider = candidate;
    }

    /** True while a provider's feature is active. Any thread. */
    public static boolean active() {
        AtmosFeature feature = owner;
        return provider != null && feature != null && feature.isActive();
    }

    public static float sunCover() {
        return active() ? clamp(provider.sunCover()) : 0f;
    }

    public static float moonCover() {
        return active() ? clamp(provider.moonCover()) : 0f;
    }

    /**
     * Lightning inside the clouds (0.8.0, Atmosphera 0.5.0 "Storm"): 0 without a flash, up to about 1.3 for a bolt
     * nearby. Flickers over a few frames. Lumis and others can brighten the sky light by it. Any thread.
     */
    public static float flash() {
        return active() ? Math.max(0f, provider.flash()) : 0f;
    }

    /** The screen-space cloud texture (alpha = transmittance), or 0. Render thread. */
    public static int transmittanceTexture() {
        return active() ? provider.transmittanceTexture() : 0;
    }

    /** The cloud shadow map (0.7.1), or 0 (no provider, feature off, or no map this frame). Render thread. */
    public static int shadowTexture() {
        return active() ? provider.shadowTexture() : 0;
    }

    private static final float[] SHADOW = new float[4];

    /** atmos_CloudShadow (0.7.1): xy map centre minus camera, z 1 / map size, w 1 while a map is published. */
    public static void writeShadowSlot(float[] out) {
        if (!active() || provider.shadowTexture() == 0 || !provider.shadowParams(SHADOW) || SHADOW[2] <= 0f) {
            return;
        }
        out[0] = SHADOW[0];
        out[1] = SHADOW[1];
        out[2] = 1f / SHADOW[2];
        out[3] = 1f;
    }

    /** atmos_CloudShadow2 (0.7.1): x reference plane height, y camera height. Written after the first slot. */
    public static void writeShadowSlot2(float[] out) {
        out[0] = SHADOW[3];
        out[1] = (float) net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition().y;
    }

    /** atmos_Cloud: x sun cover, y moon cover, z deck base, w active. */
    public static void writeSlot(float[] out) {
        if (!active()) {
            return;
        }
        Provider p = provider;
        out[0] = clamp(p.sunCover());
        out[1] = clamp(p.moonCover());
        out[2] = p.deckBase();
        out[3] = p.transmittanceTexture() != 0 ? 1f : 0f;
    }

    private static float clamp(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    /**
     * Post-program GLSL: {@code atmos_cloudTransmittance(uv)} is 1 without clouds (no provider, feature off, no
     * texture this frame) and never samples the texture then, so binding 0 to the unit is fine. Safe to include twice.
     */
    public static final String GLSL = """
            #ifndef ATMOS_CLOUD_DECL
            #define ATMOS_CLOUD_DECL
            uniform sampler2D atmos_CloudT;
            // Cloud transmittance along the view ray through this screen uv: 1 clear, 0 behind opaque cloud.
            float atmos_cloudTransmittance(vec2 atmosUv) {
                return atmos_Cloud.w > 0.5 ? texture(atmos_CloudT, atmosUv).a : 1.0;
            }
            #endif
            """;
}
