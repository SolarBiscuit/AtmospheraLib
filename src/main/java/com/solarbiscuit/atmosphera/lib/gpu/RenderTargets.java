package com.solarbiscuit.atmosphera.lib.gpu;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL14C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;

/**
 * Framebuffers for post passes.
 * <ul>
 *   <li>{@link #scene()}: the framebuffer the world is being drawn into when the pass runs (normally Minecraft's main
 *       target, but render-scale and similar mods draw the level into their own and copy it to the main one later),
 *       with its depth and colour textures and size, asked from GL and cached per framebuffer.</li>
 *   <li>{@link #bindColorOnly(Scene)}: a framebuffer whose only attachment is the scene's colour texture, so a
 *       composite can sample the scene depth texture without a feedback loop.</li>
 *   <li>{@link Target}: off-screen colour targets sized as a fraction of the scene, reallocated when it changes.</li>
 * </ul>
 * Formats are GL 3.3 colour-renderable on every vendor: {@code GL_RGBA16F}, {@code GL_RGBA8}, {@code GL_R16F},
 * {@code GL_RG16F}. Texture memory is reported to {@link GpuResources} for {@code /atmosphera status}. Render thread only.
 */
public final class RenderTargets {
    /**
     * The framebuffer being drawn into, as found when a pass starts.
     *
     * @param depthTexture 0 when the depth attachment is not a texture (then nothing can read it)
     * @param colorTexture 0 when colour attachment 0 is not a texture
     * @param isMain       whether it is Minecraft's main render target
     */
    public record Scene(int framebuffer, int depthTexture, int colorTexture, int width, int height, boolean isMain) {
        public boolean usable() {
            return framebuffer != 0 && depthTexture != 0 && colorTexture != 0 && width > 0 && height > 0;
        }
    }

    private static int colorFbo;
    private static int colorFboTexture;
    private static Scene cached;
    private static int cachedMainWidth;
    private static int cachedMainHeight;

    private RenderTargets() {
    }

    /**
     * @param divisor  1 = full size, 2 = half width and height, 4 = quarter (1/16 of the pixels), ...
     * @param linear   bilinear filtering (for upsampling) instead of nearest
     */
    public static Target create(String owner, int divisor, int internalFormat, boolean linear) {
        return new Target(owner, Math.max(1, divisor), internalFormat, linear);
    }

    /** The framebuffer bound for drawing now, with its attachments (cached until it or the window size changes). */
    public static Scene scene() {
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int fbo = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        if (cached != null && cached.framebuffer() == fbo && main.width == cachedMainWidth
                && main.height == cachedMainHeight) {
            return cached;
        }
        cachedMainWidth = main.width;
        cachedMainHeight = main.height;
        if (fbo == 0) {
            cached = new Scene(0, 0, 0, 0, 0, false);
            AtmospheraLib.LOGGER.warn("Post passes: the world is drawn straight to the window (framebuffer 0); "
                    + "passes that read depth are skipped.");
            return cached;
        }
        int previousRead = GL11C.glGetInteger(GL30C.GL_READ_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, fbo);
        int depthType = attachment(GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int depthName = attachment(GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        if (depthType == GL11C.GL_NONE) {
            depthType = attachment(GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            depthName = attachment(GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        }
        int colorType = attachment(GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int colorName = attachment(GL30C.GL_COLOR_ATTACHMENT0, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previousRead);

        int depth = depthType == GL11C.GL_TEXTURE ? depthName : 0;
        int color = colorType == GL11C.GL_TEXTURE ? colorName : 0;
        int width = 0;
        int height = 0;
        int depthFormat = 0;
        int compare = 0;
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        if (color != 0) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, color);
            width = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_WIDTH);
            height = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_HEIGHT);
        }
        if (depth != 0) {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, depth);
            depthFormat = GL11C.glGetTexLevelParameteri(GL11C.GL_TEXTURE_2D, 0, GL11C.GL_TEXTURE_INTERNAL_FORMAT);
            compare = GL11C.glGetTexParameteri(GL11C.GL_TEXTURE_2D, GL14C.GL_TEXTURE_COMPARE_MODE);
        }
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
        GL13C.glActiveTexture(previousUnit);

        cached = new Scene(fbo, depth, color, width, height, fbo == main.frameBufferId);
        AtmospheraLib.LOGGER.info("Post passes draw into framebuffer {}{} ({}x{}): depth {} 0x{} compare 0x{}, colour {}; "
                        + "main target is framebuffer {} ({}x{}, depth {}, colour {})", fbo, cached.isMain() ? " (main)" : "",
                width, height, depthType == GL11C.GL_TEXTURE ? "texture " + depthName : "type 0x" + Integer.toHexString(depthType),
                Integer.toHexString(depthFormat), Integer.toHexString(compare),
                colorType == GL11C.GL_TEXTURE ? "texture " + colorName : "type 0x" + Integer.toHexString(colorType),
                main.frameBufferId, main.width, main.height, main.getDepthTextureId(), main.getColorTextureId());
        return cached;
    }

    /**
     * Binds a framebuffer whose only attachment is the scene's colour texture and sets the viewport to the scene size.
     * Returns false (nothing bound) when the scene has no colour texture or the framebuffer is incomplete.
     */
    public static boolean bindColorOnly(Scene scene) {
        if (scene.colorTexture() == 0) {
            return false;
        }
        if (colorFbo == 0) {
            colorFbo = GpuResources.framebuffer("scene colour (no depth)");
        }
        // Read and draw, so the scene framebuffer (whose depth the pass samples) is not left bound for reading.
        GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, colorFbo);
        // Attached again every time: a window resize deletes the main target's textures and creates new ones that
        // usually get the same ids, but this framebuffer would keep drawing into the deleted (invisible) one.
        GL30C.glFramebufferTexture2D(GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                scene.colorTexture(), 0);
        if (scene.colorTexture() != colorFboTexture) {
            colorFboTexture = scene.colorTexture();
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                AtmospheraLib.LOGGER.warn("Scene colour framebuffer incomplete: 0x{}", Integer.toHexString(status));
                colorFboTexture = 0;
                return false;
            }
        }
        GL11C.glViewport(0, 0, scene.width(), scene.height());
        return true;
    }

    private static int attachment(int attachment, int parameter) {
        return GL30C.glGetFramebufferAttachmentParameteri(GL30C.GL_READ_FRAMEBUFFER, attachment, parameter);
    }

    static void deleteShared() {
        if (colorFbo != 0) {
            GpuResources.deleteFramebuffer(colorFbo);
            colorFbo = 0;
            colorFboTexture = 0;
        }
        cached = null;
    }

    /** One colour texture with its framebuffer. */
    public static final class Target {
        private final String owner;
        private final int divisor;
        private final int internalFormat;
        private final boolean linear;
        private int texture;
        private int framebuffer;
        private int width;
        private int height;

        private Target(String owner, int divisor, int internalFormat, boolean linear) {
            this.owner = owner;
            this.divisor = divisor;
            this.internalFormat = internalFormat;
            this.linear = linear;
        }

        /** (Re)allocates to the scene size / divisor. Call before each use; cheap when nothing changed. */
        public boolean ensure(Scene scene) {
            int w = Math.max(1, (scene.width() + divisor - 1) / divisor);
            int h = Math.max(1, (scene.height() + divisor - 1) / divisor);
            if (texture != 0 && w == width && h == height) {
                return true;
            }
            release();
            width = w;
            height = h;
            texture = GpuResources.texture(owner);
            int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
            int filter = linear ? GL11C.GL_LINEAR : GL11C.GL_NEAREST;
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, filter);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internalFormat, w, h, 0, GL11C.GL_RGBA, GL11C.GL_UNSIGNED_BYTE,
                    (ByteBuffer) null);
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, 0);
            GL13C.glActiveTexture(previousUnit);
            GpuResources.textureBytes(texture, (long) w * h * bytesPerPixel(internalFormat));

            framebuffer = GpuResources.framebuffer(owner);
            int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                    texture, 0);
            int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
            GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
            if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                AtmospheraLib.LOGGER.error("Render target {} incomplete: 0x{}", owner, Integer.toHexString(status));
                release();
                return false;
            }
            return true;
        }

        /** Binds the framebuffer (read and draw, see {@link #bindColorOnly}) and sets the viewport. */
        public void bindWrite() {
            GL30C.glBindFramebuffer(GL30C.GL_FRAMEBUFFER, framebuffer);
            GL11C.glViewport(0, 0, width, height);
        }

        public int texture() {
            return texture;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public void release() {
            if (framebuffer != 0) {
                GpuResources.deleteFramebuffer(framebuffer);
                framebuffer = 0;
            }
            if (texture != 0) {
                GpuResources.deleteTexture(texture);
                texture = 0;
            }
            width = height = 0;
        }

        private static int bytesPerPixel(int format) {
            return switch (format) {
                case GL30C.GL_RGBA16F -> 8;
                case GL30C.GL_RGBA32F -> 16;
                case GL30C.GL_RG16F, GL30C.GL_R32F -> 4;
                case GL30C.GL_R16F -> 2;
                case GL30C.GL_RG32F -> 8;
                default -> 4;
            };
        }
    }
}
