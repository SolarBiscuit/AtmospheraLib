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

    /**
     * 0.11.6: the height of the water surface met in the blocks {@code yLow - 1 .. yHigh + 1}, scanned top down (at
     * most 8 blocks, the top ones kept): the top of the first water block with no water above it, or NaN when there is
     * none. For a box that may cross the surface anywhere along its height (an elder guardian or a player standing
     * upright in deep water, whose feet are far below it, where {@link #surfaceNear} sees only water above). One more
     * read than blocks scanned. {@code scratch} is overwritten. Thread of the level.
     */
    public static float surfaceAcross(BlockGetter level, int x, int yLow, int yHigh, int z,
                                      BlockPos.MutableBlockPos scratch) {
        int top = yHigh + 1;
        int bottom = Math.max(yLow - 1, top - 7);
        scratch.set(x, top + 1, z);
        boolean aboveWater = level.getFluidState(scratch).is(FluidTags.WATER);
        for (int y = top; y >= bottom; y--) {
            scratch.set(x, y, z);
            FluidState fluid = level.getFluidState(scratch);
            boolean water = fluid.is(FluidTags.WATER);
            if (water && !aboveWater) {
                return y + fluid.getHeight(level, scratch);
            }
            aboveWater = water;
        }
        return Float.NaN;
    }
}
