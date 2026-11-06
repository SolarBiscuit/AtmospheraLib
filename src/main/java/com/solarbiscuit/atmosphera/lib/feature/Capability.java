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
    /** Lib-provided world data for terrain shaders (the column field); never provided by anything else. */
    WORLD_DATA,
    /** Developer/debug visuals; never provided by anything else. */
    DEBUG
}
