package com.example.taczmeshloader.core;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.example.taczmeshloader.render.SmoothMeshRenderTypes;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraft.Util;
import org.joml.Matrix4f;
import javax.annotation.Nullable;

import java.util.*;
import java.util.function.Function;

@OnlyIn(Dist.CLIENT)
public class PolyMeshModel {
    private final IPolyMeshBone root;
    private final Map<String, List<PolyMesh>> meshMap = new HashMap<>();
    private boolean hasSmoothMeshes;
    private final Set<String> translucentBones = new HashSet<>();
    private final boolean hasTranslucent;
    private final Set<String> meshAncestorBones = new HashSet<>();

    /** geo bone に書かれた静的 scale（例: "scale":[0,0,0] でデフォルト非表示）。 */
    private final Map<String, float[]> staticScales = new HashMap<>();

    /**
     * 「このライト値については既に meshMap の全ボーンをアップロード済み」を
     * 記録する集合。allVboReady() をボーンツリーの走査無しで O(1) 判定できる
     * ようにするためのもの（詳細は allVboReady/ensureAllUploaded のコメント参照）。
     */
    private final Set<Integer> fullyUploadedLights = new HashSet<>();
    /** poly_mesh を持ち illuminated 扱いになるボーン名セット（祖先伝播考慮済み） */
    private final Set<String> illuminatedBones = new HashSet<>();
    /** 描画から除外するサブツリーのルートボーン名（additional_magazine 対応用） */
    private String excludeSubtreeRoot = null;
    /** excludeSubtreeRoot 配下のボーン名セット（毎回計算しないようキャッシュ） */
    private final Set<String> excludedBones = new HashSet<>();

    /** geo bone に "inspect_hide": true と書かれた骨名集合（検視アニメ中のみメッシュ非表示） */
    private final Set<String> inspectHideBones = new HashSet<>();
    /**
     * geo bone に "starflow": true と書かれた骨名集合。
     * これらの骨のメッシュは通常パスに加えて「流动纹理オーバーレイパス」
     * （additive + UV 滚动 + 無照明）でも描かれる（皮肤の星空流动表現）。
     */
    private final Set<String> starflowBones = new HashSet<>();
    /** 按皮肤临时隐藏的骨（换皮替换件；与 excludedBones 独立，互不覆盖） */
    private final Set<String> skinHiddenBones = new HashSet<>();
    /** 每骨贴图覆盖（骨名 → 贴图 id 字符串）：该骨的网格改用这张贴图渲染（如发光件用发光贴图）。 */
    private final java.util.Map<String, String> boneTextures = new java.util.LinkedHashMap<>();
    /** 每骨是否走"发光"渲染（additive + 全亮；配合 boneTextures 使用）。 */
    private final Set<String> glowBones = new HashSet<>();
    /** 发光骨的叠加层滚动速度（UV/秒，配合 "glow": {"speed_u":..,"speed_v":..} 使用）。
     *  例：能量条从枪托流向枪口 = 沿该条 UV 的 u 反向推进，靠这里的速度由渲染侧按时间驱动。 */
    private final java.util.Map<String, float[]> glowScroll = new java.util.LinkedHashMap<>();
    /** 发光骨叠加层的强度（geo 里 "glow": {"alpha": 0.55}）；缺省 1.0。
     *  遮罩类贴图（M 图）均值本身就有 0.6 亮度，给 1.0 会过曝，所以强度按件配置。 */
    private final java.util.Map<String, Float> glowAlpha = new java.util.LinkedHashMap<>();
    /** 发光骨叠加层的颜色（geo "glow".tint：数组=所有皮肤共用 / 对象={"皮肤id":[...]}=按皮肤）；缺省白。
     *  例：原皮的发光来自 UE `Emissivemap{1,1,3}`（蓝），星空皮肤则不该再压蓝。 */
    private final java.util.Map<String, java.util.Map<String, float[]>> glowTint = new java.util.LinkedHashMap<>();
    /** 替换件骨 → 允许出现的皮肤 id 集合（geo 里 "skins": [...]；缺省=所有皮肤）。 */
    private final java.util.Map<String, java.util.Set<String>> boneSkins = new java.util.LinkedHashMap<>();
    /** 逐骨贴图"按皮肤"覆盖：骨 → {皮肤 id → 贴图 id}（geo 里 "texture": {"base": "...", "xinghe": "..."}）。 */
    private final java.util.Map<String, java.util.Map<String, String>> boneTextureSkins = new java.util.LinkedHashMap<>();
    /**
     * 検視隐藏ウィンドウ中かどうか。TaczPolyMeshGunModel.render() が毎フレーム
     * 設定する（検視進行度が [INSPECT_HIDE_T0, INSPECT_HIDE_T1] 内のとき true）。
     * true の間、inspectHideBones の骨「自身のメッシュ描画」だけをスキップする
     * （子骨・骨のトランスフォームは通常通り → 子骨は一切連坐しない）。
     */
    private volatile boolean inspectHideActive = false;

    /**
     * FX 骨锚捕捉：渲染该骨自身的 mesh 前，复制其累计矩阵供粒子定位。
     * 捕获发生在网格真正绘制的一瞬，因此粒子位置与可见模型严格同点
     * （不受渲染基线/动画链二次计算差异影响）。
     */
    private final Set<String> fxCaptureBones = new HashSet<>();
    private final Map<String, Matrix4f> fxCapturedPose = new java.util.concurrent.ConcurrentHashMap<>();

    public void setFxCaptureBones(java.util.Collection<String> names) {
        fxCapturePath.clear();
        fxCaptureBones.clear();
        if (names == null) return;
        fxCaptureBones.addAll(names);
        if (!fxCaptureBones.isEmpty()) {
            // 锚点链（锚点 + 祖先）预先算好，遍历时对这条链不剪枝
            rebuildFxCapturePath(root, new ArrayDeque<>());
            dbgCapturePath(names);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger FX_DBG = new java.util.concurrent.atomic.AtomicInteger(6);
    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");
    private static final java.util.concurrent.atomic.AtomicInteger CAP_DBG = new java.util.concurrent.atomic.AtomicInteger(6);
    private static final java.util.concurrent.atomic.AtomicInteger VISIT_DBG = new java.util.concurrent.atomic.AtomicInteger(6);

    /** 诊断：锚点骨是否真在 TaCZ 模型树里、锚点链算了几个骨。 */
    private void dbgCapturePath(java.util.Collection<String> names) {
        if (FX_DBG.getAndDecrement() <= 0) return;
        java.util.Set<String> tree = new java.util.HashSet<>();
        collectBoneNames(root, tree);
        String target = names.iterator().next();
        MESH_LOG.info("[FxDbg] 锚点={} 在模型树里={} 树骨数={} 锚点链骨数={} 链={}",
                target, tree.contains(target), tree.size(), fxCapturePath.size(), fxCapturePath);
    }

    private void collectBoneNames(IPolyMeshBone bone, java.util.Set<String> out) {
        out.add(bone.getName());
        for (IPolyMeshBone child : bone.getChildren()) collectBoneNames(child, out);
    }

    @Nullable
    public Matrix4f getFxCapturedPose(String bone) {
        Matrix4f m = fxCapturedPose.get(bone);
        return m == null ? null : new Matrix4f(m);
    }

    /** Copies into caller-owned scratch space for the per-frame particle anchor path. */
    public boolean copyFxCapturedPose(String bone, Matrix4f destination) {
        Matrix4f m = fxCapturedPose.get(bone);
        if (m == null) return false;
        destination.set(m);
        return true;
    }

    private void captureBonePose(IPolyMeshBone bone, PoseStack ps) {
        if (!fxCaptureBones.isEmpty() && fxCaptureBones.contains(bone.getName())) {
            fxCapturedPose.put(bone.getName(), new Matrix4f(ps.last().pose()));
            if (CAP_DBG.getAndDecrement() > 0) {
                MESH_LOG.info("[FxDbg] 已捕获锚点骨矩阵: {}", bone.getName());
            }
        }
    }

    private static final Function<ResourceLocation, RenderType> TRANSLUCENT_CULL =
            Util.memoize(RenderType::entityTranslucentCull);

    public PolyMeshModel(IPolyMeshBone root, JsonObject rawJson) {
        this.root = root;
        parsePolyMeshes(rawJson);
        for (String name : meshMap.keySet()) {
            if (name.toLowerCase().contains("translucent")) translucentBones.add(name);
        }
        this.hasTranslucent = !translucentBones.isEmpty();
        buildMeshAncestors(this.root, new ArrayDeque<>());
        buildIlluminatedBones(this.root, false);
    }

    public boolean hasTranslucentMeshes() { return hasTranslucent; }

    private boolean buildMeshAncestors(IPolyMeshBone bone, Deque<String> path) {
        String name = bone.getName();
        path.addLast(name);
        boolean has = meshMap.containsKey(name);
        for (IPolyMeshBone child : bone.getChildren()) if (buildMeshAncestors(child, path)) has = true;
        if (has) meshAncestorBones.addAll(path);
        path.removeLast();
        return has;
    }

    /** illuminated フラグを祖先から子へ伝播させ、poly_mesh を持つボーンを illuminatedBones に登録する */
    private void buildIlluminatedBones(IPolyMeshBone bone, boolean parentIlluminated) {
        boolean illuminated = parentIlluminated || bone.isIlluminated();
        if (illuminated && meshMap.containsKey(bone.getName())) {
            illuminatedBones.add(bone.getName());
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            buildIlluminatedBones(child, illuminated);
        }
    }

    private void parsePolyMeshes(JsonObject rawJson) {
        JsonArray geometries = rawJson.has("minecraft:geometry") ? rawJson.getAsJsonArray("minecraft:geometry") : null;
        if (geometries == null || geometries.isEmpty()) return;
        JsonObject geo = geometries.get(0).getAsJsonObject();
        float texW = geo.getAsJsonObject("description").get("texture_width").getAsFloat();
        float texH = geo.getAsJsonObject("description").get("texture_height").getAsFloat();
        JsonArray bones = geo.getAsJsonArray("bones");
        if (bones == null) return;
        for (JsonElement boneElem : bones) {
            JsonObject boneObj = boneElem.getAsJsonObject();
            if (!boneObj.has("name")) continue;
            String name = boneObj.get("name").getAsString();
            // "inspect_hide": true（检视动画中隐藏该骨自身的 mesh，子骨不受影响）
            if (boneObj.has("inspect_hide") && boneObj.get("inspect_hide").isJsonPrimitive()
                    && boneObj.get("inspect_hide").getAsBoolean()) {
                inspectHideBones.add(name);
            }
            // "texture": "ns:path"（该骨网格改用该贴图）；
            //            也可以是 {"皮肤id": "ns:path", "default": "ns:path"}（逐皮肤换贴图，如发光件两皮不同）
            // "glow": true（该骨走 additive+全亮）
            if (boneObj.has("texture")) {
                JsonElement te = boneObj.get("texture");
                if (te.isJsonPrimitive() && te.getAsJsonPrimitive().isString()) {
                    boneTextures.put(name, te.getAsString());
                } else if (te.isJsonObject()) {
                    java.util.Map<String, String> per = new java.util.LinkedHashMap<>();
                    for (java.util.Map.Entry<String, JsonElement> en : te.getAsJsonObject().entrySet()) {
                        if (en.getValue().isJsonPrimitive()) per.put(en.getKey(), en.getValue().getAsString());
                    }
                    if (!per.isEmpty()) boneTextureSkins.put(name, per);
                }
            }
            if (boneObj.has("glow")) {
                JsonElement gl = boneObj.get("glow");
                if (gl.isJsonPrimitive() && gl.getAsBoolean()) {
                    glowBones.add(name);
                } else if (gl.isJsonObject()) {
                    glowBones.add(name);
                    JsonObject go = gl.getAsJsonObject();
                    float su = go.has("speed_u") ? go.get("speed_u").getAsFloat() : 0f;
                    float sv = go.has("speed_v") ? go.get("speed_v").getAsFloat() : 0f;
                    if (su != 0f || sv != 0f) glowScroll.put(name, new float[]{su, sv});
                    if (go.has("alpha")) glowAlpha.put(name, go.get("alpha").getAsFloat());
                    JsonElement ti = go.get("tint");
                    if (ti != null && !ti.isJsonNull()) {
                        java.util.Map<String, float[]> per = new java.util.LinkedHashMap<>();
                        if (ti.isJsonArray()) {
                            per.put("", readRgb(ti.getAsJsonArray()));
                        } else if (ti.isJsonObject()) {
                            for (Map.Entry<String, JsonElement> en : ti.getAsJsonObject().entrySet()) {
                                if (en.getValue().isJsonArray()) per.put(en.getKey(), readRgb(en.getValue().getAsJsonArray()));
                            }
                        }
                        if (!per.isEmpty()) glowTint.put(name, per);
                    }
                }
            }
            // "starflow": true（该骨的网格额外参与流动纹理叠加绘制；不影响正常绘制）
            if (boneObj.has("starflow") && boneObj.get("starflow").isJsonPrimitive()
                    && boneObj.get("starflow").getAsBoolean()) {
                starflowBones.add(name);
            }
            // "skins": ["a","b"]（替换件：只在这些皮肤下出现；缺省=所有皮肤都出现）
            if (boneObj.has("skins")) {
                java.util.Set<String> allow = new java.util.LinkedHashSet<>();
                JsonElement sk = boneObj.get("skins");
                if (sk.isJsonArray()) {
                    for (JsonElement e : sk.getAsJsonArray()) if (e.isJsonPrimitive()) allow.add(e.getAsString());
                } else if (sk.isJsonPrimitive() && sk.getAsJsonPrimitive().isString()) {
                    allow.add(sk.getAsString());
                }
                if (!allow.isEmpty()) boneSkins.put(name, allow);
            }
            if (!boneObj.has("poly_mesh")) continue;
            float pX=0, pY=0, pZ=0;
            if (boneObj.has("pivot")) {
                JsonArray p = boneObj.getAsJsonArray("pivot");
                pX=p.get(0).getAsFloat(); pY=p.get(1).getAsFloat(); pZ=p.get(2).getAsFloat();
            }
            if (boneObj.has("scale")) {
                JsonArray sc = boneObj.getAsJsonArray("scale");
                if (sc.size() >= 3) {
                    staticScales.put(name, new float[]{
                            sc.get(0).getAsFloat(), sc.get(1).getAsFloat(), sc.get(2).getAsFloat()});
                }
            }
            PolyMesh mesh = new PolyMesh(boneObj.getAsJsonObject("poly_mesh"), texW, texH, new float[]{pX,pY,pZ});
            if (mesh.getVertexCount() > 0) {
                meshMap.computeIfAbsent(name, k -> new ArrayList<>()).add(mesh);
                hasSmoothMeshes |= mesh.hasSmoothNormals();
            }
        }
    }

    public Map<String, float[]> getStaticScales() {
        return staticScales;
    }

    public boolean hasSmoothMeshes() { return hasSmoothMeshes; }

    /**
     * 検視隐藏ウィンドウの ON/OFF を設定する（TaczPolyMeshGunModel.render() から毎フレーム呼ぶ）。
     * ON の間、geo で "inspect_hide": true の骨の「自身のメッシュ描画」だけをスキップする。
     * 骨のトランスフォーム適用・子骨の走査は通常通りため、子骨は一切影響を受けない。
     */
    public void setInspectHideActive(boolean active) { this.inspectHideActive = active; }

    public boolean isInspectHideActive() { return inspectHideActive; }

    /** この骨のメッシュを今フレーム隠すべきか（検視隐藏ウィンドウ中かつ inspect_hide 骨） */
    private boolean isInspectHidden(String boneName) {
        return inspectHideActive && inspectHideBones.contains(boneName);
    }

    // =========================================================================
    // 描画エントリポイント (overlay を受け取るよう修正)
    // =========================================================================

    /**
     * per-model の即時描画フラグ。Oculus のシェーダーパックが有効になった直後や
     * モデル生成直後の暖機期間に {@link com.example.taczmeshloader.render.ShaderStateTracker}
     * が true にする。true の間は VBO 高速パスを避けて VertexConsumer（標準バッチ）
     * で描画する（パス・パック状態に依存せず常に描画される）。暖機が終わると false に
     * 戻り、次フレームから再生成された VBO で高速描画する。
     */
    public volatile boolean forceImmediate = false;

    /**
     * ライト値を量子化して VBO のバケット数を減らす。
     * 天空/ブロック光とも下位2bitを落とす（誤差≦3 段、視覚的にほぼ無影響）。
     * これにより移動時の細かい光変化で毎回フル再アップロードが走るのを防ぐ。
     */
    private static int quantizeLight(int packed) {
        int sky = (packed >> 20) & 0xFF;
        int block = (packed >> 4) & 0xFF;
        return ((sky & 0xFC) << 20) | ((block & 0xFC) << 4);
    }

    // ---- 光照上限 / 软压缩（-1 = 不限制）----
    // 由 geo extras 的 "light_limit" 注入。压住天空光上限可避免枪在阳光直射下过曝
    // （枪用独立纹理渲染，光影包的 PBR 系统只认图集 sprite ⇒ 拿不到我们的 _s/_n）。
    // keep = 超过上限那一截的**保留比例**：0 = 硬截断（默认，原行为）；0.4 = 只保留 40%
    // ⇒ 直射太阳（15）被压到约 12，而上限以下的环境光完全不动（背光不会变黑）。
    private int lightSkyCap = -1;
    private int lightBlockCap = -1;
    private float lightSkyKeep = 0f;
    private float lightBlockKeep = 0f;

    public void setLightLimits(int skyCap, int blockCap) {
        setLightLimits(skyCap, blockCap, 0f, 0f);
    }

    public void setLightLimits(int skyCap, int blockCap, float skyKeep, float blockKeep) {
        this.lightSkyCap = skyCap;
        this.lightBlockCap = blockCap;
        this.lightSkyKeep = skyKeep;
        this.lightBlockKeep = blockKeep;
    }

    /** 只压"超过上限的那一截"：v <= cap 原样返回；否则 cap + (v-cap)×keep */
    private static int softenLight(int v, int cap, float keep) {
        if (cap < 0 || v <= cap) return v;
        int out = Math.round(cap + (v - cap) * keep);
        return Math.max(0, Math.min(15, out));
    }

    /** 应用光照上限/软压缩（打包的 lightmap：block 在 bit4-7，sky 在 bit20-23） */
    private int applyLightLimit(int packed) {
        if (lightSkyCap < 0 && lightBlockCap < 0) return packed;
        int block = (packed >> 4) & 0xF;
        int sky = (packed >> 20) & 0xF;
        sky = softenLight(sky, lightSkyCap, lightSkyKeep);
        block = softenLight(block, lightBlockCap, lightBlockKeep);
        return (packed & ~0x00F000F0) | (block << 4) | (sky << 20);
    }

    public void renderWithTranslucentSplit(PoseStack ps, MultiBufferSource buf,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO) {
        renderCutoutOnly(ps, buf, tex, light, overlay, useVBO);
        if (hasTranslucent) renderTranslucentOnly(ps, buf, tex, light, overlay, useVBO);
    }

    private boolean lastCutoutDirectVbo;
    /** Actual last opaque route, including initial-upload Consumer fallback. */
    public boolean wasCutoutDirectVbo() { return lastCutoutDirectVbo; }

    public void renderCutoutOnly(PoseStack ps, MultiBufferSource buf,
                                 ResourceLocation tex, int light, int overlay, boolean useVBO) {
        lastCutoutDirectVbo = false;
        light = applyLightLimit(light);
        // Vanilla entity lighting consumes Normal directly; only the shader-pack path
        // supplies the inverse-transpose normal matrix for a bone-local VBO.
        boolean useVboPath = useVBO && !forceImmediate
                && (!hasSmoothMeshes || com.tacz.guns.compat.oculus.OculusCompat.isUsingRenderPack());
        if (useVboPath) {
            int lightKey = quantizeLight(light);
            if (allVboReady(lightKey)) {
                lastCutoutDirectVbo = true;
                renderVBO(ps, tex, lightKey, false);
            } else {
                ensureAllUploaded(lightKey);
                renderBaseConsumer(ps, buf, tex, light, overlay, false);
            }
        } else {
            renderBaseConsumer(ps, buf, tex, light, overlay, false);
        }
    }

    public void renderTranslucentOnly(PoseStack ps, MultiBufferSource buf,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO) {
        if (!hasTranslucent) return;
        light = applyLightLimit(light);
        boolean useVboPath = useVBO && !forceImmediate
                && (!hasSmoothMeshes || com.tacz.guns.compat.oculus.OculusCompat.isUsingRenderPack());
        if (useVboPath) {
            int lightKey = quantizeLight(light);
            if (allVboReady(lightKey)) {
                renderVBO(ps, tex, lightKey, true);
            } else {
                ensureAllUploaded(lightKey);
                renderBaseConsumer(ps, buf, tex, light, overlay, true);
            }
        } else {
            renderBaseConsumer(ps, buf, tex, light, overlay, true);
        }
    }

    // =========================================================================
    // VBO 管理
    // =========================================================================

    /**
     * 現在のボーンツリーを辿り、実際に描画され得る（isVisible()==true かつ
     * meshAncestorBones/excludedBones の条件を満たす）poly_mesh ボーン名を
     * 収集する。renderBonesVBO() の枝刈り条件と完全に一致させること。
     *
     * 【最適化の狙い】装着していない弾倉バリエーションなど、モデルによっては
     * 「同時に描画されることのない」代替パーツが多数のボーンに分割されている
     * ことがある。これらは isVisible()==false のため実際には描画されないが、
     * 元のコードは allVboReady() / ensureAllUploaded() で meshMap の
     * 全ボーンを無条件にチェック・アップロードしていたため、ライトレベルが
     * 変わるたびに「現在表示されていないボーン」まで含めて毎回フルアップロード
     * が走っていた。ボーン数が多い（＝装着バリエーションが豊富な）モデルほど
     * この無駄なコストが大きくなる。本来描画されるボーンだけに限定することで、
     * 見た目やボーンの独立した表示切り替えには一切影響を与えずに、この無駄を
     * 削減する。
     */
    /**
     * 現在のライト値について、meshMap の全ボーンが VBO アップロード済みかどうか。
     *
     * 【設計変更の経緯】以前はボーンツリーを毎フレーム走査して「今表示中の
     * ボーンだけ」を対象にチェックしていたが、これには2つの問題があった:
     *   1. ツリー走査自体のコスト（HashSet 割り当てを含む）が毎フレーム発生する
     *   2. リロード等でボーンの表示/非表示が頻繁に切り替わるアニメーション中や、
     *      移動によってライト値が細かく変動する状況で、"表示中ボーンの一部が
     *      未アップロード" と判定される頻度が上がり、その都度アップロード
     *      処理（ensureAllUploaded）が走ってしまう
     *
     * 今回は「あるライト値について、一度でも ensureAllUploaded() が完走した
     * ことがあるか」を {@link #fullyUploadedLights} で記録するだけにした。
     * ensureAllUploaded() は常に meshMap の全ボーン（表示/非表示問わず）を
     * 対象にするため、一度完走すれば、以後そのライト値については
     * どのボーンが表示されようと（表示が切り替わろうと）再チェックが不要に
     * なる。これにより allVboReady() はボーンツリーを一切走査しない、
     * 単純な O(1) の集合参照だけで済むようになった。
     */
    private boolean allVboReady(int light) {
        if (meshMap.isEmpty()) return false;
        return fullyUploadedLights.contains(light);
    }

    private void ensureAllUploaded(int light) {
        // 描画され得る骨骼（meshAncestorBones）だけをアップロードする。
        // 隠しっぱなしの代替パーツ等は draw 側でも描かないため、ここでスキップして
        // メモリ/アップロードを削る（描画判定と条件を一致させること）。
        for (Map.Entry<String, List<PolyMesh>> entry : meshMap.entrySet()) {
            if (!meshAncestorBones.contains(entry.getKey())) continue;
            int bakeLight = illuminatedBones.contains(entry.getKey()) ? 15728880 : light;
            for (PolyMesh m : entry.getValue()) {
                // 淘汰通知：某光照档的缓冲被 LRU 踢出时，把模型级"已就绪"标记同步移除，
                // 否则 allVboReady() 误报真、drawVBO() 画空 → 该光照下整枪隐身。
                m.setEvictCallback(l -> fullyUploadedLights.remove(l));
                m.ensureUploaded(bakeLight);
            }
        }
        fullyUploadedLights.add(light);
    }


    /**
     * 全 PolyMesh の VBO キャッシュを破棄する。
     *
     * Oculus シェーダー切り替え時など、レンダリング状態が大きく変わった際に
     * {@link com.example.taczmeshloader.render.ShaderStateTracker} から呼ばれる。
     * 次フレームで VBO が再生成されるため、影の反転が解消される。
     */
    public void invalidateVboCache() {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) m.invalidateVboCache();
        }
        fullyUploadedLights.clear();
    }

    // =========================================================================
    // VBO 描画パス
    // =========================================================================

    /** Shader/state owned by the caller; only the currently visible opaque mesh, no player arms. */
    public void renderSurfaceVbo(PoseStack ps) {
        renderSurfaceRec(root, ps);
    }

    private void renderSurfaceRec(IPolyMeshBone bone, PoseStack ps) {
        if (!bone.isVisible() || !meshAncestorBones.contains(bone.getName()) || isRigExcluded(bone.getName())) return;
        ps.pushPose();
        bone.applyTransform(ps);
        if (!isSkinHidden(bone.getName()) && !isInspectHidden(bone.getName())
                && !translucentBones.contains(bone.getName()) && !hasTextureOverride(bone.getName())) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) for (PolyMesh mesh : meshes) {
                // Shader receives lighting separately; fixed key avoids per-light VBO churn.
                mesh.ensureUploaded(0);
                mesh.drawVBO(ps.last().pose(), 0);
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) renderSurfaceRec(child, ps);
        ps.popPose();
    }

    private void renderVBO(PoseStack ps, ResourceLocation tex, int light, boolean translucentPass) {
        RenderType renderType = translucentPass
                ? TRANSLUCENT_CULL.apply(tex)
                : RenderType.entityCutoutNoCull(tex);

        renderType.setupRenderState();
        renderBonesVBO(root, ps, light, translucentPass);
        renderType.clearRenderState();
    }

    /**
     * "FX 锚点 + 它的所有祖先"这条路径上的骨名。
     *
     * <p>骨遍历遇到"整棵子树都没有网格"的骨会整枝剪掉；而枪口这种锚点骨（如 muzzle_pos）
     * 自己没网格、祖先（positioning3 / gun_barrel）也没网格 ⇒ 遍历根本走不到它，
     * 骨矩阵永远捕获不到 ⇒ 特效一个粒子都不撒。所以这里预先把锚点链上的骨都记下来，
     * 遍历时对这条链网开一面（只在锚点骨本身存矩阵，链上其它骨照常不画）。</p>
     */
    private final Set<String> fxCapturePath = new HashSet<>();

    /** 该骨是否在"锚点链"上（本身是锚点，或是锚点的祖先）。 */
    private boolean isFxCaptureTarget(String bone) {
        return !fxCapturePath.isEmpty() && fxCapturePath.contains(bone);
    }

    /** 锚点集合变化时，重算一次"锚点 + 祖先"链（O(骨数)，每帧一次可忽略）。 */
    private boolean rebuildFxCapturePath(IPolyMeshBone bone, java.util.Deque<String> path) {
        path.addLast(bone.getName());
        boolean hit = fxCaptureBones.contains(bone.getName());
        for (IPolyMeshBone child : bone.getChildren()) {
            if (rebuildFxCapturePath(child, path)) hit = true;
        }
        if (hit) fxCapturePath.addAll(path);
        path.removeLast();
        return hit;
    }

    private static final java.util.Set<String> FX_TRACE = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** FX 锚点链上的骨：把"进没进、为什么被剪"各打一次日志（定位特效拿不到骨矩阵）。 */
    /**
     * "仅捕获"遍历：不画任何东西，只把锚点链上的骨矩阵捕捉下来。
     *
     * <p>枪口骨（muzzle_pos）这类锚点在 TaCZ 模型里被标成不可见（只为定位用），
     * 正常遍历会在"不可见"处整枝剪掉 ⇒ 骨矩阵永远拿不到 ⇒ 开火特效一个粒子都不撒。
     * 所以不可见但处在锚点链上时，走这条只捕获不绘制的分支。</p>
     */
    private void captureOnlyRec(IPolyMeshBone bone, PoseStack ps) {
        ps.pushPose();
        bone.applyTransform(ps);
        captureBonePose(bone, ps);
        for (IPolyMeshBone child : bone.getChildren()) {
            captureOnlyRec(child, ps);
        }
        ps.popPose();
    }

    private boolean handleInvisibleCaptureTarget(IPolyMeshBone bone, PoseStack ps) {
        if (fxCapturePath.isEmpty() || !fxCapturePath.contains(bone.getName())) return false;
        fxTrace(bone.getName(), "不可见但仅捕获");
        captureOnlyRec(bone, ps);
        return true;
    }

    private void fxTrace(String bone, String phase) {
        if (fxCapturePath.isEmpty() || !fxCapturePath.contains(bone)) return;
        if (FX_TRACE.add(bone + "@" + phase)) {
            MESH_LOG.info("[FxTrace] {} 骨={}{}", phase, bone,
                    "muzzle_pos".equals(bone) ? "  ← 锚点本身" : "");
        }
    }

    private void renderBonesVBO(IPolyMeshBone bone, PoseStack ps, int light, boolean translucentPass) {
        fxTrace(bone.getName(), bone.isVisible() ? "进入" : "不可见");
        if (!bone.isVisible()) {
            handleInvisibleCaptureTarget(bone, ps);   // 不可见但可能是 FX 锚点（枪口）：只捕获不绘制
            return;
        }
        // 无网格的骨一般整棵子树跳过；但 FX 锚点（如枪口 muzzle_pos，只有 pivot 没有网格）
        // 必须继续遍历到捕获那一步，否则开火特效拿不到枪口矩阵（表现为"特效完全不出现"）。
        if (!meshAncestorBones.contains(bone.getName()) && !isFxCaptureTarget(bone.getName())) {
            fxTrace(bone.getName(), "因不在网格/锚点路径被剪");
            return;
        }
        if (isRigExcluded(bone.getName())) {
            fxTrace(bone.getName(), "因 rig 排除被剪");
            return;
        }
        boolean skinHidden = isSkinHidden(bone.getName());     // 皮肤隐藏：只跳自己网格，子骨照画
        if (VISIT_DBG.getAndDecrement() > 0 && fxCaptureBones.contains(bone.getName())) {
            MESH_LOG.info("[FxDbg] 遍历(VBO)到达锚点骨: {}", bone.getName());
        }
        ps.pushPose();
        bone.applyTransform(ps);
        captureBonePose(bone, ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        // 検視/皮肤隐藏中の骨は「この骨自身のメッシュ」だけ描画スキップ（子骨は通常描画）
        if (!skinHidden && !isInspectHidden(bone.getName()) && isTranslucent == translucentPass
                && !hasTextureOverride(bone.getName())) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    mesh.drawVBO(ps.last().pose(), actualLight);
                }
            }
        }

        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesVBO(child, ps, light, translucentPass);
        }

        ps.popPose();
    }

    // =========================================================================
    // VertexConsumer フォールバックパス
    // =========================================================================

    private void renderBaseConsumer(PoseStack ps, MultiBufferSource buffers, ResourceLocation texture,
                                    int light, int overlay, boolean translucentPass) {
        if (!hasSmoothMeshes) {
            VertexConsumer consumer = buffers.getBuffer(translucentPass
                    ? TRANSLUCENT_CULL.apply(texture) : RenderType.entityCutoutNoCull(texture));
            renderBonesConsumer(root, ps, consumer, light, overlay, 1f, 1f, 1f, 1f, translucentPass);
            return;
        }
        renderBonesConsumer(root, ps, null, light, overlay, 1f, 1f, 1f, 1f,
                translucentPass, buffers, texture);
    }

    private void renderBonesConsumer(IPolyMeshBone bone, PoseStack ps, VertexConsumer buf,
                                     int light, int overlay, float r, float g, float b, float a,
                                     boolean translucentPass) {
        renderBonesConsumer(bone, ps, buf, light, overlay, r, g, b, a, translucentPass, null, null);
    }

    private void renderBonesConsumer(IPolyMeshBone bone, PoseStack ps, @Nullable VertexConsumer buf,
                                     int light, int overlay, float r, float g, float b, float a,
                                     boolean translucentPass, @Nullable MultiBufferSource routedBuffers,
                                     @Nullable ResourceLocation routedTexture) {
        if (!bone.isVisible()) {
            handleInvisibleCaptureTarget(bone, ps);
            return;
        }
        if (!meshAncestorBones.contains(bone.getName()) && !isFxCaptureTarget(bone.getName())) return;
        if (isRigExcluded(bone.getName())) return;
        boolean skinHidden = isSkinHidden(bone.getName());     // 皮肤隐藏：只跳自己网格，子骨照画

        ps.pushPose();
        bone.applyTransform(ps);
        captureBonePose(bone, ps);

        boolean isTranslucent = translucentBones.contains(bone.getName());
        // 検視/皮肤隐藏中の骨は「この骨自身のメッシュ」だけ描画スキップ（子骨は通常描画）
        if (!skinHidden && !isInspectHidden(bone.getName()) && isTranslucent == translucentPass
                && !hasTextureOverride(bone.getName())) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                int actualLight = (bone.isIlluminated() || illuminatedBones.contains(bone.getName())) ? 15728880 : light;
                for (PolyMesh mesh : meshes) {
                    if (routedBuffers != null) {
                        // Fetch at the point of use: switching a nonfixed batch invalidates its old consumer.
                        if (mesh.hasSmoothNormals()) {
                            VertexConsumer triangles = routedBuffers.getBuffer(translucentPass
                                    ? SmoothMeshRenderTypes.translucent(routedTexture)
                                    : SmoothMeshRenderTypes.cutout(routedTexture));
                            mesh.compileTrianglesConsumer(ps.last(), triangles, actualLight, overlay, r, g, b, a);
                        } else {
                            VertexConsumer quads = routedBuffers.getBuffer(translucentPass
                                    ? TRANSLUCENT_CULL.apply(routedTexture) : RenderType.entityCutoutNoCull(routedTexture));
                            mesh.compileConsumer(ps.last(), quads, actualLight, overlay, r, g, b, a);
                        }
                    } else {
                        mesh.compileConsumer(ps.last(), buf, actualLight, overlay, r, g, b, a);
                    }
                }
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderBonesConsumer(child, ps, buf, light, overlay, r, g, b, a, translucentPass,
                    routedBuffers, routedTexture);
        }

        ps.popPose();
    }

    /** 按皮肤临时排除若干骨（换皮时替换件用，如换消声器）。传 null 或空集合=不排除。 */
    public void setSkinHiddenBones(java.util.Collection<String> bones) {
        skinHiddenBones.clear();
        if (bones != null) skinHiddenBones.addAll(bones);
    }

    /** 指定ボーン配下を通常描画から除外する（additional_magazine アニメーション中に使用） */
    public void setExcludeSubtree(String rootBoneName) {
        if (rootBoneName.equals(excludeSubtreeRoot)) return;
        excludeSubtreeRoot = rootBoneName;
        excludedBones.clear();
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone != null) collectSubtreeBones(bone, excludedBones);
    }

    public void addExcludeSubtree(String rootBoneName) {
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone != null) {
            collectSubtreeBones(bone, excludedBones);
            excludeSubtreeRoot = null;
        }
    }

    public void clearExcludeSubtree() {
        excludeSubtreeRoot = null;
        excludedBones.clear();
    }

    private void collectSubtreeBones(IPolyMeshBone bone, Set<String> result) {
        result.add(bone.getName());
        for (IPolyMeshBone child : bone.getChildren()) collectSubtreeBones(child, result);
    }

    public void renderSubtree(String rootBoneName, PoseStack ps, MultiBufferSource buf,
                              ResourceLocation tex, int light, int overlay, boolean useVBO) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
        renderBonesConsumer(targetBone, ps, vc, light, overlay, 1f, 1f, 1f, 1f, false);
    }

    // =========================================================================
    // 流动纹理オーバーレイパス（皮肤の星空流动）
    // =========================================================================

    public java.util.Map<String, String> getBoneTextures() { return getBoneTextures(null); }

    /**
     * 当前皮肤下每骨的生效贴图（骨 → 贴图 id）。
     * 逐皮肤表里没有该皮肤时回落到 "default"，再回落到普通字符串写法。
     * 返回 null 值表示该骨在当前皮肤下没有生效贴图。
     */
    public java.util.Map<String, String> getBoneTextures(String skinId) {
        if (boneTextureSkins.isEmpty()) return boneTextures;
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>(boneTextures);
        for (java.util.Map.Entry<String, java.util.Map<String, String>> e : boneTextureSkins.entrySet()) {
            String t = skinId == null || skinId.isEmpty() ? null : e.getValue().get(skinId);
            if (t == null || t.isEmpty()) t = e.getValue().get("default");
            if (t == null || t.isEmpty()) {
                // 兜底：当前皮肤（或皮肤表读不到时）没有对应项 → 取表里第一个非空贴图。
                // 不能留空：留空会导致这个骨在基础遍被跳过、发光遍又没贴图 ⇒ 部件直接消失（"透明"）。
                for (String v : e.getValue().values()) {
                    if (v != null && !v.isEmpty()) { t = v; break; }
                }
            }
            if (t != null && !t.isEmpty()) out.put(e.getKey(), t);
        }
        return out;
    }

    public boolean isGlowBone(String bone) { return glowBones.contains(bone); }

    /** 该发光骨叠加层的强度（geo "glow".alpha）；未配置 = 1.0。 */
    public float getGlowAlpha(String bone) { return glowAlpha.getOrDefault(bone, 1f); }

    /** 该发光骨在给定皮肤下的叠加层颜色；未配置 = null（渲染侧按白处理）。 */
    public float[] getGlowTint(String bone, String skinId) {
        java.util.Map<String, float[]> per = glowTint.get(bone);
        if (per == null) return null;
        if (skinId != null && per.containsKey(skinId)) return per.get(skinId);
        return per.get("");
    }

    private static float[] readRgb(JsonArray a) {
        float r = a.size() > 0 ? a.get(0).getAsFloat() : 1f;
        float g = a.size() > 1 ? a.get(1).getAsFloat() : r;
        float b = a.size() > 2 ? a.get(2).getAsFloat() : r;
        return new float[]{r, g, b};
    }

    /** 该发光骨的叠加层滚动速度 [speed_u, speed_v]（UV/秒）；未配置=null（不滚）。 */
    public float[] getGlowScroll(String bone) { return glowScroll.get(bone); }

    /** 诊断用：网格骨数。 */
    public int getDebugBoneCount() { return meshMap.size(); }

    public boolean hasBoneOverrides() { return !boneTextures.isEmpty() || !boneTextureSkins.isEmpty(); }
    public boolean hasSkinFilteredBones() { return !boneSkins.isEmpty(); }

    /**
     * 当前皮肤下需要隐藏的骨（geo "skins" 白名单里不含该皮肤 id 的替换件）。
     * activeSkinId 为空 = 基础皮肤：只保留没写 "skins" 的骨 + 白名单里含 "" 的骨。
     */
    public java.util.Set<String> collectSkinHidden(String activeSkinId) {
        if (boneSkins.isEmpty()) return java.util.Collections.emptySet();
        String id = activeSkinId == null ? "" : activeSkinId;
        // 皮肤表没读到 / 皮肤 id 未知时不要按 skins 白名单隐藏任何东西（否则 base+xinghe 两套替换件会一起消失）
        if (id.isEmpty()) return java.util.Collections.emptySet();
        java.util.Set<String> hidden = new java.util.HashSet<>();
        for (java.util.Map.Entry<String, java.util.Set<String>> e : boneSkins.entrySet()) {
            if (!e.getValue().contains(id)) hidden.add(e.getKey());
        }
        return hidden;
    }

    /**
     * 只绘制"在给定集合内"的骨（其自身 mesh），用指定 RenderType + 顶点色；
     * 集合为 null 表示不限制。用于按贴图分组绘制（每骨不同贴图/发光件）。
     */
    public void renderBonesSubset(PoseStack ps, MultiBufferSource buf, RenderType renderType,
                                  java.util.Set<String> bones, int light, int overlay,
                                  float r, float g, float b, float a) {
        VertexConsumer vc = buf.getBuffer(renderType);
        renderSubsetRec(root, ps, vc, bones, light, overlay, r, g, b, a);
    }

    private void renderSubsetRec(IPolyMeshBone bone, PoseStack ps, VertexConsumer buf,
                                 java.util.Set<String> bones, int light, int overlay,
                                 float r, float g, float b, float a) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (isRigExcluded(bone.getName())) return;
        boolean skinHidden = isSkinHidden(bone.getName());     // 皮肤隐藏：只跳自己网格，子骨照画
        ps.pushPose();
        bone.applyTransform(ps);
        if (bones == null || bones.contains(bone.getName())) {
            if (!skinHidden && !isInspectHidden(bone.getName()) && hasTextureOverride(bone.getName())) {
                List<PolyMesh> meshes = meshMap.get(bone.getName());
                if (meshes != null) {
                    for (PolyMesh mesh : meshes) {
                        mesh.compileConsumer(ps.last(), buf, light, overlay, r, g, b, a);
                    }
                }
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderSubsetRec(child, ps, buf, bones, light, overlay, r, g, b, a);
        }
        ps.popPose();
    }

    /** 基础绘制时跳过"有贴图覆盖的骨"（它们由各自的 pass 单独画）。 */
    private boolean hasTextureOverride(String bone) {
        return boneTextures.containsKey(bone) || boneTextureSkins.containsKey(bone);
    }

    /** rig 排除：整枝剪掉（语义 = 这套 rig 里根本不画这根骨及其子树）。 */
    private boolean isRigExcluded(String bone) {
        return !excludedBones.isEmpty() && excludedBones.contains(bone);
    }

    /**
     * 皮肤替换件的隐藏：**只隐藏这根骨自己的网格，子骨照画**。
     *
     * <p>不能剪整枝：被隐藏的原件子树里往往还有必须留着的东西——例如 `suppressor` 子树里
     * 挂着枪自带的狙击镜（`sup_sightbase → carry/carry_lens/carry_frame`），
     * 换流动材质隐藏原皮消声器时若连子树一起剪，自带镜会凭空消失。</p>
     */
    private boolean isSkinHidden(String bone) {
        return !skinHiddenBones.isEmpty() && skinHiddenBones.contains(bone);
    }

    /** 该骨当前是否被隐藏（rig 排除 或 皮肤替换隐藏）。 */
    private boolean isHiddenBone(String bone) {
        return isRigExcluded(bone) || isSkinHidden(bone);
    }

    /** geo に "starflow": true の骨が 1 つでもあるか */
    public boolean hasStarflowBones() {
        return !starflowBones.isEmpty();
    }

    /**
     * "starflow": true の骨のメッシュだけを、指定 RenderType（additive + UV 滚动）で描く。
     * 他の骨は描かないがトラバースは継続する（骨のトランスフォームは通常通り適用）。
     * 通常パスの後に呼ぶ前提：深度は既に書かれているため、同位置に重ね描きで発光層になる。
     */
    public void renderStarflowOnly(PoseStack ps, MultiBufferSource buf, RenderType renderType,
                                   int light, int overlay, float r, float g, float b, float a,
                                   float uvOffU, float uvOffV) {
        if (starflowBones.isEmpty()) return;
        VertexConsumer vc = buf.getBuffer(renderType);
        renderStarflowBones(root, ps, vc, light, overlay, r, g, b, a, uvOffU, uvOffV);
    }

    /**
     * 流动纹理叠加层的 **VBO 路径**（等价于 {@link #renderStarflowOnly}，只画 starflow 骨）。
     *
     * <p>过滤规则与即时模式完全一致（可见性 / 网格祖先 / rig 排除 / 皮肤隐藏 / 有自己贴图的发光件不参与），
     * 唯一区别是绘制走 VBO：顶点色与 UV 偏移烘进缓冲、量化键没变就零上传。</p>
     *
     * @return true = 已用 VBO 画完；false = 当前环境（forceImmediate 等）需调用方走即时模式兜底
     */
    public boolean renderStarflowVbo(PoseStack ps, RenderType renderType, float r, float g, float b, float a,
                                     float uvOffU, float uvOffV) {
        if (forceImmediate || starflowBones.isEmpty()) return false;
        com.example.taczmeshloader.render.MeshyRenderTypes.setStarflowOffset(uvOffU, uvOffV);
        renderType.setupRenderState();                       // 与 renderVBO 同风格：状态在本方法内成对开关
        renderStarflowVboRec(root, ps, r, g, b, a, uvOffU, uvOffV);
        renderType.clearRenderState();
        return true;
    }

    private void renderStarflowVboRec(IPolyMeshBone bone, PoseStack ps, float r, float g, float b, float a,
                                      float uvOffU, float uvOffV) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (isRigExcluded(bone.getName())) return;
        boolean skinHidden = isSkinHidden(bone.getName());

        ps.pushPose();
        bone.applyTransform(ps);

        if (!skinHidden && starflowBones.contains(bone.getName()) && !isInspectHidden(bone.getName())
                && !hasTextureOverride(bone.getName())) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                for (PolyMesh mesh : meshes) {
                    mesh.ensureStarflowUploaded(r, g, b, a, 15728880);
                    mesh.drawStarflowVbo(ps.last().pose());
                }
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderStarflowVboRec(child, ps, r, g, b, a, uvOffU, uvOffV);
        }
        ps.popPose();
    }

    private void renderStarflowBones(IPolyMeshBone bone, PoseStack ps, VertexConsumer buf,
                                     int light, int overlay, float r, float g, float b, float a,
                                     float uvOffU, float uvOffV) {
        if (!bone.isVisible()) return;
        if (!meshAncestorBones.contains(bone.getName())) return;
        if (isRigExcluded(bone.getName())) return;
        boolean skinHidden = isSkinHidden(bone.getName());     // 皮肤隐藏：只跳自己网格，子骨照画

        ps.pushPose();
        bone.applyTransform(ps);

        // 有自己贴图的骨（发光件）**不参与流动纹理叠加**：否则材质遮罩会把发光件盖住（用户 2026-09-13 要求）
        if (!skinHidden && starflowBones.contains(bone.getName()) && !isInspectHidden(bone.getName())
                && !hasTextureOverride(bone.getName())) {
            List<PolyMesh> meshes = meshMap.get(bone.getName());
            if (meshes != null) {
                for (PolyMesh mesh : meshes) {
                    mesh.compileConsumer(ps.last(), buf, 15728880, overlay, r, g, b, a, uvOffU, uvOffV);
                }
            }
        }
        for (IPolyMeshBone child : bone.getChildren()) {
            renderStarflowBones(child, ps, buf, light, overlay, r, g, b, a, uvOffU, uvOffV);
        }
        ps.popPose();
    }

    /**
     * additional_magazine の FunctionalRenderer から直接呼ぶ版。
     * TacZ が用意した VertexConsumer にそのまま書き込むため
     * MultiBufferSource / endBatch / turnOnLightLayer は一切不要。
     *
     * @param vertexConsumer TacZ の IFunctionalRenderer が渡す VertexConsumer
     */
    public void renderSubtreeDirect(String rootBoneName, PoseStack ps,
                                    VertexConsumer vertexConsumer, int light, int overlay) {
        IPolyMeshBone targetBone = findBone(this.root, rootBoneName);
        if (targetBone == null) return;
        renderBonesConsumer(targetBone, ps, vertexConsumer, light, overlay, 1f, 1f, 1f, 1f, false);
    }

    public void renderBonesStencilOnly(String rootBoneName, PoseStack ps, MultiBufferSource buf,
                                       ResourceLocation tex, int light, int overlay) {
        IPolyMeshBone bone = findBone(this.root, rootBoneName);
        if (bone == null) return;
        VertexConsumer vc = buf.getBuffer(RenderType.entityCutoutNoCull(tex));
        renderBonesConsumer(bone, ps, vc, light, overlay, 0f, 0f, 0f, 0f, false);
    }

    /** ボーン名でツリーを検索する */
    private IPolyMeshBone findBone(IPolyMeshBone bone, String name) {
        if (name.equals(bone.getName())) return bone;
        for (IPolyMeshBone child : bone.getChildren()) {
            IPolyMeshBone found = findBone(child, name);
            if (found != null) return found;
        }
        return null;
    }

    public boolean hasMeshInSubtree(String boneName) {
        IPolyMeshBone bone = findBone(this.root, boneName);
        if (bone == null) return false;
        return hasMeshInSubtreeInternal(bone);
    }

    private boolean hasMeshInSubtreeInternal(IPolyMeshBone bone) {
        if (meshMap.containsKey(bone.getName())) return true;
        for (IPolyMeshBone child : bone.getChildren()) {
            if (hasMeshInSubtreeInternal(child)) return true;
        }
        return false;
    }

    public void close() {
        for (List<PolyMesh> meshes : meshMap.values()) {
            for (PolyMesh m : meshes) m.close();
        }
    }
}
