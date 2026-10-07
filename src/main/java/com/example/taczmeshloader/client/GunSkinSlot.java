package com.example.taczmeshloader.client;

import com.example.taczmeshloader.item.SkinCardItem;
import com.mojang.blaze3d.systems.RenderSystem;
import com.tacz.guns.client.gui.GunRefitScreen;
import com.tacz.guns.client.gui.components.refit.IStackTooltip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * 改装界面"类型行"里的皮肤槽，画法与交互逐行照 TaCZ 的 {@code GunAttachmentSlot}：
 * 外框用 {@code refit_slot.png}（悬停或选中画整框、平时内缩 1px）、中间画物品图标、
 * 悬停时在槽下方居中写名字、物品提示走 TaCZ 的 {@link IStackTooltip} 通道。
 *
 * <ul>
 *   <li>不能换皮肤的枪（没有皮肤表，或表里只有一套）→ 画 TaCZ 的"斜杠"图标，点了不反应；</li>
 *   <li>能换皮肤、当前是默认皮肤（原皮）→ 槽内为空；</li>
 *   <li>装了皮肤 → 槽内画那张皮肤卡的图标。</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
public class GunSkinSlot extends Button implements IStackTooltip {

    /** 与 TaCZ {@code GunRefitScreen.getSlotTextureXOffset} 里"不允许"用的同一格图标。 */
    private static final int SLASH_ICON_U = GunRefitScreen.ICON_UV_SIZE * 6;

    /** 空槽（原皮）用的"皮肤卡"细线图标（自己画的，风格对齐 TaCZ 卡槽图标：近白细线）。 */
    private static final ResourceLocation SKIN_ICON =
            new ResourceLocation("taczmeshloader", "textures/gui/skin_slot_icon.png");
    private static final int ICON_SIZE = GunRefitScreen.ICON_UV_SIZE;   // 32

    private final Inventory inventory;
    private final int gunSlot;
    private final boolean selected;

    public GunSkinSlot(int x, int y, Inventory inventory, int gunSlot, boolean selected, OnPress onPress) {
        super(x, y, GunRefitScreen.SLOT_SIZE, GunRefitScreen.SLOT_SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
        this.inventory = inventory;
        this.gunSlot = gunSlot;
        this.selected = selected;
    }

    /** 槽里要画的那张皮肤卡；原皮（默认皮肤）与不能换皮肤的枪都返回空。 */
    private ItemStack cardStack() {
        ItemStack gun = inventory.getItem(gunSlot);
        GunSkinCatalog.Info info = GunSkinCatalog.refittable(gun);
        if (info == null) return ItemStack.EMPTY;
        String skin = installedSkin(gun, info);
        if (skin.isEmpty() || skin.equals(info.ids.get(0))) return ItemStack.EMPTY;   // 原皮：槽内留空
        ResourceLocation id = com.example.taczmeshloader.item.SkinWeapon.id(gun);
        return id == null ? ItemStack.EMPTY : SkinCardItem.create(id, skin, 1);
    }

    /** 枪上实际写着的皮肤 id；没写过 = 默认（原皮）。 */
    static String installedSkin(ItemStack gun, GunSkinCatalog.Info info) {
        if (gun.getTag() == null || !gun.getTag().contains(info.nbtKey)) return "";
        return gun.getTag().getString(info.nbtKey);
    }

    @Override
    public void renderTooltip(Consumer<ItemStack> consumer) {
        if (!this.isHoveredOrFocused()) return;
        ItemStack card = cardStack();
        if (!card.isEmpty()) consumer.accept(card);
    }

    @Override
    public void renderWidget(@NotNull GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        ItemStack gun = inventory.getItem(gunSlot);
        GunSkinCatalog.Info info = GunSkinCatalog.refittable(gun);
        ItemStack card = cardStack();
        int x = this.getX(), y = this.getY();

        RenderSystem.disableDepthTest();
        RenderSystem.enableBlend();
        if (this.isHoveredOrFocused() || selected) {
            graphics.blit(GunRefitScreen.SLOT_TEXTURE, x, y, 0, 0, width, height,
                    GunRefitScreen.SLOT_SIZE, GunRefitScreen.SLOT_SIZE);
        } else {
            graphics.blit(GunRefitScreen.SLOT_TEXTURE, x + 1, y + 1, 1, 1, width - 2, height - 2,
                    GunRefitScreen.SLOT_SIZE, GunRefitScreen.SLOT_SIZE);
        }
        // 槽内图标：不能换皮肤的枪 = TaCZ 的斜杠；装了皮肤 = 那张卡；原皮 = 空
        if (info == null) {
            graphics.blit(GunRefitScreen.ICONS_TEXTURE, x + 2, y + 2, width - 4, height - 4,
                    SLASH_ICON_U, 0, GunRefitScreen.ICON_UV_SIZE, GunRefitScreen.ICON_UV_SIZE,
                    GunRefitScreen.getSlotsTextureWidth(), GunRefitScreen.ICON_UV_SIZE);
        } else if (!card.isEmpty()) {
            graphics.renderItem(card, x + 1, y + 1);
        } else {
            // 空槽（能换皮肤、当前是原皮）：照 TaCZ 其他卡槽的风格，画一枚自己的"皮肤卡"细线字形
            // （TaCZ 的空槽图标就是这种近白细线，不是深色剪影；坐标也用它的 x+2/y+2、width-4）
            graphics.blit(SKIN_ICON, x + 2, y + 2, width - 4, height - 4,
                    0, 0, ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE);
        }
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();

        // 悬停名字：TaCZ 同样画在槽下方居中（选中且槽里有东西时再下移 10px）
        if (this.isHoveredOrFocused()) {
            Font font = Minecraft.getInstance().font;
            int nameY = y + 20 + (selected && !card.isEmpty() ? 10 : 0);
            graphics.drawCenteredString(font, Component.translatable("taczmeshloader.type.skin.name"),
                    x + width / 2, nameY, 0xFFFFFF);
        }
    }
}
