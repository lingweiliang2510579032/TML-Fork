#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec4 sourceColor;
out vec2 localUV;
out vec3 viewPosition;
out vec3 viewNormal;
flat out float age;
flat out float particleSeed;
flat out int heldSource;
flat out ivec2 atlas;
flat out int frameIndex;
flat out float frameMix;
void main() {
    vec4 view = ModelViewMat * vec4(Position, 1.0);
    gl_Position = ProjMat * view;
    viewPosition = view.xyz;
    viewNormal = mat3(ModelViewMat) * Normal;
    sourceColor = vec4(exp2(Color.rgb * 16.0) - 1.0, exp2(Color.a * 8.0) - 1.0);
    localUV = UV0;
    age = float(UV1.x & 65535) / 65535.0;
    particleSeed = float(UV2.y & 32767) / 32767.0;
    heldSource = (UV2.y & 32768) != 0 ? 1 : 0;
    int packedFrame = UV1.y & 65535;
    frameIndex = packedFrame & 63;
    atlas = max(ivec2(1), ivec2((packedFrame >> 6) & 31, (packedFrame >> 11) & 31));
    frameMix = float(UV2.x & 255) / 255.0;
}
