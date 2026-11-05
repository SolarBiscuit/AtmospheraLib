package com.solarbiscuit.atmosphera.lib.mixin;

import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Applies the Sodium-port mixins only when Xenon/Embeddium is installed; runtime gating is done by AtmosState. */
public final class AtmosMixinPlugin implements IMixinConfigPlugin {
    private boolean sodiumPort;
    private boolean voxy;

    @Override
    public void onLoad(String mixinPackage) {
        sodiumPort = AtmosCompat.hasSodiumPort();
        voxy = AtmosCompat.isLoaded("voxy");
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".mixin.voxy.")) {
            return voxy;
        }
        return !mixinClassName.contains(".mixin.sodium.") || sodiumPort;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
