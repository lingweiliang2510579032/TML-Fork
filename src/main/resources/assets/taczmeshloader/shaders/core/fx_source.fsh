#version 150
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform int MaterialOperation;
uniform float TextureScale;
uniform float TexturePower;
uniform int TextureSrgb;
in vec4 sourceColor;
in vec2 texCoord;
flat in ivec2 atlas;
flat in int frameIndex;
flat in float frameMix;
flat in float meshAge;
flat in float meshSeed;
out vec4 fragColor;

// VehicleEffects.MP_EFX_TheReleaseOfMagic_001: reachable source material graph.
// Linear input textures; no additional sRGB transform. One seed is retained per particle.
vec4 releaseOfMagicDynamic(float normalizedAge, float uniform01) {
    float t = clamp(normalizedAge, 0.0, 1.0);
    vec2 rRange = t <= 0.5
        ? mix(vec2(1.0), vec2(0.20000000298023224, 0.5), t / 0.5)
        : mix(vec2(0.20000000298023224, 0.5), vec2(0.0), (t - 0.5) / 0.5);
    const float bKnot = 0.05000000074505806;
    float b = t <= bKnot
        ? mix(0.6000000238418579, 0.10000000149011612, t / bKnot)
        : mix(0.10000000149011612, 0.0, (t - bKnot) / (1.0 - bKnot));
    const float aKnot = 0.20000000298023224;
    float a = t <= aKnot ? mix(0.6000000238418579, -1.0, t / aKnot) : -1.0;
    return vec4(mix(rRange.x, rRange.y, uniform01), 1.0 - t, b, a);
}

vec4 releaseOfMagic(vec2 uv, vec4 particleColor, vec4 dynamic4) {
    // Smoke/light use periodic source UVs; these private textures retain default repeat addressing.
    float smokeBase = texture(Sampler0, fract(uv)).r;
    float smokeTiled = texture(Sampler0, fract(uv * vec2(8.0, 1.0))).r;
    float add9 = smokeBase + smokeTiled;
    float add8 = dynamic4.r + smokeBase;
    float if6 = add9 >= dynamic4.g ? 1.0 : dynamic4.r;
    float if5 = add9 < add8 ? if6 : 0.0;
    vec2 lightUV = uv * vec2(5.0, 1.0) + vec2(dynamic4.b, 0.0);
    vec4 light = texture(Sampler1, fract(lightUV));
    vec3 multiply20 = light.rgb * if5;
    vec3 emissive = multiply20 * particleColor.rgb;
    vec2 scanUV = vec2(uv.x + dynamic4.a, uv.y * 8.0);
    // Emulate source clamp-to-edge without changing the shared GL texture object's wrap state.
    vec2 edge = 0.5 / vec2(textureSize(Sampler2, 0));
    vec3 scan = texture(Sampler2, clamp(scanUV, edge, vec2(1.0) - edge)).rgb;
    vec3 opacityVector = clamp(multiply20 * (light.a * if6 * particleColor.a * scan), vec3(0.0), vec3(1.0));
    // Candidate inherited ConstantClamp [0,1] and scalar input truncation to .r.
    // Keep straight-alpha source RGB; do not fold opacity into HDR colour.
    return vec4(emissive, opacityVector.r);
}

vec4 sourceSample(vec2 uv) {
    vec4 value = texture(Sampler0, uv);
    if (TextureSrgb != 0) value.rgb = mix(value.rgb / 12.92,
            pow((value.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), value.rgb));
    return value;
}
void main() {
    if (MaterialOperation == 4) {
        fragColor = releaseOfMagic(texCoord, sourceColor, releaseOfMagicDynamic(meshAge, meshSeed));
        return;
    }
    vec4 texel = sourceSample(texCoord);
    if (frameMix > 0.0 && frameIndex + 1 < atlas.x * atlas.y) {
        ivec2 cell = ivec2(frameIndex % atlas.x, frameIndex / atlas.x);
        ivec2 nextCell = ivec2((frameIndex + 1) % atlas.x, (frameIndex + 1) / atlas.x);
        texel = mix(texel, sourceSample(texCoord + vec2(nextCell - cell) / vec2(atlas)), frameMix);
    }
    if (MaterialOperation == 1) {
        fragColor = vec4(sourceColor.rgb, texel.r * sourceColor.a);
    } else if (MaterialOperation == 2) {
        fragColor = vec4(pow(max(vec3(0), texel.rgb * TextureScale), vec3(TexturePower)) * sourceColor.rgb, sourceColor.a);
    } else if (MaterialOperation == 3) {
        fragColor = vec4(texel.rgb * TextureScale * sourceColor.rgb, 1.0);
    } else {
        fragColor = texel * sourceColor;
    }
}
