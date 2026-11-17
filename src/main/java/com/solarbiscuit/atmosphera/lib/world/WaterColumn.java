package com.solarbiscuit.atmosphera.lib.world;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FluidState;

/** Water surface lookups on the CPU (0.11.0 "Shared"; the GLSL side is {@code atmos_waterTop}). */
public final class WaterColumn {
    private WaterColumn() {
    }

    /**
     * The height of a water surface in the block at {@code y} or the one below it (the first that holds water), or NaN
     * when neither does, or when the water goes on above it (deeper than the surface). For something standing in or
     * on water at {@code y}: a camera, an entity's feet. {@code scratch} is overwritten. Thread of the level.
     */
    public static float surfaceNear(BlockGetter level, int x, int y, int z, BlockPos.MutableBlockPos scratch) {
        for (int dy = 0; dy >= -1; dy--) {
            scratch.set(x, y + dy, z);
            FluidState fluid = level.getFluidState(scratch);
            if (fluid.is(FluidTags.WATER)) {
                scratch.set(x, y + dy + 1, z);
                if (level.getFluidState(scratch).is(FluidTags.WATER)) {
                    return Float.NaN;
                }
                scratch.set(x, y + dy, z);
                return y + dy + fluid.getHeight(level, scratch);
            }
        }
        return Float.NaN;
    }
}
