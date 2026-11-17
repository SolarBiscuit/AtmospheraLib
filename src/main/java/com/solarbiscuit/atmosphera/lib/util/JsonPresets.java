package com.solarbiscuit.atmosphera.lib.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import java.io.Reader;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Style presets as JSON files, one per value of an enum (0.11.0 "Shared"): {@code assets/<namespace>/<folder>/<value>.json},
 * read at each resource reload, so a resource pack replaces a preset by shipping the same path. Plus lenient readers
 * for the keys of such a file: a key that is missing or of the wrong kind keeps the fallback.
 */
public final class JsonPresets {
    private JsonPresets() {
    }

    /**
     * Reads one file per value of {@code type} that {@code include} accepts ({@code <folder>/<value in lower case>.json}).
     * A missing or unreadable file is logged ("{@code <what> <id> is missing; using the fallback style.}") and left out
     * of the result, so the caller's fallback applies to it.
     */
    public static <E extends Enum<E>, T> Map<E, T> load(ResourceManager manager, String namespace, String folder,
                                                       Class<E> type, Predicate<E> include,
                                                       Function<JsonObject, T> parse, Logger logger, String what) {
        Map<E, T> loaded = new EnumMap<>(type);
        for (E value : type.getEnumConstants()) {
            if (!include.test(value)) {
                continue;
            }
            ResourceLocation id = new ResourceLocation(namespace,
                    folder + "/" + value.name().toLowerCase(Locale.ROOT) + ".json");
            Optional<Resource> resource = manager.getResource(id);
            if (resource.isEmpty()) {
                logger.warn("{} {} is missing; using the fallback style.", what, id);
                continue;
            }
            try (Reader reader = resource.get().openAsReader()) {
                loaded.put(value, parse.apply(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception e) {
                logger.warn("{} {} could not be read ({}); using the fallback style.", what, id, e.toString());
            }
        }
        return loaded;
    }

    /** A number, or {@code fallback} when the key is missing or not a number. */
    public static float f(JsonObject json, String key, float fallback) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        // 0.11.2: a non-finite number (the lenient parser takes NaN, Infinity, and 1e999 overflows) is the fallback.
        float v = e.getAsFloat();
        return Float.isFinite(v) ? v : fallback;
    }

    /** A number clamped to {@code [min, max]}, or {@code fallback} when the key is missing or not a number. */
    public static float f(JsonObject json, String key, float fallback, float min, float max) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        float v = e.getAsFloat();
        return Float.isFinite(v) ? Math.max(min, Math.min(max, v)) : fallback;
    }

    /** A colour string ({@link Colors#parseRgb}) as {r, g, b} of 0..1, or {@code fallback}. */
    public static float[] color(JsonObject json, String key, float[] fallback) {
        JsonElement e = json.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        int rgb = Colors.parseRgb(e.getAsString());
        return rgb < 0 ? fallback : Colors.toFloats(rgb);
    }
}
