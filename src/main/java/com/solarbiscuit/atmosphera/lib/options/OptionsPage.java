package com.solarbiscuit.atmosphera.lib.options;

import com.google.common.collect.ImmutableList;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.FeatureRegistry;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import me.jellysquid.mods.sodium.client.gui.options.OptionFlag;
import me.jellysquid.mods.sodium.client.gui.options.OptionGroup;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpact;
import me.jellysquid.mods.sodium.client.gui.options.OptionImpl;
import me.jellysquid.mods.sodium.client.gui.options.OptionPage;
import me.jellysquid.mods.sodium.client.gui.options.control.ControlValueFormatter;
import me.jellysquid.mods.sodium.client.gui.options.control.CyclingControl;
import me.jellysquid.mods.sodium.client.gui.options.control.SliderControl;
import me.jellysquid.mods.sodium.client.gui.options.control.TickBoxControl;
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.embeddedt.embeddium.client.gui.options.OptionIdentifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Atmosphera pages of Video Settings (Xenon/Embeddium options screen). RatPack's screen lists pages flat, with no
 * mod headings, so every page is named after its mod: "Atmosphera", "Gale", "Torrente" (a {@code pageSection} would
 * add a page of its own, "&lt;mod&gt;: &lt;section&gt;"; no mod uses one since 0.8.3). On a page every TOML section is a group
 * (groups are drawn apart), split again at each feature switch. A mod's main page starts with its status line.
 * 0.9.2: every mod's "defer to shaderpacks and other mods" switches sit in one group on the Atmosphera page; page names
 * carry the mod's icon (a one-glyph font, {@code assets/<modid>/font/atmos_icon.json}) when the mod ships one; choice
 * values are shown in normal case ("Vanilla Plus", not "VANILLA_PLUS").
 * Feature switches show live status: a suppressed feature is marked and its tooltip says why.
 */
public final class OptionsPage {
    /** Sodium's storage: values live in the configs; save() writes the TOML files and applies the changes. */
    private static final OptionStorage<Object> STORAGE = new OptionStorage<>() {
        private final Object data = new Object();

        @Override
        public Object getData() {
            return data;
        }

        @Override
        public void save() {
            AtmosConfig.saveAndApply();
        }
    };

    private OptionsPage() {
    }

    public static void register() {
        OptionGUIConstructionEvent.BUS.addListener(event -> {
            try {
                for (OptionPage page : build()) {
                    event.addPage(page);
                }
            } catch (Throwable e) {
                AtmospheraLib.LOGGER.error("Could not build the Atmosphera options page", e);
            }
        });
    }

    private static boolean loggedDefer;

    private static List<OptionPage> build() {
        // 0.9.2: the defer switches of every mod, for the Atmosphera page.
        OptionGroup.Builder defer = OptionGroup.createBuilder().add(header("Defer to shaderpacks and other mods",
                "Each switch below turns its feature off while a shaderpack or another mod already provides the same "
                        + "effect. Switched off, the feature is forced on where that is technically possible."));
        boolean anyDefer = false;
        int deferCount = 0;
        // 0.9.2: a fixed order (mods register their configs in parallel at startup, so the tabs moved between
        // launches): the library's page first, then the mods by name.
        List<AtmosConfig> configs = new ArrayList<>(AtmosConfig.all());
        configs.sort(java.util.Comparator.comparing((AtmosConfig c) -> !AtmospheraLib.MOD_ID.equals(c.modId()))
                .thenComparing(AtmosConfig::title, String.CASE_INSENSITIVE_ORDER));
        for (AtmosConfig config : configs) {
            for (AtmosConfig.Page page : config.pages()) {
                for (List<AtmosOption<?>> section : page.groups()) {
                    for (AtmosOption<?> option : section) {
                        if (!option.isFileOnly() && config.isOverlapSwitch(option)) {
                            defer.add(control(config, option));
                            anyDefer = true;
                            deferCount++;
                        }
                    }
                }
            }
        }
        if (!loggedDefer) {
            loggedDefer = true;
            AtmospheraLib.LOGGER.info("[Atmosphera] Video Settings: {} pages, {} defer switches on the Atmosphera page",
                    configs.size(), deferCount);
        }
        List<OptionPage> pages = new ArrayList<>();
        for (AtmosConfig config : configs) {
            boolean first = true;
            boolean lib = AtmospheraLib.MOD_ID.equals(config.modId());
            for (AtmosConfig.Page page : config.pages()) {
                List<OptionGroup> groups = new ArrayList<>();
                if (first) {
                    groups.add(OptionGroup.createBuilder().add(statusLine(config)).build());
                }
                groups.addAll(groups(config, page));
                if (lib && first && anyDefer) {
                    groups.add(defer.build());
                }
                if (groups.isEmpty()) {
                    continue;
                }
                first = false;
                Component title = Component.translatableWithFallback(
                        "atmosphera.options.page." + config.modId() + "." + page.key(), page.title());
                pages.add(new OptionPage(OptionIdentifier.create(config.modId(), page.key()), withIcon(config.modId(), title),
                        ImmutableList.copyOf(groups)));
            }
        }
        return pages;
    }

    /** The mod's icon glyph (U+E000 of the font {@code <modid>:atmos_icon}) and a space before the page name. */
    private static Component withIcon(String modId, Component title) {
        ResourceLocation font = new ResourceLocation(modId, "atmos_icon");
        try {
            if (Minecraft.getInstance().getResourceManager()
                    .getResource(new ResourceLocation(modId, "font/atmos_icon.json")).isEmpty()) {
                return title;
            }
        } catch (Throwable e) {
            return title;
        }
        MutableComponent icon = Component.literal("\uE000").withStyle(style -> style.withFont(font));
        return Component.empty().append(icon).append(" ").append(title);
    }

    /**
     * A choice value in normal case: a translation ({@code atmosphera.options.value.<enum>.<constant>}, lower case)
     * or the constant's name with each word capitalised ("VANILLA_PLUS" becomes "Vanilla Plus").
     */
    static Component[] valueNames(Class<?> enumClass) {
        Object[] constants = enumClass.getEnumConstants();
        Component[] names = new Component[constants.length];
        String prefix = "atmosphera.options.value." + enumClass.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        for (int i = 0; i < constants.length; i++) {
            String name = ((Enum<?>) constants[i]).name();
            names[i] = Component.translatableWithFallback(prefix + name.toLowerCase(Locale.ROOT), normalCase(name));
        }
        return names;
    }

    static String normalCase(String constant) {
        StringBuilder out = new StringBuilder(constant.length());
        for (String word : constant.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(word.charAt(0)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /**
     * One group per TOML section, split again where a section holds several feature switches (each switch leads its
     * own group). The defer switches are left out (0.9.2: they are on the Atmosphera page).
     */
    private static List<OptionGroup> groups(AtmosConfig config, AtmosConfig.Page page) {
        List<OptionGroup> groups = new ArrayList<>();
        for (List<AtmosOption<?>> section : page.groups()) {
            OptionGroup.Builder group = OptionGroup.createBuilder();
            boolean any = false;
            for (AtmosOption<?> option : section) {
                if (option.isFileOnly()) {
                    // TOML only: colours (0.6.5, no text field on these pages) and fileOnly options (0.8.3).
                    continue;
                }
                if (config.isOverlapSwitch(option)) {
                    continue;
                }
                if (any && featureFor(config, option) != null) {
                    groups.add(group.build());
                    group = OptionGroup.createBuilder();
                    any = false;
                }
                group.add(control(config, option));
                any = true;
            }
            if (any) {
                groups.add(group.build());
            }
        }
        return groups;
    }

    /** The library's line counts every feature; a mod's line counts its own. */
    /** The single value of the status row's control: the row only displays text and cannot be changed. */
    private enum Status { SHOWN }

    /** A row that only shows a heading (and a tooltip): the status row's one-value control. */
    private static OptionImpl<Object, Status> header(String text, String tooltip) {
        return OptionImpl.createBuilder(Status.class, STORAGE)
                .setName(Component.literal(text))
                .setTooltip(Component.literal(tooltip))
                .setControl(opt -> new CyclingControl<>(opt, Status.class, new Component[]{Component.empty()}))
                .setImpact(OptionImpact.LOW)
                .setBinding((data, value) -> {
                }, data -> Status.SHOWN)
                .build();
    }

    private static OptionImpl<Object, Status> statusLine(AtmosConfig config) {
        String owner = AtmospheraLib.MOD_ID.equals(config.modId()) ? null : config.modId();
        // Enabled (a disabled option is drawn struck through, which read as "switched off"); a one-value cycling
        // control with no label, so clicking it changes nothing.
        return OptionImpl.createBuilder(Status.class, STORAGE)
                .setName(LiveText.of(() -> statusText(owner, config.title())))
                .setTooltip(LiveText.of(() -> AtmosState.current().summary()))
                .setControl(opt -> new CyclingControl<>(opt, Status.class, new Component[]{Component.empty()}))
                .setImpact(OptionImpact.VARIES)
                .setBinding((data, value) -> {
                }, data -> Status.SHOWN)
                .build();
    }

    private static String statusText(String owner, String title) {
        AtmosState state = AtmosState.current();
        int total = 0;
        for (AtmosFeature feature : FeatureRegistry.all()) {
            total += owner == null || owner.equals(feature.ownerModId()) ? 1 : 0;
        }
        String text = (owner == null ? "Atmosphera" : title) + ": " + activeCount(owner) + "/" + total + " features active";
        return state.env() != null && state.env().pack().active() ? text + " (shaderpack on)" : text;
    }

    private static int activeCount(String owner) {
        int active = 0;
        for (AtmosFeature feature : FeatureRegistry.all()) {
            if ((owner == null || owner.equals(feature.ownerModId())) && feature.isActive()) {
                active++;
            }
        }
        return active;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static OptionImpl<Object, ?> control(AtmosConfig config, AtmosOption<?> option) {
        AtmosFeature feature = featureFor(config, option);
        Component name = feature == null ? option.displayName()
                : LiveText.of(() -> option.displayName().getString() + (feature.isEnabled() && !feature.isActive() ? " (off)" : ""));
        Component tooltip = feature == null ? option.displayTooltip()
                : LiveText.of(() -> {
                    String reason = feature.inactiveReason();
                    String base = option.displayTooltip().getString();
                    return reason == null || "off in config".equals(reason) ? base : base + "\n\nNot running: " + reason;
                });
        OptionImpact impact = switch (option.tier) {
            case LIVE -> OptionImpact.LOW;
            case RECOMPILE -> OptionImpact.MEDIUM;
            case REMESH -> OptionImpact.HIGH;
        };
        switch (option.control) {
            case TOGGLE -> {
                AtmosOption<Boolean> o = (AtmosOption<Boolean>) option;
                return OptionImpl.createBuilder(boolean.class, STORAGE).setName(name).setTooltip(tooltip)
                        .setControl(TickBoxControl::new).setImpact(impact)
                        .setBinding((data, v) -> o.set(v), data -> o.get()).setFlags(new OptionFlag[0]).build();
            }
            case SLIDER -> {
                AtmosOption<Integer> o = (AtmosOption<Integer>) option;
                return OptionImpl.createBuilder(int.class, STORAGE).setName(name).setTooltip(tooltip)
                        .setControl(opt -> new SliderControl(opt, o.min, o.max, o.step, ControlValueFormatter.number()))
                        .setImpact(impact).setBinding((data, v) -> o.set(v), data -> o.get()).build();
            }
            case PERCENT -> {
                AtmosOption<Double> o = (AtmosOption<Double>) option;
                return OptionImpl.createBuilder(int.class, STORAGE).setName(name).setTooltip(tooltip)
                        .setControl(opt -> new SliderControl(opt, o.min, o.max, o.step, ControlValueFormatter.percentage()))
                        .setImpact(impact)
                        .setBinding((data, v) -> o.set(v / 100.0), data -> (int) Math.round(o.get() * 100.0)).build();
            }
            default -> {
                AtmosOption o = option;
                Class enumClass = option.enumClass;
                return OptionImpl.createBuilder(enumClass, STORAGE).setName(name).setTooltip(tooltip)
                        .setControl(opt -> new CyclingControl((OptionImpl) opt, enumClass, valueNames(enumClass)))
                        .setImpact(impact)
                        .setBinding((data, v) -> o.set(v), data -> o.get()).build();
            }
        }
    }

    /** The feature whose switch this option is (registered by FeatureSwitch), if any. */
    private static AtmosFeature featureFor(AtmosConfig config, AtmosOption<?> option) {
        for (AtmosFeature feature : FeatureRegistry.all()) {
            if (feature.ownerModId().equals(config.modId()) && feature.switchOption() == option) {
                return feature;
            }
        }
        return null;
    }
}
