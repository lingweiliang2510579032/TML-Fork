package com.example.taczmeshloader.rig;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.client.resource.GunDisplayInstance;
import com.tacz.guns.client.resource.pojo.model.BedrockModelPOJO;
import com.tacz.guns.client.resource.pojo.model.BedrockVersion;
import com.example.taczmeshloader.tacz.TaczPolyMeshGunModel;
import com.example.taczmeshloader.tacz.GunConfig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import com.tacz.guns.client.model.bedrock.BedrockPart;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gun model driven by a native rig (pose tracks + optional skinned mesh) defined in
 * the gunpack. All presentation meta (action mapping / anchor chain / fire stretch /
 * part visibility) is read from the per-gun geo model JSON (top-level extras); with no config
 * the model simply renders its rigs with identity/pose data and no special actions.
 */
@OnlyIn(Dist.CLIENT)
public class RigGunModel extends TaczPolyMeshGunModel {

    private static final Logger LOG = LogManager.getLogger("RigGun");
    private static volatile long lastActionLogNs;

    private final Map<String, String> actionMap = new HashMap<>();
    private final List<String> anchorPath = new ArrayList<>();
    private float fireSeconds = -1f;
    private float shootSeconds = -1f;

    private RigPoseModel poseModel;
    private RigSkinnedModel skinModel;

    public RigGunModel(BedrockModelPOJO pojo, BedrockVersion version) {
        super(pojo, version);
    }

    public static void register() {
        com.tacz.guns.api.client.other.GunModelTypeManager.registerModelType(
                "rig", RigGunModel::new);
        LOG.info("Registered gun model type: rig");
    }

    public void loadRig(ResourceLocation modelId) {
        try {
            ResourceLocation geo = new ResourceLocation(
                    modelId.getNamespace(), "geo_models/" + modelId.getPath() + ".json");
            ResourceLocation rig = new ResourceLocation(
                    modelId.getNamespace(), "rigs/" + modelId.getPath() + ".json");
            ResourceLocation mesh = new ResourceLocation(
                    modelId.getNamespace(), "rig_native/" + modelId.getPath() + ".json");

            // 每枪配置（可选）来自 geo 顶部 "extras"：动作映射 / 锚链 / 开火拉伸 /
            // 部件显隐都从这里来；geo 一定会加载，不存在资源挂载时机问题。
            GunConfig cfg = null;
            try {
                var geoRes = Minecraft.getInstance().getResourceManager().getResource(geo);
                if (geoRes.isPresent()) {
                    try (var gr = new InputStreamReader(geoRes.get().open(), java.nio.charset.StandardCharsets.UTF_8)) {
                        cfg = GunConfig.fromGeo(com.google.gson.JsonParser.parseReader(gr).getAsJsonObject());
                    }
                }
            } catch (Exception ignoredCfg) {
                cfg = null;
            }

            actionMap.clear();
            anchorPath.clear();
            fireSeconds = shootSeconds = -1f;
            if (cfg != null) {
                actionMap.putAll(cfg.rigActionMap);
                anchorPath.addAll(cfg.rigAnchorPath);
                fireSeconds = cfg.rigFireSeconds;
                shootSeconds = cfg.rigShootSeconds;
            }

            poseModel = new RigPoseModel();
            poseModel.setParts(cfg != null ? cfg.rigParts : new ArrayList<>());
            poseModel.load(geo, rig);
            try {
                skinModel = new RigSkinnedModel();
                skinModel.load(mesh, rig);
                LOG.info("Loaded native skinned mesh from {}", mesh);
            } catch (Exception skinErr) {
                skinModel = null;
                LOG.warn("No native skin mesh, using pose model: {}", skinErr.toString());
            }
            LOG.info("Loaded rig from {}", rig);
        } catch (Exception e) {
            LOG.error("Failed to load rig for {}", modelId, e);
            poseModel = null;
        }
    }

    public boolean hasRig() {
        return poseModel != null;
    }

    @Override
    public void render(PoseStack poseStack, ItemStack stack, ItemDisplayContext transformType,
                       RenderType renderType, int light, int overlay) {
        if (poseModel == null) {
            super.render(poseStack, stack, transformType, renderType, light, overlay);
            return;
        }

        boolean shouldRestoreAcceleration = com.tacz.guns.compat.ar.ARCompat.shouldAccelerate();
        if (shouldRestoreAcceleration) {
            com.tacz.guns.compat.ar.ARCompat.disableAcceleration();
        }
        try {
            float[] progressHolder = {0f};
            String action = resolveAction(stack, progressHolder);

            // 全部隐藏玩家手臂（第一人称），避免遮挡模型 / 与自带手套重复；
            // 自带的对应手套随动画可见，由它表现操作动作。
            boolean firstPerson = transformType.firstPerson();
            if (firstPerson) {
                setRenderHand(false);
            }
            try {
                // 壳模型：视角/双手/附件/镜片模板照常。
                super.render(poseStack, stack, transformType, renderType, light, overlay);

                if (skinModel != null) {
                    skinModel.update(action, progressHolder[0]);
                } else if (poseModel != null) {
                    poseModel.update(action, progressHolder[0]);
                }

                ResourceLocation texture = TimelessAPI.getGunDisplay(stack)
                        .map(GunDisplayInstance::getModelTexture).orElse(null);
                if (texture != null) {
                    Minecraft mc = Minecraft.getInstance();
                    MultiBufferSource.BufferSource bufferSource = mc.renderBuffers().bufferSource();
                    mc.gameRenderer.lightTexture().turnOnLightLayer();
                    try {
                        poseStack.pushPose();
                        poseStack.mulPoseMatrix(ancestorDynamicMatrix());
                        if (skinModel != null) {
                            skinModel.render(poseStack, bufferSource,
                                    RenderType.entityCutoutNoCull(texture), light, overlay);
                        } else {
                            poseModel.render(poseStack, bufferSource, texture, light, overlay, false);
                        }
                        poseStack.popPose();
                    } finally {
                        mc.gameRenderer.lightTexture().turnOffLightLayer();
                    }
                    if (!com.tacz.guns.compat.oculus.OculusCompat.endBatch(bufferSource)) {
                        bufferSource.endBatch(RenderType.entityCutoutNoCull(texture));
                    }
                }
            } finally {
                if (firstPerson) {
                    setRenderHand(true);
                }
            }
        } finally {
            if (shouldRestoreAcceleration) {
                com.tacz.guns.compat.ar.ARCompat.resetAcceleration();
            }
        }
    }

    /**
     * 原生网格按“静止成品坐标”烘焙，外层再叠根/手链的动态相对变换，
     * 使枪体跟随第一人称视角与手部动画。锚链由 gun_config 的 anchor_path 给出（geo extras）。
     */
    private Matrix4f ancestorDynamicMatrix() {
        Matrix4f current = new Matrix4f();
        Matrix4f rest = new Matrix4f();
        if (anchorPath.isEmpty()) {
            return current;
        }
        for (String name : anchorPath) {
            BedrockPart part = getNode(name);
            if (part == null) continue;
            current.mul(localMatrix(part, true));
            rest.mul(localMatrix(part, false));
        }
        Matrix4f restInv = new Matrix4f(rest).invert();
        return new Matrix4f(current).mul(restInv);
    }

    private Matrix4f localMatrix(BedrockPart part, boolean animated) {
        Matrix4f m = new Matrix4f();
        if (animated) {
            m.translate(part.offsetX, part.offsetY, part.offsetZ);
        }
        m.translate(part.x / 16f, part.y / 16f, part.z / 16f);
        if (part.zRot != 0f) m.rotateZ(part.zRot);
        if (part.yRot != 0f) m.rotateY(part.yRot);
        if (part.xRot != 0f) m.rotateX(part.xRot);
        if (animated) {
            m.rotate(new Quaternionf(part.additionalQuaternion));
        }
        if (part.xScale != 1f || part.yScale != 1f || part.zScale != 1f) {
            m.scale(part.xScale, part.yScale, part.zScale);
        }
        return m;
    }

    @Nullable
    private String resolveAction(ItemStack stack, float[] progressHolder) {
        if (!TimelessAPI.getGunDisplay(stack).isPresent()) return null;
        GunDisplayInstance display = TimelessAPI.getGunDisplay(stack).get();
        var stateMachine = display.getAnimationStateMachine();
        if (stateMachine == null) return null;
        var controller = stateMachine.getAnimationController();
        long now = System.nanoTime();
        boolean debugThisTick = now - lastActionLogNs > 500_000_000L;
        for (int track = 0; track < 16; track++) {
            var runner = controller.getAnimation(track);
            if (runner == null) {
                continue;
            }
            var transitionTo = runner.getTransitionTo();
            if (transitionTo != null) runner = transitionTo;
            String key = runner.getAnimation().name;
            float progress = runner.getProgressNs() / 1e9f;
            String rigAction = actionMap.get(key);
            if (rigAction == null) {
                if (debugThisTick) {
                    lastActionLogNs = now;
                    LOG.info("runner track={} key={} progress={}s mapped={}",
                            track, key, progress, false);
                }
                continue;
            }
            if (debugThisTick) {
                lastActionLogNs = now;
                LOG.info("runner track={} key={} progress={}s mapped={}",
                        track, key, progress, true);
            }
            progressHolder[0] = progress;
            if ("shoot".equals(key) && shootSeconds > 0f && fireSeconds > 0f) {
                float stretch = fireSeconds / shootSeconds;
                progressHolder[0] = Math.min(progress * stretch, fireSeconds);
            }
            return rigAction;
        }
        if (debugThisTick) {
            lastActionLogNs = now;
            LOG.info("no active mapped runner; tracks count sample done");
        }
        return null;
    }
}
