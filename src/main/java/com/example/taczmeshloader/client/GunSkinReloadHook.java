package com.example.taczmeshloader.client;

import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.Unit;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 资源包重载（F3+T / 重进世界）后清空皮肤表缓存。
 *
 * <p>皮肤表是从 geo JSON 现场读的，改了资源配置（比如新加一套皮肤）后在同一个客户端会话里
 * 按 F3+T 就会重新读；不清缓存的话 <b>第一次读到的空表会一直留在内存里</b>，
 * 表现就是"皮肤控件怎么都不出现"。{@code RegisterClientReloadListenersEvent} 是
 * <b>模组总线</b>事件，所以这个订阅类必须带 {@code bus = MOD}（挂在默认 FORGE 总线上不会触发）。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "taczmeshloader", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.MOD)
public final class GunSkinReloadHook {

    private GunSkinReloadHook() {}

    @SubscribeEvent
    public static void onRegisterReloadListeners(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new PreparableReloadListener() {
            @Override
            public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager rm,
                                                  ProfilerFiller prepProfiler, ProfilerFiller reloadProfiler,
                                                  Executor prepExecutor, Executor reloadExecutor) {
                return barrier.wait(Unit.INSTANCE).thenRunAsync(GunSkinCatalog::invalidate, reloadExecutor);
            }

            @Override
            public String getName() {
                return "taczmeshloader_gun_skin_catalog";
            }
        });
    }
}
