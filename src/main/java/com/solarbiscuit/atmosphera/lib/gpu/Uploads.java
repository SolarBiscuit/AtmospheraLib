package com.solarbiscuit.atmosphera.lib.gpu;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL21C;

/**
 * Tight client-memory unpacking around a batch of texture uploads (0.3.23):
 * <pre>
 * Uploads.begin(4);
 * ... AtmosGl.texSubImage3D(...) per section ...
 * Uploads.end();
 * </pre>
 * {@link #begin} unbinds any pixel-unpack buffer and sets row length, skip rows, skip pixels, image height and skip
 * images to 0 and the alignment to the given value. Vanilla leaves row length and skips set after its own uploads,
 * and with them the driver reads past a small buffer.
 * <p>
 * {@link #end} puts back only the unpack buffer binding (one query per batch) and leaves the GL defaults (zeros,
 * alignment 4) instead of reading and restoring the previous values. Checked: vanilla's {@code NativeImage} and
 * NeoVoxy (model bakery, buffer uploads, occlusion traverser) set every unpack value they use before each upload.
 * Render thread only; batches do not nest.
 */
public final class Uploads {
    private static int savedPbo;
    private static boolean open;

    private Uploads() {
    }

    public static void begin(int alignment) {
        if (open) {
            throw new IllegalStateException("Uploads.begin without end");
        }
        open = true;
        savedPbo = AtmosGl.getInteger(GL21C.GL_PIXEL_UNPACK_BUFFER_BINDING);
        if (savedPbo != 0) {
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, 0);
        }
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_IMAGE_HEIGHT, 0);
        GL11C.glPixelStorei(GL12C.GL_UNPACK_SKIP_IMAGES, 0);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, alignment);
    }

    public static void end() {
        if (!open) {
            return;
        }
        open = false;
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, 4);
        if (savedPbo != 0) {
            GL21C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, savedPbo);
        }
    }
}
