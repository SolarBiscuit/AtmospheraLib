package com.solarbiscuit.atmosphera.lib.options;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/**
 * Which settings groups are open (0.10.0). Groups start closed; what a player opens is remembered in
 * {@code config/Atmosphera/atmospheralib-ui.json}. Shared by the Video Settings pages and the library's own screen.
 * A group's id is {@code <modid>/<page>/<group>}.
 */
public final class GroupState {
    private static final String FILE = "atmospheralib-ui.json";
    private static Set<String> open;
    /** Embeddium option objects of the Atmosphera pages and the group each one sits in (headers are not listed). */
    private static final Map<Object, String> OPTION_GROUPS = Collections.synchronizedMap(new WeakHashMap<>());
    /** Whether the options screen's search box holds text (then every matching option shows, open group or not). */
    static volatile BooleanSupplier searching = () -> false;

    private GroupState() {
    }

    public static String id(String modId, String pageKey, String groupKey) {
        return modId + "/" + pageKey + "/" + groupKey;
    }

    public static synchronized boolean isOpen(String groupId) {
        return load().contains(groupId);
    }

    /** Opens or closes a group and saves the state. */
    public static synchronized void toggle(String groupId) {
        Set<String> set = load();
        if (!set.remove(groupId)) {
            set.add(groupId);
        }
        save();
    }

    static void bind(Object option, String groupId) {
        OPTION_GROUPS.put(option, groupId);
    }

    /** For the Video Settings frame: false for an option inside a closed Atmosphera group (unless searching). */
    public static boolean visible(Object option) {
        String group = OPTION_GROUPS.get(option);
        if (group == null) {
            return true;
        }
        try {
            if (searching.getAsBoolean()) {
                return true;
            }
        } catch (RuntimeException e) {
            // a screen this code does not know: show everything
            return true;
        }
        return isOpen(group);
    }

    private static Path path() {
        return AtmosPaths.configDir().resolve(FILE);
    }

    private static Set<String> load() {
        if (open != null) {
            return open;
        }
        open = new LinkedHashSet<>();
        Path file = path();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = new Gson().fromJson(reader, JsonObject.class);
                if (json != null && json.get("open") instanceof JsonArray array) {
                    for (JsonElement element : array) {
                        open.add(element.getAsString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                AtmospheraLib.LOGGER.warn("Could not read config/{}/{}: {}", AtmosPaths.FOLDER, FILE, e.toString());
            }
        }
        return open;
    }

    private static void save() {
        JsonObject json = new JsonObject();
        JsonArray array = new JsonArray();
        for (String id : open) {
            array.add(id);
        }
        json.add("open", array);
        try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
            new Gson().toJson(json, writer);
        } catch (IOException e) {
            AtmospheraLib.LOGGER.warn("Could not write config/{}/{}: {}", AtmosPaths.FOLDER, FILE, e.toString());
        }
    }
}
