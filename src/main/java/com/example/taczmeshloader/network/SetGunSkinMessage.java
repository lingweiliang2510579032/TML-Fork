package com.example.taczmeshloader.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * C2S：设置"枪皮肤"（写进枪物品 NBT）。服务端只做基本校验（物品确实是 TaCZ 枪、皮肤 id 长度/字符合法、
 * 槽位合法），不依赖服务端解析皮肤表——因为皮肤表属于客户端外观资源。
 */
public class SetGunSkinMessage {

    private final int inventorySlot;
    private final String nbtKey;
    private final String skinId;
    /** 皮肤卡所在背包槽（安装模式：装上后消耗这张卡）；-1 = 只改皮肤不消耗（旧的循环切换用）。 */
    private final int cardSlot;

    public SetGunSkinMessage(int inventorySlot, String nbtKey, String skinId) {
        this(inventorySlot, nbtKey, skinId, -1);
    }

    public SetGunSkinMessage(int inventorySlot, String nbtKey, String skinId, int cardSlot) {
        this.inventorySlot = inventorySlot;
        this.nbtKey = nbtKey;
        this.skinId = skinId;
        this.cardSlot = cardSlot;
    }

    public static void encode(SetGunSkinMessage m, FriendlyByteBuf buf) {
        buf.writeVarInt(m.inventorySlot);
        buf.writeUtf(m.nbtKey, 64);
        buf.writeUtf(m.skinId, 64);
        buf.writeVarInt(m.cardSlot + 1);
    }

    public static SetGunSkinMessage decode(FriendlyByteBuf buf) {
        return new SetGunSkinMessage(buf.readVarInt(), buf.readUtf(64), buf.readUtf(64), buf.readVarInt() - 1);
    }

    public static void handle(SetGunSkinMessage m, Supplier<NetworkEvent.Context> ctxSupplier) {
        NetworkEvent.Context ctx = ctxSupplier.get();
        if (!ctx.getDirection().getReceptionSide().isServer()) {
            return;
        }
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) return;
            if (m.inventorySlot < 0 || m.inventorySlot >= player.getInventory().getContainerSize()) return;
            if (!isLegalToken(m.nbtKey) || !isLegalToken(m.skinId)) return;
            ItemStack stack = player.getInventory().getItem(m.inventorySlot);
            var targetId = com.example.taczmeshloader.item.SkinWeapon.id(stack);
            if (targetId == null) return;
            // Melee skins use the same MeshSkin contract; no arbitrary NBT writes/free card installs.
            if (com.example.taczmeshloader.item.SkinWeapon.isMelee(stack)
                    && (!"MeshSkin".equals(m.nbtKey) || (m.cardSlot < 0 && !"base".equals(m.skinId)))) return;
            // 安装模式：确认来源槽位真是一张"皮肤卡"（带 GunId/Skin 的物品），才写皮肤并消耗它
            if (m.cardSlot >= 0) {
                if (m.cardSlot == m.inventorySlot || m.cardSlot >= player.getInventory().getContainerSize()) return;
                ItemStack card = player.getInventory().getItem(m.cardSlot);
                if (!(card.getItem() instanceof com.example.taczmeshloader.item.SkinCardItem)
                        || !targetId.equals(com.example.taczmeshloader.item.SkinCardItem.gunId(card))
                        || !m.skinId.equals(com.example.taczmeshloader.item.SkinCardItem.skinId(card))) {
                    return;
                }
                if (!player.getAbilities().instabuild) card.shrink(1);
            }
            stack.getOrCreateTag().putString(m.nbtKey, m.skinId);
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
        });
        ctx.setPacketHandled(true);
    }

    /** 只允许 [A-Za-z0-9_./:-]，长度 1..64。 */
    private static boolean isLegalToken(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '.' || c == '/' || c == ':' || c == '-';
            if (!ok) return false;
        }
        return true;
    }
}
