package com.example.taczmeshloader.render;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.tacz.guns.compat.oculus.OculusCompat;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Oculus のシェーダーパック有効時の描画安定化トラッカー。
 *
 * <h3>背景</h3>
 * {@link com.example.taczmeshloader.core.PolyMesh} はパフォーマンスのためライトレベルごとに
 * VBO を焼き込みキャッシュする。しかしシェーダーパック下では、ライト値が変わるたび
 * （起動直後・シーン移動・昼夜・パック切替）に作られる焼き込みバッファが表示されない
 * ことがあり、特定フレームの問題ではなく「光が変わるたび再発」する。
 *
 * <h3>方針</h3>
 * シェーダーパック有効中は全モデルの {@link PolyMeshModel#forceImmediate} を常に true にし、
 * 即時描画（MC 標準バッチ）で描く。毎フレーム正しいライトを与えるため、どのシーンでも
 * 必ず描画される。パック無効の間は false（従来どおり VBO 高速パス）。切替時は古い VBO を
 * 破棄してから移行する。
 *
 * <h3>登録方法</h3>
 * {@code TacZMeshLoaderMod} のコンストラクタで
 * {@code MinecraftForge.EVENT_BUS.register(ShaderStateTracker.class);} を呼ぶこと。
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ShaderStateTracker {

    /** Oculus がロードされていない場合はトラッキング不要 */
    private static final boolean OCULUS_LOADED =
            ModList.get().isLoaded("oculus");

    /**
     * 前フレームのシェーダーパック使用状態。
     * 初回は "未初期化" を表すため null を使う。
     */
    private static Boolean lastShaderState = null;

    /**
     * 登録済みの全 PolyMeshModel を WeakReference で保持する。
     * モデルが GC されても自動的にセットから消えるため、手動登録解除は不要。
     */
    private static final Set<PolyMeshModel> registeredModels =
            Collections.newSetFromMap(new WeakHashMap<>());

    private ShaderStateTracker() {}

    /**
     * PolyMeshModel をシェーダー状態監視対象として登録する。
     * {@link com.example.taczmeshloader.tacz.TaczPolyMeshGunModel#loadPolyMesh} および
     * {@link com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel#loadPolyMesh}
     * の末尾から呼ばれる。
     *
     * @param model 監視対象のモデル（null は無視）
     */
    public static void register(PolyMeshModel model) {
        if (model != null) {
            registeredModels.add(model);
        }
    }

    /**
     * 登録を解除する。{@link PolyMeshModel#close()} と連動して呼ぶ。
     *
     * @param model 解除するモデル
     */
    public static void unregister(PolyMeshModel model) {
        registeredModels.remove(model);
    }

    private static void invalidateAll() {
        for (PolyMeshModel model : registeredModels) {
            model.invalidateVboCache();
        }
    }

    /**
     * レンダーティック開始時にシェーダー状態を確認し、各モデルの描画パスを切り替える。
     *
     * <p>シェーダーパック有効中は {@link PolyMeshModel#forceImmediate} を常に true にし、
     * 全モデルを即時描画（MC 標準バッチ）で描く。VBO はライトレベルごとに焼き込むため、
     * シェーダーパック下で「起動直後・シーン/光照変化・パック切替」のたびに作られる
     * バッファが表示されないことがある（これは特定フレームの問題ではなくライト値が変わる
     * たびに再発する）。即時描画は毎フレーム正しいライトを与えるため、どのシーンでも
     * 必ず描画される。パック無効時は従来どおり VBO 高速パスを使う。</p>
     */
    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!OCULUS_LOADED) return;

        boolean currentState = OculusCompat.isUsingRenderPack();

        if (lastShaderState != null && lastShaderState != currentState) {
            // パック切替時: 双方の状態で古い VBO が残らないよう破棄する
            invalidateAll();
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoaderPerf")
                    .info("[MeshyPerf] ShaderStateTracker: state -> {} invalidated {} model(s).",
                            currentState, registeredModels.size());
        }
        lastShaderState = currentState;

        // 【实验A】不再强制即时：光影下也走 VBO（探针版，验证隐形是否复现/帧率是否回升）
        for (PolyMeshModel model : registeredModels) {
            model.forceImmediate = false;
        }
    }
}
