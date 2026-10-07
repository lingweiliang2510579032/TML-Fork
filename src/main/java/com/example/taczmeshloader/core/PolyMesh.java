package com.example.taczmeshloader.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * poly_mesh の 1 描画ユニット（ライトレベル・キャッシュ対応 VBO 版）。
 *
 * <h3>シェーダー切り替え時の影反転バグ修正</h3>
 * Oculus がシェーダーパックを ON/OFF すると、シャドウパスの有無や
 * 法線変換行列の期待値が変わるため、古いライトレベルで焼き込んだ
 * VBO がそのまま残ると影の表示が反転する。
 *
 * 修正: {@link #invalidateVboCache()} を用意し、シェーダー状態が変わった
 * フレームで全 VBO キャッシュを破棄する。呼び出しは {@link PolyMeshModel}
 * 経由で行われ、{@link com.example.taczmeshloader.render.ShaderStateTracker}
 * がフレーム開始時に差分を検知して通知する。
 */
public class PolyMesh {

    // =========================================================
    // ▼ 描画設定トグル ▼
    // =========================================================
    private static final boolean FLIP_MODEL_X    = false;
    private static final boolean FLIP_MODEL_Y    = true;
    private static final boolean FLIP_UV_V       = true;
    private static final boolean FORCE_FLAT_SHADING = true;
    private static final boolean INVERT_FLAT_NORMAL = false;
    // =========================================================

    // ---- ライトレベルごとのVBOキャッシュ（最大8個） ----
    private final Map<Integer, VertexBuffer> vboCache = new LinkedHashMap<Integer, VertexBuffer>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, VertexBuffer> eldest) {
            if (size() > 8) {
                if (eldest.getValue() != null) eldest.getValue().close();
                if (evictCallback != null) evictCallback.accept(eldest.getKey());
                return true;
            }
            return false;
        }
    };

    /** ライト値ごとのキャッシュが淘汰されたときにモデル側へ通知するコールバック */
    private java.util.function.IntConsumer evictCallback = null;

    public void setEvictCallback(java.util.function.IntConsumer callback) {
        this.evictCallback = callback;
    }

    // ---- フォールバック用ベイク済み配列 ----
    private final float[] bakedX, bakedY, bakedZ;
    private final float[] bakedNX, bakedNY, bakedNZ;
    private final float[] bakedU, bakedV;
    private final int vertexCount;
    private final boolean smoothNormals;

    public PolyMesh(JsonObject meshObj, float texWidth, float texHeight, float[] absPivot) {
        float pivotX = absPivot[0], pivotY = absPivot[1], pivotZ = absPivot[2];

        boolean normalizedUvs = meshObj.has("normalized_uvs") && meshObj.get("normalized_uvs").getAsBoolean();
        this.smoothNormals = meshObj.has("smooth_normals") && meshObj.get("smooth_normals").getAsBoolean();
        float[][] positions = parse2DArray(meshObj.getAsJsonArray("positions"), 3);
        float[][] normals   = parse2DArray(meshObj.getAsJsonArray("normals"), 3);
        float[][] uvs       = parse2DArray(meshObj.getAsJsonArray("uvs"), 2);
        int[][][] polys     = parse3DArray(meshObj.getAsJsonArray("polys"));

        int totalVerts = 0;
        for (int[][] poly : polys) {
            if (smoothNormals && poly.length != 3) {
                throw new IllegalArgumentException("smooth_normals requires triangular polys");
            }
            if (poly.length >= 3) totalVerts += smoothNormals ? 3 : (poly.length == 3 ? 4 : poly.length);
        }
        this.vertexCount = totalVerts;
        this.bakedX  = new float[totalVerts]; this.bakedY  = new float[totalVerts]; this.bakedZ  = new float[totalVerts];
        this.bakedNX = new float[totalVerts]; this.bakedNY = new float[totalVerts]; this.bakedNZ = new float[totalVerts];
        this.bakedU  = new float[totalVerts]; this.bakedV  = new float[totalVerts];

        int vIdx = 0;
        for (int[][] poly : polys) {
            if (poly.length < 3) continue;
            float faceNx = 0, faceNy = 0, faceNz = 0;
            if (!smoothNormals && FORCE_FLAT_SHADING) {
                float[] v0 = positions[poly[0][0]], v1 = positions[poly[1][0]], v2 = positions[poly[2][0]];
                float ux = v1[0]-v0[0], uy = v1[1]-v0[1], uz = v1[2]-v0[2];
                float vx = v2[0]-v0[0], vy = v2[1]-v0[1], vz = v2[2]-v0[2];
                faceNx = INVERT_FLAT_NORMAL ? vy*uz-vz*uy : uy*vz-uz*vy;
                faceNy = INVERT_FLAT_NORMAL ? vz*ux-vx*uz : uz*vx-ux*vz;
                faceNz = INVERT_FLAT_NORMAL ? vx*uy-vy*ux : ux*vy-uy*vx;
                float len = (float)Math.sqrt(faceNx*faceNx + faceNy*faceNy + faceNz*faceNz);
                if (len > 1e-6f) { faceNx/=len; faceNy/=len; faceNz/=len; }
            }
            int drawCount = smoothNormals ? 3 : (poly.length == 3 ? 4 : poly.length);
            for (int i = 0; i < drawCount; i++) {
                int srcIdx = (poly.length == 3 && i == 3) ? 2 : i;
                int[] vi = poly[srcIdx];
                float[] pos = positions[vi[0]]; float[] uv = uvs[vi[2]];
                bakedX[vIdx] = (FLIP_MODEL_X ? -(pos[0]-pivotX) : (pos[0]-pivotX)) / 16.0f;
                bakedY[vIdx] = (FLIP_MODEL_Y ? -(pos[1]-pivotY) : (pos[1]-pivotY)) / 16.0f;
                bakedZ[vIdx] = (pos[2]-pivotZ) / 16.0f;
                if (smoothNormals) {
                    if (vi[1] < 0 || vi[1] >= normals.length) {
                        throw new IllegalArgumentException("smooth_normals has an invalid normal index");
                    }
                    float[] n = normals[vi[1]];
                    float length = (float) Math.sqrt(n[0]*n[0] + n[1]*n[1] + n[2]*n[2]);
                    if (!Float.isFinite(length) || length < 1e-6f) {
                        throw new IllegalArgumentException("smooth_normals requires finite nonzero normals");
                    }
                    // Authored direction vectors, not a cross product in reflected geo coordinates.
                    bakedNX[vIdx] = (FLIP_MODEL_X ? -n[0] : n[0]) / length;
                    bakedNY[vIdx] = (FLIP_MODEL_Y ? -n[1] : n[1]) / length;
                    bakedNZ[vIdx] = n[2] / length;
                } else if (FORCE_FLAT_SHADING) {
                    bakedNX[vIdx] = FLIP_MODEL_X ? -faceNx : faceNx;
                    bakedNY[vIdx] = FLIP_MODEL_Y ? -faceNy : faceNy;
                    bakedNZ[vIdx] = faceNz;
                } else {
                    float[] n = normals[vi[1]];
                    bakedNX[vIdx] = FLIP_MODEL_X ? -n[0] : n[0]; bakedNY[vIdx] = FLIP_MODEL_Y ? -n[1] : n[1]; bakedNZ[vIdx] = n[2];
                }
                bakedU[vIdx] = normalizedUvs ? uv[0] : (uv[0]/texWidth);
                float v = normalizedUvs ? uv[1] : (uv[1]/texHeight);
                bakedV[vIdx] = FLIP_UV_V ? 1.0f - v : v;
                vIdx++;
            }
        }
    }

    // =========================================================================
    // VBO 管理
    // =========================================================================

    private static final org.apache.logging.log4j.Logger MESH_PERF_LOG =
            org.apache.logging.log4j.LogManager.getLogger("MeshyLoaderPerf");

    public void ensureUploaded(int packedLight) {
        if (vertexCount == 0 || vboCache.containsKey(packedLight)) return;

        long t0 = System.nanoTime();

        VertexBuffer vertexBuffer = new VertexBuffer(VertexBuffer.Usage.STATIC);

        BufferBuilder builder = new BufferBuilder(vertexCount * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
        builder.begin(getDrawMode(), DefaultVertexFormat.NEW_ENTITY);

        for (int i = 0; i < vertexCount; i++) {
            builder.vertex(bakedX[i], bakedY[i], bakedZ[i])
                    .color(1f, 1f, 1f, 1f)
                    .uv(bakedU[i], bakedV[i])
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight) // 要求されたライトレベルを焼き付ける
                    .normal(bakedNX[i], bakedNY[i], bakedNZ[i])
                    .endVertex();
        }

        vertexBuffer.bind();
        vertexBuffer.upload(builder.end());
        VertexBuffer.unbind();

        vboCache.put(packedLight, vertexBuffer);

        double ms = (System.nanoTime() - t0) / 1_000_000.0;
        MESH_PERF_LOG.info("[MeshyPerf] VBO upload: light={} vertexCount={} cacheSizeAfter={} tookMs={} screenOpen={}",
                packedLight, vertexCount, vboCache.size(), String.format("%.3f", ms),
                net.minecraft.client.Minecraft.getInstance().screen != null);
    }

    public void drawVBO(Matrix4f posePose, int packedLight) {
        VertexBuffer vbo = vboCache.get(packedLight);
        if (vbo == null) return;

        vbo.bind();
        vbo.drawWithShader(posePose, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    public boolean isVboReady(int packedLight) { return vboCache.containsKey(packedLight); }
    public int getVertexCount() { return vertexCount; }
    public boolean hasSmoothNormals() { return smoothNormals; }
    public VertexFormat.Mode getDrawMode() {
        return smoothNormals ? VertexFormat.Mode.TRIANGLES : VertexFormat.Mode.QUADS;
    }

    /**
     * シェーダー状態変化時に VBO キャッシュを全破棄する。
     *
     * Oculus がシェーダーパックを切り替えると、シャドウパスの有無や
     * 法線行列の扱いが変わる。古い VBO には以前の状態で焼き込んだ
     * ライト値・法線が残っているため、そのまま使うと影が反転する。
     * キャッシュを破棄して次フレームで再アップロードさせることで解決する。
     */
    public void invalidateVboCache() {
        invalidateStarflowVbo();          // 流动纹理缓冲同样烘了光照/法线，状态变了必须一起作废
        int hadEntries = vboCache.size();
        for (VertexBuffer vbo : vboCache.values()) {
            if (vbo != null) vbo.close();
        }
        vboCache.clear();
        if (hadEntries > 0) {
            MESH_PERF_LOG.info("[MeshyPerf] VBO cache invalidated: entriesCleared={} screenOpen={}",
                    hadEntries, net.minecraft.client.Minecraft.getInstance().screen != null);
        }
    }

    // =========================================================================
    // 流动纹理叠加层专用 VBO（UV 偏移 / 顶点色烘进缓冲，量化值没变就复用）
    // =========================================================================
    private VertexBuffer starVbo = null;
    private String starKey = null;

    /**
     * 流动纹理叠加层用：把 UV 偏移与顶点色烘进缓冲。
     *
     * <p>为什么：即时模式每帧都要重吐一遍这些顶点并上传（流动材质实测 4~7ms/帧），而同样的几何走
     * VBO 只要零点几毫秒。偏移量化到 1/4096、颜色量化到 1/100 后进键——纹理滚动速度 0.035/s，
     * 量化后约一秒才变一次，所以绝大多数帧是"零上传"，观感与逐帧重传完全一致。</p>
     */
    public void ensureStarflowUploaded(float r, float g, float b, float a, int packedLight) {
        if (vertexCount == 0) return;
        // 偏移不进键：它走纹理矩阵（每帧只改 uniform）⇒ 这里只有颜色/光照变了才重传
        String key = Math.round(r * 100f) + "," + Math.round(g * 100f) + "," + Math.round(b * 100f) + "|"
                + Math.round(a * 100f) + "|" + packedLight;
        if (starVbo != null && key.equals(starKey)) return;

        if (starVbo == null) starVbo = new VertexBuffer(VertexBuffer.Usage.STATIC);
        BufferBuilder builder = new BufferBuilder(vertexCount * DefaultVertexFormat.NEW_ENTITY.getVertexSize());
        builder.begin(getDrawMode(), DefaultVertexFormat.NEW_ENTITY);
        for (int i = 0; i < vertexCount; i++) {
            builder.vertex(bakedX[i], bakedY[i], bakedZ[i])
                    .color(r, g, b, a)
                    .uv(bakedU[i], bakedV[i])
                    .overlayCoords(OverlayTexture.NO_OVERLAY)
                    .uv2(packedLight)
                    .normal(bakedNX[i], bakedNY[i], bakedNZ[i])
                    .endVertex();
        }
        starVbo.bind();
        starVbo.upload(builder.end());
        VertexBuffer.unbind();
        starKey = key;
    }

    public void drawStarflowVbo(Matrix4f pose) {
        if (starVbo == null) return;
        starVbo.bind();
        starVbo.drawWithShader(pose, RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
    }

    public void invalidateStarflowVbo() {
        if (starVbo != null) {
            starVbo.close();
            starVbo = null;
        }
        starKey = null;
    }

    public void close() {
        invalidateStarflowVbo();
        for (VertexBuffer vbo : vboCache.values()) {
            if (vbo != null) { vbo.close(); }
        }
        vboCache.clear();
    }

    // =========================================================================
    // フォールバック: VertexConsumer パス
    // =========================================================================

    public void compileConsumer(PoseStack.Pose pose, VertexConsumer consumer,
                                int lightmap, int overlay,
                                float red, float green, float blue, float alpha) {
        compileConsumer(pose, consumer, lightmap, overlay, red, green, blue, alpha, 0f, 0f);
    }

    /**
     * 同上，但顶点 UV 额外加一个偏移（{@code uvOffU}/{@code uvOffV}）。
     *
     * <p>流动纹理（星空滚动）就用这个：偏移加在**顶点 UV** 上，于是 RenderType 可以固定不变、
     * 只建一次；早期版本把偏移塞进 RenderType 的 key，导致每滚动一步就新建一个 RenderType
     * （约几十个/秒），既拖帧又堆缓存。</p>
     */
    public void compileConsumer(PoseStack.Pose pose, VertexConsumer consumer,
                                int lightmap, int overlay,
                                float red, float green, float blue, float alpha,
                                float uvOffU, float uvOffV) {
        emitConsumer(pose, consumer, lightmap, overlay, red, green, blue, alpha, uvOffU, uvOffV, false);
    }

    /** Only use with a TRIANGLES consumer. Other entry points retain their QUADS contract. */
    public void compileTrianglesConsumer(PoseStack.Pose pose, VertexConsumer consumer,
                                         int lightmap, int overlay,
                                         float red, float green, float blue, float alpha) {
        if (!smoothNormals) throw new IllegalStateException("Triangle consumer requires smooth_normals");
        emitConsumer(pose, consumer, lightmap, overlay, red, green, blue, alpha, 0f, 0f, true);
    }

    private void emitConsumer(PoseStack.Pose pose, VertexConsumer consumer,
                              int lightmap, int overlay,
                              float red, float green, float blue, float alpha,
                              float uvOffU, float uvOffV, boolean triangles) {
        if (vertexCount == 0) return;
        Matrix4f pm = pose.pose();
        Matrix3f nm = pose.normal();
        boolean expandQuads = smoothNormals && !triangles;
        int outputCount = expandQuads ? vertexCount / 3 * 4 : vertexCount;
        float normalSign = smoothNormals ? 1f : -1f;
        for (int output = 0; output < outputCount; output++) {
            int i = expandQuads ? output / 4 * 3 + Math.min(output % 4, 2) : output;
            consumer.vertex(pm, bakedX[i], bakedY[i], bakedZ[i])
                    .color(red, green, blue, alpha)
                    .uv(bakedU[i] + uvOffU, bakedV[i] + uvOffV)
                    .overlayCoords(overlay)
                    .uv2(lightmap)
                    .normal(nm, normalSign*bakedNX[i], normalSign*bakedNY[i], normalSign*bakedNZ[i])
                    .endVertex();
        }
    }

    public void compile(PoseStack.Pose pose, VertexConsumer consumer,
                        int lightmap, int overlay, float red, float green, float blue, float alpha) {
        compileConsumer(pose, consumer, lightmap, overlay, red, green, blue, alpha);
    }

    // =========================================================================
    // パースユーティリティ
    // =========================================================================

    private float[][] parse2DArray(JsonArray array, int dim) {
        if (array == null) return new float[0][0];
        float[][] result = new float[array.size()][dim];
        for (int i = 0; i < array.size(); i++) {
            JsonArray sub = array.get(i).getAsJsonArray();
            for (int j = 0; j < Math.min(dim, sub.size()); j++) result[i][j] = sub.get(j).getAsFloat();
        }
        return result;
    }

    private int[][][] parse3DArray(JsonArray array) {
        if (array == null) return new int[0][0][0];
        int[][][] result = new int[array.size()][][];
        for (int i = 0; i < array.size(); i++) {
            JsonArray face = array.get(i).getAsJsonArray();
            result[i] = new int[face.size()][3];
            for (int j = 0; j < face.size(); j++) {
                JsonArray vd = face.get(j).getAsJsonArray();
                result[i][j][0] = vd.get(0).getAsInt();
                result[i][j][1] = vd.get(1).getAsInt();
                result[i][j][2] = vd.get(2).getAsInt();
            }
        }
        return result;
    }
}
