package com.solarbiscuit.atmosphera.lib.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;

/**
 * Reads what vanilla's {@code GlStateManager} already caches, so {@link GlStateSnapshot} does not have to ask the
 * driver (0.3.23): the active texture unit, the viewport, blend enable and functions, depth test and mask, cull,
 * scissor enable and the colour mask.
 * <p>
 * The cache is only as good as the code around it: a mod that changes GL state without {@code GlStateManager} leaves
 * it stale. So every value is checked against the driver first. The first {@link #CHECK} snapshots read both and
 * compare; a value is used from the cache only if it matched every time. After that one snapshot in
 * {@link #SPOT_EVERY} is checked again, and a value that ever differs goes back to the driver for the session, with a
 * warning. Render thread only.
 */
public final class GlStateCache {
    /** Cached values, in {@link #NAMES} order. */
    public static final int ACTIVE_TEXTURE = 0, VIEWPORT = 1, BLEND = 2, BLEND_RGB = 3, BLEND_ALPHA = 4, DEPTH_TEST = 5,
            DEPTH_MASK = 6, CULL = 7, SCISSOR = 8, COLOR_MASK = 9;
    private static final String[] NAMES = {"active texture", "viewport", "blend", "blend rgb", "blend alpha",
            "depth test", "depth mask", "cull", "scissor", "colour mask"};
    private static final int CHECK = 600;
    private static final int SPOT_EVERY = 256;

    private static boolean available;
    private static Object blendMode, depthMode, cullMode, scissorMode;
    private static Object blendState, depthState, colorMask, viewport;
    private static Field booleanEnabled, blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha, depthMask;
    private static Field maskR, maskG, maskB, maskA, viewX, viewY, viewW, viewH;

    private static final int[] mismatches = new int[NAMES.length];
    private static int trusted;
    private static int snapshots;
    private static boolean disabled;

    static {
        try {
            Class<?> gsm = GlStateManager.class;
            blendState = staticField(gsm, "BLEND");
            depthState = staticField(gsm, "DEPTH");
            Object cull = staticField(gsm, "CULL");
            Object scissor = staticField(gsm, "SCISSOR");
            colorMask = staticField(gsm, "COLOR_MASK");
            blendMode = field(blendState.getClass(), "f_84577_").get(blendState);
            blendSrcRgb = field(blendState.getClass(), "f_84578_");
            blendDstRgb = field(blendState.getClass(), "f_84579_");
            blendSrcAlpha = field(blendState.getClass(), "f_84580_");
            blendDstAlpha = field(blendState.getClass(), "f_84581_");
            depthMode = field(depthState.getClass(), "f_84626_").get(depthState);
            depthMask = field(depthState.getClass(), "f_84627_");
            cullMode = field(cull.getClass(), "f_84621_").get(cull);
            scissorMode = field(scissor.getClass(), "f_84732_").get(scissor);
            booleanEnabled = field(blendMode.getClass(), "f_84586_");
            maskR = field(colorMask.getClass(), "f_84608_");
            maskG = field(colorMask.getClass(), "f_84609_");
            maskB = field(colorMask.getClass(), "f_84610_");
            maskA = field(colorMask.getClass(), "f_84611_");
            Class<?> viewportClass = Class.forName("com.mojang.blaze3d.platform.GlStateManager$Viewport");
            viewport = staticField(viewportClass, "INSTANCE");
            viewX = field(viewportClass, "f_84806_");
            viewY = field(viewportClass, "f_84807_");
            viewW = field(viewportClass, "f_84808_");
            viewH = field(viewportClass, "f_84809_");
            available = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            AtmospheraLib.LOGGER.info("GlStateManager's state cache is not readable ({}); post passes query the driver.", e.toString());
        }
    }

    private GlStateCache() {
    }

    private static Object staticField(Class<?> owner, String name) throws IllegalAccessException {
        return field(owner, name).get(null);
    }

    private static Field field(Class<?> owner, String srg) {
        Field f = ObfuscationReflectionHelper.findField(owner, srg);
        f.setAccessible(true);
        return f;
    }

    /** Starts a snapshot: returns whether this one must also read the driver and compare. */
    static boolean beginSnapshot() {
        snapshots++;
        if (!available || disabled) {
            return false;
        }
        if (snapshots == CHECK) {
            for (int i = 0; i < NAMES.length; i++) {
                if (mismatches[i] == 0) {
                    trusted |= 1 << i;
                }
            }
            AtmospheraLib.LOGGER.info("GL state cache check after {} snapshots: {}", CHECK, describe());
        }
        return snapshots <= CHECK || snapshots % SPOT_EVERY == 0;
    }

    /** Whether value {@code which} is read from the cache (after the check passed for it). */
    static boolean trusted(int which) {
        return available && !disabled && (trusted & 1 << which) != 0;
    }

    /** Records a comparison; a mismatch after the check takes the value back to the driver for the session. */
    static void compare(int which, boolean same) {
        if (same) {
            return;
        }
        mismatches[which]++;
        if ((trusted & 1 << which) != 0) {
            trusted &= ~(1 << which);
            AtmospheraLib.LOGGER.warn("GlStateManager's cached {} differed from the driver after the check; reading it from the driver from now on.",
                    NAMES[which]);
        }
    }

    static int activeTexture() {
        return GlStateManager._getActiveTexture();
    }

    static void viewport(int[] out) throws IllegalAccessException {
        out[0] = viewX.getInt(viewport);
        out[1] = viewY.getInt(viewport);
        out[2] = viewW.getInt(viewport);
        out[3] = viewH.getInt(viewport);
    }

    static boolean blend() throws IllegalAccessException {
        return booleanEnabled.getBoolean(blendMode);
    }

    static int blendSrcRgb() throws IllegalAccessException {
        return blendSrcRgb.getInt(blendState);
    }

    static int blendDstRgb() throws IllegalAccessException {
        return blendDstRgb.getInt(blendState);
    }

    static int blendSrcAlpha() throws IllegalAccessException {
        return blendSrcAlpha.getInt(blendState);
    }

    static int blendDstAlpha() throws IllegalAccessException {
        return blendDstAlpha.getInt(blendState);
    }

    static boolean depthTest() throws IllegalAccessException {
        return booleanEnabled.getBoolean(depthMode);
    }

    static boolean depthMask() throws IllegalAccessException {
        return depthMask.getBoolean(depthState);
    }

    static boolean cull() throws IllegalAccessException {
        return booleanEnabled.getBoolean(cullMode);
    }

    static boolean scissor() throws IllegalAccessException {
        return booleanEnabled.getBoolean(scissorMode);
    }

    static void colorMask(boolean[] out) throws IllegalAccessException {
        out[0] = maskR.getBoolean(colorMask);
        out[1] = maskG.getBoolean(colorMask);
        out[2] = maskB.getBoolean(colorMask);
        out[3] = maskA.getBoolean(colorMask);
    }

    /** A read failed at runtime: stop using the cache. */
    static void disable(Exception e) {
        if (!disabled) {
            disabled = true;
            AtmospheraLib.LOGGER.warn("Reading GlStateManager's cache failed ({}); post passes query the driver.", e.toString());
        }
    }

    public static String describe() {
        if (!available || disabled) {
            return "state cache off";
        }
        if (snapshots < CHECK) {
            return "state cache: checking (" + snapshots + "/" + CHECK + ")";
        }
        StringBuilder used = new StringBuilder();
        StringBuilder driver = new StringBuilder();
        for (int i = 0; i < NAMES.length; i++) {
            StringBuilder to = (trusted & 1 << i) != 0 ? used : driver;
            to.append(to.isEmpty() ? "" : ", ").append(NAMES[i]);
            if (mismatches[i] > 0) {
                to.append(" (").append(mismatches[i]).append(" mismatches)");
            }
        }
        return "state cache used for: " + (used.isEmpty() ? "nothing" : used) + "; from the driver: "
                + (driver.isEmpty() ? "nothing" : driver);
    }
}
