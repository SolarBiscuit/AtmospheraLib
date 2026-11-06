package com.solarbiscuit.atmosphera.lib.compat;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

/**
 * Oculus/Iris state through its public API, by reflection only (Atmosphera never mixes into Oculus):
 * whether a shaderpack is in use, its name, and the option values the user set for it ({@code shaderpacks/<pack>.txt}).
 */
public final class ShaderPackState {
    public record Pack(boolean active, String name, Map<String, String> options) {
        static final Pack NONE = new Pack(false, null, Map.of());
    }

    private static boolean resolved;
    private static Object irisApi;
    private static Method inUse;
    private static Method packName;
    private static Method packsDirectory;

    private ShaderPackState() {
    }

    /** Current pack; cheap enough to call every second. Render thread. */
    public static Pack probe() {
        if (!AtmosCompat.isLoaded("oculus")) {
            return Pack.NONE;
        }
        resolve();
        if (irisApi == null) {
            return Pack.NONE;
        }
        try {
            if (!(boolean) inUse.invoke(irisApi)) {
                return Pack.NONE;
            }
            String name = packName != null ? (String) packName.invoke(null) : null;
            return new Pack(true, name, readOptions(name));
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Unknown state: treat as "a pack is active", which keeps Atmosphera's terrain hooks safely off.
            return new Pack(true, "unknown", Map.of());
        }
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
            irisApi = api.getMethod("getInstance").invoke(null);
            inUse = api.getMethod("isShaderPackInUse");
        } catch (ReflectiveOperationException | LinkageError e) {
            AtmospheraLib.LOGGER.warn("Oculus is installed but its API was not found ({}).", e.toString());
            return;
        }
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            packName = iris.getMethod("getCurrentPackName");
            packsDirectory = iris.getMethod("getShaderpacksDirectory");
        } catch (ReflectiveOperationException | LinkageError e) {
            AtmospheraLib.LOGGER.debug("Oculus pack name not available: {}", e.toString());
        }
    }

    // Options of the last read pack, kept until the pack or its file's modification time changes (0.3.22: the
    // probe runs every second while a pack is active and used to re-read and re-parse the file each time).
    private static String cachedName;
    private static long cachedModified = Long.MIN_VALUE;
    private static Map<String, String> cachedOptions = Map.of();

    private static Map<String, String> readOptions(String name) {
        if (name == null || packsDirectory == null) {
            return Map.of();
        }
        try {
            Path file = ((Path) packsDirectory.invoke(null)).resolve(name + ".txt");
            if (!Files.exists(file)) {
                return Map.of();
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (name.equals(cachedName) && modified == cachedModified) {
                return cachedOptions;
            }
            Properties props = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) {
                props.load(reader);
            }
            Map<String, String> options = props.stringPropertyNames().stream()
                    .collect(Collectors.toUnmodifiableMap(k -> k, props::getProperty));
            cachedName = name;
            cachedModified = modified;
            cachedOptions = options;
            return options;
        } catch (IOException | ReflectiveOperationException | RuntimeException e) {
            return Map.of();
        }
    }
}
