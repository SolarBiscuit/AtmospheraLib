package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.shader.ShaderPatcher;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.render.chunk.shader.ChunkShaderOptions;
import me.jellysquid.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Adds Atmosphera's defines to each terrain program: the pass ({@code ATMOS_PASS_SOLID/CUTOUT/TRANSLUCENT}), one
 * macro per active terrain-shader feature, and the scene-copy samplers in the translucent pass. Programs are compiled
 * lazily, so this always reflects the snapshot at compile time; a change in it triggers a recompile.
 */
@Mixin(value = ChunkShaderOptions.class, remap = false)
public abstract class ChunkShaderOptionsMixin {
    @Shadow
    public abstract TerrainRenderPass pass();

    @Redirect(method = "constants", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gl/shader/ShaderConstants$Builder;build()Lme/jellysquid/mods/sodium/client/gl/shader/ShaderConstants;"))
    private ShaderConstants atmos$addDefines(ShaderConstants.Builder builder) {
        TerrainRenderPass pass = this.pass();
        if (pass.isReverseOrder()) {
            builder.add("ATMOS_PASS_TRANSLUCENT");
            ShaderPatcher.addSceneDefines(builder::add);
        } else if (pass.supportsFragmentDiscard()) {
            builder.add("ATMOS_PASS_CUTOUT");
        } else {
            builder.add("ATMOS_PASS_SOLID");
        }
        AtmosState.current().forEachFeatureDefine(builder::add);
        return builder.build();
    }
}
