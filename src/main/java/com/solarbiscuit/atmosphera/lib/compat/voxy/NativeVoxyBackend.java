package com.solarbiscuit.atmosphera.lib.compat.voxy;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * NeoVoxy backend: registers one {@code me.cortex.voxy.client.api.ext.LodShaderExtension} built as a reflection proxy,
 * so AtmospheraLib has no compile-time dependency on NeoVoxy and loads fine without it. Methods a newer API adds keep
 * their default implementation ({@link InvocationHandler#invokeDefault}).
 */
final class NativeVoxyBackend implements VoxyBridge.Backend {
    private static final String API = "me.cortex.voxy.client.api.ext.LodShaderExtensions";
    private static final String EXTENSION = "me.cortex.voxy.client.api.ext.LodShaderExtension";
    private static final int REQUIRE_OPAQUE_DEPTH = 1;
    private static final int REQUIRE_UNMERGED_FOLIAGE = 2;
    private static final int REQUIRE_SECTION_EMITTERS = 4;

    private final Class<?> api;
    //Material-only mode (text-patch backend on a NeoVoxy build): fills BlockModel._pad[0], adds no GLSL
    private boolean materialOnly;
    private final Method fadeStart;
    private final Method fadeEnd;
    private final Method vanillaDistance;
    private final Method shaderRebuild;

    private NativeVoxyBackend(Class<?> api) throws ReflectiveOperationException {
        this.api = api;
        this.fadeStart = api.getMethod("boundaryFadeStart");
        this.fadeEnd = api.getMethod("boundaryFadeEnd");
        this.vanillaDistance = api.getMethod("vanillaRenderDistanceBlocks");
        this.shaderRebuild = api.getMethod("requestShaderRebuild", String.class);
    }

    /** Registers the extension, or null when this Voxy has no (compatible) extension API. */
    static VoxyBridge.Backend tryRegister() throws ReflectiveOperationException {
        Class<?> api;
        Class<?> extension;
        try {
            api = Class.forName(API);
            extension = Class.forName(EXTENSION);
        } catch (ClassNotFoundException e) {
            return null;
        }
        int version = (int) api.getMethod("apiVersion").invoke(null);
        if (version < 1) {
            return null;
        }
        VoxyBridge.apiVersion = version;
        NativeVoxyBackend backend = new NativeVoxyBackend(api);
        Object proxy = Proxy.newProxyInstance(extension.getClassLoader(), new Class<?>[]{extension}, backend::invoke);
        api.getMethod("register", extension).invoke(null, proxy);
        return backend;
    }

    /**
     * Text-patch backend on NeoVoxy: registers an extension that only provides material words, so NeoVoxy writes them
     * into BlockModel._pad[0] for the patched shaders to read. False when there is no NeoVoxy API.
     */
    static boolean tryRegisterMaterialOnly() throws ReflectiveOperationException {
        Class<?> api;
        Class<?> extension;
        try {
            api = Class.forName(API);
            extension = Class.forName(EXTENSION);
        } catch (ClassNotFoundException e) {
            return false;
        }
        NativeVoxyBackend backend = new NativeVoxyBackend(api);
        backend.materialOnly = true;
        Object proxy = Proxy.newProxyInstance(extension.getClassLoader(), new Class<?>[]{extension}, backend::invoke);
        api.getMethod("register", extension).invoke(null, proxy);
        return true;
    }

    /** NeoVoxy's full renderer rebuild (shaders, models, meshes). */
    void requestRendererRebuild(String reason) {
        try {
            Class.forName(API).getMethod("requestRendererRebuild", String.class).invoke(null, reason);
        } catch (ReflectiveOperationException | LinkageError e) {
            AtmospheraLib.LOGGER.info("Could not ask NeoVoxy to rebuild its renderer ({}): {}", reason, e.toString());
        }
    }

    /** NeoVoxy's shader-only rebuild when its API exists; otherwise changes apply with the next renderer reload. */
    static void requestShaderRebuildIfAvailable(String reason) {
        try {
            Class.forName(API).getMethod("requestShaderRebuild", String.class).invoke(null, reason);
        } catch (ReflectiveOperationException | LinkageError e) {
            AtmospheraLib.LOGGER.info("Atmosphera LOD features changed; reload Voxy's renderer to apply ({})", reason);
        }
    }

    private Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        switch (method.getName()) {
            case "id":
                return this.materialOnly ? "atmosphera-materials" : "atmosphera";
            case "materialBitsFor":
                return VoxyBridge.materialBitsFor((BlockState) args[0]);
            case "glslCommon":
                return this.materialOnly ? "" : VoxyHostGlsl.common(AtmosState.current());
            case "glslVertex":
                return this.materialOnly ? "" : VoxyHostGlsl.vertex();
            case "glslFragment":
                return this.materialOnly ? "" : VoxyHostGlsl.fragment();
            case "requires":
                if (this.materialOnly) return 0;
                return (VoxyBridge.needsOpaqueDepth() ? REQUIRE_OPAQUE_DEPTH : 0)
                        | (VoxyBridge.needsUnmergedFoliage() ? REQUIRE_UNMERGED_FOLIAGE : 0)
                        | (VoxyBridge.needsSectionEmitters() ? REQUIRE_SECTION_EMITTERS : 0);
            case "emitterWordFor":
                return this.materialOnly ? 0 : VoxyBridge.emitterWordFor((BlockState) args[0]);
            case "sectionEmitters":
                if (!this.materialOnly) {
                    VoxyBridge.sectionEmitters((int) args[0], (int) args[1], (int) args[2], (int) args[3],
                            (long[]) args[4], (int) args[5]);
                }
                return null;
            case "beforeLodDraw":
                VoxyBridge.beforeLodDraw();
                return null;
            case "animating":
                return VoxyBridge.animating();
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            case "toString":
                return "AtmospheraLib LOD shader extension";
            default:
                if (method.isDefault()) {
                    return InvocationHandler.invokeDefault(proxy, method, args);
                }
                throw new UnsupportedOperationException(method.toString());
        }
    }

    @Override
    public String name() {
        return "NeoVoxy LOD shader extension API";
    }

    @Override
    public void requestShaderRebuild(String reason) {
        try {
            this.shaderRebuild.invoke(null, reason);
        } catch (ReflectiveOperationException e) {
            AtmospheraLib.LOGGER.warn("Could not ask NeoVoxy to recompile its LOD shaders: {}", e.toString());
        }
    }

    @Override
    public float fadeStart() {
        return call(this.fadeStart);
    }

    @Override
    public float fadeEnd() {
        return call(this.fadeEnd);
    }

    @Override
    public float vanillaDistance() {
        return call(this.vanillaDistance);
    }

    private static float call(Method method) {
        try {
            return (float) method.invoke(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0f;
        }
    }
}
