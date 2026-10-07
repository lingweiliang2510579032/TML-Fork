package com.example.taczmeshloader.mixin;

import com.tacz.guns.client.gui.components.smith.ResultButton;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 配件台（GunSmithTableScreen）列表条目的名字一长就被裁掉——TaCZ 没做跑马灯（jar 里连 scroll
 * 字样都没有，客户端配置也没这项），官方包里"较长的物品显示名称"这种长名字同样被切。
 *
 * <p>这里只做一件事：把那段名字的绘制 x 按时间横向来回挪。配件台自己会给条目开裁剪，
 * 所以挪 x 就等于跑马灯，其余（图标、颜色、hover、选中态）全不动。</p>
 *
 * <p><b>为什么用描述符定位方法</b>：TaCZ 这个私有方法名不确定（反编译/混淆都可能改），
 * 而描述符是确定的 —— 所以 method 直接写
 * {@code (Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIIII)V}。
 * 配合 {@code require = 0}：万一将来 TaCZ 改了这段，注入静默失效（只是不滚动），**不会崩游戏**。</p>
 */
@Mixin(value = ResultButton.class, remap = false)
public class SmithListScrollMixin {

    /** 条目里名字的可用宽（逻辑像素；按用户截图量的：图标右侧到面板边缘）。 */
    private static final int TEXT_BOX = 110;
    /** 一个来回的周期（毫秒）。 */
    private static final long PERIOD_MS = 4000L;
    /** 两端停顿比例（0 = 不停，纯三角波）。 */
    private static final double HOLD = 0.15;

    @ModifyVariable(
            method = "(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/gui/Font;"
                    + "Lnet/minecraft/network/chat/Component;IIIII)V",
            at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private int zcode$marqueeX(int x, GuiGraphics g, Font font, Component text,
                               int a, int b, int c, int d, int e) {
        if (text == null || font == null) {
            return x;
        }
        int over = font.width(text.getString()) - TEXT_BOX;
        if (over <= 0) {
            return x;                       // 放得下就不动
        }
        double t = (System.currentTimeMillis() % PERIOD_MS) / (double) PERIOD_MS;
        double u;                            // 0..1 往返，两端各停 HOLD
        if (t < 0.5 - HOLD) {
            u = (t / (0.5 - HOLD)) * 0.5;
        } else if (t < 0.5) {
            u = 0.5;
        } else if (t < 1.0 - HOLD) {
            u = 1.0 - ((t - 0.5) / (0.5 - HOLD)) * 0.5;
        } else {
            u = 0.0;
        }
        return x - (int) Math.round(over * u);
    }
}
