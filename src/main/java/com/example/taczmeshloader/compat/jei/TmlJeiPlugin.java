package com.example.taczmeshloader.compat.jei;

import com.example.taczmeshloader.item.ModItems;
import com.example.taczmeshloader.item.SkinCardItem;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.ingredients.subtypes.IIngredientSubtypeInterpreter;
import mezz.jei.api.registration.IExtraIngredientRegistration;
import mezz.jei.api.registration.ISubtypeRegistration;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import java.util.ArrayList;
import java.util.List;

/** Makes data-pack weapon variants and their skin cards searchable as distinct JEI items. */
@JeiPlugin
public final class TmlJeiPlugin implements IModPlugin {
    private static final ResourceLocation UID = new ResourceLocation("taczmeshloader", "jei");

    @Override
    public ResourceLocation getPluginUid() {
        return UID;
    }

    @Override
    public void registerItemSubtypes(ISubtypeRegistration registration) {
        registration.registerSubtypeInterpreter(ModItems.SKIN_CARD.get(), (stack, context) -> {
            ResourceLocation weapon = SkinCardItem.gunId(stack);
            String skin = SkinCardItem.skinId(stack);
            return weapon == null || skin.isEmpty()
                    ? IIngredientSubtypeInterpreter.NONE : weapon + "/" + skin;
        });
    }

    @Override
    public void registerExtraIngredients(IExtraIngredientRegistration registration) {
        List<ItemStack> stacks = new ArrayList<>();
        for (var entry : TimelessAPI.getAllClientGunIndex()) {
            ItemStack stack = GunItemBuilder.create().setId(entry.getKey()).build();
            if (!stack.isEmpty()) stacks.add(stack);
        }
        if (ModList.get().isLoaded("lrtactical")) {
            for (var index : me.xjqsh.lrtactical.api.LrTacticalAPI.getMeleeIndexes()) {
                ItemStack stack = index.createItemStack();
                if (!stack.isEmpty()) stacks.add(stack);
            }
        }
        stacks.addAll(ModItems.allSkinCards());
        registration.addExtraItemStacks(stacks);
    }
}
