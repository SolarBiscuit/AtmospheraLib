package com.solarbiscuit.atmosphera.lib.compat.dh;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Distant Horizons' LOD depth for post passes (0.7.0). DH draws its LODs with a depth texture and a projection of its
 * own (other near and far planes), so a pass that must stop at distant terrain (Atmosphera clouds, later Lumis) reads that
 * texture next to the main depth. Everything is reached by reflection, except {@link DhRenderListener}, which is
 * compiled against stand-ins of two DH API classes ({@code src/stubs}) and loaded only when DH is installed.
 * <p>
 * Frame slot {@code atmos_Dh}: x near plane, y far plane (blocks), z 1 while DH drew LODs this frame or the last one,
 * w unused. Post programs include {@link #GLSL}, bind {@link #depthTexture()} to a unit of their own and set the
 * sampler {@code atmos_DhDepth}.
 * <p>
 * <b>Unverified in game</b> until a Distant Horizons test instance runs (checked only against the DH 3.2.0 API, API
 * major version 7).
 */
public final class DhBridge {
    private static boolean initialised;
    private static boolean available;
    private static Object renderProxy;
    private static Method depthTextureMethod;
    private static Field resultSuccess;
    private static Field resultPayload;
    private static int depthTexture;
    private static int refreshCountdown;
    private static int lastWidth;
    private static int lastHeight;
    private static String status = "not installed";

    static volatile float near;
    static volatile float far;
    static volatile long lastRenderFrame = Long.MIN_VALUE;

    private DhBridge() {
    }

    /** Client setup: binds the render listener when DH is installed. Safe to call more than once. */
    public static synchronized void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        if (!AtmosCompat.isLoaded("distanthorizons")) {
            return;
        }
        try {
            Class<?> api = Class.forName("com.seibel.distanthorizons.api.DhApi");
            int major = (int) api.getMethod("getApiMajorVersion").invoke(null);
            Object events = api.getField("events").get(null);
            Class<?> eventClass = Class.forName("com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeRenderEvent");
            Method bind = null;
            for (Method m : events.getClass().getMethods()) {
                if (m.getName().equals("bind") && m.getParameterCount() == 2) {
                    bind = m;
                    break;
                }
            }
            if (bind == null) {
                throw new NoSuchMethodException("IDhApiEventInjector.bind");
            }
            bind.invoke(events, eventClass, new DhRenderListener());
            available = true;
            status = "API " + major + ", render listener bound (unverified in game)";
            AtmospheraLib.LOGGER.info("[Atmosphera] Distant Horizons API {}: LOD depth available to post passes", major);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            status = "API not usable: " + e;
            AtmospheraLib.LOGGER.warn("[Atmosphera] Distant Horizons is installed but its API could not be used ({}); "
                    + "clouds and shafts will not stop at DH terrain", e.toString());
        }
    }

    /** Whether DH drew LODs in this frame or the one before (render thread). */
    public static boolean rendering(long frame) {
        return available && frame - lastRenderFrame <= 1;
    }

    /**
     * DH's depth texture (window depth, 1 = no LOD), or 0 while DH does not render. The id is re-read after a window
     * resize and about once a second otherwise. Render thread.
     */
    public static int depthTexture(long frame) {
        if (!rendering(frame)) {
            return 0;
        }
        var target = net.minecraft.client.Minecraft.getInstance().getMainRenderTarget();
        if (target.width != lastWidth || target.height != lastHeight) {
            lastWidth = target.width;
            lastHeight = target.height;
            refreshCountdown = 0; // DH recreates its textures on resize, often with new ids
        }
        if (--refreshCountdown <= 0 || depthTexture == 0) {
            refreshCountdown = 60;
            depthTexture = queryDepthTexture();
        }
        return depthTexture;
    }

    /** Forces the depth texture id to be re-read next frame (call on resize). */
    public static void invalidate() {
        refreshCountdown = 0;
    }

    private static int queryDepthTexture() {
        try {
            if (renderProxy == null) {
                Class<?> delayed = Class.forName("com.seibel.distanthorizons.api.DhApi$Delayed");
                renderProxy = delayed.getField("renderProxy").get(null);
                if (renderProxy == null) {
                    return 0;
                }
                depthTextureMethod = Class.forName("com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy")
                        .getMethod("getDhDepthTextureId");
            }
            Object result = depthTextureMethod.invoke(renderProxy);
            if (resultSuccess == null) {
                resultSuccess = result.getClass().getField("success");
                resultPayload = result.getClass().getField("payload");
            }
            if (!resultSuccess.getBoolean(result)) {
                return 0;
            }
            Object payload = resultPayload.get(result);
            return payload instanceof Integer id ? id : 0;
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (available) {
                available = false;
                status = "depth texture not readable: " + e;
                AtmospheraLib.LOGGER.warn("[Atmosphera] Distant Horizons depth texture not readable ({}); DH depth off", e.toString());
            }
            return 0;
        }
    }

    /** atmos_Dh: x near, y far, z rendering, w 0. */
    public static void writeSlot(float[] out, long frame) {
        if (!rendering(frame)) {
            return;
        }
        out[0] = near;
        out[1] = far;
        out[2] = 1f;
    }

    /** One line for {@code /atmosphera status}. */
    public static String status() {
        return status;
    }

    /**
     * Post-program GLSL: {@code atmos_dhDistance(uv)} is the view distance (blocks, along the view axis) to DH's LOD
     * terrain at this screen uv, or 1e9 where there is none or DH is not rendering. Safe to include twice.
     */
    public static final String GLSL = """
            #ifndef ATMOS_DH_DECL
            #define ATMOS_DH_DECL
            uniform sampler2D atmos_DhDepth;
            float atmos_dhDistance(vec2 atmosUv) {
                if (atmos_Dh.z < 0.5) {
                    return 1e9;
                }
                float atmosD = texture(atmos_DhDepth, atmosUv).r;
                if (atmosD >= 1.0) {
                    return 1e9;
                }
                float atmosN = atmos_Dh.x;
                float atmosF = atmos_Dh.y;
                float atmosNdc = atmosD * 2.0 - 1.0;
                return 2.0 * atmosN * atmosF / (atmosF + atmosN - atmosNdc * (atmosF - atmosN));
            }
            #endif
            """;
}
