package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.mesh.AtmosMaterial;
import com.solarbiscuit.atmosphera.lib.mesh.BlockMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import me.jellysquid.mods.sodium.client.model.light.data.QuadLightData;
import me.jellysquid.mods.sodium.client.model.quad.BakedQuadView;
import me.jellysquid.mods.sodium.client.render.chunk.compile.ChunkBuildBuffers;
import me.jellysquid.mods.sodium.client.render.chunk.compile.buffers.ChunkModelBuilder;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderContext;
import me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.material.Material;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexEncoder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Runs the active features' block mesh hooks once per block and writes their bits: material bits by swapping in a
 * cached Material variant, light bits into each vertex right before it is pushed. Each chunk-builder thread owns its
 * BlockRenderer, so these fields are thread-confined.
 * <p>
 * The hooks run lazily, when the block hands its first quad list (or CCL buffer) to the mesh: Xenon calls
 * {@code renderModel} for every non-air block of a section, and most of those (stone inside the terrain, the seabed
 * under the top layer) have no visible face. With no active mesh feature the cost is one volatile read per such block.
 */
@Mixin(value = BlockRenderer.class, remap = false)
public abstract class BlockRendererMixin {
    @Shadow @Final private ChunkVertexEncoder.Vertex[] vertices;

    @Unique private static final BlockMeshHook[] NO_HOOKS = new BlockMeshHook[0];
    @Unique private BlockRenderContext atmos$ctx;
    @Unique private boolean atmos$resolved;
    @Unique private BlockMeshHook[] atmos$hooks;
    @Unique private BlockState atmos$state;
    @Unique private int atmos$materialBits;
    @Unique private int atmos$lightBits;
    @Unique private boolean atmos$perVertex;

    @Inject(method = "renderModel", at = @At("HEAD"))
    private void atmos$begin(BlockRenderContext ctx, ChunkBuildBuffers buffers, CallbackInfo ci) {
        this.atmos$ctx = ctx;
        this.atmos$resolved = false;
    }

    /** Every face list, including Xenon's solid-leaf path, passes its Material through here. */
    @ModifyVariable(method = "renderQuadList", at = @At("HEAD"), argsOnly = true)
    private Material atmos$quadMaterial(Material material) {
        return atmos$withBits(material);
    }

    /** Custom (CCL) block renderers flush their buffered quads with the block's Material. */
    @ModifyArg(method = "renderModel", require = 0, at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/compat/ccl/SinkingVertexBuilder;flush(Lme/jellysquid/mods/sodium/client/render/chunk/compile/buffers/ChunkModelBuilder;Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;Lorg/joml/Vector3fc;)Z"),
            index = 1)
    private Material atmos$customMaterial(Material material) {
        return atmos$withBits(material);
    }

    @Unique
    private Material atmos$withBits(Material material) {
        if (!this.atmos$resolved) {
            atmos$classify();
        }
        int bits = this.atmos$materialBits;
        return bits == 0 ? material : ((AtmosMaterial) material).atmos$withExtraBits(bits);
    }

    @Unique
    private void atmos$classify() {
        this.atmos$resolved = true;
        AtmosState state = AtmosState.current();
        BlockMeshHook[] hooks = state.blockHooks();
        // Only hooks of LIGHT_BITS features get the light calls (fewer calls, and those call sites stay monomorphic).
        BlockMeshHook[] lightHooks = state.lightBits() ? state.lightHooks() : NO_HOOKS;
        int material = 0;
        int light = 0;
        boolean perVertex = false;
        if (hooks.length != 0) {
            BlockRenderContext ctx = this.atmos$ctx;
            BlockState blockState = ctx.state();
            BlockPos pos = ctx.pos();
            BlockAndTintGetter level = ctx.localSlice();
            for (BlockMeshHook hook : hooks) {
                material |= hook.materialBits(blockState, pos, level);
            }
            for (BlockMeshHook hook : lightHooks) {
                light |= hook.lightBits(blockState, pos, level);
                perVertex |= hook.perVertex(blockState);
            }
            this.atmos$state = blockState;
        }
        this.atmos$hooks = lightHooks;
        this.atmos$materialBits = material;
        this.atmos$lightBits = light;
        this.atmos$perVertex = perVertex;
    }

    @Inject(method = "writeGeometry", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/render/chunk/vertex/builder/ChunkMeshBufferBuilder;push([Lme/jellysquid/mods/sodium/client/render/chunk/vertex/format/ChunkVertexEncoder$Vertex;Lme/jellysquid/mods/sodium/client/render/chunk/terrain/material/Material;)V"))
    private void atmos$lightBits(BlockRenderContext ctx, ChunkModelBuilder builder, Vec3 offset, Material material,
                                 BakedQuadView quad, int[] colors, QuadLightData light, CallbackInfo ci) {
        int blockBits = this.atmos$lightBits;
        if (blockBits == 0 && !this.atmos$perVertex) {
            return;
        }
        Vector3fc origin = ctx.origin();
        for (ChunkVertexEncoder.Vertex vertex : this.vertices) {
            int bits = blockBits;
            if (this.atmos$perVertex) {
                for (BlockMeshHook hook : this.atmos$hooks) {
                    if (hook.perVertex(this.atmos$state)) {
                        bits = hook.vertexLightBits(this.atmos$state, bits,
                                vertex.x - origin.x(), vertex.y - origin.y(), vertex.z - origin.z());
                    }
                }
            }
            vertex.light = VertexBits.packLight(vertex.light, bits);
        }
    }
}
