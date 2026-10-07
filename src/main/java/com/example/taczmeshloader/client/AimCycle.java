package com.example.taczmeshloader.client;

import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * 「同一个键：开镜 → 切倍率 → 关镜」的客户端实现（由 config 开关，默认关）。
 *
 * <p>只在玩家把 TaCZ 的「长按瞄准」(hold_to_aim) 设为 <b>false</b>（切换式开镜）时生效；
 * 用长按的玩家行为完全不变——这正是"两套都留"的做法：不需要两份资源配置。</p>
 *
 * <p>原理：拦截 TaCZ {@code AimKey.onAimPress}（默认鼠标右键按下）：</p>
 * <ul>
 *   <li>还没开镜 → 放行（TaCZ 正常开镜，落在第 1 档倍率）</li>
 *   <li>已开镜、且当前不是最后一档 → 调用 TaCZ 自己的缩放逻辑切下一档，并取消这次的"关镜"</li>
 *   <li>已开镜、且已经是最后一档 → 放行（TaCZ 关镜）</li>
 * </ul>
 *
 * <p>档数取自当前狙镜 display 的 {@code zoom} 数组长度；计数在<b>每次缩放</b>时自增
 * （见 {@code ZoomKeyMixin} 的 TAIL 钩子，所以玩家自己按缩放键切档也会计数，不会错位），
 * 离开开镜状态时归零。</p>
 */
public final class AimCycle {

    private AimCycle() {}

    /** 本次开镜已切过几次档。 */
    private static int advanced = 0;

    /** 最近一次解析出的手持枪镜档数缓存（按镜 id 记，避免每次按键都查表）。 */
    private static ResourceLocation cachedScopeId = null;
    private static int cachedSteps = 1;

    /**
     * 是否要吃掉这次"瞄准键按下"（返回 true ⇒ 调用方 cancel TaCZ 的开镜切换）。
     * 返回 false 时 TaCZ 的行为完全不变。
     */
    public static boolean interceptAimPress() {
        try {
            if (!com.example.taczmeshloader.config.TmlClientConfig.aimCycleZoomEnabled()) return false;
            // 长按用户：保持现状（TaCZ 原样处理开镜）
            if (Boolean.TRUE.equals(com.tacz.guns.config.client.KeyConfig.HOLD_TO_AIM.get())) return false;

            Minecraft mc = Minecraft.getInstance();
            LocalPlayer player = mc.player;
            if (player == null) return false;
            IClientPlayerGunOperator operator = IClientPlayerGunOperator.fromLocalPlayer(player);
            if (operator == null) return false;
            if (!operator.isAim()) {          // 这一下是"开镜"：计数归零后放行
                advanced = 0;
                return false;
            }
            int steps = zoomSteps(player.getMainHandItem());
            if (steps <= 1) return false;      // 没镜 / 单档：保持 TaCZ 原生开关镜
            if (advanced >= steps - 1) return false;   // 已在最后一档：放行 = 关镜

            // 切下一档：直接发 TaCZ 自己的"玩家切倍率"同步包（与它 ZoomKey 内部做的事完全一致）。
            // ★ 这样就不必用 @Invoker 挂 TaCZ 的私有方法：万一 TaCZ 改名/重构，这功能静默失效而不是报错。
            com.tacz.guns.network.NetworkHandler.CHANNEL.sendToServer(
                    new com.tacz.guns.network.message.ClientMessagePlayerZoom());
            return true;
        } catch (Throwable ignored) {
            return false;                      // 任何异常都不干预游戏
        }
    }

    /** 每次倍率变化（含玩家自己按缩放键）都记一笔，保证计数与真实档位同步。 */
    public static void onZoomChanged() {
        advanced++;
    }

    /** 当前手持枪的镜倍率档数：装上的镜优先，其次"物品自带镜"（如示例物品的内置 2x）。 */
    private static int zoomSteps(ItemStack gunStack) {
        try {
            IGun gun = IGun.getIGunOrNull(gunStack);
            if (gun == null) return 1;
            ResourceLocation scopeId = gun.getAttachmentId(gunStack, AttachmentType.SCOPE);
            if (scopeId == null) scopeId = gun.getBuiltInAttachmentId(gunStack, AttachmentType.SCOPE);
            if (scopeId == null) return 1;
            if (scopeId.equals(cachedScopeId)) return cachedSteps;
            var display = com.tacz.guns.client.resource.ClientAssetsManager.INSTANCE.getAttachmentDisplay(scopeId);
            float[] zoom = display == null ? null : display.getZoom();
            int steps = (zoom == null || zoom.length == 0) ? 1 : zoom.length;
            cachedScopeId = scopeId;
            cachedSteps = steps;
            return steps;
        } catch (Throwable ignored) {
            return 1;
        }
    }
}
