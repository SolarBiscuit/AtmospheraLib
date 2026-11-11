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
        // Column field (0.5.0): client chunks and logins (block-state ids are reassigned per server).
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.ChunkEvent.Load e) -> {
            if (e.getLevel().isClientSide() && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
                com.solarbiscuit.atmosphera.lib.world.ColumnField.onChunkLoad(chunk);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.event.level.ChunkEvent.Unload e) -> {
            if (e.getLevel().isClientSide() && e.getChunk() instanceof net.minecraft.world.level.chunk.LevelChunk chunk) {
                com.solarbiscuit.atmosphera.lib.world.ColumnField.onChunkUnload(chunk);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingIn e) ->
                com.solarbiscuit.atmosphera.lib.world.CasterTable.reset());
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
            com.solarbiscuit.atmosphera.lib.world.ColumnFieldShader.registerLate();
            com.solarbiscuit.atmosphera.lib.world.CloudShadowShader.registerLate();
            DebugFeatures.registerLate();
            FeatureRegistry.freeze();
            VertexBits.freeze();
            ShaderSnippets.freeze();
            FrameUniforms.freeze();
            PostPasses.freeze();
            com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers.freeze();
            AtmosState.refreshNow();
            com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.init();
            com.solarbiscuit.atmosphera.lib.compat.dh.DhBridge.init();
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
            if (DevFlags.HIDE_GUI) {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.level != null) {
                    mc.options.hideGui = true;
                }
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
            com.solarbiscuit.atmosphera.lib.gpu.AtmosGl.newFrame();
            com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers.resolve();
            com.solarbiscuit.atmosphera.lib.world.ColumnField.frame(frame);
            if (com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.isActive()) {
                //Voxy draws its LODs with the AtmosFrame block too, also in frames where no Xenon program was patched
                FrameUniforms.update(frame);
            }
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            runPosts(PostPasses.Stage.AFTER_TRANSLUCENT, event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            runPosts(PostPasses.Stage.CLOUDS, event);
        } else if (stage == RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            // The world matrices for the FINAL stage, after the hand (0.6.4): copied, no allocation.
            FINAL_PROJECTION.set(event.getProjectionMatrix());
            FINAL_VIEW.set(event.getPoseStack().last().pose());
            finalCamera = event.getCamera().getPosition();
            finalPartial = event.getPartialTick();
            finalFrame = frame;
            runPosts(PostPasses.Stage.AFTER_WEATHER, event);
        }
    }

    private static final org.joml.Matrix4f FINAL_PROJECTION = new org.joml.Matrix4f();
    private static final org.joml.Matrix4f FINAL_VIEW = new org.joml.Matrix4f();
    private static net.minecraft.world.phys.Vec3 finalCamera;
    private static float finalPartial;
    private static long finalFrame = -1;

    /**
     * The {@link PostPasses.Stage#FINAL} stage (0.6.4), from the end of {@code GameRenderer.renderLevel}: the hand is
     * drawn, vanilla's post effects and the GUI are not. Only in frames whose AFTER_WEATHER stage ran.
     */
    public static void runFinal() {
        PostPasses.Stage stage = PostPasses.Stage.FINAL;
        if (finalFrame == frame && finalCamera != null && PostPasses.count() > 0 && PostPasses.anyActive(stage)) {
            PostPasses.Context context = POST_CONTEXTS[stage.ordinal()].set(FINAL_PROJECTION, FINAL_VIEW, finalPartial,
                    finalCamera);
            PostPasses.run(stage, context);
        }
    }

    /** One reused context per stage (0.3.22: no matrices allocated per frame). */
    private static final PostPasses.Context[] POST_CONTEXTS = new PostPasses.Context[PostPasses.Stage.values().length];

    static {
        for (int i = 0; i < POST_CONTEXTS.length; i++) {
            POST_CONTEXTS[i] = new PostPasses.Context();
        }
    }

    private static void runPosts(PostPasses.Stage stage, RenderLevelStageEvent event) {
        if (PostPasses.count() > 0 && PostPasses.anyActive(stage)) {
            PostPasses.Context context = POST_CONTEXTS[stage.ordinal()].set(event.getPoseStack(),
                    event.getProjectionMatrix(), event.getPartialTick(), event.getCamera().getPosition());
            PostPasses.run(stage, context);
        }
    }

    /**
     * The {@link PostPasses.Stage#BEFORE_TRANSLUCENT} stage, from the start of Xenon's translucent terrain pass right
     * after the scene copies (0.4.0).
     */
    public static void runBeforeTranslucent(org.joml.Matrix4fc projection, org.joml.Matrix4fc modelView) {
        PostPasses.Stage stage = PostPasses.Stage.BEFORE_TRANSLUCENT;
        if (PostPasses.count() > 0 && PostPasses.anyActive(stage)) {
            net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
            PostPasses.Context context = POST_CONTEXTS[stage.ordinal()].set(projection, modelView, mc.getFrameTime(),
                    mc.gameRenderer.getMainCamera().getPosition());
            PostPasses.run(stage, context);
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
            com.solarbiscuit.atmosphera.lib.world.ColumnField.release();
            FrameUniforms.delete();
            GpuTimers.deleteAll();
            GpuResources.deleteAll();
        } catch (RuntimeException e) {
            AtmospheraLib.LOGGER.debug("GL cleanup at shutdown skipped: {}", e.toString());
        }
    }
}
