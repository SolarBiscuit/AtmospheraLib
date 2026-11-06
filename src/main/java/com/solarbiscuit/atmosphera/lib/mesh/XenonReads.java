package com.solarbiscuit.atmosphera.lib.mesh;

import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Neighbour reads for mesh hooks (see {@link MeshHooks#blockAt}). A class of its own so Xenon's {@code WorldSlice} is
 * only resolved once a hook actually runs, which only happens inside Xenon's chunk meshing.
 */
final class XenonReads {
    /** Per thread, for levels that are not a WorldSlice (other meshers); never shared between threads. */
    private static final ThreadLocal<BlockPos.MutableBlockPos> POS = ThreadLocal.withInitial(BlockPos.MutableBlockPos::new);

    private XenonReads() {
    }

    static BlockState blockAt(BlockGetter level, int x, int y, int z) {
        if (level instanceof WorldSlice slice) {
            return slice.getBlockState(x, y, z);
        }
        return level.getBlockState(POS.get().set(x, y, z));
    }
}
