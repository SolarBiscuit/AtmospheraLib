package com.solarbiscuit.atmosphera.lib.shader;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * GLSL snippets the feature mods insert into the terrain program. Each snippet is compiled in only while its feature
 * is active (wrapped in {@code #ifdef} of the feature macro), so an inactive feature costs nothing on the GPU.
 * Register during mod construction.
 */
public final class ShaderSnippets {
    public static final int PASS_SOLID = 1;
    public static final int PASS_CUTOUT = 2;
    public static final int PASS_TRANSLUCENT = 4;
    public static final int PASS_ALL = PASS_SOLID | PASS_CUTOUT | PASS_TRANSLUCENT;

    public record Snippet(AtmosFeature feature, ShaderHook hook, int passes, int order, String code) {
    }

    private static final List<Snippet> SNIPPETS = new ArrayList<>();
    private static boolean frozen;

    private ShaderSnippets() {
    }

    /**
     * @param passes {@link #PASS_SOLID} | {@link #PASS_CUTOUT} | {@link #PASS_TRANSLUCENT}
     * @param order  lower runs first within a hook
     */
    public static synchronized void register(AtmosFeature feature, ShaderHook hook, int passes, int order, String code) {
        if (frozen) {
            throw new IllegalStateException("Atmosphera shader snippets must be registered during mod construction");
        }
        if (!feature.uses(AtmosFeature.Uses.TERRAIN_SHADER)) {
            throw new IllegalArgumentException(feature.id() + " registers a shader snippet but does not declare Uses.TERRAIN_SHADER");
        }
        SNIPPETS.add(new Snippet(feature, hook, passes, order, code));
    }

    public static synchronized void freeze() {
        frozen = true;
        SNIPPETS.sort(Comparator.comparingInt(Snippet::order));
    }

    /** All snippets for a hook, wrapped in their feature and pass guards. */
    public static synchronized String render(ShaderHook hook) {
        StringBuilder out = new StringBuilder();
        for (Snippet s : SNIPPETS) {
            if (s.hook() != hook) {
                continue;
            }
            out.append("#if defined(").append(s.feature().macro()).append(')');
            if ((s.passes() & PASS_ALL) != PASS_ALL) {
                out.append(" && (0");
                if ((s.passes() & PASS_SOLID) != 0) out.append(" || defined(ATMOS_PASS_SOLID)");
                if ((s.passes() & PASS_CUTOUT) != 0) out.append(" || defined(ATMOS_PASS_CUTOUT)");
                if ((s.passes() & PASS_TRANSLUCENT) != 0) out.append(" || defined(ATMOS_PASS_TRANSLUCENT)");
                out.append(')');
            }
            out.append("\n// ").append(s.feature().id()).append('\n').append(s.code());
            if (!s.code().endsWith("\n")) {
                out.append('\n');
            }
            out.append("#endif\n");
        }
        return out.toString();
    }

    public static synchronized int count() {
        return SNIPPETS.size();
    }

    /** Whether any snippet of an active feature (per {@code active}) is registered for one of the hooks. */
    public static synchronized boolean any(java.util.function.Predicate<AtmosFeature> active, ShaderHook... hooks) {
        for (Snippet s : SNIPPETS) {
            for (ShaderHook hook : hooks) {
                if (s.hook() == hook && active.test(s.feature())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Features with a snippet at one of the hooks, whether active or not. */
    public static synchronized java.util.Set<AtmosFeature> featuresAt(ShaderHook... hooks) {
        java.util.Set<AtmosFeature> out = new java.util.LinkedHashSet<>();
        for (Snippet s : SNIPPETS) {
            for (ShaderHook hook : hooks) {
                if (s.hook() == hook) {
                    out.add(s.feature());
                }
            }
        }
        return out;
    }
}
