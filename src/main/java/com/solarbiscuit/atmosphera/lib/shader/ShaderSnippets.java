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
        ShaderHook[] hooks = ShaderHook.values();
        AtmosFeature[][] table = new AtmosFeature[hooks.length][];
        for (ShaderHook hook : hooks) {
            java.util.Set<AtmosFeature> features = new java.util.LinkedHashSet<>();
            for (Snippet s : SNIPPETS) {
                if (s.hook() == hook) {
                    features.add(s.feature());
                }
            }
            table[hook.ordinal()] = features.toArray(new AtmosFeature[0]);
        }
        byHook = table;
    }

    /**
     * 0.10.3: after {@link #freeze} the snippet list never changes, so {@link #any} reads this table of features per
     * hook without the lock and without allocating (it runs per frame from the LOD bridge).
     */
    private static volatile AtmosFeature[][] byHook;

    /**
     * 0.11.3: {@link #render} per hook after {@link #freeze}. The text depends only on the snippet list (the feature
     * state is in its {@code #if defined} guards), so it never changes once frozen.
     */
    private static String[] rendered;

    /** True once {@link #freeze} ran: the snippet list, and so every {@link #render} result, is final. */
    public static boolean frozen() {
        return byHook != null;
    }

    /** All snippets for a hook, wrapped in their feature and pass guards. */
    public static synchronized String render(ShaderHook hook) {
        if (rendered != null && rendered[hook.ordinal()] != null) {
            return rendered[hook.ordinal()];
        }
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
        String text = out.toString();
        if (frozen) {
            if (rendered == null) {
                rendered = new String[ShaderHook.values().length];
            }
            rendered[hook.ordinal()] = text;
        }
        return text;
    }

    public static synchronized int count() {
        return SNIPPETS.size();
    }

    /** Whether any snippet of an active feature (per {@code active}) is registered for the hook. Any thread. */
    public static boolean any(java.util.function.Predicate<AtmosFeature> active, ShaderHook hook) {
        AtmosFeature[][] table = byHook;
        if (table == null) {
            return anyBeforeFreeze(active, hook);
        }
        for (AtmosFeature feature : table[hook.ordinal()]) {
            if (active.test(feature)) {
                return true;
            }
        }
        return false;
    }

    /** {@link #any(java.util.function.Predicate, ShaderHook)} for either of two hooks, without a varargs array. */
    public static boolean any(java.util.function.Predicate<AtmosFeature> active, ShaderHook first, ShaderHook second) {
        return any(active, first) || any(active, second);
    }

    /** Whether any snippet of an active feature (per {@code active}) is registered for one of the hooks. */
    public static boolean any(java.util.function.Predicate<AtmosFeature> active, ShaderHook... hooks) {
        for (ShaderHook hook : hooks) {
            if (any(active, hook)) {
                return true;
            }
        }
        return false;
    }

    private static synchronized boolean anyBeforeFreeze(java.util.function.Predicate<AtmosFeature> active, ShaderHook hook) {
        for (Snippet s : SNIPPETS) {
            if (s.hook() == hook && active.test(s.feature())) {
                return true;
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
