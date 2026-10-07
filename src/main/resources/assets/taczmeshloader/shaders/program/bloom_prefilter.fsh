#version 150

// 亮部提取 + 4 抽样降采样（main -> bloom0a，半分辨率）。
// 只有超过阈值的像素进入泛光链，避免整个场景发灰。

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;
uniform float BloomThreshold;
uniform float BloomSoftKnee;

in vec2 texCoord;
out vec4 fragColor;

void main(){
    vec2 t = 1.0 / InSize;
    vec4 c = texture(DiffuseSampler, texCoord);
    c += texture(DiffuseSampler, texCoord + vec2(t.x, 0.0));
    c += texture(DiffuseSampler, texCoord + vec2(0.0, t.y));
    c += texture(DiffuseSampler, texCoord + t);
    c *= 0.25;

    float lum = dot(c.rgb, vec3(0.2126, 0.7152, 0.0722));

    float knee = max(BloomThreshold * BloomSoftKnee, 1.0e-4);
    float soft = clamp(lum - BloomThreshold + knee, 0.0, 2.0 * knee);
    soft = soft * soft / (4.0 * knee);
    float contrib = max(soft, lum - BloomThreshold) / max(lum, 1.0e-4);

    fragColor = vec4(c.rgb * contrib, 1.0);
}
