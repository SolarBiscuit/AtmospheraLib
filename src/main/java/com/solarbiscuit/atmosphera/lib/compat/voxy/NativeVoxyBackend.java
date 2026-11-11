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
    /** API 4: column tops per built LOD section. */
    private static final int REQUIRE_SECTION_COLUMNS = 16;

    private final Class<?> api;
    //Material-only mode (text-patch backend on a NeoVoxy build): fills BlockModel._pad[0], adds no GLSL
    private boolean materialOnly;
    // Per-frame reads go through MethodHandles typed to ()float (0.3.22): no Method.invoke, no boxing.
    private final java.lang.invoke.MethodHandle fadeStart;
    private final java.lang.invoke.MethodHandle fadeEnd;
    private final java.lang.invoke.MethodHandle vanillaDistance;
    private final Method shaderRebuild;

    private NativeVoxyBackend(Class<?> api) throws ReflectiveOperationException {
        this.api = api;
        this.fadeStart = floatGetter(api.getMethod("boundaryFadeStart"));
        this.fadeEnd = floatGetter(api.getMethod("boundaryFadeEnd"));
        this.vanillaDistance = floatGetter(api.getMethod("vanillaRenderDistanceBlocks"));
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
                        | (VoxyBridge.needsSectionEmitters() ? REQUIRE_SECTION_EMITTERS : 0)
                        | (VoxyBridge.needsSectionColumns() ? REQUIRE_SECTION_COLUMNS : 0);
            case "emitterWordFor":
                return this.materialOnly ? 0 : VoxyBridge.emitterWordFor((BlockState) args[0]);
            case "sectionEmitters":
                if (!this.materialOnly) {
                    VoxyBridge.sectionEmitters((int) args[0], (int) args[1], (int) args[2], (int) args[3],
                            (long[]) args[4], (int) args[5]);
                }
                return null;
            case "sectionColumns":
                if (!this.materialOnly) {
                    VoxyBridge.sectionColumns((int) args[0], (int) args[1], (int) args[2], (int) args[3],
                            (short[]) args[4], (short[]) args[5]);
                }
                return null;
            case "glslFog":
                return this.materialOnly ? "" : VoxyBridge.fogGlsl();
            case "fogData":
                if (!this.materialOnly) {
                    VoxyBridge.fogData((float[]) args[0]);
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

    /** A ()float handle for a public static getter; falls back to reflection if the handle cannot be made. */
    private static java.lang.invoke.MethodHandle floatGetter(Method method) {
        try {
            return java.lang.invoke.MethodHandles.publicLookup().unreflect(method)
                    .asType(java.lang.invoke.MethodType.methodType(float.class));
        } catch (ReflectiveOperationException | RuntimeException e) {
            AtmospheraLib.LOGGER.debug("NeoVoxy getter {} through reflection: {}", method.getName(), e.toString());
            try {
                java.lang.invoke.MethodHandle invoke = java.lang.invoke.MethodHandles.publicLookup()
                        .findVirtual(Method.class, "invoke", java.lang.invoke.MethodType.methodType(Object.class,
                                Object.class, Object[].class))
                        .bindTo(method);
                return java.lang.invoke.MethodHandles.insertArguments(invoke, 0, null, new Object[0])
                        .asType(java.lang.invoke.MethodType.methodType(float.class));
            } catch (ReflectiveOperationException e2) {
                return java.lang.invoke.MethodHandles.constant(float.class, 0f);
            }
        }
    }

    private static float call(java.lang.invoke.MethodHandle handle) {
        try {
            return (float) handle.invokeExact();
        } catch (Throwable e) {
            return 0f;
        }
    }
}
