package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.AtmosGl;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import org.lwjgl.opengl.GL11C;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * The drawn sky as a map (0.8.0, for Stratos 0.4.0 "Mirror"): a latitude-longitude texture of the upper hemisphere,
 * sky and clouds together, in the game's display values like {@code atmos_SkyColor}. Water reflections (Torrente) read
 * it with {@code vec3 atmos_skyView(vec3 dir)}; without a sky mod the function falls back to the fog-to-sky-colour
 * blend water used before.
 * <p>
 * Layout: u = atan2(z, x) / 2pi + 0.5 (world azimuth), v = sqrt(elevation / (pi / 2)) from the horizon (v = 0) to the
 * zenith (v = 1); directions below the horizon read the horizon row. Linear filtering, repeat across u.
 * <ul>
 *   <li>Sky mod: {@link #register(AtmosFeature, IntSupplier)} with a supplier of the texture (0 while not drawn).</li>
 *   <li>Terrain and Voxy LOD features: {@link #requireInTerrain(AtmosFeature)} during mod construction; the lib feature
 *       {@code atmospheralib:sky_view} compiles the function into the fragment stage (guard with
 *       {@code #ifdef ATMOS_SKY_VIEW}) and binds the map (a terrain sampler unit; unit {@value #LOD_UNIT} for LODs).</li>
 *   <li>Post programs: include {@link #GLSL}, bind {@link #texture()} to a unit of your own and set
 *       {@code atmos_SkyViewMap}.</li>
 * </ul>
 * Frame slot {@code atmos_SkyView}: x 1 while a map is published.
 */
public final class SkyView {
    /** Texture unit of the map in Voxy LOD programs (cloud shadows use 10, Lumis 13-15, NeoVoxy 0-7). */
    public static final int LOD_UNIT = 11;

    private static volatile IntSupplier provider;
    private static volatile AtmosFeature owner;
    private static final List<AtmosFeature> CONSUMERS = new ArrayList<>();
    private static AtmosFeature feature;

    private SkyView() {
    }

    /** The sky mod's map. One provider; the last one wins. */
    public static synchronized void register(AtmosFeature ownerFeature, IntSupplier texture) {
        owner = ownerFeature;
        provider = texture;
    }

    /** The map's texture, or 0 (no sky mod, its feature off, or not drawn lately). Render thread. */
    public static int texture() {
        IntSupplier p = provider;
        AtmosFeature f = owner;
        if (p == null || f == null || !f.isActive()) {
            return 0;
        }
        return p.getAsInt();
    }

    /** True while some active feature reads the map (a sky mod can skip drawing it otherwise). Any thread. */
    public static boolean wanted() {
        AtmosFeature f = feature;
        return f != null && f.isActive();
    }

    /** Frame slot atmos_SkyView: x 1 while a map is published. */
    public static void writeSlot(float[] out) {
        out[0] = texture() != 0 ? 1f : 0f;
    }

    /** A terrain or LOD feature that calls {@code atmos_skyView}. Call during mod construction. */
    public static synchronized void requireInTerrain(AtmosFeature consumer) {
        if (feature != null) {
            throw new IllegalStateException("Sky view consumers must be declared during mod construction");
        }
        CONSUMERS.add(consumer);
    }

    /** Client setup, before the registries freeze: registers the lib feature if any consumer asked. */
    public static synchronized void registerLate() {
        if (CONSUMERS.isEmpty() || feature != null) {
            return;
        }
        List<AtmosFeature> consumers = List.copyOf(CONSUMERS);
        feature = AtmosFeature.builder(AtmospheraLib.MOD_ID, "sky_view")
                .provides(Capability.WORLD_DATA)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER)
                .enabledWhen(() -> {
                    for (AtmosFeature c : consumers) {
                        if (c.isEnabled()) {
                            return true;
                        }
                    }
                    return false;
                })
                .register();
        TerrainSamplers.request(feature, "atmos_SkyViewMap", GL11C.GL_TEXTURE_2D, TerrainSamplers.Stage.FRAGMENT, 85,
                SkyView::texture);
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_ALL, -85, GLSL);
        ShaderSnippets.register(feature, ShaderHook.LOD_DECL, ShaderSnippets.PASS_ALL, -85, LOD_GLSL);
        VoxyBridge.addBeforeLodDraw(() -> {
            if (feature.isActive()) {
                AtmosGl.bindTextureUnit(LOD_UNIT, GL11C.GL_TEXTURE_2D, texture());
            }
        });
    }

    /** True while the map was drawn this frame or the one before (for providers that track their own frame). */
    public static boolean recent(long drawnFrame) {
        return AtmosClient.frame() - drawnFrame <= 1;
    }

    private static final String BODY = """
            #define ATMOS_SKY_VIEW
            // The drawn sky (and clouds) in a direction, in display values; without a sky mod the fog-to-sky blend.
            vec3 atmos_skyView(vec3 atmosDir) {
                if (atmos_SkyView.x < 0.5) {
                    return mix(atmos_FogColor.rgb, atmos_SkyColor.rgb, smoothstep(0.0, 0.35, atmosDir.y));
                }
                float atmosAz = atan(atmosDir.z, atmosDir.x);
                float atmosEl = asin(clamp(atmosDir.y, 0.0, 1.0));
                vec2 atmosUv = vec2(atmosAz * 0.15915494 + 0.5, sqrt(atmosEl * 0.63661977));
                return textureLod(atmos_SkyViewMap, atmosUv, 0.0).rgb;
            }
            #endif
            """;

    /** Terrain and post programs: declares {@code atmos_SkyViewMap} without a binding. Safe to include twice. */
    public static final String GLSL = """
            #ifndef ATMOS_SKY_VIEW_DECL
            #define ATMOS_SKY_VIEW_DECL
            uniform sampler2D atmos_SkyViewMap;
            """ + BODY;

    /** Voxy LOD programs (GLSL 4.60): the sampler on {@link #LOD_UNIT}. */
    static final String LOD_GLSL = """
            #ifndef ATMOS_SKY_VIEW_DECL
            #define ATMOS_SKY_VIEW_DECL
            layout(binding = 11) uniform sampler2D atmos_SkyViewMap;
            """ + BODY;
}
