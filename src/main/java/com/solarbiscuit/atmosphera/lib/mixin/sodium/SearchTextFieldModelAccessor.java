package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import org.embeddedt.embeddium.gui.frame.components.SearchTextFieldModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The search box's text (0.10.0). */
@Mixin(value = SearchTextFieldModel.class, remap = false)
public interface SearchTextFieldModelAccessor {
    @Accessor("text")
    String atmos$text();
}
