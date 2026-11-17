package com.solarbiscuit.atmosphera.lib.gpu;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.lwjgl.opengl.EXTTextureFilterAnisotropic;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/**
 * Large images streamed into textures (0.11.0 "Shared"; the moon map, nebulae and night-sky photographs each had a
 * copy): decoded off the render thread with stb_image, halved while larger than a detail size, uploaded in strips of
 * rows over several frames so no frame stalls, then mipmapped with up to 8x anisotropy.
 * <p>
 * {@link #decode} and {@link #halve} run on any thread; {@link #allocate} and {@link #uploadRows} on the render
 * thread. A {@link Decoded} holds native memory until {@link Decoded#free}.
 */
public final class StreamedImage {
    private StreamedImage() {
    }

    /** A decoded image: rows top to bottom, {@code channels} bytes per pixel. */
    public static final class Decoded {
        public final ByteBuffer pixels;
        public final int width;
        public final int height;
        public final int channels;
        private final boolean stb;

        Decoded(ByteBuffer pixels, int width, int height, int channels, boolean stb) {
            this.pixels = pixels;
            this.width = width;
            this.height = height;
            this.channels = channels;
            this.stb = stb;
        }

        /** Frees the pixels; the image must not be used afterwards. */
        public void free() {
            if (stb) {
                STBImage.stbi_image_free(pixels);
            } else {
                MemoryUtil.memFree(pixels);
            }
        }
    }

    /**
     * Decodes a JPEG or PNG resource to {@code channels} channels and halves it while its larger side exceeds
     * {@code maxSide}. Returns null (logged as a warning naming {@code what}) when it is missing or unreadable.
     */
    public static Decoded decode(ResourceManager manager, ResourceLocation id, int channels, int maxSide, Logger logger,
                                 String what) {
        try {
            Resource resource = manager.getResource(id).orElse(null);
            if (resource == null) {
                logger.warn("[Atmosphera] The {} {} is missing.", what, id);
                return null;
            }
            byte[] bytes;
            try (InputStream in = resource.open()) {
                bytes = in.readAllBytes();
            }
            ByteBuffer file = MemoryUtil.memAlloc(bytes.length);
            Decoded image;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                file.put(bytes).flip();
                IntBuffer w = stack.mallocInt(1);
                IntBuffer h = stack.mallocInt(1);
                IntBuffer comp = stack.mallocInt(1);
                ByteBuffer pixels = STBImage.stbi_load_from_memory(file, w, h, comp, channels);
                if (pixels == null) {
                    logger.warn("[Atmosphera] Could not read the {} {}: {}", what, id, STBImage.stbi_failure_reason());
                    return null;
                }
                image = new Decoded(pixels, w.get(0), h.get(0), channels, true);
            } finally {
                MemoryUtil.memFree(file);
            }
            try {
                while (Math.max(image.width, image.height) > maxSide) {
                    image = halve(image);
                }
            } catch (Throwable e) {
                // 0.11.1: the image being halved is freed (it leaked when halving ran out of memory).
                image.free();
                throw e;
            }
            return image;
        } catch (Throwable e) {
            logger.warn("[Atmosphera] Could not load the {} {}", what, id, e);
            return null;
        }
    }

    /** A 2x2 box filter per channel (odd edges reuse their last row or column); frees {@code in}. */
    public static Decoded halve(Decoded in) {
        int c = in.channels;
        int w = Math.max(1, in.width / 2);
        int h = Math.max(1, in.height / 2);
        ByteBuffer out = MemoryUtil.memAlloc(w * h * c);
        ByteBuffer src = in.pixels;
        int stride = in.width * c;
        for (int y = 0; y < h; y++) {
            int row0 = (2 * y) * stride;
            int row1 = Math.min(2 * y + 1, in.height - 1) * stride;
            for (int x = 0; x < w; x++) {
                int a = row0 + 2 * c * x;
                int b = Math.min(a + c, row0 + stride - c);
                int d0 = row1 + 2 * c * x;
                int d1 = Math.min(d0 + c, row1 + stride - c);
                for (int k = 0; k < c; k++) {
                    int sum = (src.get(a + k) & 0xFF) + (src.get(b + k) & 0xFF) + (src.get(d0 + k) & 0xFF)
                            + (src.get(d1 + k) & 0xFF);
                    out.put((y * w + x) * c + k, (byte) ((sum + 2) >> 2));
                }
            }
        }
        in.free();
        return new Decoded(out, w, h, c, false);
    }

    /**
     * An empty texture the size of {@code image} (linear, clamped; mipmaps follow when the last rows arrive), bound
     * on {@code unit} only while it is set up. Its memory is recorded with mipmaps (×4/3).
     */
    public static int allocate(String owner, Decoded image, int unit, int internalFormat, int format) {
        int texture = GpuResources.texture(owner);
        Textures.with2D(unit, texture, () -> {
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_LINEAR);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
            GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internalFormat, image.width, image.height, 0, format,
                    GL11C.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        });
        GpuResources.textureBytes(texture, (long) image.width * image.height * image.channels * 4 / 3);
        return texture;
    }

    /**
     * Uploads up to {@code maxRows} rows of {@code image} from row {@code fromRow} into {@code texture} (bound on
     * {@code unit} meanwhile) and returns how many it uploaded. After the last row it builds the mipmaps and turns on
     * trilinear filtering and up to 8x anisotropy.
     */
    public static int uploadRows(int texture, Decoded image, int fromRow, int maxRows, int unit, int format) {
        int rows = Math.min(maxRows, image.height - fromRow);
        if (rows <= 0) {
            return 0;
        }
        Textures.with2D(unit, texture, () -> {
            Uploads.begin(1);
            try {
                ByteBuffer strip = MemoryUtil.memSlice(image.pixels, fromRow * image.width * image.channels,
                        rows * image.width * image.channels);
                GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, 0, 0, fromRow, image.width, rows, format,
                        GL11C.GL_UNSIGNED_BYTE, strip);
            } finally {
                Uploads.end();
            }
            if (fromRow + rows >= image.height) {
                GL30C.glGenerateMipmap(GL11C.GL_TEXTURE_2D);
                GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR_MIPMAP_LINEAR);
                if (GL.getCapabilities().GL_EXT_texture_filter_anisotropic) {
                    float max = GL11C.glGetFloat(EXTTextureFilterAnisotropic.GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT);
                    GL11C.glTexParameterf(GL11C.GL_TEXTURE_2D, EXTTextureFilterAnisotropic.GL_TEXTURE_MAX_ANISOTROPY_EXT,
                            Math.min(8f, max));
                }
            }
        });
        return rows;
    }
}
