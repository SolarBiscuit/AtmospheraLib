package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.shader.ShaderPatcher;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderConstants;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Patches a program's top-level source where loadShader hands it to the parser. Hooking getShaderSource instead
 * is unsafe: source caches (Collections of Optimizations' MixinShaderLoaderSourceCache) answer at its HEAD, so a
 * RETURN hook there only sees the first load. Here the cache only ever holds raw sources.
 * Render thread only, so a static field carries the file name from HEAD to the parser call.
 */
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class ShaderLoaderMixin {
    @Unique
    private static ResourceLocation atmos$loading;

    @Inject(method = "loadShader", at = @At("HEAD"))
    private static void atmos$rememberName(ShaderType type, ResourceLocation name, ShaderConstants constants,
                                           CallbackInfoReturnable<?> cir) {
        atmos$loading = name;
    }

    @ModifyArg(method = "loadShader", at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gl/shader/ShaderParser;parseShader(Ljava/lang/String;Lme/jellysquid/mods/sodium/client/gl/shader/ShaderConstants;)Ljava/lang/String;"),
            index = 0)
    private static String atmos$patchSource(String source) {
        ResourceLocation name = atmos$loading;
        atmos$loading = null;
        return name == null ? source : ShaderPatcher.patch(name, source);
    }
}
