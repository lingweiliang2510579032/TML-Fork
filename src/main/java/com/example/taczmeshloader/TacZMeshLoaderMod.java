package com.example.taczmeshloader;

import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.render.ShaderStateTracker;
import com.example.taczmeshloader.tacz.TaczMeshyIntegration;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(TacZMeshLoaderMod.MOD_ID)
public class TacZMeshLoaderMod {

    public static final String MOD_ID = "taczmeshloader";
    private static final Logger LOGGER = LogUtils.getLogger();
    /** 本分支的构建基准：mixins 直接打在 TaCZ 的内部类上，版本不同可能出现"静默异常"。 */
    public static final String TACZ_BUILD_BASELINE = "1.1.8";

    public TacZMeshLoaderMod() {
        LOGGER.info("[TacZMeshLoader] Initialized.");

        // TaCZ 版本自检：玩家反馈过"某整合包里枪不播动画"，而本分支只按 1.1.8 构建/验证过。
        // 版本不一致时**启动就喊一声**（并把版本号打进日志），省得来回问。
        try {
            net.minecraftforge.fml.ModContainer tc =
                    net.minecraftforge.fml.ModList.get().getModContainerById("tacz").orElse(null);
            String tv = (tc == null) ? "<未安装>" : tc.getModInfo().getVersion().toString();
            LOGGER.info("[TacZMeshLoader] TaCZ 版本 = {}（本分支构建基准 {}）", tv, TACZ_BUILD_BASELINE);
            if (tc == null || !tv.startsWith(TACZ_BUILD_BASELINE)) {
                LOGGER.warn("[TacZMeshLoader] ⚠ TaCZ 版本 {} 与本分支构建基准 {} 不一致："
                        + "可能出现枪械动画/特效异常；请换回 {} 或把这条日志反馈过来",
                        tv, TACZ_BUILD_BASELINE, TACZ_BUILD_BASELINE);
            }
        } catch (Throwable th) {
            LOGGER.warn("[TacZMeshLoader] TaCZ 版本自检失败: {}", th.toString());
        }

        // 网络通道（皮肤选择 C2S；两端都要注册）
        com.example.taczmeshloader.network.GunSkinNetwork.register();

        // 自带物品与创造分类（皮肤卡；内容从资源配置数据现算，零品牌硬编码）
        // 客户端配置（生成 config/taczmeshloader-client.toml）：目前用于流动纹理叠加层开关
        net.minecraftforge.fml.ModLoadingContext.get().registerConfig(
                net.minecraftforge.fml.config.ModConfig.Type.CLIENT,
                com.example.taczmeshloader.config.TmlClientConfig.SPEC);

        com.example.taczmeshloader.item.ModItems.ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        com.example.taczmeshloader.item.ModItems.TABS.register(FMLJavaModLoadingContext.get().getModEventBus());

        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus()
                    .addListener(TaczMeshyIntegration::onClientSetup);
            LOGGER.info("[TacZMeshLoader] Registered TacZ client setup listener.");

            // フレームレベルの translucent バッチフラッシュハンドラを登録
            // これにより地面に複数の銃を落としても translucent は 1 フレームに 1 回だけフラッシュされる
            MinecraftForge.EVENT_BUS.register(MeshyBatchFlushHandler.class);
            LOGGER.info("[TacZMeshLoader] Registered batch flush handler.");

            // Oculus シェーダー切り替え時に VBO キャッシュを無効化するトラッカーを登録
            // これにより、シェーダーを切り替えた際にメッシュモデルの影が反転する問題を修正する
            MinecraftForge.EVENT_BUS.register(ShaderStateTracker.class);
            LOGGER.info("[TacZMeshLoader] Registered shader state tracker.");
        }
    }
}
