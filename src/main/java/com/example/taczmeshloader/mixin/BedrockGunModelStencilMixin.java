package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.BedrockGunModel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The base renderer clears the scope stencil at the end of its render. */
@Mixin(value = BedrockGunModel.class, remap = false)
public class BedrockGunModelStencilMixin {
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lcom/tacz/guns/util/RenderHelper;disableItemEntityStencilTest()V",
            shift = At.Shift.BEFORE), require = 1)
    private void tml$renderMeshBeforeScopeStencilClear(PoseStack poseStack, ItemStack stack,
            ItemDisplayContext context, RenderType renderType, int light, int overlay, CallbackInfo ci) {
        if ((Object) this instanceof TaczPolyMeshGunModel mesh) {
            mesh.renderMeshBeforeStencilClear(poseStack, stack, context, light, overlay);
        }
    }
}
