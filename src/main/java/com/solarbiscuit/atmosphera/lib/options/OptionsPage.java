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
        // 0.10.0: while the search box holds text, closed groups show their matching options.
        GroupState.searching = OptionsPage::searching;
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

    /** Dev (settings_toggle): opens or closes a group as a click on its title row does, and re-lays the open screen. */
    public static void toggleForTest(String groupId) {
        GroupState.toggle(groupId);
        GroupHeaderControl.rebuildLater();
        AtmospheraLib.LOGGER.info("[dev] Group {} is now {}", groupId, GroupState.isOpen(groupId) ? "open" : "closed");
    }

    /** Dev (settings_screen): opens Video Settings at a mod's main page; {@code reeses} opens Xenon's Reese's screen. */
    public static void openForTest(String modId, boolean reeses) {
        for (AtmosConfig config : AtmosConfig.all()) {
            if (config.modId().equals(modId)) {
                Component title = withIcon(modId, Component.translatableWithFallback(
                        "atmosphera.options.page." + modId + ".main", config.title()));
                if (reeses) {
                    com.solarbiscuit.atmosphera.lib.mixin.sodium.ReesesScreenAccessor.atmos$selectedTab().set(title);
                } else {
                    com.solarbiscuit.atmosphera.lib.mixin.sodium.VideoOptionsScreenAccessor.atmos$selectedTab().set(title);
                }
            }
        }
        Minecraft.getInstance().setScreen(reeses ? new me.jellysquid.mods.sodium.client.gui.reesesoptions.client.gui.SodiumVideoOptionsScreen(null)
                : new me.jellysquid.mods.sodium.client.gui.SodiumOptionsGUI(null));
    }

    private static boolean searching() {
        if (Minecraft.getInstance().screen instanceof me.jellysquid.mods.sodium.client.gui.reesesoptions.client.gui.SodiumVideoOptionsScreen) {
            String text = com.solarbiscuit.atmosphera.lib.mixin.sodium.ReesesScreenAccessor.atmos$lastSearch().get();
            return text != null && !text.isBlank();
        }
        if (Minecraft.getInstance().screen instanceof org.embeddedt.embeddium.gui.EmbeddiumVideoOptionsScreen screen) {
            Object model = ((com.solarbiscuit.atmosphera.lib.mixin.sodium.VideoOptionsScreenAccessor) screen).atmos$searchModel();
            String text = ((com.solarbiscuit.atmosphera.lib.mixin.sodium.SearchTextFieldModelAccessor) model).atmos$text();
            return text != null && !text.isBlank();
        }
        return false;
    }

    private static boolean loggedDefer;

    /** The configs in page order (0.9.2: the library first, then the mods by name; they register in parallel). */
    static List<AtmosConfig> ordered() {
        return AtmosConfig.ordered();
    }

    /** Server views are shown to the host of a singleplayer or LAN world only (0.10.0). */
    static boolean showServerGroups() {
        return Minecraft.getInstance().hasSingleplayerServer();
    }

    private static List<OptionPage> build() {
        List<AtmosConfig> configs = ordered();
        // 0.9.2: the defer switches of every mod, in one group on the library's page (0.10.0: a heading per mod).
        String deferId = GroupState.id(AtmospheraLib.MOD_ID, "main", "defer");
        OptionGroup.Builder defer = OptionGroup.createBuilder();
        defer.add(groupHeader(deferId, Component.translatableWithFallback("atmosphera.options.group.atmospheralib.defer",
                        "Defer to shaderpacks and other mods"),
                "Each switch in this group turns its feature off while a shaderpack or another mod already provides the "
                        + "same effect. Switched off, the feature is forced on where that is technically possible.", null, null));
        int deferCount = 0;
        for (AtmosConfig config : configs) {
            boolean heading = false;
            for (AtmosOption<?> option : config.options()) {
                if (!option.isFileOnly() && config.isOverlapSwitch(option)) {
                    if (!heading) {
                        heading = true;
                        defer.add(bound(subheadingRow(config.title()), deferId));
                    }
                    defer.add(bound(control(config, option), deferId));
                    deferCount++;
                }
            }
        }
        if (!loggedDefer) {
            loggedDefer = true;
            AtmospheraLib.LOGGER.info("[Atmosphera] Video Settings: {} pages, {} defer switches on the AtmospheraLib page",
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
                for (AtmosConfig.Group group : page.groups()) {
                    OptionGroup built = group(config, page.key(), group);
                    if (built != null) {
                        groups.add(built);
                    }
                }
                if (first && showServerGroups()) {
                    for (AtmosConfig view : AtmosConfig.serverViews(config.modId())) {
                        for (AtmosConfig.Page viewPage : view.pages()) {
                            for (AtmosConfig.Group group : viewPage.groups()) {
                                OptionGroup built = group(view, "main", group);
                                if (built != null) {
                                    groups.add(built);
                                }
                            }
                        }
                    }
                }
                if (lib && first && deferCount > 0) {
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
        MutableComponent icon = Component.literal("").withStyle(style -> style.withFont(font));
        return Component.empty().append(icon).append(" ").append(title);
    }

    /**
     * A choice value in normal case: a translation ({@code atmosphera.options.value.<modid>.<enum>.<constant>}, lower
     * case; 0.10.0 adds the mod id, so two mods' enums of the same name do not share keys) or the constant's name with
     * each word capitalised ("VANILLA_PLUS" becomes "Vanilla Plus").
     */
    static Component[] valueNames(String modId, Class<?> enumClass) {
        Object[] constants = enumClass.getEnumConstants();
        Component[] names = new Component[constants.length];
        String prefix = "atmosphera.options.value." + modId + "." + enumClass.getSimpleName().toLowerCase(Locale.ROOT) + ".";
        for (int i = 0; i < constants.length; i++) {
            String name = ((Enum<?>) constants[i]).name();
            names[i] = Component.translatableWithFallback(prefix + name.toLowerCase(Locale.ROOT), AtmosConfig.normalCase(name));
        }
        return names;
    }

    /** A group's title, translated ({@code atmosphera.options.group.<modid>.<key>}). */
    static Component groupTitle(AtmosConfig config, AtmosConfig.Group group) {
        return Component.translatableWithFallback("atmosphera.options.group." + config.modId() + "." + group.key(), group.title());
    }

    /**
     * One dropdown (0.10.0): its title row (carrying the master switch, if any), then its options and subheadings, each
     * bound to the group so the frame hides them while it is closed. Overlap switches are left out (they are in the
     * library page's defer group). Null when nothing in it has a control.
     */
    private static OptionGroup group(AtmosConfig config, String pageKey, AtmosConfig.Group group) {
        String id = GroupState.id(config.modId(), pageKey, group.key());
        List<OptionImpl<Object, ?>> rows = new ArrayList<>();
        boolean pendingHeading = false;
        String headingTitle = null;
        for (Object entry : group.entries()) {
            if (entry instanceof AtmosConfig.Subheading heading) {
                pendingHeading = true;
                headingTitle = heading.title() == null ? null : Component.translatableWithFallback(
                        "atmosphera.options.subheader." + config.modId() + "." + heading.key(), heading.title()).getString();
                continue;
            }
            AtmosOption<?> option = (AtmosOption<?>) entry;
            if (option.isFileOnly() || config.isOverlapSwitch(option)) {
                continue;
            }
            if (pendingHeading && (headingTitle != null || !rows.isEmpty())) {
                rows.add(subheadingRow(headingTitle == null ? "" : headingTitle));
            }
            pendingHeading = false;
            rows.add(control(config, option));
        }
        AtmosOption<Boolean> master = group.master() != null && !group.master().isFileOnly() ? group.master() : null;
        if (rows.isEmpty() && master == null) {
            return null;
        }
        String tooltip = group.server()
                ? "Settings of this world's server: shown because you host it (singleplayer or LAN). On a multiplayer "
                + "server its owner sets them in config/Atmosphera/" + config.modId() + "-server.toml."
                : rows.isEmpty() ? "" : "Click to show or hide the " + rows.size() + " settings in this group.";
        OptionGroup.Builder builder = OptionGroup.createBuilder();
        builder.add(groupHeader(id, groupTitle(config, group), tooltip, config, master));
        for (OptionImpl<Object, ?> row : rows) {
            builder.add(bound(row, id));
        }
        return builder.build();
    }

    private static OptionImpl<Object, ?> bound(OptionImpl<Object, ?> row, String groupId) {
        GroupState.bind(row, groupId);
        return row;
    }

    /**
     * A group's title row (0.10.0). With a master switch its value is that switch (the tick box on the right);
     * without one its value is a constant false that never changes.
     */
    private static OptionImpl<Object, Boolean> groupHeader(String groupId, Component title, String tooltip,
                                                         AtmosConfig config, AtmosOption<Boolean> master) {
        AtmosFeature feature = master == null ? null : featureFor(config, master);
        Component name = feature == null ? title
                : LiveText.of(() -> title.getString() + (feature.isEnabled() && !feature.isActive() ? " (off)" : ""));
        Component fullTooltip = master == null ? Component.literal(tooltip)
                : Component.empty().append(master.displayTooltip())
                .append(Component.literal(tooltip.isEmpty() ? "" : "\n\n" + tooltip));
        OptionImpact impact = master == null ? OptionImpact.LOW : switch (master.tier) {
            case LIVE -> OptionImpact.LOW;
            case RECOMPILE -> OptionImpact.MEDIUM;
            case REMESH -> OptionImpact.HIGH;
        };
        return OptionImpl.createBuilder(boolean.class, STORAGE)
                .setName(name)
                .setTooltip(fullTooltip)
                .setControl(opt -> new GroupHeaderControl(opt, groupId, name, master != null))
                .setImpact(impact)
                .setBinding((data, value) -> {
                    if (master != null) {
                        master.set(value);
                    }
                }, data -> master != null && master.get())
                .build();
    }

    /** A heading inside a group: a label row that cannot be changed. */
    private static OptionImpl<Object, Status> subheadingRow(String text) {
        return header(text.isEmpty() ? " " : "— " + text + " —", text.isEmpty() ? " " : text);
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
                        .setControl(opt -> new CyclingControl((OptionImpl) opt, enumClass, valueNames(config.modId(), enumClass)))
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
