package com.solarbiscuit.atmosphera.lib.client;

import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;
import com.solarbiscuit.atmosphera.lib.compat.PackProfiles;
import com.solarbiscuit.atmosphera.lib.debug.DebugFeatures;
import com.solarbiscuit.atmosphera.lib.debug.StatusCommand;
import com.solarbiscuit.atmosphera.lib.feature.FeatureRegistry;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.SceneTextures;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.options.OptionsPage;
import com.solarbiscuit.atmosphera.lib.shader.ShaderPatcher;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import com.solarbiscuit.atmosphera.lib.wind.AtmosClock;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraftforge.client.event.CustomizeGuiOverlayEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/** Client-side wiring of the library. Never loaded on a dedicated server. */
public final class AtmosClient {
    private static long frame;
    private static long ticks;

    private AtmosClient() {
    }

    /** Frame counter, advanced when the sky of a new frame has been drawn. */
    public static long frame() {
        return frame;
    }

    public static void init(IEventBus modBus) {
        LibConfig.init();
        modBus.addListener(AtmosClient::setup);
        modBus.addListener(AtmosClient::reloadListeners);
        MinecraftForge.EVENT_BUS.addListener(AtmosClient::clientTick);
        // Before anything of the frame reads the world clock (uniforms, wind for particles).
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent e) -> {
            if (e.phase == TickEvent.Phase.START) {
                AtmosClock.update(net.minecraft.client.Minecraft.getInstance());
            }
        });
        MinecraftForge.EVENT_BUS.addListener(AtmosClient::renderStage);
        MinecraftForge.EVENT_BUS.addListener((RegisterClientCommandsEvent e) -> StatusCommand.register(e.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener(AtmosClient::debugText);
        MinecraftForge.EVENT_BUS.addListener(AtmosClient::shutdown);
        if (AtmosCompat.hasSodiumPort()) {
            OptionsPage.register();
        } else {
            AtmospheraLib.LOGGER.warn("No Xenon/Embeddium found: Atmosphera terrain features stay off.");
        }
        AtmosState.addListener((state, tier) -> {
            GpuTimers.setEnabled(LibConfig.gpuTimers.get());
            ShaderPatcher.setDumpShaders(LibConfig.dumpShaders.get());
        });
    }

    private static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            // Every Atmosphera mod has been constructed by now: late registrations, then the layout is fixed.
            DebugFeatures.registerLate();
            FeatureRegistry.freeze();
            VertexBits.freeze();
            ShaderSnippets.freeze();
            FrameUniforms.freeze();
            PostPasses.freeze();
            com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers.freeze();
            AtmosState.refreshNow();
            com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.init();
            AtmospheraLib.LOGGER.info("AtmospheraLib ready: {} features, {} vertex-bit fields, {} snippets.",
                    FeatureRegistry.all().size(), VertexBits.fields().size(), ShaderSnippets.count());
        });
    }

    private static void reloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) manager -> {
            PackProfiles.reload(manager);
            AtmosState.requestRefresh();
        });
    }

    private static void clientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            AtmosState.tick(ticks++);
            StatusCommand.tick();
            if (DevFlags.SHOT_FILE) {
                shotRequest();
            }
        }
    }

    /** {@link DevFlags#SHOT_FILE}: a screenshot per request file, without needing keyboard focus. */
    private static void shotRequest() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        java.nio.file.Path request = mc.gameDirectory.toPath().resolve("atmos_shot.request");
        try {
            if (java.nio.file.Files.deleteIfExists(request)) {
                net.minecraft.client.Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(),
                        message -> com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.info("Test screenshot: {}", message.getString()));
            }
        } catch (java.io.IOException e) {
            com.solarbiscuit.atmosphera.lib.AtmospheraLib.LOGGER.warn("Test screenshot request failed: {}", e.toString());
        }
    }

    private static void renderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        if (stage == RenderLevelStageEvent.Stage.AFTER_SKY) {
            frame++;
            GpuTimers.newFrame();
            if (com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.isActive()) {
                //Voxy draws its LODs with the AtmosFrame block too, also in frames where no Xenon program was patched
                FrameUniforms.update(frame);
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            runPosts(PostPasses.Stage.AFTER_TRANSLUCENT, event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            runPosts(PostPasses.Stage.AFTER_WEATHER, event);
        }
    }

    private static void runPosts(PostPasses.Stage stage, RenderLevelStageEvent event) {
        if (PostPasses.count() > 0) {
            org.joml.Matrix4f inverse = new org.joml.Matrix4f(event.getProjectionMatrix())
                    .mul(event.getPoseStack().last().pose()).invert();
            PostPasses.run(stage, new PostPasses.Context(event.getPoseStack(), event.getProjectionMatrix(),
                    event.getPartialTick(), inverse, event.getCamera().getPosition()));
        }
    }

    private static void debugText(CustomizeGuiOverlayEvent.DebugText event) {
        if (LibConfig.debugLine == null || !LibConfig.debugLine.get() || FeatureRegistry.all().isEmpty()) {
            return;
        }
        AtmosState state = AtmosState.current();
        String pack = state.env() != null && state.env().pack().active() ? ", shaderpack on" : "";
        event.getRight().add("Atmosphera: " + state.activeCount() + "/" + FeatureRegistry.all().size() + " features" + pack);
    }

    private static void shutdown(GameShuttingDownEvent event) {
        try {
            SceneTextures.deleteAll();
            FrameUniforms.delete();
            GpuTimers.deleteAll();
            GpuResources.deleteAll();
        } catch (RuntimeException e) {
            AtmospheraLib.LOGGER.debug("GL cleanup at shutdown skipped: {}", e.toString());
        }
    }
}
