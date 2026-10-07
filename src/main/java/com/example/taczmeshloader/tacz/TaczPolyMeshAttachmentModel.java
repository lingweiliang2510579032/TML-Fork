package com.example.taczmeshloader.tacz;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.api.IPolyMeshBone;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.tacz.guns.api.client.gameplay.IClientPlayerGunOperator;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.item.IAttachment;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.opengl.GL11;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@OnlyIn(Dist.CLIENT)
public class TaczPolyMeshAttachmentModel extends BedrockAttachmentModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation cachedTexture = null;
    private List<IPolyMeshBone> cachedRootChildren = null;

    /**
     * 皮肤表（附件 geo 顶层 extras {@code "skins"}：id → 贴图），**跟随所挂枪**的皮肤 NBT
     * （键名 extras {@code "skin_nbt_key"}，默认 {@code MeshSkin}）。
     *
     * <p>动机：镜/配件和枪是同一套外观，枪换皮时配件也必须跟着换；机制与枪身完全一致
     * （同一张 extras.skins 表、同一个 NBT 键）⇒ 通用，零品牌硬编码。
     * 不写 {@code skins} 的附件：{@link #skinNbtKey} 为 null，一切保持原样（仍用 display 的贴图）。</p>
     */
    private final Map<String, ResourceLocation> skinTextures = new java.util.HashMap<>();
    private String skinNbtKey = null;
    private String lastAttachSkinLogged = null;
    /** 当前渲染用的皮肤 id（流动纹理/逐骨贴图判定用） */
    private String curSkinId = "";

    /**
     * 流动纹理叠加层（皮肤"星空流动"）：与枪身**同一套数据格式**
     * （geo 顶层 {@code extras.starflow} 兜底 + {@code skins[].starflow} 逐皮肤覆盖），
     * 画在 geo 里标了 {@code "starflow": true} 的骨上（镜片不标 ⇒ 镜片不流动）。
     */
    private boolean sfEnabled = false;
    private String sfTexture = "";
    private float sfSpeedU = 0.02f, sfSpeedV = 0.0f;
    private float sfTintR = 1f, sfTintG = 1f, sfTintB = 1f, sfAlpha = 1f;
    private static final class SfCfg {
        boolean on; String tex = ""; float su, sv, tr, tg, tb, alpha;
    }
    private final Map<String, SfCfg> skinStarflow = new java.util.HashMap<>();
    private final java.util.Set<String> skinNoStarflow = new java.util.HashSet<>();

    /** geo 里的贴图 id → 纹理管理器要的完整路径（与枪身同一套转换）。 */
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
    /** 瞄具开/关镜音切换检测（上一次是否处于瞄准态）。
     *  <p>放在**静态**侧：附件模型实例可能被逐帧重建（实测日志每帧都报 last=false），
     *  实例字段留不住状态 ⇒ 每帧重复触发一次开镜音。静态字段按音源配置区分，换镜时自动重新对齐。
     *  与具体枪/镜无关：任何在 geo extras 里写了 ads_sound 的镜都适用。 */
    private static String lastAdsSoundKey = null;
    private static boolean lastScopeAiming = false;
    /** ADS 开/关镜音源 id，来自 scope geo 顶层 extras 的 "ads_sound"；空字符串=不播（保持通用）。 */
    private String adsSoundOpen = "";
    private String adsSoundClose = "";

    /** 光照上限（geo extras "light_limit"），-1 = 不限制；解析时先记下，new PolyMeshModel 之后再应用。 */
    private int pendingLightSkyCap = -1;
    private int pendingLightBlockCap = -1;
    private float pendingLightSkyKeep = 0f;
    private float pendingLightBlockKeep = 0f;

    /** 开镜时是否把镜体(scope_body)画在镜孔之外（原版语义）。默认 false=排除镜体，
     *  保持既有各镜的运行表现；需要该效果的镜在其 geo 顶层 extras 写 "ads_body":"show"。 */
    private boolean adsBodyVisible = false;
    /** 镜内合成(隐藏镜体多余件)的进度门：进入≥此值才切，退出<此值才恢复（滞回防穿帮） */
    private static final float ADS_ENTER_PROGRESS = 0.85f;
    private static final float ADS_EXIT_PROGRESS = 0.30f;
    private boolean adsCompositing = false;
    private final Set<String> ocularPolyMeshBoneNames = new HashSet<>();
    /** geo.json 中带 poly_mesh 的骨名（ADS 时整镜 mesh 排除用，封闭式镜体不能遮挡瞄准视野） */
    private final Set<String> polyMeshBoneNames = new HashSet<>();

    private static final java.lang.reflect.Field SCOPE_VIEW_RADIUS_FIELD;
    static {
        java.lang.reflect.Field f = null;
        try {
            f = BedrockAttachmentModel.class.getDeclaredField("scopeViewRadiusModifier");
            f.setAccessible(true);
        } catch (NoSuchFieldException ignored) {}
        SCOPE_VIEW_RADIUS_FIELD = f;
    }

    private float getScopeViewRadiusModifier() {
        if (SCOPE_VIEW_RADIUS_FIELD != null) {
            try { return SCOPE_VIEW_RADIUS_FIELD.getFloat(this); }
            catch (IllegalAccessException ignored) {}
        }
        return 1.0f;
    }

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    private static final java.nio.file.Path DBG_PATH =
            System.getProperty("tml.dumpPath") != null
                    ? java.nio.file.Paths.get(System.getProperty("tml.dumpPath"))
                    : null;

    private static void dumpMatrix(String tag, String tex, ItemDisplayContext ctx, PoseStack ps) {
        try {
            org.joml.Matrix4f m = ps.last().pose();
            MESH_LOG.info("[DBG] {} {} {}", tag, tex, ctx);
            String s = String.format("%s|%s|%s|%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f%n",
                    tag, tex, ctx,
                    m.m00(), m.m01(), m.m02(), m.m03(), m.m10(), m.m11(), m.m12(), m.m13(),
                    m.m20(), m.m21(), m.m22(), m.m23(), m.m30(), m.m31(), m.m32(), m.m33());
            java.nio.file.Files.write(DBG_PATH, s.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }

    private void dumpMeshPoints(Object texTag, PoseStack ps) {
        try {
            java.lang.reflect.Field pmf = getClass().getDeclaredField("polyMeshModel");
            pmf.setAccessible(true);
            Object pmm = pmf.get(this);
            if (pmm == null) return;
            java.lang.reflect.Field mapF = pmm.getClass().getDeclaredField("meshMap");
            mapF.setAccessible(true);
            java.util.Map<?, ?> mm = (java.util.Map<?, ?>) mapF.get(pmm);
            StringBuilder sb = new StringBuilder();
            for (Object v : mm.values()) {
                java.util.List<?> list = (java.util.List<?>) v;
                for (Object poly : list) {
                    Class<?> pc = poly.getClass();
                    java.lang.reflect.Field xf = pc.getDeclaredField("bakedX");
                    java.lang.reflect.Field yf = pc.getDeclaredField("bakedY");
                    java.lang.reflect.Field zf = pc.getDeclaredField("bakedZ");
                    xf.setAccessible(true); yf.setAccessible(true); zf.setAccessible(true);
                    float[] bx = (float[]) xf.get(poly);
                    float[] by = (float[]) yf.get(poly);
                    float[] bz = (float[]) zf.get(poly);
                    int n = bx.length;
                    int[] idx = {0, n / 2, n - 1};
                    for (int i : idx) {
                        if (i >= 0 && i < n) {
                            sb.append("MP|").append(texTag)
                              .append(String.format(java.util.Locale.ROOT, "|%.4f,%.4f,%.4f%n", bx[i], by[i], bz[i]));
                        }
                    }
                }
            }
            if (sb.length() > 0) {
                java.nio.file.Files.write(DBG_PATH, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            }
        } catch (Throwable ignored) {
        }
    }

    public TaczPolyMeshAttachmentModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
        // mesh 模型的 ocular/目镜小件默认隐藏：它们只参与开镜时的模板/遮罩渲染
        // （vanilla renderScope 通过 renderTempPart 会临时置 visible=true），
        // 平时（手持/检视/第三人称）不应显示，否则会在 mesh 镜片上露出黑块异物。
        hideOcularParts();
    }

    private void hideOcularParts() {
        if (ocularNodePaths != null) {
            for (List<BedrockPart> path : ocularNodePaths) {
                if (path != null && !path.isEmpty()) {
                    path.get(path.size() - 1).visible = false;
                }
            }
        }
    }

    /**
     * 开镜（瞄准中）时的 mesh 排除：只排除真实镜片(ocular)——它的贴图不透明，
     * 若不排除会盖住瞄准视野。镜筒(scope_body) 不再排除：按原版语义用
     * stencil==0 只画在镜孔之外（renderPolyOcularScopeADS 第 2.5 步），
     * 否则只剩细镜框、枪身顶部会从镜孔四周露出。
     */
    private void applyMeshExclusion(boolean aiming) {
        if (polyMeshModel == null) return;
        if (aiming) {
            for (String name : polyMeshBoneNames) {
                if ("scope_body".equals(name) || "ocular".equals(name)) {
                    polyMeshModel.addExcludeSubtree(name);
                }
            }
        } else {
            polyMeshModel.clearExcludeSubtree();
        }
    }

    private boolean isPlayerAiming() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        return IClientPlayerGunOperator.fromLocalPlayer(player)
                .getClientAimingProgress(Minecraft.getInstance().getFrameTime()) > 0.05f;
    }

    // =========================================================================
    // visibility 復元
    // =========================================================================

    private void restorePartVisibilityForPolyMesh() {
        Set<BedrockPart> divisionLeaves = new HashSet<>();
        if (divisionNodePaths != null) {
            for (List<BedrockPart> path : divisionNodePaths) {
                if (path != null && !path.isEmpty()) divisionLeaves.add(path.get(path.size() - 1));
            }
        }
        restorePathLeaf(scopeBodyPath, divisionLeaves);
        restorePathLeaf(ocularRingPath, divisionLeaves);
        if (ocularNodePaths != null) {
            for (List<BedrockPart> path : ocularNodePaths) restorePathLeaf(path, divisionLeaves);
        }
    }

    private static void restorePathLeaf(@Nullable List<BedrockPart> path, Set<BedrockPart> excluded) {
        if (path == null || path.isEmpty()) return;
        BedrockPart leaf = path.get(path.size() - 1);
        if (!excluded.contains(leaf)) leaf.visible = true;
    }

    /**
     * scope/sight 一人称時の PolyMesh 描画。ステンシルバッファの残留値を利用して穴描画。
     */
    private void renderPolyMeshThroughStencilHole(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO) {

        // ★ 诊断（限频 1s）：一次性把"进镜合成链"的每个判定值打出来，替代反复猜测
        //   （混沌那把镜当时就是靠这套日志定位的）
        {
            com.example.taczmeshloader.util.ScopeDbg.log("enter-ADS", "isScope=" + isScope()
                    + " adsBodyVisible=" + adsBodyVisible
                    + " ocularPaths=" + (ocularNodePaths == null ? -1 : ocularNodePaths.size())
                    + " divisionPaths=" + (divisionNodePaths == null ? -1 : divisionNodePaths.size())
                    + " polyBones=" + (polyMeshModel == null ? -1 : polyMeshModel.getDebugBoneCount()));
        }
        com.tacz.guns.util.RenderHelper.enableItemEntityStencilTest();
        RenderSystem.stencilFunc(GL11.GL_EQUAL, 0, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
        bufferSource.endBatch(RenderType.entityCutout(tex));

        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            bufferSource.endBatch(RenderType.entityTranslucentCull(tex));
        }

        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        com.tacz.guns.util.RenderHelper.disableItemEntityStencilTest();
    }

    // =========================================================================
    // PolyMesh ocular（镜片 mesh 直接当 ocular）的 ADS 模板通道
    // =========================================================================

    /**
     * 流动纹理叠加层（皮肤"星空流动"）：把 geo 里标了 {@code "starflow": true} 的骨再画一遍，
     * 材质 = additive + UV 滚动 + 不受光照（{@link com.example.taczmeshloader.render.MeshyRenderTypes#starflow}）。
     *
     * <p>参数优先取"当前皮肤"（{@code skins[].starflow}），没有则回落 geo 顶层 {@code extras.starflow}；
     * 皮肤写 {@code "starflow": false} = 该皮肤明确不流动（如原皮）。UV 偏移按现实时间推进，与帧率/tick 无关。</p>
     *
     * <p>只在**非开镜合成**路径调用：开镜时镜筒是"模板 != 1"画出来的，流动纹理层没有模板约束，
     * 若在开镜时叠加会把材质遮罩糊到整个观察孔上。</p>
     */
    private void renderStarflowPass(PoseStack poseStack, MultiBufferSource.BufferSource bufferSource,
                                    int light, int overlay) {
        if (polyMeshModel == null || !polyMeshModel.hasStarflowBones()) return;
        boolean on = sfEnabled;
        String tex = sfTexture;
        float su = sfSpeedU, sv = sfSpeedV, tr = sfTintR, tg = sfTintG, tb = sfTintB, al = sfAlpha;
        if (skinNbtKey != null) {
            if (skinNoStarflow.contains(curSkinId)) {
                on = false;
            } else {
                SfCfg c = skinStarflow.get(curSkinId);
                if (c != null) {
                    on = c.on; tex = c.tex;
                    su = c.su; sv = c.sv; tr = c.tr; tg = c.tg; tb = c.tb; al = c.alpha;
                }
            }
        }
        if (!on || tex == null || tex.isEmpty()) return;
        // 客户端配置可整体关掉流动纹理叠加层。★附件（狙镜）有**自己独立的一套 starflow 配置**（scope geo 的 skins[]），
        //   所以枪身那条配置必须在这里同样生效——否则"关掉星空流动"后镜片照样在动（玩家反馈实证）。
        if (!com.example.taczmeshloader.config.TmlClientConfig.starflowEnabled()) return;
        ResourceLocation t = parseTexFile(tex);
        if (t == null) return;
        float time = (float) ((System.nanoTime() / 1_000_000_000.0) % 3600.0);
        float u = (time * su) % 1.0f;
        float v = (time * sv) % 1.0f;
        RenderType rt = com.example.taczmeshloader.render.MeshyRenderTypes.starflow(t);
        // 与枪身同款：偏移走纹理矩阵 + 走 VBO（即时模式每帧重吐顶点，既贵又会与本体互闪）
        com.example.taczmeshloader.render.MeshyRenderTypes.setStarflowOffset(u, v);
        if (!polyMeshModel.renderStarflowVbo(poseStack, rt, tr, tg, tb, al, u, v)) {
            // 兜底：即时模式（偏移已走纹理矩阵 ⇒ 顶点 UV 偏移必须传 0，否则双重偏移）
            polyMeshModel.renderStarflowOnly(poseStack, bufferSource, rt, light, overlay, tr, tg, tb, al, 0f, 0f);
            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(rt);
            }
        }
    }

    private void setPolyExcludes(String... names) {        polyMeshModel.clearExcludeSubtree();
        for (String name : names) {
            polyMeshModel.addExcludeSubtree(name);
        }
    }

    private void flushPolyCutout(MultiBufferSource.BufferSource bufferSource, ResourceLocation tex) {
        bufferSource.endBatch(RenderType.entityCutoutNoCull(tex));
        bufferSource.endBatch(RenderType.entityCutout(tex));
    }

    /** 只渲染 BedrockPart 路径上的 cube 几何（等价 vanilla renderTempPart）。 */
    private void renderCubePartPath(
            PoseStack poseStack,
            RenderType renderType,
            ItemDisplayContext transformType,
            int light, int overlay,
            List<BedrockPart> path) {
        if (path == null || path.isEmpty()) return;
        poseStack.pushPose();
        for (int i = 0; i < path.size() - 1; ++i) {
            path.get(i).translateAndRotateAndScale(poseStack);
        }
        BedrockPart part = path.get(path.size() - 1);
        part.visible = true;
        MultiBufferSource.BufferSource bufferSource = Minecraft.getInstance().renderBuffers().bufferSource();
        part.render(poseStack, transformType, bufferSource.getBuffer(renderType), light, overlay);
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(renderType);
        }
        part.visible = false;
        poseStack.popPose();
    }

    private Vector3f getPartCenter(PoseStack poseStack, List<BedrockPart> path) {
        poseStack.pushPose();
        for (BedrockPart part : path) {
            part.translateAndRotateAndScale(poseStack);
        }
        Vector3f result = new Vector3f(
                poseStack.last().pose().m30(),
                poseStack.last().pose().m31(),
                poseStack.last().pose().m32());
        poseStack.popPose();
        return result;
    }

    /**
     * 镜片 mesh 即 ocular 时的完整 ADS 通道（复刻 vanilla renderScope 的
     * 模板语义，但用 ocular poly 代替 cube 写模板值）：
     *   1) ocular_ring 镜框先画（圆外框，无模板限制）
     *   2) ocular（真实镜片 mesh）以模板 REPLACE=1 写入
     *   3) 屏幕空间圆遮罩翻转 + division 分划在镜内绘制
     * scope_body 已被 applyMeshExclusion 排除（封闭镜体不遮挡视野）。
     */
    private void renderPolyOcularScopeADS(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO) {

        com.tacz.guns.util.RenderHelper.enableItemEntityStencilTest();
        RenderSystem.clearStencil(0);
        RenderSystem.clear(GL11.GL_STENCIL_BUFFER_BIT, Minecraft.ON_OSX);

        // ---- 1) ocular_ring 真镜框（无模板限制，先画）----
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        setPolyExcludes("scope_body", "ocular");
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        flushPolyCutout(bufferSource, tex);

        // ---- 2) 镜片 mesh 写模板值 1 ----
        RenderSystem.colorMask(false, false, false, false);
        RenderSystem.depthMask(false);
        RenderSystem.stencilMask(0xFF);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_REPLACE);
        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 1, 0xFF);
        setPolyExcludes("scope_body", "ocular_ring");
        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        flushPolyCutout(bufferSource, tex);
        RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
        RenderSystem.stencilMask(0xFF);
        RenderSystem.depthMask(true);
        RenderSystem.colorMask(true, true, true, true);

        // ---- 3.5) 镜体（镜筒/镜架）按原版语义只画在镜孔之外（只给 ads_body=show 的镜）----
        // 原版 renderScope 的顺序：ocular_ring → ocular 写模板 → scope_body 以 stencil!=1
        // 只画在镜片之外。镜体因此是不透明的镜筒——镜孔外看到的是镜筒本体，而不是直接透出
        // 世界/枪身（旧版把 scope_body 整棵排除，才出现"开镜是透明的"）；也不必整屏涂黑。
        // 这一步必须在镜片模板写好之后，否则模板没写住会盖死镜孔（早期"镜孔全黑"）。
        // ★ 这个 if 只能包住"镜体"这一步：下面的圆遮罩与分划是**所有镜**都必须走的。
        //   早前误把下面两块一起包进来 ⇒ 没写 ads_body 的镜（方块镜体那种）圆遮罩被跳过，
        //   模板 0xFE 永远没产生，分划无处可画 ⇒ 刻度整个消失。
        if (adsBodyVisible) {
            RenderSystem.stencilFunc(GL11.GL_NOTEQUAL, 1, 0xFF);
            RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.depthMask(true);
            setPolyExcludes("ocular_ring", "ocular");
            polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
            flushPolyCutout(bufferSource, tex);
        }

        // ---- 3) 屏幕空间圆遮罩（与 vanilla renderOcularAndDivision 一致）----
        if (ocularNodePaths != null && !ocularNodePaths.isEmpty()) {
            BufferBuilder builder = Tesselator.getInstance().getBuilder();
            RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_INVERT);
            RenderSystem.colorMask(false, false, false, false);
            RenderSystem.depthMask(false);
            float rad = 80 * getScopeViewRadiusModifier();
            com.example.taczmeshloader.util.ScopeDbg.log("mask", "半径=" + rad + " ocular数=" + ocularNodePaths.size()
                    + " 圆心(第一)=" + getPartCenter(poseStack, ocularNodePaths.get(0)));
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                rad *= IClientPlayerGunOperator.fromLocalPlayer(player)
                        .getClientAimingProgress(Minecraft.getInstance().getFrameTime());
            }
            for (int i = 0; i < ocularNodePaths.size(); i++) {
                RenderSystem.stencilFunc(GL11.GL_EQUAL, i + 1, 0xFF);
                Vector3f ocularCenter = getPartCenter(poseStack, ocularNodePaths.get(i));
                float centerX = ocularCenter.x() * 16 * 90;
                float centerY = ocularCenter.y() * 16 * 90;
                builder.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
                builder.vertex(centerX, centerY, -90.0D).color(255, 255, 255, 255).endVertex();
                for (int j = 0; j <= 90; j++) {
                    float angle = (float) j * ((float) Math.PI * 2F) / 90.0F;
                    float sin = Mth.sin(angle);
                    float cos = Mth.cos(angle);
                    builder.vertex(centerX + cos * rad, centerY + sin * rad, -90.0D)
                            .color(255, 255, 255, 255).endVertex();
                }
                BufferUploader.drawWithShader(builder.end());
            }
            RenderSystem.depthMask(true);
            RenderSystem.colorMask(true, true, true, true);
            RenderSystem.stencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            }

        // ---- 4) division 分划只在镜内绘制（所有镜都走，不受 ads_body 影响）----
        // 关深度测试：分划平面悬在镜前约 5.5 格，若不关深度会被更近的地形遮挡
        // （原版 TaCZ renderDivisionOnly 同样 disableDepthTest）。
        RenderSystem.disableDepthTest();
        for (int i = 0; i < ocularNodePaths.size() && i < divisionNodePaths.size(); i++) {
            int b = ~(i + 1) & 0xFF;
            RenderSystem.stencilFunc(GL11.GL_EQUAL, b, 0xFF);
            renderCubePartPath(poseStack, RenderType.entityCutoutNoCull(tex),
                    ItemDisplayContext.FIRST_PERSON_RIGHT_HAND, light, overlay,
                    divisionNodePaths.get(i));
        }
        RenderSystem.enableDepthTest();

        RenderSystem.stencilFunc(GL11.GL_ALWAYS, 0, 0xFF);
        com.tacz.guns.util.RenderHelper.disableItemEntityStencilTest();
    }

    /**
     * 非 scope/sight 時（通常描画）の PolyMesh 描画。
     */

    private void renderPolyMeshNormalAttachment(
            PoseStack poseStack,
            MultiBufferSource.BufferSource bufferSource,
            ResourceLocation tex,
            int light, int overlay, boolean useVBO) {

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, tex, light, overlay, useVBO);
        }
    }

    private void playScopeAimSound(boolean entering) {
        String sound = entering ? adsSoundOpen : adsSoundClose;
        if (sound == null || sound.isEmpty()) return;
        var player = Minecraft.getInstance().player;
        if (player == null) return;
        int dist = com.tacz.guns.config.common.GunConfig.DEFAULT_GUN_OTHER_SOUND_DISTANCE.get();
        MESH_LOG.info("[ScopeSound] play {} dist={}", sound, dist);
        com.tacz.guns.client.sound.SoundPlayManager.playClientSound(
                player, new ResourceLocation(sound), 1.0f, 1.0f, dist);
    }

    @Override
    public void render(@Nullable ItemStack attachmentItem, ItemStack currentGunItem, PoseStack poseStack,
                       ItemDisplayContext transformType, RenderType renderType, int light, int overlay) {

        if (!this.hasPolyMesh()) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        hideOcularParts();

        // ---- テクスチャ解決 ----
        if (cachedTexture == null) {
            if (attachmentItem != null) {
                IAttachment iAttachment = IAttachment.getIAttachmentOrNull(attachmentItem);
                if (iAttachment != null) {
                    TimelessAPI.getClientAttachmentIndex(iAttachment.getAttachmentId(attachmentItem))
                            .ifPresent(index -> cachedTexture = index.getModelTexture());
                }
            } else {
                for (Map.Entry<ResourceLocation, ClientAttachmentIndex> entry : TimelessAPI.getAllClientAttachmentIndex()) {
                    if (entry.getValue().getAttachmentModel() == this) {
                        cachedTexture = entry.getValue().getModelTexture();
                        break;
                    }
                }
            }
        }

        if (cachedTexture == null) {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            return;
        }

        // ---- 皮肤：跟随**所挂枪**的皮肤 NBT（geo extras "skins"；没写表的附件保持原样）----
        // 与枪身同一套约定（同一张 skins 表 + 同一个 NBT 键）⇒ 枪换皮，镜/配件一起换。
        ResourceLocation useTex = cachedTexture;
        if (skinNbtKey != null) {
            String skinId = "";
            if (currentGunItem != null && !currentGunItem.isEmpty()) {
                net.minecraft.nbt.CompoundTag tag = currentGunItem.getTag();
                if (tag != null && tag.contains(skinNbtKey)) skinId = tag.getString(skinNbtKey);
            }
            ResourceLocation st = skinId.isEmpty() ? null : skinTextures.get(skinId);
            if (st != null) useTex = st;
            curSkinId = skinId;
            if (polyMeshModel != null) {
                polyMeshModel.setSkinHiddenBones(polyMeshModel.collectSkinHidden(skinId));
            }
            if (!skinId.equals(lastAttachSkinLogged)) {
                lastAttachSkinLogged = skinId;
                MESH_LOG.info("[AttachSkinDbg] 生效皮肤='{}' 贴图={}", skinId, useTex);
            }
        }

        // ---- ライト・オーバーレイ計算 ----
        int safeLight = light;
        int safeOverlay = overlay;

        boolean isStandalone = (currentGunItem == null || currentGunItem.isEmpty());

        // インベントリのプレイヤープレビュー（ドール表示）など、GUI 画面が開いている
        // 状態では VBO 直接描画が正しく表示されないことが実機で確認されているため、
        // その場合は VBO を無効化する。
        final boolean isGuiLike = com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();
        final boolean useVBO = !isGuiLike;
        final boolean isFirstPersonScopeOrSight = transformType.firstPerson() && isScope();
        // 瞄准进度（0~1）：用于滞回门，避免一按右键镜体多余件就瞬隐造成穿帮
        float aimProgress = 0f;
        if (isFirstPersonScopeOrSight) {
            LocalPlayer lp0 = Minecraft.getInstance().player;
            if (lp0 != null) {
                aimProgress = IClientPlayerGunOperator.fromLocalPlayer(lp0)
                        .getClientAimingProgress(Minecraft.getInstance().getFrameTime());
            }
        }
        // 滞回门：进入≥85%（镜已基本拉到眼前）才隐藏镜体多余件并切镜内合成；
        // 退出<30%（镜已明显离开）才恢复外观，两端都避免"件先没/件先现"的穿帮。
        if (!adsCompositing && aimProgress >= ADS_ENTER_PROGRESS) adsCompositing = true;
        else if (adsCompositing && aimProgress < ADS_EXIT_PROGRESS) adsCompositing = false;
        // A cube ocular already wrote TaCZ's native scope stencil in super.render().
        // Only a real poly ocular needs our replacement ADS mask and mesh exclusions.
        final boolean polyOcularAds = isFirstPersonScopeOrSight && adsCompositing
                && !ocularPolyMeshBoneNames.isEmpty();
        applyMeshExclusion(polyOcularAds);
        // 开/关镜音：只在"开始瞄 / 退出瞄"的**那次跃变**各响一次（1P 持镜瞄准）。
        // 状态存静态侧（模型实例可能逐帧重建）；换镜（音源变了）时只重新对齐、不补响。
        final boolean aimingStarted = isFirstPersonScopeOrSight && isPlayerAiming();
        final String adsKey = adsSoundOpen + "|" + adsSoundClose;
        if (isFirstPersonScopeOrSight) {
            if (!adsKey.equals(lastAdsSoundKey)) {
                lastAdsSoundKey = adsKey;
                lastScopeAiming = aimingStarted;
            } else if (aimingStarted != lastScopeAiming) {
                lastScopeAiming = aimingStarted;
                MESH_LOG.info("[ScopeSound] ADS transition: aimingStarted={} tt={}", aimingStarted, transformType);
                playScopeAimSound(aimingStarted);
            }
        }
        // 非 1P 持镜时**不动**状态：模型实例会被逐帧重建/多实例轮流渲染，
        // 此处若清状态会导致每帧都被判成"换镜重新对齐"，结果一次都不响（实测踩过）。

        Minecraft mc2 = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc2.renderBuffers().bufferSource();

        // ---------- AR (Accelerated Rendering) との関わり方 ----------
        // TaczPolyMeshGunModel と同じ方針・同じ理由。AR のレイヤー機構経由で
        // 部分的に協調させようとすることに起因する不具合の再発を避けるため、
        // AR が有効な場合はこのアタッチメントの描画全体を AR の介入対象から
        // 完全に外す（一時的に無効化してから、AR 未導入時と同じコードパスで
        // 描画する）。
        final boolean shouldRestoreAcceleration = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (shouldRestoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        try {
            super.render(attachmentItem, currentGunItem, poseStack, transformType, renderType, light, overlay);
            restorePartVisibilityForPolyMesh();

            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOnLightLayer();
            }

            if (polyOcularAds) {
                // ocular=真实镜片 mesh 时走完整 poly 模板 ADS 通道（实验B：光影下也启用）
                // （scope_body/ocular 已排除，ocular_ring 保留）
                renderPolyOcularScopeADS(poseStack, bufferSource, useTex, light, overlay, useVBO);
            } else {
                // 非"镜内合成"状态（世界视图 / 第一人称但还没进镜）：
                // ★ `division` 分划平面是 ADS 专用件，这里必须排除——
                //   否则没开镜时镜的位置上会杵出一大块平面（用户 2026-09-13 反馈"4x/10x 装配位置不对"，
                //   像素比对显示 4x 与 10x 画面几乎相同、比 2x 多出一块高亮实心面）。
                setPolyExcludes("division");
                if (isFirstPersonScopeOrSight) {
                    renderPolyMeshThroughStencilHole(poseStack, bufferSource, useTex, light, overlay, useVBO);
                } else {
                    renderPolyMeshNormalAttachment(poseStack, bufferSource, useTex, light, overlay, useVBO);
                }
                applyMeshExclusion(false);   // 恢复（函数末尾还会再调一次，幂等）
                // 流动纹理叠加层（皮肤"星空流动"）：只在非开镜合成路径画（开镜时镜筒按模板画，叠加层无模板约束会糊满镜孔）
                if (!adsCompositing) renderStarflowPass(poseStack, bufferSource, light, overlay);
            }
            boolean hasTrans = this.polyMeshModel.hasTranslucentMeshes();

            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                bufferSource.endBatch(RenderType.entityCutoutNoCull(useTex));
                bufferSource.endBatch(RenderType.entityCutout(useTex));
                if (hasTrans) {
                    if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                        bufferSource.endBatch(RenderType.entityTranslucentCull(useTex));
                    } else {
                        com.example.taczmeshloader.render.MeshyBatchFlushHandler.markTranslucentPending(useTex);
                    }
                }
            }


            if (!isGuiLike) {
                mc2.gameRenderer.lightTexture().turnOffLightLayer();
            }
        } finally {
            applyMeshExclusion(false);
            if (shouldRestoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
            hideOcularParts();
        }
    }


    /**
     * geo.json を読み込み、poly_mesh ボーンを PolyMeshModel に登録する。
     *
     * cubes の消去は一切行わない（TaczPolyMeshGunModel と同じ方針）。
     */
    public void loadPolyMesh(ResourceLocation modelLocation) {
        try {
            if (this.polyMeshModel != null) {
                this.polyMeshModel.close();
            }

            var resource = Minecraft.getInstance().getResourceManager()
                    .getResource(modelLocation).orElseThrow();

            try (var reader = new InputStreamReader(resource.open())) {
                JsonObject rawJson = JsonParser.parseReader(reader).getAsJsonObject();

                // ADS 开/关镜音：scope geo 顶层 extras "ads_sound"（open/close 为音源 id）。
                // 无配置则清空 → 不播，保证 TML 通用、数据跟着附件资源走。
                adsSoundOpen = "";
                adsSoundClose = "";
                adsBodyVisible = false;
                if (rawJson.has("extras") && rawJson.get("extras").isJsonObject()) {
                    JsonObject ex = rawJson.getAsJsonObject("extras");
                    // 光照上限（与枪身同一套 extras."light_limit" 约定）：
                    // 附件是**独立模型**，枪身上那条限制管不到它 ⇒ 镜必须自己声明，否则阳光直射下镜照样过曝。
                    // 注意：polyMeshModel 要到这里之后（new PolyMeshModel）才存在 ⇒ 先记下来，建完再应用。
                    pendingLightSkyCap = -1;
                    pendingLightBlockCap = -1;
                    pendingLightSkyKeep = 0f;
                    pendingLightBlockKeep = 0f;
                    if (ex.has("light_limit") && ex.get("light_limit").isJsonObject()) {
                        JsonObject ll = ex.getAsJsonObject("light_limit");
                        if (ll.has("sky") && ll.get("sky").isJsonPrimitive()) pendingLightSkyCap = ll.get("sky").getAsInt();
                        if (ll.has("block") && ll.get("block").isJsonPrimitive()) pendingLightBlockCap = ll.get("block").getAsInt();
                        if (ll.has("keep") && ll.get("keep").isJsonPrimitive()) {
                            pendingLightSkyKeep = ll.get("keep").getAsFloat();
                            pendingLightBlockKeep = pendingLightSkyKeep;
                        }
                    }
                    if (ex.has("ads_body") && ex.get("ads_body").isJsonPrimitive()
                            && ex.get("ads_body").getAsJsonPrimitive().isString()) {
                        adsBodyVisible = "show".equalsIgnoreCase(ex.get("ads_body").getAsString());
                    }
                    if (ex.has("ads_sound") && ex.get("ads_sound").isJsonObject()) {
                        JsonObject as = ex.getAsJsonObject("ads_sound");
                        if (as.has("open") && as.get("open").isJsonPrimitive()) {
                            adsSoundOpen = as.get("open").getAsString();
                        }
                        if (as.has("close") && as.get("close").isJsonPrimitive()) {
                            adsSoundClose = as.get("close").getAsString();
                        }
                    }
                    // 皮肤表（跟随所挂枪的皮肤 NBT；与枪身同一套 extras.skins 约定）
                    skinTextures.clear();
                    skinStarflow.clear();
                    skinNoStarflow.clear();
                    skinNbtKey = null;
                    lastAttachSkinLogged = null;
                    // geo 顶层 starflow 兜底（与枪身 GunConfig 同字段名）
                    sfEnabled = false;
                    sfTexture = "";
                    sfSpeedU = 0.02f;
                    sfSpeedV = 0.0f;
                    sfTintR = 1f;
                    sfTintG = 1f;
                    sfTintB = 1f;
                    sfAlpha = 1f;
                    if (ex.has("starflow") && ex.get("starflow").isJsonObject()) {
                        JsonObject sf = ex.getAsJsonObject("starflow");
                        sfEnabled = !sf.has("enabled") || sf.get("enabled").getAsBoolean();
                        if (sf.has("texture") && sf.get("texture").isJsonPrimitive()) sfTexture = sf.get("texture").getAsString();
                        if (sf.has("speed_u") && sf.get("speed_u").isJsonPrimitive()) sfSpeedU = sf.get("speed_u").getAsFloat();
                        if (sf.has("speed_v") && sf.get("speed_v").isJsonPrimitive()) sfSpeedV = sf.get("speed_v").getAsFloat();
                        if (sf.has("alpha") && sf.get("alpha").isJsonPrimitive()) sfAlpha = sf.get("alpha").getAsFloat();
                        if (sf.has("tint") && sf.get("tint").isJsonArray()) {
                            var t = sf.getAsJsonArray("tint");
                            if (t.size() >= 3) { sfTintR = t.get(0).getAsFloat(); sfTintG = t.get(1).getAsFloat(); sfTintB = t.get(2).getAsFloat(); }
                        }
                    }
                    if (ex.has("skins") && ex.get("skins").isJsonArray()) {
                        for (com.google.gson.JsonElement el : ex.getAsJsonArray("skins")) {
                            if (!el.isJsonObject()) continue;
                            JsonObject sk = el.getAsJsonObject();
                            if (!sk.has("id") || !sk.has("texture")) continue;
                            if (!sk.get("id").isJsonPrimitive() || !sk.get("texture").isJsonPrimitive()) continue;
                            String id = sk.get("id").getAsString();
                            String tex = sk.get("texture").getAsString();
                            // ★ 必须走 id→文件 的转换（与枪身同一套）：geo 里写的是 "example:attachment/uv/xxx"，
                            //   纹理管理器要的是 "example:textures/attachment/uv/xxx.png"。漏了这步 ⇒ FileNotFound ⇒ 紫黑格。
                            ResourceLocation texFile = parseTexFile(tex);
                            if (!id.isEmpty() && texFile != null) skinTextures.put(id, texFile);
                            // 逐皮肤流动纹理覆盖（与枪身一致：布尔 false=该皮肤明确不流动；对象=覆盖参数）
                            if (sk.has("starflow")) {
                                var sfe = sk.get("starflow");
                                if (sfe.isJsonPrimitive() && sfe.getAsJsonPrimitive().isBoolean() && !sfe.getAsBoolean()) {
                                    skinNoStarflow.add(id);
                                } else if (sfe.isJsonObject()) {
                                    JsonObject sf = sfe.getAsJsonObject();
                                    SfCfg c = new SfCfg();
                                    c.on = !sf.has("enabled") || sf.get("enabled").getAsBoolean();
                                    c.tex = (sf.has("texture") && sf.get("texture").isJsonPrimitive()) ? sf.get("texture").getAsString() : sfTexture;
                                    c.su = (sf.has("speed_u") && sf.get("speed_u").isJsonPrimitive()) ? sf.get("speed_u").getAsFloat() : sfSpeedU;
                                    c.sv = (sf.has("speed_v") && sf.get("speed_v").isJsonPrimitive()) ? sf.get("speed_v").getAsFloat() : sfSpeedV;
                                    c.alpha = (sf.has("alpha") && sf.get("alpha").isJsonPrimitive()) ? sf.get("alpha").getAsFloat() : sfAlpha;
                                    c.tr = sfTintR; c.tg = sfTintG; c.tb = sfTintB;
                                    if (sf.has("tint") && sf.get("tint").isJsonArray()) {
                                        var t = sf.getAsJsonArray("tint");
                                        if (t.size() >= 3) { c.tr = t.get(0).getAsFloat(); c.tg = t.get(1).getAsFloat(); c.tb = t.get(2).getAsFloat(); }
                                    }
                                    skinStarflow.put(id, c);
                                }
                            }
                        }
                        if (!skinTextures.isEmpty()) {
                            skinNbtKey = (ex.has("skin_nbt_key") && ex.get("skin_nbt_key").isJsonPrimitive())
                                    ? ex.get("skin_nbt_key").getAsString() : "MeshSkin";
                        }
                    }
                }

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
                this.polyMeshModel.setLightLimits(pendingLightSkyCap, pendingLightBlockCap, pendingLightSkyKeep, pendingLightBlockKeep);   // 见 extras.light_limit 解析处

                // cubes の消去は一切行わない（クラス Javadoc 参照）

                this.cachedTexture = null;
                this.cachedRootChildren = null;

                polyMeshBoneNames.clear();
                JsonObject geo = rawJson.getAsJsonArray("minecraft:geometry").get(0).getAsJsonObject();
                JsonArray bones = geo.getAsJsonArray("bones");
                for (int i = 0; i < bones.size(); i++) {
                    JsonObject boneObj = bones.get(i).getAsJsonObject();
                    if (boneObj.has("poly_mesh") && boneObj.has("name")) {
                        polyMeshBoneNames.add(boneObj.get("name").getAsString());
                    }
                }

                com.example.taczmeshloader.render.ShaderStateTracker.register(this.polyMeshModel);

                ocularPolyMeshBoneNames.clear();
                if (ocularNodePaths != null) {
                    for (List<BedrockPart> path : ocularNodePaths) {
                        if (path == null || path.isEmpty()) continue;
                        BedrockPart leaf = path.get(path.size() - 1);
                        if (leaf.name != null && polyMeshModel.hasMeshInSubtree(leaf.name)) {
                            ocularPolyMeshBoneNames.add(leaf.name);
                        }
                    }
                }

                MESH_LOG.info("[MeshyLoader] Loaded attachment poly_mesh from: {} (ocularPolyMeshBones={})",
                        modelLocation, ocularPolyMeshBoneNames);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][loadPolyMesh] FAILED: location={}", modelLocation, e);
        }
    }

    public boolean hasPolyMesh() { return polyMeshModel != null && !polyMeshBoneNames.isEmpty(); }

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
        @Override public boolean isVisible()     { return true; }
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
