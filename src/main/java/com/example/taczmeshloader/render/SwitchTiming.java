package com.example.taczmeshloader.render;

import com.example.taczmeshloader.item.SkinWeapon;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;

/** Bounded slot-switch timing. Emits one summary after switching settles, no per-frame logging. */
@Mod.EventBusSubscriber(modid = "taczmeshloader", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SwitchTiming {
    private static Object world;
    private static int slot = -1, frames, slowFrames, switches, reports;
    private static long previous, frameStart, tickStart, ticksSinceFrame;
    private static long until, maxFrame, maxRender, maxTick, total;
    private static String weapon = "", from = "";
    private SwitchTiming() {}

    private static String describe(ItemStack stack) {
        var id = SkinWeapon.id(stack);
        String skin = stack.hasTag() ? stack.getTag().getString("MeshSkin") : "";
        return (id == null ? "other" : id.toString()) + "/" + skin;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void start(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START || reports >= 60) return;
        long now = System.nanoTime();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null || mc.isPaused()) {
            previous = frameStart = until = ticksSinceFrame = 0;
            slot = -1; world = null;
            return;
        }
        int selected = mc.player.getInventory().selected;
        if (world != mc.level || slot < 0) {
            world = mc.level; slot = selected; weapon = describe(mc.player.getMainHandItem());
            previous = now;
        } else if (slot != selected) {
            if (until == 0) {
                frames = slowFrames = switches = 0;
                maxFrame = maxRender = maxTick = total = 0;
                from = weapon;
            }
            slot = selected; weapon = describe(mc.player.getMainHandItem());
            switches++;
            until = now + 1_500_000_000L;
        }
        if (until != 0 && previous != 0) {
            long elapsed = now - previous;
            maxFrame = Math.max(maxFrame, elapsed);
            maxTick = Math.max(maxTick, ticksSinceFrame);
            total += elapsed; frames++;
            if (elapsed > 25_000_000L) slowFrames++;
        }
        ticksSinceFrame = 0;
        previous = frameStart = now;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void end(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || until == 0 || frameStart == 0) return;
        long now = System.nanoTime();
        maxRender = Math.max(maxRender, now - frameStart);
        if (now < until) return;
        // Event spans are wall time, not GPU timer queries or isolated CPU work.
        LogManager.getLogger("MeshyLoader").info(
                "[SwitchTiming] from={} to={} switches={} frames={} maxFrameMs={} maxRenderEventMs={} maxClientTickMs={} over25={} avgFrameMs={}",
                from, weapon, switches, frames, maxFrame / 1e6, maxRender / 1e6, maxTick / 1e6,
                slowFrames, frames == 0 ? 0 : total / (frames * 1e6));
        until = 0; reports++;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void tickStart(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.START && reports < 60) tickStart = System.nanoTime();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tickEnd(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && reports < 60 && tickStart != 0) {
            ticksSinceFrame = Math.max(ticksSinceFrame, System.nanoTime() - tickStart);
            tickStart = 0;
        }
    }
}
