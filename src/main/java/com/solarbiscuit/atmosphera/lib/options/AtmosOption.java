package com.solarbiscuit.atmosphera.lib.options;

import net.minecraft.network.chat.Component;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.Objects;

/**
 * One setting, declared once through {@link AtmosConfig}: it lives in the mod's TOML file and appears in the
 * Atmosphera pages of Video Settings. {@link #get()} is cheap (Forge caches config values) and thread-safe.
 *
 * @param <T> Boolean, Integer, Double (shown as a percentage slider) or an enum
 */
public final class AtmosOption<T> {
    enum Control { TOGGLE, SLIDER, PERCENT, CYCLE }

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

    public String path() {
        return path;
    }

    public ChangeTier tier() {
        return tier;
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
