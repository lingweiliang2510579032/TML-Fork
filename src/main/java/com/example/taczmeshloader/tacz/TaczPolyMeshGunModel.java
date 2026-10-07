package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.IFunctionalRenderer;
import com.tacz.guns.client.model.GunModelConstant;
import com.tacz.guns.client.model.bedrock.ModelRendererWrapper;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.client.animation.AnimationListener;
import com.tacz.guns.api.client.animation.ObjectAnimationChannel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.model.listener.model.ModelAdditionalMagazineListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshGunModel extends com.tacz.guns.client.model.BedrockGunModel implements com.example.taczmeshloader.render.FxParticleSource {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;
    /** LODモデル用にテクスチャを固定する場合にセット。nullなら通常通りTimelessAPIから取得。 */
    private ResourceLocation overrideTexture = null;
    /**
     * MAG_NORMAL_NODE 配下に poly_mesh があるかどうかのキャッシュ（loadPolyMesh 時に確定）。
     * additional_magazine のメッシュ描画要否判定に使う。
     */
    private boolean cachedHasMagMesh = false;
    /**
     * additional_magazine ボーン自体に poly_mesh があるかどうかのキャッシュ。
     * MAG_ADDITIONAL_NODE サブツリーに直接メッシュを持つ場合の判定に使う。
     */
    private boolean cachedHasAdditionalMagMesh = false;

    /** geo bone の静的 scale（例: the gun 导弹 scale 0 = 平时收起，动画播放时由动画覆盖）。 */
    private Map<String, float[]> staticScales = new HashMap<>();

    // =========================================================================
    // 检视动画 inspect_hide 骨隐藏 & 检视粒子特效（喷口/导弹）参数。
    // 全部由每枪 geo JSON 顶部 "extras" 注入（GunConfig），无配置时保持关闭：
    //   INSPECT_HIDE_T0/T1 默认 -1 → 隐藏窗口不生效；
    //   FX_ENABLED 默认 false → 特效不触发；其余 FX_* 字段仅在启用后读取。
    // =========================================================================
    private volatile float INSPECT_HIDE_T0 = -1f;
    private volatile float INSPECT_HIDE_T1 = -1f;
    private volatile boolean FX_ENABLED = false;
    private volatile float FX_JET_T0 = 2.4f;
    private volatile float FX_JET_T1 = 6.1f;
    private volatile float FX_JET_INTERVAL_S = 0.05f;
    private volatile int FX_JET_SMOKE_EVERY = 3;
    private volatile String FX_JET_L_BONE = "";
    private volatile String FX_JET_R_BONE = "";
    private volatile int FX_JET_PER_BATCH = 2;
    private volatile double FX_JET_SPEED = 0.02;
    private volatile float FX_MISSILE_R_T0 = 4.2f, FX_MISSILE_R_T1 = 5.4f;
    private volatile float FX_MISSILE_L_T0 = 4.6f, FX_MISSILE_L_T1 = 5.8f;
    private volatile float FX_MISSILE_INTERVAL_S = 0.04f;
    private volatile int FX_MISSILE_CLOUD_EVERY = 4;
    private volatile String FX_MISSILE_R_BONE = "";
    private volatile String FX_MISSILE_L_BONE = "";

    /** 限频游标(动画 progress 秒)；-1=待触发/新一次检视 */
    private float fxLastJetProgress = -1f;
    private float fxLastMissileProgress = -1f;
    private int fxJetCount = 0;
    private int fxMissileCount = 0;

    /** 换弹尾部拉栓抛壳（可配置）：空仓换弹在指定 action 的 [from,to] 秒窗口内抛一次真实弹壳。
     *  数值全部经 geo extras "reload_eject" 注入；未配置/action 为空 → 永不触发。 */
    private volatile boolean reloadEjectFired = false;
    private volatile String reloadEjectAction = "";
    private volatile float reloadEjectFrom = 0f;
    private volatile float reloadEjectTo = 0f;
    private volatile float reloadEjectVx = 0f, reloadEjectVy = 0f, reloadEjectVz = 0f;

    // =========================================================================
    // 流动纹理叠加层（皮肤"星空流动"）：由 geo extras "starflow" 注入；
    // 只有 geo 里存在 "starflow": true 的骨时才会真正绘制。
    // =========================================================================
    private volatile boolean STARFLOW_ENABLED = false;
    private volatile ResourceLocation STARFLOW_TEXTURE = null;
    private volatile float STARFLOW_SPEED_U = 0.02f;
    private volatile float STARFLOW_SPEED_V = 0.0f;
    private volatile float STARFLOW_SCALE_U = 1.0f;
    private volatile float STARFLOW_SCALE_V = 1.0f;
    private volatile float STARFLOW_TINT_R = 1f, STARFLOW_TINT_G = 1f, STARFLOW_TINT_B = 1f;
    private volatile float STARFLOW_ALPHA = 1f;

    // =========================================================================
    // 自发光层（emissive）：additive + 全亮叠加，对应 UE 的 Emissive_map；不依赖光影。
    // =========================================================================
    private volatile boolean EMISSIVE_ENABLED = false;
    private volatile ResourceLocation EMISSIVE_TEXTURE = null;
    private volatile float EMISSIVE_TINT_R = 1f, EMISSIVE_TINT_G = 1f, EMISSIVE_TINT_B = 1f;
    private volatile float EMISSIVE_ALPHA = 1f;
    private volatile float EMISSIVE_PULSE_HZ = 0f;
    private volatile float EMISSIVE_PULSE_AMT = 0f;

    // =========================================================================
    // 开火特效（shot_fx）：由 geo extras 注入。腰射 = 枪口迸发 + 弹道闪电链；开镜 = 仅闪电链。
    // =========================================================================
    private volatile boolean SHOT_FX_ENABLED = false;
    private volatile String SHOT_FX_MUZZLE_BONE = "";
    private volatile float SHOT_FX_AIM_THRESHOLD = 0.5f;
    private volatile String SHOT_FX_HIP_PARTICLE = "minecraft:electric_spark";
    private volatile int SHOT_FX_HIP_COUNT = 24;
    private volatile double SHOT_FX_HIP_SPEED = 0.35;
    private volatile String SHOT_FX_CHAIN_PARTICLE = "minecraft:electric_spark";
    private volatile double SHOT_FX_CHAIN_SPACING = 0.35;
    private volatile int SHOT_FX_CHAIN_WAVES = 4;
    private volatile double SHOT_FX_CHAIN_RANGE = 96.0;
    private volatile double SHOT_FX_CHAIN_JITTER = 0.06;

    /** 皮肤表（geo extras "skins"）：id → 皮肤；选择存在枪 NBT（skinNbtKey）里。 */
    /** 皮肤表（**保持 geo 声明顺序**：第一项 = 没选皮肤时的默认皮肤） */
    private final Map<String, GunConfig.Skin> skinsById = new LinkedHashMap<>();
    private volatile String skinNbtKey = "MeshSkin";

    /** 本枪要捕获的骨名缓存（配置不变就复用；这段代码每帧执行，禁止逐帧重建集合）。 */
    private volatile java.util.List<String> fxCaptureBonesCache = null;

    /** UE 粒子发射器表（geo extras "fx".emitters）；空 = 本枪没有粒子特效。 */
    private volatile java.util.List<GunConfig.FxEmitter> fxEmitters = java.util.List.of();

    public boolean hasFxParticles() { return !fxEmitters.isEmpty(); }

    public java.util.List<GunConfig.FxEmitter> activeFxEmitters() { return fxEmitters; }

    /**
     * 指定骨在**世界坐标**里的位置（粒子发射点）：取渲染瞬间捕获的骨矩阵
     * （{@code PolyMeshModel.getFxCapturedPose}）再走同一套相机/FOV 换算，
     * 因此与画面上看到的枪口严格同点。
     */
    public Vec3 fxBoneWorld(String bone, Minecraft mc) {
        return fxBoneWorld(bone, 0f, 0f, 0f, mc);
    }

    /**
     * 同上，外加**相机空间**微调 (ox=右, oy=上, oz=深度；枪口骨在 z 上是负向前)。
     * 偏移加在骨矩阵的平移项上，因此后续相机/FOV 换算与不偏移时完全一致。
     */
    public Vec3 fxBoneWorld(String bone, float ox, float oy, float oz, Minecraft mc) {
        return fxBoneWorld(bone, ox, oy, oz, false, mc);
    }

    public Vec3 fxBoneWorld(String bone, float ox, float oy, float oz, boolean boneSpace, Minecraft mc) {
        if (bone == null || bone.isEmpty() || polyMeshModel == null || mc == null) return null;
        Matrix4f pose = polyMeshModel.getFxCapturedPose(bone);
        if (pose == null) return null;
        if (ox != 0f || oy != 0f || oz != 0f) {
            pose = boneSpace ? new Matrix4f(pose).translate(ox, oy, oz)
                    : new Matrix4f(pose).setTranslation(pose.m30() + ox, pose.m31() + oy, pose.m32() + oz);
        }
        return toWorldPos(mc, pose);
    }

    /** World-aligned offset from the camera; caller owns scratch objects, no per-frame allocation. */
    public boolean fxBoneWorldOffset(String bone, float ox, float oy, float oz, Minecraft mc,
                                     float fovScale, Matrix4f scratch, org.joml.Vector3f result) {
        return fxBoneWorldOffset(bone, ox, oy, oz, false, mc, fovScale, scratch, result);
    }

    public boolean fxBoneWorldOffset(String bone, float ox, float oy, float oz, boolean boneSpace, Minecraft mc,
                                     float fovScale, Matrix4f scratch, org.joml.Vector3f result) {
        if (polyMeshModel == null || !polyMeshModel.copyFxCapturedPose(bone, scratch)) return false;
        if (boneSpace) {
            scratch.transformPosition(result.set(ox, oy, oz));
            result.z *= fovScale;
        } else result.set(scratch.m30() + ox, scratch.m31() + oy, (scratch.m32() + oz) * fovScale);
        fxCameraToWorld(result, mc.gameRenderer.getMainCamera().rotation());
        return true;
    }

    /** 最近一次第一人称渲染的本枪模型（开火特效据此取配置 + 捕获的枪口矩阵）。 */
    public boolean fxBoneWorldPose(String bone, Minecraft mc, float fovScale, Matrix4f result) {
        if (polyMeshModel == null || !polyMeshModel.copyFxCapturedPose(bone, result)) return false;
        fxCameraPoseToWorld(result, fovScale, mc.gameRenderer.getMainCamera().rotation());
        return true;
    }

    /** Camera uses +Z forward/+X left; captured hand poses use -Z forward/+X right. */
    private static void fxCameraToWorld(org.joml.Vector3f result, org.joml.Quaternionf camera) {
        result.x = -result.x;
        result.z = -result.z;
        camera.transform(result);
    }

    private static void fxCameraPoseToWorld(Matrix4f result, float fovScale, org.joml.Quaternionf camera) {
        result.scaleLocal(-1f, 1f, -fovScale).rotateLocal(camera);
    }

    private static volatile TaczPolyMeshGunModel LAST_LOCAL_FP_MODEL = null;

    public static TaczPolyMeshGunModel lastLocalFirstPersonModel() {
        return LAST_LOCAL_FP_MODEL;
    }

    /** 该模型最近一次第一人称渲染用的枪物品栈（开火特效用它校验"登记模型 == 当前手持的枪"）。 */
    public ItemStack lastRenderStack() {
        return lastSkinStack;
    }

    public boolean isShotFxEnabled() {
        return SHOT_FX_ENABLED && polyMeshModel != null && !SHOT_FX_MUZZLE_BONE.isEmpty();
    }

    // ---- 诊断用只读（定位开火特效为何不生效）----
    public boolean rawShotFxEnabled() { return SHOT_FX_ENABLED; }
    public String rawShotFxBone() { return SHOT_FX_MUZZLE_BONE; }
    public boolean rawHasPolyMesh() { return polyMeshModel != null; }

    public String getShotFxHipBurstParticle() { return SHOT_FX_HIP_PARTICLE; }
    public int getShotFxHipBurstCount() { return SHOT_FX_HIP_COUNT; }
    public double getShotFxHipBurstSpeed() { return SHOT_FX_HIP_SPEED; }
    public String getShotFxChainParticle() { return SHOT_FX_CHAIN_PARTICLE; }
    public double getShotFxChainSpacing() { return SHOT_FX_CHAIN_SPACING; }
    public int getShotFxChainWaves() { return SHOT_FX_CHAIN_WAVES; }
    public double getShotFxChainRange() { return SHOT_FX_CHAIN_RANGE; }
    public double getShotFxChainJitter() { return SHOT_FX_CHAIN_JITTER; }

    private void applyShotFxConfig(GunConfig cfg) {
        SHOT_FX_ENABLED = cfg.shotFxEnabled;
        SHOT_FX_MUZZLE_BONE = cfg.shotFxMuzzleBone;
        SHOT_FX_AIM_THRESHOLD = cfg.shotFxAimThreshold;
        SHOT_FX_HIP_PARTICLE = cfg.shotFxHipBurstParticle;
        SHOT_FX_HIP_COUNT = cfg.shotFxHipBurstCount;
        SHOT_FX_HIP_SPEED = cfg.shotFxHipBurstSpeed;
        SHOT_FX_CHAIN_PARTICLE = cfg.shotFxChainParticle;
        SHOT_FX_CHAIN_SPACING = cfg.shotFxChainSpacing;
        SHOT_FX_CHAIN_WAVES = cfg.shotFxChainWaves;
        SHOT_FX_CHAIN_RANGE = cfg.shotFxChainRange;
        SHOT_FX_CHAIN_JITTER = cfg.shotFxChainJitter;
    }

    /** 射手是否在开镜（读 TaCZ 同步的开镜进度；读取失败按未开镜处理）。 */
    public boolean isShooterAiming(Minecraft mc) {
        if (mc.player == null) return false;
        try {
            Float progress = com.tacz.guns.entity.sync.ModSyncedEntityData.AIMING_PROGRESS_KEY.getValue(mc.player);
            if (progress != null) return progress >= SHOT_FX_AIM_THRESHOLD;
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 枪口世界坐标（用渲染瞬间捕获的骨矩阵换算；未捕获到返回 null）。 */
    public Vec3 getShotFxMuzzleWorld(Minecraft mc) {
        if (polyMeshModel == null || SHOT_FX_MUZZLE_BONE.isEmpty()) return null;
        org.joml.Matrix4f pose = polyMeshModel.getFxCapturedPose(SHOT_FX_MUZZLE_BONE);
        if (pose == null) return null;
        return toWorldPos(mc, pose);
    }

    /** 骨路径缓存：目标骨名 → 自模型根起的 BedrockPart 链 */
    private final Map<String, List<BedrockPart>> fxBonePathCache = new HashMap<>();

    /** 调试：每骨前 N 次打印粒子相机偏移与换算出的世界坐标，用于修正喷口定位。 */
    private final Map<String, Integer> fxDbgBoneLogCount = new HashMap<>();

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    private void resetGunConfig() {
        INSPECT_HIDE_T0 = -1f;
        INSPECT_HIDE_T1 = -1f;
        FX_ENABLED = false;
        reloadEjectFired = false;
        reloadEjectAction = "";
        reloadEjectFrom = 0f;
        reloadEjectTo = 0f;
        reloadEjectVx = 0f;
        reloadEjectVy = 0f;
        reloadEjectVz = 0f;
        STARFLOW_ENABLED = false;
        STARFLOW_TEXTURE = null;
        STARFLOW_SPEED_U = 0.02f;
        STARFLOW_SPEED_V = 0.0f;
        STARFLOW_SCALE_U = 1.0f;
        STARFLOW_SCALE_V = 1.0f;
        STARFLOW_TINT_R = 1f; STARFLOW_TINT_G = 1f; STARFLOW_TINT_B = 1f;
        STARFLOW_ALPHA = 1f;
        SHOT_FX_ENABLED = false;
        SHOT_FX_MUZZLE_BONE = "";
        fxEmitters = java.util.List.of();
        fxCaptureBonesCache = null;
        skinsById.clear();
        skinNbtKey = "MeshSkin";
        EMISSIVE_ENABLED = false;
        EMISSIVE_TEXTURE = null;
    }

    public void applyGunConfig(com.example.taczmeshloader.tacz.GunConfig cfg) {
        if (cfg == null) {
            resetGunConfig();
            if (polyMeshModel != null) polyMeshModel.setLightLimits(-1, -1);
            return;
        }
        // 光照上限/软压缩：压住直射阳光，避免枪在阳光直射下过曝（不依赖任何光影包）
        if (polyMeshModel != null) {
            polyMeshModel.setLightLimits(cfg.lightSkyCap, cfg.lightBlockCap, cfg.lightSkyKeep, cfg.lightBlockKeep);
        }
        if (cfg.hasInspectHide) {
            INSPECT_HIDE_T0 = cfg.inspectHideFrom;
            INSPECT_HIDE_T1 = cfg.inspectHideTo;
        } else {
            INSPECT_HIDE_T0 = -1f;
            INSPECT_HIDE_T1 = -1f;
        }
        FX_ENABLED = cfg.fxEnabled;
        FX_JET_T0 = cfg.jetFrom;
        FX_JET_T1 = cfg.jetTo;
        FX_JET_INTERVAL_S = cfg.jetInterval;
        FX_JET_SMOKE_EVERY = cfg.jetSmokeEvery;
        FX_JET_L_BONE = cfg.jetLeftBone;
        FX_JET_R_BONE = cfg.jetRightBone;
        FX_JET_PER_BATCH = cfg.jetPerBatch;
        FX_JET_SPEED = cfg.jetSpeed;
        FX_MISSILE_R_T0 = cfg.missileRightFrom;
        FX_MISSILE_R_T1 = cfg.missileRightTo;
        FX_MISSILE_L_T0 = cfg.missileLeftFrom;
        FX_MISSILE_L_T1 = cfg.missileLeftTo;
        FX_MISSILE_INTERVAL_S = cfg.missileInterval;
        FX_MISSILE_CLOUD_EVERY = cfg.missileCloudEvery;
        FX_MISSILE_R_BONE = cfg.missileRightBone;
        FX_MISSILE_L_BONE = cfg.missileLeftBone;
        reloadEjectFired = false;
        reloadEjectAction = cfg.reloadEjectAction;
        reloadEjectFrom = cfg.reloadEjectFrom;
        reloadEjectTo = cfg.reloadEjectTo;
        reloadEjectVx = cfg.reloadEjectVx;        reloadEjectVy = cfg.reloadEjectVy;
        reloadEjectVz = cfg.reloadEjectVz;
        // 流动纹理叠加层（皮肤"星空流动"）：只有 geo 里写了 starflow 骨 + 贴图 id 才生效
        STARFLOW_ENABLED = cfg.starflowEnabled && !cfg.starflowTexture.isEmpty()
                && polyMeshModel != null && polyMeshModel.hasStarflowBones();
        STARFLOW_TEXTURE = STARFLOW_ENABLED ? ResourceLocation.tryParse(cfg.starflowTexture) : null;
        STARFLOW_SPEED_U = cfg.starflowSpeedU;
        STARFLOW_SPEED_V = cfg.starflowSpeedV;
        STARFLOW_SCALE_U = cfg.starflowScaleU;
        STARFLOW_SCALE_V = cfg.starflowScaleV;
        STARFLOW_TINT_R = cfg.starflowTintR;
        STARFLOW_TINT_G = cfg.starflowTintG;
        STARFLOW_TINT_B = cfg.starflowTintB;
        STARFLOW_ALPHA = cfg.starflowAlpha;
        applyShotFxConfig(cfg);
        skinsById.clear();
        for (GunConfig.Skin sk : cfg.skins) skinsById.put(sk.id, sk);
        fxEmitters = cfg.fxEmitters.isEmpty() ? java.util.List.of() : java.util.List.copyOf(cfg.fxEmitters);
        fxCaptureBonesCache = null;   // 配置变了⇒缓存作废
        skinNbtKey = cfg.skinNbtKey;
        EMISSIVE_ENABLED = cfg.emissiveEnabled && !cfg.emissiveTexture.isEmpty();
        EMISSIVE_TEXTURE = EMISSIVE_ENABLED ? ResourceLocation.tryParse(cfg.emissiveTexture) : null;
        EMISSIVE_TINT_R = cfg.emissiveTintR; EMISSIVE_TINT_G = cfg.emissiveTintG; EMISSIVE_TINT_B = cfg.emissiveTintB;
        EMISSIVE_ALPHA = cfg.emissiveAlpha;
        EMISSIVE_PULSE_HZ = cfg.emissivePulseHz; EMISSIVE_PULSE_AMT = cfg.emissivePulseAmt;
    }


    public TaczPolyMeshGunModel(
            com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO pojo,
            com.tacz.guns.client.resource.pojo.model.BedrockVersion version) {
        super(pojo, version);
    }

    private boolean polyPassPending;

    /** Draw the mesh while TaCZ still owns the scope stencil from its first render. */
    public void renderMeshBeforeStencilClear(PoseStack poseStack, ItemStack stack,
                                             ItemDisplayContext transformType, int light, int overlay) {
        if (!polyPassPending || polyMeshModel == null) return;
        polyPassPending = false;
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean gui = com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();
        ItemStack scope = getCurrentAttachmentItem().get(com.tacz.guns.api.item.attachment.AttachmentType.SCOPE);
        boolean hasScope = scopePosPath != null && scope != null && !scope.isEmpty()
                && com.tacz.guns.api.item.IAttachment.getIAttachmentOrNull(scope) != null;
        if (!gui) mc.gameRenderer.lightTexture().turnOnLightLayer();
        try {
            long start = System.nanoTime();
            if (hasScope && !gui) renderPolyMeshWithStencil(poseStack, buffers, lastSkinTex, light, overlay, true);
            else renderPolyMeshNormal(poseStack, buffers, lastSkinTex, light, overlay, !gui);
            com.example.taczmeshloader.render.MeshPerf.frameDone(
                    System.nanoTime() - start, mc.getFps(), transformType.firstPerson(), activeSkinId(stack));
        } finally {
            if (!gui) mc.gameRenderer.lightTexture().turnOffLightLayer();
        }
    }

    @Override
    public void render(PoseStack poseStack, ItemStack stack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {

        if (!this.hasPolyMesh()) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        if (cachedTexture == null) {
            if (overrideTexture != null) {
                cachedTexture = overrideTexture;
            } else {
                TimelessAPI.getGunDisplay(stack).ifPresent(display ->
                        cachedTexture = display.getModelTexture()
                );
            }
        }

        if (cachedTexture == null) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        lastSkinStack = stack;
        // ---- 皮肤：按枪 NBT 覆盖贴图（未选/表为空 → 用 display 默认贴图）----
        GunConfig.Skin skin = activeSkin(stack);
        ResourceLocation useTex = cachedTexture;
        if (skin != null && !skin.texture.isEmpty()) {
            ResourceLocation sk = parseTexFile(skin.texture);   // geo 里的 id → 资源路径
            if (sk != null) useTex = sk;
        }
        // ---- 替换件：geo 里写了 "skins": [...] 的骨只在列出的皮肤下出现
        //      （如换皮肤要连模型一起换的消声器）；没声明的枪不走这条判定。
        if (polyMeshModel != null && polyMeshModel.hasSkinFilteredBones()) {
            String skinId = activeSkinId(stack);
            java.util.Set<String> hidden = polyMeshModel.collectSkinHidden(skinId);
            polyMeshModel.setSkinHiddenBones(hidden);
            if (!skinId.equals(lastSkinLogged)) {
                lastSkinLogged = skinId;
                java.util.Map<String, String> tex = polyMeshModel.getBoneTextures(skinId);
                MESH_LOG.info("[SkinDbg] 生效皮肤='{}' 隐藏骨={} 逐骨贴图={}", skinId, hidden, tex);
            }
        }

        this.lastSkinTex = useTex;
        boolean firstPerson = transformType.firstPerson();
        com.example.taczmeshloader.render.MeshPerf.setFirstPerson(firstPerson);   // 计时只算第一人称那一遍

        // 检视动画(inspect/inspect_empty)进度(秒)；非第一人称恒 -1
        final float inspectT = firstPerson ? getInspectProgressSeconds(stack) : -1f;
        // 进度落在隐藏窗口内时，跳过 geo 中 "inspect_hide": true 骨自身的 poly_mesh
        // 绘制（stencil/normal 两条路径都经由 PolyMeshModel 的骨遍历，一处生效）。
        // 非第一人称（GUI/第三人称/掉落/地面等）恒不隐藏，非检视状态渲染不受影响。
        if (polyMeshModel != null) {
            polyMeshModel.setInspectHideActive(inspectT >= INSPECT_HIDE_T0 && inspectT <= INSPECT_HIDE_T1);
        }
        // 检视粒子特效（路线B）：FX_ENABLED && 第一人称 && 正在播检视(inspectT>=0)
        // 三个条件缺一不可；第三人称/GUI/其它动作 inspectT 恒 -1 → 永不触发。
        // 打开 FX 骨捕捉：本帧 poly 网格真正绘制各锚骨时会把骨矩阵快照下来，
        // 渲染完成后再据此撒粒，保证粒子与可见模型严格同点。
        if (polyMeshModel != null && FX_ENABLED && firstPerson && inspectT >= 0f) {
            polyMeshModel.setFxCaptureBones(java.util.Arrays.asList(
                    FX_JET_L_BONE, FX_JET_R_BONE,
                    FX_MISSILE_R_BONE, FX_MISSILE_L_BONE));
        }
        // 开火特效：登记"本机第一人称正在渲染的这把枪"并把枪口骨纳入渲染瞬间捕获，
        // 开火时（render/ShotFxHandler、render/FxParticles）即可拿到与画面严格同点的枪口矩阵。
        // ★ 登记条件是「shot_fx **或** fx.emitters 存在」，不能只看 shot_fx ——
        //   2026-09-13 实测：某把枪只配 fx.emitters（无 shot_fx）时登记被跳过，
        //   开火瞬间 lastLocalFirstPersonModel=null ⇒ 粒子全部 abort（日志实证 12 条 abort）。
        boolean wantShotFx = SHOT_FX_ENABLED && !SHOT_FX_MUZZLE_BONE.isEmpty();
        boolean wantParticles = hasFxParticles();
        if (firstPerson && polyMeshModel != null && (wantShotFx || wantParticles)) {
            LAST_LOCAL_FP_MODEL = this;
            // ★ 骨集合只在配置变化时重建并缓存：这段每帧都跑，逐帧 new 集合是白白烧 CPU
            //   （切枪/连续开火时的卡顿来源之一）。
            if (fxCaptureBonesCache == null) {
                java.util.LinkedHashSet<String> bones = new java.util.LinkedHashSet<>();
                if (wantShotFx) bones.add(SHOT_FX_MUZZLE_BONE);
                for (GunConfig.FxEmitter em : fxEmitters) {
                    if (em.bone != null && !em.bone.isEmpty()) bones.add(em.bone);
                }
                fxCaptureBonesCache = bones.isEmpty() ? java.util.List.of() : java.util.List.copyOf(bones);
            }
            if (!fxCaptureBonesCache.isEmpty()) polyMeshModel.setFxCaptureBones(fxCaptureBonesCache);
        }

        int safeLight = light;
        int safeOverlay = overlay;

        // インベントリのプレイヤープレビュー（ドール表示）など、GUI 画面が開いている
        // 状態では VBO 直接描画が正しく表示されないことが実機で確認されているため、
        // その場合は VBO を無効化する。「メニューが開いているか」ではなく、実際に
        // GUI 描画（Screen#render()）が実行されている「瞬間」だけを検出する
        // （ワールド内の無関係な描画への影響を避けるため）。
        final boolean isGuiLike = com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();
        final boolean useVBO = !isGuiLike;

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        if (cachedHasAdditionalMagMesh) {
            polyMeshModel.setExcludeSubtree(GunModelConstant.MAG_ADDITIONAL_NODE);
        } else {
            polyMeshModel.clearExcludeSubtree();
        }

        // ---------- AR (Accelerated Rendering) との関わり方 ----------
        //
        // 長期にわたる調査の結果（Forge/NeoForge/Fabric 1.20.1/1.21.1 全プラット
        // フォームで検証）、AR のレイヤー機構（setRenderLayer /
        // setRenderBeforeFunction による遅延実行）を介して poly_mesh の
        // 描画とキューブボディの AR 加速を部分的に協調させようとする試みは、
        // プラットフォームごとに異なる形で（スコープのレンズが真っ黒になる、
        // poly_mesh のアニメーションがフリーズする、Iris 環境での FPS 異常、
        // NeoForge での照準以外の場面での FPS 低下など）繰り返し不具合を
        // 引き起こすことが判明した。
        //
        // そのため方針を統一し、AR が有効な場合は、このメッシュ銃の描画全体
        // （キューブボディ・poly_mesh の両方）を AR の介入対象から完全に外す。
        // AR が有効な間だけ一時的に無効化し、AR が全く導入されていない場合と
        // 完全に同じコードパス（常に非加速）で描画する。
        //
        // トレードオフ: メッシュ銃はキューブボディも含めて AR 加速の恩恵を
        // 受けられなくなる。しかし AR とレイヤー機構経由で協調させようとする
        // ことに起因する不具合の再発を避けるため、確実な動作を優先する。
        // （なお、MeshyLoader が一切関与しない純粋なキューブオンリーの銃は
        // これまで通り正常に AR 加速される。）
        final boolean shouldRestoreAcceleration = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (shouldRestoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        // 非第一人称（GUI/掉落/地面/第三人称等）はアニメーションが走らないため、
        // geo で宣言した静的 scale（例: 导弹 scale 0）を強制適用して隠す。
        if (transformType != ItemDisplayContext.FIRST_PERSON_LEFT_HAND
                && transformType != ItemDisplayContext.FIRST_PERSON_RIGHT_HAND) {
            applyStaticScales();
        }
        try {
            poseStack.pushPose();
            polyPassPending = true;
            try { super.render(poseStack, stack, transformType, renderType, light, overlay); }
            finally { polyPassPending = false; }
            updateReloadShell(stack, firstPerson);
            // 检视粒子特效：poly 网格绘制完成后，用各锚骨的真实绘制矩阵撒粒，
            // 与模型严格同点（第一人称+检视窗口才触发，非检视恒被 inspectT<0 拦下）。
            if (FX_ENABLED && firstPerson && inspectT >= 0f && polyMeshModel != null) {
                spawnInspectFxFromCaptured(mc2, inspectT);
            }
            poseStack.popPose();
        } finally {
            com.example.taczmeshloader.render.MeshPerf.setFirstPerson(false);
            if (shouldRestoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
        }
    }


    /**

     * geo.json を読み込み、poly_mesh ボーンを PolyMeshModel に登録する。
     *
     * <h3>キューブ・メッシュ混在対応</h3>
     * cubes の消去は一切行わない。理由は次の通り:
     * <ul>
     *   <li><b>poly_mesh のみ</b>のボーンは geo.json 上で cubes が元々空。
     *       TacZ 側は何も描画しないため PolyMesh との二重描画は起きない。</li>
     *   <li><b>cubes のみ</b>のボーンは PolyMeshModel の meshMap に存在しないため
     *       PolyMesh 側は何もしない。TacZ が正常にキューブを描画する。</li>
     *   <li><b>両方を持つ混在ボーン</b>は TacZ がキューブを、PolyMesh がメッシュを
     *       それぞれ描画し、両者が正しく合わさる。cubes を消す必要はない。</li>
     * </ul>
     */

    // =========================================================================
    // PolyMesh 描画ヘルパー
    // =========================================================================

    /**
     * 当前播放中的检视动画进度（秒）；未在播检视返回 -1。
     * 遍历方式与 rig 路径的 resolveAction 相同（GunDisplayInstance →
     * getAnimationStateMachine → getAnimationController → getAnimation(track) →
     * runner.getProgressNs() / 1e9f）。
     */
    public float getInspectProgressSeconds(ItemStack stack) {
        var displayOpt = TimelessAPI.getGunDisplay(stack);
        if (!displayOpt.isPresent()) return -1f;
        var stateMachine = displayOpt.get().getAnimationStateMachine();
        if (stateMachine == null) return -1f;
        var controller = stateMachine.getAnimationController();
        for (int track = 0; track < 16; track++) {
            var runner = controller.getAnimation(track);
            if (runner == null) continue;
            var transitionTo = runner.getTransitionTo();
            if (transitionTo != null) runner = transitionTo;
            String key = runner.getAnimation().name;
            if ("inspect".equals(key) || "inspect_empty".equals(key)) {
                return runner.getProgressNs() / 1e9f;
            }
        }
        return -1f;
    }


    /** 在全轨道中找指定动画名的进度（秒）；未在播返回 -1。 */
    private float findActionProgress(ItemStack stack, String wanted) {
        var opt = TimelessAPI.getGunDisplay(stack);
        if (opt.isEmpty()) return -1f;
        var sm = opt.get().getAnimationStateMachine();
        if (sm == null) return -1f;
        var ctl = sm.getAnimationController();
        for (int track = 0; track < 16; track++) {
            var runner = ctl.getAnimation(track);
            if (runner == null) continue;
            var t = runner.getTransitionTo();
            if (t != null) runner = t;
            if (wanted.equals(runner.getAnimation().name)) {
                return runner.getProgressNs() / 1e9f;
            }
        }
        return -1f;
    }

    /** 换弹尾部拉栓抛壳：仅当 geo extras 配置了 reload_eject（action 非空）且指定动作
     *  正在播放时工作。进度落入 [from,to] 窗口触发一次 TACZ ShellRender.addShell
     *  （抛真实弹壳）；离开窗口或动作切走即复位，可随动画重放再次触发。 */
    private void updateReloadShell(ItemStack stack, boolean firstPerson) {
        if (!firstPerson || reloadEjectAction.isEmpty()) {
            reloadEjectFired = false;
            return;
        }
        float prog = findActionProgress(stack, reloadEjectAction);
        if (prog < 0f) {
            reloadEjectFired = false;
            return;
        }
        if (!reloadEjectFired && prog >= reloadEjectFrom && prog <= reloadEjectTo) {
            reloadEjectFired = true;
            MESH_LOG.info("[MeshLoader] reload eject action={} progress={}s", reloadEjectAction, prog);
            try {
                com.tacz.guns.client.model.functional.ShellRender sr0 = getShellRender(0);
                if (sr0 != null) {
                    sr0.addShell(new org.joml.Vector3f(reloadEjectVx, reloadEjectVy, reloadEjectVz));
                }
            } catch (Exception shellErr) {
                MESH_LOG.error("[MeshLoader] reload eject failed", shellErr);
            }
        } else if (prog < reloadEjectFrom || prog > reloadEjectTo) {
            reloadEjectFired = false;
        }
    }

    // =========================================================================
    // 检视粒子特效实现（路线B）
    // =========================================================================

    /**
     * 检视粒子特效入口。render() 在 poly 网格绘制完成后调用（仅当
     * FX_ENABLED && 第一人称 && inspectT >= 0 时进入）；内部按各特效窗口与
     * 玩家/世界非空兜底，非检视状态绝不会走到 addParticle。
     * 位置来源：PolyMeshModel 在真正绘制喷口/导弹骨那一瞬捕获的累计矩阵
     * （getFxCapturedPose），因此粒子与画面上的模型严格同点；随后经相机
     * 旋转/平移换算成世界坐标 addParticle。
     */
    private long lastFxSpawnNs = 0L;

    private void spawnInspectFxFromCaptured(Minecraft mc, float inspectT) {
        if (!FX_ENABLED) return;
        if (mc.player == null || mc.level == null || polyMeshModel == null) return;

        // ---- 双喷口巡航尾焰（巡航 2.4~6.1s；FLAME 为主，每 N 批掺 SMOKE） ----
        if (inspectT >= FX_JET_T0 && inspectT <= FX_JET_T1) {
            float dt = Math.max(0f, inspectT - fxLastJetProgress);
            if (fxLastJetProgress < 0 || dt >= FX_JET_INTERVAL_S) {
                fxLastJetProgress = inspectT;
                addCapturedParticles(mc, FX_JET_R_BONE,
                        ParticleTypes.FLAME, FX_JET_PER_BATCH, FX_JET_SPEED);
                addCapturedParticles(mc, FX_JET_L_BONE,
                        ParticleTypes.FLAME, FX_JET_PER_BATCH, FX_JET_SPEED);
                if (FX_JET_SMOKE_EVERY > 0 && fxJetCount++ % FX_JET_SMOKE_EVERY == 0) {
                    addCapturedParticles(mc, FX_JET_R_BONE,
                            ParticleTypes.SMOKE, 1, FX_JET_SPEED * 0.4);
                    addCapturedParticles(mc, FX_JET_L_BONE,
                            ParticleTypes.SMOKE, 1, FX_JET_SPEED * 0.4);
                }
            }
        } else if (inspectT > FX_JET_T1) {
            fxLastJetProgress = -1f; // 本段结束，重置游标以便下次检视立即生效
        }

        // ---- 导弹发射（右 4.2~5.4 / 左 4.6~5.8）：在导弹骨处撒 SMOKE+CLOUD，
        //      导弹骨随动画前飞，粒子留在原世界坐标 → 自然形成拖尾烟 ----
        boolean rW = inspectT >= FX_MISSILE_R_T0 && inspectT <= FX_MISSILE_R_T1;
        boolean lW = inspectT >= FX_MISSILE_L_T0 && inspectT <= FX_MISSILE_L_T1;
        if (rW || lW) {
            float dt = Math.max(0f, inspectT - fxLastMissileProgress);
            if (fxLastMissileProgress < 0 || dt >= FX_MISSILE_INTERVAL_S) {
                fxLastMissileProgress = inspectT;
                if (rW) {
                    if (FX_MISSILE_CLOUD_EVERY > 0 && fxMissileCount++ % FX_MISSILE_CLOUD_EVERY == 0) {
                        addCapturedParticles(mc, FX_MISSILE_R_BONE,
                                ParticleTypes.CLOUD, 1, 0.02);
                    }
                    addCapturedParticles(mc, FX_MISSILE_R_BONE,
                            ParticleTypes.SMOKE, 1, 0.02);
                }
                if (lW) {
                    if (FX_MISSILE_CLOUD_EVERY > 0 && fxMissileCount % FX_MISSILE_CLOUD_EVERY == 0) {
                        addCapturedParticles(mc, FX_MISSILE_L_BONE,
                                ParticleTypes.CLOUD, 1, 0.02);
                    }
                    addCapturedParticles(mc, FX_MISSILE_L_BONE,
                            ParticleTypes.SMOKE, 1, 0.02);
                }
            }
        } else if (inspectT > FX_MISSILE_R_T1 && inspectT > FX_MISSILE_L_T1) {
            fxLastMissileProgress = -1f;
        }
    }

    /** 基于本帧 poly 网格绘制瞬间捕获的骨矩阵撒粒。 */
    private void addCapturedParticles(Minecraft mc, String boneName,
                                      net.minecraft.core.particles.ParticleOptions type,
                                      int count, double speed) {
        if (mc.level == null || polyMeshModel == null) return;
        Matrix4f pose = polyMeshModel.getFxCapturedPose(boneName);
        if (pose == null) return;
        Vec3 world = toWorldPos(mc, pose);
        if (world == null) return;
        logFxBonePos(boneName, pose, world, mc);
        java.util.Random rnd = new java.util.Random();
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(type, world.x, world.y, world.z,
                    (rnd.nextDouble() - 0.5) * speed, (rnd.nextDouble() - 0.5) * speed,
                    (rnd.nextDouble() - 0.5) * speed);
        }
    }

    private void logFxBonePos(String boneName, Matrix4f pose, Vec3 world, Minecraft mc) {
        Integer c = fxDbgBoneLogCount.get(boneName);
        if (c != null && c >= 4) return;
        fxDbgBoneLogCount.put(boneName, c == null ? 1 : c + 1);
        net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
        MESH_LOG.info("[FXBONE] bone={} camOff=({}, {}, {}) world=({}, {}, {}) camPos=({}, {}, {}) yaw={} pitch={}",
                boneName,
                String.format(java.util.Locale.ROOT, "%.3f", pose.m30()),
                String.format(java.util.Locale.ROOT, "%.3f", pose.m31()),
                String.format(java.util.Locale.ROOT, "%.3f", pose.m32()),
                String.format(java.util.Locale.ROOT, "%.2f", world.x),
                String.format(java.util.Locale.ROOT, "%.2f", world.y),
                String.format(java.util.Locale.ROOT, "%.2f", world.z),
                String.format(java.util.Locale.ROOT, "%.2f", cam.getPosition().x),
                String.format(java.util.Locale.ROOT, "%.2f", cam.getPosition().y),
                String.format(java.util.Locale.ROOT, "%.2f", cam.getPosition().z),
                String.format(java.util.Locale.ROOT, "%.1f", cam.getYRot()),
                String.format(java.util.Locale.ROOT, "%.1f", cam.getXRot()));
    }

    /**
     * 相机空间偏移 → 世界坐标。渲染链在相机空间：先以相机 yaw(+180)/pitch 的
     * 逆旋转把偏移转成世界朝向偏移，再加相机位置。旋转符号取自 TaCZ 曳光弹
     * {@code EntityBulletRenderer#renderTracerAmmo} 的相机补偿段。
     * 深度层：第一人称物品用 item 模型 FOV 渲染、粒子用世界 FOV 投影，
     * 二者视角不同会让粒子浮在模型前后不同“层面”；故对 z(深度) 先乘
     * tan(itemFov/2)/tan(levelFov/2)（与 TaCZ cacheMuzzlePosition 同法）。
     */
    private Vec3 toWorldPos(Minecraft mc, Matrix4f camSpacePose) {
        net.minecraft.client.Camera cam = mc.gameRenderer.getMainCamera();
        double itemFov = com.tacz.guns.client.event.CameraSetupEvent.ITEM_MODEL_FOV_DYNAMICS.get();
        double levelFov = com.tacz.guns.client.event.CameraSetupEvent.WORLD_FOV_DYNAMICS.get();
        double fovScale = 1.0;
        if (itemFov > 0 && levelFov > 0) {
            fovScale = Math.tan(Math.toRadians(itemFov) / 2.0)
                    / Math.tan(Math.toRadians(levelFov) / 2.0);
        }
        float ox = camSpacePose.m30();
        float oy = camSpacePose.m31();
        float oz = (float) (camSpacePose.m32() * fovScale);
        PoseStack ps = new PoseStack();
        ps.mulPose(com.mojang.math.Axis.YN.rotationDegrees(cam.getYRot() + 180f));
        ps.mulPose(com.mojang.math.Axis.XN.rotationDegrees(cam.getXRot()));
        Matrix4f rot = new Matrix4f(ps.last().pose());
        org.joml.Vector4f v = rot.transform(new org.joml.Vector4f(ox, oy, oz, 1f));
        return cam.getPosition().add(v.x, v.y, v.z);
    }

    private void renderPolyMeshWithStencil(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                           ResourceLocation tex, int light, int overlay, boolean useVBO) {
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
            bufferSource.endBatch(RenderType.entityCutout(tex));
        }
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityTranslucentCull(tex));
            }
        }
        renderStarflowPass(poseStack, bufferSource, light, overlay);
    }

    /**
     * 流动纹理叠加层（皮肤"星空流动"）：把 geo 里标了 {@code "starflow": true} 的骨再画一遍，
     * 材质 = additive + UV 滚动 + 不受光照（{@link com.example.taczmeshloader.render.MeshyRenderTypes#starflow}）。
     * 贴图与参数优先取"当前皮肤"（skins[].starflow），没有则回落到 geo 顶层 starflow 配置；
     * UV 偏移按现实时间（nanoTime）推进，与帧率、游戏 tick 无关；未配置的枪完全不进入此分支。
     */
    /**
     * geo 里写的贴图 id 是"id"形式（如 {@code example:gun/uv/example_surface}），
     * 但 MC 的纹理管理器要的是**完整资源路径**（{@code example:textures/gun/uv/example_surface.png}）。
     * TaCZ 展示文件里的贴图字段都会过一遍同一个 {@code FileToIdConverter("textures", ".png")}
     * （见 IDisplay.converter），我们写在 geo extras 里的贴图也必须过这一遍，
     * 否则纹理管理器会去找 {@code assets/example/gun/uv/…} → FileNotFound → 整枪变紫黑。
     */
    private static ResourceLocation toTexFile(ResourceLocation id) {
        if (id == null) return null;
        try {
            return com.tacz.guns.client.resource.pojo.display.IDisplay.converter.idToFile(id);
        } catch (Throwable t) {
            return id;
        }
    }

    private static ResourceLocation parseTexFile(String id) {
        return id == null || id.isEmpty() ? null : toTexFile(ResourceLocation.tryParse(id));
    }

    private void renderStarflowPass(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                    int light, int overlay) {
        if (polyMeshModel == null) return;
        // 逐骨贴图（发光件）**必须无条件先画**：主 pass 会跳过"有贴图覆盖的骨"，
        // 这里若不画就没人画它们（表现 = 发光件看起来是透明/缺件）。
        // 早先它挂在 renderEmissivePass 末尾，皮肤一旦把 emissive 关掉（流动材质就是），整条链被 early-return 掉。
        long tBone0 = System.nanoTime();
        renderBoneTexturePass(poseStack, bufferSource, lastSkinTex, light, overlay);
        com.example.taczmeshloader.render.MeshPerf.add(com.example.taczmeshloader.render.MeshPerf.BONE, System.nanoTime() - tBone0);
        if (!polyMeshModel.hasStarflowBones()) return;

        boolean enabled = STARFLOW_ENABLED;
        ResourceLocation tex = STARFLOW_TEXTURE;
        float speedU = STARFLOW_SPEED_U, speedV = STARFLOW_SPEED_V;
        float tr = STARFLOW_TINT_R, tg = STARFLOW_TINT_G, tb = STARFLOW_TINT_B, alpha = STARFLOW_ALPHA;

        GunConfig.Skin skin = activeSkin(lastSkinStack);
        if (skin != null && skin.starflow && !skin.starflowTexture.isEmpty()) {
            ResourceLocation sk = parseTexFile(skin.starflowTexture);
            if (sk != null) {
                enabled = true; tex = sk;
                speedU = skin.starflowSpeedU; speedV = skin.starflowSpeedV;
                tr = skin.starflowTint[0]; tg = skin.starflowTint[1]; tb = skin.starflowTint[2];
                alpha = skin.starflowAlpha;
            }
        } else if (skin != null && !skin.starflow) {
            enabled = false;   // 该皮肤明确不带流动纹理（如原皮）
        }

        // 客户端配置可整体关掉流动纹理叠加层（弱显存机器：省掉"整枪每帧再叠一遍"）
        if (enabled && !com.example.taczmeshloader.config.TmlClientConfig.starflowEnabled()) {
            enabled = false;
        }
        if (enabled && tex != null) {
            long tStar0 = System.nanoTime();
            float t = (float) ((System.nanoTime() / 1_000_000_000.0) % 3600.0);
            float u = (t * speedU) % 1.0f;
            float v = (t * speedV) % 1.0f;
            RenderType rt = com.example.taczmeshloader.render.MeshyRenderTypes.starflow(tex);
            // 首选 VBO：流动纹理骨占整枪 97% 顶点，即时模式每帧重吐并上传 ~900KB（实测 4~7ms/帧，
            // FPS 119→59 的主因）；走 VBO 只上传一次、之后每帧一个 draw call。
            com.example.taczmeshloader.render.MeshyRenderTypes.setStarflowOffset(u, v);
            boolean viaVbo = polyMeshModel.renderStarflowVbo(poseStack, rt, tr, tg, tb, alpha, u, v);
            if (!viaVbo) {
                // 偏移已由 setStarflowOffset 走纹理矩阵 ⇒ 顶点 UV 偏移必须传 0，否则会双重偏移
                polyMeshModel.renderStarflowOnly(poseStack, bufferSource, rt, light, overlay, tr, tg, tb, alpha, 0f, 0f);
                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    bufferSource.endBatch(rt);
                }
            }
            com.example.taczmeshloader.render.MeshPerf.add(com.example.taczmeshloader.render.MeshPerf.STAR, System.nanoTime() - tStar0);
        }
        com.example.taczmeshloader.render.MeshyRenderTypes.setStarflowOffset(0f, 0f);   // 自发光层不滚动
        long tEmis0 = System.nanoTime();
        renderEmissivePass(poseStack, bufferSource, light, overlay, skin);
        com.example.taczmeshloader.render.MeshPerf.add(com.example.taczmeshloader.render.MeshPerf.EMIS, System.nanoTime() - tEmis0);
    }

    /**
     * 自发光层：additive + 全亮叠加渲染（静态，可选呼吸）。用于还原 UE 的 Emissive_map；
     * 皮肤可用 skins[].emissive 覆盖（贴图/颜色/呼吸）。不依赖任何光影，原版渲染即有。
     */
    private void renderEmissivePass(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                    int light, int overlay, GunConfig.Skin skin) {
        if (polyMeshModel == null || !polyMeshModel.hasStarflowBones()) return;

        boolean enabled = EMISSIVE_ENABLED;
        ResourceLocation tex = toTexFile(EMISSIVE_TEXTURE);
        float tr = EMISSIVE_TINT_R, tg = EMISSIVE_TINT_G, tb = EMISSIVE_TINT_B;
        float alpha = EMISSIVE_ALPHA, pulseHz = EMISSIVE_PULSE_HZ, pulseAmt = EMISSIVE_PULSE_AMT;
        if (skin != null && skin.emissiveSet) {
            enabled = !skin.emissiveTexture.isEmpty();
            ResourceLocation sk = enabled ? parseTexFile(skin.emissiveTexture) : null;
            if (sk != null) {
                tex = sk;
                tr = skin.emissiveTintR; tg = skin.emissiveTintG; tb = skin.emissiveTintB;
                alpha = skin.emissiveAlpha; pulseHz = skin.emissivePulseHz; pulseAmt = skin.emissivePulseAmt;
            }
        }
        if (!enabled || tex == null || alpha <= 0f) return;
        if (pulseAmt > 0f && pulseHz > 0f) {
            double t = System.nanoTime() / 1_000_000_000.0;
            alpha *= (1f - pulseAmt) + pulseAmt * (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * pulseHz * t));
        }
        RenderType rt = com.example.taczmeshloader.render.MeshyRenderTypes.starflow(tex);
        polyMeshModel.renderStarflowOnly(poseStack, bufferSource, rt, light, overlay, tr, tg, tb, alpha, 0f, 0f);
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(rt);
        }
    }

    /**
     * 逐骨贴图覆盖：geo 里给骨写了 {@code "texture": "ns:path"} 的，其网格改用该贴图绘制
     * （基础贴图那一遍会跳过它，避免一个模型两种贴图互相覆盖）。
     * 同时带 {@code "glow": true} 的骨走 additive + 全亮，用于"发光件直接用发光贴图"这类部件。
     * 未声明 texture 的枪完全不进入此分支。
     */
    private void renderBoneTexturePass(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                      ResourceLocation skinTex, int light, int overlay) {
        if (polyMeshModel == null || !polyMeshModel.hasBoneOverrides()) return;
        // 逐骨贴图可以是"按皮肤"的（如发光件：原皮用 Emissive、流动材质用材质遮罩遮罩）
        String skinId = activeSkinId(lastSkinStack);
        Map<String, String> texOf = polyMeshModel.getBoneTextures(skinId);
        // 合批键 = 贴图 + 该骨的叠加层滚动偏移（滚动速度不同的发光条不能同批，否则速度互相覆盖）
        float tNow = (float) ((System.nanoTime() / 1_000_000_000.0) % 3600.0);
        Map<String, java.util.Set<String>> byKey = new LinkedHashMap<>();
        Map<String, Boolean> glowByKey = new HashMap<>();
        Map<String, ResourceLocation> texByKey = new HashMap<>();
        Map<String, float[]> uvByKey = new HashMap<>();
        Map<String, Float> alphaByKey = new HashMap<>();
        Map<String, float[]> tintByKey = new HashMap<>();
        for (Map.Entry<String, String> e : texOf.entrySet()) {
            boolean g = polyMeshModel.isGlowBone(e.getKey());
            float u = 0f, v = 0f;
            if (g) {
                float[] sp = polyMeshModel.getGlowScroll(e.getKey());
                if (sp != null) { u = (tNow * sp[0]) % 1.0f; v = (tNow * sp[1]) % 1.0f; }
            }
            float qu = Math.round(u * 1024f) / 1024f, qv = Math.round(v * 1024f) / 1024f;  // 与 MeshyRenderTypes 内量化一致
            // 叠加层强度也进合批键：同贴图不同强度的发光件不能同批，否则强度互相覆盖
            float ga = g ? polyMeshModel.getGlowAlpha(e.getKey()) : 1f;
            float qa = Math.round(ga * 100f) / 100f;
            float[] gt = g ? polyMeshModel.getGlowTint(e.getKey(), skinId) : null;
            String tintKey = gt == null ? "w" : (Math.round(gt[0] * 100f) + "," + Math.round(gt[1] * 100f) + "," + Math.round(gt[2] * 100f));
            String key = e.getValue() + "|" + qu + "|" + qv + "|" + qa + "|" + tintKey;
            byKey.computeIfAbsent(key, k -> new java.util.LinkedHashSet<>()).add(e.getKey());
            glowByKey.merge(key, g, (a, b) -> a || b);
            ResourceLocation rl = parseTexFile(e.getValue());   // 逐骨贴图同样要转资源路径
            if (rl != null) texByKey.put(key, rl);
            uvByKey.put(key, new float[]{qu, qv});
            alphaByKey.put(key, qa);
            tintByKey.put(key, gt);
        }
        for (Map.Entry<String, java.util.Set<String>> e : byKey.entrySet()) {
            ResourceLocation tex = texByKey.get(e.getKey());
            if (tex == null) continue;
            boolean glow = Boolean.TRUE.equals(glowByKey.get(e.getKey()));
            float[] uvOff = uvByKey.get(e.getKey());
            if (glow) {
                // 发光件：**只画自己的贴图**（geo 的 "texture"，如蓝色海洋底图），全亮 + 该骨的 UV 滚动。
                // · 不用枪械原图当底色（用户 2026-09-13 明确要求）；
                // · 不再叠那层"几乎全亮"的遮罩（349 的 M 图 52% 像素 >200 ⇒ 会把本体糊成一片没有细节的蓝雾）；
                // · 滚动直接作用在本体上（花纹滑动才是肉眼看得见的流光）。
                RenderType rt = (uvOff[0] != 0f || uvOff[1] != 0f)
                        ? com.example.taczmeshloader.render.MeshyRenderTypes.flowOpaque(tex, uvOff[0], uvOff[1])
                        : RenderType.entityCutoutNoCull(tex);
                float gr = 1f, gg = 1f, gb = 1f;
                float[] gt = tintByKey.get(e.getKey());
                if (gt != null) { gr = gt[0]; gg = gt[1]; gb = gt[2]; }
                polyMeshModel.renderBonesSubset(poseStack, bufferSource, rt, e.getValue(),
                        15728880, overlay, gr, gg, gb, 1f);
                if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                    bufferSource.endBatch(rt);
                }
                continue;   // 发光件只有这一遍
            }
            // 叠加那层：遮罩（M）只给一个"反射/蓝调"的感觉，强度按 geo 配（低），细节交给本体
            RenderType rt = glow
                    ? com.example.taczmeshloader.render.MeshyRenderTypes.starflow(tex, uvOff[0], uvOff[1])
                    : RenderType.entityCutoutNoCull(tex);
            float[] gt = glow ? tintByKey.get(e.getKey()) : null;
            polyMeshModel.renderBonesSubset(poseStack, bufferSource, rt, e.getValue(),
                    glow ? 15728880 : light, overlay,
                    gt == null ? 1f : gt[0], gt == null ? 1f : gt[1], gt == null ? 1f : gt[2],
                    glow ? alphaByKey.getOrDefault(e.getKey(), 1f) : 1f);
            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(rt);
            }
        }
    }

    /** 本模型是从哪份 geo 载入的（由 GunDisplayInstanceMixin 在载入时写入）。
     *  开火特效的归属校验用它按**模型身份**判定，而不是看 TaCZ 递进来的物品栈——
     *  否则"某个界面用别的栈渲染了我们的模型"就能骗过校验（2026-09-14 用户反馈的串用即此类）。 */
    private volatile ResourceLocation sourceGeo;

    public void setSourceGeo(ResourceLocation geo) { this.sourceGeo = geo; }

    public ResourceLocation sourceGeo() { return sourceGeo; }

    /** 当前渲染的枪物品栈（皮肤解析用；render 每帧写入）。 */
    private ItemStack lastSkinStack = ItemStack.EMPTY;

    /** 本帧生效的枪皮贴图（含皮肤覆盖）；发光件本体用它，保证发光条不会变成一块黑洞。 */
    private ResourceLocation lastSkinTex;

    /** 诊断用：上次打印过的皮肤 id，变化时打一条。 */
    private String lastSkinLogged = "\0";

    /** 皮肤 id 列表（按 geo 里的声明顺序，供键位循环/换皮卡使用）。 */
    public java.util.List<String> getSkinIds() {
        return new java.util.ArrayList<>(skinsById.keySet());
    }

    /** 皮肤 NBT 键名（geo extras 可配）。 */
    public String getSkinNbtKey() {
        return skinNbtKey;
    }

    /**
     * 当前皮肤 id：枪 NBT 里选的；没选/查不到 → 皮肤表的第一项（约定：表里第一个=默认皮肤）。
     * 用于"替换件/逐皮肤贴图"这类需要"当前是哪套皮肤"的判定（外观本身仍走 activeSkin）。
     */
    private String activeSkinId(ItemStack stack) {
        if (skinsById.isEmpty()) return "";
        GunConfig.Skin sk = activeSkin(stack);
        if (sk != null) return sk.id;
        for (String id : skinsById.keySet()) return id;   // 声明顺序里的第一项
        return "";
    }

    /** 读枪 NBT 里的皮肤选择；没有/表里查不到 → null（用默认外观）。 */
    private GunConfig.Skin activeSkin(ItemStack stack) {
        if (skinsById.isEmpty() || stack == null || stack.isEmpty()) return null;
        net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(skinNbtKey)) return null;
        return skinsById.get(tag.getString(skinNbtKey));
    }

    private void renderPolyMeshNormal(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                      ResourceLocation tex, int light, int overlay, boolean useVBO) {
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
            bufferSource.endBatch(RenderType.entityCutout(tex));
        }
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                    bufferSource.endBatch(RenderType.entityTranslucentCull(tex));
                } else {
                    com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(tex);
                }
            }
        }
        renderStarflowPass(poseStack, bufferSource, light, overlay);
    }

    public void loadPolyMesh(ResourceLocation modelLocation) {
        try {
            resetGunConfig();
            if (this.polyMeshModel != null) {
                this.polyMeshModel.close();
            }

            var resource = Minecraft.getInstance().getResourceManager()
                    .getResource(modelLocation).orElseThrow();

            try (var reader = new InputStreamReader(resource.open(), java.nio.charset.StandardCharsets.UTF_8)) {
                JsonObject rawJson = JsonParser.parseReader(reader).getAsJsonObject();

                IPolyMeshBone adaptedRoot = new IPolyMeshBone() {
                    @Override public String getName()    { return "meshy_dummy_root"; }
                    @Override public float getPivotX()   { return 0; }
                    @Override public float getPivotY()   { return 0; }
                    @Override public float getPivotZ()   { return 0; }
                    @Override public float getRotX()     { return 0; }
                    @Override public float getRotY()     { return 0; }
                    @Override public float getRotZ()     { return 0; }
                    @Override public boolean isVisible() { return true; }
                    @Override public void applyTransform(PoseStack ps) {}
                    @Override
                    public List<? extends IPolyMeshBone> getChildren() {
                        if (cachedRootChildren != null) return cachedRootChildren;
                        cachedRootChildren = getShouldRender().stream()
                                .map(TaczPartAdapter::new).collect(Collectors.toList());
                        return cachedRootChildren;
                    }
                };

                this.polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson);
                this.staticScales = new HashMap<>(this.polyMeshModel.getStaticScales());
                applyStaticScales();

                // cubes の消去は一切行わない（クラス Javadoc 参照）

                this.cachedTexture = null;
                this.cachedRootChildren = null;

                com.example.taczmeshloader.render.ShaderStateTracker.register(this.polyMeshModel);
                cachedHasMagMesh = this.polyMeshModel.hasMeshInSubtree(GunModelConstant.MAG_NORMAL_NODE);
                cachedHasAdditionalMagMesh = this.polyMeshModel.hasMeshInSubtree(GunModelConstant.MAG_ADDITIONAL_NODE);

                // loadPolyMesh 後に additional_magazine の FunctionalRenderer を再セットアップする。
                // BedrockGunModel のコンストラクタで setFunctionalRenderer が呼ばれた時点では
                // cachedHasMagMesh / cachedHasAdditionalMagMesh がまだ false のため、
                // PolyMesh 描画のフックが適用されていない。
                // poly_mesh が確定した今のタイミングで改めてフックを適用する。
                if (cachedHasMagMesh || cachedHasAdditionalMagMesh) {
                    applyAdditionalMagazineMeshHook();
                }

                MESH_LOG.info("[MeshyLoader] Loaded poly_mesh from: {}", modelLocation);
                applyGunConfig(com.example.taczmeshloader.tacz.GunConfig.fromGeo(rawJson));
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    /**
     * additional_magazine ボーンの FunctionalRenderer に poly_mesh 描画フックを適用する。
     *
     * <p>このメソッドは loadPolyMesh() の末尾から呼ばれる。
     * BedrockGunModel のコンストラクタで設定された FunctionalRenderer の上に、
     * PolyMesh の magazine サブツリー描画を追加でラップする。</p>
     *
     * <h3>描画戦略</h3>
     * <ul>
     *   <li>TacZ オリジナルの FunctionalRenderer（キューブ描画 + visible 制御）を先に実行</li>
     *   <li>additional_magazine の visible が true のとき（アニメーション中）のみ、
     *       続けて poly_mesh の magazine サブツリーを同じ VertexConsumer に書き込む</li>
     *   <li>additional_magazine サブツリー自体に poly_mesh がある場合はそちらも描画</li>
     * </ul>
     */
    private void applyAdditionalMagazineMeshHook() {
        com.tacz.guns.client.model.bedrock.ModelRendererWrapper wrapper =
                modelMap.get(GunModelConstant.MAG_ADDITIONAL_NODE);
        if (wrapper == null) return;

        com.tacz.guns.client.model.bedrock.BedrockPart part = wrapper.getModelRenderer();
        if (!(part instanceof com.tacz.guns.client.model.FunctionalBedrockPart functionalPart)) return;

        // 現在セットされている FunctionalRenderer を取得しておく
        // （BedrockGunModel のコンストラクタが設定した renderAdditionalMagazine ラムダ）
        java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                IFunctionalRenderer> existingFunction = functionalPart.functionalRenderer;

        functionalPart.functionalRenderer = (bp) -> {
            // 既存のレンダラー（TacZ オリジナル: キューブ描画）を取得
            IFunctionalRenderer originalRenderer = (existingFunction != null) ? existingFunction.apply(bp) : null;

            return (poseStack, vertexBuffer, transformType, light, overlay) -> {
                // 1. TacZ オリジナル処理（additional_magazine + magazine キューブ描画）
                if (originalRenderer != null) {
                    originalRenderer.render(poseStack, vertexBuffer, transformType, light, overlay);
                }

                // 2. additional_magazine の visible が true のときのみ poly_mesh を描画する。
                //    visible は ModelAdditionalMagazineListener によってアニメーション再生中に
                //    true にセットされる。false のときは描画しない（TacZ と同じ挙動）。
                if (!bp.visible) return;

                if (hasPolyMesh()) {
                    // 2a. magazine（MAG_NORMAL_NODE）サブツリーの poly_mesh を描画。
                    //     TacZ オリジナルが magazine キューブを複製して描画するのと同様に、
                    //     PolyMesh 側も magazine メッシュを additional_magazine の座標に描画する。
                    if (cachedHasMagMesh) {
                        polyMeshModel.renderSubtreeDirect(
                                GunModelConstant.MAG_NORMAL_NODE, poseStack, vertexBuffer, light, overlay);
                    }
                    // 2b. additional_magazine サブツリー自体の poly_mesh を描画。
                    if (cachedHasAdditionalMagMesh) {
                        polyMeshModel.renderSubtreeDirect(
                                GunModelConstant.MAG_ADDITIONAL_NODE, poseStack, vertexBuffer, light, overlay);
                    }
                }
            };
        };
    }

    /**
     * BedrockGunModel のコンストラクタが MAG_ADDITIONAL_NODE に対して
     * setFunctionalRenderer を呼んだタイミングでは、まだ loadPolyMesh() が
     * 実行されていないため cachedHasMagMesh が false になっている。
     * そのため、ここでは super を呼ぶだけにとどめ、実際のフック適用は
     * loadPolyMesh() 末尾の applyAdditionalMagazineMeshHook() に委ねる。
     */
    @Override
    public void setFunctionalRenderer(String node,
                                      java.util.function.Function<com.tacz.guns.client.model.bedrock.BedrockPart,
                                              IFunctionalRenderer> function) {
        super.setFunctionalRenderer(node, function);
        // loadPolyMesh() 後に再度呼ばれた場合（外部から上書き）は何もしない。
        // フック適用は loadPolyMesh() → applyAdditionalMagazineMeshHook() が担う。
    }

    /**
     * アニメーション用リスナーのサプライ。
     * BedrockGunModel の実装を継承しつつ、additional_magazine ノードに対して
     * {@link MeshAdditionalMagazineListener} を返すことで、
     * poly_mesh モデルの additional_magazine サブツリーの visible も
     * 同時に制御する。
     */
    @Override
    public AnimationListener supplyListeners(String nodeName, ObjectAnimationChannel.ChannelType type) {
        AnimationListener listener = super.supplyListeners(nodeName, type);
        if (listener == null) return null;

        if (GunModelConstant.MAG_ADDITIONAL_NODE.equals(nodeName) && hasPolyMesh()
                && (cachedHasMagMesh || cachedHasAdditionalMagMesh)) {
            // BedrockGunModel.supplyListeners は MAG_ADDITIONAL_NODE に対して
            // すでに ModelAdditionalMagazineListener を返している（BedrockPart.visible を true にする）。
            // ここではさらにそれをラップして、PolyMeshModel 側の除外制御も連動させる。
            return new MeshAdditionalMagazineListener(listener, this);
        }
        return listener;
    }

    /**
     * アニメーションリセット時に additional_magazine poly_mesh の除外設定も
     * cleanAnimationTransform に合わせてリセットする。
     */
    @Override
    public void cleanAnimationTransform() {
        super.cleanAnimationTransform();
        // 动画结束后把带静态 scale 的 poly 骨恢复默认（例: 导弹收起为 0）
        applyStaticScales();
        // super.cleanAnimationTransform() が additionalMagazineNode.visible = false にする。
        // PolyMeshModel 側の除外設定は render() の冒頭で毎フレーム再設定するため、
        // ここで明示的にリセットする必要はない。
    }

    private void applyStaticScales() {
        if (staticScales.isEmpty()) return;
        for (Map.Entry<String, float[]> entry : staticScales.entrySet()) {
            ModelRendererWrapper wrapper = modelMap.get(entry.getKey());
            if (wrapper == null) continue;
            BedrockPart part = wrapper.getModelRenderer();
            if (part == null) continue;
            float[] s = entry.getValue();
            part.xScale = s[0];
            part.yScale = s[1];
            part.zScale = s[2];
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null; }

    /** LODモデル用テクスチャを固定する。checkLod介入時に呼ぶ。 */
    public void setOverrideTexture(ResourceLocation texture) {
        this.overrideTexture = texture;
        this.cachedTexture = null;
    }

    public static void register() {
        com.tacz.guns.api.client.other.GunModelTypeManager.registerModelType(
                "mesh", TaczPolyMeshGunModel::new);
        MESH_LOG.info("[TacZMeshLoader] Registered TacZ model type: meshy");
    }

    // =========================================================================
    // 内部クラス
    // =========================================================================

    /**
     * additional_magazine アニメーションリスナーの PolyMesh 対応版。
     *
     * <p>BedrockGunModel の {@link ModelAdditionalMagazineListener} は
     * {@code BedrockPart.visible = true} にするだけだが、このリスナーは
     * {@link PolyMeshModel#clearExcludeSubtree()} を追加で呼ぶことで、
     * render() 冒頭で設定した除外を解除し、FunctionalRenderer 経由での
     * poly_mesh 描画が正しく動くようにする。</p>
     *
     * <p>実際の poly_mesh 描画は {@link #applyAdditionalMagazineMeshHook()} が
     * セットした FunctionalRenderer 内で行うため、ここでは除外制御のみ担当する。</p>
     */
    private static class MeshAdditionalMagazineListener implements AnimationListener {
        private final AnimationListener delegate;
        private final TaczPolyMeshGunModel model;

        MeshAdditionalMagazineListener(AnimationListener delegate, TaczPolyMeshGunModel model) {
            this.delegate = delegate;
            this.model = model;
        }

        @Override
        public void update(float[] values, boolean blend) {
            delegate.update(values, blend);
            // additional_magazine アニメーション再生中は polyMeshModel の
            // additional_magazine サブツリー除外を解除する。
            // これにより FunctionalRenderer 内の renderSubtreeDirect が機能する。
            if (model.polyMeshModel != null) {
                model.polyMeshModel.clearExcludeSubtree();
            }
        }

        @Override
        public float[] initialValue() { return delegate.initialValue(); }

        @Override
        public ObjectAnimationChannel.ChannelType getType() { return delegate.getType(); }
    }

    /**
     * BedrockPart（TacZ ボーン）を {@link IPolyMeshBone} に適合させるアダプタ。
     */
    private static class TaczPartAdapter implements IPolyMeshBone {
        private final BedrockPart part;
        private List<IPolyMeshBone> cachedChildren;
        TaczPartAdapter(BedrockPart part) { this.part = part; }
        @Override public String getName()        { return part.name == null ? "" : part.name; }
        @Override public float getPivotX()       { return part.x; }
        @Override public float getPivotY()       { return part.y; }
        @Override public float getPivotZ()       { return part.z; }
        @Override public float getRotX()         { return part.xRot; }
        @Override public float getRotY()         { return part.yRot; }
        @Override public float getRotZ()         { return part.zRot; }
        @Override public float getScaleX()       { return part.xScale == 0 ? 1f : part.xScale; }
        @Override public float getScaleY()       { return part.yScale == 0 ? 1f : part.yScale; }
        @Override public float getScaleZ()       { return part.zScale == 0 ? 1f : part.zScale; }
        @Override public boolean isVisible()     { return part.visible; }
        @Override public boolean isIlluminated() { return part.illuminated; }
        @Override
        public List<? extends IPolyMeshBone> getChildren() {
            if (cachedChildren != null) return cachedChildren;
            cachedChildren = new ArrayList<>();
            if (part.children != null) {
                for (BedrockPart c : part.children) cachedChildren.add(new TaczPartAdapter(c));
            }
            return cachedChildren;
        }
        @Override public void applyTransform(PoseStack ps) { part.translateAndRotateAndScale(ps); }
    }
}
