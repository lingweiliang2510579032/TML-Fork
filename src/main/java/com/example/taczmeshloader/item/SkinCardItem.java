package com.example.taczmeshloader.item;

import com.example.taczmeshloader.client.GunSkinCatalog;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 皮肤卡（通用物品）：一张卡对应"某把枪的某套皮肤"。
 *
 * <p>NBT：{@code GunId}=枪 id，{@code Skin}=皮肤 id（与资源配置 geo extras 里 skins[].id 一致）。
 * 卡本身不含任何枪名/品牌：名字与说明都从**资源配置数据**（geo extras 的 skins 表）里读，
 * 所以同一张卡物品可以被任意资源配置复用。</p>
 *
 * <p>用法：一只手持枪、另一只手拿卡 → 右键 → 给那把枪换上这张卡的皮肤，卡消耗 1 张
 * （创造模式不消耗）。</p>
 */
public class SkinCardItem extends Item {

    /** 存皮肤选择的 NBT 键（与 geo extras 的 skin_nbt_key 默认值一致）。 */
    public static final String SKIN_NBT_KEY = "MeshSkin";

    public static final String TAG_GUN_ID = "GunId";
    public static final String TAG_SKIN = "Skin";

    public SkinCardItem(Properties properties) {
        super(properties);
    }

    /** 这张卡描述的枪 id。 */
    @Nullable
    public static ResourceLocation gunId(ItemStack stack) {
        if (stack.getTag() == null || !stack.getTag().contains(TAG_GUN_ID)) return null;
        return ResourceLocation.tryParse(stack.getTag().getString(TAG_GUN_ID));
    }

    /** 这张卡描述的皮肤 id。 */
    public static String skinId(ItemStack stack) {
        return stack.getTag() == null ? "" : stack.getTag().getString(TAG_SKIN);
    }

    public static ItemStack create(ResourceLocation gunId, String skin, int count) {
        ItemStack stack = new ItemStack(ModItems.SKIN_CARD.get(), count);
        stack.getOrCreateTag().putString(TAG_GUN_ID, gunId.toString());
        stack.getOrCreateTag().putString(TAG_SKIN, skin);
        return stack;
    }

    @Override
    public @NotNull Component getName(@NotNull ItemStack stack) {
        String skin = displayName(gunId(stack), skinId(stack));
        if (skin.isEmpty()) return Component.translatable("item.taczmeshloader.skin_card");
        return Component.translatable("item.taczmeshloader.skin_card.named", skin);
    }

    /** 皮肤显示名：只在客户端读资源配置数据（服务端退化成 id），用 dist 守卫避免专用服务器踩到客户端类。 */
    private static String displayName(ResourceLocation gunId, String skinId) {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != net.minecraftforge.api.distmarker.Dist.CLIENT) {
            return skinId == null ? "" : skinId;
        }
        try {
            return GunSkinCatalog.skinName(gunId, skinId);
        } catch (Throwable t) {
            return skinId == null ? "" : skinId;
        }
    }

    private static String gunDisplayName(ResourceLocation gunId) {
        if (net.minecraftforge.fml.loading.FMLEnvironment.dist != net.minecraftforge.api.distmarker.Dist.CLIENT) {
            return gunId == null ? "" : gunId.toString();
        }
        try {
            return GunSkinCatalog.gunName(gunId);
        } catch (Throwable t) {
            return gunId == null ? "" : gunId.toString();
        }
    }

    @Override
    public void appendHoverText(@NotNull ItemStack stack, @Nullable Level level,
                                @NotNull List<Component> tooltip, @NotNull TooltipFlag flag) {
        ResourceLocation gun = gunId(stack);
        tooltip.add(Component.translatable("item.taczmeshloader.skin_card.tip.gun",
                        gunDisplayName(gun)).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.taczmeshloader.skin_card.tip.use").withStyle(ChatFormatting.DARK_GRAY));
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(@NotNull Level level, @NotNull Player player,
                                                           @NotNull InteractionHand hand) {
        ItemStack card = player.getItemInHand(hand);
        InteractionHand other = hand == InteractionHand.MAIN_HAND ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack target = player.getItemInHand(other);
        ResourceLocation gunId = gunId(card);
        String skin = skinId(card);

        if (gunId == null || skin.isEmpty() || SkinWeapon.id(target) == null) {
            if (level.isClientSide) {
                player.displayClientMessage(Component.translatable("item.taczmeshloader.skin_card.msg.need_gun")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(card);
        }
        if (!gunId.equals(SkinWeapon.id(target))) {
            if (level.isClientSide) {
                player.displayClientMessage(Component.translatable("item.taczmeshloader.skin_card.msg.wrong_gun")
                        .withStyle(ChatFormatting.RED), true);
            }
            return InteractionResultHolder.fail(card);
        }
        if (level.isClientSide) {
            player.displayClientMessage(Component.translatable("item.taczmeshloader.skin_card.msg.applied",
                    displayName(gunId, skin)).withStyle(ChatFormatting.AQUA), true);
            return InteractionResultHolder.sidedSuccess(card, true);
        }
        // 服务端才是权威：写枪 NBT（键名与资源配置 geo extras 的 skin_nbt_key 一致，TML 默认 MeshSkin）
        target.getOrCreateTag().putString(SKIN_NBT_KEY, skin);
        if (!player.getAbilities().instabuild) card.shrink(1);
        return InteractionResultHolder.success(card);
    }
}
