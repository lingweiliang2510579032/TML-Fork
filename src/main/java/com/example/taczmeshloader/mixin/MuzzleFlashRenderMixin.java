package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.render.ShotFxHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端开火钩子：TaCZ 在本地玩家真正开火时调用 MuzzleFlashRender.onShoot()，
 * 在这里驱动本模组的开火特效（枪口闪电迸发 / 弹道闪电链 / UE 粒子发射器）。
 * 只做"通知"，具体是否生效由每把枪 geo extras 的 shot_fx、fx.emitters 配置决定
 * （未配置=什么都不做）。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = com.tacz.guns.client.model.functional.MuzzleFlashRender.class, remap = false)
public class MuzzleFlashRenderMixin {

    @Inject(method = "onShoot", at = @At("HEAD"))
    private static void taczmeshloader$onShootFx(CallbackInfo ci) {
        try {
            ShotFxHandler.onGunShot();
        } catch (Throwable ignored) {
        }
        try {
            com.example.taczmeshloader.render.FxParticles.onShot();
        } catch (Throwable ignored) {
        }
    }
}
