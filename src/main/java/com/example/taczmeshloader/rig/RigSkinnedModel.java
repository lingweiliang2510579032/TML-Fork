package com.example.taczmeshloader.rig;

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
 * rig 真蒙皮渲染：网格顶点来自 tacz.blend 原对象，权重来自原顶点组，
 * 每帧按 rig 骨架逐骨做线性混合蒙皮。
 */
public class RigSkinnedModel {

    private static class Track {
        float[] times;
        float[] px, py, pz;
        float[] qx, qy, qz, qw;
    }

    private static class Action {
        float[] times;
        Map<String, Track> tracks = new LinkedHashMap<>();
    }

    private static class PoseBone {
        String name;
        float originX, originY, originZ;
        float tx, ty, tz;
        Quaternionf quat = new Quaternionf();
        Track track;
        float r00, r01, r02, r10, r11, r12, r20, r21, r22;
    }

    private final Map<String, Action> actions = new HashMap<>();
    private final Map<String, PoseBone> bones = new LinkedHashMap<>();

    private float[] restX, restY, restZ;
    private float[] uvU, uvV;
    private int[] influenceCount;
    private int[][] influenceBone;
    private float[][] influenceWeight;
    private float[] skinX, skinY, skinZ;

    public void load(ResourceLocation meshLocation, ResourceLocation rigLocation) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        JsonObject mesh;
        try (var reader = new InputStreamReader(
                mc.getResourceManager().getResource(meshLocation).orElseThrow().open(),
                StandardCharsets.UTF_8)) {
            mesh = JsonParser.parseReader(reader).getAsJsonObject();
        }
        JsonObject rig;
        try (var reader = new InputStreamReader(
                mc.getResourceManager().getResource(rigLocation).orElseThrow().open(),
                StandardCharsets.UTF_8)) {
            rig = JsonParser.parseReader(reader).getAsJsonObject();
        }

        JsonArray names = mesh.getAsJsonArray("bones");
        JsonArray origins = mesh.getAsJsonArray("origins");
        for (int i = 0; i < names.size(); i++) {
            PoseBone b = new PoseBone();
            b.name = names.get(i).getAsString();
            JsonArray o = origins.get(i).getAsJsonArray();
            b.originX = o.get(0).getAsFloat();
            b.originY = o.get(1).getAsFloat();
            b.originZ = o.get(2).getAsFloat();
            b.tx = b.originX;
            b.ty = b.originY;
            b.tz = b.originZ;
            bones.put(b.name, b);
        }

        JsonArray verts = mesh.getAsJsonArray("verts");
        int n = verts.size();
        restX = new float[n]; restY = new float[n]; restZ = new float[n];
        uvU = new float[n]; uvV = new float[n];
        influenceCount = new int[n];
        influenceBone = new int[n][];
        influenceWeight = new float[n][];
        skinX = new float[n]; skinY = new float[n]; skinZ = new float[n];
        for (int i = 0; i < n; i++) {
            JsonObject v = verts.get(i).getAsJsonObject();
            JsonArray p = v.getAsJsonArray("p");
            restX[i] = p.get(0).getAsFloat();
            restY[i] = p.get(1).getAsFloat();
            restZ[i] = p.get(2).getAsFloat();
            JsonArray uv = v.getAsJsonArray("uv");
            uvU[i] = uv.get(0).getAsFloat();
            uvV[i] = uv.get(1).getAsFloat();
            JsonArray w = v.getAsJsonArray("w");
            int cnt = w.size();
            influenceCount[i] = cnt;
            influenceBone[i] = new int[cnt];
            influenceWeight[i] = new float[cnt];
            for (int j = 0; j < cnt; j++) {
                JsonArray inf = w.get(j).getAsJsonArray();
                influenceBone[i][j] = inf.get(0).getAsInt();
                influenceWeight[i][j] = inf.get(1).getAsFloat();
            }
        }

        parseActions(rig.getAsJsonObject("actions"));
    }

    private void parseActions(JsonObject actionsJson) {
        if (actionsJson == null) return;
        for (String actionName : actionsJson.keySet()) {
            JsonObject aObj = actionsJson.getAsJsonObject(actionName);
            Action action = new Action();
            action.times = floats(aObj.getAsJsonArray("times"));
            JsonObject tracks = aObj.getAsJsonObject("tracks");
            for (String boneName : tracks.keySet()) {
                JsonObject tObj = tracks.getAsJsonObject(boneName);
                Track track = new Track();
                track.times = action.times;
                track.px = splitAxis(tObj.getAsJsonArray("pos"), 0, 3);
                track.py = splitAxis(tObj.getAsJsonArray("pos"), 1, 3);
                track.pz = splitAxis(tObj.getAsJsonArray("pos"), 2, 3);
                track.qw = splitAxis(tObj.getAsJsonArray("quat"), 0, 4);
                track.qx = splitAxis(tObj.getAsJsonArray("quat"), 1, 4);
                track.qy = splitAxis(tObj.getAsJsonArray("quat"), 2, 4);
                track.qz = splitAxis(tObj.getAsJsonArray("quat"), 3, 4);
                PoseBone b = bones.get(boneName);
                if (b != null) b.track = track;
                action.tracks.put(boneName, track);
            }
            actions.put(actionName, action);
        }
    }

    private static float[] floats(JsonArray arr) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = arr.get(i).getAsFloat();
        return out;
    }

    private static float[] splitAxis(JsonArray arr, int axis, int dim) {
        float[] out = new float[arr.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.get(i).getAsJsonArray().get(axis).getAsFloat();
        }
        return out;
    }

    public void update(@Nullable String actionName, float progress) {
        Action action = actionName == null ? null : actions.get(actionName);
        for (PoseBone b : bones.values()) {
            b.quat.identity();
            b.tx = b.originX;
            b.ty = b.originY;
            b.tz = b.originZ;
            b.track = action != null ? action.tracks.get(b.name) : null;
            if (b.track == null) {
                setRotation(b, 0, 0, 0, 1);
                continue;
            }
            Track t = b.track;
            if (t.times.length == 0) {
                setRotation(b, 0, 0, 0, 1);
                continue;
            }
            int n = t.times.length;
            if (progress <= t.times[0]) {
                applyFrame(b, 0);
            } else if (progress >= t.times[n - 1]) {
                applyFrame(b, n - 1);
            } else {
                int idx = 0;
                while (idx + 1 < n && t.times[idx + 1] < progress) idx++;
                int j = Math.min(idx + 1, n - 1);
                float a = (t.times[j] <= t.times[idx]) ? 0 : (progress - t.times[idx]) / (t.times[j] - t.times[idx]);
                b.tx = b.originX + lerp(t.px[idx], t.px[j], a);
                b.ty = b.originY + lerp(t.py[idx], t.py[j], a);
                b.tz = b.originZ + lerp(t.pz[idx], t.pz[j], a);
                Quaternionf q0 = new Quaternionf(t.qx[idx], t.qy[idx], t.qz[idx], t.qw[idx]);
                Quaternionf q1 = new Quaternionf(t.qx[j], t.qy[j], t.qz[j], t.qw[j]);
                q0.slerp(q1, a);
                setRotation(b, q0.x, q0.y, q0.z, q0.w);
            }
        }
        skin();
    }

    private void applyFrame(PoseBone b, int i) {
        Track t = b.track;
        b.tx = b.originX + t.px[i];
        b.ty = b.originY + t.py[i];
        b.tz = b.originZ + t.pz[i];
        setRotation(b, t.qx[i], t.qy[i], t.qz[i], t.qw[i]);
    }

    private static float lerp(float a, float b, float f) {
        return a + (b - a) * f;
    }

    private void setRotation(PoseBone b, float x, float y, float z, float w) {
        b.quat.set(x, y, z, w).normalize();
        x = b.quat.x; y = b.quat.y; z = b.quat.z; w = b.quat.w;
        float xx = x * x, yy = y * y, zz = z * z;
        float xy = x * y, xz = x * z, yz = y * z;
        float wx = w * x, wy = w * y, wz = w * z;
        b.r00 = 1 - 2 * (yy + zz); b.r01 = 2 * (xy - wz); b.r02 = 2 * (xz + wy);
        b.r10 = 2 * (xy + wz); b.r11 = 1 - 2 * (xx + zz); b.r12 = 2 * (yz - wx);
        b.r20 = 2 * (xz - wy); b.r21 = 2 * (yz + wx); b.r22 = 1 - 2 * (xx + yy);
    }

    private void skin() {
        for (int i = 0; i < restX.length; i++) {
            float sx = 0, sy = 0, sz = 0;
            for (int j = 0; j < influenceCount[i]; j++) {
                PoseBone b = getBone(influenceBone[i][j]);
                if (b == null) continue;
                float dx = restX[i] - b.originX;
                float dy = restY[i] - b.originY;
                float dz = restZ[i] - b.originZ;
                float rx = b.r00 * dx + b.r01 * dy + b.r02 * dz;
                float ry = b.r10 * dx + b.r11 * dy + b.r12 * dz;
                float rz = b.r20 * dx + b.r21 * dy + b.r22 * dz;
                float w = influenceWeight[i][j];
                sx += w * (rx + b.tx);
                sy += w * (ry + b.ty);
                sz += w * (rz + b.tz);
            }
            skinX[i] = sx;
            skinY[i] = sy;
            skinZ[i] = sz;
        }
    }

    private PoseBone getBone(int idx) {
        int i = 0;
        for (PoseBone b : bones.values()) {
            if (i++ == idx) return b;
        }
        return null;
    }

    public void render(PoseStack ps, MultiBufferSource buffer,
                       net.minecraft.client.renderer.RenderType renderType,
                       int light, int overlay) {
        var consumer = buffer.getBuffer(renderType);
        var pose = ps.last().pose();
        var normalPose = ps.last().normal();
        for (int i = 0; i < skinX.length; i += 3) {
            float ax = skinX[i], ay = skinY[i], az = skinZ[i];
            float bx = skinX[i+1], by = skinY[i+1], bz = skinZ[i+1];
            float cx = skinX[i+2], cy = skinY[i+2], cz = skinZ[i+2];
            float ux = bx-ax, uy = by-ay, uz = bz-az;
            float vx = cx-ax, vy = cy-ay, vz = cz-az;
            float nx = uy*vz-uz*vy, ny = uz*vx-ux*vz, nz = ux*vy-uy*vx;
            float len = (float) Math.sqrt(nx*nx+ny*ny+nz*nz);
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; }
            emit(consumer, pose, normalPose, i, light, overlay, nx, ny, nz);
            emit(consumer, pose, normalPose, i+1, light, overlay, nx, ny, nz);
            emit(consumer, pose, normalPose, i+2, light, overlay, nx, ny, nz);
        }
    }

    private void emit(com.mojang.blaze3d.vertex.VertexConsumer consumer,
                      org.joml.Matrix4f pose, org.joml.Matrix3f normalPose,
                      int i, int light, int overlay,
                      float nx, float ny, float nz) {
        consumer.vertex(pose, skinX[i], skinY[i], skinZ[i])
                .color(1f, 1f, 1f, 1f)
                .uv(uvU[i], uvV[i])
                .overlayCoords(overlay)
                .uv2(light)
                .normal(normalPose, nx, ny, nz)
                .endVertex();
    }
}
