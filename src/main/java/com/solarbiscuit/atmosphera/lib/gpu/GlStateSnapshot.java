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
 */
public final class GlStateSnapshot {
    private int drawFramebuffer;
    private int readFramebuffer;
    private int program;
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

    private GlStateSnapshot() {
    }

    /** Saves, then sets a neutral state: no depth test or writes, no cull/scissor/stencil/blend, full colour mask. */
    public static GlStateSnapshot save() {
        GlStateSnapshot s = new GlStateSnapshot();
        s.drawFramebuffer = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        s.readFramebuffer = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        s.program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        s.activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, s.viewport);
        s.blend = GL11C.glIsEnabled(GL11C.GL_BLEND);
        s.blendSrcRgb = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_RGB);
        s.blendDstRgb = GL11C.glGetInteger(GL14C.GL_BLEND_DST_RGB);
        s.blendSrcAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_SRC_ALPHA);
        s.blendDstAlpha = GL11C.glGetInteger(GL14C.GL_BLEND_DST_ALPHA);
        s.depthTest = GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST);
        s.depthMask = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK);
        s.cull = GL11C.glIsEnabled(GL11C.GL_CULL_FACE);
        s.scissor = GL11C.glIsEnabled(GL11C.GL_SCISSOR_TEST);
        GL11C.glGetIntegerv(GL11C.GL_SCISSOR_BOX, s.scissorBox);
        s.stencil = GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer mask = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, mask);
            for (int i = 0; i < 4; i++) {
                s.colorMask[i] = mask.get(i) != 0;
            }
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
    }

    private static void set(int cap, boolean on) {
        if (on) {
            GL11C.glEnable(cap);
        } else {
            GL11C.glDisable(cap);
        }
    }
}
