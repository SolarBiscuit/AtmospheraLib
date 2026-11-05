package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.mesh.AtmosMaterial;
import com.solarbiscuit.atmosphera.lib.mesh.FluidMeshHook;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.FluidRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.world.WorldSlice;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Fluid mesh hooks: material bits for fluid quads. The material chosen in render() is passed on to renderVanilla(). */
@Mixin(value = FluidRenderer.class, remap = false)
public abstract class FluidRendererMixin {
    @Unique private int atmos$materialBits;

    @Inject(method = "render", at = @At("HEAD"))
    private void atmos$classify(WorldSlice world, FluidState fluidState, BlockPos blockPos, BlockPos offset,
                                ChunkBuildBuffers buffers, CallbackInfo ci) {
        FluidMeshHook[] hooks = AtmosState.current().fluidHooks();
        int bits = 0;
        for (FluidMeshHook hook : hooks) {
            bits |= hook.materialBits(fluidState, blockPos, world);
        }
        this.atmos$materialBits = bits;
    }

    @ModifyVariable(method = "render", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    private Material atmos$materialVariant(Material material) {
        int bits = this.atmos$materialBits;
        return bits == 0 ? material : ((AtmosMaterial) material).atmos$withExtraBits(bits);
    }
}
