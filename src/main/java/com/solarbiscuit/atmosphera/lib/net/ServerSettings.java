package com.solarbiscuit.atmosphera.lib.net;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * What a server-side Atmosphera mod has switched on (0.9.0): a small handshake. A mod whose gameplay runs on the
 * server (Gale's wind on elytra) publishes its settings as names and numbers; the library sends them to every client
 * at login, and again when the mod says they changed. A client mod reads them to know whether to predict the same
 * effect locally, and with which coefficients.
 * <p>
 * In singleplayer the integrated server publishes and the same game's client receives, so both paths are the same
 * code. On a server without the mod nothing arrives and {@link #present} is false: the client mod then leaves the
 * gameplay effect off, so it never fights the server.
 */
public final class ServerSettings {
    private static final Map<String, Supplier<Map<String, Float>>> PUBLISHERS = new ConcurrentHashMap<>();
    private static final Map<String, Map<String, Float>> RECEIVED = new ConcurrentHashMap<>();

    private ServerSettings() {
    }

    /** Server (both sides load it): the settings a mod announces to clients. Call from the mod's constructor. */
    public static void publish(String modId, Supplier<Map<String, Float>> values) {
        PUBLISHERS.put(modId, values);
    }

    /**
     * 0.11.0: re-sends a mod's settings to every player whenever Forge reloads {@code spec} (its server config edited on
     * disk). Register on the mod's own bus in its constructor, next to {@link #publish}.
     */
    public static void resendOnReload(net.minecraftforge.eventbus.api.IEventBus modBus, String modId,
                                      net.minecraftforge.common.ForgeConfigSpec spec) {
        // 0.11.4 (integration test IT-5): Forge's file watcher missed a saved server config on the test server, so the
        // file is also polled from the server tick once a second (as the client configs are, AtmosConfig.poll).
        Watched watched = new Watched(modId, spec);
        modBus.addListener((net.minecraftforge.fml.event.config.ModConfigEvent.Loading e) -> {
            if (e.getConfig().getSpec() == spec) {
                watched.config = e.getConfig();
                watched.modified = watched.lastModified();
                watched.primed = false;
                WATCHED.add(watched);
                ensureTicking();
            }
        });
        modBus.addListener((net.minecraftforge.fml.event.config.ModConfigEvent.Reloading e) -> {
            if (e.getConfig().getSpec() == spec) {
                watched.modified = watched.lastModified();
                MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
                if (server != null) {
                    // The reload comes from the file watcher's thread; players are sent to from the server thread.
                    server.execute(() -> resend(server, modId));
                }
            }
        });
    }

    /** 0.11.4: a server config polled for edits (see {@link #resendOnReload}). */
    private static final class Watched {
        final String modId;
        final net.minecraftforge.common.ForgeConfigSpec spec;
        volatile net.minecraftforge.fml.config.ModConfig config;
        volatile long modified;
        /** 0.11.5: false until the first poll after loading, which only records the file's time. */
        volatile boolean primed;
        java.io.File file;

        Watched(String modId, net.minecraftforge.common.ForgeConfigSpec spec) {
            this.modId = modId;
            this.spec = spec;
        }

        long lastModified() {
            try {
                if (file == null && config != null) {
                    file = config.getFullPath().toFile();
                }
                return file == null ? 0L : file.lastModified();
            } catch (RuntimeException e) {
                return 0L;
            }
        }

        /** Server thread: re-reads the file if it changed on disk; true when it did. */
        boolean poll() {
            long now = lastModified();
            if (!primed) {
                // 0.11.5: Forge saves the file after loading it, so the first poll found it "edited" and reloaded it
                // at every server start (a misleading log line). The first poll after loading takes the time as is.
                primed = config != null;
                modified = now;
                return false;
            }
            if (now == modified || config == null) {
                return false;
            }
            modified = now;
            try {
                if (config.getConfigData() instanceof com.electronwill.nightconfig.core.file.CommentedFileConfig data) {
                    data.load();
                    if (spec.correct(data) > 0) {
                        data.save();
                        modified = lastModified();
                    }
                    spec.afterReload();
                    com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.info("Reloaded {} after it was edited.",
                            config.getFileName());
                    return true;
                }
            } catch (RuntimeException e) {
                com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.warn("Could not reload {}: {}",
                        config.getFileName(), e.toString());
            }
            return false;
        }
    }

    private static final java.util.List<Watched> WATCHED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static boolean ticking;
    private static int pollTicks;

    private static synchronized void ensureTicking() {
        if (ticking) {
            return;
        }
        ticking = true;
        net.minecraftforge.common.MinecraftForge.EVENT_BUS.addListener(
                (net.minecraftforge.event.TickEvent.ServerTickEvent e) -> {
                    if (e.phase != net.minecraftforge.event.TickEvent.Phase.END || ++pollTicks < 20) {
                        return;
                    }
                    pollTicks = 0;
                    for (Watched w : WATCHED) {
                        if (w.poll()) {
                            resend(e.getServer(), w.modId);
                        }
                    }
                });
    }

    /** Server: sends a mod's settings to everyone again, after its server config changed. */
    public static void resend(MinecraftServer server, String modId) {
        Supplier<Map<String, Float>> values = PUBLISHERS.get(modId);
        if (values == null || server == null) {
            return;
        }
        Map<String, Float> now = Map.copyOf(values.get());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            AtmosNet.sendSettings(player, modId, now);
        }
    }

    static void sendAll(ServerPlayer player) {
        for (Map.Entry<String, Supplier<Map<String, Float>>> e : PUBLISHERS.entrySet()) {
            AtmosNet.sendSettings(player, e.getKey(), Map.copyOf(e.getValue().get()));
        }
    }

    static void accept(String modId, Map<String, Float> values) {
        RECEIVED.put(modId, Map.copyOf(values));
    }

    /** Client: forget everything received (on leaving a server). */
    public static void clear() {
        RECEIVED.clear();
    }

    /** Client: whether the server announced settings for this mod (it runs there). Any thread. */
    public static boolean present(String modId) {
        return RECEIVED.containsKey(modId);
    }

    /** Client: a setting the server announced, or the fallback. Any thread; no allocation. */
    public static float get(String modId, String key, float fallback) {
        Map<String, Float> values = RECEIVED.get(modId);
        if (values == null) {
            return fallback;
        }
        Float value = values.get(key);
        return value == null ? fallback : value;
    }
}
