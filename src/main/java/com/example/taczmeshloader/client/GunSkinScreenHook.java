package com.example.taczmeshloader.client;

import com.example.taczmeshloader.item.SkinCardItem;
import com.example.taczmeshloader.network.GunSkinNetwork;
import com.example.taczmeshloader.network.SetGunSkinMessage;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.attachment.AttachmentType;
import com.tacz.guns.client.animation.screen.RefitTransform;
import com.tacz.guns.client.gui.GunRefitScreen;
import com.tacz.guns.client.gui.components.refit.RefitTurnPageButton;
import com.tacz.guns.client.gui.components.refit.RefitUnloadButton;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * 在 TaCZ 改装界面（按 Z）里按 TaCZ 自己的排布规则追加"皮肤"槽，规则与配件槽完全一致：
 * <ul>
 *   <li><b>类型行</b>：TaCZ 是 {@code startX = width - 30}、每加一个类型槽 {@code startX -= 18}
 *       （NONE 不占位），皮肤槽接在这一行最左、{@code y = 10}；不支持的枪画斜杠；</li>
 *   <li><b>选中皮肤槽后</b>：右侧背包列（{@code x = width - 30}，从 {@code y = 50} 往下）列出这把枪的
 *       皮肤卡，每页 8 个（与 TaCZ 的 {@code INVENTORY_ATTACHMENT_SLOT_COUNT} 相同），多于 8 个时用
 *       TaCZ 的翻页按钮；</li>
 *   <li><b>已装皮肤时</b>：在皮肤槽下方画 TaCZ 的"卸载"按钮，点了回到默认皮肤（原皮）。</li>
 * </ul>
 * 位置常量、外框、图标、翻页与卸载按钮全部复用 TaCZ 自己的字段/控件，保证与配件槽看起来一模一样。
 *
 * <p>挂载方式（只用公开 API，不碰 mixin）：首次 {@code ScreenEvent.Init.Post} 记下事件（它的
 * listener 列表就是界面自己的 list，引用一直在），把控件 addListener 进去；之后每一帧
 * {@code ScreenEvent.Render.Pre} 自检一次——TaCZ 每次点槽/换页都会 {@code this.init()} 并
 * {@code clearWidgets()} 把我们的控件清掉，自检发现不在列表里就立刻补回来。
 * （0.3.6 用过"跟着 TaCZ init 挂"的 mixin，但 mixin 不允许继承目标类、@Invoker 又拿不到混淆映射，
 * 两条路都不通，所以改回纯公开 API + 每帧自检。）</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class GunSkinScreenHook {

    private GunSkinScreenHook() {}

    private static final Logger LOG = LogManager.getLogger("MeshyLoader");
    private static final int SLOT = GunRefitScreen.SLOT_SIZE;
    /** 与 TaCZ 的 INVENTORY_ATTACHMENT_SLOT_COUNT 一致。 */
    private static final int PAGE_SIZE = 8;
    /** TaCZ: startX = width - 30 = width - SLOT_SIZE - 12。 */
    private static final int RIGHT_MARGIN = SLOT + 12;
    /** TaCZ: 类型行 y = 10，背包列 y = 50。 */
    private static final int TYPE_ROW_Y = 10;
    private static final int LIST_Y = 50;

    /** 皮肤类型是否被选中（选中后右侧才列皮肤卡）。 */
    private static boolean skinSelected = false;
    private static int page = 0;
    private static WeakReference<Screen> lastScreen = new WeakReference<>(null);
    /** 诊断用：本次 build 列出的可装皮肤卡数量。 */
    private static int cardCount = -1;
    /** 最近一次 Init.Post 事件：它的 listener 列表就是界面自己的 list（clearWidgets 清的是同一个 list）。 */
    private static ScreenEvent.Init.Post lastInitEvent = null;
    /** 本次挂上去的控件（自检用：还在不在列表里）。 */
    private static final List<AbstractWidget> ATTACHED = new ArrayList<>();

    /** 改装界面 init 完成：记下事件并把控件挂上去。 */
    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof GunRefitScreen screen)) return;
        lastInitEvent = event;
        attach(screen);
    }

    /**
     * 每帧自检：TaCZ 自己 init() 过之后 clearWidgets() 会把我们的控件清掉，发现不在列表里就补回来。
     * 只用公开 API（{@code Init.Post#getListenersList} 返回的就是界面自己的 children 列表）。
     */
    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Pre event) {
        if (!(event.getScreen() instanceof GunRefitScreen screen)) return;
        if (lastInitEvent == null || lastScreen.get() != screen) return;
        for (AbstractWidget w : ATTACHED) {
            if (!lastInitEvent.getListenersList().contains(w)) {
                LOG.info("[SkinDbg] 皮肤槽被界面清掉，补挂 selected={} 卡数={}", skinSelected, cardCount);
                attach(screen);
                return;
            }
        }
    }

    /** 构造并挂上本次需要的全部控件（幂等：每次都会重新构造一套）。 */
    private static void attach(GunRefitScreen screen) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || lastInitEvent == null) return;
        if (lastScreen.get() != screen) {          // 换了界面实例（重开/换枪）就回到未选中
            lastScreen = new WeakReference<>(screen);
            skinSelected = false;
            page = 0;
        }
        cardCount = -1;
        List<AbstractWidget> out = new ArrayList<>();
        try {
            rebuild(out, screen, mc);
        } catch (Throwable t) {
            LOG.warn("[SkinDbg] 皮肤槽初始化异常（已吞掉）", t);
        }
        ATTACHED.clear();
        for (AbstractWidget w : out) {
            lastInitEvent.addListener(w);
            ATTACHED.add(w);
        }
        LOG.info("[SkinDbg] 皮肤槽挂载: 控件={} selected={} 卡数={} TaCZ类型={}",
                out.size(), skinSelected, cardCount, RefitTransform.getCurrentTransformType());
    }

    /** 状态变化后立刻重排（TaCZ 的 init() 清空之后在同一帧内把我们的控件补回去，避免闪一下）。 */
    private static void refresh(GunRefitScreen screen) {
        attach(screen);
    }

    private static void rebuild(List<AbstractWidget> out, GunRefitScreen screen, Minecraft mc) {
        Inventory inv = mc.player.getInventory();
        final int gunSlot = inv.selected;
        int typeX = screen.width - RIGHT_MARGIN - SLOT * typeSlotCount();
        ItemStack gun = inv.getItem(gunSlot);
        GunSkinCatalog.Info info = GunSkinCatalog.refittable(gun);
        // TaCZ 自己的"背包配件列"和我们的"皮肤卡列"是同一组坐标（x=width-30、y=50 起）⇒ 同一时刻只留一个：
        // 玩家点了某个配件类型（TaCZ 选中态非 NONE）时，我们的槽只是**暂时不高亮/不列卡**，
        // 但 skinSelected 保留（不然 TaCZ 动画没结束时一次失败的切换会把选择吞掉，表现=点一下就没反应）。
        boolean showColumn = skinSelected && RefitTransform.getCurrentTransformType() == AttachmentType.NONE;

        out.add(new GunSkinSlot(typeX, TYPE_ROW_Y, inv, gunSlot, showColumn, b -> {
                if (GunSkinCatalog.refittable(inv.getItem(gunSlot)) == null) {          // 不支持：点了不反应
                LOG.info("[SkinDbg] 皮肤槽点击被忽略（这把枪没有可换的皮肤）");
                return;
            }
            LOG.info("[SkinDbg] 皮肤槽点击: selected {} -> {}", skinSelected, !skinSelected);
            // 选中皮肤前先请 TaCZ 退回"概览"，否则它的配件列会跟我们叠在同一列
            if (RefitTransform.getCurrentTransformType() != AttachmentType.NONE) {
                RefitTransform.changeRefitScreenView(AttachmentType.NONE);
            }
            skinSelected = !skinSelected;
            page = 0;
            screen.resize(mc, screen.width, screen.height);   // init() 是 protected，resize 是它的 public 包装
            refresh(screen);
        }));
        if (!showColumn || info == null) return;

        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null) return;

        // 背包列：与 TaCZ 的 addInventoryAttachmentButtons 同一套坐标
        int colX = screen.width - RIGHT_MARGIN;
        List<Integer> cards = matchingCards(inv, iGun.getGunId(gun));
        cardCount = cards.size();
        int pageStart = page * PAGE_SIZE;
        int shown = 0;
        for (int k = pageStart; k < cards.size() && shown < PAGE_SIZE; k++, shown++) {
            final int cardSlot = cards.get(k);
            out.add(new GunSkinCardSlot(colX, LIST_Y + shown * SLOT, cardSlot, inv,
                    b -> install(screen, inv, gunSlot, cardSlot, info.nbtKey)));
        }
        int totalPage = (cards.size() - 1) / PAGE_SIZE;
        if (page < totalPage) {
            out.add(new RefitTurnPageButton(colX, LIST_Y + SLOT * PAGE_SIZE + 2, false,
                    b -> { page++; screen.resize(mc, screen.width, screen.height); refresh(screen); }));
        }
        if (page > 0) {
            out.add(new RefitTurnPageButton(colX, LIST_Y - 10, true,
                    b -> { page--; screen.resize(mc, screen.width, screen.height); refresh(screen); }));
        }
        // 已装皮肤 → TaCZ 的卸载按钮（位置同 TaCZ：类型槽下方 +5,+20）
        String installed = GunSkinSlot.installedSkin(gun, info);
        if (!installed.isEmpty() && !installed.equals(info.ids.get(0))) {
            out.add(new RefitUnloadButton(typeX + 5, TYPE_ROW_Y + SLOT + 2,
                    b -> uninstall(screen, gunSlot, info)));
        }
    }

    /** 背包里属于这把枪的皮肤卡（槽位下标）。 */
    private static List<Integer> matchingCards(Inventory inv, ResourceLocation gunId) {
        List<Integer> out = new ArrayList<>();
        if (gunId == null) return out;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack card = inv.getItem(i);
            if (!(card.getItem() instanceof SkinCardItem)) continue;
            if (!gunId.equals(SkinCardItem.gunId(card))) continue;
            if (SkinCardItem.skinId(card).isEmpty()) continue;
            out.add(i);
        }
        return out;
    }

    private static void install(GunRefitScreen screen, Inventory inv, int gunSlot, int cardSlot, String nbtKey) {
        ItemStack card = inv.getItem(cardSlot);
        if (!(card.getItem() instanceof SkinCardItem)) return;
        // 服务端才写 NBT 并消耗卡；客户端只发消息（与 TaCZ 安装配件同一条路线）
        GunSkinNetwork.CHANNEL.sendToServer(
                new SetGunSkinMessage(gunSlot, nbtKey, SkinCardItem.skinId(card), cardSlot));
        screen.resize(Minecraft.getInstance(), screen.width, screen.height);   // init() 是 protected
        refresh(screen);
    }

    private static void uninstall(GunRefitScreen screen, int gunSlot, GunSkinCatalog.Info info) {
        // cardSlot = -1：只改皮肤、不消耗任何卡
        GunSkinNetwork.CHANNEL.sendToServer(new SetGunSkinMessage(gunSlot, info.nbtKey, info.ids.get(0)));
        screen.resize(Minecraft.getInstance(), screen.width, screen.height);
        refresh(screen);
    }

    /** TaCZ 类型行里占位的类型个数（NONE 不占位，与 GunRefitScreen#addAttachmentTypeButtons 一致）。 */
    private static int typeSlotCount() {
        int n = 0;
        for (AttachmentType type : AttachmentType.values()) {
            if (type != AttachmentType.NONE) n++;
        }
        return n;
    }
}
