package com.solarbiscuit.atmosphera.lib.options;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One-time rewrites of a saved setting when its meaning changes between versions (0.11.0 "Shared"), run in the mod's
 * constructor, before Forge reads the file. The new version adds a marker key to its spec; a file without that marker
 * was written before the change, and its value is converted once (Forge then writes the marker).
 */
public final class ConfigMigration {
    /** What a migration changed: the old and the new value text. */
    public record Change(String before, String after) {
    }

    private ConfigMigration() {
    }

    /**
     * Converts one value in {@code config/Atmosphera/<fileName>}.
     *
     * @param marker  a key the new version writes; present means the file is already converted
     * @param section a TOML table name to search in (its lines up to the next table), or null for the whole file
     * @param value   a pattern whose group 2 is the value text (group 1 typically the key and the equals sign)
     * @param convert the new value text, or null to leave the file alone (for example an unchanged default)
     * @return the change made, or null when nothing was changed (no file, already converted, no match, or an error,
     *         which is logged as a warning naming {@code what})
     */
    public static Change rewrite(String fileName, String marker, String section, Pattern value,
                                 UnaryOperator<String> convert, Logger logger, String what) {
        Path file = AtmosPaths.file(fileName);
        try {
            if (!Files.exists(file)) {
                return null;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (text.contains(marker)) {
                return null;
            }
            int from = 0;
            int to = text.length();
            if (section != null) {
                Matcher head = Pattern.compile("(?m)^\\s*\\[" + Pattern.quote(section) + "\\]\\s*$").matcher(text);
                if (!head.find()) {
                    return null;
                }
                from = head.end();
                Matcher next = Pattern.compile("(?m)^\\s*\\[").matcher(text);
                to = next.find(from) ? next.start() : text.length();
            }
            Matcher m = value.matcher(text).region(from, to);
            if (!m.find()) {
                return null;
            }
            String before = m.group(2);
            String after = convert.apply(before);
            if (after == null) {
                return null;
            }
            // 0.11.1: the marker goes in with the value, so a game stopped before Forge saves the file cannot convert
            // it a second time on the next launch.
            String converted = text.substring(0, m.start(2)) + after + text.substring(m.end(2));
            Files.writeString(file, converted + System.lineSeparator() + "# " + marker + ": converted by Atmosphera"
                    + System.lineSeparator(), StandardCharsets.UTF_8);
            return new Change(before, after);
        } catch (IOException | RuntimeException e) {
            logger.warn("[Atmosphera] Could not convert the {} in {}: {}", what, file, e.toString());
            return null;
        }
    }
}
