package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import me.jellysquid.mods.sodium.client.gui.reesesoptions.client.gui.frame.OptionPageFrame;
import com.google.common.collect.ImmutableList;
import com.solarbiscuit.atmosphera.lib.options.GroupState;
import me.jellysquid.mods.sodium.client.gui.options.Option;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Collapsible settings groups on Xenon's Reese's-style options screen (0.10.1; 0.10.0 hooked only Embeddium's own
 * frame, so on this screen every group stayed open). This frame has no option filter: it lays a page out straight from
 * {@link OptionGroup#getOptions()}, in its height pass, its layout pass and a lambda of the height pass. Each call gets
 * the group's options without those of closed Atmosphera groups ({@link GroupState#visible}); other mods' groups come
 * back unchanged (no copy).
 */
@Mixin(value = OptionPageFrame.class, remap = false)
public abstract class ReesesOptionPageFrameMixin {
    @Redirect(method = {"setupFrame", "buildFrame"}, at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gui/options/OptionGroup;getOptions()Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<Option<?>> atmos$visibleOptions(OptionGroup group) {
        return visible(group);
    }

    @Redirect(method = "lambda$setupFrame$1", require = 0, at = @At(value = "INVOKE",
            target = "Lme/jellysquid/mods/sodium/client/gui/options/OptionGroup;getOptions()Lcom/google/common/collect/ImmutableList;"))
    private ImmutableList<Option<?>> atmos$visibleOptionsLambda(OptionGroup group) {
        return visible(group);
    }

    private static ImmutableList<Option<?>> visible(OptionGroup group) {
        ImmutableList<Option<?>> all = group.getOptions();
        int hidden = 0;
        for (Option<?> option : all) {
            if (!GroupState.visible(option)) {
                hidden++;
            }
        }
        if (hidden == 0) {
            return all;
        }
        ImmutableList.Builder<Option<?>> out = ImmutableList.builderWithExpectedSize(all.size() - hidden);
        for (Option<?> option : all) {
            if (GroupState.visible(option)) {
                out.add(option);
            }
        }
        return out.build();
    }
}
