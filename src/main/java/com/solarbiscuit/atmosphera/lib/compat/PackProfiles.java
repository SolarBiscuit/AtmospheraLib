package com.solarbiscuit.atmosphera.lib.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.Reader;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Data-driven knowledge of shaderpacks and mods that provide the same effects as Atmosphera features.
 * <ul>
 *   <li>{@code assets/<ns>/atmosphera/pack_profiles/*.json}: per pack, a name regex, default option values and, per
 *       capability, the condition under which the pack provides it (match kinds: {@code always}, {@code never},
 *       {@code any}, {@code all}, {@code define} + {@code is: "defined"} / {@code gt} / {@code ne}).</li>
 *   <li>{@code assets/atmospheralib/atmosphera/overlap_mods.json} (merged across resource packs): capability name to a
 *       list of mod ids providing it.</li>
 * </ul>
 * Both are resource-pack extendable and reloaded with resources.
 */
public final class PackProfiles {
    private record Profile(String name, Pattern match, Map<String, String> defaults, Map<Capability, JsonElement> capabilities) {
    }

    /** What a pack provides: capability to the reason text, or {@code known == false} for an unmatched pack. */
    public record Result(boolean known, String profileName, Map<Capability, String> provided) {
        public static final Result NONE = new Result(true, null, Map.of());
    }

    private static volatile List<Profile> profiles = List.of();
    private static volatile Map<Capability, List<String>> overlapMods = Map.of();

    private PackProfiles() {
    }

    public static void reload(ResourceManager manager) {
        List<Profile> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : manager.listResources("atmosphera/pack_profiles",
                path -> path.getPath().endsWith(".json")).entrySet()) {
            try (Reader reader = entry.getValue().openAsReader()) {
                loaded.add(parse(JsonParser.parseReader(reader).getAsJsonObject()));
            } catch (Exception e) {
                AtmospheraLib.LOGGER.warn("Bad shaderpack profile {}: {}", entry.getKey(), e.toString());
            }
        }
        Map<Capability, List<String>> mods = new EnumMap<>(Capability.class);
        for (Resource resource : manager.getResourceStack(new ResourceLocation(AtmospheraLib.MOD_ID, "atmosphera/overlap_mods.json"))) {
            try (Reader reader = resource.openAsReader()) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> e : json.entrySet()) {
                    if (e.getKey().startsWith("_")) {
                        continue;
                    }
                    Capability cap = Capability.valueOf(e.getKey());
                    for (JsonElement id : e.getValue().getAsJsonArray()) {
                        mods.computeIfAbsent(cap, k -> new ArrayList<>()).add(id.getAsString());
                    }
                }
            } catch (Exception e) {
                AtmospheraLib.LOGGER.warn("Bad overlap_mods.json in {}: {}", resource.sourcePackId(), e.toString());
            }
        }
        profiles = List.copyOf(loaded);
        overlapMods = mods;
        AtmospheraLib.LOGGER.debug("Loaded {} shaderpack profiles.", loaded.size());
    }

    public static int profileCount() {
        return profiles.size();
    }

    /** Mod ids (from overlap_mods.json) that provide a capability. */
    public static List<String> modsProviding(Capability capability) {
        return overlapMods.getOrDefault(capability, List.of());
    }

    public static Result evaluate(ShaderPackState.Pack pack) {
        if (!pack.active()) {
            return Result.NONE;
        }
        String name = pack.name() != null ? pack.name() : "";
        for (Profile profile : profiles) {
            if (!profile.match().matcher(name).find()) {
                continue;
            }
            Map<String, String> values = new HashMap<>(profile.defaults());
            values.putAll(pack.options());
            Map<Capability, String> provided = new EnumMap<>(Capability.class);
            for (Map.Entry<Capability, JsonElement> e : profile.capabilities().entrySet()) {
                if (test(e.getValue(), values)) {
                    provided.put(e.getKey(), e.getValue().toString());
                }
            }
            return new Result(true, profile.name(), provided);
        }
        return new Result(false, null, Map.of());
    }

    private static Profile parse(JsonObject json) {
        Map<String, String> defaults = new HashMap<>();
        if (json.has("defaults")) {
            for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("defaults").entrySet()) {
                defaults.put(e.getKey(), e.getValue().getAsString());
            }
        }
        Map<Capability, JsonElement> caps = new EnumMap<>(Capability.class);
        for (Map.Entry<String, JsonElement> e : json.getAsJsonObject("capabilities").entrySet()) {
            caps.put(Capability.valueOf(e.getKey()), e.getValue());
        }
        return new Profile(json.get("name").getAsString(), Pattern.compile(json.get("match").getAsString()), defaults, caps);
    }

    private static boolean test(JsonElement element, Map<String, String> values) {
        JsonObject c = element.getAsJsonObject();
        if (c.has("always")) {
            return c.get("always").getAsBoolean();
        }
        if (c.has("never")) {
            return !c.get("never").getAsBoolean();
        }
        if (c.has("any")) {
            for (JsonElement e : c.getAsJsonArray("any")) {
                if (test(e, values)) return true;
            }
            return false;
        }
        if (c.has("all")) {
            JsonArray all = c.getAsJsonArray("all");
            for (JsonElement e : all) {
                if (!test(e, values)) return false;
            }
            return true;
        }
        String value = values.get(c.get("define").getAsString());
        if (c.has("is")) {
            return value != null && !"false".equalsIgnoreCase(value.trim());
        }
        if (c.has("gt")) {
            try {
                return value != null && Double.parseDouble(value.trim()) > c.get("gt").getAsDouble();
            } catch (NumberFormatException e) {
                return false;
            }
        }
        if (c.has("ne")) {
            return value == null || !value.trim().toLowerCase(Locale.ROOT).equals(c.get("ne").getAsString().toLowerCase(Locale.ROOT));
        }
        return false;
    }

    /** Capabilities Atmosphera checks against packs; DEBUG is never provided by anything else. */
    public static boolean isCheckable(Capability capability) {
        return !Set.of(Capability.DEBUG).contains(capability);
    }
}
