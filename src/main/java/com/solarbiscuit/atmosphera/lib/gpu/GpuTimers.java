package com.solarbiscuit.atmosphera.lib.gpu;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL33C;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GPU time of Atmosphera's own passes (scene copies, post passes), measured with GL timestamp queries
 * (core since GL 3.3; pairs of timestamps instead of TIME_ELAPSED so sections may nest). Results are read three frames
 * later so the CPU never waits. Every 10 s the per-frame averages go to {@code logs/atmospheralib-gpu.csv}
 * ({@code time,section,frames,gpu_ms_avg}) and to {@code /atmosphera status}. Off unless enabled in the config.
 * Render thread only.
 */
public final class GpuTimers {
    private static final int RING = 4;
    private static final long WINDOW_NANOS = 10_000_000_000L;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final Map<String, Section> SECTIONS = new LinkedHashMap<>();
    private static boolean enabled;
    private static long frame;
    private static long windowStart;

    private GpuTimers() {
    }

    public static void setEnabled(boolean value) {
        if (enabled && !value) {
            deleteAll();
        }
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void begin(String section) {
        if (enabled) {
            SECTIONS.computeIfAbsent(section, Section::new).mark(0);
        }
    }

    public static void end(String section) {
        if (enabled) {
            Section s = SECTIONS.get(section);
            if (s != null) {
                s.mark(1);
            }
        }
    }

    /** Called once at the start of every frame: collects finished queries and writes the CSV window. */
    public static void newFrame() {
        if (!enabled) {
            return;
        }
        frame++;
        for (Section s : SECTIONS.values()) {
            s.collect();
        }
        long now = System.nanoTime();
        if (windowStart == 0) {
            windowStart = now;
        } else if (now - windowStart >= WINDOW_NANOS) {
            windowStart = now;
            writeWindow();
        }
    }

    /** Last window's average GPU ms per frame, per section. */
    public static Map<String, Double> lastAverages() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Section s : SECTIONS.values()) {
            out.put(s.name, s.lastAverage);
        }
        return out;
    }

    public static void deleteAll() {
        for (Section s : SECTIONS.values()) {
            s.delete();
        }
        SECTIONS.clear();
    }

    private static void writeWindow() {
        StringBuilder lines = new StringBuilder();
        String time = LocalDateTime.now().format(TIME);
        for (Section s : SECTIONS.values()) {
            s.lastAverage = s.frames == 0 ? 0.0 : s.totalNanos / 1.0e6 / s.frames;
            lines.append(time).append(',').append(s.name).append(',').append(s.frames).append(',')
                    .append(String.format(java.util.Locale.ROOT, "%.4f", s.lastAverage)).append('\n');
            s.frames = 0;
            s.totalNanos = 0;
        }
        if (lines.isEmpty()) {
            return;
        }
        // The file write happens on one background thread (0.3.22), not on the render thread.
        String rows = lines.toString();
        WRITER.execute(() -> append(rows));
    }

    /** One daemon thread for the CSV, so rows keep their order. */
    private static final java.util.concurrent.ExecutorService WRITER =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "atmospheralib-gpu-csv");
                t.setDaemon(true);
                return t;
            });

    private static void append(String rows) {
        try {
            Path file = FMLPaths.GAMEDIR.get().resolve("logs").resolve("atmospheralib-gpu.csv");
            if (!Files.exists(file)) {
                Files.writeString(file, "time,section,frames,gpu_ms_avg\n");
            }
            Files.writeString(file, rows, StandardOpenOption.APPEND);
        } catch (IOException e) {
            AtmospheraLib.LOGGER.debug("Could not write the GPU timer CSV: {}", e.toString());
        }
    }

    private static final class Section {
        private final String name;
        private final int[] queries = new int[RING * 2];
        private final long[] issuedFrame = new long[RING];
        private final boolean[] ended = new boolean[RING];
        private long frames;
        private long totalNanos;
        private double lastAverage;

        Section(String name) {
            this.name = name;
            for (int i = 0; i < queries.length; i++) {
                queries[i] = GpuResources.query("gpu timer " + name);
            }
            java.util.Arrays.fill(issuedFrame, -1);
        }

        void mark(int which) {
            int slot = (int) (frame % RING);
            if (which == 0) {
                if (issuedFrame[slot] == frame) {
                    return; // one measurement per frame per section
                }
                issuedFrame[slot] = frame;
                ended[slot] = false;
            } else if (issuedFrame[slot] != frame || ended[slot]) {
                return;
            } else {
                ended[slot] = true;
            }
            GL33C.glQueryCounter(queries[slot * 2 + which], GL33C.GL_TIMESTAMP);
        }

        void collect() {
            for (int slot = 0; slot < RING; slot++) {
                long issued = issuedFrame[slot];
                if (issued < 0 || frame - issued < RING - 1) {
                    continue;
                }
                if (!ended[slot]) {
                    issuedFrame[slot] = -1; // begin without end: never read a query that was not issued
                    continue;
                }
                int start = queries[slot * 2];
                int end = queries[slot * 2 + 1];
                if (GL15C.glGetQueryObjecti(end, GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                    continue;
                }
                long t0 = GL33C.glGetQueryObjecti64(start, GL15C.GL_QUERY_RESULT);
                long t1 = GL33C.glGetQueryObjecti64(end, GL15C.GL_QUERY_RESULT);
                if (t1 > t0) {
                    totalNanos += t1 - t0;
                    frames++;
                }
                issuedFrame[slot] = -1;
            }
        }

        void delete() {
            for (int query : queries) {
                GpuResources.deleteQuery(query);
            }
        }
    }
}
