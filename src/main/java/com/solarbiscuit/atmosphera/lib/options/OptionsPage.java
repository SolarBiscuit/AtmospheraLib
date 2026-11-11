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
import net.minecraft.network.chat.Component;
import org.embeddedt.embeddium.api.OptionGUIConstructionEvent;
import org.embeddedt.embeddium.client.gui.options.OptionIdentifier;

import java.util.ArrayList;
import java.util.List;

/**
 * The Atmosphera pages of Video Settings (Xenon/Embeddium options screen). RatPack's screen lists pages flat, with no
 * mod headings, so every page is named after its mod: "Atmosphera", "Gale (wind)", "Torrente (water)", and pages of
 * their own for long sections such as "Torrente (water): Custom style". On a page every TOML section is a group
 * (groups are drawn apart), split again at each feature switch, and the "defer to shaders/mods" switches sit in one
 * group at the bottom. A mod's main page starts with its status line.
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

    private static List<OptionPage> build() {
        List<OptionPage> pages = new ArrayList<>();
        for (AtmosConfig config : AtmosConfig.all()) {
            boolean first = true;
            for (AtmosConfig.Page page : config.pages()) {
                List<OptionGroup> groups = new ArrayList<>();
                if (first) {
                    groups.add(OptionGroup.createBuilder().add(statusLine(config)).build());
                }
                groups.addAll(groups(config, page));
                if (groups.isEmpty()) {
                    continue;
                }
                first = false;
                pages.add(new OptionPage(OptionIdentifier.create(config.modId(), page.key()),
                        Component.translatableWithFallback("atmosphera.options.page." + config.modId() + "." + page.key(),
                                page.title()),
                        ImmutableList.copyOf(groups)));
            }
        }
        return pages;
    }

    /**
     * One group per TOML section, split again where a section holds several feature switches (each switch leads its
     * own group); the "defer to shaders/mods" switches last, in a group of their own.
     */
    private static List<OptionGroup> groups(AtmosConfig config, AtmosConfig.Page page) {
        List<OptionGroup> groups = new ArrayList<>();
        OptionGroup.Builder overlap = OptionGroup.createBuilder();
        boolean anyOverlap = false;
        for (List<AtmosOption<?>> section : page.groups()) {
            OptionGroup.Builder group = OptionGroup.createBuilder();
            boolean any = false;
            for (AtmosOption<?> option : section) {
                if (option.control == AtmosOption.Control.COLOR) {
                    // TOML only (0.6.5): no text field on these pages.
                    continue;
                }
                if (config.isOverlapSwitch(option)) {
                    overlap.add(control(config, option));
                    anyOverlap = true;
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
        if (anyOverlap) {
            groups.add(overlap.build());
        }
        return groups;
    }

    /** The library's line counts every feature; a mod's line counts its own. */
    /** The single value of the status row's control: the row only displays text and cannot be changed. */
    private enum Status { SHOWN }

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
                        .setControl(opt -> new CyclingControl((OptionImpl) opt, enumClass))
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
