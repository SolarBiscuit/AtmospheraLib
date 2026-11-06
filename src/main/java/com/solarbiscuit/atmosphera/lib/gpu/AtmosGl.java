package com.solarbiscuit.atmosphera.lib.gpu;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL45C;
import org.lwjgl.opengl.GLCapabilities;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

/**
 * GL calls that avoid state queries (0.3.23). Every {@code glGet*} may make the driver wait for its command thread
 * (NVIDIA's threaded optimization, on by default), so the lib and its mods route the queries they still need through
 * {@link #getInteger} and {@link #isEnabled}, which count them for {@code /atmosphera status}, and use direct state
 * access where the context has it (GL 4.5, or {@code ARB_direct_state_access}):
 * <ul>
 *   <li>{@link #bindTextureUnit} binds to a unit without switching (and so without first reading) the active unit;</li>
 *   <li>{@link #texSubImage2D} / {@link #texSubImage3D} upload to a texture without binding it.</li>
 * </ul>
 * Without direct state access the same calls fall back to save, bind and restore. Use them only for units 12 and up:
 * {@code GlStateManager} tracks units 0-11 and would not see a direct bind. Render thread only.
 */
public final class AtmosGl {
    private static int dsa = -1;
    private static boolean arb;

    private static long gets;
    private static long frameStartGets;
    private static int framesInWindow;
    private static long windowGets;
    private static int windowMax;
    private static float lastAverage = -1f;
    private static int lastMax;
    private static final int WINDOW = 600;

    private AtmosGl() {
    }

    /** Whether direct state access is available (GL 4.5 or ARB_direct_state_access). Render thread. */
    public static boolean dsa() {
        if (dsa < 0) {
            try {
                GLCapabilities caps = GL.getCapabilities();
                arb = !caps.OpenGL45 && caps.GL_ARB_direct_state_access;
                dsa = caps.OpenGL45 || caps.GL_ARB_direct_state_access ? 1 : 0;
            } catch (IllegalStateException noContext) {
                return false;
            }
        }
        return dsa == 1;
    }

    /** {@code glGetInteger}, counted. */
    public static int getInteger(int pname) {
        gets++;
        return GL11C.glGetInteger(pname);
    }

    /** {@code glGetIntegerv}, counted. */
    public static void getIntegerv(int pname, int[] out) {
        gets++;
        GL11C.glGetIntegerv(pname, out);
    }

    /** {@code glIsEnabled}, counted. */
    public static boolean isEnabled(int cap) {
        gets++;
        return GL11C.glIsEnabled(cap);
    }

    /** {@code glGetBoolean}, counted. */
    public static boolean getBoolean(int pname) {
        gets++;
        return GL11C.glGetBoolean(pname);
    }

    /** Counts a query made some other way (for example {@code glGetBooleanv} into a stack buffer). */
    public static void countQuery() {
        gets++;
    }

    /**
     * Binds {@code texture} to {@code unit} (12 or more) for {@code target}. With direct state access the active unit
     * is neither read nor changed; otherwise (and for texture 0, which must clear only {@code target}) it is saved and
     * restored.
     */
    public static void bindTextureUnit(int unit, int target, int texture) {
        // Binding 0 by unit would clear every target of the unit (Lumis shares unit 14 between a 2D and a 3D
        // texture), so a zero bind keeps the per-target path.
        if (texture != 0 && dsa()) {
            if (arb) {
                org.lwjgl.opengl.ARBDirectStateAccess.glBindTextureUnit(unit, texture);
            } else {
                GL45C.glBindTextureUnit(unit, texture);
            }
            return;
        }
        int previous = getInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        GL11C.glBindTexture(target, texture);
        GL13C.glActiveTexture(previous);
    }

    /**
     * {@code glTexSubImage3D} into {@code texture}. Without direct state access it binds the texture on {@code unit}
     * (12 or more) and restores both the active unit and that unit's binding. The unpack state is the caller's (see
     * {@link Uploads}).
     */
    public static void texSubImage3D(int unit, int texture, int x, int y, int z, int w, int h, int d, int format,
                                     int type, ByteBuffer pixels) {
        if (dsa()) {
            if (arb) {
                org.lwjgl.opengl.ARBDirectStateAccess.glTextureSubImage3D(texture, 0, x, y, z, w, h, d, format, type, pixels);
            } else {
                GL45C.glTextureSubImage3D(texture, 0, x, y, z, w, h, d, format, type, pixels);
            }
            return;
        }
        int previousUnit = getInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        int previous = getInteger(GL12C.GL_TEXTURE_BINDING_3D);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        GL12C.glTexSubImage3D(GL12C.GL_TEXTURE_3D, 0, x, y, z, w, h, d, format, type, pixels);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
        GL13C.glActiveTexture(previousUnit);
    }

    /** {@code glTexSubImage2D} into {@code texture}, like {@link #texSubImage3D}. */
    public static void texSubImage2D(int unit, int texture, int x, int y, int w, int h, int format, int type,
                                     ShortBuffer pixels) {
        if (dsa()) {
            if (arb) {
                org.lwjgl.opengl.ARBDirectStateAccess.glTextureSubImage2D(texture, 0, x, y, w, h, format, type, pixels);
            } else {
                GL45C.glTextureSubImage2D(texture, 0, x, y, w, h, format, type, pixels);
            }
            return;
        }
        int previousUnit = getInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        int previous = getInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, x, y, w, h, format, type, pixels);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        GL13C.glActiveTexture(previousUnit);
    }

    /** {@code glTexSubImage2D} into {@code texture}, like {@link #texSubImage3D} (array source). */
    public static void texSubImage2D(int unit, int texture, int x, int y, int w, int h, int format, int type,
                                     short[] pixels) {
        if (dsa()) {
            if (arb) {
                org.lwjgl.opengl.ARBDirectStateAccess.glTextureSubImage2D(texture, 0, x, y, w, h, format, type, pixels);
            } else {
                GL45C.glTextureSubImage2D(texture, 0, x, y, w, h, format, type, pixels);
            }
            return;
        }
        int previousUnit = getInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        int previous = getInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, x, y, w, h, format, type, pixels);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        GL13C.glActiveTexture(previousUnit);
    }

    /** Same-size nearest blit between two framebuffers by name; only with {@link #dsa()}. */
    public static void blitNamed(int read, int draw, int w, int h, int mask) {
        if (arb) {
            org.lwjgl.opengl.ARBDirectStateAccess.glBlitNamedFramebuffer(read, draw, 0, 0, w, h, 0, 0, w, h, mask, GL11C.GL_NEAREST);
        } else {
            GL45C.glBlitNamedFramebuffer(read, draw, 0, 0, w, h, 0, 0, w, h, mask, GL11C.GL_NEAREST);
        }
    }

    /** Frame boundary (called by the lib after the sky). */
    public static void newFrame() {
        int frameGets = (int) (gets - frameStartGets);
        frameStartGets = gets;
        windowGets += frameGets;
        windowMax = Math.max(windowMax, frameGets);
        if (++framesInWindow >= WINDOW) {
            lastAverage = windowGets / (float) framesInWindow;
            lastMax = windowMax;
            framesInWindow = 0;
            windowGets = 0;
            windowMax = 0;
        }
    }

    /** "glGet per frame: 3.0 avg, 5 max (last 600 frames), direct state access on" for the status. */
    public static String describe() {
        String window = lastAverage < 0 ? "not measured yet"
                : String.format(java.util.Locale.ROOT, "%.1f avg, %d max (last %d frames)", lastAverage, lastMax, WINDOW);
        return "glGet per frame (Atmosphera): " + window + " | direct state access " + (dsa() ? (arb ? "on (ARB)" : "on") : "off")
                + " | " + GlStateCache.describe();
    }
}
