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
