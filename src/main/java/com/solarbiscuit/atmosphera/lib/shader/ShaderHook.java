package com.solarbiscuit.atmosphera.lib.shader;

/**
 * Places in Xenon's terrain program ({@code sodium:blocks/block_layer_opaque}) where snippets are inserted, and the
 * {@code LOD_*} places in Voxy's LOD terrain program (see {@code compat.voxy.VoxyHostGlsl}).
 * Variables a snippet may read or write are listed per hook.
 */
public enum ShaderHook {
    /** Vertex stage, global scope before {@code main}: functions, constants. */
    VERT_DECL(true),
    /**
     * Vertex stage, right after {@code _vert_init()}. May modify {@code _vert_position} (chunk-local),
     * {@code _vert_color}, {@code _vert_tex_diffuse_coord}. Reads {@code atmos_MaterialBits}, {@code atmos_LightBits}.
     */
    VERT_POST_INIT(true),
    /**
     * Vertex stage, after the camera-relative {@code vec3 position} is computed, before projection and fog distance.
     * May modify {@code position}. World position: {@code atmos_worldPos(position)} (wrapped every 4096 blocks).
     */
    VERT_POSITION(true),
    /** Vertex stage, after {@code v_Color} (vertex colour times lightmap) is written. May modify {@code v_Color}. */
    VERT_POST_LIGHT(true),
    /** Fragment stage, global scope before {@code main}. */
    FRAG_DECL(false),
    /**
     * Fragment stage, before fog. May modify {@code vec4 diffuseColor}. Reads {@code v_AtmosMaterialBits},
     * {@code v_AtmosLightBits}, {@code v_AtmosRelPos}, {@code v_Color}, {@code v_TexCoord}.
     */
    FRAG_PRE_FOG(false),
    /** Fragment stage, after fog. May modify {@code fragColor}. */
    FRAG_POST_FOG(false),
    /**
     * Voxy LOD program, global scope of both stages ({@code VOXY_EXT_STAGE_VERTEX} / {@code VOXY_EXT_STAGE_FRAGMENT}
     * say which). The AtmosFrame block, vertex-bit macros and {@code atmos_worldPos} are declared before it.
     */
    LOD_DECL(false),
    /**
     * Voxy LOD vertex stage. May modify {@code vec3 position} (camera-relative LOD corner, blocks). Reads
     * {@code atmos_MaterialBits} and the {@code VoxyExtVertex v} (face, quadFlags, lodScale, voxelFracY, ...).
     */
    LOD_VERTEX(true),
    /**
     * Voxy LOD fragment stage, after Voxy's own colour. Reads and modifies {@code VoxyExtFragment f}
     * ({@code f.colour}, or a premultiplied colour plus {@code f.keep} in the translucent pass); reads
     * {@code atmos_MaterialBits}.
     */
    LOD_FRAGMENT(false);

    private final boolean vertex;

    ShaderHook(boolean vertex) {
        this.vertex = vertex;
    }

    public boolean isVertex() {
        return vertex;
    }
}
