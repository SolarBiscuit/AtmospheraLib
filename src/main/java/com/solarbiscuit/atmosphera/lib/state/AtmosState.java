package com.solarbiscuit.atmosphera.lib.state;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.compat.OverlapGuard;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.FeatureRegistry;
import com.solarbiscuit.atmosphera.lib.mesh.BlockMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.FluidMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.MeshHooks;
import com.solarbiscuit.atmosphera.lib.options.AtmosConfig;
import com.solarbiscuit.atmosphera.lib.options.ChangeTier;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Immutable snapshot of what Atmosphera is doing right now: which features are active (and why the others are not),
 * the mesh hooks the chunk builders must call, and which scene copies the translucent pass needs.
 * <p>
 * Chunk-builder threads read {@link #current()} (one volatile read) and nothing else of the library's mutable state,
 * so a refresh can never race a mesh build into a half-updated view. Refreshes run on the render thread: on startup,
 * on "Apply" in the options tab, when a TOML file is edited, when the renderer reloads (Oculus pack toggles,
 * F3+A, video settings) and when the once-per-second environment poll sees a change. Each refresh works out the
 * cheapest {@link ChangeTier} that covers what changed and applies it (recompile or chunk rebuild).
 */
public final class AtmosState {
    /** Called on the render thread after a refresh is published, before its tier is applied. */
    @FunctionalInterface
    public interface Listener {
        void onRefresh(AtmosState state, ChangeTier tier);
    }

    private static final AtmosState EMPTY = new AtmosState(new boolean[0], new String[0], new BlockMeshHook[0],
            new BlockMeshHook[0], new FluidMeshHook[0], false, false, false, null, 0);
    private static volatile AtmosState current = EMPTY;
    private static volatile boolean refreshRequested;
    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();

    private final boolean[] active;
    private final String[] reasons;
    private final BlockMeshHook[] blockHooks;
    private final BlockMeshHook[] lightHooks;
    private final FluidMeshHook[] fluidHooks;
    private final boolean lightBits;
    private final boolean sceneDepth;
    private final boolean sceneColor;
    private final OverlapGuard.Environment env;
    private final long version;

    private AtmosState(boolean[] active, String[] reasons, BlockMeshHook[] blockHooks, BlockMeshHook[] lightHooks,
                       FluidMeshHook[] fluidHooks, boolean lightBits, boolean sceneDepth, boolean sceneColor, OverlapGuard.Environment env, long version) {
        this.active = active;
        this.reasons = reasons;
        this.blockHooks = blockHooks;
        this.lightHooks = lightHooks;
        this.fluidHooks = fluidHooks;
        this.lightBits = lightBits;
        this.sceneDepth = sceneDepth;
        this.sceneColor = sceneColor;
        this.env = env;
        this.version = version;
    }

    /** The published snapshot. Any thread. */
    public static AtmosState current() {
        return current;
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    public boolean isActive(AtmosFeature feature) {
        int i = feature.index();
        return i < active.length && active[i];
    }

    public String reason(AtmosFeature feature) {
        int i = feature.index();
        return i < reasons.length ? reasons[i] : "not initialised yet";
    }

    public int activeCount() {
        int n = 0;
        for (boolean a : active) {
            if (a) n++;
        }
        return n;
    }

    /** Block hooks of active features; empty array when nothing writes mesh bits. */
    public BlockMeshHook[] blockHooks() {
        return blockHooks;
    }

    /**
     * The subset of {@link #blockHooks()} whose features declare {@code Uses.LIGHT_BITS}. Only these get the
     * lightBits/perVertex calls, which keeps those call sites monomorphic (inlined) and saves two calls per block for
     * material-only hooks.
     */
    public BlockMeshHook[] lightHooks() {
        return lightHooks;
    }

    public FluidMeshHook[] fluidHooks() {
        return fluidHooks;
    }

    /** Whether light-coordinate bits may be written (compact format, no shaderpack). */
    public boolean lightBits() {
        return lightBits;
    }

    public boolean sceneDepth() {
        return sceneDepth;
    }

    public boolean sceneColor() {
        return sceneColor;
    }

    public OverlapGuard.Environment env() {
        return env;
    }

    public long version() {
        return version;
    }

    /**
     * Defines for the terrain programs: one macro per active terrain-shader feature, plus {@code ATMOS_FACE_NORMAL}
     * while one of them reads the shared face normal.
     */
    public void forEachFeatureDefine(Consumer<String> define) {
        boolean faceNormal = false;
        for (AtmosFeature feature : FeatureRegistry.all()) {
            if (isActive(feature) && feature.uses(AtmosFeature.Uses.TERRAIN_SHADER)) {
                define.accept(feature.macro());
                faceNormal |= feature.uses(AtmosFeature.Uses.FACE_NORMAL);
            }
        }
        if (faceNormal) {
            define.accept("ATMOS_FACE_NORMAL");
        }
    }

    public String summary() {
        StringBuilder out = new StringBuilder();
        for (AtmosFeature feature : FeatureRegistry.all()) {
            out.append(feature.id()).append(": ").append(isActive(feature) ? "active" : reason(feature)).append('\n');
        }
        return out.isEmpty() ? "No Atmosphera features installed." : out.toString().trim();
    }

    // ---- refresh -------------------------------------------------------------------------------------------------

    /** Asks for a refresh on the next client tick (any thread). */
    public static void requestRefresh() {
        refreshRequested = true;
    }

    /** Client tick: runs a requested refresh, and polls the environment once a second. Render thread. */
    public static void tick(long tickCount) {
        if (refreshRequested) {
            refreshRequested = false;
            refresh(false);
        } else if (tickCount % 20 == 0 && current != EMPTY) {
            OverlapGuard.Environment env = OverlapGuard.Environment.probe();
            if (AtmosConfig.pollFiles() || !env.sameAs(current.env)) {
                refresh(false);
            }
        }
    }

    /** Refresh now and apply whatever the change needs. Render thread. */
    public static void refreshNow() {
        refresh(false);
    }

    /**
     * Called at the head of {@code LevelRenderer.allChanged()}: the renderer is about to rebuild every chunk and
     * recreate its programs anyway, so only the snapshot must be current before the first new mesh is built.
     */
    public static void beforeRendererReload() {
        refresh(true);
    }

    private static synchronized void refresh(boolean reloadPending) {
        List<AtmosFeature> features = FeatureRegistry.all();
        OverlapGuard.Environment env = OverlapGuard.Environment.probe();
        boolean[] active = new boolean[features.size()];
        String[] reasons = new String[features.size()];
        List<BlockMeshHook> blocks = new ArrayList<>();
        List<BlockMeshHook> lights = new ArrayList<>();
        List<FluidMeshHook> fluids = new ArrayList<>();
        boolean light = false;
        boolean depth = false;
        boolean color = false;
        for (AtmosFeature feature : features) {
            String reason;
            try {
                reason = OverlapGuard.reason(feature, env);
            } catch (IllegalStateException configNotLoaded) {
                reason = "config not loaded yet";
            }
            int i = feature.index();
            reasons[i] = reason;
            active[i] = reason == null;
            if (active[i]) {
                light |= feature.uses(AtmosFeature.Uses.LIGHT_BITS);
                depth |= feature.uses(AtmosFeature.Uses.SCENE_DEPTH);
                color |= feature.uses(AtmosFeature.Uses.SCENE_COLOR);
            }
        }
        for (MeshHooks.BlockEntry entry : MeshHooks.blockHooks()) {
            // 0.8.2: a feature registered but not in the frozen list (another mod broke loading before the freeze):
            // skip it instead of failing the resource reload, so the real error stays the one reported.
            if (entry.feature().index() >= active.length) {
                continue;
            }
            if (active[entry.feature().index()]) {
                blocks.add(entry.hook());
                if (entry.feature().uses(AtmosFeature.Uses.LIGHT_BITS)) lights.add(entry.hook());
            }
        }
        for (MeshHooks.FluidEntry entry : MeshHooks.fluidHooks()) {
            if (entry.feature().index() < active.length && active[entry.feature().index()]) fluids.add(entry.hook());
        }

        AtmosState previous = current;
        AtmosState next = new AtmosState(active, reasons, blocks.toArray(new BlockMeshHook[0]),
                lights.toArray(new BlockMeshHook[0]), fluids.toArray(new FluidMeshHook[0]), light, depth, color, env, previous.version + 1);

        ChangeTier tier = AtmosConfig.collectChanges();
        if (previous != EMPTY) {
            for (AtmosFeature feature : features) {
                if (previous.isActive(feature) != next.isActive(feature)) {
                    tier = max(tier, tierFor(feature));
                }
                logTransition(feature, previous.reason(feature), next.reason(feature));
            }
        } else {
            for (AtmosFeature feature : features) {
                logTransition(feature, null, next.reason(feature));
            }
            tier = null; // first snapshot: nothing has been built with an older one
        }
        current = next;

        ChangeTier applied = tier == null ? ChangeTier.LIVE : tier;
        for (Listener listener : LISTENERS) {
            try {
                listener.onRefresh(next, applied);
            } catch (RuntimeException e) {
                AtmospheraLib.LOGGER.error("Atmosphera refresh listener failed", e);
            }
        }
        if (reloadPending || applied == ChangeTier.LIVE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (applied == ChangeTier.REMESH && mc.levelRenderer != null && mc.level != null) {
            AtmospheraLib.LOGGER.info("Atmosphera settings changed: rebuilding chunks.");
            mc.levelRenderer.allChanged();
        } else {
            AtmospheraLib.LOGGER.info("Atmosphera settings changed: recompiling terrain shaders.");
            com.solarbiscuit.atmosphera.lib.gpu.ProgramCache.recompileAll();
        }
    }

    private static ChangeTier tierFor(AtmosFeature feature) {
        if (feature.uses(AtmosFeature.Uses.MESH_BITS) || feature.uses(AtmosFeature.Uses.LIGHT_BITS)) {
            return ChangeTier.REMESH;
        }
        if (feature.uses(AtmosFeature.Uses.TERRAIN_SHADER) || feature.uses(AtmosFeature.Uses.SCENE_DEPTH)
                || feature.uses(AtmosFeature.Uses.SCENE_COLOR)) {
            return ChangeTier.RECOMPILE;
        }
        return ChangeTier.LIVE;
    }

    private static ChangeTier max(ChangeTier a, ChangeTier b) {
        return a == null ? b : a.max(b);
    }

    /** One line per transition; overlap and compatibility reasons are warnings, config switches are info. */
    private static void logTransition(AtmosFeature feature, String before, String after) {
        if (Objects.equals(before, after)) {
            return;
        }
        if (after == null) {
            if (before != null) {
                AtmospheraLib.LOGGER.info("[Atmosphera] {} is active again.", feature.id());
            }
        } else if ("off in config".equals(after) || "config not loaded yet".equals(after)) {
            AtmospheraLib.LOGGER.info("[Atmosphera] {} is off ({}).", feature.id(), after);
        } else if (OverlapGuard.isOverlapReason(after)) {
            AtmospheraLib.LOGGER.warn("[Atmosphera] {} disabled: {}. Turn that effect off there, or switch off this "
                    + "feature's switch under \"Defer to shaderpacks and other mods\" (Video Settings, Atmosphera page) to force it on.", feature.id(), after);
        } else {
            AtmospheraLib.LOGGER.warn("[Atmosphera] {} disabled: {}.", feature.id(), after);
        }
    }
}
