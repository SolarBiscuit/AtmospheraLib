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
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * A mod's Atmosphera settings: declare each option once and get both the TOML entry
 * ({@code config/SolConfig/atmosphera/<modid>-client.toml}) and the control in Video Settings, where every TOML section is
 * a page under the owning mod's heading in the sidebar.
 * <pre>{@code
 * AtmosConfig config = AtmosConfig.create("gale", "Gale (wind)");
 * config.section("waving", "Waving foliage");
 * AtmosConfig.FeatureSwitch waving = config.featureSwitch("enabled", true, "Waving foliage", "...");
 * AtmosOption<Double> amplitude = config.percent("amplitude", 1.0, 0.0, 2.0, ChangeTier.LIVE, "Amplitude", "...");
 * config.register(); // in the mod constructor
 * }</pre>
 * Edits from the GUI apply on "Apply"; edits to the TOML apply when Forge notices the file changed. Changes made
 * together cost only their highest {@link ChangeTier}.
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

    /**
     * A page of Video Settings. Every mod has a main page named after it ({@link #title()}); each TOML section is a
     * group on it (groups are drawn apart). A section declared with {@link #pageSection} gets a page of its own,
     * named "&lt;mod title&gt;: &lt;section title&gt;". Options are in declaration order.
     */
    public record Page(String key, String title, List<List<AtmosOption<?>>> groups) {
    }

    private static final List<AtmosConfig> CONFIGS = new ArrayList<>();

    private final String modId;
    private final String title;
    private final ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
    private final List<AtmosOption<?>> options = new ArrayList<>();
    private final List<String> sections = new ArrayList<>();
    private final List<Page> pages = new ArrayList<>();
    private List<AtmosOption<?>> currentGroup;
    private final java.util.Set<AtmosOption<?>> overlapSwitches = new java.util.HashSet<>();
    private ForgeConfigSpec spec;
    private volatile boolean dirty;
    private volatile ModConfig modConfig;
    private volatile long lastModified;

    private AtmosConfig(String modId, String title) {
        this.modId = modId;
        this.title = title;
    }

    public static AtmosConfig create(String modId, String title) {
        return new AtmosConfig(modId, title);
    }

    public String modId() {
        return modId;
    }

    public String title() {
        return title;
    }

    /** Starts a TOML section (closes the previous one), shown as its own group on the mod's main page. */
    public AtmosConfig section(String name, String comment) {
        startSection(name, comment);
        mainPage().groups().add(new ArrayList<>());
        currentGroup = mainPage().groups().get(mainPage().groups().size() - 1);
        return this;
    }

    /** Starts a new visual group in the current section (same TOML section), for long sections. */
    public AtmosConfig group() {
        Page page = pages.isEmpty() ? mainPage() : pages.get(pages.size() - 1);
        if (currentGroup != null && currentGroup.isEmpty()) {
            return this;
        }
        page.groups().add(new ArrayList<>());
        currentGroup = page.groups().get(page.groups().size() - 1);
        return this;
    }

    /**
     * Starts a TOML section shown on a page of its own, "&lt;mod title&gt;: &lt;title&gt;": for long sections (for
     * example a custom style's sliders) that would bury the main page.
     */
    public AtmosConfig pageSection(String name, String title, String comment) {
        startSection(name, comment);
        mainPage();
        Page page = new Page(name, this.title + ": " + title, new ArrayList<>());
        page.groups().add(new ArrayList<>());
        pages.add(page);
        currentGroup = page.groups().get(0);
        return this;
    }

    private void startSection(String name, String comment) {
        closeSection();
        builder.comment(comment).push(name);
        sections.add(name);
    }

    /** Always pages[0], so the main page is listed before any pageSection pages. */
    private Page mainPage() {
        if (pages.isEmpty()) {
            pages.add(new Page("main", title, new ArrayList<>()));
        }
        return pages.get(0);
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

    public AtmosOption<Boolean> toggle(String key, boolean defaultValue, ChangeTier tier, String name, String tooltip) {
        ForgeConfigSpec.BooleanValue value = builder.comment(tooltip).define(key, defaultValue);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.TOGGLE, 0, 1, 1, null, value));
    }

    public AtmosOption<Integer> slider(String key, int defaultValue, int min, int max, int step, ChangeTier tier,
                                       String name, String tooltip) {
        ForgeConfigSpec.IntValue value = builder.comment(tooltip).defineInRange(key, defaultValue, min, max);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.SLIDER, min, max, step, null, value));
    }

    /** A multiplier stored as a double (1.0 = 100 %) and shown as a percentage slider in 5 % steps. */
    public AtmosOption<Double> percent(String key, double defaultValue, double min, double max, ChangeTier tier,
                                       String name, String tooltip) {
        ForgeConfigSpec.DoubleValue value = builder.comment(tooltip).defineInRange(key, defaultValue, min, max);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.PERCENT,
                (int) Math.round(min * 100), (int) Math.round(max * 100), 5, null, value));
    }

    public <E extends Enum<E>> AtmosOption<E> choice(String key, E defaultValue, ChangeTier tier, String name, String tooltip) {
        ForgeConfigSpec.EnumValue<E> value = builder.comment(tooltip).defineEnum(key, defaultValue);
        return add(new AtmosOption<>(this, path(key), name, tooltip, tier, AtmosOption.Control.CYCLE, 0, 0, 1,
                defaultValue.getDeclaringClass(), value));
    }

    /**
     * The standard pair of options for a feature: {@code <key>} (on/off) and {@code respect_overlap}. Their tier is
     * LIVE because the cost of switching a feature follows from what the feature uses (see AtmosFeature.Uses).
     */
    public FeatureSwitch featureSwitch(String key, boolean defaultOn, String name, String tooltip) {
        AtmosOption<Boolean> enabled = toggle(key, defaultOn, ChangeTier.LIVE, name, tooltip);
        AtmosOption<Boolean> respect = toggle(key + "_respect_overlap", true, ChangeTier.LIVE,
                name + ": defer to shaders/mods",
                "Turn " + name + " off while a shaderpack or another mod already provides the same effect. "
                        + "Off forces it on where that is technically possible (a log warning says so).");
        overlapSwitches.add(respect);
        return new FeatureSwitch(enabled, respect);
    }

    private <T> AtmosOption<T> add(AtmosOption<T> option) {
        if (spec != null) {
            throw new IllegalStateException(modId + ": options must be declared before register()");
        }
        options.add(option);
        if (currentGroup == null) {
            mainPage().groups().add(new ArrayList<>());
            currentGroup = mainPage().groups().get(mainPage().groups().size() - 1);
        }
        currentGroup.add(option);
        return option;
    }

    /** Builds the spec and registers the TOML; call from the owning mod's constructor. */
    public void register() {
        closeSection();
        spec = builder.build();
        try {
            Files.createDirectories(FMLPaths.CONFIGDIR.get().resolve("SolConfig").resolve("atmosphera"));
        } catch (IOException e) {
            AtmospheraLib.LOGGER.warn("Could not create config/SolConfig/atmosphera: {}", e.toString());
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, spec, "SolConfig/atmosphera/" + modId + "-client.toml");
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

    /** The settings pages: the main page, then any {@link #pageSection} pages. */
    public List<Page> pages() {
        return List.copyOf(pages);
    }

    /** Whether this option is a feature's "defer to shaders/mods" switch (shown in a separate group). */
    public boolean isOverlapSwitch(AtmosOption<?> option) {
        return overlapSwitches.contains(option);
    }

    void markDirty() {
        dirty = true;
    }

    public static List<AtmosConfig> all() {
        synchronized (CONFIGS) {
            return List.copyOf(CONFIGS);
        }
    }

    /** Writes the TOML files edited in the GUI and applies the changes (render thread). */
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
            reloaded |= config.poll();
        }
        return reloaded;
    }

    private boolean poll() {
        if (modConfig == null) {
            return false;
        }
        long modified = lastModified();
        if (modified == lastModified) {
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
                AtmospheraLib.LOGGER.info("Reloaded {} after it was edited.", modConfig.getFileName());
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
