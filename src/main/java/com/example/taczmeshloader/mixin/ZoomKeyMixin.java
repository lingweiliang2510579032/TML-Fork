package com.example.taczmeshloader.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 每次"切倍率"后记一笔，保证 TML 那个开镜循环功能的档位计数与 TaCZ 的真实档位同步
 * （玩家自己按缩放键切档也算）。
 *
 * <p>★ {@code require = 0}：TaCZ 若改名/重构此方法，这个注入**静默不生效**，
 * 不会因为 required 注入失败而报错或崩启动（功能退化为"计数不更新"，其余照常）。</p>
 */
@Mixin(value = com.tacz.guns.client.input.ZoomKey.class, remap = false)
public class ZoomKeyMixin {

    @Inject(method = "doZoomLogic", at = @At("TAIL"), require = 0)
    private static void tml$countZoomChange(CallbackInfo ci) {
        com.example.taczmeshloader.client.AimCycle.onZoomChanged();
    }
}
