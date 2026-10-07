package com.example.taczmeshloader.item;

import com.tacz.guns.api.item.IGun;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

/** Shared identity check for skin cards; no client resource access on the server. */
public final class SkinWeapon {
    private SkinWeapon() {}
    public static ResourceLocation id(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        IGun gun = IGun.getIGunOrNull(stack);
        if (gun != null) return gun.getGunId(stack);
        if (ModList.get().isLoaded("lrtactical")) {
            var melee = me.xjqsh.lrtactical.api.item.IMeleeWeapon.of(stack);
            if (melee != null) return melee.getId(stack);
        }
        return null;
    }
    public static boolean isMelee(ItemStack stack) {
        return stack != null && !stack.isEmpty() && ModList.get().isLoaded("lrtactical")
                && me.xjqsh.lrtactical.api.item.IMeleeWeapon.of(stack) != null;
    }
}
