package com.solarbiscuit.atmosphera.lib.gpu;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;

/**
 * Saves the GL state a fullscreen pass usually changes and puts it back exactly, so vanilla's {@code GlStateManager}
 * cache stays true: draw and read framebuffers, program, active texture unit, viewport, blend (enable and functions), depth test,
 * depth mask, cull, scissor (enable and box), stencil and colour mask. Texture bindings are not saved: passes use units 12 to 15, which
 * {@code GlStateManager} does not track, and leave them bound to 0. Render thread only.
 * <pre>
 * GlStateSnapshot saved = GlStateSnapshot.save();   // also disables depth/cull/scissor/stencil/blend, full colour mask
 * ... draw ...
 * saved.restore();
 * </pre>
 * Since 0.3.23 the values {@code GlStateManager} caches come from its cache once {@link GlStateCache} has checked
 * them against the driver, and the vertex array binding is saved too.
 * Snapshots are pooled (0.3.22): {@link #restore()} returns the instance, so do not use it after restoring. Nested
 * saves are fine. A snapshot never restored is simply not reused.
 */
public final class GlStateSnapshot {
    private int drawFramebuffer;
    private int readFramebuffer;
    private int program;
    private int vertexArray;
    private int activeTexture;
    private final int[] viewport = new int[4];
    private boolean blend;
    private int blendSrcRgb;
    private int blendDstRgb;
    private int blendSrcAlpha;
    private int blendDstAlpha;
    private boolean depthTest;
    private boolean depthMask;
    private boolean cull;
    private boolean scissor;
    private boolean stencil;
    private final boolean[] colorMask = new boolean[4];
    private final int[] scissorBox = new int[4];

    /** Free snapshots (render thread only). */
    private static GlStateSnapshot[] pool = new GlStateSnapshot[4];
    private static int pooled;
    private boolean inUse;
    private static int open;
    private static final int[] VIEWPORT_CHECK = new int[4];
    private static final boolean[] MASK_CHECK = new boolean[4];

    private GlStateSnapshot() {
    }

    /** Saves, then sets a neutral state: no depth test or writes, no cull/scissor/stencil/blend, full colour mask. */
    public static GlStateSnapshot save() {
        GlStateSnapshot s = pooled > 0 ? pool[--pooled] : new GlStateSnapshot();
        s.inUse = true;
        open++;
        // Not cached by vanilla: always from the driver.
        s.drawFramebuffer = AtmosGl.getInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        s.readFramebuffer = AtmosGl.getInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        s.program = AtmosGl.getInteger(GL20C.GL_CURRENT_PROGRAM);
        s.vertexArray = AtmosGl.getInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        AtmosGl.getIntegerv(GL11C.GL_SCISSOR_BOX, s.scissorBox);
        s.stencil = AtmosGl.isEnabled(GL11C.GL_STENCIL_TEST);
        // Cached by GlStateManager: from its cache once the check trusts the value (0.3.23, see GlStateCache).
        boolean verify = GlStateCache.beginSnapshot();
        try {
            s.readCached(verify);
        } catch (RuntimeException e) {
            GlStateCache.disable(e);
            s.readCached(false);
        }
        GL11C.glDisable(GL11C.GL_BLEND);
        GL11C.glDisable(GL11C.GL_DEPTH_TEST);
        GL11C.glDepthMask(false);
        GL11C.glDisable(GL11C.GL_CULL_FACE);
        GL11C.glDisable(GL11C.GL_SCISSOR_TEST);
        GL11C.glDisable(GL11C.GL_STENCIL_TEST);
        GL11C.glColorMask(true, true, true, true);
        return s;
    }

    public void restore() {
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, drawFramebuffer);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer);
        GL20C.glUseProgram(program);
        GL30C.glBindVertexArray(vertexArray);
        GL13C.glActiveTexture(activeTexture);
        GL11C.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        GL14C.glBlendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        set(GL11C.GL_BLEND, blend);
        set(GL11C.GL_DEPTH_TEST, depthTest);
        GL11C.glDepthMask(depthMask);
        set(GL11C.GL_CULL_FACE, cull);
        set(GL11C.GL_SCISSOR_TEST, scissor);
        GL11C.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);
        set(GL11C.GL_STENCIL_TEST, stencil);
        GL11C.glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
        if (inUse) {
            inUse = false;
            open--;
            if (pooled == pool.length) {
                pool = java.util.Arrays.copyOf(pool, pooled * 2);
            }
            pool[pooled++] = this;
        }
    }

    /** Snapshots taken and not yet restored (GlslPrograms.drawFullscreen leaves the VAO to the snapshot then). */
    static int openCount() {
        return open;
    }

    /**
     * The values GlStateManager caches. A value comes from the cache when GlStateCache trusts it and this is not a
     * checking snapshot; otherwise from the driver, compared with the cache while checking.
     */
    private void readCached(boolean verify) {
        try {
            readCachedOrThrow(verify);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private void readCachedOrThrow(boolean verify) throws IllegalAccessException {
        if (GlStateCache.trusted(GlStateCache.ACTIVE_TEXTURE) && !verify) {
            activeTexture = GlStateCache.activeTexture();
        } else {
            activeTexture = AtmosGl.getInteger(GL13C.GL_ACTIVE_TEXTURE);
            if (verify) GlStateCache.compare(GlStateCache.ACTIVE_TEXTURE, activeTexture == GlStateCache.activeTexture());
        }
        if (GlStateCache.trusted(GlStateCache.VIEWPORT) && !verify) {
            GlStateCache.viewport(viewport);
        } else {
            AtmosGl.getIntegerv(GL11C.GL_VIEWPORT, viewport);
            if (verify) {
                GlStateCache.viewport(VIEWPORT_CHECK);
                GlStateCache.compare(GlStateCache.VIEWPORT, java.util.Arrays.equals(viewport, VIEWPORT_CHECK));
            }
        }
        if (GlStateCache.trusted(GlStateCache.BLEND) && !verify) {
            blend = GlStateCache.blend();
        } else {
            blend = AtmosGl.isEnabled(GL11C.GL_BLEND);
            if (verify) GlStateCache.compare(GlStateCache.BLEND, blend == GlStateCache.blend());
        }
        if (GlStateCache.trusted(GlStateCache.BLEND_RGB) && !verify) {
            blendSrcRgb = GlStateCache.blendSrcRgb();
            blendDstRgb = GlStateCache.blendDstRgb();
        } else {
            blendSrcRgb = AtmosGl.getInteger(GL14C.GL_BLEND_SRC_RGB);
            blendDstRgb = AtmosGl.getInteger(GL14C.GL_BLEND_DST_RGB);
            if (verify) GlStateCache.compare(GlStateCache.BLEND_RGB,
                    blendSrcRgb == GlStateCache.blendSrcRgb() && blendDstRgb == GlStateCache.blendDstRgb());
        }
        if (GlStateCache.trusted(GlStateCache.BLEND_ALPHA) && !verify) {
            blendSrcAlpha = GlStateCache.blendSrcAlpha();
            blendDstAlpha = GlStateCache.blendDstAlpha();
        } else {
            blendSrcAlpha = AtmosGl.getInteger(GL14C.GL_BLEND_SRC_ALPHA);
            blendDstAlpha = AtmosGl.getInteger(GL14C.GL_BLEND_DST_ALPHA);
            if (verify) GlStateCache.compare(GlStateCache.BLEND_ALPHA,
                    blendSrcAlpha == GlStateCache.blendSrcAlpha() && blendDstAlpha == GlStateCache.blendDstAlpha());
        }
        if (GlStateCache.trusted(GlStateCache.DEPTH_TEST) && !verify) {
            depthTest = GlStateCache.depthTest();
        } else {
            depthTest = AtmosGl.isEnabled(GL11C.GL_DEPTH_TEST);
            if (verify) GlStateCache.compare(GlStateCache.DEPTH_TEST, depthTest == GlStateCache.depthTest());
        }
        if (GlStateCache.trusted(GlStateCache.DEPTH_MASK) && !verify) {
            depthMask = GlStateCache.depthMask();
        } else {
            depthMask = AtmosGl.getBoolean(GL11C.GL_DEPTH_WRITEMASK);
            if (verify) GlStateCache.compare(GlStateCache.DEPTH_MASK, depthMask == GlStateCache.depthMask());
        }
        if (GlStateCache.trusted(GlStateCache.CULL) && !verify) {
            cull = GlStateCache.cull();
        } else {
            cull = AtmosGl.isEnabled(GL11C.GL_CULL_FACE);
            if (verify) GlStateCache.compare(GlStateCache.CULL, cull == GlStateCache.cull());
        }
        if (GlStateCache.trusted(GlStateCache.SCISSOR) && !verify) {
            scissor = GlStateCache.scissor();
        } else {
            scissor = AtmosGl.isEnabled(GL11C.GL_SCISSOR_TEST);
            if (verify) GlStateCache.compare(GlStateCache.SCISSOR, scissor == GlStateCache.scissor());
        }
        if (GlStateCache.trusted(GlStateCache.COLOR_MASK) && !verify) {
            GlStateCache.colorMask(colorMask);
        } else {
            AtmosGl.countQuery();
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) {
                    colorMask[i] = mask.get(i) != 0;
                }
            }
            if (verify) {
                GlStateCache.colorMask(MASK_CHECK);
                GlStateCache.compare(GlStateCache.COLOR_MASK, java.util.Arrays.equals(colorMask, MASK_CHECK));
            }
        }
    }

    private static void set(int cap, boolean on) {
        if (on) {
            GL11C.glEnable(cap);
        } else {
            GL11C.glDisable(cap);
        }
    }
}
