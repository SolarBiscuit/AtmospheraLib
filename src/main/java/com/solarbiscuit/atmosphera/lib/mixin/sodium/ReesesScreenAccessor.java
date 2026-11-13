package com.solarbiscuit.atmosphera.lib.mixin.sodium;

import me.jellysquid.mods.sodium.client.gui.reesesoptions.client.gui.SodiumVideoOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Xenon's Reese's-style options screen (0.10.1): its search text (while it holds text, closed groups show their
 * matching options) and the tab it opens on (for the dev flag settings_screen). Both are static on this screen.
 */
@Mixin(value = SodiumVideoOptionsScreen.class, remap = false)
public interface ReesesScreenAccessor {
    @Accessor("lastSearch")
    static AtomicReference<String> atmos$lastSearch() {
        throw new AssertionError();
    }

    @Accessor("tabFrameSelectedTab")
    static AtomicReference<Component> atmos$selectedTab() {
        throw new AssertionError();
    }
}
