package com.solarbiscuit.atmosphera.lib.options;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.FeatureRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The library's own settings screen (0.10.0), for players without Xenon/Embeddium (whose Video Settings pages carry
 * the same settings) and for the Mods list's "Config" button. One tab per mod, the same dropdown groups as Video
 * Settings (closed by default, remembered with {@link GroupState}), switches, sliders and choices; colours and lists
 * stay in the TOML files. Changes are made at once and written to the files on "Done".
 */
public final class AtmosSettingsScreen extends Screen {
    private static final int TAB_WIDTH = 100;
    private static final int ROW_HEIGHT = 22;
    private final Screen parent;
    private String selected;
    private SettingsList list;

    private AtmosSettingsScreen(Screen parent, String modId) {
        super(Component.translatableWithFallback("atmosphera.options.screen", "Atmosphera settings"));
        this.parent = parent;
        this.selected = modId;
    }

    /** The screen at a mod's page (null: the first page). */
    public static Screen open(Screen parent, String modId) {
        return new AtmosSettingsScreen(parent, modId);
    }

    @Override
    protected void init() {
        List<AtmosConfig> configs = AtmosConfig.ordered();
        if (configs.isEmpty()) {
            addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                    .bounds(width / 2 - 100, height - 28, 200, 20).build());
            return;
        }
        if (selected == null || configs.stream().noneMatch(c -> c.modId().equals(selected))) {
            selected = configs.get(0).modId();
        }
        int y = 32;
        for (AtmosConfig config : configs) {
            Button tab = Button.builder(withIcon(config.modId(), Component.translatableWithFallback(
                            "atmosphera.options.page." + config.modId() + ".main", config.title())), b -> {
                        selected = config.modId();
                        minecraft.tell(() -> rebuild(0)); // not while the screen dispatches this click
                    })
                    .bounds(8, y, TAB_WIDTH, 20).build();
            tab.active = !config.modId().equals(selected);
            addRenderableWidget(tab);
            y += 22;
        }
        double scroll = list == null ? 0 : list.getScrollAmount();
        list = new SettingsList(minecraft, width - TAB_WIDTH - 24, height, 32, height - 36);
        list.setLeftPos(TAB_WIDTH + 16);
        for (AtmosConfig config : configs) {
            if (config.modId().equals(selected)) {
                fill(list, config);
            }
        }
        list.setScrollAmount(scroll);
        addRenderableWidget(list);
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(width / 2 - 100, height - 28, 200, 20).build());
    }

    private void rebuild(double scroll) {
        double keep = list == null ? 0 : scroll < 0 ? list.getScrollAmount() : scroll;
        clearWidgets();
        list = null;
        init();
        if (list != null) {
            list.setScrollAmount(keep);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void onClose() {
        AtmosConfig.saveAndApply();
        minecraft.setScreen(parent);
    }

    // ---- the page's rows ----

    private void fill(SettingsList target, AtmosConfig config) {
        target.add(new TextRow(Component.literal(status(config)).withStyle(ChatFormatting.GRAY), false));
        for (AtmosConfig.Page page : config.pages()) {
            for (AtmosConfig.Group group : page.groups()) {
                addGroup(target, config, page.key(), group);
            }
        }
        if (Minecraft.getInstance().hasSingleplayerServer()) {
            for (AtmosConfig view : AtmosConfig.serverViews(config.modId())) {
                for (AtmosConfig.Page page : view.pages()) {
                    for (AtmosConfig.Group group : page.groups()) {
                        addGroup(target, view, "main", group);
                    }
                }
            }
        }
        if (AtmospheraLib.MOD_ID.equals(config.modId())) {
            addDefer(target);
        }
    }

    private void addGroup(SettingsList target, AtmosConfig config, String pageKey, AtmosConfig.Group group) {
        String id = GroupState.id(config.modId(), pageKey, group.key());
        List<Row> rows = new ArrayList<>();
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
            if (pendingHeading && headingTitle != null) {
                rows.add(new TextRow(Component.literal("— " + headingTitle + " —").withStyle(ChatFormatting.GRAY), true));
            }
            pendingHeading = false;
            rows.add(optionRow(config, option));
        }
        AtmosOption<Boolean> master = group.master() != null && !group.master().isFileOnly() ? group.master() : null;
        if (rows.isEmpty() && master == null) {
            return;
        }
        Component title = Component.translatableWithFallback("atmosphera.options.group." + config.modId() + "." + group.key(),
                group.title());
        target.add(new HeaderRow(id, title, master, group.server()));
        if (GroupState.isOpen(id)) {
            rows.forEach(target::add);
        }
    }

    private void addDefer(SettingsList target) {
        String id = GroupState.id(AtmospheraLib.MOD_ID, "main", "defer");
        List<Row> rows = new ArrayList<>();
        for (AtmosConfig config : AtmosConfig.ordered()) {
            boolean heading = false;
            for (AtmosOption<?> option : config.options()) {
                if (!option.isFileOnly() && config.isOverlapSwitch(option)) {
                    if (!heading) {
                        heading = true;
                        rows.add(new TextRow(Component.literal("— " + config.title() + " —").withStyle(ChatFormatting.GRAY), true));
                    }
                    rows.add(optionRow(config, option));
                }
            }
        }
        if (rows.isEmpty()) {
            return;
        }
        target.add(new HeaderRow(id, Component.translatableWithFallback("atmosphera.options.group.atmospheralib.defer",
                "Defer to shaderpacks and other mods"), null, false));
        if (GroupState.isOpen(id)) {
            rows.forEach(target::add);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Row optionRow(AtmosConfig config, AtmosOption<?> option) {
        Component name = option.displayName();
        AbstractWidget widget;
        switch (option.control) {
            case TOGGLE -> {
                AtmosOption<Boolean> o = (AtmosOption<Boolean>) option;
                widget = CycleButton.onOffBuilder(o.get()).displayOnlyValue()
                        .create(0, 0, 120, 20, name, (button, value) -> o.set(value));
            }
            case SLIDER -> widget = new NumberSlider((AtmosOption<Integer>) option, false);
            case PERCENT -> widget = new NumberSlider((AtmosOption<Double>) option, true);
            default -> {
                AtmosOption o = option;
                Object[] constants = option.enumClass.getEnumConstants();
                String prefix = "atmosphera.options.value." + config.modId() + "."
                        + option.enumClass.getSimpleName().toLowerCase(Locale.ROOT) + ".";
                widget = CycleButton.builder((Object v) -> (Component) Component.translatableWithFallback(
                                prefix + ((Enum<?>) v).name().toLowerCase(Locale.ROOT), AtmosConfig.normalCase(((Enum<?>) v).name())))
                        .withValues(Arrays.asList(constants)).withInitialValue(o.get()).displayOnlyValue()
                        .create(0, 0, 120, 20, name, (button, value) -> o.set(value));
            }
        }
        widget.setTooltip(Tooltip.create(option.displayTooltip()));
        return new OptionRow(name, widget, featureFor(config, option));
    }

    private static AtmosFeature featureFor(AtmosConfig config, AtmosOption<?> option) {
        for (AtmosFeature feature : FeatureRegistry.all()) {
            if (feature.ownerModId().equals(config.modId()) && feature.switchOption() == option) {
                return feature;
            }
        }
        return null;
    }

    private static String status(AtmosConfig config) {
        String owner = AtmospheraLib.MOD_ID.equals(config.modId()) ? null : config.modId();
        int total = 0;
        int active = 0;
        for (AtmosFeature feature : FeatureRegistry.all()) {
            if (owner == null || owner.equals(feature.ownerModId())) {
                total++;
                active += feature.isActive() ? 1 : 0;
            }
        }
        return (owner == null ? "Atmosphera" : config.title()) + ": " + active + "/" + total + " features active";
    }

    private static Component withIcon(String modId, Component title) {
        try {
            if (Minecraft.getInstance().getResourceManager()
                    .getResource(new ResourceLocation(modId, "font/atmos_icon.json")).isEmpty()) {
                return title;
            }
        } catch (Throwable e) {
            return title;
        }
        MutableComponent icon = Component.literal("")
                .withStyle(style -> style.withFont(new ResourceLocation(modId, "atmos_icon")));
        return Component.empty().append(icon).append(" ").append(title);
    }

    // ---- list and rows ----

    private final class SettingsList extends ContainerObjectSelectionList<Row> {
        private final int listWidth;

        SettingsList(Minecraft mc, int width, int height, int top, int bottom) {
            super(mc, width, height, top, bottom, ROW_HEIGHT);
            this.listWidth = width;
            setRenderBackground(false);
            setRenderTopAndBottom(false);
        }

        void add(Row row) {
            addEntry(row);
        }

        @Override
        public int getRowWidth() {
            return listWidth - 20;
        }

        @Override
        protected int getScrollbarPosition() {
            return getRowLeft() + getRowWidth() + 6;
        }
    }

    private abstract static class Row extends ContainerObjectSelectionList.Entry<Row> {
    }

    /** A line of text: the status line or a subheading. */
    private final class TextRow extends Row {
        private final Component text;
        private final boolean centered;

        TextRow(Component text, boolean centered) {
            this.text = text;
            this.centered = centered;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            if (centered) {
                graphics.drawCenteredString(font, text, left + rowWidth / 2, top + 6, 0xAAAAAA);
            } else {
                graphics.drawString(font, text, left + 4, top + 6, 0xAAAAAA);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of();
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of();
        }
    }

    /** A group's title: a button that opens and closes the group, and its master switch on the right. */
    private final class HeaderRow extends Row {
        private final Button toggle;
        private final CycleButton<Boolean> master;

        HeaderRow(String groupId, Component title, AtmosOption<Boolean> masterOption, boolean server) {
            boolean open = GroupState.isOpen(groupId);
            Component label = Component.literal(open ? "▼ " : "▶ ").append(title.copy().withStyle(ChatFormatting.BOLD));
            this.toggle = Button.builder(label, b -> {
                GroupState.toggle(groupId);
                minecraft.tell(() -> rebuild(-1));
            }).bounds(0, 0, 200, 20).build();
            if (server) {
                toggle.setTooltip(Tooltip.create(Component.literal("Settings of this world's server, shown because you host it "
                        + "(singleplayer or LAN).")));
            }
            this.master = masterOption == null ? null : CycleButton.onOffBuilder(masterOption.get()).displayOnlyValue()
                    .create(0, 0, 50, 20, title, (button, value) -> masterOption.set(value));
            if (master != null) {
                master.setTooltip(Tooltip.create(masterOption.displayTooltip()));
            }
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            int masterWidth = master == null ? 0 : 54;
            toggle.setX(left);
            toggle.setY(top);
            toggle.setWidth(rowWidth - masterWidth);
            toggle.render(graphics, mouseX, mouseY, partialTick);
            if (master != null) {
                master.setX(left + rowWidth - 50);
                master.setY(top);
                master.render(graphics, mouseX, mouseY, partialTick);
            }
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return master == null ? List.of(toggle) : List.of(toggle, master);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return master == null ? List.of(toggle) : List.of(toggle, master);
        }
    }

    /** A setting: its name on the left, its control on the right. */
    private final class OptionRow extends Row {
        private final Component name;
        private final AbstractWidget widget;
        private final AtmosFeature feature;

        OptionRow(Component name, AbstractWidget widget, AtmosFeature feature) {
            this.name = name;
            this.widget = widget;
            this.feature = feature;
        }

        @Override
        public void render(GuiGraphics graphics, int index, int top, int left, int rowWidth, int rowHeight, int mouseX,
                           int mouseY, boolean hovering, float partialTick) {
            String text = name.getString();
            if (feature != null && feature.isEnabled() && !feature.isActive()) {
                text += " (off)";
            }
            int widgetWidth = Math.min(120, rowWidth * 2 / 5); // names need the room more than the controls
            graphics.drawString(font, font.plainSubstrByWidth(text, rowWidth - widgetWidth - 16), left + 12, top + 6, 0xFFFFFF);
            widget.setX(left + rowWidth - widgetWidth);
            widget.setY(top);
            widget.setWidth(widgetWidth);
            widget.render(graphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(widget);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(widget);
        }
    }

    /** An integer slider, or a percentage slider over a double (1.0 = 100 %), in the option's steps. */
    private static final class NumberSlider extends AbstractSliderButton {
        private final AtmosOption<?> option;
        private final boolean percent;

        NumberSlider(AtmosOption<?> option, boolean percent) {
            super(0, 0, 120, 20, Component.empty(), 0);
            this.option = option;
            this.percent = percent;
            int current = percent ? (int) Math.round(((Double) option.get()) * 100.0) : (Integer) option.get();
            this.value = option.max == option.min ? 0 : (current - option.min) / (double) (option.max - option.min);
            updateMessage();
        }

        private int current() {
            int raw = (int) Math.round(option.min + value * (option.max - option.min));
            int step = Math.max(1, option.step);
            int snapped = option.min + Math.round((raw - option.min) / (float) step) * step;
            return Math.max(option.min, Math.min(option.max, snapped));
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal(current() + (percent ? "%" : "")));
        }

        @Override
        @SuppressWarnings("unchecked")
        protected void applyValue() {
            if (percent) {
                ((AtmosOption<Double>) option).set(current() / 100.0);
            } else {
                ((AtmosOption<Integer>) option).set(current());
            }
        }
    }
}
