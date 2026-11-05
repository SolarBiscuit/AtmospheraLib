package com.solarbiscuit.atmosphera.lib.mesh;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.material.FluidState;

/** Material bits for fluid quads. Same threading rules as {@link BlockMeshHook}. */
@FunctionalInterface
public interface FluidMeshHook {
    int materialBits(FluidState state, BlockPos pos, BlockAndTintGetter level);

    /** Material bits of this fluid on Voxy LOD terrain (state only, Voxy's model bake thread). */
    default int lodMaterialBits(FluidState state) {
        return 0;
    }
}
