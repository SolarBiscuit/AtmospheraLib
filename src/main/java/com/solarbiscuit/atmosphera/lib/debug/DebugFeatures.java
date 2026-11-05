package com.solarbiscuit.atmosphera.lib.debug;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.client.LibConfig;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.mesh.BitField;
import com.solarbiscuit.atmosphera.lib.mesh.BlockMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.MeshHooks;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Developer features that use the public API exactly like Gale, Torrente and Lumis will: a material bit + vertex
 * displacement (leaves), a fluid bit + scene depth copy (water), and per-block + per-vertex light bits (emitters).
 * Only with {@code -Datmospheralib.debugFeatures=true}. Their bits are allocated after every other mod, at client
 * setup, so they never take bits a real feature needs; when the bits are used up, that debug feature is skipped.
 */
public final class DebugFeatures {
    private DebugFeatures() {
    }

    /** Client setup, before the registries freeze. */
    public static void registerLate() {
        if (!LibConfig.DEBUG_FEATURES) {
            return;
        }
        try {
            leaves();
        } catch (IllegalStateException e) {
            AtmospheraLib.LOGGER.info("Debug leaf feature skipped: {}", e.getMessage());
        }
        try {
            water();
        } catch (IllegalStateException e) {
            AtmospheraLib.LOGGER.info("Debug water feature skipped: {}", e.getMessage());
        }
        try {
            light();
        } catch (IllegalStateException e) {
            AtmospheraLib.LOGGER.info("Debug light feature skipped: {}", e.getMessage());
        }
    }

    private static void leaves() {
        BitField leaf = VertexBits.material(AtmospheraLib.MOD_ID, "debug_leaf", 1);
        AtmosFeature feature = AtmosFeature.builder(AtmospheraLib.MOD_ID, "debug_leaf_wave")
                .provides(Capability.DEBUG).uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.MESH_BITS)
                .switchedBy(LibConfig.debugLeaf).register();
        MeshHooks.registerBlock(feature, new BlockMeshHook() {
            @Override
            public int materialBits(BlockState state, BlockPos pos, BlockAndTintGetter level) {
                return state.getBlock() instanceof LeavesBlock ? leaf.set(0, 1) : 0;
            }
        });
        String bit = leaf.glslMacro();
        ShaderSnippets.register(feature, ShaderHook.VERT_POSITION, ShaderSnippets.PASS_ALL, 0, """
                    if (%s(atmos_MaterialBits) != 0u) {
                        vec3 atmosWorld = atmos_worldPos(position);
                        float atmosPhase = atmos_Time.y * 2.0 + atmosWorld.x * 0.7 + atmosWorld.z * 0.5;
                        position.xz += atmos_Wind.xy * (0.05 + 0.10 * atmos_Wind.z) * sin(atmosPhase);
                    }
                """.formatted(bit));
        ShaderSnippets.register(feature, ShaderHook.FRAG_PRE_FOG, ShaderSnippets.PASS_ALL, 0, """
                    if (%s(v_AtmosMaterialBits) != 0u) {
                        diffuseColor.rgb = mix(diffuseColor.rgb, vec3(1.0, 0.25, 0.65), 0.3);
                    }
                """.formatted(bit));
    }

    private static void water() {
        BitField water = VertexBits.material(AtmospheraLib.MOD_ID, "debug_water", 1);
        AtmosFeature feature = AtmosFeature.builder(AtmospheraLib.MOD_ID, "debug_water_depth")
                .provides(Capability.DEBUG)
                .uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.MESH_BITS, AtmosFeature.Uses.SCENE_DEPTH)
                .switchedBy(LibConfig.debugWater).register();
        int bits = water.set(0, 1);
        MeshHooks.registerFluid(feature, (state, pos, level) -> state.is(FluidTags.WATER) ? bits : 0);
        ShaderSnippets.register(feature, ShaderHook.FRAG_PRE_FOG, ShaderSnippets.PASS_TRANSLUCENT, 0, """
                #ifdef ATMOS_SCENE_DEPTH
                    if (%s(v_AtmosMaterialBits) != 0u && atmos_Flags.x > 0.5) {
                        float atmosThickness = max(atmos_linearDepth(atmos_sceneDepth()) - atmos_linearDepth(gl_FragCoord.z), 0.0);
                        float atmosAbsorb = 1.0 - exp(-atmosThickness * 0.15);
                        diffuseColor.rgb = mix(vec3(0.55, 1.0, 0.85), vec3(0.0, 0.04, 0.22), atmosAbsorb);
                        diffuseColor.a = mix(0.2, 0.9, atmosAbsorb);
                    }
                #endif
                """.formatted(water.glslMacro()));
    }

    private static void light() {
        BitField emitter = VertexBits.light(AtmospheraLib.MOD_ID, "debug_emitter", 1);
        BitField top = VertexBits.light(AtmospheraLib.MOD_ID, "debug_top", 1);
        AtmosFeature feature = AtmosFeature.builder(AtmospheraLib.MOD_ID, "debug_light_bits")
                .provides(Capability.DEBUG).uses(AtmosFeature.Uses.TERRAIN_SHADER, AtmosFeature.Uses.LIGHT_BITS)
                .switchedBy(LibConfig.debugLight).register();
        MeshHooks.registerBlock(feature, new BlockMeshHook() {
            @Override
            public int lightBits(BlockState state, BlockPos pos, BlockAndTintGetter level) {
                return state.getLightEmission() > 0 ? emitter.set(0, 1) : 0;
            }

            @Override
            public boolean perVertex(BlockState state) {
                return state.getLightEmission() > 0;
            }

            @Override
            public int vertexLightBits(BlockState state, int bits, float x, float y, float z) {
                return y > 0.5f ? top.set(bits, 1) : bits;
            }
        });
        ShaderSnippets.register(feature, ShaderHook.FRAG_PRE_FOG, ShaderSnippets.PASS_ALL, 0, """
                    if (%s(v_AtmosLightBits) != 0u) {
                        vec3 atmosTint = %s(v_AtmosLightBits) != 0u ? vec3(1.0, 0.55, 0.1) : vec3(1.0, 0.1, 0.1);
                        diffuseColor.rgb = mix(diffuseColor.rgb, atmosTint, 0.5);
                    }
                """.formatted(emitter.glslMacro(), top.glslMacro()));
    }
}
