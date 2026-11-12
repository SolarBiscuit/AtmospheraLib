package com.solarbiscuit.atmosphera.lib.options;

import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Objects;

/**
 * One setting, declared once through {@link AtmosConfig}: it lives in the mod's TOML file and appears in the
 * Atmosphera pages of Video Settings. {@link #get()} is cheap (Forge caches config values) and thread-safe.
 *
 * @param <T> Boolean, Integer, Double (shown as a percentage slider), an enum, or a String colour ({@link #rgb()})
 */
public final class AtmosOption<T> {
    /** COLOR (0.6.5): a #RRGGBB string, set in the TOML only (Embeddium's pages have no text field). */
    /** LIST (0.8.3): a list of strings, set in the TOML only. */
    enum Control { TOGGLE, SLIDER, PERCENT, CYCLE, COLOR, LIST }

    final AtmosConfig owner;
    final String path;
    final String nameKey;
    final String name;
    final String tooltip;
    final ChangeTier tier;
    final Control control;
    final int min;
    final int max;
    final int step;
    final Class<?> enumClass;
    final ForgeConfigSpec.ConfigValue<T> value;
    /** TOML only (0.8.3): kept out of Video Settings; set by {@link AtmosConfig#fileOnly(boolean)}. */
    boolean fileOnly;
    private Object lastApplied;

    AtmosOption(AtmosConfig owner, String path, String name, String tooltip, ChangeTier tier, Control control,
                int min, int max, int step, Class<?> enumClass, ForgeConfigSpec.ConfigValue<T> value) {
        this.owner = owner;
        this.path = path;
        this.nameKey = "atmosphera.option." + owner.modId() + "." + path;
        this.name = name;
        this.tooltip = tooltip;
        this.tier = tier;
        this.control = control;
        this.min = min;
        this.max = max;
        this.step = step;
        this.enumClass = enumClass;
        this.value = value;
    }

    public T get() {
        return value.get();
    }

    /** Sets the value in memory; {@link AtmosConfig#saveAndApply()} writes the file and applies the change. */
    public void set(T newValue) {
        value.set(newValue);
        owner.markDirty();
    }

    /**
     * A COLOR option's value as 0xRRGGBB (0.6.5). Accepts {@code #RRGGBB}, {@code RRGGBB}, {@code #RGB} and
     * {@code 0xRRGGBB}; anything else gives the default, so a typo never breaks the game.
     */
    public int rgb() {
        Object raw = value.get();
        int parsed = parseRgb(raw instanceof String text ? text : null);
        if (parsed >= 0) {
            return parsed;
        }
        Object def = value.getDefault();
        int fallback = parseRgb(def instanceof String text ? text : null);
        return Math.max(fallback, 0);
    }

    static int parseRgb(String text) {
        if (text == null) {
            return -1;
        }
        String t = text.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.startsWith("0x") || t.startsWith("0X")) {
            t = t.substring(2);
        }
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) {
            return -1;
        }
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public String path() {
        return path;
    }

    public ChangeTier tier() {
        return tier;
    }

    /** True when the option is set in the TOML only and not shown in Video Settings (0.8.3). */
    public boolean isFileOnly() {
        return fileOnly || control == Control.COLOR || control == Control.LIST;
    }

    Component displayName() {
        return Component.translatableWithFallback(nameKey, name);
    }

    Component displayTooltip() {
        String key = nameKey + ".tooltip";
        String tier = switch (this.tier) {
            case LIVE -> "";
            case RECOMPILE -> "\n\nApplying recompiles the terrain shaders.";
            case REMESH -> "\n\nApplying rebuilds all chunks.";
        };
        return Component.translatableWithFallback(key, tooltip).append(Component.literal(tier));
    }

    /** Returns this option's tier if its value changed since the last apply (and remembers the new value). */
    ChangeTier collectChange() {
        Object now;
        try {
            now = value.get();
        } catch (IllegalStateException notLoaded) {
            return null;
        }
        boolean first = lastApplied == null;
        boolean changed = !Objects.equals(now, lastApplied);
        lastApplied = now;
        return changed && !first ? tier : null;
    }
}
