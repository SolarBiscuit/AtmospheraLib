package com.solarbiscuit.atmosphera.lib.wind;

import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;

/**
 * The wind service. The provider with the highest priority wins; the library's {@link DefaultWind} (priority 0) is a
 * simple drifting wind so Torrente and Lumis work without Gale. Gale registers its richer field with a higher priority.
 * Safe to call from any thread.
 */
public final class Wind {
    private static volatile WindProvider provider = new DefaultWind();
    private static int providerPriority;

    private Wind() {
    }

    public static synchronized void register(WindProvider candidate, int priority) {
        if (priority > providerPriority) {
            provider = candidate;
            providerPriority = priority;
        }
    }

    public static WindProvider provider() {
        return provider;
    }

    public static void global(Level level, float partialTick, WindVector out) {
        provider.global(level, partialTick, out);
    }

    public static void sample(Level level, double x, double y, double z, float partialTick, WindVector out) {
        provider.sample(level, x, y, z, partialTick, out);
    }

    /** Slowly drifting direction, gusts, stronger in rain and thunder. Driven by the smoothed world clock and the dimension. */
    static final class DefaultWind implements WindProvider {
        @Override
        public String name() {
            return "AtmospheraLib default";
        }

        @Override
        public void global(Level level, float partialTick, WindVector out) {
            double t = AtmosClock.worldSeconds(level, partialTick);
            double base = (level.dimension().location().hashCode() & 0xFFFF) / 65536.0 * Math.PI * 2.0;
            double angle = base + 0.6 * Math.sin(t / 600.0) + 0.3 * Math.sin(t / 233.0 + 1.7);
            float rain = level.getRainLevel(partialTick);
            float thunder = level.getThunderLevel(partialTick);
            float gust = (float) Math.max(0.0, Math.sin(t * 0.37) * Math.sin(t * 0.11 + 2.0));
            float strength = (0.25f + 0.35f * rain + 0.4f * thunder) * (0.8f + 0.4f * gust);
            out.set((float) Math.cos(angle), (float) Math.sin(angle), Mth.clamp(strength, 0f, 1f), gust);
        }

        @Override
        public void sample(Level level, double x, double y, double z, float partialTick, WindVector out) {
            global(level, partialTick, out);
            double t = AtmosClock.worldSeconds(level, partialTick);
            double local = 1.0 + 0.15 * Math.sin(x * 0.031 + t * 0.5) * Math.cos(z * 0.027 - t * 0.4);
            double altitude = Mth.clamp((y - level.getSeaLevel()) / 128.0, 0.0, 0.5);
            out.strength = Mth.clamp((float) (out.strength * local * (1.0 + altitude)), 0f, 1f);
        }
    }
}
