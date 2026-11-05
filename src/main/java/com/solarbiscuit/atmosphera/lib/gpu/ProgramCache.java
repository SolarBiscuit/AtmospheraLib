package com.solarbiscuit.atmosphera.lib.gpu;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Lets Atmosphera recompile Xenon's terrain programs without a chunk rebuild: every ShaderChunkRenderer registers
 * itself here (weakly) and can drop its compiled programs; Sodium recompiles them lazily on the next pass.
 * Render thread only.
 */
public final class ProgramCache {
    /** Duck interface mixed into {@code ShaderChunkRenderer}. */
    public interface Recompilable {
        void atmos$deletePrograms();
    }

    private static final List<WeakReference<Recompilable>> RENDERERS = new ArrayList<>();

    private ProgramCache() {
    }

    public static void track(Recompilable renderer) {
        RENDERERS.removeIf(ref -> ref.get() == null);
        RENDERERS.add(new WeakReference<>(renderer));
    }

    /** Must not run while terrain is being drawn (client tick or between frames). */
    public static void recompileAll() {
        for (Iterator<WeakReference<Recompilable>> it = RENDERERS.iterator(); it.hasNext(); ) {
            Recompilable renderer = it.next().get();
            if (renderer == null) {
                it.remove();
            } else {
                renderer.atmos$deletePrograms();
            }
        }
    }
}
