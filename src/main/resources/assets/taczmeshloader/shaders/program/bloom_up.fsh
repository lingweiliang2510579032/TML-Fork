#version 150

// 升采样：对更小一级的 mip 做 9 抽样帐篷滤波，再与当前级已有内容相加。
// AddTexture 是当前级目标（降采样链的产物），由 Java 侧以 aux asset 绑定。

uniform sampler2D DiffuseSampler;
uniform sampler2D AddTexture;
uniform vec2 InSize;
uniform vec2 OutSize;

in vec2 texCoord;
out vec4 fragColor;

void main(){
    vec2 t = 1.0 / InSize;

    vec4 c = texture(DiffuseSampler, texCoord + vec2(-t.x, -t.y));
    c += texture(DiffuseSampler, texCoord + vec2(0.0, -t.y)) * 2.0;
    c += texture(DiffuseSampler, texCoord + vec2(t.x, -t.y));
    c += texture(DiffuseSampler, texCoord + vec2(-t.x, 0.0)) * 2.0;
    c += texture(DiffuseSampler, texCoord) * 4.0;
    c += texture(DiffuseSampler, texCoord + vec2(t.x, 0.0)) * 2.0;
    c += texture(DiffuseSampler, texCoord + vec2(-t.x, t.y));
    c += texture(DiffuseSampler, texCoord + vec2(0.0, t.y)) * 2.0;
    c += texture(DiffuseSampler, texCoord + vec2(t.x, t.y));
    c *= 1.0 / 16.0;

    fragColor = c + texture(AddTexture, texCoord);
}
