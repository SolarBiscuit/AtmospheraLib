package com.solarbiscuit.atmosphera.lib.gpu;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every GL object AtmospheraLib (and the feature mods, through it) creates, with an owner label. Used for
 * {@code /atmosphera status} (live handle counts, to spot leaks) and to free everything at shutdown.
 * Render thread only.
 */
public final class GpuResources {
    public enum Type { BUFFER, TEXTURE, FRAMEBUFFER, PROGRAM, VERTEX_ARRAY, QUERY }

    private record Key(Type type, int handle) {
    }

    private static final Map<Key, String> LIVE = new LinkedHashMap<>();
    private static final Map<Integer, Long> TEXTURE_BYTES = new java.util.HashMap<>();

    private GpuResources() {
    }

    public static int buffer(String owner) {
        return track(Type.BUFFER, GL15C.glGenBuffers(), owner);
    }

    public static int texture(String owner) {
        return track(Type.TEXTURE, GL11C.glGenTextures(), owner);
    }

    public static int framebuffer(String owner) {
        return track(Type.FRAMEBUFFER, GL30C.glGenFramebuffers(), owner);
    }

    public static int vertexArray(String owner) {
        return track(Type.VERTEX_ARRAY, GL30C.glGenVertexArrays(), owner);
    }

    public static int query(String owner) {
        return track(Type.QUERY, GL15C.glGenQueries(), owner);
    }

    /** Registers a program created elsewhere (for example by {@link GlslPrograms}). */
    public static int program(int handle, String owner) {
        return track(Type.PROGRAM, handle, owner);
    }

    public static void deleteBuffer(int handle) {
        if (LIVE.remove(new Key(Type.BUFFER, handle)) != null) GL15C.glDeleteBuffers(handle);
        else untracked(Type.BUFFER, handle);
    }

    public static void deleteTexture(int handle) {
        TEXTURE_BYTES.remove(handle);
        if (LIVE.remove(new Key(Type.TEXTURE, handle)) != null) GL11C.glDeleteTextures(handle);
        else untracked(Type.TEXTURE, handle);
    }

    public static void deleteFramebuffer(int handle) {
        if (LIVE.remove(new Key(Type.FRAMEBUFFER, handle)) != null) GL30C.glDeleteFramebuffers(handle);
        else untracked(Type.FRAMEBUFFER, handle);
    }

    public static void deleteVertexArray(int handle) {
        if (LIVE.remove(new Key(Type.VERTEX_ARRAY, handle)) != null) GL30C.glDeleteVertexArrays(handle);
        else untracked(Type.VERTEX_ARRAY, handle);
    }

    public static void deleteQuery(int handle) {
        if (LIVE.remove(new Key(Type.QUERY, handle)) != null) GL15C.glDeleteQueries(handle);
        else untracked(Type.QUERY, handle);
    }

    public static void deleteProgram(int handle) {
        if (LIVE.remove(new Key(Type.PROGRAM, handle)) != null) GL20C.glDeleteProgram(handle);
        else untracked(Type.PROGRAM, handle);
    }

    /** Records the memory a texture created here holds (its levels and format), for the status VRAM line. */
    public static void textureBytes(int texture, long bytes) {
        TEXTURE_BYTES.put(texture, bytes);
    }

    /** Total recorded texture memory in bytes. */
    public static long totalTextureBytes() {
        long total = 0;
        for (long bytes : TEXTURE_BYTES.values()) {
            total += bytes;
        }
        return total;
    }

    /** Live handle count per type, for the status command. */
    public static Map<Type, Integer> counts() {
        Map<Type, Integer> counts = new LinkedHashMap<>();
        for (Key key : LIVE.keySet()) {
            counts.merge(key.type(), 1, Integer::sum);
        }
        return counts;
    }

    public static Map<String, Integer> byOwner() {
        Map<String, Integer> owners = new LinkedHashMap<>();
        for (String owner : LIVE.values()) {
            owners.merge(owner, 1, Integer::sum);
        }
        return owners;
    }

    /** Set by {@link #deleteAll}: mods still releasing their handles afterwards (the world unloads after it) are expected. */
    private static boolean closed;

    /** Frees whatever is still alive (shutdown). */
    public static void deleteAll() {
        closed = true;
        for (Key key : Map.copyOf(LIVE).keySet()) {
            switch (key.type()) {
                case BUFFER -> deleteBuffer(key.handle());
                case TEXTURE -> deleteTexture(key.handle());
                case FRAMEBUFFER -> deleteFramebuffer(key.handle());
                case PROGRAM -> deleteProgram(key.handle());
                case VERTEX_ARRAY -> deleteVertexArray(key.handle());
                case QUERY -> deleteQuery(key.handle());
            }
        }
    }

    private static final java.util.EnumSet<Type> WARNED = java.util.EnumSet.noneOf(Type.class);

    /**
     * 0.10.3: a delete of a handle this class does not track is skipped (it may belong to another mod, or be a
     * stale id already freed), but it means some code created a GL object without {@link GpuResources}, or deletes
     * one twice. Logged once per type, so such a leak or double free is not silent.
     */
    private static void untracked(Type type, int handle) {
        if (handle != 0 && !closed && WARNED.add(type)) {
            com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.warn(
                    "GpuResources: delete of untracked {} {} skipped (created outside GpuResources, or deleted twice)",
                    type, handle, new Throwable("caller"));
        }
    }

    private static int track(Type type, int handle, String owner) {
        LIVE.put(new Key(type, handle), owner);
        return handle;
    }
}
