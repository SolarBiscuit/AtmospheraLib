package com.solarbiscuit.atmosphera.lib.options;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.gpu.ProgramCache;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Rapid reload of every Atmosphera mod's settings in game (0.8.0): {@code /atmosphera reload}, {@code /atmosphera
 * reload shaders} and the "Reload Atmosphera" key. Client-side only (client configs, client commands), so it works on
 * any server, single player or multiplayer, with no permissions.
 * <p>
 * A settings reload re-reads every Atmosphera TOML at once (without waiting for the once-a-second timestamp poll) and
 * applies what changed, at the cost the change needs (live, shader recompile or chunk rebuild). A shader reload also
 * recompiles the terrain programs and asks every mod to free its own post-pass programs ({@link #register}); they are
 * rebuilt on the next frame.
 */
public final class AtmosReload {
    private record Hook(String owner, Runnable onReload) {
    }

    private static final List<Hook> HOOKS = new ArrayList<>();

    /** What a reload did, for the chat line and the log. */
    public record Summary(int configs, int changed, List<String> changedPaths, int hooks, boolean shaders, long micros) {
        public String line() {
            StringBuilder s = new StringBuilder("Atmosphera: reloaded ").append(configs).append(" config")
                    .append(configs == 1 ? "" : "s").append(", ").append(changed).append(" setting")
                    .append(changed == 1 ? "" : "s").append(" changed");
            if (shaders) {
                s.append(", shaders rebuilt (").append(hooks).append(" mod pass").append(hooks == 1 ? "" : "es")
                        .append(")");
            }
            return s.append(String.format(" in %.1f ms.", micros / 1000.0)).toString();
        }
    }

    private AtmosReload() {
    }

    /**
     * Registers what a mod does on a shader reload, typically freeing its post-pass programs and look-up tables so the
     * next frame builds them again. Called on the render thread. Register from the mod's constructor or setup.
     */
    public static synchronized void register(String owner, Runnable onReload) {
        HOOKS.add(new Hook(owner, onReload));
    }

    /** Reloads every Atmosphera config now and applies the changes; with {@code shaders}, rebuilds all shaders too. */
    public static Summary reload(boolean shaders) {
        long start = System.nanoTime();
        Map<String, Object> before = snapshot();
        int configs = AtmosConfig.reloadAll();
        Map<String, Object> after = snapshot();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> entry : after.entrySet()) {
            if (!Objects.equals(before.get(entry.getKey()), entry.getValue())) {
                changed.add(entry.getKey());
            }
        }
        AtmosState.refreshNow();
        int ran = 0;
        if (shaders) {
            ProgramCache.recompileAll();
            List<Hook> hooks;
            synchronized (AtmosReload.class) {
                hooks = List.copyOf(HOOKS);
            }
            for (Hook hook : hooks) {
                try {
                    hook.onReload().run();
                    ran++;
                } catch (RuntimeException e) {
                    AtmospheraLib.LOGGER.error("Atmosphera reload hook of {} failed", hook.owner(), e);
                }
            }
        }
        Summary summary = new Summary(configs, changed.size(), changed, ran, shaders, (System.nanoTime() - start) / 1000L);
        AtmospheraLib.LOGGER.info("[reload] {}{}", summary.line(), changed.isEmpty() ? "" : " Changed: " + changed);
        return summary;
    }

    private static Map<String, Object> snapshot() {
        Map<String, Object> values = new HashMap<>();
        for (AtmosConfig config : AtmosConfig.all()) {
            if (!config.isLoaded()) {
                continue;
            }
            for (AtmosOption<?> option : config.options()) {
                try {
                    values.put(config.modId() + ":" + option.path(), option.get());
                } catch (RuntimeException ignored) {
                    // not loaded yet
                }
            }
        }
        return values;
    }
}
