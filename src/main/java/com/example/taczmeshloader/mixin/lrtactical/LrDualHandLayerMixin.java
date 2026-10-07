package com.example.taczmeshloader.mixin.lrtactical;

import com.example.taczmeshloader.lrtactical.LrPolyMeshModel;
import com.mojang.blaze3d.vertex.PoseStack;
import me.xjqsh.lrtactical.api.item.IMeleeWeapon;
import me.xjqsh.lrtactical.client.resource.LrClientAssetsManager;
import me.xjqsh.lrtactical.client.resource.display.MeleeDisplayInstance;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** An opt-in visual second blade; never inserts an item into the offhand slot. */
@Mixin(ItemInHandLayer.class)
public abstract class LrDualHandLayerMixin {
    @Unique private static final boolean meshyloader$hasLrt = ModList.get().isLoaded("lrtactical");
    @Shadow
    protected abstract void renderArmWithItem(LivingEntity entity, ItemStack stack,
            ItemDisplayContext context, HumanoidArm arm, PoseStack pose,
            MultiBufferSource buffers, int light);

    @Inject(method = "render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILnet/minecraft/world/entity/LivingEntity;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack;popPose()V", shift = At.Shift.BEFORE))
    private void meshyloader$renderSecondHand(PoseStack pose, MultiBufferSource buffers,
            int light, LivingEntity entity, float swing, float amount, float partialTicks,
            float age, float headYaw, float headPitch, CallbackInfo ci) {
        if (!meshyloader$hasLrt || !entity.getOffhandItem().isEmpty()) return;
        ItemStack stack = entity.getMainHandItem();
        if (stack.isEmpty() || !(stack.getItem() instanceof IMeleeWeapon melee)) return;
        MeleeDisplayInstance display = LrClientAssetsManager.INSTANCE.getMeleeDisplay(melee.getDisplayId(stack));
        if (display == null) display = LrClientAssetsManager.INSTANCE.getMeleeDisplay(melee.getId(stack));
        if (display == null || !(display.getModel() instanceof LrPolyMeshModel model)
                || !model.hasThirdPersonPair()) return;
        HumanoidArm arm = entity.getMainArm().getOpposite();
        ItemDisplayContext context = arm == HumanoidArm.LEFT
                ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND;
        // Run inside vanilla's layer push/pop, retaining its young-model scale.
        renderArmWithItem(entity, stack, context, arm, pose, buffers, light);
    }
}
