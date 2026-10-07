package com.example.taczmeshloader.render;

/** Render-owner identity includes the effective skin emitter list and actual melee stack. */
public final class FxOwnerEpoch {
    private Object level, model, emitters, token;
    public boolean matches(Object level, Object model, Object emitters, Object token) {
        return this.level == level && this.model == model && this.emitters == emitters && this.token == token;
    }
    public void bind(Object level, Object model, Object emitters, Object token) {
        this.level = level; this.model = model; this.emitters = emitters; this.token = token;
    }
    public void clear() { bind(null, null, null, null); }
}
