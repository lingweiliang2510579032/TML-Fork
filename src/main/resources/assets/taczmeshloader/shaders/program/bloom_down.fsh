#version 150

// 降采样：四角抽样求平均，逐级减半。

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform vec2 OutSize;

in vec2 texCoord;
out vec4 fragColor;

void main(){
    vec2 t = 1.0 / InSize;
    vec4 c = texture(DiffuseSampler, texCoord + vec2(-t.x, -t.y));
    c += texture(DiffuseSampler, texCoord + vec2(t.x, -t.y));
    c += texture(DiffuseSampler, texCoord + vec2(-t.x, t.y));
    c += texture(DiffuseSampler, texCoord + vec2(t.x, t.y));
    fragColor = c * 0.25;
}
