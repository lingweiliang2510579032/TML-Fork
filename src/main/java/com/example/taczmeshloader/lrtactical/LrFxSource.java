package com.example.taczmeshloader.lrtactical;

import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.client.RenderedHand;
import com.example.taczmeshloader.render.FxParticleSource;
import com.example.taczmeshloader.render.FxParticles;
import com.example.taczmeshloader.render.FxHandProjection;
import com.mojang.blaze3d.systems.RenderSystem;
import com.example.taczmeshloader.tacz.GunConfig;
import com.google.gson.JsonObject;
import com.tacz.guns.api.client.animation.ObjectAnimationRunner;
import me.xjqsh.lrtactical.api.LrTacticalAPI;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import java.util.*;

/** LRT adapter; timing, particles and HDR drawing remain owned by the shared FX engine. */
public final class LrFxSource implements FxParticleSource {
    private final LrPolyMeshModel model;
    private final Map<String, List<GunConfig.FxEmitter>> skins = new HashMap<>();
    private final Map<String, Matrix4f> poses = new LinkedHashMap<>();
    private final Map<String, String> heldPoseSources = new LinkedHashMap<>();
    private final Map<String, Vector3f> heldPoseOffsets = new HashMap<>();
    private final Map<String, Vector3f> heldWorldOffsets = new HashMap<>();
    private final Set<String> captured = new HashSet<>();
    private final ObjectAnimationRunner[] animations = new ObjectAnimationRunner[16];
    private final com.example.taczmeshloader.render.FxAnimationBlock[] skinBlocks = new com.example.taczmeshloader.render.FxAnimationBlock[16];
    private final Matrix4f scratch = new Matrix4f();
    private final Vector3f position = new Vector3f();
    private final String skinKey;
    private final boolean heldDepthOcclusion;
    private final boolean heldProjectionMatch;
    private final Matrix4f heldToWorld = new Matrix4f(), projectionScratch = new Matrix4f();
    private final Matrix4f worldProjection = new Matrix4f(), worldView = new Matrix4f(), handView = new Matrix4f();
    private final Matrix4f handProjection = new Matrix4f();
    private long handProjectionFrame = -1, matchedProjectionFrame = -1;
    private long projectionMatches;
    private final Vector3f matchedPosition = new Vector3f();
    private boolean matchedProjection;
    private ItemStack stack = ItemStack.EMPTY;
    private List<GunConfig.FxEmitter> active = List.of();
    private boolean hasHeld;
    private float heldDepthWeight = 1f;
    private float normalPoseWeight;
    private long heldDepthLastNs;

    public LrFxSource(LrPolyMeshModel model, JsonObject extras, PolyMeshModel mesh) {
        this.model = model;
        if (model != null) com.example.taczmeshloader.client.GunSkinCatalog.rememberGeoSkins(model.geoLocation(), extras);
        for (int i = 0; i < skinBlocks.length; i++) skinBlocks[i] = new com.example.taczmeshloader.render.FxAnimationBlock();
        skinKey = extras.has("skin_nbt_key") ? extras.get("skin_nbt_key").getAsString() : "MeshSkin";
        heldDepthOcclusion = !extras.has("held_depth_occlusion") || extras.get("held_depth_occlusion").getAsBoolean();
        heldProjectionMatch = extras.has("held_projection_match") && extras.get("held_projection_match").getAsBoolean();
        List<GunConfig.FxEmitter> emitters = GunConfig.parse(extras).fxEmitters;
        Set<String> ids = new LinkedHashSet<>();
        ids.add("base");
        if (extras.has("skins")) for (var value : extras.getAsJsonArray("skins")) {
            if (value.isJsonObject() && value.getAsJsonObject().has("id"))
                ids.add(value.getAsJsonObject().get("id").getAsString());
        }
        for (String id : ids) {
            List<GunConfig.FxEmitter> selected = new ArrayList<>();
            for (var em : emitters) if (em.enabled && (em.skins.isEmpty() || em.skins.contains(id))) selected.add(em);
            skins.put(id, List.copyOf(selected));
        }
        // Optional held-only aliases keep source sockets unchanged during accepted inspect poses.
        if (extras.has("held_pose_aliases")) for (var value : extras.getAsJsonArray("held_pose_aliases")) {
            if (!value.isJsonObject()) continue;
            var alias = value.getAsJsonObject();
            String offsetKey = alias.has("normal_bone_offset") ? "normal_bone_offset" : "normal_world_offset";
            if (!alias.has("name") || !alias.has("bone") || !alias.has(offsetKey)) continue;
            String name = alias.get("name").getAsString(), bone = alias.get("bone").getAsString();
            var offset = alias.getAsJsonArray(offsetKey);
            if (name.isEmpty() || bone.isEmpty() || name.equals(bone) || offset.size() != 3) continue;
            boolean heldOnly = false, usedElsewhere = false;
            for (var em : emitters) if (name.equals(em.bone)) {
                if ("held".equals(em.trigger)) heldOnly = true; else usedElsewhere = true;
            }
            if (!heldOnly || usedElsewhere) continue;
            Vector3f shift = new Vector3f(offset.get(0).getAsFloat(), offset.get(1).getAsFloat(), offset.get(2).getAsFloat());
            if (!shift.isFinite()) continue;
            heldPoseSources.put(name, bone);
            if ("normal_bone_offset".equals(offsetKey)) heldPoseOffsets.put(name, shift);
            else heldWorldOffsets.put(name, shift);
        }
        for (var em : emitters) if (!em.bone.isEmpty())
            poses.putIfAbsent(heldPoseSources.getOrDefault(em.bone, em.bone), new Matrix4f());
        mesh.setFxCaptureBones(poses.keySet());
        for (var name : heldPoseSources.keySet()) poses.put(name, new Matrix4f());
    }

    public void bind(ItemStack stack) {
        // During put-away the animation still owns the outgoing item, not the newly selected slot.
        ItemStack rendered = RenderedHand.stack();
        if (stack != null && ItemStack.matches(stack, rendered)) stack = rendered;
        boolean skinChange = this.stack == stack && !this.stack.isEmpty() && selected(this.stack) != active;
        this.stack = stack == null ? ItemStack.EMPTY : stack;
        var next = selected(this.stack);
        if (active != next) {
            hasHeld = false;
            for (var em : next) if (em.enabled && "held".equals(em.trigger)) { hasHeld = true; break; }
        }
        active = next;
        if (skinChange) {
            sampleAnimations(false);
            for (int i = 0; i < animations.length; i++) {
                var runner = animations[i];
                skinBlocks[i].block(runner, runner == null ? -1f : runner.getProgressNs()/1e9f);
            }
        }
    }

    private List<GunConfig.FxEmitter> selected(ItemStack item) {
        String id = item.hasTag() ? item.getTag().getString(skinKey) : "base";
        return skins.getOrDefault(id, skins.get("base"));
    }

    /** Snapshots only the first-person pass; GUI and third-person can never replace these matrices. */
    public void capture(PolyMeshModel mesh) {
        captured.clear();
        for (var entry : poses.entrySet())
            if (!heldPoseSources.containsKey(entry.getKey()) && mesh.copyFxCapturedPose(entry.getKey(), entry.getValue())) captured.add(entry.getKey());
        float depthWeight = hasHeld ? heldDepthWeight() : 0f;
        // A calibrated core stays attached to one ring point through inspect and its exit.
        // Keep the legacy inspect weight only for packs that have not opted into this path.
        float normalWeight = hasHeld && heldProjectionMatch ? 1f : depthWeight;
        normalPoseWeight = normalWeight;
        com.example.taczmeshloader.render.FxPreHandDepth.request(this, heldProjectionMatch && normalWeight > 0f);
        matchedProjection = false;
        matchedProjectionFrame = -1;
        handProjectionFrame = com.example.taczmeshloader.render.BloomPostProcessor.currentFrameId();
        handProjection.set(RenderSystem.getProjectionMatrix());
        handView.identity();
        if (!mesh.wasCutoutDirectVbo()) handView.set(RenderSystem.getModelViewMatrix());
        for (var entry : heldPoseSources.entrySet()) if (captured.contains(entry.getValue())) {
            // Calibrated ring-lip offset follows the knife throughout the complete animation.
            Vector3f shift = heldPoseOffsets.get(entry.getKey());
            Matrix4f pose = poses.get(entry.getKey()).set(poses.get(entry.getValue()));
            if (shift != null) pose.translate(shift.x * normalWeight, shift.y * normalWeight, shift.z * normalWeight);
            captured.add(entry.getKey());
        }
        FxParticles.registerMelee(this);
        FxParticles.onSourceRendered(this);
        if (hasHeld) com.example.taczmeshloader.render.FxHeldDepth.capture(this, heldDepthOcclusion ? depthWeight : 0f, fovScale(), mesh.wasCutoutDirectVbo());
    }

    /** Inspect is accepted as-is: remove occlusion immediately; fade back over 150ms on exit. */
    private float heldDepthWeight() {
        long now = System.nanoTime();
        float dt = heldDepthLastNs == 0 ? 0f : Math.min(.15f, (now-heldDepthLastNs)/1e9f);
        heldDepthLastNs = now;
        var display = LrTacticalAPI.getMeleeDisplay(stack).orElse(null);
        boolean inspecting = false;
        if (display != null && display.getModel() == model && display.getStateMachine() != null) {
            var controller = display.getStateMachine().getAnimationController();
            for (int i=0; i<animations.length; i++) {
                var runner = controller.getAnimation(i);
                // Both sides of a transition can still contribute to the rendered pose.
                if (inspectPose(runner) || (runner != null && inspectPose(runner.getTransitionTo()))) {
                    inspecting = true; break;
                }
            }
        }
        heldDepthWeight = inspecting ? 0f : Math.min(1f, heldDepthWeight + dt/.15f);
        return heldDepthWeight;
    }

    private static boolean inspectPose(ObjectAnimationRunner runner) {
        return runner != null && (!runner.isStopped() || runner.isTransitioning()) && runner.getAnimation().name.startsWith("inspect");
    }

    @Override public boolean validLocalOwner(Minecraft mc) {
        if (mc.player == null || mc.level == null || !mc.player.isAlive()
                || mc.getCameraEntity() != mc.player || !mc.options.getCameraType().isFirstPerson()
                || (!hasHeld && !allowTransientFx(mc)) || stack.isEmpty()
                || RenderedHand.stack() != stack || selected(stack) != active || active.isEmpty()) return false;
        var display = LrTacticalAPI.getMeleeDisplay(stack).orElse(null);
        return display != null && display.getModel() == model;
    }

    @Override public Object ownershipToken() { return stack; }
    @Override public String diagnosticDetails(Minecraft mc) {
        ItemStack held = RenderedHand.stack();
        return "renderedRef=" + (held == stack) + ",renderedTags=" + ItemStack.matches(held, stack)
                + ",skin=" + (selected(stack) == active) + ",screen=" + (mc.screen != null)
                + ",hidden=" + mc.options.hideGui + ",bones=" + captured.size()
                + ",projectionMatches=" + projectionMatches;
    }
    @Override public boolean deferEmissionUntilHand() { return true; }
    @Override public boolean allowTransientFx(Minecraft mc) { return !mc.options.hideGui && mc.screen == null; }
    @Override public List<GunConfig.FxEmitter> activeFxEmitters() { return active; }
    @Override public boolean isShooterAiming(Minecraft mc) { return false; }
    @Override public float getInspectProgressSeconds(ItemStack stack) { return -1f; }

    @Override public void sampleAnimations() {
        sampleAnimations(true);
    }

    private void sampleAnimations(boolean applySkinBlocks) {
        Arrays.fill(animations, null);
        var display = LrTacticalAPI.getMeleeDisplay(stack).orElse(null);
        if (display == null || display.getModel() != model || display.getStateMachine() == null) return;
        var controller = display.getStateMachine().getAnimationController();
        for (int i = 0; i < animations.length; i++) {
            var runner = controller.getAnimation(i);
            if (runner != null && runner.getTransitionTo() != null) runner = runner.getTransitionTo();
            if (runner != null && (runner.isStopped() || runner.isHolding())) runner = null;
            if (!applySkinBlocks || skinBlocks[i].permits(runner, runner == null ? -1f : runner.getProgressNs()/1e9f)) animations[i] = runner;
        }
    }

    @Override public ObjectAnimationRunner animation(GunConfig.FxEmitter emitter) {
        for (var runner : animations)
            if (runner != null && emitter.animations.contains(runner.getAnimation().name)) return runner;
        return null;
    }

    @Override public Vec3 fxBoneWorld(String bone, float x, float y, float z, boolean boneSpace, Minecraft mc) {
        if (!fxBoneWorldOffset(bone, x, y, z, boneSpace, mc, fovScale(), scratch, position)) return null;
        return mc.gameRenderer.getMainCamera().getPosition().add(position.x, position.y, position.z);
    }

    @Override public boolean fxBoneWorldOffset(String bone, float x, float y, float z, boolean boneSpace,
                                               Minecraft mc, float fovScale, Matrix4f scratch, Vector3f result) {
        if (!captured.contains(bone)) return false;
        prepareHeldProjection();
        scratch.set(poses.get(bone));
        if (boneSpace) scratch.transformPosition(result.set(x, y, z));
        else result.set(scratch.m30() + x, scratch.m31() + y, scratch.m32() + z);
        if (matchedProjection && heldPoseSources.containsKey(bone))
            heldToWorld.transformPosition(matchedPosition.set(result));
        result.mul(-1f, 1f, -fovScale);
        mc.gameRenderer.getMainCamera().rotation().transform(result);
        if (matchedProjection && heldPoseSources.containsKey(bone)) result.lerp(matchedPosition, normalPoseWeight);
        // Legacy installed packs specify world-space offsets: apply after camera/FOV conversion.
        Vector3f shift = heldWorldOffsets.get(bone);
        if (shift != null) result.add(shift.x * normalPoseWeight, shift.y * normalPoseWeight, shift.z * normalPoseWeight);
        return true;
    }

    @Override public boolean fxBoneWorldPose(String bone, Minecraft mc, float fovScale, Matrix4f result) {
        if (!captured.contains(bone)) return false;
        prepareHeldProjection();
        result.set(poses.get(bone)).scaleLocal(-1f, 1f, -fovScale)
                .rotateLocal(mc.gameRenderer.getMainCamera().rotation());
        if (matchedProjection && heldPoseSources.containsKey(bone)) {
            projectionScratch.set(heldToWorld).mul(poses.get(bone));
            result.lerp(projectionScratch, normalPoseWeight);
        }
        Vector3f shift = heldWorldOffsets.get(bone);
        if (shift != null) result.translateLocal(shift.x * normalPoseWeight, shift.y * normalPoseWeight, shift.z * normalPoseWeight);
        return true;
    }

    /** Oculus may draw the hand before AFTER_PARTICLES. Join snapshots when FX is consumed. */
    private void prepareHeldProjection() {
        long frame = com.example.taczmeshloader.render.BloomPostProcessor.currentFrameId();
        if (matchedProjectionFrame == frame) return;
        matchedProjection = false;
        if (!heldProjectionMatch || normalPoseWeight <= 0f || handProjectionFrame != frame
                || !FxParticles.copyLevelProjection(worldProjection, worldView)) return;
        matchedProjection = FxHandProjection.map(handProjection, handView,
                worldProjection, worldView, heldToWorld, projectionScratch);
        if (matchedProjection) { matchedProjectionFrame = frame; projectionMatches++; }
    }

    private static float fovScale() {
        double item = com.tacz.guns.client.event.CameraSetupEvent.ITEM_MODEL_FOV_DYNAMICS.get();
        double world = com.tacz.guns.client.event.CameraSetupEvent.WORLD_FOV_DYNAMICS.get();
        return item > 0 && world > 0 ? (float)(Math.tan(Math.toRadians(item)/2) / Math.tan(Math.toRadians(world)/2)) : 1f;
    }
}
