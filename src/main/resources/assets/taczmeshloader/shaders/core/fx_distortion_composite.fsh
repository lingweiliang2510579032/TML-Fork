#version 150

uniform sampler2D SceneColor;
uniform sampler2D Distortion;
uniform vec2 TargetSize;
out vec4 fragColor;

void main() {
    vec2 uv = gl_FragCoord.xy / TargetSize;
    // RGBA stores positive X/Y and negative X/Y in source framebuffer pixels.
    vec4 packedOffset = texture(Distortion, uv);
    vec2 offset = packedOffset.xy - packedOffset.zw;
    if (all(equal(offset, vec2(0.0)))) discard;
    vec2 halfPixel = 0.5 / TargetSize;
    vec2 displaced = clamp(uv + offset / TargetSize, halfPixel, vec2(1.0) - halfPixel);
    fragColor = texture(SceneColor, displaced);
}
