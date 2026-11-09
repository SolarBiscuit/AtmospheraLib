package com.solarbiscuit.atmosphera.lib.mixin.minecraft;

import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The FINAL post stage (0.6.4): at the end of {@code renderLevel}, after the hand and before vanilla's post effects
 * and the GUI.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    @Inject(method = "renderLevel", at = @At("RETURN"))
    private void atmos$finalStage(float partialTick, long finishNanos, com.mojang.blaze3d.vertex.PoseStack poseStack,
                                  CallbackInfo ci) {
        AtmosClient.runFinal();
    }
}
