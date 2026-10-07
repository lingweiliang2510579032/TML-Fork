package com.example.taczmeshloader.tacz;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-gun presentation extras, embedded as the top-level {@code "extras"} object of a
 * gun's geo model JSON ({@code geo_models/<...>.json}).
 *
 * <p>Everything gun-specific lives in the gunpack JSON so the mod stays generic:
 * <ul>
 *   <li>{@code inspect_hide} – geo bones flagged {@code "inspect_hide": true} are
 *       suppressed while the inspect animation progress is inside {@code [from, to]}.</li>
 *   <li>{@code fx} – per-shot particles anchored to named bones during inspect
 *       (jet exhaust + missile smoke).</li>
 *   <li>{@code reload_eject} – when an action is replayed and its progress enters
 *       {@code [from, to]}, throw one real casing ({@code action}, window and
 *       {@code velocity} are all per-gun data).</li>
 *   <li>{@code rig} – native rig presentation: animation name mapping, anchor bone
 *       chain, fire stretch, per-part visibility rules.</li>
 * </ul>
 * A missing (or absent) section simply leaves the matching feature disabled.
 */
public class GunConfig {

    // ---- mesh path: inspect-hide window ----
    public boolean hasInspectHide = false;
    public float inspectHideFrom = -1f;
    public float inspectHideTo = -1f;

    // ---- mesh path: inspect particles (jet exhaust / missile smoke) ----
    public boolean fxEnabled = false;
    public float jetFrom = 2.4f, jetTo = 6.1f;
    public float jetInterval = 0.05f;
    public int jetSmokeEvery = 3;
    public String jetLeftBone = "", jetRightBone = "";
    public int jetPerBatch = 2;
    public double jetSpeed = 0.02;
    public float missileRightFrom = 4.2f, missileRightTo = 5.4f;
    public float missileLeftFrom = 4.6f, missileLeftTo = 5.8f;
    public float missileInterval = 0.04f;
    public int missileCloudEvery = 4;
    public String missileLeftBone = "", missileRightBone = "";

    // ---- rig path: presentation meta ----
    public final Map<String, String> rigActionMap = new LinkedHashMap<>();
    public final List<String> rigAnchorPath = new ArrayList<>();
    public float rigFireSeconds = -1f;
    public float rigShootSeconds = -1f;
    public final List<PartVisibility> rigParts = new ArrayList<>();

    // ---- mesh path: star-flow overlay (data-driven; geo bones flagged "starflow": true) ----
    /** 流动纹理叠加层：关光照 additive 滚动纹理，用于"星空流动"类皮肤。仅当 geo 里有 starflow 骨骼时生效。 */
    public boolean starflowEnabled = false;
    public String starflowTexture = "";
    public float starflowSpeedU = 0.02f;
    public float starflowSpeedV = 0.0f;
    public float starflowScaleU = 1.0f;
    public float starflowScaleV = 1.0f;
    public float starflowTintR = 1.0f, starflowTintG = 1.0f, starflowTintB = 1.0f;
    public float starflowAlpha = 1.0f;

    // ---- mesh path: light limit（光照上限；-1 = 不限制）----
    /**
     * 把多边形网格吃到的天空光/方块光各自压到上限。用途：枪贴在独立纹理上渲染，
     * 光影包（Iris/Oculus 的 PBR 只认图集 sprite）拿不到我们的 _s/_n，会按"默认光滑材质"处理，
     * 于是阳光直射时整枪过曝刺眼。压住天空光上限即可从根上避免过曝，且不依赖任何光影包。
     * <p>{@code keep} = 超过上限那一截的保留比例（0 = 硬截断；0.4 = 直射 15 压到约 12 而低光不动）。</p>
     */
    public int lightSkyCap = -1;
    public int lightBlockCap = -1;
    public float lightSkyKeep = 0f;
    public float lightBlockKeep = 0f;

    // ---- mesh path: emissive overlay（自发光层，不依赖光影；UE 的 Emissive_map 对应）----
    public boolean emissiveEnabled = false;
    public String emissiveTexture = "";
    public float emissiveTintR = 1.0f, emissiveTintG = 1.0f, emissiveTintB = 1.0f;
    public float emissiveAlpha = 1.0f;
    public float emissivePulseHz = 0.0f;     // >0 时按该频率呼吸
    public float emissivePulseAmt = 0.0f;    // 呼吸幅度 0..1

    /** 皮肤表（geo extras "skins"）：id → {texture, starflow?}。id 与枪 NBT 里存的选择对应。
     *  未配置 = 只有默认皮肤（沿用 display 的 texture）。 */
    public static class Skin {
        public String id = "";
        public String texture = "";
        public boolean starflow = false;
        public String starflowTexture = "";
        public float starflowSpeedU = 0.035f;
        public float starflowSpeedV = 0.012f;
        public float[] starflowTint = {0.75f, 1.0f, 1.35f};
        public float starflowAlpha = 1.0f;
        /** 该皮肤可选的自发光层覆盖（缺省=用 geo 顶层 emissive 配置） */
        public boolean emissiveSet = false;
        public String emissiveTexture = "";
        public float emissiveTintR = 1f, emissiveTintG = 1f, emissiveTintB = 1f;
        public float emissiveAlpha = 1f;
        public float emissivePulseHz = 0f;
        public float emissivePulseAmt = 0f;
    }
    public final java.util.List<Skin> skins = new java.util.ArrayList<>();
    /** 存皮肤选择的 NBT 键名（默认 MeshSkin）。 */
    public String skinNbtKey = "MeshSkin";

    // ---- mesh path: shot FX (data-driven; 枪口闪电迸发 + 枪口→命中点闪电链) ----
    public boolean shotFxEnabled = false;
    public String shotFxMuzzleBone = "";
    public float shotFxAimThreshold = 0.5f;
    public String shotFxHipBurstParticle = "minecraft:electric_spark";
    public int shotFxHipBurstCount = 24;
    public double shotFxHipBurstSpeed = 0.35;
    public String shotFxChainParticle = "minecraft:electric_spark";
    public double shotFxChainSpacing = 0.35;
    public int shotFxChainWaves = 4;
    public double shotFxChainRange = 96.0;
    public double shotFxChainJitter = 0.06;

    // ---- mesh path: reload bolt-cycle shell eject ----
    public String reloadEjectAction = "";
    public float reloadEjectFrom = 0f, reloadEjectTo = 0f;
    public float reloadEjectVx = 0f, reloadEjectVy = 0f, reloadEjectVz = 0f;

    public static class Window {
        public String action = "";
        public float from;
        public float to;

        public static Window fromJson(JsonObject o) {
            Window w = new Window();
            w.action = o.get("action").getAsString();
            w.from = o.get("from").getAsFloat();
            w.to = o.get("to").getAsFloat();
            return w;
        }
    }

    public static class PartVisibility {
        public String bone = "";
        public boolean defaultHidden = false;
        public final List<Window> showWindows = new ArrayList<>();
        public final List<Window> hideWindows = new ArrayList<>();
    }

    private static float optFloat(JsonObject o, String key, float dflt) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsFloat() : dflt;
    }

    private static float fxBound(float value, float min, float max, float fallback) {
        return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : fallback;
    }

    private static int optInt(JsonObject o, String key, int dflt) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsInt() : dflt;
    }

    private static String optString(JsonObject o, String key, String dflt) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : dflt;
    }

    /**
     * 读曲线数组：{@code [[t, v…], …]} → {@code float[n][1+k]}。
     * 每行首列是时间 t（0..1 归一化寿命），其余列是值（size 曲线 1 列、color 3 列、alpha 1 列）。
     * 解析后按 t 升序排序——曲线求值假定递增。
     */
    private static float[][] optCurve(JsonObject o, String key, float[][] dflt) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonArray()) return dflt;
        JsonArray rows = e.getAsJsonArray();
        java.util.List<float[]> out = new java.util.ArrayList<>();
        int width = -1;
        for (JsonElement r : rows) {
            if (!r.isJsonArray()) continue;
            JsonArray a = r.getAsJsonArray();
            if (width < 0) width = a.size();
            if (a.size() != width || width < 2) continue;
            float[] row = new float[width];
            for (int i = 0; i < width; i++) row[i] = a.get(i).getAsFloat();
            out.add(row);
        }
        if (out.isEmpty()) return dflt;
        out.sort(java.util.Comparator.comparingDouble(r -> r[0]));
        return out.toArray(new float[0][]);
    }

    private static float[][] optVectorCurve(JsonObject o, String key) {
        float[][] curve = optCurve(o, key, null);
        if (curve == null) return null;
        float last = -Float.MAX_VALUE;
        for (float[] row : curve) {
            if (row.length < 4 || row[0] <= last) return null;
            for (float value : row) if (!Float.isFinite(value)) return null;
            last = row[0];
        }
        return curve;
    }

    private static void parseParts(JsonArray partsArr, GunConfig cfg) {
        if (partsArr == null) return;
        for (JsonElement el : partsArr) {
            JsonObject o = el.getAsJsonObject();
            PartVisibility p = new PartVisibility();
            p.bone = o.get("bone").getAsString();
            p.defaultHidden = o.has("default_hidden") && o.get("default_hidden").getAsBoolean();
            JsonArray show = o.getAsJsonArray("show_windows");
            if (show != null) for (JsonElement s : show) p.showWindows.add(Window.fromJson(s.getAsJsonObject()));
            JsonArray hide = o.getAsJsonArray("hide_windows");
            if (hide != null) for (JsonElement s : hide) p.hideWindows.add(Window.fromJson(s.getAsJsonObject()));
            cfg.rigParts.add(p);
        }
    }


    /**
     * Parse optional per-gun extras embedded as the top-level {@code "extras"} object
     * of a geo model JSON (single source of truth; nothing extra to mount).
     * Returns {@code null} when absent.
     */
    @Nullable
    public static GunConfig fromGeo(JsonObject geoRoot) {
        if (geoRoot == null || !geoRoot.has("extras")) return null;
        JsonElement ex = geoRoot.get("extras");
        if (ex == null || !ex.isJsonObject()) return null;
        return parse(ex.getAsJsonObject());
    }

    // =====================================================================
    // fx.emitters：UE(Cascade) 粒子发射器子集（数据驱动，见 render.FxParticles）
    // =====================================================================

    /**
     * 一个粒子发射器。字段刻意对齐 UE Cascade 的模块语义：
     * {@code sizeCurve} 对应 SizeMultiplyLife、{@code colorCurve}/{@code alphaCurve} 对应
     * ColorOverLife/AlphaOverLife、{@code life} 对应 Lifetime、{@code velocity} 对应
     * InitialVelocity（这里只做"随机三维速度"的简化）。
     * 尺寸单位是**MC 像素**（1px = 1/16 方块）——UE 的 cm 由作者换算后写进来，运行时不再猜单位。
     */
    public static final class FxEmitter {
        public String name = "";
        /** 挂载骨名（用渲染瞬间捕获的骨矩阵取世界坐标，必须与画面上同点）。 */
        public String bone = "";
        /** 贴图 id（如 example:attachment/uv/fx/xxx），运行时过 IDisplay.converter 转文件路径。 */
        public String texture = "";
        /** additive | alpha（默认 additive：UE 特效多数是加法混合）。 */
        public String blend = "additive";
        /** Explicit source shader operation: raw, red_opacity, or power_subuv. */
        public String material = "";
        public String texture2 = "", texture3 = "", texture4 = "", texture5 = "";
        public com.example.taczmeshloader.render.FxMesh mesh;
        public float[] colorScaleMin = {1,1,1}, colorScaleMax = {1,1,1};
        public float[][] alphaScaleCurve = null;
        public float[][] alphaScaleCurveMin = null, alphaScaleCurveMax = null;
        public org.joml.Matrix4f motionBasis;
        public float sourceUnit = 1f;
        public float[] velocityMin, velocityMax, worldAccelerationMin, worldAccelerationMax;
        public float[][] sizeSamples;
        public boolean velocityAlign, sizeYFromX, subRandom;
        public float textureScale = 1f, texturePower = 1f;
        /** shot（开火瞬间爆发）| always（常驻按 rate 发射）。 */
        public String trigger = "shot";
        /** Exact animation aliases and optional skin filter for melee notifications. */
        public final java.util.List<String> animations = new java.util.ArrayList<>();
        public final java.util.List<String> skins = new java.util.ArrayList<>();
        public boolean enabled = true;
        /** 秒；延迟不占粒子可见寿命。 */
        public float delay = 0f;
        /** Source emitter duration; zero retains a single burst. */
        public float duration = 0f;
        /** Used only by explicit held emitters: zero loops means infinite. */
        public int emitterLoops = 0;
        public boolean delayFirstLoopOnly = false;
        public float[][] bursts = null;
        /** Animation notification time (seconds), used by trigger="inspect". */
        public float at = 0f;
        /** UE rotation and angular speed are expressed in turns, not radians. */
        public float rotationMin = 0f, rotationMax = 1f;
        public float rotationRateMin = Float.NaN, rotationRateMax = Float.NaN;
        /** 静止枪口粒子跟随挂点；不等同于完整 UE 局部空间速度模拟。 */
        public boolean followBone = false;
        /** 方块/秒²；旧配置保留 0.6，新源数据可显式设 0。 */
        public float gravity = 0.6f;
        public int count = 8;               // shot：每次开火撒几个
        public float rate = 0f;             // always：每秒几个
        public float lifeMin = 0.12f, lifeMax = 0.2f;      // 秒
        public float sizeMin = 8f, sizeMax = 8f;           // MC 像素（边长）
        public float sizeYMin = -1f, sizeYMax = -1f;
        public float sizeMultiplierMin = 1f, sizeMultiplierMax = 1f;
        public float[][] sizeYCurve = null;
        public float[][] cameraOffsetCurve = null, velocityLifeCurve = null, locationCurve = null;
        public float[] locationMin, locationMax;
        public float[] orbitOffsetMin, orbitOffsetMax;
        public boolean velocityLifeAbsolute = false;
        public float cameraOffset = 0f;
        /** 尺寸随寿命倍率曲线 [t, mult] …（默认线性收小）。 */
        public float[][] sizeCurve = {{0f, 1f}, {1f, 0f}};
        /** 颜色随寿命 [t, r, g, b] …（0..1，超 1 会被夹到 1）。 */
        public float[][] colorCurve = {{0f, 1f, 1f, 1f}, {1f, 1f, 1f, 1f}};
        /** 透明度随寿命 [t, a] …。 */
        public float[][] alphaCurve = {{0f, 1f}, {1f, 0f}};
        /** 随机初速范围（方块/秒，三维同范围；UE 里是 cm/s，作者换算后写入）。 */
        public float velMin = 0f, velMax = 0f;
        /** 是否随机滚转（0=不转）。 */
        public float spinMax = 0f;
        /**
         * 发射点微调（相机空间：x=右、y=上、z=深度；枪口骨在 z 上是负向前）。
         * 用于把火线精确压到画面上看到的枪口——骨枢轴与枪管口常有几像素差。
         */
        public float offX = 0f, offY = 0f, offZ = 0f;
        /** Offsets in the captured bone frame, in blocks; legacy camera offsets remain default. */
        public boolean boneSpace = false;
        public boolean killOnDeactivate = false;
        public boolean textureSrgb = false;
        /** SubUV 序列帧：贴图按 subCols×subRows 切格，粒子按寿命推进帧号（1×1=普通单图）。 */
        public int subCols = 1, subRows = 1;
        public float[][] subImageCurve = null;
        /** 加法材质用两个加权四边形混合相邻帧。 */
        public boolean subBlend = false;
        /** 沿粒子自身轴拉伸倍率（1=方片；>1 = 细长电丝/光束状，原版枪口电丝靠它）。 */
        public float aspect = 1f;
        /** true = 开镜(ADS)时不出/不画（用户 2026-09-13：开镜第一人称不能看见枪口闪电）。 */
        public boolean hideWhenAiming = false;
        /** true = **只在**开镜(ADS)时出（对应原版"狙击模式专用枪口特效"；ads:"hide" 是腰射专用）。 */
        public boolean onlyWhenAiming = false;
        /** 发射器类型："sprite"(默认)＝面向相机的公告板；"beam"＝从枪口沿射击方向拉出的细长光带
         *  （Cascade Beam2 的几何近似；并发束数量须按 MaxBeamCount 另行映射）。 */
        public String type = "sprite";
        /** beam 专用：光带长度（px）。 */
        public float beamLength = 48f;
        /** beam 专用：分段数（分段各自用 u∈[0,1]，避免贴图 CLAMP 让平铺失效）。 */
        public int beamSegments = 5;
    }

    /** extras.fx.emitters 解析结果（空 = 该枪没有粒子特效，运行时完全不进入）。 */
    public final java.util.List<FxEmitter> fxEmitters = new java.util.ArrayList<>();

    public static GunConfig parse(JsonObject root) {
        GunConfig cfg = new GunConfig();
        // mesh path
        if (root.has("light_limit")) {
            JsonObject ll = root.getAsJsonObject("light_limit");
            cfg.lightSkyCap = optInt(ll, "sky", -1);
            cfg.lightBlockCap = optInt(ll, "block", -1);
            cfg.lightSkyKeep = optFloat(ll, "keep", 0f);
            cfg.lightBlockKeep = optFloat(ll, "keep", 0f);
        }
        if (root.has("inspect_hide")) {
            JsonObject ih = root.getAsJsonObject("inspect_hide");
            cfg.hasInspectHide = true;
            cfg.inspectHideFrom = optFloat(ih, "from", 1.4f);
            cfg.inspectHideTo = optFloat(ih, "to", 6.2f);
        }
        if (root.has("fx")) {
            JsonObject fx = root.getAsJsonObject("fx");
            cfg.fxEnabled = !fx.has("enabled") || fx.get("enabled").getAsBoolean();
            if (fx.has("jets")) {
                JsonObject j = fx.getAsJsonObject("jets");
                cfg.jetFrom = optFloat(j, "from", cfg.jetFrom);
                cfg.jetTo = optFloat(j, "to", cfg.jetTo);
                cfg.jetInterval = optFloat(j, "interval", cfg.jetInterval);
                cfg.jetSmokeEvery = optInt(j, "smoke_every", cfg.jetSmokeEvery);
                cfg.jetLeftBone = optString(j, "left_bone", cfg.jetLeftBone);
                cfg.jetRightBone = optString(j, "right_bone", cfg.jetRightBone);
                cfg.jetPerBatch = optInt(j, "per_batch", cfg.jetPerBatch);
                cfg.jetSpeed = optFloat(j, "speed", (float) cfg.jetSpeed);
            }
            if (fx.has("missiles")) {
                JsonObject m = fx.getAsJsonObject("missiles");
                cfg.missileLeftBone = optString(m, "left_bone", cfg.missileLeftBone);
                cfg.missileRightBone = optString(m, "right_bone", cfg.missileRightBone);
                cfg.missileLeftFrom = optFloat(m, "left_from", cfg.missileLeftFrom);
                cfg.missileLeftTo = optFloat(m, "left_to", cfg.missileLeftTo);
                cfg.missileRightFrom = optFloat(m, "right_from", cfg.missileRightFrom);
                cfg.missileRightTo = optFloat(m, "right_to", cfg.missileRightTo);
                cfg.missileInterval = optFloat(m, "interval", cfg.missileInterval);
                cfg.missileCloudEvery = optInt(m, "cloud_every", cfg.missileCloudEvery);
            }
            // 粒子发射器（UE 特效移植）：extras.fx.emitters = [ {…}, … ]
            if (fx.has("emitters") && fx.get("emitters").isJsonArray()) {
                for (JsonElement el : fx.getAsJsonArray("emitters")) {
                    if (!el.isJsonObject()) continue;
                    JsonObject o = el.getAsJsonObject();
                    FxEmitter em = new FxEmitter();
                    em.name = optString(o, "name", "");
                    em.bone = optString(o, "bone", "");
                    em.texture = optString(o, "texture", "");
                    em.blend = optString(o, "blend", em.blend);
                    em.material = optString(o, "material", "");
                    em.texture2 = optString(o, "texture2", "");
                    em.texture3 = optString(o, "texture3", "");
                    em.texture4 = optString(o, "texture4", "");
                    em.texture5 = optString(o, "texture5", "");
                    try {
                        if (o.has("mesh")) em.mesh = com.example.taczmeshloader.render.FxMesh.parse(o.getAsJsonObject("mesh"), "held".equals(optString(o, "trigger", "shot")));
                        em.colorScaleMin = com.example.taczmeshloader.render.FxMesh.vector(o, "color_scale_min", 1f);
                        em.colorScaleMax = o.has("color_scale_max")
                                ? com.example.taczmeshloader.render.FxMesh.vector(o, "color_scale_max", 1f) : em.colorScaleMin;
                        if (o.has("motion_basis")) em.motionBasis = com.example.taczmeshloader.render.FxMesh.parseBasis(o.getAsJsonArray("motion_basis"));
                        if (o.has("velocity_min")) {
                            em.velocityMin = com.example.taczmeshloader.render.FxMesh.vector(o, "velocity_min", 0f);
                            em.velocityMax = com.example.taczmeshloader.render.FxMesh.vector(o, "velocity_max", 0f);
                        }
                        if (o.has("world_acceleration_min")) {
                            em.worldAccelerationMin = com.example.taczmeshloader.render.FxMesh.vector(o, "world_acceleration_min", 0f);
                            em.worldAccelerationMax = com.example.taczmeshloader.render.FxMesh.vector(o, "world_acceleration_max", 0f);
                        }
                    } catch (RuntimeException invalidMesh) { continue; }
                    em.sizeSamples = optCurve(o, "size_samples", null);
                    em.sourceUnit = fxBound(optFloat(o, "source_unit", 1f), 0.000001f, 1000f, 1f);
                    em.velocityAlign = o.has("velocity_align") && o.get("velocity_align").getAsBoolean();
                    em.sizeYFromX = o.has("size_y_from_x") && o.get("size_y_from_x").getAsBoolean();
                    em.subRandom = o.has("subuv_random") && o.get("subuv_random").getAsBoolean();
                    em.alphaScaleCurve = optCurve(o, "alpha_scale_curve", null);
                    em.alphaScaleCurveMin = optCurve(o, "alpha_scale_curve_min", null);
                    em.alphaScaleCurveMax = optCurve(o, "alpha_scale_curve_max", null);
                    em.textureScale = fxBound(optFloat(o, "texture_scale", 1f), 0f, 256f, 1f);
                    em.texturePower = fxBound(optFloat(o, "texture_power", 1f), 0.001f, 32f, 1f);
                    em.trigger = optString(o, "trigger", em.trigger);
                    for (String field : new String[]{"animations", "skins"}) {
                        if (o.has(field) && o.get(field).isJsonArray()) {
                            var values = "animations".equals(field) ? em.animations : em.skins;
                            for (JsonElement value : o.getAsJsonArray(field))
                                if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) values.add(value.getAsString());
                        }
                    }
                    em.enabled = !o.has("enabled") || o.get("enabled").getAsBoolean();
                    em.followBone = o.has("follow_bone") && o.get("follow_bone").getAsBoolean();
                    em.boneSpace = o.has("bone_space") && o.get("bone_space").getAsBoolean();
                    em.killOnDeactivate = o.has("kill_on_deactivate") && o.get("kill_on_deactivate").getAsBoolean();
                    em.textureSrgb = o.has("texture_srgb") && o.get("texture_srgb").getAsBoolean();
                    em.delay = fxBound(optFloat(o, "delay", 0f), 0f, 10f, 0f);
                    em.duration = fxBound(optFloat(o, "duration", 0f), 0f, 10f, 0f);
                    em.emitterLoops = Math.max(0, optInt(o, "emitter_loops", 0));
                    em.delayFirstLoopOnly = o.has("delay_first_loop_only") && o.get("delay_first_loop_only").getAsBoolean();
                    if ("held".equals(em.trigger) && em.duration <= 0f) em.enabled = false;
                    em.at = fxBound(optFloat(o, "at", 0f), 0f, 120f, 0f);
                    em.bursts = optCurve(o, "bursts", null);
                    if (o.has("rotation") && o.get("rotation").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("rotation");
                        if (a.size() > 0) em.rotationMin = fxBound(a.get(0).getAsFloat(), -100f, 100f, 0f);
                        em.rotationMax = a.size() > 1 ? fxBound(a.get(1).getAsFloat(), -100f, 100f, em.rotationMin) : em.rotationMin;
                    }
                    if (o.has("rotation_rate") && o.get("rotation_rate").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("rotation_rate");
                        if (a.size() > 0) em.rotationRateMin = fxBound(a.get(0).getAsFloat(), -100f, 100f, 0f);
                        em.rotationRateMax = a.size() > 1 ? fxBound(a.get(1).getAsFloat(), -100f, 100f, em.rotationRateMin) : em.rotationRateMin;
                    }
                    em.gravity = fxBound(optFloat(o, "gravity", em.gravity), -100f, 100f, 0.6f);
                    em.count = Math.max(0, Math.min(128, optInt(o, "count", em.count)));
                    em.rate = fxBound(optFloat(o, "rate", em.rate), 0f, 128f, 0f);
                    if (o.has("life") && o.get("life").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("life");
                        em.lifeMin = a.get(0).getAsFloat();
                        em.lifeMax = a.size() > 1 ? a.get(1).getAsFloat() : em.lifeMin;
                    }
                    if (o.has("size") && o.get("size").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("size");
                        em.sizeMin = a.get(0).getAsFloat();
                        em.sizeMax = a.size() > 1 ? a.get(1).getAsFloat() : em.sizeMin;
                    }
                    em.sizeCurve = optCurve(o, "size_curve", em.sizeCurve);
                    em.sizeYCurve = optCurve(o, "size_y_curve", null);
                    if (o.has("location_min") && o.has("location_max")) {
                        em.locationMin = com.example.taczmeshloader.render.FxMesh.vector(o, "location_min", 0f);
                        em.locationMax = com.example.taczmeshloader.render.FxMesh.vector(o, "location_max", 0f);
                    }
                    if (o.has("orbit_offset_min") && o.has("orbit_offset_max")) {
                        em.orbitOffsetMin = com.example.taczmeshloader.render.FxMesh.vector(o, "orbit_offset_min", 0f);
                        em.orbitOffsetMax = com.example.taczmeshloader.render.FxMesh.vector(o, "orbit_offset_max", 0f);
                    }
                    em.cameraOffsetCurve = optCurve(o, "camera_offset_curve", null);
                    em.velocityLifeCurve = optVectorCurve(o, "velocity_life_curve");
                    em.locationCurve = optVectorCurve(o, "location_curve");
                    em.velocityLifeAbsolute = o.has("velocity_life_absolute") && o.get("velocity_life_absolute").getAsBoolean();
                    if (o.has("size_multiplier") && o.get("size_multiplier").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("size_multiplier");
                        if (a.size() > 0) em.sizeMultiplierMin = fxBound(a.get(0).getAsFloat(), 0f, 100f, 1f);
                        em.sizeMultiplierMax = a.size() > 1 ? fxBound(a.get(1).getAsFloat(), 0f, 100f, em.sizeMultiplierMin) : em.sizeMultiplierMin;
                    }
                    em.cameraOffset = fxBound(optFloat(o, "camera_offset", 0f), -10f, 10f, 0f);
                    if (o.has("size_y") && o.get("size_y").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("size_y");
                        if (a.size() > 0) em.sizeYMin = fxBound(a.get(0).getAsFloat(), 0f, 1000f, 0f);
                        em.sizeYMax = a.size() > 1 ? fxBound(a.get(1).getAsFloat(), 0f, 1000f, em.sizeYMin) : em.sizeYMin;
                    }
                    em.colorCurve = optCurve(o, "color_curve", em.colorCurve);
                    em.alphaCurve = optCurve(o, "alpha_curve", em.alphaCurve);
                    em.subImageCurve = optCurve(o, "sub_image_curve", null);
                    em.subBlend = o.has("subuv_blend") && o.get("subuv_blend").getAsBoolean();
                    if (o.has("velocity") && o.get("velocity").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("velocity");
                        em.velMin = a.get(0).getAsFloat();
                        em.velMax = a.size() > 1 ? a.get(1).getAsFloat() : em.velMin;
                    }
                    em.spinMax = optFloat(o, "spin", em.spinMax);
                    em.aspect = Math.max(0.05f, optFloat(o, "aspect", em.aspect));
                    if (o.has("subuv") && o.get("subuv").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("subuv");
                        if (a.size() >= 2) {
                            em.subCols = Math.max(1, Math.min(16, a.get(0).getAsInt()));
                            em.subRows = Math.max(1, Math.min(16, a.get(1).getAsInt()));
                        }
                    }
                    em.type = optString(o, "type", "sprite");
                    if (o.has("length")) em.beamLength = (float) o.get("length").getAsDouble();
                    if (o.has("segments")) em.beamSegments = Math.max(1, Math.min(32, o.get("segments").getAsInt()));
                    em.lifeMin = fxBound(em.lifeMin, 0.005f, 10f, 0.12f);
                    em.lifeMax = fxBound(em.lifeMax, em.lifeMin, 10f, em.lifeMin);
                    String adsMode = optString(o, "ads", "show");
                    em.hideWhenAiming = "hide".equalsIgnoreCase(adsMode);      // 腰射专用
                    em.onlyWhenAiming = "only".equalsIgnoreCase(adsMode);      // 开镜专用
                    if (o.has("offset") && o.get("offset").isJsonArray()) {
                        JsonArray a = o.getAsJsonArray("offset");
                        if (a.size() >= 3) {
                            em.offX = a.get(0).getAsFloat();
                            em.offY = a.get(1).getAsFloat();
                            em.offZ = a.get(2).getAsFloat();
                        }
                    }
                    if (!em.bone.isEmpty() && !em.texture.isEmpty()) cfg.fxEmitters.add(em);
                }
            }
        }
        // rig path
        if (root.has("rig")) {
            JsonObject rig = root.getAsJsonObject("rig");
            if (rig.has("action_map")) {
                JsonObject am = rig.getAsJsonObject("action_map");
                for (Map.Entry<String, JsonElement> e : am.entrySet()) {
                    cfg.rigActionMap.put(e.getKey(), e.getValue().getAsString());
                }
            }
            if (rig.has("anchor_path")) {
                for (JsonElement e : rig.getAsJsonArray("anchor_path")) {
                    cfg.rigAnchorPath.add(e.getAsString());
                }
            }
            cfg.rigFireSeconds = optFloat(rig, "fire_seconds", cfg.rigFireSeconds);
            cfg.rigShootSeconds = optFloat(rig, "shoot_seconds", cfg.rigShootSeconds);
            parseParts(rig.getAsJsonArray("parts"), cfg);
        }
        // skins path（皮肤表：一把枪多套贴图/流动纹理，靠枪 NBT 选择）
        if (root.has("skins") && root.get("skins").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("skins")) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                Skin sk = new Skin();
                sk.id = optString(o, "id", "");
                sk.texture = optString(o, "texture", "");
                sk.starflow = o.has("starflow");
                if (sk.starflow && o.get("starflow").isJsonObject()) {
                    JsonObject sf = o.getAsJsonObject("starflow");
                    sk.starflowTexture = optString(sf, "texture", "");
                    sk.starflowSpeedU = optFloat(sf, "speed_u", sk.starflowSpeedU);
                    sk.starflowSpeedV = optFloat(sf, "speed_v", sk.starflowSpeedV);
                    sk.starflowAlpha = optFloat(sf, "alpha", sk.starflowAlpha);
                    if (sf.has("tint") && sf.get("tint").isJsonArray()) {
                        JsonArray t = sf.getAsJsonArray("tint");
                        if (t.size() >= 3) {
                            sk.starflowTint = new float[]{t.get(0).getAsFloat(), t.get(1).getAsFloat(), t.get(2).getAsFloat()};
                        }
                    }
                }
                if (o.has("emissive") && o.get("emissive").isJsonObject()) {
                    JsonObject em = o.getAsJsonObject("emissive");
                    sk.emissiveSet = true;
                    sk.emissiveTexture = optString(em, "texture", "");
                    sk.emissiveAlpha = optFloat(em, "alpha", 1.0f);
                    sk.emissivePulseHz = optFloat(em, "pulse_hz", 0f);
                    sk.emissivePulseAmt = optFloat(em, "pulse_amount", 0f);
                    if (em.has("tint") && em.get("tint").isJsonArray()) {
                        JsonArray t = em.getAsJsonArray("tint");
                        if (t.size() >= 3) {
                            sk.emissiveTintR = t.get(0).getAsFloat();
                            sk.emissiveTintG = t.get(1).getAsFloat();
                            sk.emissiveTintB = t.get(2).getAsFloat();
                        }
                    }
                }
                if (!sk.id.isEmpty()) cfg.skins.add(sk);
            }
            cfg.skinNbtKey = optString(root, "skin_nbt_key", cfg.skinNbtKey);
        }
        // shot-fx path（开火特效：腰射枪口迸发 + 弹道闪电链；两种模式都放链）
        if (root.has("shot_fx")) {
            JsonObject sf = root.getAsJsonObject("shot_fx");
            cfg.shotFxEnabled = !sf.has("enabled") || sf.get("enabled").getAsBoolean();
            cfg.shotFxMuzzleBone = optString(sf, "muzzle_bone", cfg.shotFxMuzzleBone);
            cfg.shotFxAimThreshold = optFloat(sf, "aim_threshold", cfg.shotFxAimThreshold);
            cfg.shotFxHipBurstParticle = optString(sf, "hip_particle", cfg.shotFxHipBurstParticle);
            cfg.shotFxHipBurstCount = optInt(sf, "hip_count", cfg.shotFxHipBurstCount);
            cfg.shotFxHipBurstSpeed = optFloat(sf, "hip_speed", (float) cfg.shotFxHipBurstSpeed);
            cfg.shotFxChainParticle = optString(sf, "chain_particle", cfg.shotFxChainParticle);
            cfg.shotFxChainSpacing = optFloat(sf, "chain_spacing", (float) cfg.shotFxChainSpacing);
            cfg.shotFxChainWaves = optInt(sf, "chain_waves", cfg.shotFxChainWaves);
            cfg.shotFxChainRange = optFloat(sf, "chain_range", (float) cfg.shotFxChainRange);
            cfg.shotFxChainJitter = optFloat(sf, "chain_jitter", (float) cfg.shotFxChainJitter);
        }
        // emissive overlay path（自发光层：additive + 全亮，用 材质的 Emissive_map）
        if (root.has("emissive")) {
            JsonObject em = root.getAsJsonObject("emissive");
            cfg.emissiveEnabled = !em.has("enabled") || em.get("enabled").getAsBoolean();
            cfg.emissiveTexture = optString(em, "texture", "");
            cfg.emissiveAlpha = optFloat(em, "alpha", cfg.emissiveAlpha);
            cfg.emissivePulseHz = optFloat(em, "pulse_hz", cfg.emissivePulseHz);
            cfg.emissivePulseAmt = optFloat(em, "pulse_amount", cfg.emissivePulseAmt);
            if (em.has("tint") && em.get("tint").isJsonArray()) {
                JsonArray t = em.getAsJsonArray("tint");
                if (t.size() >= 3) {
                    cfg.emissiveTintR = t.get(0).getAsFloat();
                    cfg.emissiveTintG = t.get(1).getAsFloat();
                    cfg.emissiveTintB = t.get(2).getAsFloat();
                }
            }
        }
        // star-flow overlay path（皮肤"星空流动"层：geo 里标 starflow 的骨 + 一张滚动叠加贴图）
        if (root.has("starflow")) {
            JsonObject sf = root.getAsJsonObject("starflow");
            cfg.starflowEnabled = !sf.has("enabled") || sf.get("enabled").getAsBoolean();
            cfg.starflowTexture = optString(sf, "texture", "");
            cfg.starflowSpeedU = optFloat(sf, "speed_u", cfg.starflowSpeedU);
            cfg.starflowSpeedV = optFloat(sf, "speed_v", cfg.starflowSpeedV);
            cfg.starflowScaleU = optFloat(sf, "scale_u", cfg.starflowScaleU);
            cfg.starflowScaleV = optFloat(sf, "scale_v", cfg.starflowScaleV);
            cfg.starflowAlpha = optFloat(sf, "alpha", cfg.starflowAlpha);
            if (sf.has("tint") && sf.get("tint").isJsonArray()) {
                JsonArray t = sf.getAsJsonArray("tint");
                if (t.size() >= 3) {
                    cfg.starflowTintR = t.get(0).getAsFloat();
                    cfg.starflowTintG = t.get(1).getAsFloat();
                    cfg.starflowTintB = t.get(2).getAsFloat();
                }
            }
        }
        // reload eject path（空仓换弹拉栓处抛真实弹壳）
        if (root.has("reload_eject")) {
            JsonObject re = root.getAsJsonObject("reload_eject");
            cfg.reloadEjectAction = optString(re, "action", "");
            cfg.reloadEjectFrom = optFloat(re, "from", 0f);
            cfg.reloadEjectTo = optFloat(re, "to", 0f);
            if (re.has("velocity") && re.get("velocity").isJsonArray()) {
                JsonArray v = re.getAsJsonArray("velocity");
                if (v.size() >= 3) {
                    cfg.reloadEjectVx = v.get(0).getAsFloat();
                    cfg.reloadEjectVy = v.get(1).getAsFloat();
                    cfg.reloadEjectVz = v.get(2).getAsFloat();
                }
            }
        }
        return cfg;
    }
}
