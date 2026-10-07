package com.example.taczmeshloader.lrtactical;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.render.MeshyBatchFlushHandler;
import com.example.taczmeshloader.render.ShaderStateTracker;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.tacz.guns.client.resource.ClientAssetsManager;
import me.xjqsh.lrtactical.client.renderer.model.CustomBedrockModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * LesRaisins Tactical Equipements 用の poly_mesh 対応モデルクラス。
 *
 * <p>{@link CustomBedrockModel} のサブクラスであるため、poly_mesh を使わない
 * 通常の cubeモデルでも完全に同じ動作をする。
 * {@link } が {@code @Redirect} で
 * {@code new CustomBedrockModel(...)} をこのクラスに差し替えることで、
 * AnimationController が最初からこのインスタンスに紐づく。</p>
 */
@OnlyIn(Dist.CLIENT)
public class LrPolyMeshModel extends CustomBedrockModel {

    private PolyMeshModel polyMeshModel;
    private ResourceLocation texture;
    private ResourceLocation baseTexture;
    private ResourceLocation geoLocation;
    private String skinNbtKey = "MeshSkin";
    private final java.util.Map<String, ResourceLocation> skinTextures = new java.util.LinkedHashMap<>();
    private List<IPolyMeshBone> cachedRootChildren = null;
    private LrPolyMeshModel thirdPersonRight;
    private LrPolyMeshModel thirdPersonLeft;
    private boolean allowThirdPersonModels = true;
    private LrFxSource fxSource;
    private final java.util.Map<String, com.example.taczmeshloader.render.MeleeSurfaceMaterial> surfaceMaterials = new java.util.LinkedHashMap<>();
    private ItemStack renderStack = ItemStack.EMPTY;

    private static final org.apache.logging.log4j.Logger MESH_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader");

    public LrPolyMeshModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    // -------------------------------------------------------------------------
    // レンダリング
    // -------------------------------------------------------------------------

    @Override
    public void render(PoseStack poseStack,
                       ItemDisplayContext transformType,
                       RenderType renderType,
                       int light, int overlay) {
        // GUI previews share this model but never own the first-person FX lifecycle.
        if (transformType.firstPerson() && fxSource != null
                && !com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen()) {
            fxSource.bind(renderStack);
        }
        LrPolyMeshModel handModel = transformType == ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                ? thirdPersonLeft : transformType == ItemDisplayContext.THIRD_PERSON_RIGHT_HAND
                ? thirdPersonRight : null;
        if (handModel != null) {
            handModel.render(poseStack, transformType, renderType, light, overlay);
            return;
        }
        // Mesh skins and held FX capture depth before returning to the hand renderer.
        // AR's queued cube draws are not submitted by OculusCompat.endBatch.
        final boolean shouldRestoreAcceleration = polyMeshModel != null
                && com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (shouldRestoreAcceleration) com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        try {
            super.render(poseStack, transformType, renderType, light, overlay);
            renderPolyMeshLayer(poseStack, transformType, light, overlay);
        } finally {
            if (shouldRestoreAcceleration) com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
        }
    }

    public boolean hasThirdPersonPair() {
        return thirdPersonRight != null && thirdPersonLeft != null;
    }

    public ResourceLocation geoLocation() {
        return geoLocation;
    }

    /** Called with the actual rendered stack, including previews and dropped items. */
    public void setSkinStack(ItemStack stack) {
        renderStack = stack == null ? ItemStack.EMPTY : stack;
        ResourceLocation selected = baseTexture;
        if (stack != null && stack.hasTag()) {
            selected = skinTextures.getOrDefault(stack.getTag().getString(skinNbtKey), baseTexture);
        }
        texture = selected;
        if (thirdPersonRight != null) thirdPersonRight.texture = selected;
        if (thirdPersonLeft != null) thirdPersonLeft.texture = selected;
    }

    private void loadSkinTextures(JsonObject rawJson) {
        skinTextures.clear();
        skinNbtKey = "MeshSkin";
        if (!rawJson.has("extras") || !rawJson.get("extras").isJsonObject()) return;
        JsonObject extras = rawJson.getAsJsonObject("extras");
        if (extras.has("skin_nbt_key")) skinNbtKey = extras.get("skin_nbt_key").getAsString();
        if (!extras.has("skins") || !extras.get("skins").isJsonArray()) return;
        for (var entry : extras.getAsJsonArray("skins")) {
            if (!entry.isJsonObject()) continue;
            JsonObject skin = entry.getAsJsonObject();
            if (!skin.has("id") || !skin.has("texture")) continue;
            ResourceLocation id = ResourceLocation.tryParse(skin.get("texture").getAsString());
            if (id == null) continue;
            String path = id.getPath();
            ResourceLocation file = path.startsWith("textures/") && path.endsWith(".png") ? id
                    : new ResourceLocation(id.getNamespace(), "textures/" + path + ".png");
            skinTextures.put(skin.get("id").getAsString(), file);
        }
    }

    private LrPolyMeshModel loadThirdPersonHand(String name, ResourceLocation texture) {
        ResourceLocation id = new ResourceLocation(name);
        BedrockModelPOJO pojo = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(id);
        if (pojo == null) throw new IllegalArgumentException("Missing third-person hand model: " + id);
        BedrockVersion version = BedrockVersion.isLegacyVersion(pojo) ? BedrockVersion.LEGACY : BedrockVersion.NEW;
        LrPolyMeshModel model = new LrPolyMeshModel(pojo, version);
        // Third-person leaf models never recursively load more hand models.
        model.allowThirdPersonModels = false;
        model.loadPolyMesh(new ResourceLocation(id.getNamespace(), "geo_models/" + id.getPath() + ".json"), texture);
        if (model.polyMeshModel == null) throw new IllegalArgumentException("Missing third-person mesh: " + id);
        return model;
    }

    private void clearThirdPersonModels() {
        if (thirdPersonRight != null && thirdPersonRight.polyMeshModel != null) thirdPersonRight.polyMeshModel.close();
        if (thirdPersonLeft != null && thirdPersonLeft.polyMeshModel != null) thirdPersonLeft.polyMeshModel.close();
        thirdPersonRight = null;
        thirdPersonLeft = null;
    }

    private void renderPolyMeshLayer(PoseStack poseStack, ItemDisplayContext ctx,
                                     int light, int overlay) {
        if (polyMeshModel == null || texture == null) return;

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();

        // GUI・HEAD などのアイコン/2D表示コンテキストでは、VBO 直接描画が
        // GUI のフレームバッファーに正しく書き込めずアイコンが透明になるため VBO を使わない。
        final boolean isIconContext = ctx == ItemDisplayContext.GUI
                || ctx == ItemDisplayContext.HEAD
                || ctx == ItemDisplayContext.FIXED
                || ctx == ItemDisplayContext.NONE;
        final boolean useVBO = !isIconContext;

        // インベントリのプレイヤープレビュー（近接武器等を手に持って表示する
        // ケースを含む）など、GUI 画面が開いている状態で lightTexture の
        // 有効/無効化を毎フレーム行うと重い処理になり、メニューを開いている間
        // FPS が大幅に低下することが分かっているため、画面が開いている間は
        // この操作をスキップする（VBO 自体は無効化しない）。
        final boolean isGuiLike = isIconContext || com.example.taczmeshloader.render.ScreenRenderTracker.isRenderingScreen();

        if (!isGuiLike) {
            mc.gameRenderer.lightTexture().turnOnLightLayer();
        }

        polyMeshModel.renderCutoutOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
        if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
            bufferSource.endBatch(RenderType.entityCutoutNoCull(texture));
            bufferSource.endBatch(RenderType.entityCutout(texture));
            if (polyMeshModel.hasSmoothMeshes()) {
                com.example.taczmeshloader.render.SmoothMeshRenderTypes.endBatch(bufferSource, texture);
            }
        }

        if (polyMeshModel.hasTranslucentMeshes()) {
            polyMeshModel.renderTranslucentOnly(poseStack, bufferSource, texture, light, overlay, useVBO);
            if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                if (net.minecraftforge.fml.ModList.get().isLoaded("oculus")) {
                    bufferSource.endBatch(RenderType.entityTranslucentCull(texture));
                } else {
                    MeshyBatchFlushHandler.markTranslucentPending(texture);
                }
                if (polyMeshModel.hasSmoothMeshes()) {
                    com.example.taczmeshloader.render.SmoothMeshRenderTypes.endBatch(bufferSource, texture);
                }
            }
        }

        if (!isGuiLike) {
            mc.gameRenderer.lightTexture().turnOffLightLayer();
        }
        if (!isGuiLike && ctx.firstPerson() && fxSource != null) fxSource.capture(polyMeshModel);
        if (!isGuiLike && ctx.firstPerson() && !surfaceMaterials.isEmpty()) {
            String skin = renderStack.hasTag() ? renderStack.getTag().getString(skinNbtKey) : "";
            var material = surfaceMaterials.getOrDefault(skin, surfaceMaterials.get("base"));
            com.example.taczmeshloader.render.MeleeSurfaceRenderer.capture(polyMeshModel, poseStack, material,
                    renderStack, skinNbtKey, light);
        }
    }

    // -------------------------------------------------------------------------
    // poly_mesh ロード
    // -------------------------------------------------------------------------

    /**
     * geo_models/ に対応する poly_mesh JSON が存在すればロードする静的ヘルパー。
     * Mixin（= Mixinパッケージ外から呼べない制約あり）ではなく
     * このクラス自身のメソッドとして定義することで、Mixin から安全に呼べる。
     *
     * @param model         差し替え済みの LrPolyMeshModel インスタンス
     * @param modelLocation display JSON の "model" フィールド値
     * @param texture       解決済みテクスチャ ResourceLocation（textures/〜.png 形式）
     */
    public static void tryLoadPolyMesh(LrPolyMeshModel model,
                                       ResourceLocation modelLocation,
                                       ResourceLocation texture) {
        if (modelLocation == null) return;

        ResourceLocation geoPath = new ResourceLocation(
                modelLocation.getNamespace(),
                "geo_models/" + modelLocation.getPath() + ".json"
        );

        // poly_mesh JSON が存在する場合のみロード（なければ通常の cubeモデルとして動作）
        if (Minecraft.getInstance().getResourceManager().getResource(geoPath).isEmpty()) return;

        model.loadPolyMesh(geoPath, texture);
    }

    public void loadPolyMesh(ResourceLocation modelLocation, ResourceLocation texture) {
        this.texture = texture;
        this.baseTexture = texture;
        this.geoLocation = modelLocation;
        try {
            if (this.polyMeshModel != null) this.polyMeshModel.close();
            clearThirdPersonModels();
            fxSource = null;
            surfaceMaterials.clear();

            var resource = Minecraft.getInstance().getResourceManager()
                    .getResource(modelLocation).orElseThrow();

            try (var reader = new InputStreamReader(resource.open(), java.nio.charset.StandardCharsets.UTF_8)) {
                JsonObject rawJson = JsonParser.parseReader(reader).getAsJsonObject();
                loadSkinTextures(rawJson);
                if (allowThirdPersonModels && rawJson.has("extras")) {
                    try {
                        surfaceMaterials.putAll(com.example.taczmeshloader.render.MeleeSurfaceMaterial.parse(rawJson.getAsJsonObject("extras")));
                    } catch (RuntimeException invalidSurface) {
                        surfaceMaterials.clear();
                        MESH_LOG.warn("[MeleeSurface] Invalid optional surface contract for {}; retaining original material", modelLocation, invalidSurface);
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
                                .map(LrPartAdapter::new).collect(Collectors.toList());
                        return cachedRootChildren;
                    }
                };

                this.polyMeshModel = new PolyMeshModel(adaptedRoot, rawJson);
                this.cachedRootChildren = null;
                ShaderStateTracker.register(this.polyMeshModel);
                if (allowThirdPersonModels && rawJson.has("extras")
                        && rawJson.getAsJsonObject("extras").has("fx")) {
                    fxSource = new LrFxSource(this, rawJson.getAsJsonObject("extras"), polyMeshModel);
                }
                if (allowThirdPersonModels && rawJson.has("extras")) {
                    JsonObject extras = rawJson.getAsJsonObject("extras");
                    if (extras.has("third_person_hand_models")) {
                        JsonObject hands = extras.getAsJsonObject("third_person_hand_models");
                        try {
                            thirdPersonRight = loadThirdPersonHand(hands.get("right").getAsString(), texture);
                            thirdPersonLeft = loadThirdPersonHand(hands.get("left").getAsString(), texture);
                        } catch (Exception error) {
                            clearThirdPersonModels();
                            MESH_LOG.error("[MeshyLoader] Failed to load third-person hands for {}", modelLocation, error);
                        }
                    }
                }
                MESH_LOG.info("[MeshyLoader] Loaded LR poly_mesh from: {}", modelLocation);
            }
        } catch (Exception e) {
            MESH_LOG.error("[MeshyDebug][LrPolyMeshModel] FAILED: location={}", modelLocation, e);
        }
    }

    // -------------------------------------------------------------------------
    // BedrockPart → IPolyMeshBone アダプター
    // -------------------------------------------------------------------------

    private static class LrPartAdapter implements IPolyMeshBone {
        private final BedrockPart part;
        private List<IPolyMeshBone> cachedChildren;
        LrPartAdapter(BedrockPart part) { this.part = part; }
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
            if (part.children != null)
                for (BedrockPart c : part.children) cachedChildren.add(new LrPartAdapter(c));
            return cachedChildren;
        }
        @Override public void applyTransform(PoseStack ps) { part.translateAndRotateAndScale(ps); }
    }
}
