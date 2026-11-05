package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.gpu.ProgramCache;
import me.jellysquid.mods.sodium.client.gl.device.RenderDevice;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.ShaderChunkRenderer;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/** Lets {@link ProgramCache} drop the compiled terrain programs so Sodium recompiles them with new defines. */
@Mixin(value = ShaderChunkRenderer.class, remap = false)
public abstract class ShaderChunkRendererMixin implements ProgramCache.Recompilable {
    @Shadow @Final private Map<ChunkShaderOptions, GlProgram<ChunkShaderInterface>> programs;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void atmos$track(RenderDevice device, ChunkVertexType vertexType, CallbackInfo ci) {
        ProgramCache.track(this);
    }

    @Override
    public void atmos$deletePrograms() {
        for (GlProgram<ChunkShaderInterface> program : this.programs.values()) {
            program.delete();
        }
        this.programs.clear();
    }
}
