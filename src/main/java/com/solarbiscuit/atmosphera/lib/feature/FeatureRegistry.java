package com.solarbiscuit.atmosphera.lib.feature;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;

/**
 * All registered features. Registration happens in mod constructors (which Forge runs in parallel), so it is
 * synchronized; the registry is frozen at client setup, after which the list never changes and is read lock-free.
 */
public final class FeatureRegistry {
    private static final List<AtmosFeature> FEATURES = new ArrayList<>();
    private static volatile List<AtmosFeature> frozen;

    private FeatureRegistry() {
    }

    static synchronized AtmosFeature register(AtmosFeature.Builder builder, IntFunction<AtmosFeature> factory) {
        if (frozen != null) {
            throw new IllegalStateException("Atmosphera features must be registered during mod construction");
        }
        AtmosFeature feature = factory.apply(FEATURES.size());
        for (AtmosFeature existing : FEATURES) {
            if (existing.id().equals(feature.id())) {
                throw new IllegalArgumentException("Duplicate Atmosphera feature " + feature.id());
            }
        }
        FEATURES.add(feature);
        return feature;
    }

    public static synchronized void freeze() {
        if (frozen == null) {
            frozen = List.copyOf(FEATURES);
        }
    }

    /** Frozen list (empty before client setup). */
    public static List<AtmosFeature> all() {
        List<AtmosFeature> list = frozen;
        return list != null ? list : List.of();
    }
}
