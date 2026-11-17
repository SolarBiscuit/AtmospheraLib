package com.solarbiscuit.atmosphera.lib.net;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.world.Weather;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The library's networking (0.9.0), for the few things a client cannot work out by itself: the server's weather
 * ({@link Weather}) and what a server-side Atmosphera mod has switched on ({@link ServerSettings}).
 * <p>
 * Everything here is optional on both sides. The channel accepts a missing peer: a client with the library joins a
 * server without it, and a client without it joins a server with it. Nothing is sent to a player whose client did not
 * announce the channel. Packets only go from the server to clients. Common code: it loads on a dedicated server.
 */
public final class AtmosNet {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = channel(AtmospheraLib.MOD_ID, "main", VERSION);

    private AtmosNet() {
    }

    /**
     * A channel for a mod's own packets with the library's rule: either side may lack it. Register messages on it in
     * the mod's constructor, on both sides.
     */
    public static SimpleChannel channel(String modId, String name, String version) {
        return NetworkRegistry.newSimpleChannel(new ResourceLocation(modId, name), () -> version,
                NetworkRegistry.acceptMissingOr(version), NetworkRegistry.acceptMissingOr(version));
    }

    /** Whether this player's client has the library (and so receives its packets). */
    public static boolean reaches(ServerPlayer player) {
        return player.connection != null && CHANNEL.isRemotePresent(player.connection.connection);
    }

    /** Both sides, from the library's constructor. */
    public static void init() {
        CHANNEL.registerMessage(0, WeatherMessage.class, WeatherMessage::encode, WeatherMessage::decode,
                WeatherMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, SettingsMessage.class, SettingsMessage::encode, SettingsMessage::decode,
                SettingsMessage::handle, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        // A player arriving in a level gets its weather and, at login, every published server setting.
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                ServerSettings.sendAll(player);
                sendWeather(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerChangedDimensionEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                sendWeather(player);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerRespawnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer player) {
                sendWeather(player);
            }
        });
    }

    private static void sendWeather(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Weather.State state = Weather.published(level.dimension());
        if (state != null && reaches(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new WeatherMessage(level.dimension(), state, false));
        }
    }

    /** Sends a level's weather to every client in it that has the library. Server thread. */
    public static void sendWeather(ServerLevel level, Weather.State state) {
        sendToLevel(level, new WeatherMessage(level.dimension(), state, false));
    }

    /**
     * Tells every client in a level that its weather state was withdrawn (0.10.3). The packet carries the last state
     * too, so a 0.10.2 client (which does not read the flag) keeps it, as it did before.
     */
    public static void sendWithdraw(ServerLevel level, Weather.State last) {
        sendToLevel(level, new WeatherMessage(level.dimension(), last, true));
    }

    private static void sendToLevel(ServerLevel level, WeatherMessage message) {
        for (ServerPlayer player : level.players()) {
            if (reaches(player)) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
            }
        }
    }

    /**
     * Runs a received packet's work on the main thread, and only while its connection is still open (0.10.3). Before,
     * handlers wrote on the network thread, so a packet decoded just before logout could fill the client's maps again
     * after the logout cleared them.
     */
    private static void onMainThread(Supplier<NetworkEvent.Context> ctx, Runnable work) {
        NetworkEvent.Context context = ctx.get();
        net.minecraft.network.Connection connection = context.getNetworkManager();
        context.enqueueWork(() -> {
            if (connection == null || connection.isConnected()) {
                work.run();
            }
        });
        context.setPacketHandled(true);
    }

    static void sendSettings(ServerPlayer player, String modId, Map<String, Float> values) {
        if (!reaches(player)) {
            return;
        }
        // 0.11.1: names longer than the packet's 64 characters are left out (encoding them threw at every login).
        if (modId.length() > 64) {
            AtmospheraLib.LOGGER.warn("Server settings of {}: mod id longer than 64 characters, not sent", modId);
            return;
        }
        Map<String, Float> safe = values;
        for (String key : values.keySet()) {
            if (key.length() > 64) {
                safe = new HashMap<>(values);
                safe.keySet().removeIf(k -> k.length() > 64);
                AtmospheraLib.LOGGER.warn("Server settings of {}: keys longer than 64 characters left out", modId);
                break;
            }
        }
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SettingsMessage(modId, safe));
    }

    /** A level's weather state, or (0.10.3, {@code withdrawn}) word that the server no longer states one. */
    private record WeatherMessage(ResourceKey<Level> dimension, Weather.State state, boolean withdrawn) {
        static void encode(WeatherMessage m, FriendlyByteBuf buf) {
            buf.writeResourceLocation(m.dimension.location());
            Weather.State s = m.state;
            buf.writeByte(s.type().ordinal());
            buf.writeFloat(s.intensity());
            buf.writeFloat(s.windFactor());
            buf.writeByte(s.next().ordinal());
            buf.writeFloat(s.nextIntensity());
            buf.writeLong(s.changeAt());
            buf.writeLong(s.seed());
            buf.writeFloat(s.contrast());
            // 0.10.2: appended, so the channel version stays 1 (an older client ignores the trailing bytes; a newer
            // client reading an older server's packet finds none and takes -1).
            buf.writeLong(s.stormAt());
            // 0.10.3: appended the same way; an older client ignores it, an older server's packet has none.
            buf.writeBoolean(m.withdrawn);
        }

        static WeatherMessage decode(FriendlyByteBuf buf) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
            Weather.Type type = Weather.Type.byOrdinal(buf.readByte());
            float intensity = unit(buf.readFloat(), 0f);
            float windFactor = finite(buf.readFloat(), 1f, 0f, 4f);
            Weather.Type next = Weather.Type.byOrdinal(buf.readByte());
            float nextIntensity = unit(buf.readFloat(), 0f);
            long changeAt = buf.readLong();
            long seed = buf.readLong();
            float contrast = unit(buf.readFloat(), 0f);
            long stormAt = buf.readableBytes() >= 8 ? buf.readLong() : -1L;
            // 0.11.1: a NaN or infinite value from a buggy server would reach the uniforms; replaced and clamped.
            Weather.State state = new Weather.State(type, intensity, windFactor, next, nextIntensity, changeAt, seed,
                    contrast, stormAt);
            boolean withdrawn = buf.readableBytes() >= 1 && buf.readBoolean();
            return new WeatherMessage(dimension, state, withdrawn);
        }

        static void handle(WeatherMessage m, Supplier<NetworkEvent.Context> ctx) {
            onMainThread(ctx, () -> {
                if (m.withdrawn) {
                    Weather.dropSynced(m.dimension);
                } else {
                    Weather.acceptSynced(m.dimension, m.state);
                }
            });
        }
    }

    private static float unit(float v, float fallback) {
        return finite(v, fallback, 0f, 1f);
    }

    private static float finite(float v, float fallback, float min, float max) {
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
    }

    /** One server mod's settings: names to numbers (a switch is 0 or 1). */
    private record SettingsMessage(String modId, Map<String, Float> values) {
        static void encode(SettingsMessage m, FriendlyByteBuf buf) {
            buf.writeUtf(m.modId, 64);
            buf.writeVarInt(m.values.size());
            for (Map.Entry<String, Float> e : m.values.entrySet()) {
                buf.writeUtf(e.getKey(), 64);
                buf.writeFloat(e.getValue());
            }
        }

        static SettingsMessage decode(FriendlyByteBuf buf) {
            String modId = buf.readUtf(64);
            int n = Math.min(buf.readVarInt(), 256);
            Map<String, Float> values = new HashMap<>();
            for (int i = 0; i < n; i++) {
                values.put(buf.readUtf(64), buf.readFloat());
            }
            return new SettingsMessage(modId, values);
        }

        static void handle(SettingsMessage m, Supplier<NetworkEvent.Context> ctx) {
            onMainThread(ctx, () -> ServerSettings.accept(m.modId, m.values));
        }
    }
}
