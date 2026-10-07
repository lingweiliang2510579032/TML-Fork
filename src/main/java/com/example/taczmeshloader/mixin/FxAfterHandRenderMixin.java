package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.render.FxParticles;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After all renderLevel hand/tail callbacks; before vanilla post processing, bloom and GUI. */
@Mixin(GameRenderer.class)
public class FxAfterHandRenderMixin {
    // Explicit 1.20.1 production names, matching this fork's existing GameRenderer mixin.
    @Inject(method = "m_109093_(FJZ)V", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/GameRenderer;m_109089_(FJLcom/mojang/blaze3d/vertex/PoseStack;)V",
            shift = At.Shift.AFTER), remap = false, require = 1)
    private void tml$drawFxAfterHand(float partialTick, long nanoTime, boolean renderLevel, CallbackInfo ci) {
        com.example.taczmeshloader.render.MeleeSurfaceRenderer.present();
        FxParticles.renderAfterHand();
    }
}
