package com.solarbiscuit.atmosphera.lib.gpu;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fullscreen passes the feature mods run after the world (for example Lumis godrays). Each pass belongs to a feature
 * and runs only while that feature is active; it is timed under its own name in the GPU timer CSV.
 * Passes must restore any GL state they change (program, blend, depth, bound textures on units 0-11 through
 * {@code RenderSystem}). Register during mod construction; called on the render thread.
 */
public final class PostPasses {
    public enum Stage {
        /** After translucent terrain (water, glass), before particles and weather. */
        AFTER_TRANSLUCENT,
        /** After particles and weather, before the hand and the GUI. */
        AFTER_WEATHER
    }

    @FunctionalInterface
    public interface PostPass {
        void render(Context context);
    }

    /**
     * What a pass gets: the matrices of the frame being drawn.
     *
     * @param invViewProjection inverse of {@code projection * view}: clip space to camera-relative world space
     *                          (world position = camera + result), for reconstructing positions from depth (0.4.0)
     * @param camera            camera position in world space (0.4.0)
     */
    public record Context(PoseStack poseStack, Matrix4f projection, float partialTick, Matrix4f invViewProjection,
                          net.minecraft.world.phys.Vec3 camera) {
    }

    private record Entry(AtmosFeature feature, Stage stage, int order, String name, PostPass pass) {
    }

    private static final List<Entry> PASSES = new ArrayList<>();
    private static List<Entry> frozen = List.of();
    private static final java.util.Set<String> FAILED = new java.util.HashSet<>();

    private PostPasses() {
    }

    public static synchronized void register(AtmosFeature feature, Stage stage, int order, String name, PostPass pass) {
        PASSES.add(new Entry(feature, stage, order, name, pass));
    }

    public static synchronized void freeze() {
        List<Entry> sorted = new ArrayList<>(PASSES);
        sorted.sort(Comparator.comparingInt(Entry::order));
        frozen = List.copyOf(sorted);
    }

    public static int count() {
        return frozen.size();
    }

    public static void run(Stage stage, Context context) {
        for (Entry entry : frozen) {
            if (entry.stage() != stage || !entry.feature().isActive()) {
                continue;
            }
            GpuTimers.begin(entry.name());
            try {
                entry.pass().render(context);
            } catch (RuntimeException e) {
                if (FAILED.add(entry.name())) {
                    AtmospheraLib.LOGGER.error("Post pass {} of {} failed (logged once)", entry.name(), entry.feature().id(), e);
                }
            }
            GpuTimers.end(entry.name());
        }
    }
}
