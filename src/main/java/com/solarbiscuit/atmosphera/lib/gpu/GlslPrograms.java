package com.solarbiscuit.atmosphera.lib.gpu;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;

/**
 * Small helpers for the feature mods' own GL programs (post passes): compile from source strings with logged errors,
 * and draw a fullscreen triangle without any vertex buffer. The {@code AtmosFrame} block is attached automatically
 * when the program declares it. Render thread only.
 */
public final class GlslPrograms {
    /** Vertex stage for {@link #drawFullscreen()}: outputs {@code v_Uv} in 0..1. */
    public static final String FULLSCREEN_VERTEX = """
            #version 330 core
            out vec2 v_Uv;
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                v_Uv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    /**
     * GLSL 4.60 vertex stage for {@link #drawFullscreen()} ({@code v_Uv} in 0..1), for programs built with
     * {@link #header460()}. Only for features registered with {@code requiresGl(4, 6)}.
     */
    public static final String FULLSCREEN_VERTEX_460 = FULLSCREEN_VERTEX.replace("#version 330 core", "#version 460 core");

    private static int emptyVao;

    private GlslPrograms() {
    }

    /** Compiles and links; returns 0 (and logs the GLSL error) on failure. */
    public static int compile(String owner, String vertexSource, String fragmentSource) {
        int vs = shader(GL20C.GL_VERTEX_SHADER, owner, vertexSource);
        int fs = shader(GL20C.GL_FRAGMENT_SHADER, owner, fragmentSource);
        if (vs == 0 || fs == 0) {
            if (vs != 0) GL20C.glDeleteShader(vs);
            if (fs != 0) GL20C.glDeleteShader(fs);
            return 0;
        }
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vs);
        GL20C.glAttachShader(program, fs);
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, vs);
        GL20C.glDetachShader(program, fs);
        GL20C.glDeleteShader(vs);
        GL20C.glDeleteShader(fs);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            AtmospheraLib.LOGGER.error("Linking {} failed: {}", owner, GL20C.glGetProgramInfoLog(program));
            GL20C.glDeleteProgram(program);
            return 0;
        }
        int block = org.lwjgl.opengl.GL31C.glGetUniformBlockIndex(program, "AtmosFrame");
        if (block != org.lwjgl.opengl.GL31C.GL_INVALID_INDEX) {
            org.lwjgl.opengl.GL31C.glUniformBlockBinding(program, block, FrameUniforms.binding());
        }
        return GpuResources.program(program, owner);
    }

    /** {@code #version 330 core} plus the AtmosFrame block, for prepending to post-pass fragment sources. */
    public static String header() {
        return "#version 330 core\n" + FrameUniforms.glslBlock();
    }

    /**
     * {@code #version 460 core} plus the AtmosFrame block with an explicit binding, for GL 4.6 post passes (samplers
     * may use {@code layout(binding = N)}, and {@code textureGather}, {@code textureQueryLevels} and the other 4.x
     * built-ins are available).
     */
    public static String header460() {
        return "#version 460 core\n" + FrameUniforms.glslBlockWithBinding();
    }

    /** Draws one triangle covering the viewport (use with {@link #FULLSCREEN_VERTEX}). Restores the VAO binding. */
    public static void drawFullscreen() {
        if (emptyVao == 0) {
            emptyVao = GpuResources.vertexArray("fullscreen triangle");
        }
        if (GlStateSnapshot.openCount() > 0) {
            // Inside a snapshot (every post pass): its restore puts the vertex array back (0.3.23).
            GL30C.glBindVertexArray(emptyVao);
            GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
            return;
        }
        int previous = AtmosGl.getInteger(GL30C.GL_VERTEX_ARRAY_BINDING);
        GL30C.glBindVertexArray(emptyVao);
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, 0, 3);
        GL30C.glBindVertexArray(previous);
    }

    static void deleteShared() {
        if (emptyVao != 0) {
            GpuResources.deleteVertexArray(emptyVao);
            emptyVao = 0;
        }
    }

    private static int shader(int type, String owner, String source) {
        int shader = GL20C.glCreateShader(type);
        GL20C.glShaderSource(shader, source);
        GL20C.glCompileShader(shader);
        if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            AtmospheraLib.LOGGER.error("Compiling {} ({}) failed: {}", owner,
                    type == GL20C.GL_VERTEX_SHADER ? "vertex" : "fragment", GL20C.glGetShaderInfoLog(shader));
            GL20C.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }
}
