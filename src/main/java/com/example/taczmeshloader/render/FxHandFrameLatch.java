package com.example.taczmeshloader.render;

/** Accept animated hand poses once in their actual render frame, regardless of scene-capture order. */
public final class FxHandFrameLatch {
    private final FxOwnerEpoch owner = new FxOwnerEpoch();
    private long capturedFrame = -1, consumedFrame = -1;

    public void capture(long frame, Object world, Object model, Object emitters, Object stack) {
        capturedFrame = frame;
        owner.bind(world, model, emitters, stack);
    }

    public boolean consume(long frame, Object world, Object model, Object emitters, Object stack) {
        if (frame < 0 || frame != capturedFrame || frame == consumedFrame
                || !owner.matches(world, model, emitters, stack)) return false;
        consumedFrame = frame;
        return true;
    }
}
