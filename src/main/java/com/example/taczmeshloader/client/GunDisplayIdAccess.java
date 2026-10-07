package com.example.taczmeshloader.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 由 {@code GunDisplayInstanceMixin} 实现的访问接口：把 TaCZ 展示实例里私有的 displayId 暴露出来。
 *
 * <p>皮肤表需要"枪物品 → 展示 → geo"这条链，而枪物品 NBT 里的 displayId 常常是
 * {@code tacz:default}（TaCZ 的回落值），客户端又读不到 {@code data/} 下的 index 文件，
 * 所以改由展示实例自己告诉我们它是谁（见 {@link GunSkinCatalog#rememberDisplayGeo}）。</p>
 */
@OnlyIn(Dist.CLIENT)
public interface GunDisplayIdAccess {
    ResourceLocation taczmeshloader$displayId();
}
