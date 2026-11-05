package com.solarbiscuit.atmosphera.lib.compat.voxy;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.client.AtmosClient;
import com.solarbiscuit.atmosphera.lib.client.LibConfig;
import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.Capability;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.mesh.BlockMeshHook;
import com.solarbiscuit.atmosphera.lib.mesh.MeshHooks;
import com.solarbiscuit.atmosphera.lib.shader.ShaderHook;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.List;
import java.util.Set;

/**
 * Atmosphera on Voxy LOD terrain: Torrente's water shading and Gale's sway continue past the vanilla render distance,
 * from the same snippets Xenon runs, so the boundary between vanilla chunks and LODs has one look.
 * <p>
 * Backends: {@link NativeVoxyBackend} (NeoVoxy's {@code LodShaderExtensions} API, everything) and
 * {@link TextPatchVoxyBackend} (any Voxy whose LOD shaders carry the expected anchors, for example the official Voxy;
 * fewer capabilities). Chosen once at client setup from the {@code voxy_backend} option. Both run without shader packs
 * only: under a pack Atmosphera's terrain features and Voxy's own LOD shading are off.
 */
public final class VoxyBridge {
    public enum Mode { AUTO, NATIVE, TEXT_PATCH, OFF }

    /** NeoVoxy material word: Atmosphera material bits in 0-4, {@link BlockMeshHook#lodShape} anchor in 8-9. */
    public static final int WORD_ANCHOR_SHIFT = 8;
    /** NeoVoxy material word: never merge this model's LOD quads (LodShaderExtensions.MATERIAL_UNMERGED). */
    public static final int WORD_UNMERGED = 1 << 10;

    interface Backend {
        String name();

        void requestShaderRebuild(String reason);

        float fadeStart();

        float fadeEnd();

        float vanillaDistance();
    }

    private static volatile Backend backend;
    private static String status = "not initialised";
    private static long signature;
    private static final float[] LOD = new float[4];

    private VoxyBridge() {
    }

    /**
     * A feature mod's handler for NeoVoxy's distant light emitters (NeoVoxy API 3, 1.43.7+): NeoVoxy reports, per built
     * LOD section, the voxels whose {@link #emitterWordFor} word is not 0. Register with {@link #setLodEmitters}.
     */
    public interface LodEmitters {
        /** Word for a block state (0 = not reported). Mesh build threads: pure and thread-safe. */
        int emitterWordFor(BlockState state);

        /** One section's emitters: {@code (long) word << 15 | (y << 10 | z << 5 | x)}. Mesh build threads; copy. */
        void sectionEmitters(int lodLevel, int sectionX, int sectionY, int sectionZ, long[] emitters, int count);

        /** Whether reports are wanted now (for example while the feature is active). Any thread. */
        boolean wanted();
    }

    private static volatile LodEmitters lodEmitters;
    /** NeoVoxy's LOD extension API version (0 when there is none). */
    static volatile int apiVersion;
    private static final java.util.List<Runnable> BEFORE_DRAW = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void setLodEmitters(LodEmitters handler) {
        lodEmitters = handler;
    }

    /** NeoVoxy reports LOD emitters (API 3) and a handler wants them. */
    public static boolean lodEmittersAvailable() {
        return apiVersion >= 3 && backend instanceof NativeVoxyBackend;
    }

    static boolean needsSectionEmitters() {
        LodEmitters handler = lodEmitters;
        return handler != null && apiVersion >= 3 && handler.wanted();
    }

    static int emitterWordFor(BlockState state) {
        LodEmitters handler = lodEmitters;
        return handler == null ? 0 : handler.emitterWordFor(state);
    }

    static void sectionEmitters(int lodLevel, int x, int y, int z, long[] emitters, int count) {
        LodEmitters handler = lodEmitters;
        if (handler != null) {
            handler.sectionEmitters(lodLevel, x, y, z, emitters, count);
        }
    }

    /** Runs right before every LOD draw on the render thread (bind textures the LOD snippets sample). */
    public static void addBeforeLodDraw(Runnable action) {
        BEFORE_DRAW.add(action);
    }

    /** Asks NeoVoxy to rebuild its LOD renderer (meshes included), for example to collect emitters again. */
    public static void requestRendererRebuild(String reason) {
        if (backend instanceof NativeVoxyBackend nativeBackend) {
            nativeBackend.requestRendererRebuild(reason);
        }
    }

    /** Client setup, after the registries are frozen. Render thread. */
    public static void init() {
        Mode mode = LibConfig.voxyBackend.get();
        if (mode == Mode.OFF) {
            status = "off (voxy_backend = off)";
            return;
        }
        if (!AtmosCompat.isLoaded("voxy")) {
            status = "Voxy not installed";
            return;
        }
        if (ShaderSnippets.featuresAt(ShaderHook.LOD_VERTEX, ShaderHook.LOD_FRAGMENT).isEmpty()) {
            status = "no Atmosphera feature has LOD shading";
            return;
        }
        Backend chosen = null;
        String failure = null;
        if (mode != Mode.TEXT_PATCH) {
            try {
                chosen = NativeVoxyBackend.tryRegister();
            } catch (Throwable t) {
                failure = "native: " + t;
            }
            if (chosen == null && failure == null) {
                failure = "native: NeoVoxy LOD shader extension API not found";
            }
        }
        if (chosen == null && mode != Mode.NATIVE) {
            chosen = TextPatchVoxyBackend.tryEnable();
            if (chosen == null) {
                failure = (failure == null ? "" : failure + "; ") + "text patch: " + TextPatchVoxyBackend.problem();
            }
        }
        if (chosen == null) {
            status = "unavailable (" + failure + ")";
            AtmospheraLib.LOGGER.warn("Atmosphera LOD shading is off: {}", failure);
            return;
        }
        backend = chosen;
        status = chosen.name();
        signature = signature();
        FrameUniforms.setLodSource(VoxyBridge::writeLod);
        AtmosState.addListener((state, tier) -> {
            long now = signature();
            if (now != signature) {
                signature = now;
                Backend current = backend;
                if (current != null) current.requestShaderRebuild("Atmosphera LOD features changed");
            }
        });
        AtmospheraLib.LOGGER.info("Atmosphera LOD shading via {}", chosen.name());
    }

    public static String status() {
        return status;
    }

    /** From the Voxy ShaderLoader mixin: the patched source while the text-patch backend is in use, else the input. */
    public static String textPatch(String id, String source) {
        return backend instanceof TextPatchVoxyBackend ? TextPatchVoxyBackend.apply(id, source) : source;
    }

    public static boolean isActive() {
        return backend != null;
    }

    /** Active LOD features, one bit each: when it changes, the LOD shaders are recompiled with the new set. */
    private static long signature() {
        long bits = 0;
        int i = 0;
        for (AtmosFeature feature : ShaderSnippets.featuresAt(ShaderHook.LOD_DECL, ShaderHook.LOD_VERTEX, ShaderHook.LOD_FRAGMENT)) {
            if (feature.isActive()) bits |= 1L << (i & 63);
            i++;
        }
        return bits;
    }

    /** Material word of a block state for Voxy's LOD models (bake thread). Static: independent of active features. */
    public static int materialBitsFor(BlockState state) {
        int bits = 0;
        int shape = 0;
        for (MeshHooks.BlockEntry entry : MeshHooks.blockHooks()) {
            try {
                bits |= entry.hook().lodMaterialBits(state);
                shape |= entry.hook().lodShape(state);
            } catch (RuntimeException e) {
                // A broken hook only loses its own bits
            }
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty()) {
            for (MeshHooks.FluidEntry entry : MeshHooks.fluidHooks()) {
                try {
                    bits |= entry.hook().lodMaterialBits(fluid);
                } catch (RuntimeException e) {
                    // as above
                }
            }
        }
        int word = bits & 31;
        word |= (shape & (BlockMeshHook.LOD_ANCHOR_BOTTOM | BlockMeshHook.LOD_ANCHOR_TOP)) << WORD_ANCHOR_SHIFT;
        if ((shape & BlockMeshHook.LOD_UNMERGED) != 0) word |= WORD_UNMERGED;
        return word;
    }

    /** True when some active LOD fragment feature reads the scene depth (Torrente's water thickness). */
    static boolean needsOpaqueDepth() {
        return ShaderSnippets.any(f -> f.isActive() && f.uses(AtmosFeature.Uses.SCENE_DEPTH), ShaderHook.LOD_FRAGMENT);
    }

    /** Static (meshing): a waving-foliage feature has LOD vertex code, whether or not it is switched on right now. */
    static boolean needsUnmergedFoliage() {
        Set<AtmosFeature> features = ShaderSnippets.featuresAt(ShaderHook.LOD_VERTEX);
        for (AtmosFeature feature : features) {
            if (feature.capabilities().contains(Capability.WAVING_FOLIAGE)) return true;
        }
        return false;
    }

    static boolean animating() {
        return ShaderSnippets.any(AtmosFeature::isActive, ShaderHook.LOD_VERTEX, ShaderHook.LOD_FRAGMENT);
    }

    /** Before every LOD draw: the AtmosFrame buffer must be current and bound (Xenon may not have drawn this frame). */
    static void beforeLodDraw() {
        FrameUniforms.update(AtmosClient.frame());
        for (Runnable action : BEFORE_DRAW) {
            action.run();
        }
    }

    private static void writeLod(float[] out) {
        Backend current = backend;
        if (current == null) return;
        out[0] = current.fadeStart();
        out[1] = current.fadeEnd();
        out[2] = current.vanillaDistance();
        out[3] = 1f;
    }

    /** For {@code /atmosphera status}. */
    public static List<String> describe() {
        return List.of("Voxy LOD shading: " + status);
    }
}
