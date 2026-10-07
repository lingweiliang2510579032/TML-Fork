package com.example.taczmeshloader.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.client.gui.GunRefitScreen;
import com.tacz.guns.client.gui.components.refit.IStackTooltip;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * 背包里的皮肤卡槽：与 TaCZ 的 {@code InventoryAttachmentSlot} 同一画法（外框 + {@code renderItem}），
 * 悬停时由 TaCZ 改装界面的物品提示通道渲染这张卡自己的 tooltip。点一下即安装并消耗这张卡。
 */
@OnlyIn(Dist.CLIENT)
public class GunSkinCardSlot extends Button implements IStackTooltip {

    private final int slotIndex;
    private final Inventory inventory;

    public GunSkinCardSlot(int x, int y, int slotIndex, Inventory inventory, OnPress onPress) {
        super(x, y, GunRefitScreen.SLOT_SIZE, GunRefitScreen.SLOT_SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
        this.slotIndex = slotIndex;
        this.inventory = inventory;
    }

    public int getSlotIndex() {
        return slotIndex;
    }

    @Override
    public void renderTooltip(Consumer<ItemStack> consumer) {
        if (this.isHoveredOrFocused() && 0 <= slotIndex && slotIndex < inventory.getContainerSize()) {
            consumer.accept(inventory.getItem(slotIndex));
        }
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();

        int x = getX(), y = getY();
        if (isHoveredOrFocused()) {
            graphics.blit(GunRefitScreen.SLOT_TEXTURE, x, y, 0, 0, width, height, 18, 18);
        } else {
            graphics.blit(GunRefitScreen.SLOT_TEXTURE, x + 1, y + 1, 1, 1, width - 2, height - 2, 18, 18);
        }
        graphics.renderItem(inventory.getItem(slotIndex), x + 1, y + 1);

        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }
}
