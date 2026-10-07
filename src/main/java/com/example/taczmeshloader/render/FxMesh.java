package com.example.taczmeshloader.render;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.joml.Matrix4f;

/** Immutable, bounded source mesh. Coordinates and wedge UVs are kept separate from unit conversion. */
public final class FxMesh {
    public final float[][] vertices;
    public final Matrix4f basis;
    public final float[] scaleMin, scaleMax, rotationMin, rotationMax;
    public final float[][] scaleCurve;
    public final String rotationOrder, lockedAxes;

    private FxMesh(JsonObject json, int maxVertices) {
        rotationOrder = json.get("rotation_order").getAsString();
        lockedAxes = json.has("locked_axes") ? json.get("locked_axes").getAsString() : "EDVLF_None";
        if (!"pitch_yaw_roll_candidate".equals(rotationOrder) && !"roll_pitch_yaw_candidate".equals(rotationOrder))
            throw new IllegalArgumentException("Mesh rotation convention must be explicit");
        JsonArray rows = json.getAsJsonArray("vertices");
        if (rows == null || rows.size() == 0 || rows.size() > maxVertices || rows.size() % 3 != 0)
            throw new IllegalArgumentException("FX mesh must contain bounded complete triangles");
        vertices = new float[rows.size()][];
        for (int i = 0; i < vertices.length; i++) {
            JsonArray row = rows.get(i).getAsJsonArray();
            if (row.size() != 5 && row.size() != 8) throw new IllegalArgumentException("FX mesh vertex requires xyzuv and optional normal xyz");
            vertices[i] = new float[row.size()];
            for (int j = 0; j < row.size(); j++) vertices[i][j] = finite(row.get(j).getAsFloat());
        }
        JsonArray b = json.getAsJsonArray("basis");
        if (b == null || b.size() != 3) throw new IllegalArgumentException("FX mesh needs explicit source basis");
        basis = parseBasis(b);
        scaleMin = vector(json, "scale_min", 1f);
        scaleMax = json.has("scale_max") ? vector(json, "scale_max", 1f) : scaleMin;
        rotationMin = vector(json, "rotation_min", 0f);
        rotationMax = json.has("rotation_max") ? vector(json, "rotation_max", 0f) : rotationMin;
        if (json.has("scale_curve")) {
            JsonArray curve = json.getAsJsonArray("scale_curve");
            if (curve.size() > 4096) throw new IllegalArgumentException("FX mesh scale curve too large");
            scaleCurve = new float[curve.size()][4];
            for (int i = 0; i < curve.size(); i++) {
                JsonArray row = curve.get(i).getAsJsonArray();
                if (row.size() != 4) throw new IllegalArgumentException("FX mesh scale key requires txyz");
                for (int j = 0; j < 4; j++) scaleCurve[i][j] = finite(row.get(j).getAsFloat());
            }
            java.util.Arrays.sort(scaleCurve, java.util.Comparator.comparingDouble(row -> row[0]));
        } else scaleCurve = new float[][]{{0,1,1,1}};
    }

    public static Matrix4f parseBasis(JsonArray b) {
        if (b == null || b.size() != 3) throw new IllegalArgumentException("FX basis shape");
        Matrix4f basis = new Matrix4f();
        for (int row = 0; row < 3; row++) {
            if (b.get(row).getAsJsonArray().size() != 3) throw new IllegalArgumentException("FX mesh basis shape");
            for (int col = 0; col < 3; col++)
                basis.set(col, row, finite(b.get(row).getAsJsonArray().get(col).getAsFloat()));
        }
        // Indexed writes do not refresh JOML's identity/orthonormal fast-path flags.
        basis.determineProperties();
        return basis;
    }

    private static float finite(float v) {
        if (!Float.isFinite(v)) throw new IllegalArgumentException("Non-finite FX mesh data");
        return v;
    }

    public static float[] vector(JsonObject json, String key, float fallback) {
        if (!json.has(key)) return new float[]{fallback,fallback,fallback};
        JsonArray a = json.getAsJsonArray(key);
        if (a.size() != 3) throw new IllegalArgumentException("FX vector requires 3 components: " + key);
        return new float[]{finite(a.get(0).getAsFloat()),finite(a.get(1).getAsFloat()),finite(a.get(2).getAsFloat())};
    }

    public static FxMesh parse(JsonObject json) {
        return parse(json, false);
    }

    /** Only explicit held source meshes need the higher bound; existing callers retain 4096 triangles. */
    public static FxMesh parse(JsonObject json, boolean held) {
        try { return new FxMesh(json, held ? 32768 : 12288); }
        catch (RuntimeException error) { throw new IllegalArgumentException("Malformed source FX mesh", error); }
    }
}
