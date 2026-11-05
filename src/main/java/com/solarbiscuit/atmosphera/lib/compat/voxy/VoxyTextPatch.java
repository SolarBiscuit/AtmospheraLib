package com.solarbiscuit.atmosphera.lib.compat.voxy;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text patch for Voxy LOD shaders that have no extension API (the official Voxy; any NeoVoxy build with it turned
 * off). Works on the flattened {@code voxy:lod/gl46/quads3.vert} / {@code quads.frag} that
 * {@code ShaderLoader.parse} returns, at structural anchors both lines share:
 * <ul>
 *   <li>V1 / F1: {@code void main() {}: the header (struct contract, Atmosphera code, varyings 11-13) goes before it;
 *       the fragment stage also takes its geometric normal right after it, while derivatives are still valid;</li>
 *   <li>V2: the first {@code gl_Position =} statement: the corner's offset is added after it
 *       ({@code MVP} is linear, so {@code MVP * (p + o) = MVP * p + MVP * vec4(o, 0)});</li>
 *   <li>F2: the last {@code outColour =} write that is not a debug {@code vec4(...)} constant: the shading replaces
 *       {@code colour} right before it, converted to straight alpha for Voxy's alpha blending.</li>
 * </ul>
 * Inputs the host does not have degrade instead of failing: no material word unless the host fills
 * {@code BlockModel._pad[0]}, no opaque depth (water thickness falls back to 3 blocks), merged quads, sky light 15.
 * Pure string code (no Minecraft, no GL), so a Fabric port can reuse it as is.
 */
public final class VoxyTextPatch {
    public static final String VERTEX_ID = "voxy:lod/gl46/quads3.vert";
    public static final String FRAGMENT_ID = "voxy:lod/gl46/quads.frag";

    private static final String MAIN = "void main() {";
    private static final Pattern POSITION = Pattern.compile("\\bgl_Position\\s*=[^;]*;");
    private static final Pattern OUT_COLOUR = Pattern.compile("\\boutColour\\s*=\\s*(?!vec4\\s*\\()([^;]+);");

    private VoxyTextPatch() {
    }

    /** The extension's code for one program: common text plus the two hook bodies. */
    public record Extension(String common, String vertexBody, String fragmentBody) {
    }

    /** Patched source, or null when an anchor is missing (the caller keeps the original). */
    public static String patch(String id, String source, Extension ext) {
        if (VERTEX_ID.equals(id)) {
            return patchVertex(source, ext);
        }
        if (FRAGMENT_ID.equals(id)) {
            return patchFragment(source, ext);
        }
        return null;
    }

    /** Why {@link #patch} returned null for this source (for the log), or null when it would patch. */
    public static String problem(String id, String source) {
        if (!source.contains(MAIN)) return "no '" + MAIN + "'";
        if (VERTEX_ID.equals(id)) {
            if (!POSITION.matcher(source).find()) return "no gl_Position statement";
            if (!source.contains("QuadData quad;") || !source.contains("cornerId")) return "no QuadData quad / cornerId in main";
            if (!source.contains("quad.basePoint")) return "QuadData has no basePoint";
        } else {
            if (lastOutColour(source) == null) return "no outColour write";
            if (!source.contains("texPos")) return "no texPos in main";
        }
        return null;
    }

    private static String contract(String source, boolean vertex) {
        //Never under shader packs (PATCHED_SHADER): the pack's own varyings and outputs are in charge there
        StringBuilder out = new StringBuilder("// ---- AtmospheraLib Voxy text patch ----\n#ifndef PATCHED_SHADER\n#define VOXY_EXT_TEXTPATCH 1\n");
        out.append(vertex ? "#ifndef VOXY_EXT_STAGE_VERTEX\n#define VOXY_EXT_STAGE_VERTEX\n#endif\n"
                : "#ifndef VOXY_EXT_STAGE_FRAGMENT\n#define VOXY_EXT_STAGE_FRAGMENT\n#endif\n");
        out.append("#if defined(TRANSLUCENT) && !defined(VOXY_EXT_TRANSLUCENT)\n#define VOXY_EXT_TRANSLUCENT 1\n#endif\n");
        if (!source.contains("struct VoxyExtVertex")) {
            //Same contract as NeoVoxy's lod/ext/api.glsl (a NeoVoxy source already has it)
            out.append("""
                    #define VOXY_EXT_QUAD_WATER 1u
                    #define VOXY_EXT_QUAD_LEAF 2u
                    #define VOXY_EXT_QUAD_LEAF_CARD 4u
                    #define VOXY_EXT_QUAD_FLUID_SHAPE 8u
                    #define VOXY_EXT_QUAD_UNMERGED 16u
                    uint voxyExt_anchor(uint quadFlags) { return (quadFlags >> 8u) & 3u; }
                    struct VoxyExtVertex {
                        vec3 relPos; vec3 offset; vec4 vary; uint face; uint material; uint quadFlags; uint light;
                        uint lodLevel; float lodScale; float voxelFracY; float blocksPerPixel;
                    };
                    struct VoxyExtFragment {
                        vec4 colour; vec3 keep; bool premultiplied; vec4 vary; vec3 relPos; vec3 geoNormal; vec4 albedo;
                        vec3 tint; vec3 bathyTint; vec3 light; float sky; float block; float fragDepth; float sceneDepth;
                        uint face; uint material; uint quadFlags; float lodScale;
                    };
                    float voxyExt_linearDepth(float windowZ) { return 0.0; }
                    """);
        }
        return out.toString();
    }

    private static String header(String source, Extension ext, boolean vertex) {
        String io = vertex ? "out" : "in";
        StringBuilder out = new StringBuilder(contract(source, vertex));
        out.append("layout(location = 11) ").append(io).append(" flat uvec2 voxyTpFlat;//material, light << 16 | face << 24\n");
        out.append("layout(location = 12) ").append(io).append(" vec4 voxyTpVary;\n");
        out.append("layout(location = 13) ").append(io).append(" vec3 voxyTpRelPos;\n");
        out.append(ext.common()).append('\n');
        if (vertex) {
            out.append("void voxyExt_vertex(inout VoxyExtVertex v) {\n").append(ext.vertexBody()).append("}\n");
        } else {
            out.append("void voxyExt_fragment(inout VoxyExtFragment f) {\n").append(ext.fragmentBody()).append("}\n");
        }
        return out.append("#endif\n// ---- /AtmospheraLib Voxy text patch ----\n").toString();
    }

    private static String patchVertex(String source, Extension ext) {
        if (problem(VERTEX_ID, source) != null) return null;
        String raw = "quadData[uint(gl_VertexID)>>2]";
        String material = source.contains("extractStateId(") && source.contains("_pad[")
                ? "modelData[extractStateId(" + raw + ")]._pad[0]" : "0u";
        String light = source.contains("extractLightId(") ? "extractLightId(" + raw + ")" : "15u";
        String face = source.contains("extractFace(") ? "extractFace(" + raw + ")" : "1u";
        String corner = source.contains("vec3 getQuadCornerPoint(")
                ? "getQuadCornerPoint(quad, cornerId)"
                : "(quad.basePoint + swizzelDataAxis(quad.axis, vec3(quad.quadSizeAddin * (vec2((cornerId>>1)&1u, cornerId&1u) * quad.lodScale), 0.0)))";
        String hook = """

                #ifndef PATCHED_SHADER
                    {// AtmospheraLib Voxy text patch
                        vec3 voxyTpCorner = %s;
                        VoxyExtVertex voxyTpV;
                        voxyTpV.relPos = voxyTpCorner - cameraSubPos;
                        voxyTpV.offset = vec3(0.0);
                        voxyTpV.vary = vec4(0.0);
                        voxyTpV.face = %s;
                        voxyTpV.material = %s;
                        voxyTpV.quadFlags = 0u;
                        voxyTpV.light = %s;
                        voxyTpV.lodScale = quad.lodScale;
                        voxyTpV.lodLevel = uint(round(log2(max(quad.lodScale, 1.0))));
                        voxyTpV.voxelFracY = clamp((voxyTpCorner.y - quad.basePoint.y) / quad.lodScale, 0.0, 1.0);
                        voxyTpV.blocksPerPixel = 0.0;
                        voxyExt_vertex(voxyTpV);
                        float voxyTpLength = length(voxyTpV.offset);
                        vec3 voxyTpOffset = voxyTpLength > 0.25 ? voxyTpV.offset * (0.25 / voxyTpLength) : voxyTpV.offset;
                        gl_Position += MVP * vec4(voxyTpOffset, 0.0);
                        voxyTpFlat = uvec2(voxyTpV.material, (voxyTpV.light & 0xFFu) << 16u | (voxyTpV.face & 7u) << 24u);
                        voxyTpVary = voxyTpV.vary;
                        voxyTpRelPos = voxyTpV.relPos + voxyTpOffset;
                    }
                #endif
                """.formatted(corner, face, material, light);
        int main = source.indexOf(MAIN);
        Matcher position = POSITION.matcher(source);
        position.find(main);
        int at = position.end();
        String out = source.substring(0, at) + hook + source.substring(at);
        return out.substring(0, main) + header(source, ext, true) + out.substring(main);
    }

    /** The last non-debug outColour write in main, already matched (group 1 = value), or null. */
    private static Matcher lastOutColour(String source) {
        Matcher m = OUT_COLOUR.matcher(source);
        int start = -1;
        int from = Math.max(0, source.indexOf(MAIN));
        while (from < source.length() && m.find(from)) {
            start = m.start();
            from = m.end();
        }
        if (start < 0) return null;
        Matcher last = OUT_COLOUR.matcher(source);
        return last.find(start) ? last : null;
    }

    private static String patchFragment(String source, Extension ext) {
        if (problem(FRAGMENT_ID, source) != null) return null;
        Matcher write = lastOutColour(source);
        String value = write.group(1).trim();
        String hook = """
                {// AtmospheraLib Voxy text patch
                    VoxyExtFragment voxyTpF;
                    voxyTpF.colour = %1$s;
                    voxyTpF.keep = vec3(1.0 - voxyTpF.colour.a);
                    voxyTpF.premultiplied = false;
                    voxyTpF.vary = voxyTpVary;
                    voxyTpF.relPos = voxyTpRelPos;
                    voxyTpF.geoNormal = dot(voxyTpGeo, voxyTpRelPos) > 0.0 ? -voxyTpGeo : voxyTpGeo;
                    voxyTpF.albedo = textureLod(blockModelAtlas, texPos, 0.0);
                    voxyTpF.tint = tintingState() != 0u ? uint2vec4RGBA(interData.z).yzw : vec3(1.0);
                    voxyTpF.bathyTint = voxyTpF.tint;
                    voxyTpF.light = uint2vec4RGBA(interData.y).rgb;
                    voxyTpF.sky = float((voxyTpFlat.y >> 16u) & 15u) / 15.0;
                    voxyTpF.block = float((voxyTpFlat.y >> 20u) & 15u) / 15.0;
                    voxyTpF.fragDepth = voxyExt_linearDepth(gl_FragCoord.z);
                    voxyTpF.sceneDepth = -1.0;
                    voxyTpF.face = (voxyTpFlat.y >> 24u) & 7u;
                    voxyTpF.material = voxyTpFlat.x;
                    voxyTpF.quadFlags = 0u;
                    voxyTpF.lodScale = 1.0;
                    voxyExt_fragment(voxyTpF);
                #ifdef TRANSLUCENT
                    if (voxyTpF.premultiplied) {//Voxy blends with straight alpha
                        float voxyTpAlpha = clamp(1.0 - dot(voxyTpF.keep, vec3(1.0 / 3.0)), 0.0, 1.0);
                        voxyTpF.colour = vec4(voxyTpAlpha > 1e-4 ? voxyTpF.colour.rgb / voxyTpAlpha : voxyTpF.colour.rgb, voxyTpAlpha);
                    }
                #endif
                    outColour = voxyTpF.colour;
                }
                """.formatted(value);
        //Replace the write itself, so the value expression is evaluated once, in its original place
        String out = source.substring(0, write.start()) + hook + source.substring(write.end());
        int main = out.indexOf(MAIN);
        String geo = "\n#ifndef PATCHED_SHADER\n    vec3 voxyTpGeo = normalize(cross(dFdx(voxyTpRelPos), dFdy(voxyTpRelPos)));//AtmospheraLib, before any discard\n#endif\n";
        out = out.substring(0, main + MAIN.length()) + geo + out.substring(main + MAIN.length());
        return out.substring(0, main) + header(source, ext, false) + out.substring(main);
    }
}
