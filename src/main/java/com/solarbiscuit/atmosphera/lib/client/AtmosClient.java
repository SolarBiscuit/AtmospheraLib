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
    /** "Reload Atmosphera" (0.8.0): unbound by default, so it clashes with nothing. */
    public static final net.minecraft.client.KeyMapping RELOAD_KEY = new net.minecraft.client.KeyMapping(
            "key.atmospheralib.reload", com.mojang.blaze3d.platform.InputConstants.UNKNOWN.getValue(), "key.categories.atmosphera");

    public static long frame() {
        return frame;
    }

    public static void init(IEventBus modBus) {
        LibConfig.init();
        modBus.addListener(AtmosClient::setup);
        modBus.addListener(AtmosClient::reloadListeners);
        modBus.addListener((net.minecraftforge.client.event.RegisterKeyMappingsEvent e) -> e.register(RELOAD_KEY));
        MinecraftForge.EVENT_BUS.addListener(AtmosClient::clientTick);
        // Before anything of the frame reads the world clock (uniforms, wind for particles).
        MinecraftForge.EVENT_BUS.addListener((TickEvent.RenderTickEvent e) -> {
            if (e.phase == TickEvent.Phase.START) {
                com.solarbiscuit.atmosphera.lib.wind.ClientClock.update(net.minecraft.client.Minecraft.getInstance());
            }
        });
        // AFTER_SKY at high priority (0.7.2): the frame starts and the SKY stage runs before other mods' AFTER_SKY work
        // (Atmospherics copies the sky there for its horizon fade, so it copies the sky drawn here).
        MinecraftForge.EVENT_BUS.addListener(net.minecraftforge.eventbus.api.EventPriority.HIGH, false,
                RenderLevelStageEvent.class, AtmosClient::skyStage);
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
        // 0.9.0: what a server sent (its weather, its mods' settings) ends with the session.
        MinecraftForge.EVENT_BUS.addListener((net.minecraftforge.client.event.ClientPlayerNetworkEvent.LoggingOut e) -> {
            com.solarbiscuit.atmosphera.lib.world.Weather.clearSynced();
            com.solarbiscuit.atmosphera.lib.net.ServerSettings.clear();
        });
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
            com.solarbiscuit.atmosphera.lib.world.SkyView.registerLate();
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
            while (RELOAD_KEY.consumeClick()) {
                // 0.8.0: the "Reload Atmosphera" key: settings and shaders, with the summary in chat.
                com.solarbiscuit.atmosphera.lib.options.AtmosReload.Summary summary =
                        com.solarbiscuit.atmosphera.lib.options.AtmosReload.reload(true);
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.player != null) {
                    mc.player.displayClientMessage(net.minecraft.network.chat.Component.literal(summary.line())
                            .withStyle(net.minecraft.ChatFormatting.AQUA), false);
                }
            }
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

    private static void skyStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY) {
            return;
        }
        startFrame();
        runPosts(PostPasses.Stage.SKY, event);
    }

    /** The frame's start, at AFTER_SKY (moved from renderStage in 0.7.2; same order of work). */
    private static void startFrame() {
        frame++;
        GpuTimers.newFrame();
        com.solarbiscuit.atmosphera.lib.gpu.AtmosGl.newFrame();
        com.solarbiscuit.atmosphera.lib.gpu.TerrainSamplers.resolve();
        com.solarbiscuit.atmosphera.lib.world.ColumnField.frame(frame);
        if (com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.isActive()) {
            //Voxy draws its LODs with the AtmosFrame block too, also in frames where no Xenon program was patched
            FrameUniforms.update(frame);
        }
    }

    private static void renderStage(RenderLevelStageEvent event) {
        RenderLevelStageEvent.Stage stage = event.getStage();
        // AFTER_SKY (the frame's start and the SKY stage) is handled by skyStage, at high priority.
        if (stage == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
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
