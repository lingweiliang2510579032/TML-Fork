package com.example.taczmeshloader.render;

/** Piecewise-linear source curves, integrated in normalized lifetime coordinates. */
public final class FxCurveMotion {
    private FxCurveMotion() {}
    public static float value(float[][] curve, float t, int channel) {
        if (t <= curve[0][0]) return curve[0][channel];
        for (int i = 1; i < curve.length; i++) {
            float[] a = curve[i-1], b = curve[i];
            if (t <= b[0]) return a[channel] + (b[channel]-a[channel])*(t-a[0])/(b[0]-a[0]);
        }
        return curve[curve.length-1][channel];
    }

    public static float integral(float[][] curve, float t, int channel) {
        if (t <= 0f) return 0f;
        float result = 0f, begin = 0f;
        for (float[] point : curve) {
            float end = Math.min(t, point[0]);
            if (end > begin) {
                result += (value(curve, begin, channel)+value(curve, end, channel))*(end-begin)*0.5f;
                begin = end;
            }
            if (begin >= t) return result;
        }
        return result + (t-begin)*value(curve, t, channel);
    }
}
