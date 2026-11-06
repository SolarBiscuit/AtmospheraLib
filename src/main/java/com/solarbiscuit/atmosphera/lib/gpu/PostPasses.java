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
     * What a pass gets: the matrices of the frame being drawn. One instance per stage, refilled every frame (0.3.22:
     * no allocation per frame), so a pass must not keep it, or its matrices, past {@link PostPass#render}.
     * The accessors are the same as the record this was before 0.3.22.
     */
    public static final class Context {
        private PoseStack poseStack;
        private final Matrix4f projection = new Matrix4f();
        private float partialTick;
        private final Matrix4f invViewProjection = new Matrix4f();
        private net.minecraft.world.phys.Vec3 camera;

        /** Refills this context; computes the inverse view-projection in place. Render thread. */
        public Context set(PoseStack poseStack, Matrix4f projection, float partialTick, net.minecraft.world.phys.Vec3 camera) {
            this.poseStack = poseStack;
            this.projection.set(projection);
            this.partialTick = partialTick;
            this.invViewProjection.set(projection).mul(poseStack.last().pose()).invert();
            this.camera = camera;
            return this;
        }

        public PoseStack poseStack() {
            return poseStack;
        }

        public Matrix4f projection() {
            return projection;
        }

        public float partialTick() {
            return partialTick;
        }

        /**
         * Inverse of {@code projection * view}: clip space to camera-relative world space (world position = camera +
         * result), for reconstructing positions from depth (0.4.0).
         */
        public Matrix4f invViewProjection() {
            return invViewProjection;
        }

        /** Camera position in world space (0.4.0). */
        public net.minecraft.world.phys.Vec3 camera() {
            return camera;
        }
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

    /** Whether any pass of this stage belongs to an active feature (skip building the context otherwise). */
    public static boolean anyActive(Stage stage) {
        for (Entry entry : frozen) {
            if (entry.stage() == stage && entry.feature().isActive()) {
                return true;
            }
        }
        return false;
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
