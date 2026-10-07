package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.render.BloomPostProcessor;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 泛光链的挂载点。
 *
 * <p>位置很讲究：必须在 {@code renderLevel(...)} 返回之后（否则漏掉第一人称手部与枪口特效，
 * 而枪口特效恰恰是泛光最该吃到的东西），又在 {@code getMainRenderTarget().bindWrite(true)}
 * 之前（之后主 target 已绑定、紧接着就画 GUI，此时改画面会连 GUI 一起泛光）。
 * 全文件唯一的 bindWrite 调用正好卡在这两者之间。
 */
@OnlyIn(Dist.CLIENT)
@Mixin(value = net.minecraft.client.renderer.GameRenderer.class)
public class BloomRenderMixin {
    /** Requests belong to one render frame; switching screens/worlds cannot retain old glow. */
    @Inject(method = "m_109093_(FJZ)V", at = @At("HEAD"), remap = false, require = 0)
    private void tml$beginBloomFrame(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        BloomPostProcessor.beginFrame();
    }

    // 本工程是免 gradle 的手动构建，无法生成 refmap；故直接写 1.20.1 生产环境（srg）方法名：
    // render(FJZ)V = m_109093_、RenderTarget.bindWrite(Z)V = m_83947_（自 47.4.22 srg client jar javap 实查）。
    @Inject(
            method = "m_109093_(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/mojang/blaze3d/pipeline/RenderTarget;m_83947_(Z)V",
                    shift = At.Shift.BEFORE),
            remap = false,
            require = 0)
    private void tml$bloomBeforePresent(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        try {
            BloomPostProcessor.render(partialTick);
        } catch (Throwable ignored) {
            // 泛光是纯观感增益，任何异常都不该把玩家的画面搞崩。
        }
    }
}
