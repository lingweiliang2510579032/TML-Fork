package com.example.taczmeshloader.render;

import org.joml.Matrix4f;

/** Affine map whose world-pass screen XY matches the captured hand pass exactly. */
public final class FxHandProjection {
    private FxHandProjection() {}

    public static boolean map(Matrix4f handProjection, Matrix4f handModelView,
                              Matrix4f worldProjection, Matrix4f worldView,
                              Matrix4f output, Matrix4f scratch) {
        if (!handProjection.isFinite() || !handModelView.isFinite()
                || !worldProjection.isFinite() || !worldView.isFinite()) return false;
        // Minecraft's world perspective: clip W=-eye Z; allow projection jitter/shift.
        if (Math.abs(worldProjection.m23()+1f)>1e-5f || Math.abs(worldProjection.m33())>1e-5f
                || Math.abs(worldProjection.m03())>1e-5f || Math.abs(worldProjection.m13())>1e-5f
                || Math.abs(worldProjection.m10())>1e-5f || Math.abs(worldProjection.m01())>1e-5f
                || Math.abs(worldProjection.m00())<1e-5f || Math.abs(handProjection.m11())<1e-5f
                || Math.abs(worldProjection.m11())<1e-5f || Math.abs(worldView.determinant())<1e-8f) return false;
        float depth = worldProjection.m11()/handProjection.m11();
        if (!(depth>0f) || !Float.isFinite(depth)) return false;
        scratch.set(handProjection).mul(handModelView);
        output.identity();
        for (int col=0; col<4; col++) {
            float w=scratch.get(col,3);
            output.set(col,0,depth*(scratch.get(col,0)+worldProjection.m20()*w)/worldProjection.m00()
                    -(col==3 ? worldProjection.m30()/worldProjection.m00() : 0f));
            output.set(col,1,depth*(scratch.get(col,1)+worldProjection.m21()*w)/worldProjection.m11()
                    -(col==3 ? worldProjection.m31()/worldProjection.m11() : 0f));
            output.set(col,2,-depth*w);
        }
        // Indexed setters do not invalidate JOML's identity/orthonormal fast-path flags.
        output.assume(0);
        scratch.set(worldView).invert().mul(output);
        output.set(scratch);
        return output.isFinite();
    }
}
