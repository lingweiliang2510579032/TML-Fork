package com.example.taczmeshloader.render;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.function.Function;

import static net.minecraft.Util.memoize;

/**
 * MeshyLoader が使用する RenderType 定義。
 *
 * translucentGlass: キャノピー・ガラス状の半透明メッシュ用。
 *   - Zテスト: LEQUAL（前後関係を尊重）
 *   - depthMask: 呼び出し元で false に設定（Z書き込みなし）
 *   - アルファブレンド: SRC_ALPHA / ONE_MINUS_SRC_ALPHA
 *   - 両面描画（カリングなし）
 */
@OnlyIn(Dist.CLIENT)
public final class MeshyRenderTypes extends RenderType {

    private MeshyRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode,
                              int bufferSize, boolean affectsCrumbling, boolean sortOnUpload,
                              Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setup, clear);
    }

    private static final Function<ResourceLocation, RenderType> TRANSLUCENT_GLASS =
            memoize(texture -> create(
                    "meshy_translucent_glass",
                    DefaultVertexFormat.NEW_ENTITY,
                    VertexFormat.Mode.QUADS,
                    256, false, true,
                    CompositeState.builder()
                            .setShaderState(RENDERTYPE_ENTITY_TRANSLUCENT_SHADER)
                            .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                            .setTransparencyState(TRANSLUCENT_TRANSPARENCY)
                            .setCullState(NO_CULL)
                            .setLightmapState(LIGHTMAP)
                            .setOverlayState(OVERLAY)
                            .setDepthTestState(LEQUAL_DEPTH_TEST)
                            .setWriteMaskState(COLOR_WRITE)
                            .createCompositeState(false)
            ));

    public static RenderType translucentGlass(ResourceLocation texture) {
        return TRANSLUCENT_GLASS.apply(texture);
    }

    // =========================================================================
    // flow-opaque（发光件本体：自己的贴图 + UV 滚动，不透光；按实体光照/贴图走）
    // =========================================================================

    private static final java.util.Map<String, RenderType> FLOW_OPAQUE_CACHE = new java.util.HashMap<>();

    /**
     * 不透光的"滚动"材质：与 {@code entityCutoutNoCull} 同一条渲染管线（NO_TRANSPARENCY + NO_CULL + LIGHTMAP），
     * 只多挂一个 {@code OffsetTexturingStateShard}（原版实体着色器都会把 TextureMat 乘到 UV 上 ⇒ 贴图滚动）。
     * 用途：发光件本体 —— 让**花纹本身滑动**，那才是肉眼看得见的"流光"。
     */
    public static RenderType flowOpaque(ResourceLocation texture, float u, float v) {
        float qu = Math.round(u * 1024f) / 1024f;
        float qv = Math.round(v * 1024f) / 1024f;
        String key = texture + "|" + qu + "|" + qv;
        RenderType cached = FLOW_OPAQUE_CACHE.get(key);
        if (cached != null) return cached;
        if (FLOW_OPAQUE_CACHE.size() > 4096) FLOW_OPAQUE_CACHE.clear();
        RenderType rt = create(
                "meshy_flow_opaque",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256, true, false,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTexturingState(new RenderStateShard.OffsetTexturingStateShard(qu, qv))
                        .setTransparencyState(NO_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setOverlayState(OVERLAY)
                        .createCompositeState(true));
        FLOW_OPAQUE_CACHE.put(key, rt);
        return rt;
    }

    // =========================================================================
    // star-flow overlay（皮肤"星空流动"：滚动 UV + additive + 不吃光照）
    // =========================================================================

    /**
     * 同一张贴图只建一次 RenderType；UV 滚动偏移由顶点 UV 承担，不进 key。
     */
    private static final java.util.Map<String, RenderType> STARFLOW_CACHE = new java.util.HashMap<>();

    /**
     * 流动纹理滚动偏移（**可变静态值**）：渲染方每次绘制前写入，{@link #STARFLOW_TEXTURING} 在
     * setupRenderState 时把它变成纹理矩阵。
     *
     * <p>★ 为什么这么做：偏移是**逐帧连续变化**的，若把它塞进 RenderType 的构造参数（旧写法）就会
     * 每帧新建一个 RenderType；若把它烘进顶点缓冲（也试过）就会每帧重建整个 VBO。两种都是"每帧重做一遍
     * 整枪的活"——流动材质掉帧（119→59）的根因就在这里。改成纹理矩阵后：**RenderType 只建一次、
     * VBO 只上传一次，偏移每帧只改一个 uniform**。</p>
     *
     * <p>只有 {@code energy_swirl} 这类着色器认 {@code TextureMat}（原版实体着色器不吃，实测过），
     * 所以这里保持 energy_swirl；观感与之前完全一致。</p>
     */
    private static volatile float starflowU = 0f, starflowV = 0f;

    public static void setStarflowOffset(float u, float v) {
        starflowU = u;
        starflowV = v;
    }

    private static final RenderStateShard.TexturingStateShard STARFLOW_TEXTURING =
            new RenderStateShard.TexturingStateShard("meshy_starflow_uv",
                    () -> com.mojang.blaze3d.systems.RenderSystem.setTextureMatrix(
                            new org.joml.Matrix4f().translation(starflowU, starflowV, 0f)),
                    com.mojang.blaze3d.systems.RenderSystem::resetTextureMatrix);

    private static RenderType buildStarflow(ResourceLocation texture) {
        return create(
                "meshy_starflow",
                DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS,
                256, false, true,
                CompositeState.builder()
                        .setShaderState(RENDERTYPE_ENERGY_SWIRL_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTexturingState(STARFLOW_TEXTURING)
                        .setTransparencyState(ADDITIVE_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setWriteMaskState(COLOR_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .setLightmapState(NO_LIGHTMAP)
                        .setOverlayState(NO_OVERLAY)
                        .createCompositeState(false));
    }

    /**
     * 流动纹理叠加材质。{@code u}/{@code v} = UV 偏移（随时间递增 ⇒ 纹理滚动）。
     * 用原版 energy swirl 着色器：反向滚动 + additive 发光，且不受世界光照影响。
     */
    public static RenderType starflow(ResourceLocation texture) {
        // 只按贴图缓存：偏移走纹理矩阵（见 setStarflowOffset），所以**不再进 key**。
        String key = texture.toString();
        RenderType cached = STARFLOW_CACHE.get(key);
        if (cached != null) return cached;
        RenderType rt = buildStarflow(texture);
        STARFLOW_CACHE.put(key, rt);
        return rt;
    }

    /** 兼容旧调用（偏移由渲染方 setStarflowOffset 设定，参数忽略）。 */
    public static RenderType starflow(ResourceLocation texture, float u, float v) {
        return starflow(texture);
    }

    // =========================================================================
    // fx sprite（UE 粒子移植：带贴图的公告板四边面）
    // =========================================================================

    /**
     * 粒子四边面：{@code POSITION_COLOR_TEX_LIGHTMAP} 顶点格式（顶点自带 RGBA 与光照），
     * 顶点光照写全亮、不写深度、可选加法混合 —— 与 UE 里 SpriteEmitter 的
     * Unlit + Additive 观感对齐。贴图 + 混合模式各缓存一份，避免逐帧 new。
     */
    private static final java.util.Map<String, RenderType> FX_SPRITE_CACHE = new java.util.HashMap<>();
    private static final java.util.Map<String, RenderType> FX_SOURCE_MESH_CACHE = new java.util.HashMap<>();

    private static RenderType buildFxSprite(ResourceLocation texture, boolean additive) {
        return create(
                additive ? "meshy_fx_additive" : "meshy_fx_alpha",
                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
                VertexFormat.Mode.QUADS,
                1536, false, true,
                CompositeState.builder()
                        .setShaderState(POSITION_COLOR_TEX_LIGHTMAP_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(texture, false, false))
                        .setTransparencyState(additive ? ADDITIVE_TRANSPARENCY : TRANSLUCENT_TRANSPARENCY)
                        .setCullState(NO_CULL)
                        .setLightmapState(LIGHTMAP)
                        .setWriteMaskState(COLOR_WRITE)
                        .setDepthTestState(LEQUAL_DEPTH_TEST)
                        .createCompositeState(false));
    }

    /** 粒子公告板材质（{@code additive=true} → ADDITIVE_TRANSPARENCY，否则普通半透明）。 */
    public static RenderType fxSprite(ResourceLocation texture, boolean additive) {
        String key = texture + (additive ? "|a" : "|t");
        RenderType rt = FX_SPRITE_CACHE.get(key);
        if (rt == null) {
            if (FX_SPRITE_CACHE.size() > 512) FX_SPRITE_CACHE.clear();
            rt = buildFxSprite(texture, additive);
            FX_SPRITE_CACHE.put(key, rt);
        }
        return rt;
    }

    public static RenderType fxSource(ResourceLocation texture, boolean additive, String operation, float scale, float power, boolean srgb) {
        String key = "source|" + texture + '|' + additive + '|' + operation + '|' + scale + '|' + power + '|' + srgb;
        return FX_SPRITE_CACHE.computeIfAbsent(key, ignored -> {
            int mode = "red_opacity".equals(operation) ? 1 : "power_subuv".equals(operation) ? 2
                    : "raw_opaque_alpha".equals(operation) ? 3 : 0;
            return create("meshy_fx_source", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                    1536, false, true, CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> FxSourceShader.get(additive, mode, scale, power, srgb)))
                    .setTextureState(new RenderStateShard.TextureStateShard(texture, true, false))
                    .setTransparencyState(additive ? ADDITIVE_TRANSPARENCY : TRANSLUCENT_TRANSPARENCY)
                    .setCullState(NO_CULL).setWriteMaskState(COLOR_WRITE).setDepthTestState(LEQUAL_DEPTH_TEST)
                    .createCompositeState(false));
        });
    }

    private static final java.util.Map<String, RenderType> FX_CASCADE_CACHE = new java.util.HashMap<>();
    private static final RenderStateShard.TransparencyStateShard FX_CASCADE_ADDITIVE = cascadeTransparency(true);
    private static final RenderStateShard.TransparencyStateShard FX_CASCADE_ALPHA = cascadeTransparency(false);

    private static RenderStateShard.TransparencyStateShard cascadeTransparency(boolean additive) {
        return new RenderStateShard.TransparencyStateShard("meshy_fx_cascade_blend", () ->
                FxShaderInstance.applyBlend(FxCascadeShader.isDistortionPass() ? FxShaderInstance.Blend.DISTORTION
                        : additive ? FxShaderInstance.Blend.ADDITIVE : FxShaderInstance.Blend.ALPHA), () -> {
                    com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                    com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
                });
    }

    /** Both setup and ShaderInstance.apply agree on the active source pass's blend contract. */
    public static RenderType fxCascade(ResourceLocation texture, ResourceLocation texture2, int operation, boolean additive, float sourceUnit) {
        return fxCascade(texture, texture2, texture, texture, texture, operation, additive, sourceUnit);
    }

    public static RenderType fxCascade(ResourceLocation texture, ResourceLocation texture2,
                                        ResourceLocation texture3, ResourceLocation texture4, ResourceLocation texture5,
                                        int operation, boolean additive, float sourceUnit) {
        String key = texture + "|" + texture2 + "|" + texture3 + "|" + texture4 + "|" + texture5
                + "|" + operation + "|" + additive + "|" + sourceUnit;
        RenderType cached = FX_CASCADE_CACHE.get(key);
        if (cached != null) return cached;
        if (FX_CASCADE_CACHE.size() > 512) FX_CASCADE_CACHE.clear();
        RenderType result = create("meshy_fx_cascade", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                // Additive particles only write color: their triangles need no distance sort.
                1536, false, !additive, CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(() -> FxCascadeShader.get(additive, operation, sourceUnit)))
                .setTextureState(RenderStateShard.MultiTextureStateShard.builder()
                        .add(texture, true, false).add(texture2, true, false)
                        .add(texture3, true, false).add(texture4, true, false).add(texture5, true, false).build())
                .setTransparencyState(additive ? FX_CASCADE_ADDITIVE : FX_CASCADE_ALPHA)
                .setCullState(NO_CULL).setLightmapState(NO_LIGHTMAP).setOverlayState(NO_OVERLAY)
                .setWriteMaskState(COLOR_WRITE).setDepthTestState(LEQUAL_DEPTH_TEST)
                .createCompositeState(false));
        FX_CASCADE_CACHE.put(key, result);
        return result;
    }

    /** Original ReleaseOfMagic graph: linear smoke/light/scanline textures, selected source blend. */
    public static RenderType fxSourceMesh(ResourceLocation smoke, ResourceLocation light, ResourceLocation scanline,
                                          boolean additive) {
        String key = smoke + "|" + light + "|" + scanline + "|" + additive;
        RenderType cached = FX_SOURCE_MESH_CACHE.get(key);
        if (cached != null) return cached;
        if (FX_SOURCE_MESH_CACHE.size() > 512) FX_SOURCE_MESH_CACHE.clear();
        RenderType result = create("meshy_fx_source_mesh", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
                1536, false, true, CompositeState.builder()
                .setShaderState(new RenderStateShard.ShaderStateShard(() ->
                        FxSourceShader.get(additive, FxSourceShader.RELEASE_OF_MAGIC, 1f, 1f, false)))
                .setTextureState(RenderStateShard.MultiTextureStateShard.builder()
                        .add(smoke, true, false).add(light, true, false).add(scanline, true, false).build())
                .setTransparencyState(additive ? ADDITIVE_TRANSPARENCY : TRANSLUCENT_TRANSPARENCY)
                .setCullState(NO_CULL).setLightmapState(NO_LIGHTMAP).setOverlayState(NO_OVERLAY)
                .setWriteMaskState(COLOR_WRITE).setDepthTestState(LEQUAL_DEPTH_TEST)
                .createCompositeState(false));
        FX_SOURCE_MESH_CACHE.put(key, result);
        return result;
    }
}
