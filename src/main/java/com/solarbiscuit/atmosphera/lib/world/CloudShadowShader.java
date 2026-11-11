package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.AtmosGl;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import org.lwjgl.opengl.GL11C;

import java.util.ArrayList;
import java.util.List;

/**
 * Cloud shadows in shaders (0.7.1): {@code float atmos_cloudShadow(vec3 rel)}, the share of sunlight the clouds let
 * through to a camera-relative point (1 = no cloud overhead, also without a cloud mod, at night and outside the map).
 * The cloud mod (Stratos 0.2) publishes a world-locked map of the clouds' transmittance along the sun on a horizontal
 * reference plane ({@link CloudCover.Provider#shadowTexture()}); a point is projected along {@code atmos_SunDir} onto
 * that plane and looked up there.
 * <ul>
 *   <li>Terrain (Xenon) and Voxy LOD programs: a consumer feature calls {@link #requireInTerrain} during mod
 *       construction; the lib feature {@code atmospheralib:cloud_shadow} then compiles {@link #GLSL} into the
 *       fragment stage and binds the map (a terrain sampler unit; unit {@value #LOD_UNIT} before each Voxy LOD draw).
 *       Guard calls with {@code #ifdef ATMOS_CLOUD_SHADOW}.</li>
 *   <li>Post programs: include {@link #GLSL} after the frame block, bind {@link CloudCover#shadowTexture()} to a unit
 *       of your own and set the sampler {@code atmos_CloudShadowMap}.</li>
 * </ul>
 * Frame slots: {@code atmos_CloudShadow} (xy map centre minus camera, z 1 / map size in blocks, w 1 while a map is
 * published) and {@code atmos_CloudShadow2} (x reference plane height, y camera height).
 */
public final class CloudShadowShader {
    /** Texture unit of the map in Voxy LOD programs (NeoVoxy uses 0-7, Lumis's far shadows 13). */
    public static final int LOD_UNIT = 10;

    private static final List<AtmosFeature> CONSUMERS = new ArrayList<>();
    private static AtmosFeature feature;

    private CloudShadowShader() {
    }

    /** A terrain or LOD feature that calls {@code atmos_cloudShadow} in its snippets. Call during mod construction. */
    public static synchronized void requireInTerrain(AtmosFeature consumer) {
        if (feature != null) {
            throw new IllegalStateException("Cloud shadow consumers must be declared during mod construction");
        }
        CONSUMERS.add(consumer);
    }

    /** The lib feature, or null when no mod asked for cloud shadows in terrain. */
    public static AtmosFeature feature() {
        return feature;
    }

    /** Client setup, before the registries freeze: registers the lib feature if any consumer asked. */
    public static synchronized void registerLate() {
        if (CONSUMERS.isEmpty() || feature != null) {
            return;
        }
        List<AtmosFeature> consumers = List.copyOf(CONSUMERS);
        feature = AtmosFeature.builder(AtmospheraLib.MOD_ID, "cloud_shadow")
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
        TerrainSamplers.request(feature, "atmos_CloudShadowMap", GL11C.GL_TEXTURE_2D, TerrainSamplers.Stage.FRAGMENT, 90,
                CloudCover::shadowTexture);
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_ALL, -90, GLSL);
        ShaderSnippets.register(feature, ShaderHook.LOD_DECL, ShaderSnippets.PASS_ALL, -90, LOD_GLSL);
        VoxyBridge.addBeforeLodDraw(() -> {
            if (feature.isActive()) {
                AtmosGl.bindTextureUnit(LOD_UNIT, GL11C.GL_TEXTURE_2D, CloudCover.shadowTexture());
            }
        });
    }

    private static final String BODY = """
            #define ATMOS_CLOUD_SHADOW
            // Sunlight let through by the clouds above a camera-relative point (1 = none overhead or no map).
            float atmos_cloudShadow(vec3 atmosRel) {
                vec3 atmosSun = atmos_SunDir.xyz;
                if (atmos_CloudShadow.w < 0.5 || atmosSun.y < 0.03) {
                    return 1.0;
                }
                float atmosWorldY = atmos_CloudShadow2.y + atmosRel.y;
                vec2 atmosQ = atmosRel.xz + atmosSun.xz * ((atmos_CloudShadow2.x - atmosWorldY) / atmosSun.y);
                vec2 atmosUv = (atmosQ - atmos_CloudShadow.xy) * atmos_CloudShadow.z + 0.5;
                vec2 atmosEdge = abs(atmosUv - 0.5) * 2.0;
                float atmosFade = smoothstep(0.82, 1.0, max(atmosEdge.x, atmosEdge.y));
                // Low sun: long projections and a reddened, weak light; shadows fade out towards the horizon.
                atmosFade = max(atmosFade, 1.0 - smoothstep(0.03, 0.12, atmosSun.y));
                float atmosT = textureLod(atmos_CloudShadowMap, clamp(atmosUv, 0.0, 1.0), 0.0).r;
                return mix(atmosT, 1.0, atmosFade);
            }
            #endif
            """;

    /** Terrain and post programs: declares {@code atmos_CloudShadowMap} without a binding. Safe to include twice. */
    public static final String GLSL = """
            #ifndef ATMOS_CLOUD_SHADOW_DECL
            #define ATMOS_CLOUD_SHADOW_DECL
            uniform sampler2D atmos_CloudShadowMap;
            """ + BODY;

    /** Voxy LOD programs (GLSL 4.60): the sampler on {@link #LOD_UNIT}. */
    static final String LOD_GLSL = """
            #ifndef ATMOS_CLOUD_SHADOW_DECL
            #define ATMOS_CLOUD_SHADOW_DECL
            layout(binding = 10) uniform sampler2D atmos_CloudShadowMap;
            """ + BODY;
}
