package com.example.taczmeshloader.render;

/** Finite Cascade emission. Birth times are independent of render/tick frequency. */
public final class FxEmissionSchedule {
    @FunctionalInterface public interface Birth { void emit(double time); }
    private final double start, duration, rate;
    private final float[][] bursts;
    private double previous = -1;
    private long consumedRateIndex;
    private boolean done;

    public FxEmissionSchedule(double start, double duration, double rate, float[][] bursts) {
        this.start = start;
        this.duration = Math.max(0, duration);
        this.rate = Math.max(0, Math.min(128, rate));
        this.bursts = bursts;
    }

    /** Consumes missed events too; overload never creates a deferred particle avalanche. */
    public void advance(double now, double maxLife, int budget, Birth birth) {
        if (done || now < start) return;
        double elapsed = Math.max(0, now - start);
        if (elapsed < previous) return;
        int remaining = Math.max(0, Math.min(128, budget));
        if (bursts != null) for (float[] b : bursts) {
            if (b.length < 2 || b[0] < 0 || b[0] > duration
                    || b[0] <= previous || b[0] > elapsed) continue;
            if (start + b[0] + maxLife <= now) continue;
            int count = Math.min(remaining, Math.max(0, (int) b[1]));
            for (int n = 0; n < count; n++) birth.emit(start + b[0]);
            remaining -= count;
        }
        if (rate > 0) {
            long first = Math.max(consumedRateIndex + 1, (long) Math.floor((elapsed - maxLife) * rate) + 1);
            long last = (long) Math.floor(Math.min(elapsed, duration) * rate + 1e-7);
            for (long n = first; n <= last && remaining > 0; n++) {
                double t = n / rate;
                if (t >= duration) break;
                birth.emit(start + t);
                remaining--;
            }
            consumedRateIndex = Math.max(consumedRateIndex, last);
        }
        previous = elapsed;
        done = elapsed >= duration;
    }

    public boolean done() { return done; }
}
