package com.solarbiscuit.atmosphera.lib.world;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraftforge.fml.ModList;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Where the sun is drawn this frame (lib 0.5.0, audit BUG-08): the direction towards the sun in world space, resolved
 * once per frame and shared by {@code atmos_SunDir} and every mod (Lumis shadows and shafts, Torrente glints and
 * caustics), so light comes from the sun on screen.
 * <ul>
 *   <li>With Nuit Forged's API v3 ({@code NuitApi.getSunDirection(float[])}, reflection, no dependency): the skybox's
 *       drawn sun, which may be rotated by the skybox's decoration rotation or run on its own clock.</li>
 *   <li>Otherwise vanilla's sun path from {@code level.getSunAngle}: rises in the east (+x), overhead at noon.</li>
 * </ul>
 * Policy: only the <b>direction</b> follows the drawn sun. Brightness and the day/night switch keep following game
 * time, since sky light levels come from the server. Render thread.
 */
public final class SunSource {
    private static float dirX;
    private static float dirY = 1f;
    private static float dirZ;
    private static boolean fromSkybox;

    private static boolean probed;
    private static MethodHandle nuitDirection;
    private static boolean failed;
    private static final float[] OUT = new float[3];

    private SunSource() {
    }

    /**
     * Resolves the direction for this level and partial tick. Cheap (two trig calls, one getter), so callers simply
     * call it before reading: Lumis at the start of the render tick, the lib when it writes the frame block.
     */
    public static void update(ClientLevel level, float partial) {
        float angle = level.getSunAngle(partial);
        dirX = (float) -Math.sin(angle);
        dirY = (float) Math.cos(angle);
        dirZ = 0f;
        fromSkybox = false;
        if (!probed) {
            probed = true;
            probe();
        }
        if (nuitDirection != null && !failed) {
            try {
                boolean drawn = (boolean) nuitDirection.invokeExact(OUT);
                float len = (float) Math.sqrt(OUT[0] * OUT[0] + OUT[1] * OUT[1] + OUT[2] * OUT[2]);
                if (drawn && len > 0.5f) {
                    dirX = OUT[0] / len;
                    dirY = OUT[1] / len;
                    dirZ = OUT[2] / len;
                    fromSkybox = true;
                }
            } catch (Throwable t) {
                failed = true;
                AtmospheraLib.LOGGER.warn("Nuit's drawn sun direction could not be read ({}); using the vanilla sun path.", t.toString());
            }
        }
    }

    /** Towards the sun, world space, unit length. */
    public static float dirX() {
        return dirX;
    }

    public static float dirY() {
        return dirY;
    }

    public static float dirZ() {
        return dirZ;
    }

    /** True while the direction comes from a skybox's drawn sun. */
    public static boolean fromSkybox() {
        return fromSkybox;
    }

    private static void probe() {
        if (!ModList.get().isLoaded("nuit")) {
            return;
        }
        try {
            Class<?> api = Class.forName("me.flashyreese.mods.nuit.api.NuitApi");
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            int version = (int) lookup.findStatic(api, "getApiVersion", MethodType.methodType(int.class)).invokeExact();
            if (version < 3) {
                AtmospheraLib.LOGGER.info("Nuit API v{} has no drawn sun direction (API v3); the vanilla sun path is used.", version);
                return;
            }
            nuitDirection = lookup.findStatic(api, "getSunDirection", MethodType.methodType(boolean.class, float[].class));
            AtmospheraLib.LOGGER.info("Sun direction from Nuit's drawn sun (API v{}).", version);
        } catch (Throwable t) {
            nuitDirection = null;
            AtmospheraLib.LOGGER.info("Nuit's sun direction is not available ({}); the vanilla sun path is used.", t.toString());
        }
    }

    public static String describe() {
        return fromSkybox ? "skybox (Nuit)" : "vanilla path";
    }
}
