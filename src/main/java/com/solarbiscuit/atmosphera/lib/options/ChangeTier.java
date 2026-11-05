package com.solarbiscuit.atmosphera.lib.options;

/** What applying a changed setting costs. Changes made together are batched into the highest tier only. */
public enum ChangeTier {
    /** Read every frame (uniforms, CPU state): nothing to do. */
    LIVE,
    /** A shader define changed: the terrain programs are recompiled (no chunk rebuild). */
    RECOMPILE,
    /** Mesh data changed: every chunk is rebuilt (includes a recompile). */
    REMESH;

    public ChangeTier max(ChangeTier other) {
        return other.ordinal() > ordinal() ? other : this;
    }
}
