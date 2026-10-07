package com.example.taczmeshloader.rig;

import com.example.taczmeshloader.api.IPolyMeshBone;
import com.example.taczmeshloader.core.PolyMeshModel;
import com.example.taczmeshloader.tacz.GunConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * rig 原生姿态驱动的 poly 渲染。
 *
 * 渲染树由“每根 poly 骨直接挂根”的适配器组成，不再依赖 Bedrock
 * 父链逐件换算。每帧从 rig data 数据取 q_rel + 世界位移，直接
 * 旋转/平移该骨自己的网格。
 *
 * <p>哪些骨默认隐藏、在哪个动作的哪段进度窗口隐藏/显示，全部由
 * geo extras 的 rig.parts 提供（每枪一份，代码零骨名零窗口硬编码）。</p>
 */
public class RigPoseModel {

    public static class BoneState {
        String name;
        float px, py, pz;
        float baseX, baseY, baseZ;
        Quaternionf quat = new Quaternionf();
        boolean visible = true;
        boolean defaultHidden;
    }

    private static class Track {
        float[] times;
        float[] px, py, pz;
        float[] qx, qy, qz, qw;
    }

    private static class ActionData {
        String name;
        float[] times;
        Map<String, Track> tracks = new LinkedHashMap<>();
    }

    private final List<BoneState> states = new ArrayList<>();
    private final Map<String, BoneState> stateByName = new HashMap<>();
    private final Map<String, ActionData> actions = new HashMap<>();
    private IPolyMeshBone root;
    private PolyMeshModel polyModel;

    private List<GunConfig.PartVisibility> parts = new ArrayList<>();
    private final Map<String, GunConfig.PartVisibility> partByBone = new HashMap<>();

    /** 由加载方传入该枪的 rig 部件规则（可空/可空列表 = 无额外显隐控制）。 */
    public void setParts(List<GunConfig.PartVisibility> parts) {
        this.parts = parts != null ? parts : new ArrayList<>();
        partByBone.clear();
        for (GunConfig.PartVisibility p : this.parts) {
            partByBone.put(p.bone, p);
        }
        applyPartDefaults();
    }

    private void applyPartDefaults() {
        for (BoneState state : states) {
            GunConfig.PartVisibility p = partByBone.get(state.name);
            state.defaultHidden = p != null && p.defaultHidden;
        }
    }

    public void load(ResourceLocation geoLocation, ResourceLocation rigLocation) throws Exception {
        Minecraft mc = Minecraft.getInstance();

        JsonObject rigJson;
        try (var reader = new InputStreamReader(
                mc.getResourceManager().getResource(rigLocation).orElseThrow().open(),
                StandardCharsets.UTF_8)) {
            rigJson = JsonParser.parseReader(reader).getAsJsonObject();
        }

        // 先建 BoneState 与 Track，根树会引用这些 state。
        JsonObject actionsJson = rigJson.getAsJsonObject("actions");
        parseActions(actionsJson);
        applyPartDefaults();

        JsonObject geoJson;
        try (var reader = new InputStreamReader(
                mc.getResourceManager().getResource(geoLocation).orElseThrow().open(),
                StandardCharsets.UTF_8)) {
            geoJson = JsonParser.parseReader(reader).getAsJsonObject();
        }

        // 以第一动作里的骨清单建树；mesh 必须在 geo poly 骨中存在。
        List<IPolyMeshBone> children = new ArrayList<>();
        for (BoneState state : states) {
            children.add(new PoseBoneAdapter(state));
        }
        root = new RootBone(children);
        polyModel = new PolyMeshModel(root, geoJson);
    }

    private void parseActions(JsonObject actionsJson) {
        if (actionsJson == null) return;
        for (String actionName : actionsJson.keySet()) {
            JsonObject action = actionsJson.getAsJsonObject(actionName);
            ActionData data = new ActionData();
            data.name = actionName;
            JsonArray timeArr = action.getAsJsonArray("times");
            data.times = toFloats(timeArr);
            JsonObject tracksObj = action.getAsJsonObject("tracks");
            for (String boneName : tracksObj.keySet()) {
                JsonObject trackObj = tracksObj.getAsJsonObject(boneName);
                JsonArray pivot = trackObj.getAsJsonArray("pivot");
                BoneState state = stateByName.get(boneName);
                if (state == null) {
                    state = new BoneState();
                    state.name = boneName;
                    stateByName.put(boneName, state);
                    states.add(state);
                }
                if (pivot != null) {
                    state.px = pivot.get(0).getAsFloat();
                    state.py = pivot.get(1).getAsFloat();
                    state.pz = pivot.get(2).getAsFloat();
                    state.baseX = state.px;
                    state.baseY = state.py;
                    state.baseZ = state.pz;
                }
                Track track = new Track();
                track.times = data.times;
                JsonArray posArr = trackObj.getAsJsonArray("pos");
                JsonArray quatArr = trackObj.getAsJsonArray("quat");
                track.px = splitAxis(posArr, 0, 3);
                track.py = splitAxis(posArr, 1, 3);
                track.pz = splitAxis(posArr, 2, 3);
                track.qw = splitAxis(quatArr, 0, 4);
                track.qx = splitAxis(quatArr, 1, 4);
                track.qy = splitAxis(quatArr, 2, 4);
                track.qz = splitAxis(quatArr, 3, 4);
                data.tracks.put(boneName, track);
            }
            actions.put(actionName, data);
        }
    }

    private static float[] toFloats(JsonArray arr) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) out[i] = arr.get(i).getAsFloat();
        return out;
    }

    private static float[] splitAxis(JsonArray arr, int axis, int dim) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).getAsJsonArray().get(axis).getAsFloat();
        }
        return out;
    }

    public void setIdentity() {
        for (BoneState state : states) {
            state.quat.identity();
            state.visible = !state.defaultHidden;
            state.px = state.baseX;
            state.py = state.baseY;
            state.pz = state.baseZ;
        }
    }

    public void update(@Nullable String animName, float progressSec) {
        if (animName == null) {
            setIdentity();
            return;
        }
        ActionData action = actions.get(animName);
        if (action == null) {
            setIdentity();
            return;
        }
        for (Map.Entry<String, Track> entry : action.tracks.entrySet()) {
            BoneState state = stateByName.get(entry.getKey());
            Track track = entry.getValue();
            if (state == null || track == null) continue;
            interpolate(state, track, progressSec);
            state.visible = !state.defaultHidden;
        }
        applyPartVisibility(animName, progressSec);
    }

    /**
     * 按 geo extras 的部件规则复算显隐：
     * <ul>
     *   <li>{@code default_hidden=true} 的骨平时隐藏；</li>
     *   <li>{@code show_windows}：动作匹配且进度落在 [from,to) 时显示；</li>
     *   <li>{@code hide_windows}：动作匹配且进度落在 [from,to) 时隐藏（优先）。</li>
     * </ul>
     */
    private void applyPartVisibility(String animName, float progressSec) {
        if (parts.isEmpty()) return;
        for (GunConfig.PartVisibility p : parts) {
            BoneState state = stateByName.get(p.bone);
            if (state == null) continue;
            boolean hiddenByWindow = false;
            for (GunConfig.Window w : p.hideWindows) {
                if (w.action.equals(animName) && progressSec >= w.from && progressSec < w.to) {
                    hiddenByWindow = true;
                    break;
                }
            }
            if (hiddenByWindow) {
                state.visible = false;
                continue;
            }
            boolean shownByWindow = false;
            for (GunConfig.Window w : p.showWindows) {
                if (w.action.equals(animName) && progressSec >= w.from && progressSec < w.to) {
                    shownByWindow = true;
                    break;
                }
            }
            if (shownByWindow) {
                state.visible = true;
            } else if (p.defaultHidden) {
                state.visible = false;
            }
        }
    }

    private void interpolate(BoneState state, Track track, float sec) {
        if (track.times.length == 0) return;
        int n = track.times.length;
        if (sec <= track.times[0]) {
            applyFrame(state, track, 0);
            return;
        }
        if (sec >= track.times[n - 1]) {
            applyFrame(state, track, n - 1);
            return;
        }
        int idx = 0;
        while (idx + 1 < n && track.times[idx + 1] < sec) idx++;
        float t0 = track.times[idx];
        float t1 = track.times[Math.min(idx + 1, n - 1)];
        float a = t1 <= t0 ? 0f : (sec - t0) / (t1 - t0);
        state.px = state.baseX + track.px[idx] + (track.px[Math.min(idx + 1, n - 1)] - track.px[idx]) * a;
        state.py = state.baseY + track.py[idx] + (track.py[Math.min(idx + 1, n - 1)] - track.py[idx]) * a;
        state.pz = state.baseZ + track.pz[idx] + (track.pz[Math.min(idx + 1, n - 1)] - track.pz[idx]) * a;
        Quaternionf q0 = new Quaternionf(track.qx[idx], track.qy[idx], track.qz[idx], track.qw[idx]);
        Quaternionf q1 = new Quaternionf(
                track.qx[Math.min(idx + 1, n - 1)],
                track.qy[Math.min(idx + 1, n - 1)],
                track.qz[Math.min(idx + 1, n - 1)],
                track.qw[Math.min(idx + 1, n - 1)]);
        q0.slerp(q1, a);
        state.quat.set(q0);
    }

    private void applyFrame(BoneState state, Track track, int i) {
        state.px = state.baseX + track.px[i];
        state.py = state.baseY + track.py[i];
        state.pz = state.baseZ + track.pz[i];
        state.quat.set(track.qx[i], track.qy[i], track.qz[i], track.qw[i]);
    }

    public boolean hasModel() {
        return polyModel != null;
    }

    public void render(PoseStack poseStack, MultiBufferSource buffer, ResourceLocation texture,
                       int light, int overlay, boolean useVBO) {
        if (polyModel == null) return;
        polyModel.renderCutoutOnly(poseStack, buffer, texture, light, overlay, useVBO);
    }

    private static class RootBone implements IPolyMeshBone {
        private final List<IPolyMeshBone> children;

        RootBone(List<IPolyMeshBone> children) {
            this.children = children;
        }

        @Override public String getName() { return "rig_root"; }
        @Override public float getPivotX() { return 0; }
        @Override public float getPivotY() { return 0; }
        @Override public float getPivotZ() { return 0; }
        @Override public float getRotX() { return 0; }
        @Override public float getRotY() { return 0; }
        @Override public float getRotZ() { return 0; }
        @Override public boolean isVisible() { return true; }
        @Override public void applyTransform(PoseStack poseStack) { }
        @Override public List<? extends IPolyMeshBone> getChildren() { return children; }
    }

    private static class PoseBoneAdapter implements IPolyMeshBone {
        private final BoneState state;

        PoseBoneAdapter(BoneState state) {
            this.state = state;
        }

        @Override public String getName() { return state.name; }
        @Override public float getPivotX() { return state.px; }
        @Override public float getPivotY() { return state.py; }
        @Override public float getPivotZ() { return state.pz; }
        @Override public float getRotX() { return 0; }
        @Override public float getRotY() { return 0; }
        @Override public float getRotZ() { return 0; }
        @Override public boolean isVisible() { return state.visible; }
        @Override public void applyTransform(PoseStack ps) {
            ps.translate(state.px, state.py, state.pz);
            ps.mulPose(state.quat);
        }
        @Override public List<? extends IPolyMeshBone> getChildren() {
            return java.util.Collections.emptyList();
        }
    }
}
