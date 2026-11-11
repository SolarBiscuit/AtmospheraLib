package com.solarbiscuit.atmosphera.lib.feature;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * One switchable Atmosphera feature (for example "gale:waving" or "torrente:caustics").
 * <p>
 * A feature is <em>active</em> when its own switch is on and nothing suppresses it (shaderpack, overlap, missing
 * shader anchors, AMDium...). Everything registered against a feature (shader snippets, mesh hooks, scene texture
 * requests, post passes) only runs while it is active. Read {@link #isActive()} from any thread: it reads the
 * published {@link com.solarbiscuit.atmosphera.lib.state.AtmosState} snapshot.
 * <p>
 * Create with {@link #builder(String, String)} during mod construction, then {@link Builder#register()}.
 */
public final class AtmosFeature {
    /** What a feature touches; decides the cost of switching it on or off. */
    public enum Uses {
        /** Inserts GLSL into the terrain programs: switching needs a program recompile. */
        TERRAIN_SHADER,
        /** Writes vertex bits while chunks are meshed: switching needs a chunk rebuild. */
        MESH_BITS,
        /** Writes light-coordinate high bits (needs Xenon's compact vertex format). Implies MESH_BITS. */
        LIGHT_BITS,
        /** Needs the scene depth copy before translucent terrain. */
        SCENE_DEPTH,
        /** Needs the scene colour copy before translucent terrain. */
        SCENE_COLOR,
        /**
         * Reads {@code atmos_FaceNormal} in fragment snippets (the face normal from screen-space derivatives, facing
         * the eye, computed once at the top of main() for every feature that needs it). Use with TERRAIN_SHADER.
         */
        FACE_NORMAL,
        /**
         * Draws into the world image from a post pass (0.7.0: Stratos clouds). A shaderpack replaces that image's
         * pipeline, so such a feature is off while one is active, even with respect_overlap off. Switching is live.
         */
        WORLD_PASS,
        /** Only CPU state, the lightmap or post passes: switching is live. */
        OTHER
    }

    private final String id;
    private final String ownerModId;
    private final String nameKey;
    private final Set<Capability> capabilities;
    private final Set<Uses> uses;
    private final BooleanSupplier enabled;
    private final BooleanSupplier respectOverlap;
    private final Object switchOption;
    private final int index;
    private final int minGl;

    private AtmosFeature(Builder b, int index) {
        this.switchOption = b.switchOption;
        this.id = b.id;
        this.ownerModId = b.ownerModId;
        this.nameKey = b.nameKey;
        this.capabilities = Set.copyOf(b.capabilities);
        this.uses = Set.copyOf(b.uses);
        this.enabled = b.enabled;
        this.respectOverlap = b.respectOverlap;
        this.index = index;
        this.minGl = b.minGl;
    }

    /** Lowest OpenGL version the feature runs on, as major * 10 + minor (46 for 4.6); 0 when any context works. */
    public int minGl() {
        return minGl;
    }

    public static Builder builder(String ownerModId, String name) {
        return new Builder(ownerModId, name);
    }

    /** {@code owner:name}, for example {@code gale:waving}. */
    public String id() {
        return id;
    }

    public String ownerModId() {
        return ownerModId;
    }

    /** Translation key of the display name. */
    public String nameKey() {
        return nameKey;
    }

    public Set<Capability> capabilities() {
        return capabilities;
    }

    public Set<Uses> uses() {
        return uses;
    }

    public boolean uses(Uses use) {
        return uses.contains(use);
    }

    /** The feature's own switch (config), ignoring suppression. */
    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    public boolean respectsOverlap() {
        return respectOverlap.getAsBoolean();
    }

    /** True while the feature runs. Thread-safe (snapshot read). */
    public boolean isActive() {
        return com.solarbiscuit.atmosphera.lib.state.AtmosState.current().isActive(this);
    }

    /** Why the feature is not active, or null when it is. */
    public String inactiveReason() {
        return com.solarbiscuit.atmosphera.lib.state.AtmosState.current().reason(this);
    }

    /** GLSL macro defined while the feature is active, for example {@code ATMOS_F_GALE_WAVING}. */
    public String macro() {
        return "ATMOS_F_" + id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "_");
    }

    /** The config option that switches this feature (shown with live status in the options tab), or null. */
    public Object switchOption() {
        return switchOption;
    }

    /** Dense index into snapshot arrays. */
    public int index() {
        return index;
    }

    @Override
    public String toString() {
        return id;
    }

    public static final class Builder {
        private final String ownerModId;
        private final String id;
        private String nameKey;
        private final EnumSet<Capability> capabilities = EnumSet.noneOf(Capability.class);
        private final EnumSet<Uses> uses = EnumSet.noneOf(Uses.class);
        private BooleanSupplier enabled = () -> true;
        private BooleanSupplier respectOverlap = () -> true;
        private Object switchOption;
        private int minGl;

        private Builder(String ownerModId, String name) {
            this.ownerModId = ownerModId;
            this.id = ownerModId + ":" + name;
            this.nameKey = "atmosphera.feature." + ownerModId + "." + name;
        }

        public Builder name(String translationKey) {
            this.nameKey = translationKey;
            return this;
        }

        public Builder provides(Capability... capabilities) {
            this.capabilities.addAll(java.util.List.of(capabilities));
            return this;
        }

        public Builder uses(Uses... uses) {
            this.uses.addAll(java.util.List.of(uses));
            return this;
        }

        /** Uses a config {@link com.solarbiscuit.atmosphera.lib.options.AtmosConfig.FeatureSwitch} for both switches. */
        public Builder switchedBy(com.solarbiscuit.atmosphera.lib.options.AtmosConfig.FeatureSwitch featureSwitch) {
            this.enabled = featureSwitch.enabledSupplier();
            this.respectOverlap = featureSwitch.respectOverlapSupplier();
            this.switchOption = featureSwitch.enabled();
            return this;
        }

        /** The feature's on/off switch, usually a config value. Read on the render thread at refresh. */
        public Builder enabledWhen(BooleanSupplier enabled) {
            this.enabled = enabled;
            return this;
        }

        /**
         * The feature needs at least this OpenGL version (its own programs use a newer GLSL version or GL 4.x
         * functions). On an older context it stays inactive with the reason shown in status and the options tab.
         */
        public Builder requiresGl(int major, int minor) {
            this.minGl = major * 10 + minor;
            return this;
        }

        /** Whether the overlap guard may suppress it (usually a per-feature config value). */
        public Builder respectOverlapWhen(BooleanSupplier respectOverlap) {
            this.respectOverlap = respectOverlap;
            return this;
        }

        public AtmosFeature register() {
            if (uses.isEmpty()) {
                uses.add(Uses.OTHER);
            }
            return FeatureRegistry.register(this, index -> new AtmosFeature(this, index));
        }
    }
}
