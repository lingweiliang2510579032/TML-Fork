package com.example.taczmeshloader.client;

import com.example.taczmeshloader.item.SkinCardItem;
import com.example.taczmeshloader.item.SkinWeapon;
import com.example.taczmeshloader.network.GunSkinNetwork;
import com.example.taczmeshloader.network.SetGunSkinMessage;
import com.tacz.guns.client.gui.components.refit.IStackTooltip;
import com.tacz.guns.client.gui.components.refit.RefitTurnPageButton;
import com.tacz.guns.client.gui.components.refit.RefitUnloadButton;
import com.tacz.guns.client.input.RefitKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** LRT has no refit screen; reuse the gun skin controls on the same remappable Z key. */
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class MeleeSkinScreen extends Screen {
    private final int weaponSlot;
    private final ResourceLocation weaponId;
    private int page;
    private boolean selected = true;
    private String inventorySignature = "";

    private MeleeSkinScreen(int slot, ResourceLocation id) {
        super(Component.translatable("taczmeshloader.type.skin.name"));
        weaponSlot = slot;
        weaponId = id;
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (event.getAction() != 1 || !RefitKey.REFIT_KEY.matches(event.getKey(), event.getScanCode())
                || mc.screen != null || mc.player == null || mc.player.isSpectator()) return;
        ItemStack stack = mc.player.getMainHandItem();
        if (SkinWeapon.isMelee(stack) && GunSkinCatalog.refittable(stack) != null)
            mc.setScreen(new MeleeSkinScreen(mc.player.getInventory().selected, SkinWeapon.id(stack)));
    }

    @Override
    protected void init() {
        if (minecraft.player == null) return;
        var inv = minecraft.player.getInventory();
        var weapon = inv.getItem(weaponSlot);
        var info = GunSkinCatalog.refittable(weapon);
        if (info == null || !weaponId.equals(SkinWeapon.id(weapon))) { onClose(); return; }
        int x = width - 30;
        addRenderableWidget(new GunSkinSlot(x, 10, inv, weaponSlot, selected,
                b -> { selected = !selected; page = 0; rebuild(); }));
        if (!selected) return;
        var cards = new java.util.ArrayList<Integer>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var card = inv.getItem(i);
            if (card.getItem() instanceof SkinCardItem && weaponId.equals(SkinCardItem.gunId(card))
                    && info.ids.contains(SkinCardItem.skinId(card))
                    && !info.ids.get(0).equals(SkinCardItem.skinId(card))) cards.add(i);
        }
        page = Math.max(0, Math.min(page, (Math.max(1, cards.size()) - 1) / 8));
        for (int n = page * 8; n < cards.size() && n < (page + 1) * 8; n++) {
            int slot = cards.get(n);
            addRenderableWidget(new GunSkinCardSlot(x, 50 + (n % 8) * 18, slot, inv,
                    b -> send(new SetGunSkinMessage(weaponSlot, info.nbtKey, SkinCardItem.skinId(inv.getItem(slot)), slot))));
        }
        if ((page + 1) * 8 < cards.size()) addRenderableWidget(new RefitTurnPageButton(x, 196, false,
                b -> { page++; rebuild(); }));
        if (page > 0) addRenderableWidget(new RefitTurnPageButton(x, 40, true,
                b -> { page--; rebuild(); }));
        String skin = GunSkinSlot.installedSkin(weapon, info);
        if (!skin.isEmpty() && !skin.equals(info.ids.get(0)))
            addRenderableWidget(new RefitUnloadButton(x + 5, 30,
                    b -> send(new SetGunSkinMessage(weaponSlot, info.nbtKey, info.ids.get(0)))));
    }

    private void send(SetGunSkinMessage message) { GunSkinNetwork.CHANNEL.sendToServer(message); }
    private void rebuild() { clearWidgets(); init(); }

    @Override
    public void tick() {
        if (minecraft.player == null) { onClose(); return; }
        var inv = minecraft.player.getInventory();
        if (inv.selected != weaponSlot || !weaponId.equals(SkinWeapon.id(inv.getItem(weaponSlot)))) {
            onClose(); return;
        }
        // Only rebuild after the authoritative inventory update, so consumed cards disappear correctly.
        StringBuilder key = new StringBuilder(String.valueOf(inv.getItem(weaponSlot).getTag()));
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var card = inv.getItem(i);
            if (card.getItem() instanceof SkinCardItem) key.append(i).append(':').append(card.getCount()).append(card.getTag());
        }
        String signature = key.toString();
        if (!signature.equals(inventorySignature)) { inventorySignature = signature; rebuild(); }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(font, Component.literal(GunSkinCatalog.gunName(weaponId)), 12, 12, 0xFFFFFF);
        for (var child : children()) if (child instanceof IStackTooltip tooltip)
            tooltip.renderTooltip(stack -> graphics.renderTooltip(font, stack, mouseX, mouseY));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean keyPressed(int key, int scan, int modifiers) {
        if (RefitKey.REFIT_KEY.matches(key, scan)) { onClose(); return true; }
        return super.keyPressed(key, scan, modifiers);
    }
}
