package com.example.taczmeshloader.render;

import com.example.taczmeshloader.tacz.GunConfig;
import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * UE(Cascade) 粒子特效的 TML 子集运行时 —— 自绘带贴图的公告板四边面。
 *
 * <p>为什么不用原版粒子：原版只有「点精灵 + 重力」，没有尺寸/颜色随寿命曲线、没有
 * additive 带电贴图、没有按骨挂载，这正是"MC 达不到要求"的原因。这里用
 * {@link MeshyRenderTypes#fxSprite}（全亮 + 可选加法混合 + 不写深度）把 UE 精灵发射器的
 * 核心观感搬过来；参数全部来自每把枪 geo 顶层 {@code extras.fx.emitters}
 * （{@link GunConfig.FxEmitter}），代码里**零枪名、零骨名、零贴图名硬编码**——
 * 没配置的枪完全不进入任何分支。</p>
 *
 * <p>位置与画面严格同点：开火瞬间由 TaCZ 的 muzzle flash 钩子触发，取
 * {@link TaczPolyMeshGunModel#fxBoneWorld}（渲染瞬间捕获的骨矩阵 + 相机/FOV 换算）。</p>
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FxParticles {

    private FxParticles() {}

    /** 粒子总数上限（性能护栏；超出时丢最旧的）。 */
    private static final int MAX_PARTICLES = 900;
    private static final int MAX_MESH_VERTICES = 32768;
    /** Two source Core meshes submit 57600 vertices; do not consume the gun/inspect budget. */
    private static final int MAX_HELD_MESH_VERTICES = 65536;
    /** 全亮光照（UE 里这些是 Unlit 加法特效，暗处不该变黑）。 */
    private static final int FULL_BRIGHT = 0xF000F0;

    private static final class P {
        double x, y, z;
        double vx, vy, vz;
        Vector3f sourceVelocity;
        Vector3f localOffset;
        float accelX, accelY, accelZ;
        int cascade = -1, randomFrame;
        double born;
        Anchor anchor;
        RenderType renderType;
        float age, life;     // 秒（life 在 lifeMin..lifeMax 间随机）
        float size;          // MC 像素（边长，尺寸曲线的基准值）
        float sizeY = -1f;
        float rot, spin;     // 滚转（弧度、弧度/秒）
        float aspect;        // 沿自身轴的拉伸倍率（1=方片）
        boolean hideAds;     // 开镜时不画（粒子生成时从 emitter 记下）
        boolean onlyAds;     // 只在开镜时画
        boolean beam;        // 光带（沿 ax,ay,az 拉出）
        double ax, ay, az;   // beam 的轴向（发射瞬间取玩家视线方向）
        GunConfig.FxEmitter em;
        Matrix4f meshPose, meshRotation;
        float[] meshScale;
        float colorR = 1f, colorG = 1f, colorB = 1f, seed, alphaSeed;
    }

    private static final List<P> PARTS = new ArrayList<>();
    private record Emission(GunConfig.FxEmitter emitter, FxEmissionSchedule schedule, double emitterStart) {}
    private static final List<Emission> EMISSIONS = new ArrayList<>();
    private static float previousInspect = -1f;
    private static boolean ownerHasInspect, ownerHasHeld;
    private static final java.util.IdentityHashMap<GunConfig.FxEmitter, FxLoopingEmissionSchedule> HELD = new java.util.IdentityHashMap<>();
    private static final class Anchor {
        long frame = -1;
        boolean valid;
        final Vector3f offset = new Vector3f();
        final Matrix4f meshPose = new Matrix4f();
    }

    /** Identity keys distinguish emitters sharing the same texture/bone. */
    private static final Map<GunConfig.FxEmitter, Float> ALWAYS_ACC = new IdentityHashMap<>();
    private static final Map<GunConfig.FxEmitter, Anchor> ANCHORS = new IdentityHashMap<>();
    private static final FxClock CLOCK = new FxClock();
    private static FxParticleSource ownerModel;
    private static final FxOwnerEpoch OWNER_EPOCH = new FxOwnerEpoch();
    private static final FxHandFrameLatch HAND_FRAME = new FxHandFrameLatch();
    private static FxParticleSource meleeSource;
    private static final Map<GunConfig.FxEmitter, FxAnimationCursor> ANIMATION_CURSORS = new IdentityHashMap<>();
    private static List<GunConfig.FxEmitter> ownerEmitters;
    private static long frame;
    private static final Matrix4f ANCHOR_POSE = new Matrix4f();
    private static final Matrix4f LEVEL_VIEW_POSE = new Matrix4f();
    private static final Matrix4f LEVEL_MODEL_VIEW = new Matrix4f(), LEVEL_PROJECTION = new Matrix4f();
    private static long levelViewFrame = -1;

    /** Copy only this frame's world pass; used by opt-in held projection matching. */
    public static boolean copyLevelProjection(Matrix4f projection, Matrix4f view) {
        if (levelViewFrame < 0 || levelViewFrame != BloomPostProcessor.currentFrameId()) return false;
        projection.set(LEVEL_PROJECTION);
        view.set(LEVEL_MODEL_VIEW).mul(LEVEL_VIEW_POSE);
        return projection.isFinite() && view.isFinite();
    }
    private static final Matrix4f SAVED_MODEL_VIEW = new Matrix4f(), SAVED_PROJECTION = new Matrix4f();
    private static VertexSorting levelSorting;
    private static final java.util.Set<String> REPORTED_PROJECTION = new java.util.HashSet<>();
    private static boolean levelViewCaptured;
    private static boolean afterHandReady;
    private static final Vector3f RIGHT = new Vector3f();
    private static final Vector3f UP = new Vector3f();
    private static final Matrix4f MESH_TRANSFORM = new Matrix4f();
    private static final Vector3f MESH_VERTEX = new Vector3f();
    // FxMesh accepts at most 32768 source vertices. Render-thread scratch, reused per particle.
    private static final float[] MESH_POSITIONS = new float[32768 * 3];
    private static final Matrix3f MESH_NORMAL_MATRIX = new Matrix3f();
    private static final Matrix3f MESH_EVENT_NORMAL_MATRIX = new Matrix3f();
    private static final Vector3f MESH_NORMAL = new Vector3f();
    /** Reused only during one particle's synchronous vertex emission on the render thread. */
    private static float cascadeRed, cascadeGreen, cascadeBlue, cascadeAlpha;
    private static float cascadeNormalX, cascadeNormalY, cascadeNormalZ;
    private static int cascadeAge, cascadePacket, cascadeMix, cascadeSeed;
    private static final Vector3f DISPLACEMENT = new Vector3f();
    private static final Vector3f LOCATION = new Vector3f();
    private static final Vector3f MOTION = new Vector3f();
    private static final Vector3f STREAK_RIGHT = new Vector3f(), STREAK_UP = new Vector3f();
    private static TaczPolyMeshGunModel checkedModel;
    private static List<GunConfig.FxEmitter> checkedEmitters;
    private static Object checkedLevel, checkedItem;
    private static String checkedGun = "", checkedDisplay = "";
    private static boolean checkedOwner;
    private static long diagSourceCalls, diagOwnerReject, diagLocalReject, diagNotReady, diagStarts,
            diagSpawnAttempts, diagSpawnAccepted, diagNullPosition, diagClears, diagDrawCalls,
            diagDrawNotReady, diagDrawNoParts, diagDrawSubmitted, diagCaptureOk, diagCaptureFail;
    private static long diagLastLog;
    private static boolean diagActive;
    private static int slowDrawReports;

    private static void traceMelee(String phase, FxParticleSource source) {
        if (!diagActive || source == null || !source.deferEmissionUntilHand()) return;
        long ns = System.nanoTime();
        if (ns - diagLastLog < 2_000_000_000L) return;
        diagLastLog = ns;
        Minecraft mc = Minecraft.getInstance();
        org.apache.logging.log4j.LogManager.getLogger("MeshyLoader").info(
                "[MeleeFxTrace] phase={} ownerSame={} local={} ready={} paused={} configured={} queued={} live={} cursors={} sourceCalls={} ownerReject={} localReject={} sourceNotReady={} starts={} attempts={} accepted={} nullPosition={} clears={} drawCalls={} drawNotReady={} drawNoParts={} submitted={} captureOk={} captureFail={} detail={}",
                phase, ownerModel == source, localOwner(mc, source), afterHandReady, mc.isPaused(),
                source.activeFxEmitters().size(), EMISSIONS.size(), PARTS.size(), ANIMATION_CURSORS.size(),
                diagSourceCalls, diagOwnerReject, diagLocalReject, diagNotReady, diagStarts,
                diagSpawnAttempts, diagSpawnAccepted, diagNullPosition, diagClears, diagDrawCalls,
                diagDrawNotReady, diagDrawNoParts, diagDrawSubmitted, diagCaptureOk, diagCaptureFail, source.diagnosticDetails(mc));
    }

    private static void clear() {
        diagClears++;
        PARTS.clear();
        ANIMATION_CURSORS.clear();
        EMISSIONS.clear();
        previousInspect = -1f;
        ownerHasInspect = false;
        ownerHasHeld = false;
        HELD.clear();
        FxHeldDepth.invalidate();
        ALWAYS_ACC.clear();
        ANCHORS.clear();
        TEX_CACHE.clear();
        ownerModel = null;
        OWNER_EPOCH.clear();
        ownerEmitters = null;
        CLOCK.reset();
    }

    /** Only LRT first-person rendering registers this optional source. */
    public static void registerMelee(FxParticleSource source) { meleeSource = source; }

    /** LRT notifications spawn only after this frame's animated hand matrices were captured. */
    public static void onSourceRendered(FxParticleSource source) {
        diagSourceCalls++;
        Minecraft mc = Minecraft.getInstance();
        if (ownerModel != source) diagOwnerReject++;
        if (!localOwner(mc, source)) diagLocalReject++;
        if (!afterHandReady) diagNotReady++;
        if (source.deferEmissionUntilHand() && localOwner(mc, source)) {
            // Oculus renders hands inside LevelRenderer BEFORE AFTER_LEVEL; vanilla does it after.
            // Pose validity belongs to the render frame, not to whether scene depth is ready yet.
            HAND_FRAME.capture(BloomPostProcessor.currentFrameId(), mc.level, source,
                    source.activeFxEmitters(), source.ownershipToken());
        }
        traceMelee("hand", source);
    }

    private static FxParticleSource currentSource(Minecraft mc) {
        if (meleeSource != null && meleeSource.validLocalOwner(mc)) return meleeSource;
        return TaczPolyMeshGunModel.lastLocalFirstPersonModel();
    }

    private static boolean localOwner(Minecraft mc, FxParticleSource source) {
        if (source instanceof TaczPolyMeshGunModel gun) return localOwner(mc, gun);
        return FxDiagnostics.particlesEnabled() && mc.level != null && mc.player != null
                && mc.player.isAlive() && mc.getCameraEntity() == mc.player
                && mc.options.getCameraType().isFirstPerson() && source != null
                && source.validLocalOwner(mc) && !source.activeFxEmitters().isEmpty();
    }

    private static boolean localOwner(Minecraft mc, TaczPolyMeshGunModel model) {
        if (!FxDiagnostics.particlesEnabled() || mc.level == null || mc.player == null
                || !mc.player.isAlive() || mc.getCameraEntity() != mc.player
                || !mc.options.getCameraType().isFirstPerson() || model == null || !model.hasFxParticles()
                || model.sourceGeo() == null
                || !ItemStack.isSameItem(model.lastRenderStack(), mc.player.getMainHandItem())) {
            checkedModel = null;
            checkedEmitters = null;
            checkedLevel = null;
            checkedItem = null;
            checkedOwner = false;
            return false;
        }
        ItemStack held = mc.player.getMainHandItem();
        var tag = held.getTag();
        // TaCZ 1.1.8 GunItemDataAccessor identity fields, without allocating ResourceLocations per frame.
        String gun = tag == null ? "" : tag.getString(com.tacz.guns.api.item.nbt.GunItemDataAccessor.GUN_ID_TAG);
        String display = tag == null ? "" : tag.getString(com.tacz.guns.api.item.nbt.GunItemDataAccessor.GUN_DISPLAY_ID_TAG);
        if (checkedModel != model || checkedEmitters != model.activeFxEmitters() || checkedLevel != mc.level
                || checkedItem != held.getItem() || !checkedGun.equals(gun) || !checkedDisplay.equals(display)) {
            checkedModel = model;
            checkedEmitters = model.activeFxEmitters();
            checkedLevel = mc.level;
            checkedItem = held.getItem();
            checkedGun = gun;
            checkedDisplay = display;
            checkedOwner = com.example.taczmeshloader.client.GunSkinCatalog.modelBelongsTo(model.sourceGeo(), held);
            // Same model may serve two display IDs. Do not retain the previous gun's particles.
            clear();
        }
        return checkedOwner;
    }

    private static void syncOwner(Minecraft mc, FxParticleSource model) {
        if (!OWNER_EPOCH.matches(mc.level, model, model.activeFxEmitters(), model.ownershipToken())) {
            clear();
            ownerModel = model;
            ownerEmitters = model.activeFxEmitters();
            OWNER_EPOCH.bind(mc.level, model, ownerEmitters, model.ownershipToken());
            for (GunConfig.FxEmitter em : ownerEmitters) {
                if (em.enabled && "inspect".equals(em.trigger)) ownerHasInspect = true;
                if (em.enabled && "held".equals(em.trigger)) ownerHasHeld = true;
            }
        }
    }

    private static double now(Minecraft mc) {
        return CLOCK.sample(System.nanoTime(), mc.isPaused());
    }
    /** extras 贴图 id → 纹理文件路径（含 IDisplay.converter 规则）。 */
    private static final Map<String, ResourceLocation> TEX_CACHE = new HashMap<>();

    // ------------------------------------------------------------------ 开火触发

    /** 诊断用：前 N 次开火各分支打一条日志（定位"特效不出现/位置不对"卡在哪一步）。 */
    private static final java.util.concurrent.atomic.AtomicInteger DBG_BUDGET =
            new java.util.concurrent.atomic.AtomicInteger(12);

    private static void dbg(String msg) {
        if (DBG_BUDGET.getAndDecrement() > 0) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader").info("[FxPartDbg] {}", msg);
        }
    }

    /**
     * 一次性归属诊断（限量 40 条）：把"手持哪把枪 / 登记的是谁的模型 / 这把枪的动画到底加载没有 /
     * 当前在播哪条动画轨"打成一行，标签 {@code [FxOwner]}。用来同时定位两类问题：
     * ①手持 B 枪却放出 A 枪特效（串用）；②某把枪"动画丢失"（动画未加载 ⇒ 只剩默认姿势）。
     */
    private static final java.util.concurrent.atomic.AtomicInteger OWNER_LOG =
            new java.util.concurrent.atomic.AtomicInteger(40);

    private static void logOwnerOnce(Minecraft mc, TaczPolyMeshGunModel model,
                                     ResourceLocation modelGeo, String verdict) {
        if (OWNER_LOG.getAndDecrement() <= 0) return;
        String gunId = "-", disp = "-", modelPath = "-", animId = "-", animLoaded = "-", tracks = "-";
        try {
            ItemStack st = mc.player.getMainHandItem();
            var gun = com.tacz.guns.api.item.IGun.getIGunOrNull(st);
            if (gun != null) {
                gunId = String.valueOf(gun.getGunId(st));
                disp = String.valueOf(gun.getGunDisplayId(st));
            }
            var inst = com.tacz.guns.api.TimelessAPI.getGunDisplay(st).orElse(null);
            // 展示 POJO 才有 model/animation 字段（实例上只有状态机/贴图）；实例的 displayId 最准，优先用它
            ResourceLocation realDisp = null;
            if (inst instanceof com.example.taczmeshloader.client.GunDisplayIdAccess acc) {
                realDisp = acc.taczmeshloader$displayId();
            }
            if (realDisp == null && gun != null) realDisp = gun.getGunDisplayId(st);
            var pojo = realDisp == null ? null : com.tacz.guns.client.resource
                    .ClientAssetsManager.INSTANCE.getGunDisplay(realDisp);
            if (pojo != null) {
                modelPath = String.valueOf(pojo.getModelLocation());
                ResourceLocation anim = pojo.getAnimationLocation();
                animId = String.valueOf(anim);
                animLoaded = String.valueOf(anim != null && com.tacz.guns.client.resource
                        .ClientAssetsManager.INSTANCE.getBedrockAnimations(anim) != null);
            }
            if (inst != null) {
                var sm = inst.getAnimationStateMachine();
                if (sm != null) {
                    StringBuilder sb = new StringBuilder();
                    for (int tk = 0; tk < 16; tk++) {
                        var r = sm.getAnimationController().getAnimation(tk);
                        if (r == null) continue;
                        var tr = r.getTransitionTo();
                        if (tr != null) r = tr;
                        if (sb.length() > 0) sb.append(',');
                        sb.append(r.getAnimation().name).append('@')
                          .append(String.format(java.util.Locale.ROOT, "%.2f", r.getProgressNs() / 1e9f));
                    }
                    tracks = sb.length() == 0 ? "(空)" : sb.toString();
                }
            }
        } catch (Throwable err) {
            tracks = "读取失败:" + err;
        }
        org.apache.logging.log4j.LogManager.getLogger("MeshyLoader").info(
                "[FxOwner] {} ｜ 手持 gunId={} displayId={} model={} ｜ 该模型 geo={} emitters={} ｜ 动画={} 已加载={} ｜ 在播=[{}]",
                verdict, gunId, disp, modelPath, modelGeo, model.activeFxEmitters().size(),
                animId, animLoaded, tracks);
    }

    /** 由 mixin（TaCZ MuzzleFlashRender.onShoot）在本地玩家开火瞬间调用。 */
    public static void onShot() {
        if (!FxDiagnostics.particlesEnabled()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        TaczPolyMeshGunModel model = TaczPolyMeshGunModel.lastLocalFirstPersonModel();
        if (model == null) {
            dbg("abort: 没有登记第一人称 mesh 模型");
            return;
        }
        if (!model.hasFxParticles()) {
            dbg("abort: 这把枪的 geo extras 里没有 fx.emitters");
            return;
        }
        // 换枪保护：静态登记的是"最后渲染过带粒子特效的枪"，不加校验会"别枪也放特效"。
        if (!ItemStack.isSameItem(model.lastRenderStack(), mc.player.getMainHandItem())) {
            dbg("abort: 登记模型不是当前手持的枪");
            return;
        }
        // ★ 归属校验（第二道，按**模型身份**）：登记模型自己是从哪份 geo 载入的，必须就等于"手持这把枪"的 geo。
        //   只比物品栈挡不住"拿别的栈渲染了我们的模型"的场合（例如 gunsmith 的改装界面 3D 预览）——
        //   那会让第一道校验通过，于是手持 B 枪却撒出 A 枪的特效（用户 2026-09-14 反馈的"串用"）。
        //   ★ 判定为 fail-closed（判不出属于手持这把枪就拦）——玩家实测：手持【别的资源配置的枪】照样会撒出
        //     关联特效的蓝色电弧（2026-09-14 两张截图实证），就是因为旧逻辑"geo 解析不到就放行"。
        ResourceLocation modelGeo = model.sourceGeo();
        if (!com.example.taczmeshloader.client.GunSkinCatalog
                .modelBelongsTo(modelGeo, mc.player.getMainHandItem())) {
            dbg("abort: 模型归属不符 模型geo=" + modelGeo);
            logOwnerOnce(mc, model, modelGeo, "拦下(归属不符)");   // 被拦也要留现场，方便玩家反馈定位
            return;
        }
        if (!localOwner(mc, model)) { clear(); return; }
        syncOwner(mc, model);
        double time = now(mc);
        logOwnerOnce(mc, model, modelGeo, "放行");

        for (GunConfig.FxEmitter em : model.activeFxEmitters()) {
            if (!em.enabled || !"shot".equals(em.trigger)) continue;
            if (em.hideWhenAiming && model.isShooterAiming(mc)) continue;   // 开镜：不出这组
            if (em.onlyWhenAiming && !model.isShooterAiming(mc)) continue;  // 只开镜：腰射不出这组
            Vec3 pos = model.fxBoneWorld(em.bone, em.offX, em.offY, em.offZ, em.boneSpace, mc);
            if (pos == null) {
                dbg("emitter=" + em.name + " 骨矩阵未捕获 bone=" + em.bone);
                continue;
            }
            beginEmission(em, time);
            dbg("emitter=" + em.name + " bone=" + em.bone + " burst=" + em.count + " world=("
                    + String.format(java.util.Locale.ROOT, "%.2f, %.2f, %.2f", pos.x, pos.y, pos.z)
                    + ") tex=" + em.texture + " 存活=" + PARTS.size());
        }
        advanceEmissions(mc, model, time);
    }

    private static void beginEmission(GunConfig.FxEmitter em, double start) {
        diagStarts++;
        if (EMISSIONS.size() >= 128) return;
        float[][] bursts = em.bursts == null ? new float[][]{{0, em.count}} : em.bursts;
        // CountLow is sampled once per system activation, never again on a later frame.
        if (em.bursts != null) {
            bursts = new float[em.bursts.length][];
            for (int i = 0; i < bursts.length; i++) {
                float[] b = em.bursts[i];
                int high = Math.max(0, (int)b[1]);
                int low = b.length > 2 ? Math.max(0, Math.min(high, (int)b[2])) : high;
                int n = low == high ? high : java.util.concurrent.ThreadLocalRandom.current().nextInt(low, high + 1);
                bursts[i] = new float[]{b[0], n};
            }
        }
        EMISSIONS.add(new Emission(em, new FxEmissionSchedule(start + em.delay, em.duration, em.rate, bursts), start + em.delay));
    }

    private static void advanceEmissions(Minecraft mc, FxParticleSource model, double time) {
        var it = EMISSIONS.iterator();
        while (it.hasNext()) {
            Emission e = it.next();
            GunConfig.FxEmitter em = e.emitter();
            boolean aiming = model.isShooterAiming(mc);
            Vec3 pos = (em.hideWhenAiming && aiming) || (em.onlyWhenAiming && !aiming)
                    ? null : model.fxBoneWorld(em.bone, em.offX, em.offY, em.offZ, em.boneSpace, mc);
            if (pos == null) diagNullPosition++;
            e.schedule().advance(time, em.lifeMax, pos == null ? 0 : MAX_PARTICLES - PARTS.size(),
                    birth -> spawn(mc, em, pos, birth, (float)(birth - e.emitterStart())));
            if (e.schedule().done()) it.remove();
        }
    }

    private static void advanceInspect(Minecraft mc, FxParticleSource model, double time) {
        float progress = model.getInspectProgressSeconds(mc.player.getMainHandItem());
        if (progress < 0 || progress < previousInspect) {
            EMISSIONS.removeIf(e -> "inspect".equals(e.emitter().trigger));
            PARTS.removeIf(p -> p.em.killOnDeactivate && "inspect".equals(p.em.trigger));
            previousInspect = -1f;
        }
        if (progress >= 0) for (GunConfig.FxEmitter em : model.activeFxEmitters()) {
            if (em.enabled && "inspect".equals(em.trigger) && em.at > previousInspect && em.at <= progress)
                beginEmission(em, time - (progress - em.at));
        }
        previousInspect = progress;
    }

    private static void clearTransientFx() {
        EMISSIONS.clear();
        ANIMATION_CURSORS.clear();
        ALWAYS_ACC.clear();
        previousInspect = -1f;
        PARTS.removeIf(p -> !"held".equals(p.em.trigger));
    }

    private static float[][] heldBursts(GunConfig.FxEmitter em) {
        if (em.bursts == null) return null;
        float[][] result = new float[em.bursts.length][];
        for (int i = 0; i < result.length; i++) {
            float[] row = em.bursts[i];
            int high = Math.max(0, (int)row[1]);
            int low = row.length > 2 ? Math.max(0, Math.min(high, (int)row[2])) : high;
            result[i] = new float[]{row[0], low == high ? high
                    : java.util.concurrent.ThreadLocalRandom.current().nextInt(low, high + 1)};
        }
        return result;
    }

    /** Invoked only after this frame's actual first-person hand capture, never by GUI/ticks. */
    private static void advanceHeld(Minecraft mc, FxParticleSource model, double time) {
        if (!ownerHasHeld || !model.deferEmissionUntilHand()) return;
        for (GunConfig.FxEmitter em : model.activeFxEmitters()) {
            if (!em.enabled || !"held".equals(em.trigger)) continue;
            FxLoopingEmissionSchedule schedule = HELD.get(em);
            if (schedule == null) {
                schedule = new FxLoopingEmissionSchedule(time, em.delay, em.duration, em.rate,
                        em.emitterLoops, em.delayFirstLoopOnly, () -> heldBursts(em));
                HELD.put(em, schedule);
            }
            Vec3 pos = model.fxBoneWorld(em.bone, em.offX, em.offY, em.offZ, em.boneSpace, mc);
            schedule.advance(time, em.lifeMax, pos == null ? 0 : MAX_PARTICLES - PARTS.size(),
                    (birth, age) -> spawn(mc, em, pos, birth, age));
        }
    }

    private static boolean advanceAnimations(FxParticleSource model, double time) {
        if (!model.allowTransientFx(Minecraft.getInstance())) { clearTransientFx(); return false; }
        boolean active = false;
        model.sampleAnimations();
        for (GunConfig.FxEmitter em : model.activeFxEmitters()) {
            if (!em.enabled || !"animation".equals(em.trigger)) continue;
            var runner = model.animation(em);
            if (runner != null) active = true;
            float progress = runner == null ? -1f : runner.getProgressNs() / 1e9f;
            FxAnimationCursor cursor = ANIMATION_CURSORS.computeIfAbsent(em, key -> new FxAnimationCursor());
            boolean fire = cursor.advance(runner, progress, em.at);
            if (cursor.changed()) {
                EMISSIONS.removeIf(e -> e.emitter() == em);
                if (em.killOnDeactivate) PARTS.removeIf(p -> p.em == em);
            }
            if (fire) beginEmission(em, time - (progress - em.at));
        }
        return active;
    }

    private static void spawn(Minecraft mc, GunConfig.FxEmitter em, Vec3 pos, double time) {
        spawn(mc, em, pos, time, 0f);
    }

    private static void spawn(Minecraft mc, GunConfig.FxEmitter em, Vec3 pos, double time, float emitterAge) {
        diagSpawnAttempts++;
        if (PARTS.size() >= MAX_PARTICLES) return;
        var rnd = mc.level.random;
        ResourceLocation tex = texture(em.texture);
        if (tex == null) return;
        P p = new P();
        p.em = em;
        p.cascade = FxCascadeShader.operation(em.material);
        if (p.cascade >= 0 && em.subCols * em.subRows > 64) return;
        p.born = time;
        if (em.followBone) p.anchor = ANCHORS.computeIfAbsent(em, key -> new Anchor());
        if (em.mesh != null) {
            ResourceLocation tex2 = texture(em.texture2), tex3 = texture(em.texture3);
            if (p.cascade < 0 && (tex2 == null || tex3 == null || !"release_of_magic".equals(em.material))) return;
            p.meshPose = new Matrix4f();
            if (!ownerModel.fxBoneWorldPose(em.bone, mc, fovScaleNow(), p.meshPose)) return;
            p.meshPose.setTranslation(0, 0, 0);
            p.meshScale = new float[3];
            float[] angles = new float[3];
            for (int axis = 0; axis < 3; axis++) {
                p.meshScale[axis] = em.mesh.scaleMin[axis] + rnd.nextFloat() * (em.mesh.scaleMax[axis] - em.mesh.scaleMin[axis]);
                angles[axis] = (em.mesh.rotationMin[axis] + rnd.nextFloat() * (em.mesh.rotationMax[axis] - em.mesh.rotationMin[axis])) * (float)(Math.PI * 2);
            }
            applyAxisLocks(p.meshScale, em.mesh.lockedAxes);
            // Explicit candidate mapping in FxMesh; source Vector->Rotator order is not yet recovered.
            p.meshRotation = "roll_pitch_yaw_candidate".equals(em.mesh.rotationOrder)
                    ? new Matrix4f().rotateZ(angles[2]).rotateY(-angles[1]).rotateX(-angles[0])
                    : new Matrix4f().rotateZ(angles[1]).rotateY(-angles[0]).rotateX(-angles[2]);
            if (p.cascade < 0) p.renderType = MeshyRenderTypes.fxSourceMesh(tex, tex2, tex3, !"alpha".equalsIgnoreCase(em.blend));
        } else if (p.cascade < 0) p.renderType = em.material.isEmpty() ? MeshyRenderTypes.fxSprite(tex, !"alpha".equalsIgnoreCase(em.blend))
                : MeshyRenderTypes.fxSource(tex, !"alpha".equalsIgnoreCase(em.blend), em.material, em.textureScale, em.texturePower, em.textureSrgb);
        if (p.cascade >= 0) p.renderType = FxCascadeShader.type(tex,
                em.texture2.isEmpty() ? tex : texture(em.texture2),
                em.texture3.isEmpty() ? tex : texture(em.texture3),
                em.texture4.isEmpty() ? tex : texture(em.texture4),
                em.texture5.isEmpty() ? tex : texture(em.texture5),
                em.material, !"alpha".equalsIgnoreCase(em.blend), em.sourceUnit);
        p.seed = rnd.nextFloat();
        p.alphaSeed = rnd.nextFloat();
        p.colorR = em.colorScaleMin[0] + rnd.nextFloat() * (em.colorScaleMax[0] - em.colorScaleMin[0]);
        p.colorG = em.colorScaleMin[1] + rnd.nextFloat() * (em.colorScaleMax[1] - em.colorScaleMin[1]);
        p.colorB = em.colorScaleMin[2] + rnd.nextFloat() * (em.colorScaleMax[2] - em.colorScaleMin[2]);
        p.x = pos.x; p.y = pos.y; p.z = pos.z;
        if ((em.locationMin != null || em.orbitOffsetMin != null || em.locationCurve != null) && em.motionBasis != null) {
            p.localOffset = new Vector3f();
            for (int axis = 0; axis < 3; axis++) {
                float value = em.locationMin == null ? 0f : em.locationMin[axis] + rnd.nextFloat()*(em.locationMax[axis]-em.locationMin[axis]);
                if (em.locationCurve != null) value += FxCurveMotion.value(em.locationCurve, emitterAge, axis + 1);
                // Explicit spawn-only zero-rotation Orbit reduction, independent of Location's random draw.
                if (em.orbitOffsetMin != null)
                    value += em.orbitOffsetMin[axis] + rnd.nextFloat()*(em.orbitOffsetMax[axis]-em.orbitOffsetMin[axis]);
                p.localOffset.setComponent(axis, value);
            }
            em.motionBasis.transformDirection(p.localOffset);
            if (!em.followBone) {
                if (p.meshPose == null) {
                    p.meshPose = new Matrix4f();
                    if (!ownerModel.fxBoneWorldPose(em.bone, mc, fovScaleNow(), p.meshPose)) return;
                    p.meshPose.setTranslation(0, 0, 0);
                }
                p.meshPose.transformDirection(p.localOffset);
            }
        }
        double v0 = em.velMin + (em.velMax - em.velMin) * rnd.nextDouble();
        p.vx = (rnd.nextDouble() - 0.5) * 2 * v0;
        p.vy = (rnd.nextDouble() - 0.5) * 2 * v0;
        p.vz = (rnd.nextDouble() - 0.5) * 2 * v0;
        if (em.velocityMin != null && em.velocityMax != null && em.motionBasis != null) {
            MOTION.set(em.velocityMin[0] + rnd.nextFloat() * (em.velocityMax[0] - em.velocityMin[0]),
                    em.velocityMin[1] + rnd.nextFloat() * (em.velocityMax[1] - em.velocityMin[1]),
                    em.velocityMin[2] + rnd.nextFloat() * (em.velocityMax[2] - em.velocityMin[2]));
            if (em.velocityLifeCurve != null) p.sourceVelocity = new Vector3f(MOTION);
            em.motionBasis.transformDirection(MOTION);
            if (!em.followBone) {
                if (p.meshPose == null) {
                    p.meshPose = new Matrix4f();
                    if (!ownerModel.fxBoneWorldPose(em.bone, mc, fovScaleNow(), p.meshPose)) return;
                    p.meshPose.setTranslation(0, 0, 0);
                }
                p.meshPose.transformDirection(MOTION);
            }
            p.vx = MOTION.x; p.vy = MOTION.y; p.vz = MOTION.z;
        }
        if (em.worldAccelerationMin != null && em.worldAccelerationMax != null) {
            p.accelX = em.worldAccelerationMin[0] + rnd.nextFloat() * (em.worldAccelerationMax[0] - em.worldAccelerationMin[0]);
            p.accelY = em.worldAccelerationMin[1] + rnd.nextFloat() * (em.worldAccelerationMax[1] - em.worldAccelerationMin[1]);
            p.accelZ = em.worldAccelerationMin[2] + rnd.nextFloat() * (em.worldAccelerationMax[2] - em.worldAccelerationMin[2]);
        }
        p.age = 0f;
        p.life = Math.max(0.005f, em.lifeMin + (em.lifeMax - em.lifeMin) * rnd.nextFloat());
        p.size = em.sizeMin + (em.sizeMax - em.sizeMin) * rnd.nextFloat();
        if (em.sizeSamples != null) {
            p.size = 0f;
            for (float[] sample : em.sizeSamples) p.size += sample[0] + rnd.nextFloat() * (sample[1] - sample[0]);
        }
        if (em.sizeYMin >= 0) p.sizeY = em.sizeYMin + (em.sizeYMax - em.sizeYMin) * rnd.nextFloat();
        float sizeMultiplier = em.sizeMultiplierMin + (em.sizeMultiplierMax - em.sizeMultiplierMin) * rnd.nextFloat();
        p.size *= sizeMultiplier;
        if (p.sizeY >= 0) p.sizeY *= sizeMultiplier;
        if (em.sizeYFromX) p.sizeY = p.size;
        if (em.subRandom) p.randomFrame = rnd.nextInt(Math.max(1, em.subCols * em.subRows));
        p.rot = (em.rotationMin + rnd.nextFloat() * (em.rotationMax - em.rotationMin)) * (float) Math.PI * 2f;
        p.spin = Float.isFinite(em.rotationRateMin)
                ? (em.rotationRateMin + rnd.nextFloat() * (em.rotationRateMax - em.rotationRateMin)) * (float) Math.PI * 2f
                : (em.spinMax > 0f ? (rnd.nextFloat() - 0.5f) * 2f * em.spinMax : 0f);
        p.aspect = em.aspect <= 0f ? 1f : em.aspect;
        p.hideAds = em.hideWhenAiming;
        p.onlyAds = em.onlyWhenAiming;
        p.beam = "beam".equalsIgnoreCase(em.type);
        if (p.beam) {
            // Positions and axes both use world coordinates, relative to the camera at draw time.
            Vec3 direction = mc.player.getViewVector(1f);
            p.ax = direction.x; p.ay = direction.y; p.az = direction.z;
            p.vx = p.vy = p.vz = 0;
        }
        PARTS.add(p);
        diagSpawnAccepted++;

    }

    private static void applyAxisLocks(float[] values, String lock) {
        if ("EDVLF_XYZ".equals(lock)) values[1] = values[2] = values[0];
        else if ("EDVLF_XY".equals(lock)) values[1] = values[0];
        else if ("EDVLF_XZ".equals(lock)) values[2] = values[0];
        else if ("EDVLF_YZ".equals(lock)) values[2] = values[1];
    }

    /** extras 里的贴图 id → 纹理管理器要的文件路径（同 TaCZ IDisplay.converter 规则）。 */
    private static ResourceLocation texture(String id) {
        if (id == null || id.isEmpty()) return null;
        ResourceLocation hit = TEX_CACHE.get(id);
        if (hit != null) return hit;
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null) return null;
        try {
            rl = com.tacz.guns.client.resource.pojo.display.IDisplay.converter.idToFile(rl);
        } catch (Throwable ignored) {
            // 转换失败就按原样用（与 geo 贴图字段同一容错策略）
        }
        TEX_CACHE.put(id, rl);
        return rl;
    }

    // ------------------------------------------------------------------ 逐帧推进

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        FxParticleSource model = currentSource(mc);
        if (!localOwner(mc, model)) { clear(); return; }
        syncOwner(mc, model);
        double time = now(mc);
        if (!model.allowTransientFx(mc)) { clearTransientFx(); return; }
        if (mc.isPaused()) return;
        final float dt = 0.05f;
        boolean aiming = model.isShooterAiming(mc);
        for (GunConfig.FxEmitter em : model.activeFxEmitters()) {
            if (!em.enabled || !"always".equals(em.trigger) || em.rate <= 0f) continue;
            if ((em.hideWhenAiming && aiming) || (em.onlyWhenAiming && !aiming)) {
                ALWAYS_ACC.remove(em);
                continue;
            }
            float acc = ALWAYS_ACC.getOrDefault(em, 0f) + em.rate * dt;
            int count = Math.min((int) acc, MAX_PARTICLES - PARTS.size());
            if (count > 0) {
                Vec3 pos = model.fxBoneWorld(em.bone, em.offX, em.offY, em.offZ, em.boneSpace, mc);
                if (pos != null) for (int i = 0; i < count; i++) spawn(mc, em, pos, time + em.delay);
            }
            // Drop over-budget emission instead of accumulating a later burst.
            ALWAYS_ACC.put(em, acc - (int) acc);
        }
        // Lifetime/motion are sampled at render time; a 50ms tick cannot delete a fresh flash.
    }

    // ------------------------------------------------------------------ 渲染

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            afterHandReady = false;
            LEVEL_VIEW_POSE.set(event.getPoseStack().last().pose());
            LEVEL_MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
            LEVEL_PROJECTION.set(RenderSystem.getProjectionMatrix());
            levelViewFrame = BloomPostProcessor.currentFrameId();
            levelSorting = RenderSystem.getVertexSorting();
            levelViewCaptured = true;
            return;
        }
        // Preserve world depth before GameRenderer clears it for the hand projection.
        // AFTER_LEVEL's pose is a projection stack; the actual view was saved above.
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !levelViewCaptured) return;
        levelViewCaptured = false;
        Minecraft mc = Minecraft.getInstance();
        FxParticleSource model = currentSource(mc);
        if (!localOwner(mc, model)) { clear(); FxSceneCapture.clear(); return; }
        syncOwner(mc, model);
        double time = now(mc);
        boolean animationActive = false;
        if (!model.allowTransientFx(mc)) clearTransientFx();
        if (!mc.isPaused() && model.allowTransientFx(mc)) {
            if (ownerHasInspect) advanceInspect(mc, model, time);
            animationActive = advanceAnimations(model, time);
            if (!model.deferEmissionUntilHand()) advanceEmissions(mc, model, time);
        }
        // Only explicit held emitters or configured notification animations prewarm capture.
        boolean visible = model.deferEmissionUntilHand() && (ownerHasHeld || animationActive || !EMISSIONS.isEmpty());
        diagActive = model.deferEmissionUntilHand() && (ownerHasHeld || animationActive || !EMISSIONS.isEmpty() || !PARTS.isEmpty());
        boolean aiming = model.isShooterAiming(mc);
        // Expire even if scene capture fails, and allocate no buffers for delayed/hidden particles.
        for (int i = PARTS.size() - 1; i >= 0; i--) {
            P p = PARTS.get(i);
            double age = time - p.born;
            if (age >= p.life) PARTS.remove(i);
            else if (age >= 0 && !(p.hideAds && aiming) && !(p.onlyAds && !aiming)) visible = true;
        }
        if (!visible) {
            if (PARTS.isEmpty() && EMISSIONS.isEmpty()) FxSceneCapture.clear();
            return;
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(buffers)) buffers.endBatch();
        afterHandReady = FxSceneCapture.beginFrame(mc.getWindow().getWidth(), mc.getWindow().getHeight());
        if (afterHandReady && !FxPreHandDepth.repair(model, FxSceneCapture.depthTexture(),
                mc.getWindow().getWidth(), mc.getWindow().getHeight())) {
            afterHandReady = false;
            FxSceneCapture.endFrame();
        }
        if (afterHandReady) diagCaptureOk++; else diagCaptureFail++;
        traceMelee("level", model);
    }

    /** Called after GameRenderer.renderLevel returns, including Oculus's tail callbacks. */
    public static void renderAfterHand() {
        diagDrawCalls++;
        if (!afterHandReady) { diagDrawNotReady++; return; }
        afterHandReady = false;
        long totalStart = System.nanoTime(), prepared = totalStart, flushed = totalStart, targetReady = totalStart;
        long submissionNs = 0;
        Minecraft mc = Minecraft.getInstance();
        FxParticleSource model = currentSource(mc);
        // Consume exactly this frame's snapshot, also when ownership/camera changed during hand rendering.
        try {
        if (!localOwner(mc, model)) { diagDrawNoParts++; traceMelee("draw-reject", model); return; }
        if (model.deferEmissionUntilHand()) {
            // Both Oculus and vanilla have finished the hand by this existing tail callback.
            // Fail closed for stale/missing poses, changed skins/items/worlds or a duplicate callback.
            if (!HAND_FRAME.consume(BloomPostProcessor.currentFrameId(), mc.level, model,
                    model.activeFxEmitters(), model.ownershipToken())) {
                traceMelee("draw-no-current-hand", model);
                return;
            }
            if (!mc.isPaused()) {
                double handTime = now(mc);
                // Re-sample after hand stateMachine.update: cancel interrupted queues before births.
                advanceAnimations(model, handTime);
                advanceEmissions(mc, model, handTime);
            }
            if (!model.allowTransientFx(mc)) clearTransientFx();
            advanceHeld(mc, model, now(mc));
        }
        if (PARTS.isEmpty()) { diagDrawNoParts++; traceMelee("draw-reject", model); return; }
        prepared = System.nanoTime();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(buffers)) buffers.endBatch();
        flushed = System.nanoTime();
        if (!FxSceneCapture.beginAfterHand()) return;
        targetReady = System.nanoTime();
        double time = now(mc);
        long renderStart = System.nanoTime();
        Camera cam = mc.gameRenderer.getMainCamera();
        if (!cam.isInitialized()) return;
        Vec3 camPos = cam.getPosition();
        // Use world view/projection with the preserved world depth, never the hand depth.
        cam.rotation().transform(RIGHT.set(-1f, 0f, 0f));
        cam.rotation().transform(UP.set(0f, 1f, 0f));
        Matrix4f mat = LEVEL_VIEW_POSE;
        final boolean aiming = model.isShooterAiming(mc);
        final float fovScale = fovScaleNow();
        FxHeldDepth.prepare(model, LEVEL_MODEL_VIEW, LEVEL_VIEW_POSE);
        frame++;
        boolean needsScene = false, needsDistortion = false;
        for (P p : PARTS) {
            double age = time - p.born;
            if (p.cascade < 0 || age < 0 || age >= p.life || (p.hideAds && aiming) || (p.onlyAds && !aiming)) continue;
            needsScene = true;
            if (FxCascadeShader.isDistortionOperation(p.cascade)) needsDistortion = true;
        }
        boolean sceneReady = false;
        if (needsScene && FxCascadeShader.isReady()) {
            sceneReady = true;
            FxCascadeShader.prepare(FxSceneCapture.depthTexture(), mc.getWindow().getWidth(),
                    mc.getWindow().getHeight(), (float)(time % 4096.0));
        }
        SAVED_MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
        SAVED_PROJECTION.set(RenderSystem.getProjectionMatrix());
        VertexSorting savedSorting = RenderSystem.getVertexSorting();
        RenderSystem.getModelViewStack().last().pose().set(LEVEL_MODEL_VIEW);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(LEVEL_PROJECTION, levelSorting);
        try {
        for (int pass = needsDistortion && sceneReady ? 1 : 0; pass >= 0; pass--) {
        if (pass == 1 && !FxSceneCapture.beginDistortion()) continue;
        FxCascadeShader.setPass(pass);
        RenderType lastType = null;
        int meshVerticesLeft = MAX_MESH_VERTICES;
        int heldMeshVerticesLeft = MAX_HELD_MESH_VERTICES;
        Iterator<P> iterator = PARTS.iterator();
        while (iterator.hasNext()) {
            P p = iterator.next();
            p.age = (float) (time - p.born);
            if (p.age < 0) continue;
            if (p.age >= p.life) { iterator.remove(); continue; }
            if ((p.hideAds && aiming) || (p.onlyAds && !aiming)) continue;
            if (p.cascade >= 0 && !sceneReady) continue;
            if (pass == 1 && !FxCascadeShader.isDistortionOperation(p.cascade)) continue;
            float t = p.age / p.life;
            float mult = eval1(p.em.sizeCurve, t, 1f);
            float red = evalColor(p.em.colorCurve, t, 1) * p.colorR;
            float green = evalColor(p.em.colorCurve, t, 2) * p.colorG;
            float blue = evalColor(p.em.colorCurve, t, 3) * p.colorB;
            float alpha = Math.max(0f, eval1(p.em.alphaCurve, t, 1f));
            if (p.em.alphaScaleCurveMin != null && p.em.alphaScaleCurveMax != null) {
                float low = eval1(p.em.alphaScaleCurveMin, t, 1f), high = eval1(p.em.alphaScaleCurveMax, t, 1f);
                alpha *= low + (high - low) * p.alphaSeed;
            } else alpha *= eval1(p.em.alphaScaleCurve, t, 1f);
            if ("raw_opaque_alpha".equals(p.em.material)) alpha = 1f;
            if (p.em.material.isEmpty()) {
                red = clamp01(red); green = clamp01(green); blue = clamp01(blue); alpha = clamp01(alpha);
            } else if (p.cascade < 0 && alpha > 1f && !"alpha".equalsIgnoreCase(p.em.blend)) {
                red *= alpha; green *= alpha; blue *= alpha; alpha = 1f;
            }
            if (alpha <= 0.004f || mult <= 0) continue;
            float half = (p.cascade >= 0 ? p.size * mult : Math.max(0.01f, p.size * mult)) / 32f;
            double x = p.x - camPos.x, y = p.y - camPos.y, z = p.z - camPos.z;
            if (p.anchor != null) {
                Anchor a = p.anchor;
                if (a.frame != frame) {
                    a.valid = model.fxBoneWorldOffset(p.em.bone, p.em.offX, p.em.offY, p.em.offZ, p.em.boneSpace,
                            mc, fovScale, ANCHOR_POSE, a.offset);
                    if (a.valid && (p.em.mesh != null || p.em.motionBasis != null)) {
                        a.valid = model.fxBoneWorldPose(p.em.bone, mc, fovScale, a.meshPose);
                        a.meshPose.setTranslation(0, 0, 0);
                    }
                    a.frame = frame;
                }
                if (!a.valid) continue;
                x = a.offset.x; y = a.offset.y; z = a.offset.z;
            }
            MOTION.set((float)p.vx, (float)p.vy, (float)p.vz);
            if (p.anchor != null && p.em.motionBasis != null) p.anchor.meshPose.transformDirection(MOTION);
            DISPLACEMENT.set(MOTION).mul(p.age);
            if (p.sourceVelocity != null) {
                for (int axis = 0; axis < 3; axis++) {
                    float base = p.em.velocityLifeAbsolute ? 1f : p.sourceVelocity.get(axis);
                    DISPLACEMENT.setComponent(axis, base * p.life * FxCurveMotion.integral(p.em.velocityLifeCurve, t, axis+1));
                    MOTION.setComponent(axis, base * FxCurveMotion.value(p.em.velocityLifeCurve, t, axis+1));
                }
                p.em.motionBasis.transformDirection(DISPLACEMENT);
                p.em.motionBasis.transformDirection(MOTION);
                Matrix4f eventPose = p.anchor != null ? p.anchor.meshPose : p.meshPose;
                if (eventPose != null) { eventPose.transformDirection(DISPLACEMENT); eventPose.transformDirection(MOTION); }
            }
            float ex = (float) (x + DISPLACEMENT.x + 0.5 * p.accelX * p.age * p.age);
            float ey = (float) (y + DISPLACEMENT.y + 0.5 * (p.accelY - p.em.gravity) * p.age * p.age);
            float ez = (float) (z + DISPLACEMENT.z + 0.5 * p.accelZ * p.age * p.age);
            if (p.localOffset != null) {
                LOCATION.set(p.localOffset);
                if (p.anchor != null) p.anchor.meshPose.transformDirection(LOCATION);
                ex += LOCATION.x; ey += LOCATION.y; ez += LOCATION.z;
            }
            float cameraOffset = p.em.cameraOffsetCurve == null ? p.em.cameraOffset : eval1(p.em.cameraOffsetCurve, t, p.em.cameraOffset);
            if (cameraOffset != 0f) {
                float distance = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (distance <= Math.max(0f, cameraOffset)) continue;
                float factor = (distance - cameraOffset) / distance;
                ex *= factor; ey *= factor; ez *= factor;
            }
            if (p.em.mesh != null) {
                int needed = p.em.mesh.vertices.length / 3 * 4;
                if ("held".equals(p.em.trigger)) {
                    if (needed > heldMeshVerticesLeft) continue;
                    heldMeshVerticesLeft -= needed;
                } else {
                    if (needed > meshVerticesLeft) continue;
                    meshVerticesLeft -= needed;
                }
            }
            VertexConsumer vc = buffers.getBuffer(p.renderType);
            if (REPORTED_PROJECTION.add(p.em.material)) {
                org.joml.Vector4f clip = new org.joml.Vector4f(ex, ey, ez, 1);
                mat.transform(clip); LEVEL_MODEL_VIEW.transform(clip); LEVEL_PROJECTION.transform(clip);
                com.mojang.logging.LogUtils.getLogger().info("[TML FX projection] material={} center=[{},{},{}] clip={} half={} alpha={}", p.em.material, ex, ey, ez, clip, half, alpha);
            }
            lastType = p.renderType;
            if (p.em.mesh != null) {
                emitMesh(vc, mat, ex, ey, ez, p, t, red, green, blue, alpha);
            } else if (p.beam) {
                emitBeam(vc, mat, ex, ey, ez, p, RIGHT, UP, half, fovScale, red, green, blue, alpha);
            } else {
                Vector3f right = RIGHT, up = UP;
                if (p.em.velocityAlign) {
                    STREAK_UP.set(MOTION).add(p.accelX*p.age, (p.accelY-p.em.gravity)*p.age, p.accelZ*p.age);
                    // Project velocity onto the camera plane; keep the source Y size along motion.
                    float horizontal = STREAK_UP.dot(RIGHT), vertical = STREAK_UP.dot(UP);
                    if (horizontal*horizontal + vertical*vertical > 1e-12f) {
                        STREAK_UP.set(RIGHT).mul(horizontal).fma(vertical, UP).normalize();
                        STREAK_RIGHT.set(RIGHT).mul(vertical).fma(-horizontal, UP).normalize();
                        right = STREAK_RIGHT; up = STREAK_UP;
                    }
                }
                emitQuad(vc, mat, ex, ey, ez, p, right, up, half, red, green, blue, alpha);
            }
        }
        // Oculus FullyBufferedMultiBufferSource.endBatch(RenderType) is a no-op.
        // Use TaCZ's existing compatibility bridge to actually submit the queued vertices.
        long submitStart = System.nanoTime();
        if (lastType != null && !com.tacz.guns.compat.oculus.OculusCompat.endBatch(buffers)) {
            buffers.endBatch(lastType);
        }
        submissionNs += System.nanoTime() - submitStart;
        if (pass == 1) {
            FxSceneCapture.compositeDistortion();
            FxSceneCapture.bindParticleTarget();
        }
        }
        FxSceneCapture.presentColor();
        diagDrawSubmitted++;
        } finally {
            FxCascadeShader.setPass(0);
            RenderSystem.getModelViewStack().last().pose().set(SAVED_MODEL_VIEW);
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(SAVED_PROJECTION, savedSorting);
        }
        MeshPerf.recordEffect(MeshPerf.FX, System.nanoTime() - renderStart);
        } finally {
            FxHeldDepth.endDraw();
            FxSceneCapture.endFrame();
            long finished = System.nanoTime();
            if (targetReady > totalStart && finished - totalStart > 20_000_000L && slowDrawReports++ < 12) {
                com.mojang.logging.LogUtils.getLogger().info(
                        "[FxSlowDraw] totalMs={} prepareMs={} priorFlushMs={} targetMs={} drawMs={} submitMs={} particles={}",
                        (finished-totalStart)/1e6, (prepared-totalStart)/1e6, (flushed-prepared)/1e6,
                        (targetReady-flushed)/1e6, (finished-targetReady)/1e6, submissionNs/1e6, PARTS.size());
            }
        }
    }

    /**
     * 世界 pass 与"枪模型 pass"的 FOV 比 —— 与 {@code TaczPolyMeshGunModel.toWorldPos} 里给深度乘的
     * 那个系数完全同源。顶点是相机相对坐标、深度已被它放大，所以**沿视线**的长度也得乘同一系数。
     */
    private static float fovScaleNow() {
        try {
            double itemFov = com.tacz.guns.client.event.CameraSetupEvent.ITEM_MODEL_FOV_DYNAMICS.get();
            double levelFov = com.tacz.guns.client.event.CameraSetupEvent.WORLD_FOV_DYNAMICS.get();
            if (itemFov > 0 && levelFov > 0) {
                return (float) (Math.tan(Math.toRadians(itemFov) / 2.0)
                        / Math.tan(Math.toRadians(levelFov) / 2.0));
            }
        } catch (Throwable ignored) {
        }
        return 1f;
    }

    /** Real source triangles, submitted as degenerate quads to the existing buffered pass. */
    private static void emitMesh(VertexConsumer vc, Matrix4f mat, float x, float y, float z, P p,
                                  float t, float r, float g, float b, float a) {
        FxMesh mesh = p.em.mesh;
        MESH_TRANSFORM.set(p.anchor == null ? p.meshPose : p.anchor.meshPose)
                .mul(mesh.basis).mul(p.meshRotation)
                .scale(p.meshScale[0] * evalColor(mesh.scaleCurve, t, 1),
                       p.meshScale[1] * evalColor(mesh.scaleCurve, t, 2),
                       p.meshScale[2] * evalColor(mesh.scaleCurve, t, 3));
        if (!MESH_TRANSFORM.isFinite()) return;
        if (p.cascade >= 0) {
            MESH_NORMAL_MATRIX.set(MESH_TRANSFORM).invert().transpose();
            if (!MESH_NORMAL_MATRIX.isFinite()) return;
            MESH_EVENT_NORMAL_MATRIX.set(mat).mul(MESH_NORMAL_MATRIX);
            if (!MESH_EVENT_NORMAL_MATRIX.isFinite()) return;
            prepareCascadeParticle(p, r, g, b, a);
        }
        // Validate before beginning a primitive; never leave a partial quad in the shared buffer.
        int position = 0;
        for (float[] row : mesh.vertices) {
            MESH_TRANSFORM.transformDirection(MESH_VERTEX.set(row[0], row[1], row[2]));
            if (!MESH_VERTEX.isFinite()) return;
            MESH_POSITIONS[position++] = MESH_VERTEX.x;
            MESH_POSITIONS[position++] = MESH_VERTEX.y;
            MESH_POSITIONS[position++] = MESH_VERTEX.z;
        }
        float er = p.cascade >= 0 ? cascadeRed : encodeMeshColor(r);
        float eg = p.cascade >= 0 ? cascadeGreen : encodeMeshColor(g);
        float eb = p.cascade >= 0 ? cascadeBlue : encodeMeshColor(b);
        int age = Math.round(clamp01(t) * 65535f), seed = Math.round(p.seed * 32767f);
        for (int i = 0; i < mesh.vertices.length; i += 3) {
            for (int corner = 0; corner < 4; corner++) {
                int index = i + Math.min(corner, 2);
                float[] row = mesh.vertices[index];
                int offset = index * 3;
                float px = x + MESH_POSITIONS[offset], py = y + MESH_POSITIONS[offset + 1], pz = z + MESH_POSITIONS[offset + 2];
                if (p.cascade >= 0) {
                    if (corner < 3) {
                        MESH_NORMAL.set(row.length >= 8 ? row[5] : 0f, row.length >= 8 ? row[6] : 0f, row.length >= 8 ? row[7] : 1f);
                        // Corner 3 duplicates corner 2, including its transformed normal.
                        MESH_EVENT_NORMAL_MATRIX.transform(MESH_NORMAL).normalize();
                    }
                    cascadeVertex(vc, mat, px, py, pz,
                            row[3], row[4], MESH_NORMAL.x, MESH_NORMAL.y, MESH_NORMAL.z);
                    continue;
                }
                vc.vertex(mat, px, py, pz)
                        .color(er, eg, eb, clamp01(a)).uv(row[3], row[4])
                        .overlayCoords(age, seed).uv2(0, 0).normal(0, 0, 1).endVertex();
            }
        }
    }

    private static float encodeMeshColor(float value) {
        return clamp01((float)(Math.log1p(Math.max(0f, value)) / Math.log(2.0) / 16.0));
    }

    private static float sourceFrame(P p) {
        int total = Math.max(1, p.em.subCols * p.em.subRows);
        if (p.em.subRandom) return Math.max(0, Math.min(total - 1, p.randomFrame));
        return Math.max(0, Math.min(total - 1f, eval1(p.em.subImageCurve, p.age / p.life, p.age / p.life * total)));
    }

    private static int framePacket(P p) {
        return (int)sourceFrame(p) | p.em.subCols << 6 | p.em.subRows << 11;
    }

    private static void prepareCascadeParticle(P p, float r, float g, float b, float a) {
        float index = sourceFrame(p);
        cascadeMix = p.em.subBlend && !p.em.subRandom ? Math.round((index - (int)index) * 255f) : 0;
        // Both RGB and alpha need HDR transport; source spark alpha can exceed one before depth fading.
        cascadeRed = encodeMeshColor(r);
        cascadeGreen = encodeMeshColor(g);
        cascadeBlue = encodeMeshColor(b);
        cascadeAlpha = clamp01((float)(Math.log1p(Math.max(0f, a)) / Math.log(2.0) / 8.0));
        cascadeAge = Math.round(clamp01(p.age/p.life)*65535f);
        cascadePacket = (int)index | p.em.subCols << 6 | p.em.subRows << 11;
        cascadeSeed = Math.round(p.seed * 32767f) | ("held".equals(p.em.trigger) ? 32768 : 0);
    }

    /** Payload is constant for the whole particle; normals already include the event pose. */
    private static void cascadeVertex(VertexConsumer vc, Matrix4f mat, float x, float y, float z,
                                      float u, float v, float nx, float ny, float nz) {
        vc.vertex(mat,x,y,z).color(cascadeRed,cascadeGreen,cascadeBlue,cascadeAlpha)
                .uv(u,v).overlayCoords(cascadeAge,cascadePacket)
                .uv2(cascadeMix,cascadeSeed).normal(nx,ny,nz).endVertex();
    }

    /** 相机朝向的四边形（可选绕视线滚转）；坐标按调用方给好的 (fx,fy,fz) 写入。 */
    private static void emitQuad(VertexConsumer vc, Matrix4f mat,
                                 float fx, float fy, float fz, P p,
                                 Vector3f right, Vector3f up, float half,
                                 float r, float g, float b, float a) {
        if (p.cascade >= 0) {
            // Cascade atlas selection is prepared once inside emitQuadFrame, not once per corner.
            emitQuadFrame(vc, mat, fx, fy, fz, p, right, up, half, r, g, b, a, 0);
            return;
        }
        int count = p.em.subCols * p.em.subRows;
        float index = sourceFrame(p);
        int first = (int) index;
        if (!p.em.material.isEmpty()) {
            emitQuadFrame(vc, mat, fx, fy, fz, p, right, up, half, r, g, b, a, first);
            return;
        }
        float blend = p.em.subBlend && !"alpha".equalsIgnoreCase(p.em.blend) ? index - first : 0f;
        emitQuadFrame(vc, mat, fx, fy, fz, p, right, up, half, r, g, b, a * (1f - blend), first);
        if (blend > 0f && first + 1 < count)
            emitQuadFrame(vc, mat, fx, fy, fz, p, right, up, half, r, g, b, a * blend, first + 1);
    }

    private static void emitQuadFrame(VertexConsumer vc, Matrix4f mat,
                                 float fx, float fy, float fz, P p,
                                 Vector3f right, Vector3f up, float half,
                                 float r, float g, float b, float a, int fr) {
        if (p.cascade >= 0) {
            prepareCascadeParticle(p, r, g, b, a);
            // Camera-facing normal is the same at all four corners and uses the same event pose.
            MESH_NORMAL.set(RIGHT.y*UP.z-RIGHT.z*UP.y, RIGHT.z*UP.x-RIGHT.x*UP.z, RIGHT.x*UP.y-RIGHT.y*UP.x);
            mat.transformDirection(MESH_NORMAL).normalize();
            cascadeNormalX = MESH_NORMAL.x; cascadeNormalY = MESH_NORMAL.y; cascadeNormalZ = MESH_NORMAL.z;
        }
        final float halfA = p.sizeY >= 0 ? p.sizeY * eval1(p.em.sizeYCurve, p.age / p.life, 1f) / 32f
                : half * (p.aspect <= 0f ? 1f : p.aspect);
        float angle = p.rot + p.spin * p.age;
        float cs = (float) Math.cos(angle), sn = (float) Math.sin(angle);
        float rx = right.x() * cs + up.x() * sn;
        float ry = right.y() * cs + up.y() * sn;
        float rz = right.z() * cs + up.z() * sn;
        float ux = up.x() * cs - right.x() * sn;
        float uy = up.y() * cs - right.y() * sn;
        float uz = up.z() * cs - right.z() * sn;
        // SubUV 序列帧：按寿命推进帧号（行 0 在贴图顶部）
        int cols = Math.max(1, p.em.subCols), rows = Math.max(1, p.em.subRows);
        float u0 = 0f, u1 = 1f, vT = 1f, vB = 0f;
        if (p.cascade < 0 && (cols > 1 || rows > 1)) {
            int cx = fr % cols, cy = fr / cols;
            u0 = (float) cx / cols; u1 = (float) (cx + 1) / cols;
            vT = (float) (cy + 1) / rows; vB = (float) cy / rows;
        }
        // 四角：(-r,-u) (r,-u) (r,u) (-r,u)，UV 与之对应
        fxVertex(vc, mat, p, fx - rx * half - ux * halfA, fy - ry * half - uy * halfA, fz - rz * half - uz * halfA, r,g,b,a,u0,vT,fr);
        fxVertex(vc, mat, p, fx + rx * half - ux * halfA, fy + ry * half - uy * halfA, fz + rz * half - uz * halfA, r,g,b,a,u1,vT,fr);
        fxVertex(vc, mat, p, fx + rx * half + ux * halfA, fy + ry * half + uy * halfA, fz + rz * half + uz * halfA, r,g,b,a,u1,vB,fr);
        fxVertex(vc, mat, p, fx - rx * half + ux * halfA, fy - ry * half + uy * halfA, fz - rz * half + uz * halfA, r,g,b,a,u0,vB,fr);
    }

    private static void fxVertex(VertexConsumer vc, Matrix4f mat, P p, float x, float y, float z,
                                 float r, float g, float b, float a, float u, float v, int fr) {
        if (p.cascade >= 0) {
            cascadeVertex(vc,mat,x,y,z,u,v,cascadeNormalX,cascadeNormalY,cascadeNormalZ);
            return;
        }
        if (p.em.material.isEmpty()) {
            vc.vertex(mat,x,y,z).color(r,g,b,a).uv(u,v).uv2(FULL_BRIGHT).endVertex();
            return;
        }
        float scale = Math.max(1f, Math.max(r, Math.max(g,b)));
        int packedScale = Math.min(32767, (int) Math.ceil(scale * 16f));
        float encodedScale = packedScale / 16f;
        int total = p.em.subCols * p.em.subRows;
        float idx = Math.max(0, Math.min(total - 1f, eval1(p.em.subImageCurve, p.age / p.life, p.age / p.life * total)));
        int mix = p.em.subBlend ? Math.round((idx - (int) idx) * 255f) : 0;
        vc.vertex(mat,x,y,z).color(clamp01(r/encodedScale),clamp01(g/encodedScale),clamp01(b/encodedScale),clamp01(a))
                .uv(u,v).overlayCoords(p.em.subCols | p.em.subRows << 5, fr)
                .uv2(packedScale,mix).normal(0,0,1).endVertex();
    }

    /** Beam placeholder: world-aligned segments, relative to camera. Real target endpoints are phase 2. */
    private static void emitBeam(VertexConsumer vc, Matrix4f mat,
                                 float fx, float fy, float fz, P p,
                                 Vector3f camRight, Vector3f camUp, float half, float fovScale,
                                 float r, float g, float b, float a) {
        float ax = (float) p.ax, ay = (float) p.ay, az = (float) p.az;
        float alen = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (alen < 1e-5f) { ax = 0f; ay = 0f; az = -1f; } else { ax /= alen; ay /= alen; az /= alen; }
        int seg = Math.max(1, p.em.beamSegments);
        // 长度：与锚点深度走同一套 FOV 换算（横向尺寸不用乘——角大小本来就不随 FOV 变）。
        // Retain the old short-beam depth budget until target-driven beams replace this approximation.
        float want = Math.max(1f, p.em.beamLength) / 16f * fovScale;
        float depth = Math.abs(fx * ax + fy * ay + fz * az);
        float len = Math.min(want, Math.max(0.05f, depth * 0.85f));
        float step = len / seg;
        for (int i = 0; i < seg; i++) {
            float t0 = i * step, t1 = (i + 1) * step;
            float x0 = fx + ax * t0, y0 = fy + ay * t0, z0 = fz + az * t0;
            float x1 = fx + ax * t1, y1 = fy + ay * t1, z1 = fz + az * t1;
            // Camera-relative world vector towards this segment's midpoint.
            float ex = (x0 + x1) * 0.5f, ey = (y0 + y1) * 0.5f, ez = (z0 + z1) * 0.5f;
            float el = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
            if (el < 1e-4f) { ex = 0f; ey = 0f; ez = 1f; el = 1f; }
            ex /= el; ey /= el; ez /= el;
            // 宽度方向 = 视线 × 轴向
            float wx = ey * az - ez * ay;
            float wy = ez * ax - ex * az;
            float wz = ex * ay - ey * ax;
            float wl = (float) Math.sqrt(wx * wx + wy * wy + wz * wz);
            if (wl < 1e-4f) { wx = camRight.x(); wy = camRight.y(); wz = camRight.z(); wl = 1f; }
            wx /= wl; wy /= wl; wz /= wl;
            vc.vertex(mat, x0 - wx * half, y0 - wy * half, z0 - wz * half).color(r, g, b, a).uv(0f, 1f).uv2(FULL_BRIGHT).endVertex();
            vc.vertex(mat, x0 + wx * half, y0 + wy * half, z0 + wz * half).color(r, g, b, a).uv(0f, 0f).uv2(FULL_BRIGHT).endVertex();
            vc.vertex(mat, x1 + wx * half, y1 + wy * half, z1 + wz * half).color(r, g, b, a).uv(1f, 0f).uv2(FULL_BRIGHT).endVertex();
            vc.vertex(mat, x1 - wx * half, y1 - wy * half, z1 - wz * half).color(r, g, b, a).uv(1f, 1f).uv2(FULL_BRIGHT).endVertex();
        }
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /** [t, v] 曲线 → v（线性插值，端点外取端点值）。 */
    private static float eval1(float[][] c, float t, float dflt) {
        if (c == null || c.length == 0 || c[0].length < 2) return dflt;
        return evalChannel(c, t, 1);
    }

    /** 直接插值一个颜色通道，避免逐粒子、逐帧创建临时曲线数组。 */
    private static float evalColor(float[][] c, float t, int col) {
        if (c == null || c.length == 0 || c[0].length <= col) return 1f;
        return evalChannel(c, t, col);
    }

    /** Dense source Hermite samples are searched in O(log n), without per-frame allocations. */
    private static float evalChannel(float[][] c, float t, int col) {
        if (t <= c[0][0]) return c[0][col];
        if (t >= c[c.length - 1][0]) return c[c.length - 1][col];
        int low = 1, high = c.length - 1;
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (c[mid][0] < t) low = mid + 1; else high = mid;
        }
        float t0 = c[low - 1][0], t1 = c[low][0];
        float u = t1 - t0 < 1e-6f ? 1f : (t - t0) / (t1 - t0);
        return c[low - 1][col] + (c[low][col] - c[low - 1][col]) * u;
    }
}
