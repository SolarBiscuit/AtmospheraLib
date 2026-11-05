package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.SceneTextures;
import com.solarbiscuit.atmosphera.lib.shader.ShaderPatcher;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import me.jellysquid.mods.sodium.client.gl.device.CommandList;
import me.jellysquid.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import me.jellysquid.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import me.jellysquid.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Per-pass frame work: the AtmosFrame block is written once per frame (first terrain pass) and rebound for the
 * others; the translucent pass is drawn after opaque/cutout terrain and entities, so its start is where the scene is
 * copied.
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class DefaultChunkRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    private void atmos$beforePass(ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
                                  TerrainRenderPass pass, CameraTransform camera, CallbackInfo ci) {
        if (!ShaderPatcher.isPatching()) {
            return;
        }
        FrameUniforms.setProjection(matrices.projection());
        if (pass.isReverseOrder()) {
            AtmosState state = AtmosState.current();
            SceneTextures.copyBeforeTranslucent(state.sceneDepth(), state.sceneColor());
        }
        FrameUniforms.update(AtmosClient.frame());
    }
}
