package com.solarbiscuit.atmosphera.lib.mixin.voxy;

import com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Hands Voxy's flattened shader sources to the text-patch backend (VoxyBridge, voxy_backend = TEXT_PATCH or AUTO
 * without NeoVoxy's API). The same {@code ShaderLoader.parse(String)} exists in NeoVoxy and the official Voxy.
 * Applied only when a mod with id "voxy" is installed (AtmosMixinPlugin); require = 0, so a Voxy without this method
 * just leaves the backend unused.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.gl.shader.ShaderLoader", remap = false)
public abstract class VoxyShaderLoaderMixin {
    @Inject(method = "parse(Ljava/lang/String;)Ljava/lang/String;", at = @At("RETURN"), cancellable = true, require = 0)
    private static void atmos$textPatch(String id, CallbackInfoReturnable<String> cir) {
        String patched = VoxyBridge.textPatch(id, cir.getReturnValue());
        if (patched != cir.getReturnValue()) {
            cir.setReturnValue(patched);
        }
    }
}
