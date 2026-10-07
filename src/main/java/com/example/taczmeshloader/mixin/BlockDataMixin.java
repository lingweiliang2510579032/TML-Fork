package com.example.taczmeshloader.mixin;

import com.tacz.guns.resource.pojo.data.block.BlockData;
import com.tacz.guns.resource.pojo.data.block.TabConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * 给 TaCZ 的**配件工作台**补一个"皮肤"页签（tab）。
 *
 * <p>TaCZ 的工作台页签来自方块数据 {@code data/<ns>/data/blocks/<workbench>.json}，
 * 配方靠 {@code result.group} 匹配页签 id 才会在那个工作台的界面里出现。</p>
 *
 * <p>这里不动 TaCZ 的数据文件（那样会跟 TaCZ 自带包/别的资源配置互相覆盖），而是在
 * {@link BlockData#getTabs()} 返回时**追加**一个页签：只认"页签里带 {@code tacz:scope}
 * 的那个方块"= TaCZ 的配件工作台，其它工作台一概不动。皮肤卡的配方写
 * {@code "group": "taczmeshloader:skin"} 就会落到这一页里。零枪名零品牌。</p>
 */
@Mixin(value = BlockData.class, remap = false)
public class BlockDataMixin {

    private static final ResourceLocation TML_SKIN_TAB = new ResourceLocation("taczmeshloader", "skin");

    @Inject(method = "getTabs", at = @At("RETURN"), cancellable = true, remap = false)
    private void tml$appendSkinTab(CallbackInfoReturnable<List<TabConfig>> cir) {
        List<TabConfig> tabs = cir.getReturnValue();
        if (tabs == null || tabs.isEmpty()) return;
        boolean attachmentBench = false;
        boolean already = false;
        for (TabConfig tab : tabs) {
            if (TabConfig.TAB_SCOPE.equals(tab.id())) attachmentBench = true;
            if (TML_SKIN_TAB.equals(tab.id())) already = true;
        }
        if (!attachmentBench || already) return;
        List<TabConfig> out = new ArrayList<>(tabs);
        out.add(new TabConfig(TML_SKIN_TAB, "taczmeshloader.type.skin.name", icon()));
        cir.setReturnValue(out);
    }

    private static ItemStack icon() {
        try {
            return com.example.taczmeshloader.item.ModItems.SKIN_CARD.get().getDefaultInstance();
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }
}
