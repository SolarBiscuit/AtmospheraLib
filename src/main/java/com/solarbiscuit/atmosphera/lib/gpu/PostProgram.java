package com.solarbiscuit.atmosphera.lib.gpu;

import org.lwjgl.opengl.GL20C;
import org.slf4j.Logger;

import java.util.function.Supplier;

/**
 * A post-pass program with its uniform locations (0.11.0 "Shared"): compiled on first use, and when it does not
 * compile, the feature it belongs to stays off with one ERROR line until {@link #release} (the lib's shader reload)
 * lets it try again. The sources are suppliers, so a rebuild picks up changed defines or options.
 * <pre>
 * private static final PostProgram PROGRAM = new PostProgram(LOGGER, "Torrente waterline", "Torrente waterline",
 *         () -> GlslPrograms.FULLSCREEN_VERTEX, () -> GlslPrograms.header() + LINE, "u_InvViewProj", "u_Surface");
 * ...
 * if (!PROGRAM.ready()) return;
 * GL20C.glUseProgram(PROGRAM.id());
 * GL20C.glUniform4f(PROGRAM.u(1), ...);
 * </pre>
 * Render thread only.
 */
public final class PostProgram {
    private final Logger logger;
    private final String owner;
    private final String feature;
    private final Supplier<String> vertex;
    private final Supplier<String> fragment;
    private final String[] uniforms;
    private int id;
    private int[] locations = new int[0];
    private boolean failed;

    /**
     * @param owner   the program's name in GL resource lists and compile errors
     * @param feature what goes off when it does not compile, for the log line ("Torrente waterline: off, ...")
     */
    public PostProgram(Logger logger, String owner, String feature, Supplier<String> vertex, Supplier<String> fragment,
                       String... uniforms) {
        this.logger = logger;
        this.owner = owner;
        this.feature = feature;
        this.vertex = vertex;
        this.fragment = fragment;
        this.uniforms = uniforms;
    }

    /** Compiles on the first call; false (and nothing to draw) when it did not compile. */
    public boolean ready() {
        if (id != 0) {
            return true;
        }
        if (failed) {
            return false;
        }
        id = GlslPrograms.compile(owner, vertex.get(), fragment.get());
        if (id == 0) {
            failed = true;
            logger.error("[Atmosphera] {}: off, its program did not compile (see above)", feature);
            return false;
        }
        locations = locate(id, uniforms);
        return true;
    }

    /** The program (0 before {@link #ready} succeeded). */
    public int id() {
        return id;
    }

    /** The location of the {@code index}-th uniform named at construction (-1 when the compiler removed it). */
    public int u(int index) {
        return locations[index];
    }

    /** True after a failed compile, until {@link #release}. */
    public boolean failed() {
        return failed;
    }

    /** Frees the program; the next {@link #ready} compiles again (shader reload). */
    public void release() {
        if (id != 0) {
            GpuResources.deleteProgram(id);
            id = 0;
        }
        failed = false;
    }

    /** The locations of {@code names} in a linked program, in order. */
    public static int[] locate(int program, String... names) {
        int[] out = new int[names.length];
        for (int i = 0; i < names.length; i++) {
            out[i] = GL20C.glGetUniformLocation(program, names[i]);
        }
        return out;
    }
}
