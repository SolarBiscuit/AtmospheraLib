package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import com.solarbiscuit.atmosphera.lib.options.GroupState;
import me.jellysquid.mods.sodium.client.gui.options.Option;
import org.embeddedt.embeddium.gui.frame.OptionPageFrame;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Predicate;

/**
 * Collapsible settings groups (0.10.0): Embeddium lays a page out from its groups, keeping only the options its search
 * filter accepts. Both the height pass and the layout pass also drop the options of closed Atmosphera groups
 * ({@link GroupState#visible}); other mods' options are untouched.
 */
@Mixin(value = OptionPageFrame.class, remap = false)
public abstract class OptionPageFrameMixin {
    @Shadow
    @Final
    protected Predicate<Option<?>> optionFilter;

    @Redirect(method = {"setupFrame", "buildFrame"}, at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
            target = "Lorg/embeddedt/embeddium/gui/frame/OptionPageFrame;optionFilter:Ljava/util/function/Predicate;"))
    private Predicate<Option<?>> atmos$withGroups(OptionPageFrame frame) {
        Predicate<Option<?>> filter = this.optionFilter;
        return option -> filter.test(option) && GroupState.visible(option);
    }
}
