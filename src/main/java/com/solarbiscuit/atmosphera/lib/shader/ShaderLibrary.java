package com.solarbiscuit.atmosphera.lib.shader;

/**
 * GLSL functions the lib provides to every program of a kind (0.11.0 "Shared"; the mods each had a copy). Function
 * definitions only, no uniforms of their own: an unused function costs nothing.
 */
public final class ShaderLibrary {
    private ShaderLibrary() {
    }

    /**
     * In every patched terrain program (Xenon) and every LOD program (Voxy host), after the AtmosFrame block (the rain
     * rings read {@code atmos_Time}).
     */
    public static final String SURFACE = """
            // Rec. 709 luminance (0.11.0: in LOD programs too).
            float atmos_lum(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }
            // Two uniform numbers in [0, 1) from an integer cell (a fast integer hash).
            vec2 atmos_hash22(vec2 atmosCell) {
                uvec2 atmosQ = uvec2(ivec2(atmosCell)) * uvec2(1597334673u, 3812015801u);
                uint atmosN = (atmosQ.x ^ atmosQ.y) * 1597334673u;
                return vec2(uvec2(atmosN, atmosN * 0x2c1b3c6du) >> 8u) / 16777216.0;
            }
            // Rain rings: stateless, one ring per half-block cell in two offset layers. A hash picks each cell's
            // centre, start and rate (2400-3599 rings per hour, whole cycles, so the hourly wrap never jumps); the
            // radius grows and the ring fades. Returns the slope (d/dx, d/dz) at a world position.
            vec2 atmos_rainRings(vec2 atmosXZ) {
                vec2 atmosSum = vec2(0.0);
                for (int atmosL = 0; atmosL < 2; atmosL++) {
                    vec2 atmosQ = atmosXZ * 2.0 + vec2(0.5 * float(atmosL));
                    vec2 atmosCell = floor(atmosQ);
                    vec2 atmosF = fract(atmosQ);
                    vec2 atmosH = atmos_hash22(atmosCell + vec2(float(atmosL) * 71.0, 0.0));
                    vec2 atmosCtr = 0.3 + 0.4 * atmos_hash22(atmosCell + vec2(13.0, 29.0 + float(atmosL)));
                    float atmosN = 2400.0 + floor(atmosH.x * 1200.0);
                    float atmosPh = fract(atmos_Time.y * (atmosN * (1.0 / 3600.0)) + atmosH.y);
                    vec2 atmosR = atmosF - atmosCtr;
                    float atmosD = length(atmosR);
                    float atmosX = (atmosD - atmosPh * 0.3) * 14.0;
                    float atmosBump = -2.0 * atmosX * exp(-atmosX * atmosX);
                    float atmosFade = (1.0 - atmosPh) * (1.0 - atmosPh);
                    atmosSum += atmosR / max(atmosD, 1e-3) * (atmosBump * atmosFade * 0.35);
                }
                return atmosSum;
            }
            """;

    /** Prepended to post-pass programs by {@code GlslPrograms.header()} and {@code header460()}. */
    public static final String POST = """
            // Interleaved gradient noise (Jimenez 2014): a cheap per-pixel offset that a blur or upsample smooths out.
            float atmos_ign(vec2 atmosP) {
                return fract(52.9829189 * fract(dot(atmosP, vec2(0.06711056, 0.00583715))));
            }
            // Henyey-Greenstein phase function for the cosine c between light and view, anisotropy g.
            float atmos_henyeyGreenstein(float c, float g) {
                float atmosG2 = g * g;
                return (1.0 - atmosG2) / (12.5663706 * pow(max(1.0 + atmosG2 - 2.0 * g * c, 1e-4), 1.5));
            }
            """;
}
