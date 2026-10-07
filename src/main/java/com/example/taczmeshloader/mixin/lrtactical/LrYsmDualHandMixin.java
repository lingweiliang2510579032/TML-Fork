package com.example.taczmeshloader.mixin.lrtactical;

import com.example.taczmeshloader.lrtactical.LrPolyMeshModel;
import me.xjqsh.lrtactical.api.item.IMeleeWeapon;
import me.xjqsh.lrtactical.client.resource.LrClientAssetsManager;
import me.xjqsh.lrtactical.client.resource.display.MeleeDisplayInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** YSM 2.6.5 replaces vanilla's held-item layer, including its arm transforms. */
@Pseudo
@Mixin(targets = "com.elfmcys.yesstevemodel.o000Oo0OO0O00Oo0OOoOoooO", remap = false)
public abstract class LrYsmDualHandMixin {
    @Unique private static final boolean meshyloader$hasLrt = ModList.get().isLoaded("lrtactical");

    // Target the concrete YSM render overload, not its synthetic bridge.
    @Redirect(method = "Oo0Oo0o00O00Oo0OOoOOoooo(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;ILcom/elfmcys/yesstevemodel/oo0OooOO0oOoOoOoo00oO000;FFFFFF)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/LivingEntity;getOffhandItem()Lnet/minecraft/world/item/ItemStack;", remap = true),
            remap = false, require = 1, allow = 1)
    private ItemStack meshyloader$visualOffhand(LivingEntity entity) {
        ItemStack offhand = entity.getOffhandItem();
        if (!meshyloader$hasLrt || !offhand.isEmpty()) return offhand;
        ItemStack mainhand = entity.getMainHandItem();
        if (mainhand.isEmpty() || !(mainhand.getItem() instanceof IMeleeWeapon melee)) return offhand;
        MeleeDisplayInstance display = LrClientAssetsManager.INSTANCE.getMeleeDisplay(melee.getDisplayId(mainhand));
        if (display == null) display = LrClientAssetsManager.INSTANCE.getMeleeDisplay(melee.getId(mainhand));
        if (display == null || !(display.getModel() instanceof LrPolyMeshModel model)
                || !model.hasThirdPersonPair()) return offhand;
        // Only this render call sees the extra blade; inventory stays unchanged.
        return mainhand;
    }
}
