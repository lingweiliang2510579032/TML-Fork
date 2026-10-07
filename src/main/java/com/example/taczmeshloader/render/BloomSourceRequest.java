package com.example.taczmeshloader.render;

/** Pure Java, render-thread-owned, single-consumption request contract. */
public final class BloomSourceRequest<T> {
    public static final float MAX_INTENSITY = 0.5f;
    public static final int RADIUS_PIXELS = 2;
    /** Reusable descriptor. Requests copy it so a producer cannot mutate a pending snapshot. */
    public static final class Target<T> {
        private T target;
        private int width, height, colorTexture, framebuffer;
        public Target<T> set(T target, int width, int height, int colorTexture, int framebuffer) {
            this.target=target; this.width=width; this.height=height;
            this.colorTexture=colorTexture; this.framebuffer=framebuffer;
            return this;
        }
        private void copy(Target<T> other) {
            set(other.target, other.width, other.height, other.colorTexture, other.framebuffer);
        }
        public T target() { return target; }
        public int width() { return width; }
        public int height() { return height; }
        public int colorTexture() { return colorTexture; }
        public int framebuffer() { return framebuffer; }
        public boolean matches(Target<T> other) {
            return other != null && target==other.target && width==other.width && height==other.height
                    && colorTexture==other.colorTexture && framebuffer==other.framebuffer;
        }
    }
    /** Borrowed for the current render call only; the next beginFrame invalidates it. */
    public static final class Request<T> {
        private final Target<T> source = new Target<>(), main = new Target<>();
        private float intensity;
        public Target<T> source() { return source; }
        public Target<T> main() { return main; }
        public float intensity() { return intensity; }
    }
    private long frameId;
    private boolean open;
    private final Request<T> request = new Request<>();
    private boolean pending;

    /** Exactly once at render HEAD, including frames which do not render a world. */
    public long beginFrame() {
        frameId = frameId == Long.MAX_VALUE ? 1 : frameId + 1;
        pending = false;
        request.source.set(null,0,0,0,0);
        request.main.set(null,0,0,0,0);
        open = true;
        return frameId;
    }
    public long currentFrameId() { return open ? frameId : 0; }

    /** First valid submission wins. Invalid/duplicate submissions do not replace it. */
    public boolean submit(long token, Target<T> source, Target<T> main, float intensity) {
        if (!open || token != frameId || token == 0 || pending
                || !Float.isFinite(intensity) || intensity <= 0 || !legalTargets(source, main)) return false;
        request.source.copy(source);
        request.main.copy(main);
        request.intensity = Math.min(intensity, MAX_INTENSITY);
        pending = true;
        return true;
    }

    /** Close even an empty/disabled frame; a late producer cannot spill into the next frame. */
    public Request<T> consume() {
        Request<T> result = pending ? request : null;
        pending = false;
        open = false;
        return result;
    }

    public static <T> boolean legalTargets(Target<T> source, Target<T> main) {
        return source != null && main != null && source.target() != null && main.target() != null
                && source.target() != main.target()
                && source.width() > 0 && source.height() > 0
                && source.width() == main.width() && source.height() == main.height()
                && source.colorTexture() > 0 && main.colorTexture() > 0
                && source.colorTexture() != main.colorTexture()
                && source.framebuffer() > 0 && main.framebuffer() >= 0
                && source.framebuffer() != main.framebuffer();
    }
}
