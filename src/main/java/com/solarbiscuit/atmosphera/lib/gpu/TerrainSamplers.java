package com.solarbiscuit.atmosphera.lib.gpu;

import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Textures a feature samples in the terrain programs, for example Lumis's sun-shadow horizon map. The feature declares
 * the sampler in its own snippet ({@code uniform sampler2D lumis_Horizon;}); the library sets the sampler unit and
 * binds the texture each time a chunk program is set up, while the feature is active.
 * <p>
 * Units: 12 and 13 are the scene copies ({@link SceneTextures}); feature samplers get 14 and up, in registration
 * order. They lie outside the 12 units {@code GlStateManager} tracks, so binding them never confuses vanilla's
 * texture cache. GL 3.3 guarantees 16 fragment units, so at most two can be registered. Register during mod
 * construction.
 */
public final class TerrainSamplers {
    public static final int FIRST_UNIT = 14;
    public static final int MAX = 2;

    private record Entry(AtmosFeature feature, String name, int unit, int target, IntSupplier texture) {
    }

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static List<Entry> frozen = List.of();

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
        if (!frozen.isEmpty() || ENTRIES.size() >= MAX) {
            throw new IllegalStateException("Atmosphera terrain samplers: register during mod construction, at most " + MAX);
        }
        int unit = FIRST_UNIT + ENTRIES.size();
        ENTRIES.add(new Entry(feature, glslName, unit, target, texture));
        return unit;
    }

    public static synchronized void freeze() {
        frozen = List.copyOf(ENTRIES);
    }

    /** Sampler locations of one program (-1 where the program does not use it). Render thread. */
    public static int[] locate(int program) {
        List<Entry> entries = frozen;
        int[] locations = new int[entries.size()];
        for (int i = 0; i < locations.length; i++) {
            locations[i] = GL20C.glGetUniformLocation(program, entries.get(i).name());
        }
        return locations;
    }

    /** With the program bound: points its samplers at their units and binds the textures. Render thread. */
    public static void bind(int[] locations) {
        if (locations == null) {
            return;
        }
        List<Entry> entries = frozen;
        int previous = -1;
        for (int i = 0; i < locations.length && i < entries.size(); i++) {
            if (locations[i] < 0) {
                continue;
            }
            Entry entry = entries.get(i);
            if (previous < 0) {
                previous = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            }
            GL20C.glUniform1i(locations[i], entry.unit());
            GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + entry.unit());
            GL11C.glBindTexture(entry.target(), entry.feature().isActive() ? entry.texture().getAsInt() : 0);
        }
        if (previous >= 0) {
            GL13C.glActiveTexture(previous);
        }
    }
}
