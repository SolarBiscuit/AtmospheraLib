package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import org.embeddedt.embeddium.gui.EmbeddiumVideoOptionsScreen;
import org.embeddedt.embeddium.gui.frame.components.SearchTextFieldModel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The options screen's search model (0.10.0): while it holds text, closed groups show their matching options. */
@Mixin(value = EmbeddiumVideoOptionsScreen.class, remap = false)
public interface VideoOptionsScreenAccessor {
    @Accessor("searchTextModel")
    SearchTextFieldModel atmos$searchModel();

    /** The tab Video Settings opens on (static, kept between openings); for the dev flag settings_screen. */
    @Accessor("tabFrameSelectedTab")
    static java.util.concurrent.atomic.AtomicReference<net.minecraft.network.chat.Component> atmos$selectedTab() {
        throw new AssertionError();
    }
}
