package com.solarbiscuit.atmosphera.lib.compat;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.shader.ShaderPatcher;

/**
 * Decides whether a feature may run, and if not, why. Two kinds of reasons:
 * <ul>
 *   <li>hard: the terrain pipeline cannot take the feature (a shaderpack replaced the chunk programs, AMDium draws
 *       terrain, anchors missing, another jar replaced Xenon's shader, non-compact vertex format). Never overridable.</li>
 *   <li>overlap: a shaderpack or another mod already provides the same capability. Skipped when the feature's
 *       {@code respect_overlap} option is off.</li>
 * </ul>
 */
public final class OverlapGuard {
    /** Everything the decision depends on, probed once per refresh. */
    public record Environment(boolean sodiumPort, String anchorProblem, String shaderJarOverride, boolean amdiumActive,
                              ShaderPackState.Pack pack, PackProfiles.Result packResult, boolean compactVertexFormat) {
        public static Environment probe() {
            ShaderPackState.Pack pack = ShaderPackState.probe();
            return new Environment(AtmosCompat.hasSodiumPort(), ShaderPatcher.anchorProblem(),
                    AtmosCompat.sodiumShaderOverride(), AtmosCompat.isAmdiumActive(), pack, PackProfiles.evaluate(pack),
                    compactFormat());
        }

        private static boolean compactFormat() {
            try {
                return me.jellysquid.mods.sodium.client.SodiumClientMod.options().performance.useCompactVertexFormat;
            } catch (Throwable e) {
                return false;
            }
        }

        /** Same environment as far as feature decisions go (pack options included). */
        public boolean sameAs(Environment other) {
            return other != null && java.util.Objects.equals(anchorProblem, other.anchorProblem)
                    && amdiumActive == other.amdiumActive && pack.equals(other.pack)
                    && compactVertexFormat == other.compactVertexFormat;
        }
    }

    private OverlapGuard() {
    }

    /** True for reasons the feature's respect_overlap option can override (as opposed to hard pipeline reasons). */
    public static boolean isOverlapReason(String reason) {
        return reason != null && (reason.contains(" provides ") || reason.startsWith("unknown shaderpack"));
    }

    /** Null when the feature may run; otherwise a short human-readable reason. */
    public static String reason(AtmosFeature feature, Environment env) {
        if (!feature.isEnabled()) {
            return "off in config";
        }
        if (feature.minGl() > 0 && !com.solarbiscuit.atmosphera.lib.gpu.GlCaps.atLeast(feature.minGl() / 10, feature.minGl() % 10)) {
            return "needs OpenGL " + feature.minGl() / 10 + "." + feature.minGl() % 10 + " (this driver: "
                    + com.solarbiscuit.atmosphera.lib.gpu.GlCaps.describe() + ")";
        }
        boolean pipeline = feature.uses(AtmosFeature.Uses.TERRAIN_SHADER) || feature.uses(AtmosFeature.Uses.MESH_BITS)
                || feature.uses(AtmosFeature.Uses.LIGHT_BITS) || feature.uses(AtmosFeature.Uses.SCENE_DEPTH)
                || feature.uses(AtmosFeature.Uses.SCENE_COLOR);
        if (pipeline) {
            if (!env.sodiumPort()) {
                return "needs Xenon or Embeddium";
            }
            if (env.pack().active()) {
                return "shaderpack '" + env.pack().name() + "' replaces the terrain shaders";
            }
            if (env.anchorProblem() != null) {
                return env.anchorProblem();
            }
            if (env.shaderJarOverride() != null) {
                return env.shaderJarOverride() + " replaces Xenon's terrain shader";
            }
            if (env.amdiumActive()) {
                return "AMDium draws terrain (needs AMDium's Atmosphera hook)";
            }
            if (feature.uses(AtmosFeature.Uses.LIGHT_BITS) && !env.compactVertexFormat()) {
                return "needs Xenon's compact vertex format";
            }
        }
        if (!feature.respectsOverlap()) {
            return null;
        }
        if (env.pack().active()) {
            PackProfiles.Result result = env.packResult();
            for (Capability cap : feature.capabilities()) {
                if (!PackProfiles.isCheckable(cap)) {
                    continue;
                }
                if (!result.known()) {
                    return "unknown shaderpack '" + env.pack().name() + "' (assumed to provide " + cap + ")";
                }
                String why = result.provided().get(cap);
                if (why != null) {
                    return "shaderpack '" + env.pack().name() + "' (" + result.profileName() + ") provides " + cap + " " + why;
                }
            }
        }
        for (Capability cap : feature.capabilities()) {
            for (String modId : PackProfiles.modsProviding(cap)) {
                if (AtmosCompat.isLoadedAny(modId)) {
                    return "mod '" + modId + "' provides " + cap;
                }
            }
        }
        return null;
    }
}
