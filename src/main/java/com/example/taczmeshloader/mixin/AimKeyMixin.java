package com.example.taczmeshloader.mixin;

import net.minecraftforge.client.event.InputEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 拦截 TaCZ 的"瞄准键按下"（默认鼠标右键）：
 *
 * <p>当 TML 的 {@code aim.cycle_zoom_on_aim_key} 打开、且玩家用的是 TaCZ「切换式开镜」
 * （长按瞄准 = false）时，把"开镜中再次按下"变成<b>切倍率</b>——到最后一档时放行，由 TaCZ 关镜，
 * 于是同一个键就是「开镜 → 切倍率 → 关镜」。用长按的玩家或关掉开关时，这里什么都不做，
 * TaCZ 行为完全不变。</p>
 *
 * <p>★ {@code require = 0}：TaCZ 若改名/重构 {@code onAimPress}，注入静默失效（功能自动停用），
 * 不会抛 Mixin 错误。</p>
 */
@Mixin(value = com.tacz.guns.client.input.AimKey.class, remap = false)
public class AimKeyMixin {

    @Inject(method = "onAimPress", at = @At("HEAD"), cancellable = true, require = 0)
    private static void tml$cycleZoomOnAimPress(InputEvent.MouseButton.Post event, CallbackInfo ci) {
        if (com.example.taczmeshloader.client.AimCycle.interceptAimPress()) {
            ci.cancel();
        }
    }
}
