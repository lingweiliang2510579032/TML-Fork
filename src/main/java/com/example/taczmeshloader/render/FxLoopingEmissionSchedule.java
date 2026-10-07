package com.example.taczmeshloader.render;

import java.util.function.Supplier;

/** Explicit held emitter loops. Timing is emitter-local, not animation time. */
public final class FxLoopingEmissionSchedule {
    @FunctionalInterface public interface Birth { void emit(double time, float emitterAge); }
    private final double firstStart, duration, period, rate;
    private final int loops;
    private final Supplier<float[][]> bursts;
    private long index;
    private FxEmissionSchedule current;
    private int remaining;

    public FxLoopingEmissionSchedule(double activation, double delay, double duration, double rate,
                                    int loops, boolean firstDelayOnly, Supplier<float[][]> bursts) {
        if (!Double.isFinite(activation) || !Double.isFinite(delay) || !Double.isFinite(duration)
                || duration <= 0 || delay < 0 || loops < 0) throw new IllegalArgumentException("Invalid held emitter timing");
        this.firstStart = activation + delay;
        this.duration = duration;
        this.period = duration + (firstDelayOnly ? 0 : delay);
        this.rate = rate;
        this.loops = loops;
        this.bursts = bursts;
    }

    public void advance(double now, double maxLife, int budget, Birth birth) {
        if (!Double.isFinite(now) || now < firstStart) return;
        // Drop whole expired cycles; a hidden/stalled frame must not replay ancient Core bursts.
        long oldest = Math.max(0, (long)Math.floor((now - maxLife - firstStart - duration) / period));
        if (oldest > index) { index = oldest; current = null; }
        remaining = Math.max(0, Math.min(128, budget));
        int examined = 0;
        while ((loops == 0 || index < loops) && examined++ < 128) {
            double start = firstStart + index * period;
            if (now < start) return;
            if (current == null) current = new FxEmissionSchedule(start, duration, rate, bursts.get());
            current.advance(now, maxLife, remaining, time -> {
                remaining--;
                birth.emit(time, (float)(time - start));
            });
            if (!current.done()) return;
            current = null;
            index++;
        }
        // Only exceptionally short cycles can exhaust the guard. Consume dropped history too.
        if (examined > 128) {
            long latest = Math.max(index, (long)Math.floor((now - firstStart) / period));
            index = latest;
            if (loops == 0 || index < loops) {
                double start = firstStart + index * period;
                current = new FxEmissionSchedule(start, duration, rate, bursts.get());
                current.advance(now, maxLife, 0, ignored -> {});
                if (current.done()) { current = null; index++; }
            }
        }
    }
}
