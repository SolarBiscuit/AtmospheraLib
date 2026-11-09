package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import org.lwjgl.opengl.GL11C;

import java.util.ArrayList;
import java.util.List;

/**
 * The column field in Xenon's terrain programs (lib 0.5.0): {@code atmos_columnTop}, {@code atmos_waterTop},
 * {@code atmos_rainTop} and {@code atmos_columnFlags}, compiled in while a feature that asked for them
 * ({@link #requireInTerrain}) is enabled. The lib feature {@code atmospheralib:column_field} owns the sampler (a unit
 * from 16 up) and the declarations; it exists only when some mod asked for it.
 * <p>
 * GLSL (both stages; guard with {@code #ifdef ATMOS_COLUMN_FIELD}): each function takes a camera-relative position
 * and returns a world y, or {@code ATMOS_NO_COLUMN} (-1e9) where the field has no data: outside the window (with a
 * 16-block margin, so nothing reads across the toroidal seam), in unknown columns, or while the field is not running
 * ({@code atmos_Column.w == 0}). The consumer must keep the field running with {@link ColumnField#use} each frame.
 */
public final class ColumnFieldShader {
    private static final List<AtmosFeature> CONSUMERS = new ArrayList<>();
    private static AtmosFeature feature;

    private ColumnFieldShader() {
    }

    /** A terrain feature that reads the column field in its snippets. Call during mod construction. */
    public static synchronized void requireInTerrain(AtmosFeature consumer) {
        if (feature != null) {
            throw new IllegalStateException("Column field consumers must be declared during mod construction");
        }
        CONSUMERS.add(consumer);
    }

    /** The lib feature, or null when no mod asked for the field in terrain. */
    public static AtmosFeature feature() {
        return feature;
    }

    /** Client setup, before the registries freeze: registers the lib feature if any consumer asked. */
    public static synchronized void registerLate() {
        if (CONSUMERS.isEmpty() || feature != null) {
            return;
        }
        List<AtmosFeature> consumers = List.copyOf(CONSUMERS);
        feature = AtmosFeature.builder(AtmospheraLib.MOD_ID, "column_field")
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
        TerrainSamplers.request(feature, "atmos_ColumnField", GL11C.GL_TEXTURE_2D, TerrainSamplers.Stage.FRAGMENT, 100,
                ColumnField::texture);
        ShaderSnippets.register(feature, ShaderHook.VERT_DECL, ShaderSnippets.PASS_ALL, -100, GLSL);
        ShaderSnippets.register(feature, ShaderHook.FRAG_DECL, ShaderSnippets.PASS_ALL, -100, GLSL);
    }

    /** atmos_Column (lib frame slot): x window size, y min build height, zw window centre relative to the camera. */
    public static void writeSlot(float[] out) {
        if (!ColumnField.ready()) {
            return;
        }
        var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        out[0] = ColumnField.size();
        out[1] = ColumnField.minY();
        out[2] = (float) (ColumnField.centreChunkX() * 16.0 - camera.x);
        out[3] = (float) (ColumnField.centreChunkZ() * 16.0 - camera.z);
    }

    /**
     * The accessors above as GLSL (0.6.3: public, for post programs). A post program that includes it (after the frame
     * block, which holds {@code atmos_Column} and {@code atmos_CameraPos}) binds {@link ColumnField#texture()} to a
     * unit of its own, sets the sampler {@code atmos_ColumnField} to it, and keeps the field running with
     * {@link ColumnField#use}. The guard makes it safe to include twice.
     */
    public static final String GLSL = """
            #ifndef ATMOS_COLUMN_FIELD_DECL
            #define ATMOS_COLUMN_FIELD_DECL
            #define ATMOS_COLUMN_FIELD
            #define ATMOS_NO_COLUMN -1e9
            uniform sampler2D atmos_ColumnField;
            // Raw texel (caster, water, rain, flags) as 0..65535 at a camera-relative position; all 0 without data.
            vec4 atmos_columnRaw(vec3 atmosRel) {
                float atmosN = atmos_Column.x;
                vec2 atmosOff = abs(atmosRel.xz - atmos_Column.zw);
                if (atmosN < 16.0 || max(atmosOff.x, atmosOff.y) > atmosN * 0.5 - 16.0) {
                    return vec4(0.0);
                }
                ivec2 atmosTexel = ivec2(floor(atmosRel.xz + atmos_CameraPos.xz)) & ivec2(int(atmosN) - 1);
                return floor(texelFetch(atmos_ColumnField, atmosTexel, 0) * 65535.0 + 0.5);
            }
            float atmos_columnY(float atmosRaw) {
                return atmosRaw > 0.5 ? atmos_Column.y + atmosRaw * 0.0625 : ATMOS_NO_COLUMN;
            }
            // Top of the highest sun-casting block of the column (world y), or ATMOS_NO_COLUMN.
            float atmos_columnTop(vec3 atmosRel) {
                return atmos_columnY(atmos_columnRaw(atmosRel).r);
            }
            // Surface of the highest water above that caster, or ATMOS_NO_COLUMN (no water, or no data).
            float atmos_waterTop(vec3 atmosRel) {
                return atmos_columnY(atmos_columnRaw(atmosRel).g);
            }
            // True where the column has been scanned (its rain top is set): "no caster" or "no water" is then known,
            // not just missing data.
            bool atmos_columnKnown(vec4 atmosRaw) {
                return atmosRaw.b > 0.5;
            }
            // Where rain stops (MOTION_BLOCKING top), or ATMOS_NO_COLUMN.
            float atmos_rainTop(vec3 atmosRel) {
                return atmos_columnY(atmos_columnRaw(atmosRel).b);
            }
            #endif
            """;
}
