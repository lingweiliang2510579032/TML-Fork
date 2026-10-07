#version 150

// 合成：背景（原场景） + 泛光 * 亮度自适应系数。
// 暗部给更强的泛光、亮部收敛，这样夜间枪口闪光会明显炸开而白天场景不会过曝。

uniform sampler2D DiffuseSampler;
uniform sampler2D Background;
uniform vec2 OutSize;
uniform float BloomIntensive;
uniform float BloomBase;
uniform float BloomThresholdUp;
uniform float BloomThresholdDown;

in vec2 texCoord;
out vec4 fragColor;

float calculateLuminance(vec3 color) {
    return dot(color, vec3(0.299, 0.587, 0.114));
}

void main() {
    vec3 background = texture(Background, texCoord).rgb;
    vec3 bloom = texture(DiffuseSampler, texCoord).rgb;

    float luminance = calculateLuminance(background);
    float bloomFactor = (1.0 - luminance) * (BloomThresholdUp - BloomThresholdDown)
                      + BloomThresholdDown + BloomBase;

    fragColor = vec4(background + bloom * BloomIntensive * bloomFactor, 1.0);
}
