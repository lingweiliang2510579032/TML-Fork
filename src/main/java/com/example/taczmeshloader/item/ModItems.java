package com.example.taczmeshloader.item;

import com.example.taczmeshloader.TacZMeshLoaderMod;
import com.example.taczmeshloader.client.GunSkinCatalog;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.builder.GunItemBuilder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * TML 自带的通用物品与创造模式分类。
 *
 * <p>目前只有"皮肤卡"：不硬编码任何枪名/品牌，卡的清单**从已加载的资源配置数据里现算**
 * （遍历所有枪 → 读它 geo extras 的 skins 表 → 每套非默认皮肤生成一张卡）。</p>
 */
public final class ModItems {

    private ModItems() {}

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.ITEMS, TacZMeshLoaderMod.MOD_ID);
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, TacZMeshLoaderMod.MOD_ID);

    public static final RegistryObject<Item> SKIN_CARD =
            ITEMS.register("skin_card", () -> new SkinCardItem(new Item.Properties().stacksTo(16)));

    /** "皮肤"分类：内容按资源配置数据现算（没有皮肤表的枪不出卡）。 */
    public static final RegistryObject<CreativeModeTab> SKIN_TAB = TABS.register("skin", () ->
            CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.taczmeshloader.skin"))
                    .icon(() -> allSkinCards().stream().findFirst().orElse(ItemStack.EMPTY))
                    .displayItems((params, output) -> output.acceptAll(allSkinCards()))
                    .build());

    /** 所有资源配置声明的"非默认皮肤"逐一出卡（默认皮肤本来就随身，不出卡）。 */
    public static List<ItemStack> allSkinCards() {
        List<ItemStack> out = new ArrayList<>();
        if (FMLEnvironment.dist != Dist.CLIENT) return out;      // 皮肤表在客户端资源里
        try {
            List<ResourceLocation> guns = new ArrayList<>();
            for (var entry : TimelessAPI.getAllClientGunIndex()) guns.add(entry.getKey());
            guns.sort(Comparator.comparing(ResourceLocation::toString));
            for (ResourceLocation gunId : guns) {
                ItemStack gunStack = GunItemBuilder.create().setId(gunId).build();
                if (gunStack.isEmpty()) continue;
                GunSkinCatalog.Info info = GunSkinCatalog.forStack(gunStack);
                for (int i = 1; i < info.ids.size(); i++) {      // 跳过第 0 套（默认皮肤）
                    out.add(SkinCardItem.create(gunId, info.ids.get(i), 1));
                }
            }
        } catch (Throwable ignored) {
        }
        if (net.minecraftforge.fml.ModList.get().isLoaded("lrtactical")) {
            var melee = new ArrayList<>(me.xjqsh.lrtactical.api.LrTacticalAPI.getMeleeIndexes());
            melee.sort(Comparator.comparing(index -> index.getId().toString()));
            for (var index : melee) {
                GunSkinCatalog.Info info = GunSkinCatalog.forStack(index.createItemStack());
                for (int i = 1; i < info.ids.size(); i++)
                    out.add(SkinCardItem.create(index.getId(), info.ids.get(i), 1));
            }
        }
        return out;
    }
}
