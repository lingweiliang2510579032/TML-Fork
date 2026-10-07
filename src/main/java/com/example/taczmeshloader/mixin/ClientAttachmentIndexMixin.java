package com.example.taczmeshloader.mixin;

import com.example.taczmeshloader.tacz.TaczPolyMeshAttachmentModel;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.tacz.guns.client.model.BedrockAttachmentModel;
import com.tacz.guns.client.resource.ClientAssetsManager;
import com.tacz.guns.client.resource.index.ClientAttachmentIndex;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentDisplay;
import com.tacz.guns.client.resource.pojo.display.attachment.AttachmentLod;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

@Mixin(value = ClientAttachmentIndex.class, remap = false)
public class ClientAttachmentIndexMixin {

    // -----------------------------------------------------------------------
    // 通常モデル（既存）
    // -----------------------------------------------------------------------

    @Inject(method = "checkTextureAndModel", at = @At("TAIL"))
    private static void meshyloader$afterCheckTextureAndModel(
            AttachmentDisplay display, ClientAttachmentIndex index, CallbackInfo ci) {

        ResourceLocation modelId = display.getModel();
        if (modelId == null) return;

        ResourceLocation geoPath = new ResourceLocation(
                modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");

        if (!hasPolyMesh(geoPath)) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(modelId);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshAttachmentModel polyModel = new TaczPolyMeshAttachmentModel(modelPOJO, version);
        polyModel.setIsScope(display.isScope());
        polyModel.setIsSight(display.isSight());
        polyModel.loadPolyMesh(geoPath);
        if (!polyModel.hasPolyMesh()) return;

        try {
            Field field = ClientAttachmentIndex.class.getDeclaredField("attachmentModel");
            field.setAccessible(true);
            field.set(index, polyModel);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // -----------------------------------------------------------------------
    // LODモデル（新規）
    // -----------------------------------------------------------------------

    /**
     * checkLod() の末尾に介入し、アタッチメントのLODモデルにも poly_mesh を適用する。
     *
     * <p>LOD用 geo.json のパス規則：<br>
     * display JSON の {@code lod.model} に対し {@code geo_models/<path>.json} を探す（通常モデルと同じパス変換）。<br>
     * 例: {@code "lod": {"model": "mypack:attachment/scope"}}
     * → {@code assets/mypack/geo_models/attachment/lod/scope.json}</p>
     */
    @Inject(method = "checkLod", at = @At("TAIL"))
    private static void meshyloader$afterCheckLod(
            AttachmentDisplay display, ClientAttachmentIndex index, CallbackInfo ci) {

        // lodModel が生成されていなければスキップ
        Pair<BedrockAttachmentModel, ResourceLocation> currentLod;
        try {
            Field lodField = ClientAttachmentIndex.class.getDeclaredField("lodModel");
            lodField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Pair<BedrockAttachmentModel, ResourceLocation> lod =
                    (Pair<BedrockAttachmentModel, ResourceLocation>) lodField.get(index);
            currentLod = lod;
        } catch (Exception e) {
            return;
        }
        if (currentLod == null) return;

        AttachmentLod attachmentLod = display.getAttachmentLod();
        if (attachmentLod == null || attachmentLod.getModelLocation() == null) return;

        ResourceLocation lodModelId = attachmentLod.getModelLocation();

        // LOD用 geo.json: geo_models/<path>.json（通常モデルと同じルール）
        ResourceLocation geoPath = new ResourceLocation(
                lodModelId.getNamespace(),
                "geo_models/" + lodModelId.getPath() + ".json");

        if (!hasPolyMesh(geoPath)) return;

        BedrockModelPOJO modelPOJO = ClientAssetsManager.INSTANCE.getBedrockModelPOJO(lodModelId);
        if (modelPOJO == null) return;

        BedrockVersion version = BedrockVersion.isLegacyVersion(modelPOJO)
                ? BedrockVersion.LEGACY : BedrockVersion.NEW;

        TaczPolyMeshAttachmentModel polyLodModel = new TaczPolyMeshAttachmentModel(modelPOJO, version);
        polyLodModel.setIsScope(display.isScope());
        polyLodModel.setIsSight(display.isSight());
        polyLodModel.loadPolyMesh(geoPath);
        if (!polyLodModel.hasPolyMesh()) return;

        try {
            Field lodField = ClientAttachmentIndex.class.getDeclaredField("lodModel");
            lodField.setAccessible(true);
            lodField.set(index, Pair.of(polyLodModel, currentLod.getRight()));
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader")
                    .error("[MeshyLoader] Failed to inject LOD PolyMesh for attachment: {}", lodModelId, e);
        }
    }

    /** Cube-only geo files are common in TaCZ packs; leave their scope renderer untouched. */
    private static boolean hasPolyMesh(ResourceLocation geoPath) {
        var resource = Minecraft.getInstance().getResourceManager().getResource(geoPath);
        if (resource.isEmpty()) return false;
        try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonArray geometries = root.getAsJsonArray("minecraft:geometry");
            if (geometries == null || geometries.isEmpty()) return false;
            JsonArray bones = geometries.get(0).getAsJsonObject().getAsJsonArray("bones");
            if (bones == null) return false;
            for (var bone : bones) {
                if (bone.isJsonObject() && bone.getAsJsonObject().has("poly_mesh")) return true;
            }
        } catch (Exception e) {
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoader")
                    .warn("Skipping invalid attachment geo {}: {}", geoPath, e.toString());
        }
        return false;
    }
}
