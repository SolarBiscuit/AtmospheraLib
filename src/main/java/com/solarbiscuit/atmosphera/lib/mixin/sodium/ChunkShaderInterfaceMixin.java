package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.SceneTextures;
import com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers;
import me.jellysquid.mods.sodium.client.gl.shader.GlProgram;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderInterface;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ShaderBindingContext;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Attaches the AtmosFrame block, the scene-copy samplers and the features' terrain samplers to each chunk program. Raw GL lookups are used instead of
 * Sodium's bindUniform/bindUniformBlock, which throw when the GLSL compiler drops an unused uniform.
 */
@Mixin(value = ChunkShaderInterface.class, remap = false)
public abstract class ChunkShaderInterfaceMixin {
    @Unique private int atmos$depthSampler = -1;
    @Unique private int atmos$colorSampler = -1;
    @Unique private int[] atmos$featureSamplers;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void atmos$bindResources(ShaderBindingContext context, ChunkShaderOptions options, CallbackInfo ci) {
        if (!(context instanceof GlProgram<?> program)) {
            return;
        }
        int handle = program.handle();
        int block = GL31C.glGetUniformBlockIndex(handle, "AtmosFrame");
        if (block != GL31C.GL_INVALID_INDEX) {
            GL31C.glUniformBlockBinding(handle, block, FrameUniforms.binding());
        }
        this.atmos$depthSampler = GL20C.glGetUniformLocation(handle, "atmos_SceneDepth");
        this.atmos$colorSampler = GL20C.glGetUniformLocation(handle, "atmos_SceneColor");
        this.atmos$featureSamplers = TerrainSamplers.locate(handle);
    }

    /** Runs with the program bound, once per pass. */
    @Inject(method = "setupState", at = @At("TAIL"))
    private void atmos$setupState(CallbackInfo ci) {
        TerrainSamplers.bind(this.atmos$featureSamplers);
        if (this.atmos$depthSampler < 0 && this.atmos$colorSampler < 0) {
            return;
        }
        if (this.atmos$depthSampler >= 0) {
            GL20C.glUniform1i(this.atmos$depthSampler, SceneTextures.DEPTH_UNIT);
        }
        if (this.atmos$colorSampler >= 0) {
            GL20C.glUniform1i(this.atmos$colorSampler, SceneTextures.COLOR_UNIT);
        }
        SceneTextures.bind();
    }
}
