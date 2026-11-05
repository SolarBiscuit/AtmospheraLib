package com.solarbiscuit.atmosphera.lib.compat.voxy;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;

import java.lang.reflect.Method;

/**
 * Text-patch backend ({@link VoxyTextPatch}) for Voxy builds without NeoVoxy's extension API, such as the official
 * Voxy. The {@code ShaderLoader.parse} mixin hands it Voxy's LOD shader sources. Both stages are patched together
 * and verified together before anything is returned: the original pair and the patched pair are compiled and linked
 * (opaque and translucent) on the render thread, and if the original links but the patched one does not, both stay
 * unpatched and one WARN is logged. LODs never break because of this backend.
 * <p>
 * Capabilities: vertex offsets and surface shading. The material word is read from {@code BlockModel._pad[0]}, which
 * the host must fill (NeoVoxy does through a material-only extension; an upstream port needs a one-line hook calling
 * {@link VoxyBridge#materialBitsFor}). No opaque depth (water thickness falls back to 3 blocks), no column anchors,
 * merged quads, no transition band (the vanilla edge is used).
 */
final class TextPatchVoxyBackend implements VoxyBridge.Backend {
    private static final String LOADER = "me.cortex.voxy.client.core.gl.shader.ShaderLoader";
    private static final String TEST_DEFINES = """
            #define UP_FACE_TINT 1.0
            #define DOWN_FACE_TINT 0.5
            #define Z_AXIS_FACE_TINT 0.8
            #define X_AXIS_FACE_TINT 0.6
            #define NO_SHADE_FACE_TINT 1.0
            """;

    private static volatile TextPatchVoxyBackend instance;
    private static String problem = "not tried";
    private static final ThreadLocal<Boolean> INSIDE = ThreadLocal.withInitial(() -> false);

    private final boolean material;
    private final Method parse;
    private long verdictKey;
    private String patchedVertex;
    private String patchedFragment;
    private String loggedProblem;

    private TextPatchVoxyBackend(boolean material, Method parse) {
        this.material = material;
        this.parse = parse;
    }

    static VoxyBridge.Backend tryEnable() {
        if (!AtmosCompat.isLoaded("voxy")) {
            problem = "Voxy not installed";
            return null;
        }
        Method parse;
        try {
            parse = Class.forName(LOADER).getMethod("parse", String.class);
        } catch (ReflectiveOperationException | LinkageError e) {
            problem = "Voxy's ShaderLoader.parse(String) not found (" + e + ")";
            return null;
        }
        boolean material = false;
        try {
            material = NativeVoxyBackend.tryRegisterMaterialOnly();
        } catch (Throwable t) {
            AtmospheraLib.LOGGER.debug("No material provider for the Voxy text patch: {}", t.toString());
        }
        instance = new TextPatchVoxyBackend(material, parse);
        return instance;
    }

    static String problem() {
        return problem;
    }

    /** Called by the ShaderLoader mixin with every source Voxy loads. Render thread. */
    public static String apply(String id, String source) {
        TextPatchVoxyBackend backend = instance;
        if (backend == null || source == null || INSIDE.get()) {
            return source;
        }
        if (!VoxyTextPatch.VERTEX_ID.equals(id) && !VoxyTextPatch.FRAGMENT_ID.equals(id)) {
            return source;
        }
        INSIDE.set(true);
        try {
            return backend.patched(id, source);
        } catch (Throwable t) {
            backend.logOnce("text patch failed: " + t);
            return source;
        } finally {
            INSIDE.set(false);
        }
    }

    private String patched(String id, String source) throws ReflectiveOperationException {
        String vertexBody = VoxyHostGlsl.vertex();
        String fragmentBody = VoxyHostGlsl.fragment();
        if (vertexBody.isEmpty() && fragmentBody.isEmpty()) {
            return source;
        }
        String vertex = VoxyTextPatch.VERTEX_ID.equals(id) ? source : (String) this.parse.invoke(null, VoxyTextPatch.VERTEX_ID);
        String fragment = VoxyTextPatch.FRAGMENT_ID.equals(id) ? source : (String) this.parse.invoke(null, VoxyTextPatch.FRAGMENT_ID);
        VoxyTextPatch.Extension ext = new VoxyTextPatch.Extension(VoxyHostGlsl.common(AtmosState.current()), vertexBody, fragmentBody);
        long key = ((long) vertex.hashCode() * 31 + fragment.hashCode()) * 31 + (ext.common() + vertexBody + fragmentBody).hashCode();
        if (key != this.verdictKey) {
            this.verdictKey = key;
            this.patchedVertex = null;
            this.patchedFragment = null;
            String pv = VoxyTextPatch.patch(VoxyTextPatch.VERTEX_ID, vertex, ext);
            String pf = VoxyTextPatch.patch(VoxyTextPatch.FRAGMENT_ID, fragment, ext);
            if (pv == null || pf == null) {
                String why = pv == null ? VoxyTextPatch.problem(VoxyTextPatch.VERTEX_ID, vertex) : VoxyTextPatch.problem(VoxyTextPatch.FRAGMENT_ID, fragment);
                logOnce("anchor missing in Voxy's LOD shaders: " + why);
            } else {
                String failure = verify(vertex, fragment, pv, pf);
                if (failure == null) {
                    this.patchedVertex = pv;
                    this.patchedFragment = pf;
                    AtmospheraLib.LOGGER.info("Voxy LOD shaders patched for Atmosphera (text patch, material word {})",
                            this.material ? "from NeoVoxy" : "not provided by this Voxy");
                } else {
                    logOnce(failure);
                }
            }
        }
        String result = VoxyTextPatch.VERTEX_ID.equals(id) ? this.patchedVertex : this.patchedFragment;
        return result == null ? source : result;
    }

    /** Null when the patched pair compiles and links wherever the original pair does. */
    private static String verify(String vertex, String fragment, String patchedVertex, String patchedFragment) {
        for (boolean translucent : new boolean[]{false, true}) {
            String defines = TEST_DEFINES + (translucent ? "#define TRANSLUCENT\n" : "");
            String original = link(withDefines(vertex, defines), withDefines(fragment, defines));
            if (original != null) {
                return "cannot verify the patch: Voxy's own LOD shaders do not build standalone (" + original + ")";
            }
            String patched = link(withDefines(patchedVertex, defines), withDefines(patchedFragment, defines));
            if (patched != null) {
                return "patched LOD shaders do not build" + (translucent ? " (translucent)" : "") + ": " + patched;
            }
        }
        return null;
    }

    private static String withDefines(String source, String defines) {
        int line = source.indexOf('\n');
        return source.substring(0, line + 1) + defines + source.substring(line + 1);
    }

    private static String link(String vertex, String fragment) {
        int vs = GL20C.glCreateShader(GL20C.GL_VERTEX_SHADER);
        int fs = GL20C.glCreateShader(GL20C.GL_FRAGMENT_SHADER);
        int program = GL20C.glCreateProgram();
        try {
            GL20C.glShaderSource(vs, vertex);
            GL20C.glCompileShader(vs);
            if (GL20C.glGetShaderi(vs, GL20C.GL_COMPILE_STATUS) != GL11C.GL_TRUE) {
                return "vertex: " + firstLine(GL20C.glGetShaderInfoLog(vs));
            }
            GL20C.glShaderSource(fs, fragment);
            GL20C.glCompileShader(fs);
            if (GL20C.glGetShaderi(fs, GL20C.GL_COMPILE_STATUS) != GL11C.GL_TRUE) {
                return "fragment: " + firstLine(GL20C.glGetShaderInfoLog(fs));
            }
            GL20C.glAttachShader(program, vs);
            GL20C.glAttachShader(program, fs);
            GL20C.glLinkProgram(program);
            if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL11C.GL_TRUE) {
                return "link: " + firstLine(GL20C.glGetProgramInfoLog(program));
            }
            return null;
        } finally {
            GL20C.glDeleteProgram(program);
            GL20C.glDeleteShader(vs);
            GL20C.glDeleteShader(fs);
        }
    }

    private static String firstLine(String log) {
        String trimmed = log.strip();
        int nl = trimmed.indexOf('\n');
        return nl < 0 ? trimmed : trimmed.substring(0, nl);
    }

    private void logOnce(String message) {
        if (!message.equals(this.loggedProblem)) {
            this.loggedProblem = message;
            problem = message;
            AtmospheraLib.LOGGER.warn("Atmosphera LOD shading (Voxy text patch) is off: {}. Voxy renders as usual.", message);
        }
    }

    @Override
    public String name() {
        return "Voxy LOD shader text patch" + (this.material ? "" : " (no material word: shading needs the host to fill BlockModel._pad[0])");
    }

    @Override
    public void requestShaderRebuild(String reason) {
        NativeVoxyBackend.requestShaderRebuildIfAvailable(reason);
    }

    @Override
    public float fadeStart() {
        return vanillaDistance();
    }

    @Override
    public float fadeEnd() {
        return vanillaDistance();
    }

    @Override
    public float vanillaDistance() {
        return Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0f;
    }
}
