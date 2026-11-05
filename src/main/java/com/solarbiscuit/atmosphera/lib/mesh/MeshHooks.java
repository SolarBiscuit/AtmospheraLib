package com.solarbiscuit.atmosphera.lib.mesh;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * Registered mesh hooks, each tied to a feature. The {@link com.solarbiscuit.atmosphera.lib.state.AtmosState}
 * snapshot resolves them into plain arrays of the hooks whose features are active, which the Xenon mixins read.
 */
public final class MeshHooks {
    public record BlockEntry(AtmosFeature feature, BlockMeshHook hook) {
    }

    public record FluidEntry(AtmosFeature feature, FluidMeshHook hook) {
    }

    private static final List<BlockEntry> BLOCK = new ArrayList<>();
    private static final List<FluidEntry> FLUID = new ArrayList<>();

    private MeshHooks() {
    }

    /** The feature must declare {@link AtmosFeature.Uses#MESH_BITS} (and LIGHT_BITS if it writes light bits). */
    public static synchronized void registerBlock(AtmosFeature feature, BlockMeshHook hook) {
        requireMesh(feature);
        BLOCK.add(new BlockEntry(feature, hook));
    }

    public static synchronized void registerFluid(AtmosFeature feature, FluidMeshHook hook) {
        requireMesh(feature);
        FLUID.add(new FluidEntry(feature, hook));
    }

    public static synchronized List<BlockEntry> blockHooks() {
        return List.copyOf(BLOCK);
    }

    public static synchronized List<FluidEntry> fluidHooks() {
        return List.copyOf(FLUID);
    }

    private static void requireMesh(AtmosFeature feature) {
        if (!feature.uses(AtmosFeature.Uses.MESH_BITS) && !feature.uses(AtmosFeature.Uses.LIGHT_BITS)) {
            throw new IllegalArgumentException(feature.id() + " registers a mesh hook but does not declare Uses.MESH_BITS");
        }
    }
}
