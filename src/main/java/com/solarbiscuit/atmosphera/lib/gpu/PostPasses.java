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
        /**
         * Before translucent terrain, right after the scene copies (0.4.0): opaque and cutout terrain and entities are
         * drawn, water and glass are not. For data the translucent pass samples (Torrente's planar reflections).
         * The scene copies are valid here when an active feature declares them. Runs inside Xenon's terrain
         * rendering, so a pass must restore every GL state it changes ({@link GlStateSnapshot}).
         */
        BEFORE_TRANSLUCENT,
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
        // Temporal (0.6.0): last frame's view-projection and camera, for reprojecting history.
        private final Matrix4f viewProjection = new Matrix4f();
        private final Matrix4f prevViewProjection = new Matrix4f();
        private final org.joml.Vector3f cameraDelta = new org.joml.Vector3f();
        private net.minecraft.world.phys.Vec3 prevCamera;
        private long frame = -1;
        private long prevFrame = -2;
        private Object prevLevel;
        private boolean continuous;
        /** For stages that get bare matrices (BEFORE_TRANSLUCENT): a stack holding the model-view. */
        private final PoseStack ownPose = new PoseStack();
        private net.minecraft.world.phys.Vec3 camera;

        /** Refills this context; computes the inverse view-projection in place. Render thread. */
        public Context set(PoseStack poseStack, Matrix4f projection, float partialTick, net.minecraft.world.phys.Vec3 camera) {
            this.poseStack = poseStack;
            this.projection.set(projection);
            this.partialTick = partialTick;
            advance(poseStack.last().pose(), camera);
            return this;
        }

        /** Keeps last frame's matrix and camera, then computes this frame's (no allocation). */
        private void advance(org.joml.Matrix4fc view, net.minecraft.world.phys.Vec3 camera) {
            prevViewProjection.set(viewProjection);
            prevCamera = this.camera;
            prevFrame = frame;
            frame = com.solarbiscuit.atmosphera.lib.client.AtmosClient.frame();
            viewProjection.set(projection).mul(view);
            invViewProjection.set(viewProjection).invert();
            this.camera = camera;
            Object level = net.minecraft.client.Minecraft.getInstance().level;
            if (prevCamera != null) {
                cameraDelta.set((float) (camera.x - prevCamera.x), (float) (camera.y - prevCamera.y), (float) (camera.z - prevCamera.z));
            } else {
                cameraDelta.set(0f);
            }
            // History is usable when this stage also ran last frame, in the same level, and the camera did not jump.
            continuous = prevFrame == frame - 1 && level == prevLevel && cameraDelta.lengthSquared() < 32f * 32f;
            prevLevel = level;
        }

        /** Refills this context from bare matrices (the terrain pass's): model-view in an own pose stack. */
        public Context set(org.joml.Matrix4fc projection, org.joml.Matrix4fc modelView, float partialTick,
                           net.minecraft.world.phys.Vec3 camera) {
            ownPose.last().pose().set(modelView);
            ownPose.last().normal().set(modelView).invert().transpose();
            this.poseStack = ownPose;
            this.projection.set(projection);
            this.partialTick = partialTick;
            advance(modelView, camera);
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

        /** {@code projection * view} of this frame (camera-relative world to clip), 0.6.0. */
        public Matrix4f viewProjection() {
            return viewProjection;
        }

        /** Last frame's {@code projection * view} (relative to last frame's camera), 0.6.0. */
        public Matrix4f prevViewProjection() {
            return prevViewProjection;
        }

        /** Camera now minus camera last frame, in blocks (0.6.0). */
        public org.joml.Vector3f cameraDelta() {
            return cameraDelta;
        }

        /**
         * Whether last frame's data still describes this view (0.6.0): this stage ran last frame, in the same level,
         * and the camera moved less than 32 blocks. History buffers must be reset when false.
         */
        public boolean continuous() {
            return continuous;
        }

        /** Sets the {@link #REPROJECT_GLSL} uniforms of the bound program (locations from {@link #locateReproject}). */
        public void bindReproject(int[] locations, org.lwjgl.system.MemoryStack stack) {
            if (locations[0] >= 0) {
                org.lwjgl.opengl.GL20C.glUniformMatrix4fv(locations[0], false, prevViewProjection.get(stack.mallocFloat(16)));
            }
            if (locations[1] >= 0) {
                org.lwjgl.opengl.GL20C.glUniform4f(locations[1], cameraDelta.x, cameraDelta.y, cameraDelta.z, continuous ? 1f : 0f);
            }
        }
    }

    /**
     * Reprojection for temporal passes (0.6.0; camera motion only, Minecraft has no motion vectors). Declare in a pass's
     * fragment shader and set with {@link Context#bindReproject}. {@code atmos_reproject(rel, sky)}: the screen uv a
     * camera-relative point had last frame; for sky pixels pass the view direction and {@code sky = 1} (no
     * translation). {@code atmos_CameraDelta.w} is 1 while history is usable ({@link Context#continuous}).
     */
    public static final String REPROJECT_GLSL = """
            uniform mat4 atmos_PrevViewProj;
            uniform vec4 atmos_CameraDelta;
            vec2 atmos_reproject(vec3 atmosRel, float atmosSky) {
                vec4 atmosClip = atmos_PrevViewProj * vec4(atmosRel + atmos_CameraDelta.xyz * (1.0 - atmosSky), 1.0 - atmosSky);
                return atmosClip.xy / atmosClip.w * 0.5 + 0.5;
            }
            """;

    /** Uniform locations for {@link #REPROJECT_GLSL} in a program (-1 where unused). */
    public static int[] locateReproject(int program) {
        return new int[] {org.lwjgl.opengl.GL20C.glGetUniformLocation(program, "atmos_PrevViewProj"),
                org.lwjgl.opengl.GL20C.glGetUniformLocation(program, "atmos_CameraDelta")};
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
