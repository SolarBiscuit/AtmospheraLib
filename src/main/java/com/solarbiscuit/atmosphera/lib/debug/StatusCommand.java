package com.solarbiscuit.atmosphera.lib.debug;

import com.mojang.brigadier.CommandDispatcher;
import com.solarbiscuit.atmosphera.lib.AtmospheraLib;
import com.solarbiscuit.atmosphera.lib.compat.AtmosCompat;
import com.solarbiscuit.atmosphera.lib.compat.OverlapGuard;
import com.solarbiscuit.atmosphera.lib.compat.PackProfiles;
import com.solarbiscuit.atmosphera.lib.feature.AtmosFeature;
import com.solarbiscuit.atmosphera.lib.feature.FeatureRegistry;
import com.solarbiscuit.atmosphera.lib.gpu.FrameUniforms;
import com.solarbiscuit.atmosphera.lib.gpu.GpuResources;
import com.solarbiscuit.atmosphera.lib.gpu.GpuTimers;
import com.solarbiscuit.atmosphera.lib.gpu.PostPasses;
import com.solarbiscuit.atmosphera.lib.gpu.SceneTextures;
import com.solarbiscuit.atmosphera.lib.mesh.BitField;
import com.solarbiscuit.atmosphera.lib.mesh.VertexBits;
import com.solarbiscuit.atmosphera.lib.shader.ShaderPatcher;
import com.solarbiscuit.atmosphera.lib.shader.ShaderSnippets;
import com.solarbiscuit.atmosphera.lib.state.AtmosState;
import com.solarbiscuit.atmosphera.lib.wind.Wind;
import com.solarbiscuit.atmosphera.lib.wind.WindVector;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code /atmosphera status}: what runs, what does not and why, vertex-bit use, live GL objects, scene copies, wind and
 * GPU timings. Client-side command (never sent to the server). The same text goes to the log.
 */
public final class StatusCommand {
    /** Dev: also log the status every N seconds while in a world (see {@link com.solarbiscuit.atmosphera.lib.client.DevFlags}). */
    private static final int STATUS_EVERY = com.solarbiscuit.atmosphera.lib.client.DevFlags.STATUS_EVERY;
    private static boolean loggedFirstJoin;
    private static long worldTicks;

    private StatusCommand() {
    }

    /** Client tick: logs the status once on the first world join of the session (for support logs). */
    public static void tick() {
        if (Minecraft.getInstance().level == null) {
            return;
        }
        worldTicks++;
        boolean first = !loggedFirstJoin && worldTicks == 100;
        boolean periodic = STATUS_EVERY > 0 && worldTicks % (STATUS_EVERY * 20L) == 0;
        if (first || periodic) {
            loggedFirstJoin = true;
            for (String line : lines()) {
                AtmospheraLib.LOGGER.info("[status] {}", line);
            }
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("atmosphera").then(Commands.literal("status").executes(ctx -> {
            for (String line : lines()) {
                ctx.getSource().sendSuccess(() -> Component.literal(line).withStyle(color(line)), false);
                AtmospheraLib.LOGGER.info("[status] {}", line);
            }
            return 1;
        })));
    }

    private static ChatFormatting color(String line) {
        if (line.contains(": active")) return ChatFormatting.GREEN;
        if (line.startsWith("  ") && line.contains(": off")) return ChatFormatting.YELLOW;
        return ChatFormatting.GRAY;
    }

    public static List<String> lines() {
        List<String> out = new ArrayList<>();
        AtmosState state = AtmosState.current();
        OverlapGuard.Environment env = state.env();
        out.add("AtmospheraLib " + AtmospheraLib.version() + " | terrain patch: "
                + (ShaderPatcher.isPatching() ? "on" : ShaderPatcher.anchorProblem() != null ? ShaderPatcher.anchorProblem() : "not compiled yet"));
        if (env != null) {
            String pack = env.pack().active()
                    ? env.pack().name() + " (" + (env.packResult().known() ? "profile " + env.packResult().profileName() : "no profile") + ")"
                    : "none";
            out.add("Shaderpack: " + pack + " | AMDium drawing: " + env.amdiumActive() + " | compact vertex format: "
                    + env.compactVertexFormat() + (env.shaderJarOverride() != null ? " | shader replaced by " + env.shaderJarOverride() : ""));
        }
        out.add("Mods: " + String.join(", ", AtmosCompat.describe()) + " | pack profiles: " + PackProfiles.profileCount());
        out.add("Features (" + state.activeCount() + "/" + FeatureRegistry.all().size() + " active):");
        for (AtmosFeature feature : FeatureRegistry.all()) {
            String reason = state.reason(feature);
            out.add("  " + feature.id() + (reason == null ? ": active" : ": off - " + reason));
        }
        int material = 0;
        int light = 0;
        StringBuilder fields = new StringBuilder();
        for (BitField field : VertexBits.fields()) {
            if (field.kind() == BitField.Kind.MATERIAL) material += field.width(); else light += field.width();
            fields.append(fields.isEmpty() ? "" : ", ").append(field);
        }
        out.add("Vertex bits: material " + material + "/" + VertexBits.MATERIAL_BITS + ", light " + light + "/"
                + VertexBits.LIGHT_BITS + (fields.isEmpty() ? "" : " (" + fields + ")"));
        out.add("Shader: " + ShaderSnippets.count() + " snippets, AtmosFrame " + FrameUniforms.slotCount() + " vec4, "
                + PostPasses.count() + " post passes | mesh hooks running: " + state.blockHooks().length + " block, "
                + state.fluidHooks().length + " fluid");
        out.add("Scene copies: " + SceneTextures.describe());
        out.addAll(com.solarbiscuit.atmosphera.lib.compat.voxy.VoxyBridge.describe());
        Map<GpuResources.Type, Integer> counts = GpuResources.counts();
        out.add("Live GL objects: " + (counts.isEmpty() ? "none" : counts) + String.format(java.util.Locale.ROOT,
                " | texture memory %.1f MB (recorded)", GpuResources.totalTextureBytes() / 1048576.0));
        if (Minecraft.getInstance().level != null) {
            WindVector wind = new WindVector();
            Wind.global(Minecraft.getInstance().level, Minecraft.getInstance().getFrameTime(), wind);
            out.add("Wind (" + Wind.provider().name() + "): " + wind);
            out.add(com.solarbiscuit.atmosphera.lib.wind.AtmosClock.describe(Minecraft.getInstance().level));
        }
        if (GpuTimers.isEnabled()) {
            out.add("GPU ms/frame (last 10 s): " + GpuTimers.lastAverages());
        }
        return out;
    }
}
