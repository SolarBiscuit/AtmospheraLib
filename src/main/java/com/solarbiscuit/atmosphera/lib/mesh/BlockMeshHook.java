package com.solarbiscuit.atmosphera.lib.mesh;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Writes Atmosphera vertex bits for block models while Xenon meshes a chunk section.
 * <p>
 * <b>Threading:</b> called on chunk-builder threads, many at once. Implementations must be pure: read only
 * immutable data (for example a {@code byte[]} indexed by block-state id, published through a volatile field) and the
 * given {@code level} (a WorldSlice copy). No allocation per call, no shared mutable state.
 * <p>
 * <b>Cost:</b> {@link #materialBits} and {@link #lightBits} run once per block, {@link #vertexLightBits} once per
 * vertex and only for states where {@link #perVertex} is true. Keep them to table lookups.
 */
public interface BlockMeshHook {
    /** Material bits for every quad of this block (OR-ed with other hooks' bits), using fields from {@link VertexBits}. */
    default int materialBits(BlockState state, BlockPos pos, BlockAndTintGetter level) {
        return 0;
    }

    /** Light bits for every vertex of this block (OR-ed with other hooks' bits). */
    default int lightBits(BlockState state, BlockPos pos, BlockAndTintGetter level) {
        return 0;
    }

    /** Whether {@link #vertexLightBits} must run for this state. */
    default boolean perVertex(BlockState state) {
        return false;
    }

    /**
     * Light bits for one vertex. {@code bits} is the block's combined light bits so far; return it with your field
     * set. {@code x, y, z} are block-local (0..1 for a full cube; models can reach outside).
     */
    default int vertexLightBits(BlockState state, int bits, float x, float y, float z) {
        return bits;
    }

    /** {@link #lodShape} flag: pin the bottom edge of a column's first block (plants, water plants). */
    int LOD_ANCHOR_BOTTOM = 1;
    /** {@link #lodShape} flag: pin the top edge of a column's first block (hanging plants). */
    int LOD_ANCHOR_TOP = 2;
    /** {@link #lodShape} flag: the block's LOD quads must not be merged (they get per-vertex offsets). */
    int LOD_UNMERGED = 4;

    /**
     * Material bits of this state on Voxy LOD terrain (same fields as {@link #materialBits}). LOD models are baked per
     * block state, so only the state is known: return what {@link #materialBits} would for the common case. Called on
     * Voxy's model bake thread; same purity rules.
     */
    default int lodMaterialBits(BlockState state) {
        return 0;
    }

    /** {@link #LOD_ANCHOR_BOTTOM} / {@link #LOD_ANCHOR_TOP} / {@link #LOD_UNMERGED} for this state's LOD quads. */
    default int lodShape(BlockState state) {
        return 0;
    }
}
