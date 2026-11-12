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
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new WeatherMessage(level.dimension(), state));
        }
    }

    /** Sends a level's weather to every client in it that has the library. Server thread. */
    public static void sendWeather(ServerLevel level, Weather.State state) {
        WeatherMessage message = new WeatherMessage(level.dimension(), state);
        for (ServerPlayer player : level.players()) {
            if (reaches(player)) {
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
            }
        }
    }

    static void sendSettings(ServerPlayer player, String modId, Map<String, Float> values) {
        if (reaches(player)) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new SettingsMessage(modId, values));
        }
    }

    /** A level's weather state. */
    private record WeatherMessage(ResourceKey<Level> dimension, Weather.State state) {
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
        }

        static WeatherMessage decode(FriendlyByteBuf buf) {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, buf.readResourceLocation());
            return new WeatherMessage(dimension, new Weather.State(Weather.Type.byOrdinal(buf.readByte()), buf.readFloat(),
                    buf.readFloat(), Weather.Type.byOrdinal(buf.readByte()), buf.readFloat(), buf.readLong(), buf.readLong(),
                    buf.readFloat()));
        }

        static void handle(WeatherMessage m, Supplier<NetworkEvent.Context> ctx) {
            // The store is a concurrent map of plain values: no client class is touched, so no thread hand-over.
            Weather.acceptSynced(m.dimension, m.state);
            ctx.get().setPacketHandled(true);
        }
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
            ServerSettings.accept(m.modId, m.values);
            ctx.get().setPacketHandled(true);
        }
    }
}
