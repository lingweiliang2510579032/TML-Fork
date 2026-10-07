package com.example.taczmeshloader.render;

/** One notification per animation playback; a rewind is a new loop/playback. */
public final class FxAnimationCursor {
    private Object runner;
    private float previous = -1f;
    private boolean changed;

    public boolean advance(Object current, float progress, float at) {
        if (current == null || !Float.isFinite(progress) || progress < 0f) {
            changed = runner != null;
            runner = null;
            previous = -1f;
            return false;
        }
        changed = runner != current || progress < previous;
        if (changed) previous = -1f;
        runner = current;
        boolean fire = Float.isFinite(at) && at >= 0f && at > previous && at <= progress;
        previous = progress;
        return fire;
    }

    public boolean changed() { return changed; }
}
