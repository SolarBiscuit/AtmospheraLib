package com.solarbiscuit.atmosphera.lib.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;

/**
 * Copies of the scene taken after opaque/cutout terrain and entities, right before translucent terrain:
 * <ul>
 *   <li>depth (texture unit {@link #DEPTH_UNIT}, sampler {@code atmos_SceneDepth}): how far the geometry behind water
 *       is, for absorption, foam, caustic fade;</li>
 *   <li>colour (unit {@link #COLOR_UNIT}, sampler {@code atmos_SceneColor}): what lies behind, for refraction.</li>
 * </ul>
 * A copy is needed because the translucent pass still depth-tests against (and writes to) the live buffers.
 * Each copy exists only while an active feature declares it needs it ({@code Uses.SCENE_DEPTH} / {@code SCENE_COLOR});
 * otherwise its GPU memory is freed. Uses glBlitFramebuffer (GL 3.0, every vendor). The first depth copy after an
 * allocation is checked against the source once; if the blit does not reproduce it, glCopyTexSubImage2D is used
 * instead. Render thread only.
 */
public final class SceneTextures {
    /** Units above the 12 that GlStateManager tracks, so binding them never desyncs vanilla's state cache. */
    public static final int DEPTH_UNIT = 12;
    public static final int COLOR_UNIT = 13;

    /** Copies between forced re-reads of the source attachment format (about 10 s at 60 fps). */
    private static final int FORMAT_RECHECK = 600;

    private static final Copy DEPTH = new Copy("scene depth", true);
    private static final Copy COLOR = new Copy("scene colour", false);

    private SceneTextures() {
    }

    public static boolean isDepthValid() {
        return DEPTH.valid;
    }

    public static boolean isColorValid() {
        return COLOR.valid;
    }

    /**
     * The opaque scene's depth copy as a texture (0.9.1), or 0 while it is not valid. Valid from the start of the
     * translucent terrain pass, so a {@code BEFORE_TRANSLUCENT} pass can read it. Do not change its parameters.
     */
    public static int depthTexture() {
        return DEPTH.valid ? DEPTH.texture : 0;
    }

    /** The opaque scene's colour copy (RGBA8, linear filtering) as a texture (0.9.1), or 0 while it is not valid. */
    public static int colorTexture() {
        return COLOR.valid ? COLOR.texture : 0;
    }

    /** Called at the start of the translucent terrain pass with what the active features need. */
    public static void copyBeforeTranslucent(boolean depth, boolean color) {
        if (!depth) {
            DEPTH.release();
        }
        if (!color) {
            COLOR.release();
        }
        if (!depth && !color) {
            return;
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int source = AtmosGl.getInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (AtmosGl.dsa() && (!depth || DEPTH.ready(source, main.width, main.height))
                && (!color || COLOR.ready(source, main.width, main.height))) {
            // Steady state with direct state access (0.3.23): blit by name, no binding read or changed.
            if (depth) {
                GpuTimers.begin("scene_depth_copy");
                DEPTH.blitNamed(source, main.width, main.height);
                GpuTimers.end("scene_depth_copy");
            }
            if (color) {
                GpuTimers.begin("scene_color_copy");
                COLOR.blitNamed(source, main.width, main.height);
                GpuTimers.end("scene_color_copy");
            }
            return;
        }
        int previousRead = AtmosGl.getInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, source);
        if (depth) {
            GpuTimers.begin("scene_depth_copy");
            DEPTH.copy(source, main.width, main.height);
            GpuTimers.end("scene_depth_copy");
        }
        if (color) {
            GpuTimers.begin("scene_color_copy");
            COLOR.copy(source, main.width, main.height);
            GpuTimers.end("scene_color_copy");
        }
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, source);
    }

    /** Binds the copies to their units without disturbing GlStateManager's tracked active unit. */
    public static void bind() {
        AtmosGl.bindTextureUnit(DEPTH_UNIT, GL11C.GL_TEXTURE_2D, DEPTH.valid ? DEPTH.texture : 0);
        AtmosGl.bindTextureUnit(COLOR_UNIT, GL11C.GL_TEXTURE_2D, COLOR.valid ? COLOR.texture : 0);
    }

    public static void deleteAll() {
        DEPTH.release();
        COLOR.release();
    }

    public static String describe() {
        return "depth " + DEPTH.describe() + ", colour " + COLOR.describe();
    }

    private static final class Copy {
        private final String label;
        private final boolean depth;
        private int framebuffer;
        private int texture;
        private int width;
        private int height;
        private int internalFormat;
        private boolean valid;
        private boolean broken;
        private boolean verifyNext;
        private boolean copyTexture;
        // Source format cache (0.3.22): the format is read only when the source framebuffer or its size changes,
        // or every FORMAT_RECHECK copies as a safety net, instead of 3-4 glGet calls per frame.
        private int formatSource;
        private int formatWidth;
        private int formatHeight;
        private int formatCached;
        private int formatAge;
        /** Source format the copy was allocated for (the colour copy itself is always RGBA8). */
        private int allocatedFrom;

        Copy(String label, boolean depth) {
            this.label = label;
            this.depth = depth;
        }

        /**
         * Whether a plain named blit is all this frame's copy needs: allocated for this source, size and format, not
         * using the copy-texture fallback, no verification pending and no format re-read due.
         */
        boolean ready(int source, int w, int h) {
            return !broken && framebuffer != 0 && !copyTexture && !verifyNext && formatCached != 0
                    && source == formatSource && w == formatWidth && h == formatHeight && formatAge + 1 < FORMAT_RECHECK
                    && w == width && h == height && formatCached == allocatedFrom;
        }

        /** The copy by framebuffer name (GL 4.5 direct state access): no binding is read or changed. */
        void blitNamed(int source, int w, int h) {
            formatAge++;
            int mask = depth ? GL11C.GL_DEPTH_BUFFER_BIT : GL11C.GL_COLOR_BUFFER_BIT;
            AtmosGl.blitNamed(source, framebuffer, w, h, mask);
            valid = true;
        }

        /** Expects {@code source} bound as the read framebuffer. */
        void copy(int source, int w, int h) {
            if (broken) {
                return;
            }
            if (source != formatSource || w != formatWidth || h != formatHeight || formatCached == 0
                    || ++formatAge >= FORMAT_RECHECK) {
                formatCached = sourceFormat();
                formatSource = source;
                formatWidth = w;
                formatHeight = h;
                formatAge = 0;
            }
            int format = formatCached;
            if (format == 0) {
                fail("could not read the source attachment format of framebuffer " + source);
                return;
            }
            if (framebuffer == 0 || w != width || h != height || format != allocatedFrom) {
                boolean ok = allocate(w, h, format);
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, source);
                if (!ok) {
                    return;
                }
            }
            boolean verifyNow = verifyNext && depth;
            if (verifyNow) {
                //GL error flags are sticky: drop what other renderers left pending (NeoVoxy 1.43.1-1.43.4 left one
                // every frame), or the check below blamed the blit for it and switched to the fallback for good
                for (int i = 0; i < 64 && GL11C.glGetError() != GL11C.GL_NO_ERROR; i++) {
                }
            }
            if (copyTexture) {
                int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
                GL11C.glCopyTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, 0, 0, 0, w, h);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            } else {
                GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
                GL30C.glBlitFramebuffer(0, 0, w, h, 0, 0, w, h,
                        depth ? GL11C.GL_DEPTH_BUFFER_BIT : GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST);
            }
            valid = true;
            if (verifyNow) {
                verifyNext = false;
                verify(source, w, h);
            }
        }

        /** One synchronous centre-pixel comparison after each allocation; switches to glCopyTexSubImage2D on mismatch. */
        private void verify(int source, int w, int h) {
            int error = GL11C.glGetError();
            float[] src = new float[1];
            float[] dst = new float[1];
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, source);
            GL11C.glReadPixels(w / 2, h / 4, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, src);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
            GL11C.glReadPixels(w / 2, h / 4, 1, 1, GL11C.GL_DEPTH_COMPONENT, GL11C.GL_FLOAT, dst);
            GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, source);
            if (!copyTexture && (error != GL11C.GL_NO_ERROR || src[0] != dst[0])) {
                copyTexture = true;
                AtmospheraLib.LOGGER.warn("Scene depth blit did not reproduce the source (GL error 0x{}, {} vs {}); "
                        + "using glCopyTexSubImage2D.", Integer.toHexString(error), src[0], dst[0]);
            }
        }

        private int sourceFormat() {
            int attachment = depth ? GL30C.GL_DEPTH_ATTACHMENT : GL30C.GL_COLOR_ATTACHMENT0;
            int type = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, attachment,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int name = GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, attachment,
                    GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (type == GL11C.GL_TEXTURE) {
                int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, name);
                int format = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
                GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
                return format;
            }
            if (type == GL30C.GL_RENDERBUFFER) {
                int previous = GL11C.glGetInteger(GL30C.GL_RENDERBUFFER_BINDING);
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, name);
                int format = GL30C.glGetRenderbufferParameteri(GL30C.GL_RENDERBUFFER, GL30C.GL_RENDERBUFFER_INTERNAL_FORMAT);
                GL30C.glBindRenderbuffer(GL30C.GL_RENDERBUFFER, previous);
                return format;
            }
            // Default framebuffer (no attachment object): fall back to common formats.
            return depth ? GL11C.GL_DEPTH_COMPONENT : GL11C.GL_RGBA8;
        }

        private boolean allocate(int w, int h, int format) {
            int sourceFormatOf = format;
            release();
            int pixelFormat;
            int pixelType;
            int attachment;
            if (depth) {
                boolean stencil = format == GL30C.GL_DEPTH24_STENCIL8 || format == GL30C.GL_DEPTH32F_STENCIL8
                        || format == GL30C.GL_DEPTH_STENCIL;
                pixelFormat = stencil ? GL30C.GL_DEPTH_STENCIL : GL11C.GL_DEPTH_COMPONENT;
                pixelType = format == GL30C.GL_DEPTH32F_STENCIL8 ? GL30C.GL_FLOAT_32_UNSIGNED_INT_24_8_REV
                        : stencil ? GL30C.GL_UNSIGNED_INT_24_8 : GL11C.GL_FLOAT;
                attachment = stencil ? GL30C.GL_DEPTH_STENCIL_ATTACHMENT : GL30C.GL_DEPTH_ATTACHMENT;
            } else {
                // Colour blits convert formats, so a plain RGBA8 copy is enough for refraction.
                format = GL11C.GL_RGBA8;
                pixelFormat = GL11C.GL_RGBA;
                pixelType = GL11C.GL_UNSIGNED_BYTE;
                attachment = GL30C.GL_COLOR_ATTACHMENT0;
            }

            int previousTexture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
            texture = GpuResources.texture(label);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            int filter = depth ? GL11C.GL_NEAREST : GL11C.GL_LINEAR;
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, filter);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, format, w, h, 0, pixelFormat, pixelType, (ByteBuffer) null);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previousTexture);

            framebuffer = GpuResources.framebuffer(label);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, attachment, GL11C.GL_TEXTURE_2D, texture, 0);
            GL11C.glDrawBuffer(depth ? GL11C.GL_NONE : GL30C.GL_COLOR_ATTACHMENT0);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                fail("framebuffer incomplete (status 0x" + Integer.toHexString(status) + ", format 0x"
                        + Integer.toHexString(format) + ")");
                return false;
            }
            width = w;
            height = h;
            internalFormat = format;
            allocatedFrom = sourceFormatOf;
            GpuResources.textureBytes(texture, (long) w * h * bytesPerTexel(format));
            verifyNext = true;
            AtmospheraLib.LOGGER.debug("{} copy allocated: {}x{}, format 0x{}.", label, w, h, Integer.toHexString(format));
            return true;
        }

        private static int bytesPerTexel(int format) {
            return format == GL30C.GL_DEPTH32F_STENCIL8 ? 8 : format == GL30C.GL_DEPTH_COMPONENT16 ? 2 : 4;
        }

        void release() {
            if (framebuffer != 0) {
                GpuResources.deleteFramebuffer(framebuffer);
                framebuffer = 0;
            }
            if (texture != 0) {
                GpuResources.deleteTexture(texture);
                texture = 0;
            }
            valid = false;
        }

        private void fail(String reason) {
            broken = true;
            release();
            AtmospheraLib.LOGGER.warn("The {} copy is disabled: {}. Features needing it lose that effect.", label, reason);
        }

        String describe() {
            if (broken) return "broken";
            if (framebuffer == 0) return "off";
            return width + "x" + height + " 0x" + Integer.toHexString(internalFormat) + (copyTexture ? " (copyTex)" : " (blit)");
        }
    }
}
