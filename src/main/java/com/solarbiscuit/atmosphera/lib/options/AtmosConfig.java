package com.solarbiscuit.atmosphera.lib.options;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * A mod's Atmosphera settings: declare each option once and get both the TOML entry
 * ({@code config/Atmosphera/<modid>-client.toml}) and the control in Video Settings (and, 0.10.0, in the library's own
 * settings screen for players without Xenon/Embeddium). The title is the page's name in the sidebar: the mod's name.
 * <p>
 * 0.10.0: a page is a list of <b>groups</b>, drawn as dropdowns: a title row that opens and closes the group (closed
 * by default; what a player opens is remembered). Every TOML section opens a group of the same name; {@link #group(String,
 * String)} opens (or reopens) a group anywhere, so a group may gather options from several sections and a section may
 * be split across groups. A group's title row can carry its master switch ({@link #headerSwitch}; a feature switch that
 * opens a group becomes it automatically). {@link #subheading} adds a heading inside a group.
 * <pre>{@code
 * AtmosConfig config = AtmosConfig.create("gale", "Gale");
 * config.section("waving", "Plants and leaves", "Waving foliage");
 * AtmosConfig.FeatureSwitch waving = config.featureSwitch("enabled", true, "Waving foliage", "...");
 * AtmosOption<Double> amplitude = config.percent("amplitude", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Amplitude", "...");
 * config.register(); // in the mod constructor
 * }</pre>
 * Edits from the GUI apply on "Apply" (or "Done"); edits to the TOML apply when the file changes. Changes made together
 * cost only their highest {@link ChangeTier}.
 */
public final class AtmosConfig {
    /** The two options behind a feature: its switch and whether the overlap guard may suppress it. */
    public record FeatureSwitch(AtmosOption<Boolean> enabled, AtmosOption<Boolean> respectOverlap) {
        public BooleanSupplier enabledSupplier() {
            return enabled::get;
        }

        public BooleanSupplier respectOverlapSupplier() {
            return respectOverlap::get;
        }
    }

    /** A heading row inside a group (0.10.0), for example "Bloom" inside "Lens and colour". */
    public record Subheading(String key, String title) {
    }

    /**
     * One dropdown on a page (0.10.0). Its entries are options and {@link Subheading}s in declaration order; the master
     * switch, if any, sits on the title row and is not one of the entries.
     */
    public static final class Group {
        private final String key;
        private final String title;
        private final boolean atEnd;
        private final List<Object> entries = new ArrayList<>();
        private AtmosOption<Boolean> master;
        private boolean server;

        Group(String key, String title, boolean atEnd) {
            this.key = key;
            this.title = title;
            this.atEnd = atEnd;
        }

        public String key() {
            return key;
        }

        public String title() {
            return title;
        }

        /** Shown after the other groups of its page (debug and advanced groups). */
        public boolean atEnd() {
            return atEnd;
        }

        /** Options and subheadings, in order (the master switch is not among them). */
        public List<Object> entries() {
            return List.copyOf(entries);
        }

        public AtmosOption<Boolean> master() {
            return master;
        }

        /** A server-side group (shown only to the host of a singleplayer or LAN world). */
        public boolean server() {
            return server;
        }

        /** Options in this group, the master first. */
        public List<AtmosOption<?>> options() {
            List<AtmosOption<?>> out = new ArrayList<>();
            if (master != null) {
                out.add(master);
            }
            for (Object entry : entries) {
                if (entry instanceof AtmosOption<?> option) {
                    out.add(option);
                }
            }
            return out;
        }

        boolean isEmpty() {
            return master == null && entries.isEmpty();
        }
    }

    /**
     * A page of settings. Every mod has a main page named after it ({@link #title()}); a section declared with
     * {@link #pageSection} gets a page of its own, named "&lt;mod title&gt;: &lt;section title&gt;". Groups are in
     * display order (declaration order, the {@link Group#atEnd()} groups last).
     */
    public record Page(String key, String title, List<Group> groups) {
    }

    private static final List<AtmosConfig> CONFIGS = new ArrayList<>();
    private static final List<AtmosConfig> SERVER_VIEWS = new ArrayList<>();

    private final String modId;
    private final String title;
    private final boolean serverView;
    private final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
    private final List<AtmosOption<?>> options = new ArrayList<>();
    private final List<String> sections = new ArrayList<>();
    private final List<String> pageKeys = new ArrayList<>();
    private final Map<String, Map<String, Group>> pageGroups = new LinkedHashMap<>();
    private final Map<String, String> pageTitles = new LinkedHashMap<>();
    private String currentPage;
    private Group currentGroup;
    private boolean fileOnlyScope;
    private final java.util.Set<AtmosOption<?>> overlapSwitches = new java.util.HashSet<>();
    private ForgeConfigSpec spec;
    private volatile boolean dirty;
    private volatile ModConfig modConfig;
    private volatile long lastModified;

    private AtmosConfig(String modId, String title, boolean serverView) {
        this.modId = modId;
        this.title = title;
        this.serverView = serverView;
    }

    public static AtmosConfig create(String modId, String title) {
        return new AtmosConfig(modId, title, false);
    }

    /**
     * The in-game controls for a mod's server-side settings (0.10.0): a plain Forge spec the mod registers itself (it
     * must load on a dedicated server, where this library's client classes do not). Call from client code only; wrap
     * the spec's values with the {@code wrap*} methods. Shown as the group "World (server)" on the mod's page, and only
     * to the host of a singleplayer or LAN world (a multiplayer server's file rules there). Changes are saved to the
     * server's file and read live by the integrated server.
     */
    public static AtmosConfig serverView(String modId, String title) {
        AtmosConfig view = new AtmosConfig(modId, title, true);
        view.group("server", "World (server)");
        view.currentGroup.server = true;
        synchronized (SERVER_VIEWS) {
            SERVER_VIEWS.add(view);
        }
        return view;
    }

    public String modId() {
        return modId;
    }

    public String title() {
        return title;
    }

    /** "light_shafts" becomes "Light Shafts"; "VANILLA_PLUS" becomes "Vanilla Plus". */
    public static String normalCase(String constant) {
        StringBuilder out = new StringBuilder(constant.length());
        for (String word : constant.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    /** Starts a TOML section (closes the previous one) and opens the group of the same name, titled after it. */
    public AtmosConfig section(String name, String comment) {
        return section(name, normalCase(name), comment);
    }

    /** Starts a TOML section and opens (or reopens) the group of the same name with this title (0.10.0). */
    public AtmosConfig section(String name, String groupTitle, String comment) {
        startSection(name, comment);
        page("main", title);
        return group(name, groupTitle);
    }

    /**
     * Opens a group on the current page, or reopens it if it exists (0.10.0): the options declared next belong to it,
     * whatever TOML section they are in. UI only; the TOML is unchanged.
     */
    public AtmosConfig group(String key, String groupTitle) {
        return openGroup(key, groupTitle, false);
    }

    /** Like {@link #group(String, String)}, but the group is shown after the page's other groups (debug, advanced). */
    public AtmosConfig groupAtEnd(String key, String groupTitle) {
        return openGroup(key, groupTitle, true);
    }

    /**
     * Before 0.10.0: started a new visual group inside a section. Now a gap inside the current group (an untitled
     * subheading); declare named groups with {@link #group(String, String)} instead.
     */
    @Deprecated
    public AtmosConfig group() {
        return subheading(null, null);
    }

    /** A heading row inside the current group (0.10.0); {@code null} title draws a gap. */
    public AtmosConfig subheading(String key, String headingTitle) {
        ensureGroup();
        if (!currentGroup.entries.isEmpty() || headingTitle != null) {
            currentGroup.entries.add(new Subheading(key, headingTitle));
        }
        return this;
    }

    /** Puts this toggle on the current group's title row (0.10.0): the group's master switch, visible while closed. */
    public AtmosConfig headerSwitch(AtmosOption<Boolean> option) {
        ensureGroup();
        currentGroup.entries.remove(option);
        currentGroup.master = option;
        return this;
    }

    private AtmosConfig openGroup(String key, String groupTitle, boolean atEnd) {
        if (currentPage == null) {
            page("main", title);
        }
        Map<String, Group> groups = pageGroups.get(currentPage);
        currentGroup = groups.computeIfAbsent(key, k -> new Group(k, groupTitle, atEnd));
        return this;
    }

    private void ensureGroup() {
        if (currentGroup == null) {
            openGroup("general", "General", false);
        }
    }

    /**
     * Starts a TOML section shown on a page of its own, "&lt;mod title&gt;: &lt;title&gt;". Rarely needed since groups
     * collapse (0.10.0); no mod uses it.
     */
    public AtmosConfig pageSection(String name, String title, String comment) {
        startSection(name, comment);
        page(name, this.title + ": " + title);
        return group(name, title);
    }

    private void page(String key, String pageTitle) {
        if (!pageGroups.containsKey("main")) {
            pageKeys.add("main");
            pageGroups.put("main", new LinkedHashMap<>());
            pageTitles.put("main", title);
        }
        if (!pageGroups.containsKey(key)) {
            pageKeys.add(key);
            pageGroups.put(key, new LinkedHashMap<>());
            pageTitles.put(key, pageTitle);
        }
        currentPage = key;
    }

    private void startSection(String name, String comment) {
        closeSection();
        builder.comment(comment).push(name);
        sections.add(name);
    }

    private void closeSection() {
        if (!sections.isEmpty()) {
            builder.pop();
            sections.remove(sections.size() - 1);
        }
    }

    private String path(String key) {
        return sections.isEmpty() ? key : String.join(".", sections) + "." + key;
    }

    /**
     * Options declared while this is on are TOML only (0.8.3): they keep their file entry, reload and tier, but get no
     * control in the settings screens, and their file comment says so. Since 0.10.0 groups collapse, so the family's
     * mods show nearly everything; colours and lists are always file-only. A new section does not reset it.
     */
    public AtmosConfig fileOnly(boolean on) {
        fileOnlyScope = on;
        return this;
    }

    /** The TOML comment: the tooltip, plus a note for {@link #fileOnly} options. */
    private String comment(String tooltip) {
        return fileOnlyScope ? tooltip + " Set in this file only (not shown in Video Settings)." : tooltip;
    }

    public AtmosOption<Boolean> toggle(String key, boolean defaultValue, ChangeTier tier, String name, String tooltip) {
        ForgeConfigSpec.BooleanValue value = builder.comment(comment(tooltip)).define(key, defaultValue);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.TOGGLE, 0, 1, 1, null, value));
    }

    public AtmosOption<Integer> slider(String key, int defaultValue, int min, int max, int step, ChangeTier tier,
                                       String name, String tooltip) {
        ForgeConfigSpec.IntValue value = builder.comment(comment(tooltip)).defineInRange(key, defaultValue, min, max);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.SLIDER, min, max, step, null, value));
    }

    /** A multiplier stored as a double (1.0 = 100 %) and shown as a percentage slider in 5 % steps. */
    public AtmosOption<Double> percent(String key, double defaultValue, double min, double max, ChangeTier tier,
                                       String name, String tooltip) {
        ForgeConfigSpec.DoubleValue value = builder.comment(comment(tooltip)).defineInRange(key, defaultValue, min, max);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.PERCENT,
                (int) Math.round(min * 100), (int) Math.round(max * 100), 5, null, value));
    }

    /**
     * A colour as a {@code #RRGGBB} string (0.6.5), read with {@link AtmosOption#rgb()}. TOML only: it is not shown in
     * the settings screens (they have no text field). An invalid value falls back to the default.
     */
    public AtmosOption<String> color(String key, String defaultValue, ChangeTier tier, String name, String tooltip) {
        ForgeConfigSpec.ConfigValue<String> value = builder.comment(tooltip + " Hex colour, #RRGGBB (set in this file only).")
                .define(key, defaultValue, v -> v instanceof String s && AtmosOption.parseRgb(s) >= 0);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.COLOR, 0, 0, 1, null, value));
    }

    /**
     * A list of strings (0.8.3), for example block ids. TOML only: it is not shown in the settings screens. Entries
     * that are not strings are dropped by Forge's correction. Editing the file applies it at {@code tier}.
     */
    public AtmosOption<List<? extends String>> stringList(String key, List<String> defaultValue, ChangeTier tier,
                                                          String name, String tooltip) {
        List<String> def = List.copyOf(defaultValue);
        ForgeConfigSpec.ConfigValue<List<? extends String>> value = builder
                .comment(tooltip + " A list of strings (set in this file only).")
                .defineListAllowEmpty(List.of(key), () -> def, entry -> entry instanceof String);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.LIST, 0, 0, 1, null, value));
    }

    public <E extends Enum<E>> AtmosOption<E> choice(String key, E defaultValue, ChangeTier tier, String name, String tooltip) {
        ForgeConfigSpec.EnumValue<E> value = builder.comment(comment(tooltip)).defineEnum(key, defaultValue);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.CYCLE, 0, 0, 1,
                defaultValue.getDeclaringClass(), value));
    }

    /** A server view's toggle over a value of the mod's own server spec (0.10.0); see {@link #serverView}. */
    public AtmosOption<Boolean> wrapToggle(ForgeConfigSpec.BooleanValue value, String key, String name, String tooltip) {
        return add(new AtmosOption<>(this, key, name, tooltip, ChangeTier.LIVE, AtmosOption.Control.TOGGLE, 0, 1, 1, null, value));
    }

    /** A server view's percentage slider (1.0 = 100 %) over a value of the mod's server spec (0.10.0). */
    public AtmosOption<Double> wrapPercent(ForgeConfigSpec.DoubleValue value, double min, double max, String key,
                                           String name, String tooltip) {
        return add(new AtmosOption<>(this, key, name, tooltip, ChangeTier.LIVE, AtmosOption.Control.PERCENT,
                (int) Math.round(min * 100), (int) Math.round(max * 100), 5, null, value));
    }

    /** A server view's choice over an enum value of the mod's server spec (0.10.0). */
    public <E extends Enum<E>> AtmosOption<E> wrapChoice(ForgeConfigSpec.EnumValue<E> value, Class<E> type, String key,
                                                         String name, String tooltip) {
        return add(new AtmosOption<>(this, key, name, tooltip, ChangeTier.LIVE, AtmosOption.Control.CYCLE, 0, 0, 1, type, value));
    }

    /**
     * The standard pair of options for a feature: {@code <key>} (on/off) and {@code respect_overlap}. Their tier is
     * LIVE because the cost of switching a feature follows from what the feature uses (see AtmosFeature.Uses). When the
     * switch is the first option of its group it becomes the group's master switch (0.10.0).
     */
    public FeatureSwitch featureSwitch(String key, boolean defaultOn, String name, String tooltip) {
        AtmosOption<Boolean> enabled = toggle(key, defaultOn, ChangeTier.LIVE, name, tooltip);
        if (currentGroup.master == null && currentGroup.entries.size() == 1 && currentGroup.entries.get(0) == enabled
                && !enabled.isFileOnly()) {
            headerSwitch(enabled);
        }
        AtmosOption<Boolean> respect = toggle(key + "_respect_overlap", true, ChangeTier.LIVE,
                title + ": " + withoutTitle(name), // 0.9.2: in the library page's defer group, named by mod
                "Turn " + name + " off while a shaderpack or another mod already provides the same effect. "
                        + "Off forces it on where that is technically possible (a log warning says so).");
        overlapSwitches.add(respect);
        currentGroup.entries.remove(respect);
        return new FeatureSwitch(enabled, respect);
    }

    /** "Atmosphera clouds" becomes "Clouds" under "Atmosphera: " (the group names the mod already). */
    private String withoutTitle(String name) {
        String prefix = title + " ";
        if (name.length() > prefix.length() && name.regionMatches(true, 0, prefix, 0, prefix.length())) {
            String rest = name.substring(prefix.length());
            return Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
        }
        return name;
    }

    private <T> AtmosOption<T> add(AtmosOption<T> option) {
        if (spec != null) {
            throw new IllegalStateException(modId + ": options must be declared before register()");
        }
        option.fileOnly = fileOnlyScope;
        options.add(option);
        ensureGroup();
        currentGroup.entries.add(option);
        return option;
    }

    /** Builds the spec and registers the TOML; call from the owning mod's constructor. */
    public void register() {
        if (serverView) {
            throw new IllegalStateException(modId + ": a server view is not registered (the mod registers its own spec)");
        }
        closeSection();
        spec = builder.build();
        String fileName = modId + "-client.toml";
        AtmosPaths.file(fileName); // creates config/Atmosphera and moves a pre-0.6.7 file across
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, spec, AtmosPaths.relative(fileName));
        // 0.10.0: the Mods list's "Config" button opens the library's settings screen at this mod's page.
        ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                        (mc, parent) -> AtmosSettingsScreen.open(parent, modId)));
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener((ModConfigEvent.Loading event) -> {
            if (event.getConfig().getSpec() == spec) {
                modConfig = event.getConfig();
                lastModified = lastModified();
            }
        });
        // Edits to the TOML while the game runs, when Forge's watcher notices them (on its own thread).
        modBus.addListener((ModConfigEvent.Reloading event) -> {
            if (event.getConfig().getSpec() == spec) {
                lastModified = lastModified();
                Minecraft.getInstance().execute(AtmosState::refreshNow);
            }
        });
        synchronized (CONFIGS) {
            CONFIGS.add(this);
        }
    }

    public List<AtmosOption<?>> options() {
        return List.copyOf(options);
    }

    /** The settings pages: the main page, then any {@link #pageSection} pages. Empty groups are left out. */
    public List<Page> pages() {
        List<Page> out = new ArrayList<>();
        for (String key : pageKeys) {
            List<Group> groups = new ArrayList<>();
            List<Group> last = new ArrayList<>();
            for (Group group : pageGroups.get(key).values()) {
                if (!group.isEmpty()) {
                    (group.atEnd ? last : groups).add(group);
                }
            }
            groups.addAll(last);
            out.add(new Page(key, pageTitles.get(key), List.copyOf(groups)));
        }
        return out;
    }

    /** Whether this option is a feature's "defer to shaders/mods" switch (shown on the library's page, 0.9.2). */
    public boolean isOverlapSwitch(AtmosOption<?> option) {
        return overlapSwitches.contains(option);
    }

    /** True for a {@link #serverView}. */
    public boolean isServerView() {
        return serverView;
    }

    void markDirty() {
        dirty = true;
    }

    public static List<AtmosConfig> all() {
        synchronized (CONFIGS) {
            return List.copyOf(CONFIGS);
        }
    }

    /** The configs in page order (0.9.2): the library first, then the mods by title (they register in parallel). */
    public static List<AtmosConfig> ordered() {
        List<AtmosConfig> configs = new ArrayList<>(all());
        configs.sort(java.util.Comparator.comparing((AtmosConfig c) -> !AtmospheraLib.MOD_ID.equals(c.modId()))
                .thenComparing(AtmosConfig::title, String.CASE_INSENSITIVE_ORDER));
        return configs;
    }

    /** The server views of a mod (0.10.0), usually none or one. */
    public static List<AtmosConfig> serverViews(String modId) {
        List<AtmosConfig> out = new ArrayList<>();
        synchronized (SERVER_VIEWS) {
            for (AtmosConfig view : SERVER_VIEWS) {
                if (view.modId.equals(modId)) {
                    out.add(view);
                }
            }
        }
        return out;
    }

    /** Writes the files edited in the GUI and applies the changes (render thread). */
    public static void saveAndApply() {
        for (AtmosConfig config : all()) {
            if (config.dirty) {
                config.dirty = false;
                if (!config.options.isEmpty()) {
                    config.options.get(0).value.save();
                    config.lastModified = config.lastModified();
                }
            }
        }
        List<AtmosConfig> views;
        synchronized (SERVER_VIEWS) {
            views = List.copyOf(SERVER_VIEWS);
        }
        for (AtmosConfig view : views) {
            if (view.dirty) {
                view.dirty = false;
                for (AtmosOption<?> option : view.options) {
                    try {
                        option.value.save();
                        break; // one save writes the whole file
                    } catch (RuntimeException e) {
                        AtmospheraLib.LOGGER.warn("Could not save {}'s server settings: {}", view.modId, e.toString());
                    }
                }
            }
        }
        AtmosState.refreshNow();
    }

    /**
     * Reloads TOML files edited outside the game. Forge's own file watcher does not fire reliably for files in
     * subfolders, so the library also checks the modification time of each Atmosphera config once a second
     * (one file-system stat per mod). Returns true if anything was reloaded. Render thread.
     */
    public static boolean pollFiles() {
        boolean reloaded = false;
        for (AtmosConfig config : all()) {
            reloaded |= config.poll(false);
        }
        return reloaded;
    }

    /**
     * Re-reads every Atmosphera TOML now, edited or not (0.8.0, {@link AtmosReload}). Returns how many were read. The
     * caller applies the changes ({@code AtmosState.refreshNow()}). Render thread.
     */
    public static int reloadAll() {
        int count = 0;
        for (AtmosConfig config : all()) {
            if (config.poll(true)) {
                count++;
            }
        }
        return count;
    }

    private boolean poll(boolean force) {
        if (modConfig == null) {
            return false;
        }
        long modified = lastModified();
        if (!force && modified == lastModified) {
            return false;
        }
        lastModified = modified;
        try {
            if (modConfig.getConfigData() instanceof CommentedFileConfig file) {
                file.load();
                if (spec.correct(file) > 0) {
                    file.save();
                    lastModified = lastModified();
                }
                spec.afterReload();
                if (!force) {
                    AtmospheraLib.LOGGER.info("Reloaded {} after it was edited.", modConfig.getFileName());
                }
                return true;
            }
        } catch (RuntimeException e) {
            AtmospheraLib.LOGGER.warn("Could not reload {}: {}", modConfig.getFileName(), e.toString());
        }
        return false;
    }

    /** The TOML as a java.io.File, kept: a stat through it allocates nothing (0.4.0; NIO built paths and attributes). */
    private java.io.File file;

    private long lastModified() {
        try {
            if (modConfig == null) {
                return 0L;
            }
            if (file == null) {
                file = modConfig.getFullPath().toFile();
            }
            return file.lastModified();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** Highest tier among options changed since the last call, or null if none changed. */
    public static ChangeTier collectChanges() {
        ChangeTier tier = null;
        for (AtmosConfig config : all()) {
            for (AtmosOption<?> option : config.options) {
                ChangeTier changed = option.collectChange();
                if (changed != null) {
                    tier = tier == null ? changed : tier.max(changed);
                }
            }
        }
        return tier;
    }

    /** True once Forge has loaded this config (values readable). */
    public boolean isLoaded() {
        return spec != null && spec.isLoaded();
    }
}
