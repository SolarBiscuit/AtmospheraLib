package com.solarbiscuit.atmosphera.lib.gpu;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GLCapabilities;

/**
 * The OpenGL version of the game's context, for features that need more than Minecraft's GL 3.2 core baseline.
 * Minecraft asks for a 3.2 core context, and Windows and Linux drivers return the newest core version they support
 * (AMD, NVIDIA and Intel since Skylake report 4.6; macOS stops at 4.1). Read once the context exists; until then the
 * version is unknown and {@link #atLeast} answers true, so nothing is switched off before the first real check.
 */
public final class GlCaps {
    private static int version = -1;
    private static String renderer = "";

    private GlCaps() {
    }

    /** Version as major * 10 + minor (46 for 4.6), or -1 while no context exists yet. Thread-safe. */
    public static int version() {
        if (version < 0) {
            probe();
        }
        return version;
    }

    /** True when the context is at least {@code major.minor}, or when it is not known yet. */
    public static boolean atLeast(int major, int minor) {
        int v = version();
        return v < 0 || v >= major * 10 + minor;
    }

    /** "4.6" or "unknown". */
    public static String describe() {
        int v = version();
        return v < 0 ? "unknown" : (v / 10) + "." + (v % 10);
    }

    public static String renderer() {
        version();
        return renderer;
    }

    private static synchronized void probe() {
        if (version >= 0) {
            return;
        }
        GLCapabilities caps;
        try {
            caps = GL.getCapabilities();
        } catch (IllegalStateException noContextOnThisThread) {
            return;
        }
        int v;
        if (caps.OpenGL46) v = 46;
        else if (caps.OpenGL45) v = 45;
        else if (caps.OpenGL44) v = 44;
        else if (caps.OpenGL43) v = 43;
        else if (caps.OpenGL42) v = 42;
        else if (caps.OpenGL41) v = 41;
        else if (caps.OpenGL40) v = 40;
        else if (caps.OpenGL33) v = 33;
        else v = 32;
        String name = GL11C.glGetString(GL11C.GL_RENDERER);
        renderer = name == null ? "" : name;
        version = v;
        AtmospheraLib.LOGGER.info("OpenGL {} core context ({})", describe(), renderer);
        // Features that need a newer version were judged before the version was known: judge them again.
        com.solarbiscuit.atmosphera.lib.state.AtmosState.requestRefresh();
    }
}
