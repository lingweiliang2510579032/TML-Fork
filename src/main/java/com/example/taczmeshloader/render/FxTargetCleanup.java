package com.example.taczmeshloader.render;

import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Bounded reuse of FX storage, never of another frame's pixels or ownership. */
@Mod.EventBusSubscriber(modid = "taczmeshloader", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FxTargetCleanup {
    static final long IDLE_NANOS = 5_000_000_000L;
    private static Object world;
    private static int width, height;

    private FxTargetCleanup() {}

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        int w = mc.getWindow().getWidth(), h = mc.getWindow().getHeight();
        if (world != mc.level || width != w || height != h) {
            FxSceneCapture.release();
            FxHeldDepth.release();
            world = mc.level;
            width = w;
            height = h;
        }
        long now = System.nanoTime();
        FxSceneCapture.trimIdle(now);
        FxHeldDepth.trimIdle(now);
    }
}
