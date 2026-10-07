package com.example.taczmeshloader.render;

/** Do not replay an in-flight animation's old notifications after changing its skin. */
public final class FxAnimationBlock {
    private Object blocked;
    private float progress;
    public void block(Object runner, float progress) { blocked = runner; this.progress = progress; }
    public boolean permits(Object runner, float progress) {
        if (runner == null) { blocked = null; return false; }
        if (runner != blocked || progress < this.progress) { blocked = null; return true; }
        return false;
    }
}
