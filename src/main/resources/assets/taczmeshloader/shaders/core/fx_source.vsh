#version 150
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
uniform int MaterialOperation;
out vec4 sourceColor;
out vec2 texCoord;
flat out ivec2 atlas;
flat out int frameIndex;
flat out float frameMix;
flat out float meshAge;
flat out float meshSeed;
void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    if (MaterialOperation == 4) {
        // NEW_ENTITY uses signed shorts for UV1; recover the entire age payload.
        sourceColor = vec4(exp2(Color.rgb * 16.0) - 1.0, Color.a);
        meshAge = float(UV1.x & 65535) / 65535.0;
        meshSeed = float(UV1.y & 32767) / 32767.0;
        atlas = ivec2(1);
        frameIndex = 0;
        frameMix = 0.0;
    } else {
        sourceColor = vec4(Color.rgb * (float(UV2.x) / 16.0), Color.a);
        atlas = ivec2(UV1.x & 31, (UV1.x >> 5) & 31);
        frameIndex = UV1.y;
        frameMix = float(UV2.y) / 255.0;
        meshAge = 0.0;
        meshSeed = 0.0;
    }
}
