package com.solarbiscuit.atmosphera.lib.gpu;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.nio.ByteBuffer;

/**
 * Fixed-size textures and their framebuffers (0.11.0 "Shared"): the allocation every feature mod wrote out by hand.
 * <p>
 * A texture is created on unit 15 (the last unit the lib's terrain samplers own), with its filter and wrap set for every
 * axis, no data (contents undefined until drawn or uploaded), and its memory recorded for {@code /atmosphera status}.
 * The active unit and unit 15's binding of that target are put back afterwards. Render thread only.
 */
public final class Textures {
    private Textures() {
    }

    /** A 2D texture of {@code width × height}; {@code filter} is used for minification and magnification. */
    public static int create2D(String owner, int width, int height, int internalFormat, int format, int type,
                               int filter, int wrap) {
        int texture = GpuResources.texture(owner);
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, filter);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, wrap);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, wrap);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, (ByteBuffer) null);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(texture, (long) width * height * bytesPerTexel(internalFormat));
        return texture;
    }

    /** A 3D texture of {@code width × height × depth}; {@code filter} is used for minification and magnification. */
    public static int create3D(String owner, int width, int height, int depth, int internalFormat, int format, int type,
                               int filter, int wrap) {
        int texture = GpuResources.texture(owner);
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE15);
        int previous = GL11C.glGetInteger(GL12C.GL_TEXTURE_BINDING_3D);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, texture);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MIN_FILTER, filter);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_MAG_FILTER, filter);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_S, wrap);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL11C.GL_TEXTURE_WRAP_T, wrap);
        GL11C.glTexParameteri(GL12C.GL_TEXTURE_3D, GL12C.GL_TEXTURE_WRAP_R, wrap);
        GL12C.glTexImage3D(GL12C.GL_TEXTURE_3D, 0, internalFormat, width, height, depth, 0, format, type,
                (ByteBuffer) null);
        GL11C.glBindTexture(GL12C.GL_TEXTURE_3D, previous);
        GL13C.glActiveTexture(previousUnit);
        GpuResources.textureBytes(texture, (long) width * height * depth * bytesPerTexel(internalFormat));
        return texture;
    }

    /**
     * A framebuffer drawing into {@code texture} (colour attachment 0, level 0), or 0 when the driver reports it
     * incomplete (logged with the owner; the framebuffer is deleted, the texture is left to the caller). The draw
     * framebuffer binding is put back.
     */
    public static int framebuffer(String owner, int texture) {
        int framebuffer = GpuResources.framebuffer(owner);
        int previous = GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, framebuffer);
        GL30C.glFramebufferTexture2D(GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D,
                texture, 0);
        GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        int status = GL30C.glCheckFramebufferStatus(GL30C.GL_DRAW_FRAMEBUFFER);
        GL30C.glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, previous);
        if (status != GL30C.GL_FRAMEBUFFER_COMPLETE) {
            AtmospheraLib.LOGGER.error("Framebuffer {} incomplete: 0x{}", owner, Integer.toHexString(status));
            GpuResources.deleteFramebuffer(framebuffer);
            return 0;
        }
        return framebuffer;
    }

    /**
     * Runs {@code body} with {@code texture} bound to {@code GL_TEXTURE_2D} on {@code unit} (0-based), then puts the
     * active unit and that unit's binding back. For parameter changes and uploads on an existing texture.
     */
    public static void with2D(int unit, int texture, Runnable body) {
        int previousUnit = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
        GL13C.glActiveTexture(GL13C.GL_TEXTURE0 + unit);
        int previous = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
        GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, texture);
        try {
            body.run();
        } finally {
            GL11C.glBindTexture(GL11C.GL_TEXTURE_2D, previous);
            GL13C.glActiveTexture(previousUnit);
        }
    }

    /** Bytes per texel of an internal format (for the status line's memory estimate; 4 when unknown). */
    public static int bytesPerTexel(int internalFormat) {
        return switch (internalFormat) {
            case GL30C.GL_R8 -> 1;
            case GL30C.GL_RG8, GL30C.GL_R16F, GL30C.GL_R16 -> 2;
            case GL11C.GL_RGB8 -> 3;
            case GL30C.GL_RGBA16F, GL30C.GL_RG32F, GL11C.GL_RGBA16 -> 8;
            case GL30C.GL_RGBA32F -> 16;
            case GL30C.GL_RGB16F -> 6;
            case GL30C.GL_RGB32F -> 12;
            default -> 4;
        };
    }
}
