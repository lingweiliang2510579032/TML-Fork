package com.example.taczmeshloader.render;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;

import java.util.Arrays;
import java.util.Locale;

/** Session-only A/B controls; never saves Forge config or game options. */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FxDiagnostics {
    private FxDiagnostics() {}
    private static boolean particles = true;
    // Permission for explicit per-frame effect sources, never a request to bloom the scene.
    private static boolean allowRequestedBloom = true;
    private static long[] samples;
    private static int count;
    private static long start, end, previous;
    private static String label;

    public static boolean particlesEnabled() { return particles; }
    public static boolean bloomEnabled() { return allowRequestedBloom; }

    @SubscribeEvent
    public static void register(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tmlfx")
                .then(Commands.literal("particles").then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(c -> {
                            particles = BoolArgumentType.getBool(c, "enabled");
                            return message("particles=" + particles);
                        })))
                .then(Commands.literal("bloom").then(Commands.argument("enabled", BoolArgumentType.bool())
                        .executes(c -> {
                            allowRequestedBloom = BoolArgumentType.getBool(c, "enabled");
                            return message(allowRequestedBloom
                                    ? "bloom=auto (explicit effect sources only; idle is off)"
                                    : "bloom=disabled");
                        })))
                .then(Commands.literal("measure").then(Commands.argument("label", StringArgumentType.word())
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 60))
                                .executes(c -> {
                                    label = StringArgumentType.getString(c, "label");
                                    samples = new long[65536];
                                    count = 0;
                                    previous = 0;
                                    start = System.nanoTime() + 3_000_000_000L;
                                    end = start + IntegerArgumentType.getInteger(c, "seconds") * 1_000_000_000L;
                                    return message("measure=" + label + " starts in 3s; particles=" + particles + " bloomAllowed=" + allowRequestedBloom);
                                })))));
    }

    @SubscribeEvent
    public static void frame(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || samples == null) return;
        long now = System.nanoTime();
        if (now < start) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.screen != null || mc.isPaused()) {
            samples = null;
            message("measure=" + label + " aborted: paused or screen open");
            return;
        }
        if (previous != 0) {
            if (count == samples.length) {
                samples = null;
                message("measure=" + label + " aborted: sample buffer full");
                return;
            }
            samples[count++] = now - previous;
        }
        previous = now;
        if (now < end) return;
        if (count == 0) { samples = null; return; }
        long total = 0;
        int stalls = 0;
        for (int i = 0; i < count; i++) {
            total += samples[i];
            if (samples[i] > 50_000_000L) stalls++;
        }
        Arrays.sort(samples, 0, count);
        String result = String.format(Locale.ROOT,
                "measure=%s n=%d fps=%.2f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f max_ms=%.3f over50=%d particles=%s bloomAllowed=%s",
                label, count, count * 1e9 / total, percentile(.50), percentile(.95), percentile(.99),
                samples[count - 1] / 1e6, stalls, particles, allowRequestedBloom);
        samples = null;
        message(result);
    }

    private static double percentile(double p) {
        return samples[Math.max(0, (int) Math.ceil(count * p) - 1)] / 1e6;
    }

    private static int message(String text) {
        LogManager.getLogger("MeshyLoader").info("[TmlFxAB] {}", text);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.displayClientMessage(Component.literal("[TML] " + text), false);
        return 1;
    }
}
