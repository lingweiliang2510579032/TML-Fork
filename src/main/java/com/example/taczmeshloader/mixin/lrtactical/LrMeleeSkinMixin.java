package com.example.taczmeshloader.mixin.lrtactical;

import com.example.taczmeshloader.lrtactical.LrPolyMeshModel;
import com.mojang.blaze3d.vertex.PoseStack;
import me.xjqsh.lrtactical.client.renderer.model.CustomBedrockModel;
import me.xjqsh.lrtactical.client.resource.display.MeleeDisplayInstance;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Bind the actual rendered stack, including non-held previews and dropped items. */
@Pseudo
@Mixin(targets = "me.xjqsh.lrtactical.client.renderer.item.MeleeItemRenderer", remap = false)
public class LrMeleeSkinMixin {
    @Inject(method = "getModel(Lnet/minecraft/world/item/ItemStack;)Lme/xjqsh/lrtactical/client/renderer/model/CustomBedrockModel;",
            at = @At("RETURN"), require = 0)
    private void tml$bindFirstPerson(ItemStack stack, CallbackInfoReturnable<CustomBedrockModel> cir) {
        if (cir.getReturnValue() instanceof LrPolyMeshModel model) model.setSkinStack(stack);
    }

    @Inject(method = "lambda$renderByItem$2", at = @At("HEAD"), require = 0)
    private void tml$bindItem(ItemDisplayContext context, PoseStack poses, MultiBufferSource buffers,
                              int light, int overlay, ItemStack stack, MeleeDisplayInstance display, CallbackInfo ci) {
        if (display.getModel() instanceof LrPolyMeshModel model) model.setSkinStack(stack);
    }
}
