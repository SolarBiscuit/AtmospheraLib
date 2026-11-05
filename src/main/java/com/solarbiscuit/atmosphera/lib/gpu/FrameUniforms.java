package com.solarbiscuit.atmosphera.lib.gpu;

import com.mojang.blaze3d.systems.RenderSystem;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code AtmosFrame} std140 uniform block shared by every patched program: one small buffer, written once per
 * frame, bound at a high binding point so it never collides with other mods' blocks.
 * <p>
 * The first slots are the library's own (time, camera, wind, sun, fog, sky, screen, projection, flags). Feature mods
 * add vec4 slots with {@link #vec4} during mod construction; the layout is fixed at client setup.
 * Render thread only (except registration).
 */
public final class FrameUniforms {
    /** Fills one vec4 slot; called on the render thread once per frame. */
    @FunctionalInterface
    public interface Vec4Source {
        void write(float[] out);
    }

    private record Slot(String name, String comment, Vec4Source source) {
    }

    /** World positions are wrapped at this period so they stay precise in a float. */
    public static final double WRAP = 4096.0;
    private static final int MAX_SLOTS = 32;

    private static final List<Slot> SLOTS = new ArrayList<>();
    private static List<Slot> frozen;

    private static int buffer;
    private static int binding = -1;
    private static FloatBuffer staging;
    private static final float[] SCRATCH = new float[4];
    private static final WindVector WIND = new WindVector();
    private static long lastFrame = -1;
    private static float m22;
    private static float m32;
    //atmos_Lod writer, set by compat.voxy.VoxyBridge while LODs draw Atmosphera shading (the slot stays zero otherwise)
    private static volatile Vec4Source lodSource;

    static {
        lib("atmos_Time", "x: real seconds (wraps hourly), y: world seconds (smoothed AtmosClock, pauses with the game), z: day fraction, w: rain", FrameUniforms::writeTime);
        lib("atmos_CameraPos", "xyz: camera position wrapped at 4096, w: thunder", FrameUniforms::writeCamera);
        lib("atmos_Wind", "xy: direction, z: strength 0..1, w: gust 0..1", FrameUniforms::writeWind);
        lib("atmos_SunDir", "xyz: direction towards the sun (world), w: sky brightness 0..1", FrameUniforms::writeSun);
        lib("atmos_FogColor", "rgba: current shader fog colour", out -> {
            float[] fog = RenderSystem.getShaderFogColor();
            out[0] = fog[0];
            out[1] = fog[1];
            out[2] = fog[2];
            out[3] = fog[3];
        });
        lib("atmos_SkyColor", "rgb: sky colour at the camera", FrameUniforms::writeSky);
        lib("atmos_Screen", "x: 1/width, y: 1/height, z: width, w: height", out -> {
            var target = Minecraft.getInstance().getMainRenderTarget();
            out[0] = 1f / Math.max(1, target.width);
            out[1] = 1f / Math.max(1, target.height);
            out[2] = target.width;
            out[3] = target.height;
        });
        lib("atmos_Proj", "x: proj[2][2], y: proj[3][2] (for atmos_linearDepth)", out -> {
            out[0] = m22;
            out[1] = m32;
            out[2] = 0f;
            out[3] = 0f;
        });
        lib("atmos_Flags", "x: scene depth valid, y: scene colour valid, z: camera in water, w: camera in lava", FrameUniforms::writeFlags);
        lib("atmos_Lod", "x: LOD transition start, y: transition end (blocks from the camera), z: vanilla render distance (blocks), w: 1 while Voxy LODs draw Atmosphera shading", out -> {
            Vec4Source source = lodSource;
            if (source != null) {
                source.write(out);
            }
        });
    }

    /** Writer of the {@code atmos_Lod} slot (compat.voxy.VoxyBridge); null leaves it zero. */
    public static void setLodSource(Vec4Source source) {
        lodSource = source;
    }

    private FrameUniforms() {
    }

    private static void lib(String name, String comment, Vec4Source source) {
        SLOTS.add(new Slot(name, comment, source));
    }

    /**
     * Adds a vec4 to the AtmosFrame block. {@code glslName} must be unique, for example {@code torrente_WaterColor}.
     * The source is called once per frame even when the owning feature is off, so keep it cheap.
     */
    public static synchronized void vec4(String glslName, String comment, Vec4Source source) {
        if (frozen != null) {
            throw new IllegalStateException("Atmosphera frame uniforms must be registered during mod construction");
        }
        if (SLOTS.size() >= MAX_SLOTS) {
            throw new IllegalStateException("AtmosFrame is full (" + MAX_SLOTS + " vec4 slots)");
        }
        for (Slot slot : SLOTS) {
            if (slot.name().equals(glslName)) {
                throw new IllegalArgumentException("Duplicate AtmosFrame member " + glslName);
            }
        }
        SLOTS.add(new Slot(glslName, comment, source));
    }

    public static synchronized void freeze() {
        if (frozen == null) {
            frozen = List.copyOf(SLOTS);
        }
    }

    public static int slotCount() {
        return frozen != null ? frozen.size() : SLOTS.size();
    }

    /** GLSL declaration of the block (identical in both stages). */
    public static synchronized String glslBlock() {
        return glslBlock("layout(std140)");
    }

    /**
     * The block with an explicit binding, for GL 4.2+ programs that never call glUniformBlockBinding (Voxy's LOD
     * shaders). The buffer stays bound at {@link #binding()} after {@link #update}. Render thread.
     */
    public static synchronized String glslBlockWithBinding() {
        return glslBlock("layout(std140, binding = " + binding() + ")");
    }

    private static String glslBlock(String layout) {
        freeze();
        StringBuilder out = new StringBuilder(layout).append(" uniform AtmosFrame {\n");
        for (Slot slot : frozen) {
            out.append("    vec4 ").append(slot.name()).append("; // ").append(slot.comment()).append('\n');
        }
        return out.append("};\n").toString();
    }

    /** Binding point the AtmosFrame block of every patched program is attached to. Render thread. */
    public static int binding() {
        if (binding < 0) {
            binding = Math.max(0, GL11C.glGetInteger(GL31C.GL_MAX_UNIFORM_BUFFER_BINDINGS) - 1);
        }
        return binding;
    }

    /** Remembers the terrain projection (needed by atmos_linearDepth). Called per terrain pass. */
    public static void setProjection(Matrix4fc projection) {
        m22 = projection.m22();
        m32 = projection.m32();
    }

    /** Writes and binds the block once per frame; later calls in the same frame only rebind. */
    public static void update(long frame) {
        if (frame == lastFrame && buffer != 0) {
            GL31C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, binding(), buffer);
            return;
        }
        lastFrame = frame;
        List<Slot> slots = frozen;
        if (slots == null) {
            return;
        }
        if (buffer == 0) {
            buffer = GpuResources.buffer("AtmosFrame UBO");
            staging = MemoryUtil.memAllocFloat(slots.size() * 4);
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, buffer);
            GL15C.glBufferData(GL31C.GL_UNIFORM_BUFFER, slots.size() * 16L, GL15C.GL_DYNAMIC_DRAW);
            GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
        }
        staging.clear();
        for (Slot slot : slots) {
            SCRATCH[0] = SCRATCH[1] = SCRATCH[2] = SCRATCH[3] = 0f;
            try {
                slot.source().write(SCRATCH);
            } catch (RuntimeException e) {
                // A broken source must not take terrain rendering down; its slot stays zero.
            }
            staging.put(SCRATCH);
        }
        staging.flip();
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, buffer);
        GL15C.glBufferSubData(GL31C.GL_UNIFORM_BUFFER, 0L, staging);
        GL15C.glBindBuffer(GL31C.GL_UNIFORM_BUFFER, 0);
        GL31C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, binding(), buffer);
    }

    public static void delete() {
        if (buffer != 0) {
            GpuResources.deleteBuffer(buffer);
            buffer = 0;
        }
        if (staging != null) {
            MemoryUtil.memFree(staging);
            staging = null;
        }
        lastFrame = -1;
    }

    private static float partialTick() {
        return Minecraft.getInstance().getFrameTime();
    }

    private static void writeTime(float[] out) {
        ClientLevel level = Minecraft.getInstance().level;
        out[0] = (float) ((System.nanoTime() / 1.0e9) % 3600.0);
        if (level != null) {
            float partial = partialTick();
            // Smoothed world clock: raw game time races when an integrated server catches up (see AtmosClock).
            out[1] = (float) (com.solarbiscuit.atmosphera.lib.wind.AtmosClock.worldSeconds(level, partial) % 3600.0);
            out[2] = (float) ((level.getDayTime() % 24000L) / 24000.0);
            out[3] = level.getRainLevel(partial);
        }
    }

    private static void writeCamera(float[] out) {
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 pos = camera.getPosition();
        out[0] = (float) (pos.x - Math.floor(pos.x / WRAP) * WRAP);
        out[1] = (float) pos.y;
        out[2] = (float) (pos.z - Math.floor(pos.z / WRAP) * WRAP);
        ClientLevel level = Minecraft.getInstance().level;
        out[3] = level != null ? level.getThunderLevel(partialTick()) : 0f;
    }

    private static void writeWind(float[] out) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Wind.global(level, partialTick(), WIND);
        out[0] = WIND.dirX;
        out[1] = WIND.dirZ;
        out[2] = WIND.strength;
        out[3] = WIND.gust;
    }

    private static void writeSun(float[] out) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        float partial = partialTick();
        float angle = level.getSunAngle(partial);
        // Vanilla sun path: rises in the east (+x), crosses overhead, sets in the west.
        out[0] = (float) -Math.sin(angle);
        out[1] = (float) Math.cos(angle);
        out[2] = 0f;
        out[3] = 1f - level.getSkyDarken(partial) / 15f;
    }

    private static void writeSky(float[] out) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        Vec3 sky = level.getSkyColor(Minecraft.getInstance().gameRenderer.getMainCamera().getPosition(), partialTick());
        out[0] = (float) sky.x;
        out[1] = (float) sky.y;
        out[2] = (float) sky.z;
        out[3] = 1f;
    }

    private static void writeFlags(float[] out) {
        out[0] = SceneTextures.isDepthValid() ? 1f : 0f;
        out[1] = SceneTextures.isColorValid() ? 1f : 0f;
        FogType fog = Minecraft.getInstance().gameRenderer.getMainCamera().getFluidInCamera();
        out[2] = fog == FogType.WATER ? 1f : 0f;
        out[3] = fog == FogType.LAVA ? 1f : 0f;
    }
}
