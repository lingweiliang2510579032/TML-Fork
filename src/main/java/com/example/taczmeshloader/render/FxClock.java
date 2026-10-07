package com.example.taczmeshloader.render;

/** Client visual time in seconds, advanced by render/shot calls and frozen while paused. */
public final class FxClock {
    private long previousNanos;
    private boolean initialized;
    private boolean wasPaused;
    private double seconds;

    public double sample(long nanos, boolean paused) {
        if (initialized && !paused && !wasPaused) {
            seconds += Math.max(0L, nanos - previousNanos) * 1.0e-9;
        }
        previousNanos = nanos;
        initialized = true;
        wasPaused = paused;
        return seconds;
    }

    public void reset() {
        initialized = false;
        wasPaused = false;
        seconds = 0;
    }
}
