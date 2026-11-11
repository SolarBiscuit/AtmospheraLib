package com.solarbiscuit.atmosphera.lib.options;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * Where the Atmosphera family keeps its files: {@code config/Atmosphera/} (0.6.7). Before 0.6.7 the files lived in
 * {@code config/SolConfig/atmosphera/}; {@link #file(String)} moves an old file across the first time it is asked for,
 * so settings carry over. Once the old folder is empty it is removed; {@code config/SolConfig/} itself is never touched
 * (other mods keep their settings there).
 */
public final class AtmosPaths {
    /** The family folder's name inside the game's config folder. */
    public static final String FOLDER = "Atmosphera";

    private AtmosPaths() {
    }

    /** {@code config/Atmosphera/}, created if missing. */
    public static Path configDir() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve(FOLDER);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            AtmospheraLib.LOGGER.warn("Could not create config/{}: {}", FOLDER, e.toString());
        }
        return dir;
    }

    /** The pre-0.6.7 folder, {@code config/SolConfig/atmosphera/}. */
    public static Path legacyDir() {
        return FMLPaths.CONFIGDIR.get().resolve("SolConfig").resolve("atmosphera");
    }

    /** The path of a family file relative to the config folder, as Forge's {@code registerConfig} takes it. */
    public static String relative(String fileName) {
        return FOLDER + "/" + fileName;
    }

    /**
     * {@code config/Atmosphera/<fileName>}, after moving the old copy from {@code config/SolConfig/atmosphera/} when only
     * the old one exists. Safe to call more than once.
     */
    public static Path file(String fileName) {
        Path target = configDir().resolve(fileName);
        migrate(fileName, target);
        return target;
    }

    private static void migrate(String fileName, Path target) {
        Path legacyDir = legacyDir();
        Path legacy = legacyDir.resolve(fileName);
        if (!Files.exists(legacy)) {
            return;
        }
        if (Files.exists(target)) {
            AtmospheraLib.LOGGER.warn("[Atmosphera] Both config/SolConfig/atmosphera/{} and config/{}/{} exist; using the "
                    + "new one. Delete the old file once you have checked it.", fileName, FOLDER, fileName);
            return;
        }
        try {
            Files.move(legacy, target);
            AtmospheraLib.LOGGER.info("[Atmosphera] Moved config/SolConfig/atmosphera/{} to config/{}/{}", fileName, FOLDER,
                    fileName);
        } catch (IOException e) {
            AtmospheraLib.LOGGER.warn("[Atmosphera] Could not move config/SolConfig/atmosphera/{} to config/{}: {}", fileName,
                    FOLDER, e.toString());
            return;
        }
        removeIfEmpty(legacyDir);
    }

    private static void removeIfEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isPresent()) {
                return;
            }
        } catch (IOException e) {
            return;
        }
        try {
            Files.delete(dir);
            AtmospheraLib.LOGGER.info("[Atmosphera] Removed the empty config/SolConfig/atmosphera folder");
        } catch (IOException ignored) {
            // Someone else is writing there; leave it.
        }
    }
}
