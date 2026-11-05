package com.solarbiscuit.atmosphera.lib.mixin.minecraft;

import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every full renderer reload (Oculus shaderpack toggles, F3+A, video settings, Atmosphera's own REMESH) passes here
 * first. Refreshing the snapshot before the reload guarantees that no chunk is meshed with stale hooks, in particular
 * that no light bits are written once a shaderpack is active (packs read the light coordinate unmasked).
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "allChanged", at = @At("HEAD"))
    private void atmos$beforeReload(CallbackInfo ci) {
        AtmosState.beforeRendererReload();
    }
}
