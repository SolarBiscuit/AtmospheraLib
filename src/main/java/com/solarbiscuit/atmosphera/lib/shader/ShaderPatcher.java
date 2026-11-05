package com.solarbiscuit.atmosphera.lib.shader;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Builds the Atmosphera version of Xenon's terrain program ({@code sodium:blocks/block_layer_opaque}) by string
 * insertion at fixed anchors: a generated header (AtmosFrame block, vertex-bit macros, varyings, helpers) and every
 * registered {@link ShaderSnippets snippet}. Snippets are wrapped in feature macros, so which features are compiled in
 * is decided by the defines {@code ChunkShaderOptionsMixin} adds, not by this text.
 * <p>
 * The vertex and fragment stages are patched together or not at all (a half-patched pair would not link). If an
 * anchor is missing (a different Xenon/Embeddium version), nothing is patched, one WARN is logged, and every terrain
 * feature reports the reason. Xenon's LGPL sources are never shipped, only modified in memory.
 * Render thread only (Sodium compiles chunk programs there).
 */
public final class ShaderPatcher {
    private static final ResourceLocation VERTEX = new ResourceLocation("sodium", "blocks/block_layer_opaque.vsh");
    private static final ResourceLocation FRAGMENT = new ResourceLocation("sodium", "blocks/block_layer_opaque.fsh");

    // Anchors in Xenon 0.3.31 / Embeddium 0.3.x.
    private static final String A_MAIN = "void main() {";
    private static final String A_VERT_INIT = "_vert_init();";
    private static final String A_POSITION = "vec3 position = _vert_position + translation;";
    private static final String A_LIGHTMAP_UV = "clamp(uv / 256.0";
    private static final String A_COLOR = "v_Color = _vert_color * _sample_lightmap(u_LightTex, _vert_tex_light_coord);";
    private static final String A_FOG = "fragColor = _linearFog(diffuseColor, v_FragDistance, u_FogColor, u_FogStart, u_FogEnd);";

    /** The light-coordinate high bytes carry Atmosphera bits; the lightmap must only see the low byte. */
    private static final String LIGHTMAP_UV_MASKED = "clamp(vec2(uv & ivec2(255)) / 256.0";

    private static final String BEGIN = "// ---- AtmospheraLib ----\n";
    private static final String END = "// ---- /AtmospheraLib ----\n";

    private enum Verdict { UNKNOWN, PATCH, SKIP }

    private static Verdict verdict = Verdict.UNKNOWN;
    private static String problem;
    private static boolean dump;

    private ShaderPatcher() {
    }

    public static void setDumpShaders(boolean value) {
        dump = value;
    }

    /** Null while the anchors are fine (or not checked yet); otherwise the reason terrain features are off. */
    public static String anchorProblem() {
        return problem;
    }

    public static boolean isPatching() {
        return verdict == Verdict.PATCH;
    }

    /** Called with the top-level source of every program stage Sodium loads, before #import expansion. */
    public static String patch(ResourceLocation name, String source) {
        if (source == null) {
            return null;
        }
        boolean vertex = VERTEX.equals(name);
        if (!vertex && !FRAGMENT.equals(name)) {
            return source;
        }
        if (verdict == Verdict.UNKNOWN) {
            decide(vertex ? source : raw(VERTEX), vertex ? raw(FRAGMENT) : source);
        }
        if (verdict != Verdict.PATCH) {
            return source;
        }
        String patched = vertex ? patchVertex(source) : patchFragment(source);
        if (dump) {
            dump(name, patched);
        }
        return patched;
    }

    private static void decide(String vertexSource, String fragmentSource) {
        String missing = missing(vertexSource, A_MAIN, A_VERT_INIT, A_POSITION, A_LIGHTMAP_UV, A_COLOR);
        if (missing == null) {
            missing = missing(fragmentSource, A_MAIN, A_FOG);
        }
        if (missing == null) {
            verdict = Verdict.PATCH;
            AtmospheraLib.LOGGER.info("Terrain shader anchors found; patching sodium:blocks/block_layer_opaque "
                    + "({} snippets, {} AtmosFrame slots).", ShaderSnippets.count(), FrameUniforms.slotCount());
        } else {
            verdict = Verdict.SKIP;
            problem = "terrain shader anchor not found (different Xenon/Embeddium version)";
            AtmospheraLib.LOGGER.warn("Terrain shader anchor '{}' not found. Atmosphera terrain features are off; "
                    + "Xenon renders as usual.", missing);
            AtmosState.requestRefresh();
        }
    }

    private static String missing(String source, String... anchors) {
        if (source == null) {
            return "<source unavailable>";
        }
        for (String anchor : anchors) {
            if (!source.contains(anchor)) {
                return anchor;
            }
        }
        return null;
    }

    /** Unpatched source of the partner stage (patching happens after getShaderSource, so this is always raw). */
    private static String raw(ResourceLocation name) {
        try {
            return ShaderLoader.getShaderSource(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String commonHeader() {
        return FrameUniforms.glslBlock() + VertexBits.glslDefines() + """
                // Linear view distance from a window-space depth (gl_FragCoord.z or a scene depth sample).
                float atmos_linearDepth(float windowZ) {
                    return atmos_Proj.y / ((windowZ * 2.0 - 1.0) + atmos_Proj.x);
                }
                // World position from a camera-relative one (x/z wrapped every 4096 blocks).
                vec3 atmos_worldPos(vec3 relativePos) {
                    return relativePos + atmos_CameraPos.xyz;
                }
                // Rec. 709 luminance.
                float atmos_lum(vec3 c) {
                    return dot(c, vec3(0.2126, 0.7152, 0.0722));
                }
                """;
    }

    private static String patchVertex(String source) {
        String header = BEGIN + commonHeader() + """
                flat out uint v_AtmosMaterialBits;
                flat out uint v_AtmosLightBits;
                out vec3 v_AtmosRelPos;
                uint atmos_MaterialBits;
                uint atmos_LightBits;
                // Lightmap coordinates without the Atmosphera bits (x block, y sky; 0..240, 16 per level).
                ivec2 atmos_LightUV;
                """ + ShaderSnippets.render(ShaderHook.VERT_DECL) + END + "\n";
        String postInit = "\n" + BEGIN + """
                    atmos_LightUV = _vert_tex_light_coord & ivec2(255);
                    atmos_MaterialBits = (_material_params >> 3u) & 31u;
                    atmos_LightBits = ((uint(_vert_tex_light_coord.x) >> 8u) & 255u)
                            | (((uint(_vert_tex_light_coord.y) >> 8u) & 255u) << 8u);
                    v_AtmosMaterialBits = atmos_MaterialBits;
                    v_AtmosLightBits = atmos_LightBits;
                """ + ShaderSnippets.render(ShaderHook.VERT_POST_INIT) + END;
        String position = "\n" + BEGIN + ShaderSnippets.render(ShaderHook.VERT_POSITION)
                + "    v_AtmosRelPos = position;\n" + END;
        String postLight = "\n" + BEGIN + ShaderSnippets.render(ShaderHook.VERT_POST_LIGHT) + END;

        String out = insertBefore(source, A_MAIN, header);
        out = insertAfter(out, A_VERT_INIT, postInit);
        out = insertAfter(out, A_POSITION, position);
        out = insertAfter(out, A_COLOR, postLight);
        return out.replace(A_LIGHTMAP_UV, LIGHTMAP_UV_MASKED);
    }

    private static String patchFragment(String source) {
        String header = BEGIN + commonHeader() + """
                flat in uint v_AtmosMaterialBits;
                flat in uint v_AtmosLightBits;
                in vec3 v_AtmosRelPos;
                #ifdef ATMOS_SCENE_DEPTH
                uniform sampler2D atmos_SceneDepth;
                // Window-space depth of the opaque scene behind this fragment (translucent pass only).
                float atmos_sceneDepth() {
                    return texelFetch(atmos_SceneDepth, ivec2(gl_FragCoord.xy), 0).r;
                }
                #endif
                #ifdef ATMOS_SCENE_COLOR
                uniform sampler2D atmos_SceneColor;
                #endif
                """ + ShaderSnippets.render(ShaderHook.FRAG_DECL) + END + "\n";
        // Computed first, in uniform control flow (derivatives are undefined inside non-uniform branches).
        String mainStart = "\n" + BEGIN + """
                #ifdef ATMOS_FACE_NORMAL
                    vec3 atmos_FaceNormal = normalize(cross(dFdx(v_AtmosRelPos), dFdy(v_AtmosRelPos)));
                    if (dot(atmos_FaceNormal, v_AtmosRelPos) > 0.0) atmos_FaceNormal = -atmos_FaceNormal;
                #endif
                """ + END;
        String preFog = BEGIN + ShaderSnippets.render(ShaderHook.FRAG_PRE_FOG) + END + "    ";
        String postFog = "\n" + BEGIN + ShaderSnippets.render(ShaderHook.FRAG_POST_FOG) + END;

        String out = insertBefore(source, A_MAIN, header);
        out = insertAfter(out, A_MAIN, mainStart);
        out = insertBefore(out, A_FOG, preFog);
        return insertAfter(out, A_FOG, postFog);
    }

    private static String insertBefore(String source, String anchor, String text) {
        int at = source.indexOf(anchor);
        return source.substring(0, at) + text + source.substring(at);
    }

    private static String insertAfter(String source, String anchor, String text) {
        int at = source.indexOf(anchor) + anchor.length();
        return source.substring(0, at) + text + source.substring(at);
    }

    private static void dump(ResourceLocation name, String source) {
        try {
            Path dir = FMLPaths.GAMEDIR.get().resolve("logs").resolve("atmospheralib-shaders");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name.getPath().replace('/', '_')), source);
        } catch (IOException | RuntimeException e) {
            AtmospheraLib.LOGGER.debug("Could not dump {}: {}", name, e.toString());
        }
    }

    /** Defines for the scene copies, added to the translucent pass only. */
    public static void addSceneDefines(java.util.function.Consumer<String> define) {
        AtmosState state = AtmosState.current();
        if (state.sceneDepth()) {
            define.accept("ATMOS_SCENE_DEPTH");
        }
        if (state.sceneColor()) {
            define.accept("ATMOS_SCENE_COLOR");
        }
    }
}
