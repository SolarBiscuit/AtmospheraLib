package com.solarbiscuit.atmosphera.lib.feature;

/**
 * What a feature provides. The overlap guard suppresses a feature when something else (a shaderpack, another mod)
 * already provides one of its capabilities.
 */
public enum Capability {
    WAVING_FOLIAGE,
    WATER_WAVES,
    WATER_SHADING,
    CAUSTICS,
    UNDERWATER_FOG,
    BLOCKLIGHT_TINT,
    SKYLIGHT_TINT,
    COLORED_LIGHT,
    GODRAYS,
    /** Sun shadows cast by terrain (shaderpack shadow maps, Lumis heightfield shadows). */
    SUN_SHADOWS,
    /** Light that depends on the sun direction (faces turned away from the sun darker, entities lit from the sun). */
    DIRECTIONAL_LIGHT,
    /** Volumetric light shafts through the scene. */
    VOLUMETRIC_LIGHT,
    /** Atmospheric mist or haze with sun in-scatter, beyond vanilla fog. */
    ATMOSPHERIC_FOG,
    /** Screen-space or planar reflections. */
    REFLECTIONS,
    /** Bloom, lens flares or colour grading of the whole picture (0.6.4: Lumis lens effects). */
    POST_PROCESS,
    /** Clouds drawn by something other than vanilla (shaderpack clouds, Stratos) (0.7.0). */
    CLOUDS,
    /** The sky itself: dome colour, sun, moon and stars (shaderpack skies, Stratos 0.3) (0.7.0; reserved). */
    SKY,
    /** Shadows of clouds on the ground (shaderpack cloud shadows, Stratos 0.2) (0.7.0; reserved). */
    CLOUD_SHADOWS,
    /** Lib-provided world data for terrain shaders (the column field); never provided by anything else. */
    WORLD_DATA,
    /** Developer/debug visuals; never provided by anything else. */
    DEBUG
}
