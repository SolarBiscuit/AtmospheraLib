package com.solarbiscuit.atmosphera.lib.wind;

import net.minecraft.world.level.Level;

/**
 * Source of wind for every Atmosphera mod (Gale waving, Torrente waves, particles).
 * <p>
 * Must be a pure function of synced state (game time, weather, position, dimension) so every client in multiplayer
 * sees the same wind without any networking, and so it is safe to call from any thread (chunk builders, Particle
 * Core's parallel particle ticking). Never allocate per call.
 */
public interface WindProvider {
    String name();

    /** Wind for the whole frame (goes into the {@code atmos_Wind} uniform). */
    void global(Level level, float partialTick, WindVector out);

    /** Wind at a position; may add local variation on top of {@link #global}. */
    void sample(Level level, double x, double y, double z, float partialTick, WindVector out);
}
