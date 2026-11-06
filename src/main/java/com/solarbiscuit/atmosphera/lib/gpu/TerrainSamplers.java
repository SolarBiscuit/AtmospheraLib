package com.solarbiscuit.atmosphera.lib.gpu;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Textures a feature samples in the terrain programs, for example Lumis's sun-shadow horizon map. The feature declares
 * the sampler in its own snippet ({@code uniform sampler2D lumis_Horizon;}); the library sets the sampler unit and
 * binds the texture each time a chunk program is set up, while the feature is active.
 * <p>
 * Units: 12 and 13 are the scene copies ({@link SceneTextures}). {@link #register} (the API before 0.4.0) gives 14 and
 * 15 in registration order, as before. {@link #request} (0.4.0) gives units from {@link #FIRST_REQUESTED} up, assigned
 * when the layout is frozen, by priority, within what the driver reports:
 * <ul>
 *   <li>a unit must be below {@code GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS} (GL 3.3 guarantees 48; AMD, NVIDIA and Intel
 *       report 96-192);</li>
 *   <li>one terrain program may use at most {@code GL_MAX_TEXTURE_IMAGE_UNITS} fragment samplers (at least 16) and
 *       {@code GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS} vertex samplers (at least 16). Xenon uses 1 of each, the scene
 *       copies 2 fragment.</li>
 * </ul>
 * A request that does not fit turns its feature off with the reason "no free texture unit". All units lie outside
 * the 12 that {@code GlStateManager} tracks, so binding them never confuses vanilla's texture cache, and Xenon uses
 * units 0-2. Register during mod construction.
 */
public final class TerrainSamplers {
    public static final int FIRST_UNIT = 14;
    /** Legacy {@link #register} slots: units 14 and 15. */
    public static final int MAX = 2;
    /** First unit for {@link #request}. */
    public static final int FIRST_REQUESTED = 16;
    /** Samplers Xenon's own programs use per stage (block atlas in the fragment, lightmap in the vertex stage). */
    private static final int XENON_FRAGMENT = 1;
    private static final int XENON_VERTEX = 1;

    public enum Stage {
        FRAGMENT, VERTEX
    }

    /** One terrain sampler. {@link #unit()} is known after the layout is frozen (client setup). */
    public static final class Sampler {
        private final AtmosFeature feature;
        private final String name;
        private final int target;
        private final Stage stage;
        private final int priority;
        private final IntSupplier texture;
        private final boolean legacy;
        private int unit = -1;
        private String problem;

        private Sampler(AtmosFeature feature, String name, int target, Stage stage, int priority, IntSupplier texture,
                        boolean legacy, int unit) {
            this.feature = feature;
            this.name = name;
            this.target = target;
            this.stage = stage;
            this.priority = priority;
            this.texture = texture;
            this.legacy = legacy;
            this.unit = unit;
        }

        /** The texture unit, or -1 before the freeze and when no unit was free. */
        public int unit() {
            return unit;
        }

        public String glslName() {
            return name;
        }

        public AtmosFeature feature() {
            return feature;
        }
    }

    private static final List<Sampler> ENTRIES = new ArrayList<>();
    private static List<Sampler> frozen = List.of();
    private static boolean closed;
    private static boolean resolved;
    private static int maxCombined;
    private static int maxFragment;
    private static int maxVertex;

    private TerrainSamplers() {
    }

    /**
     * @param glslName the {@code uniform sampler2D} the feature's fragment snippet declares
     * @param texture  current texture id (0 = nothing bound); asked on the render thread per program setup
     * @return the unit the texture is bound to
     */
    public static int register(AtmosFeature feature, String glslName, IntSupplier texture) {
        return register(feature, glslName, GL11C.GL_TEXTURE_2D, texture);
    }

    /**
     * @param target {@code GL_TEXTURE_2D} or {@code GL_TEXTURE_3D} (0.3.19; declare {@code uniform sampler3D} for 3D)
     */
    public static synchronized int register(AtmosFeature feature, String glslName, int target, IntSupplier texture) {
        long legacy = ENTRIES.stream().filter(e -> e.legacy).count();
        if (closed || legacy >= MAX) {
            throw new IllegalStateException("Atmosphera terrain samplers: register during mod construction, at most " + MAX
                    + " with register(); use request() for more");
        }
        int unit = FIRST_UNIT + (int) legacy;
        ENTRIES.add(new Sampler(feature, glslName, target, Stage.FRAGMENT, Integer.MAX_VALUE, texture, true, unit));
        return unit;
    }

    /**
     * Requests a terrain sampler (0.4.0). The unit is assigned at the freeze; read it with {@link Sampler#unit()}
     * when building GLSL that needs an explicit binding (the LOD shaders). Requests with a higher {@code priority}
     * get units first.
     *
     * @param target {@code GL_TEXTURE_2D}, {@code GL_TEXTURE_3D}, ...
     * @param stage  the stage whose snippet declares the sampler (counts against that stage's limit)
     */
    public static synchronized Sampler request(AtmosFeature feature, String glslName, int target, Stage stage,
                                               int priority, IntSupplier texture) {
        if (closed) {
            throw new IllegalStateException("Atmosphera terrain samplers: request during mod construction");
        }
        Sampler sampler = new Sampler(feature, glslName, target, stage, priority, texture, false, -1);
        ENTRIES.add(sampler);
        return sampler;
    }

    /** Fixes the layout (client setup). Units are assigned on the render thread once the GL context is there. */
    public static synchronized void freeze() {
        closed = true;
        resolve();
    }

    /**
     * Assigns the requested units if not done yet: needs the GL context on this thread (called at freeze, per frame
     * and before program setup). Features are judged again afterwards, so a request that did not fit turns its
     * feature off.
     */
    public static synchronized void resolve() {
        if (resolved || !closed) {
            return;
        }
        try {
            org.lwjgl.opengl.GL.getCapabilities();
        } catch (IllegalStateException noContextOnThisThread) {
            return;
        }
        resolved = true;
        maxCombined = GL11C.glGetInteger(GL20C.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
        maxFragment = GL11C.glGetInteger(GL20C.GL_MAX_TEXTURE_IMAGE_UNITS);
        maxVertex = GL11C.glGetInteger(GL20C.GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS);
        int cap = com.solarbiscuit.atmosphera.lib.client.DevFlags.MAX_UNITS;
        if (cap > 0) {
            maxCombined = Math.min(maxCombined, cap);
            maxFragment = Math.min(maxFragment, cap);
            maxVertex = Math.min(maxVertex, cap);
        }
        int fragment = XENON_FRAGMENT + 2;
        int vertex = XENON_VERTEX;
        for (Sampler s : ENTRIES) {
            if (s.legacy) {
                fragment++;
            }
        }
        List<Sampler> requested = new ArrayList<>();
        for (Sampler s : ENTRIES) {
            if (!s.legacy) {
                requested.add(s);
            }
        }
        requested.sort(Comparator.comparingInt((Sampler s) -> -s.priority));
        int next = FIRST_REQUESTED;
        for (Sampler s : requested) {
            boolean stageRoom = s.stage == Stage.FRAGMENT ? fragment < maxFragment : vertex < maxVertex;
            if (next >= maxCombined || !stageRoom) {
                s.problem = "no free texture unit (" + s.name + ": driver allows " + maxCombined + " units, "
                        + maxFragment + " fragment / " + maxVertex + " vertex samplers per program)";
                AtmospheraLib.LOGGER.warn("Terrain sampler {} of {}: {}", s.name, s.feature.id(), s.problem);
                continue;
            }
            s.unit = next++;
            if (s.stage == Stage.FRAGMENT) fragment++; else vertex++;
        }
        List<Sampler> assigned = new ArrayList<>();
        for (Sampler s : ENTRIES) {
            if (s.unit >= 0) {
                assigned.add(s);
            }
        }
        frozen = List.copyOf(assigned);
        if (!ENTRIES.isEmpty()) {
            AtmospheraLib.LOGGER.info("Terrain samplers: {}", describe());
        }
        com.solarbiscuit.atmosphera.lib.state.AtmosState.requestRefresh();
    }

    /** Why this feature cannot run because a sampler it requested got no unit, or null. */
    public static synchronized String unitProblem(AtmosFeature feature) {
        for (Sampler s : ENTRIES) {
            if (s.feature == feature && s.problem != null) {
                return s.problem;
            }
        }
        return null;
    }

    /** "lumis_Horizon 14, lumis_Hue 15 (of 192 units; 32 fragment / 32 vertex per program)". */
    public static synchronized String describe() {
        StringBuilder out = new StringBuilder();
        for (Sampler s : ENTRIES) {
            out.append(out.isEmpty() ? "" : ", ").append(s.name).append(' ')
                    .append(s.unit >= 0 ? String.valueOf(s.unit) : "none")
                    .append(s.stage == Stage.VERTEX ? " (vertex)" : "");
        }
        return (out.isEmpty() ? "none" : out) + " (of " + maxCombined + " units; " + maxFragment + " fragment / "
                + maxVertex + " vertex per program)";
    }

    /** Sampler locations of one program (-1 where the program does not use it). Render thread. */
    public static int[] locate(int program) {
        resolve();
        List<Sampler> entries = frozen;
        int[] locations = new int[entries.size()];
        for (int i = 0; i < locations.length; i++) {
            locations[i] = GL20C.glGetUniformLocation(program, entries.get(i).name);
        }
        return locations;
    }

    /** With the program bound: points its samplers at their units and binds the textures. Render thread. */
    public static void bind(int[] locations) {
        if (locations == null) {
            return;
        }
        List<Sampler> entries = frozen;
        for (int i = 0; i < locations.length && i < entries.size(); i++) {
            if (locations[i] < 0) {
                continue;
            }
            Sampler entry = entries.get(i);
            GL20C.glUniform1i(locations[i], entry.unit);
            AtmosGl.bindTextureUnit(entry.unit, entry.target, entry.feature.isActive() ? entry.texture.getAsInt() : 0);
        }
    }
}
