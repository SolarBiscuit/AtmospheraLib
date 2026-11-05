package com.solarbiscuit.atmosphera.lib.compat.voxy;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;

/**
 * The Voxy side of the Atmosphera shader contract: the same AtmosFrame block, vertex-bit macros, feature macros and
 * snippets Xenon gets, assembled for Voxy's LOD program (the {@code LOD_*} {@link ShaderHook}s). The text runs inside
 * NeoVoxy's {@code voxyExt_vertex} / {@code voxyExt_fragment} hooks (struct contract in NeoVoxy's
 * {@code lod/ext/api.glsl}); the text-patch backend injects the same structs and calls into upstream Voxy's shaders,
 * so one set of snippets serves both. Forge-free apart from the library types, for a later Fabric port.
 */
public final class VoxyHostGlsl {
    private VoxyHostGlsl() {
    }

    /** Global code for both stages and both passes. Render thread (reads the UBO binding). */
    public static String common(AtmosState state) {
        StringBuilder out = new StringBuilder("// ---- AtmospheraLib (Voxy host) ----\n");
        out.append("""
                #define ATMOS_HOST_VOXY 1
                #ifdef VOXY_EXT_TRANSLUCENT
                #define ATMOS_PASS_TRANSLUCENT
                #else
                #define ATMOS_PASS_SOLID
                #define ATMOS_PASS_CUTOUT
                #endif
                """);
        state.forEachFeatureDefine(macro -> out.append("#define ").append(macro).append('\n'));
        out.append(FrameUniforms.glslBlockWithBinding());
        out.append(VertexBits.glslDefines());
        out.append("""
                // World position from a camera-relative one (x/z wrapped every 4096 blocks), as on Xenon.
                vec3 atmos_worldPos(vec3 relativePos) {
                    return relativePos + atmos_CameraPos.xyz;
                }
                """);
        out.append(ShaderSnippets.render(ShaderHook.LOD_DECL));
        return out.append("// ---- /AtmospheraLib ----\n").toString();
    }

    /*
     * Appended to both hooks in every pass. With the extension registered, NeoVoxy 1.43.2/1.43.3 LOD draws crashed the
     * AMD driver (access violation in atio6axx.dll 32.0.32015.7029 at the same offset, ~10 s after joining) whenever
     * a pass had no Atmosphera statements, or only ones the compiler can fold away (every LOD feature off, Gale
     * without Torrente, Lumis alone); only setups with real code in both passes ran. These statements read the
     * extension inputs and a uniform (atmos_Lod.w is 1 while LOD shading runs), so they are identities at run time
     * but stay in the compiled program. One multiply-add per vertex and fragment.
     */
    private static final String KEEP_VERTEX = "        v.vary.w += (1.0 - atmos_Lod.w) * 1e-6 * float(v.material & 1u);\n";
    private static final String KEEP_FRAGMENT =
            "        f.colour.rgb *= 1.0 + (1.0 - atmos_Lod.w) * 1e-6 * (f.vary.w + float(f.material & 1u));\n";

    /** Statements for {@code voxyExt_vertex}; empty while no active feature has an LOD vertex snippet. */
    public static String vertex() {
        if (!ShaderSnippets.any(AtmosFeature::isActive, ShaderHook.LOD_VERTEX)) {
            return KEEP_VERTEX;
        }
        return "        uint atmos_MaterialBits = v.material & 31u;\n"
                + "        vec3 position = v.relPos;\n"
                + ShaderSnippets.render(ShaderHook.LOD_VERTEX)
                + "        v.offset += position - v.relPos;\n" + KEEP_VERTEX;
    }

    /** Statements for {@code voxyExt_fragment}; empty while no active feature has an LOD fragment snippet. */
    public static String fragment() {
        if (!ShaderSnippets.any(AtmosFeature::isActive, ShaderHook.LOD_FRAGMENT)) {
            return KEEP_FRAGMENT;
        }
        return "        uint atmos_MaterialBits = f.material & 31u;\n" + ShaderSnippets.render(ShaderHook.LOD_FRAGMENT)
                + KEEP_FRAGMENT;
    }
}
