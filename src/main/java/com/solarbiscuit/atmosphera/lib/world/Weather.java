package com.solarbiscuit.atmosphera.lib.world;

import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The weather service (0.9.0): what kind of weather it is, how heavy, and what comes next, in one place, so the
 * Atmosphera mods agree (clouds, wind, water, wetness, precipitation). Common code: it works on a server too.
 * <p>
 * Vanilla only knows "raining" and "thundering", and its rain level always reaches 1. A weather mod (Vapora) decides
 * more on the server: a type and an intensity for each spell, and when the next change comes. It hands the state to
 * {@link #publish}; the library keeps it for that level and sends it to every client there (and to players as they
 * arrive). On a client the state of its level comes from the server's packet; without one (a server without a
 * weather mod) a registered {@link Provider} answers, and last the default, which derives everything from vanilla's
 * levels and knows no forecast.
 * <p>
 * Reads are cheap and allocate nothing: {@link #current} fills a caller's {@link Sample}. Safe from any thread.
 */
public final class Weather {
    /** How heavy a spell is. Whether it falls as rain or snow is the biome's business, as in vanilla. */
    public enum Type {
        CLEAR, DRIZZLE, LIGHT_RAIN, RAIN, HEAVY_RAIN, STORM;

        private static final Type[] ALL = values();

        public static Type byOrdinal(int i) {
            return ALL[Mth.clamp(i, 0, ALL.length - 1)];
        }
    }

    /**
     * A level's weather as its source states it. {@code changeAt}: the game time of the next change, or -1 when
     * unknown; {@code next}: what follows then. {@code contrast} and {@code seed} shape the regional cells
     * ({@link #intensityAt}); contrast 0 is the same weather everywhere.
     */
    public record State(Type type, float intensity, float windFactor, Type next, float nextIntensity, long changeAt,
                        long seed, float contrast) {
    }

    /** A mutable reading, filled by {@link #current}; keep one per caller. */
    public static final class Sample {
        public Type type = Type.CLEAR;
        /** 0 (dry) to 1 (downpour), eased with the game's own rain level while a spell begins or ends. */
        public float intensity;
        /** Multiplier on the wind's storm terms (1 = the wind mod's own). */
        public float windFactor = 1f;
        public Type next = Type.CLEAR;
        public float nextIntensity;
        /** Game ticks until the next change, or -1 when unknown (no forecast). */
        public long ticksToChange = -1;
        /** True when a weather mod's state stands behind this (not the vanilla default). */
        public boolean modelled;
    }

    /** A client-side source for mods that decide weather without a server part. Higher priority wins. */
    public interface Provider {
        String name();

        /** Fills out and returns true, or returns false to let the next source answer. Any thread; no allocation. */
        boolean current(Level level, float partialTick, Sample out);
    }

    private static final Map<ResourceKey<Level>, State> SERVER = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, State> SYNCED = new ConcurrentHashMap<>();
    private static volatile Provider provider;
    private static int providerPriority = Integer.MIN_VALUE;
    private static volatile String publisher = "";

    private Weather() {
    }

    public static synchronized void register(Provider candidate, int priority) {
        if (priority > providerPriority) {
            provider = candidate;
            providerPriority = priority;
        }
    }

    /**
     * Server: states a level's weather. The library keeps it and sends it to the clients in that level whenever it
     * differs from the last one, and to every player who arrives there. Server thread.
     */
    public static void publish(net.minecraft.server.level.ServerLevel level, String source, State state) {
        State before = SERVER.put(level.dimension(), state);
        publisher = source;
        if (!state.equals(before)) {
            com.solarbiscuit.atmosphera.lib.net.AtmosNet.sendWeather(level, state);
        }
    }

    /** Server: withdraws a level's state (the weather mod stopped); clients fall back when they next join. */
    public static void withdraw(ResourceKey<Level> dimension) {
        SERVER.remove(dimension);
    }

    /** The state a weather mod published for a server level, or null. */
    public static State published(ResourceKey<Level> dimension) {
        return SERVER.get(dimension);
    }

    /** Client: a state arrived from the server (network thread or main thread). */
    public static void acceptSynced(ResourceKey<Level> dimension, State state) {
        SYNCED.put(dimension, state);
    }

    /** Client: forget every synced state (on leaving a server). */
    public static void clearSynced() {
        SYNCED.clear();
    }

    private static State stateFor(Level level) {
        return (level.isClientSide() ? SYNCED : SERVER).get(level.dimension());
    }

    /** The weather of a level now. Any thread. */
    public static void current(Level level, float partialTick, Sample out) {
        State state = stateFor(level);
        if (state != null) {
            // The game's own rain level still times the start and end of a spell (it ramps over a few seconds, and
            // gameplay follows it); the state says how heavy the spell is once it is there.
            float rain = level.getRainLevel(partialTick);
            out.type = rain > 0.02f ? state.type : Type.CLEAR;
            out.intensity = Mth.clamp(state.intensity * rain, 0f, 1f);
            out.windFactor = state.windFactor;
            out.next = state.next;
            out.nextIntensity = state.nextIntensity;
            out.ticksToChange = state.changeAt < 0 ? -1 : Math.max(0L, state.changeAt - level.getGameTime());
            out.modelled = true;
            return;
        }
        Provider p = provider;
        if (p != null && level.isClientSide() && p.current(level, partialTick, out)) {
            out.modelled = true;
            return;
        }
        vanilla(level, partialTick, out);
    }

    /**
     * The default: vanilla's levels. Ordinary rain is steady rain at 0.8; thunder raises it to a full storm. The same
     * weight Stratos gives rain when no weather mod speaks.
     */
    private static void vanilla(Level level, float partialTick, Sample out) {
        float rain = level.getRainLevel(partialTick);
        float thunder = level.getThunderLevel(partialTick);
        out.type = thunder > 0.5f ? Type.STORM : rain > 0.2f ? Type.RAIN : Type.CLEAR;
        out.intensity = Mth.clamp(rain * (0.8f + 0.2f * thunder), 0f, 1f);
        out.windFactor = 1f;
        out.next = Type.CLEAR;
        out.nextIntensity = 0f;
        out.ticksToChange = -1;
        out.modelled = false;
    }

    /**
     * The intensity at a place: the level's intensity, shaped into cells about a kilometre across that drift slowly
     * (heavier here, lighter there), when the state gives a contrast. A pure function of the state, the place and
     * the world clock, so the server and every client agree without sending anything. Any thread.
     */
    public static float intensityAt(Level level, double x, double z, float partialTick, float levelIntensity) {
        State state = stateFor(level);
        if (state == null || state.contrast <= 0f) {
            return levelIntensity;
        }
        double t = com.solarbiscuit.atmosphera.lib.wind.AtmosClock.worldSeconds(level, partialTick);
        // The cells drift along a direction of the spell's own, about 5 blocks a second.
        double angle = (state.seed & 0xFFFF) / 65536.0 * Math.PI * 2.0;
        double u = (x - Math.cos(angle) * 5.0 * t) / 900.0;
        double v = (z - Math.sin(angle) * 5.0 * t) / 900.0;
        float n = 0.65f * noise(u, v, state.seed) + 0.35f * noise(u * 2.3 + 17.0, v * 2.3 - 9.0, state.seed ^ 0x5DEECE66DL);
        return Mth.clamp(levelIntensity * (1f + state.contrast * (n * 2f - 1f)), 0f, 1f);
    }

    /** Value noise, 0..1, one lattice point per unit. */
    private static float noise(double x, double y, long seed) {
        long xi = (long) Math.floor(x);
        long yi = (long) Math.floor(y);
        float fx = (float) (x - xi);
        float fy = (float) (y - yi);
        fx = fx * fx * (3f - 2f * fx);
        fy = fy * fy * (3f - 2f * fy);
        float a = lattice(xi, yi, seed);
        float b = lattice(xi + 1, yi, seed);
        float c = lattice(xi, yi + 1, seed);
        float d = lattice(xi + 1, yi + 1, seed);
        return Mth.lerp(fy, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
    }

    private static float lattice(long x, long y, long seed) {
        long z = x * 0x9E3779B97F4A7C15L ^ y * 0xC2B2AE3D27D4EB4FL ^ seed;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 40) / (float) (1L << 24);
    }

    /** For /atmosphera status. */
    public static String describe(Level level, float partialTick) {
        Sample s = new Sample();
        current(level, partialTick, s);
        String source = stateFor(level) != null ? (level.isClientSide() ? "server" : publisher)
                : s.modelled && provider != null ? provider.name() : "vanilla levels";
        String forecast = s.ticksToChange < 0 ? "no forecast"
                : String.format(java.util.Locale.ROOT, "%s in %d s", s.next, s.ticksToChange / 20);
        return String.format(java.util.Locale.ROOT, "Weather: %s, intensity %.2f, wind x%.2f, %s (from %s)",
                s.type, s.intensity, s.windFactor, forecast, source);
    }
}
