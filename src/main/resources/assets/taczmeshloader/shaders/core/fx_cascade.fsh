#version 150
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform sampler2D Sampler4;
uniform mat4 ModelViewMat;
uniform sampler2D FxSceneDepth;
uniform sampler2D FxHeldDepth;
uniform mat4 FxToHandClip;
uniform vec2 FxHandDepthRange;
uniform float FxHeldOcclusion;
uniform mat4 ProjMat;
uniform vec2 FxViewport;
uniform float FxTime;
uniform int MaterialOperation;
uniform int MaterialPass;
uniform float SourceUnit;
in vec4 sourceColor;
in vec2 localUV;
in vec3 viewPosition;
in vec3 viewNormal;
flat in float age;
flat in float particleSeed;
flat in int heldSource;
flat in ivec2 atlas;
flat in int frameIndex;
flat in float frameMix;
out vec4 fragColor;
// Source Texture.SRGB was not serialized: inherited true is a documented candidate default.
vec4 sourceSample(sampler2D image, vec2 uv) {
    vec4 value = texture(image, uv);
    value.rgb = mix(value.rgb / 12.92, pow((value.rgb + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), value.rgb));
    return value;
}
vec4 atlasSample() {
    int frame = clamp(frameIndex, 0, atlas.x * atlas.y - 1);
    ivec2 cell = ivec2(frame % atlas.x, frame / atlas.x);
    vec4 value = sourceSample(Sampler0, (localUV + vec2(cell)) / vec2(atlas));
    if (frameMix > 0.0 && frame + 1 < atlas.x * atlas.y) {
        ivec2 nextCell = ivec2((frame + 1) % atlas.x, (frame + 1) / atlas.x);
        value = mix(value, sourceSample(Sampler0, (localUV + vec2(nextCell)) / vec2(atlas)), frameMix);
    }
    return value;
}
float linearDepth(float windowDepth) {
    // Standard MC perspective projection. The captured texture and draw must share projection.
    return ProjMat[3][2] / (windowDepth * 2.0 - 1.0 + ProjMat[2][2]);
}
float sphereMask(vec2 uv, float radius, float hardness) {
    float distance = length(uv - vec2(0.5));
    if (hardness >= 1.0) return distance < radius ? 1.0 : 0.0;
    return clamp((1.0 - distance / radius) / max(1.0 - hardness, 0.00001), 0.0, 1.0);
}

// Generated from original M_Titan_Boost graph. Candidate only; no invented texture/alpha fallback.
// UV = original mesh UV0; cameraVectorTangent is normalized pixel-to-camera in mesh tangent space.
// particleColor is Cascade MeshEmitterVertexColor, not PSKX per-vertex colour. vertexColor is unused.
// Time is source material global elapsed seconds, not normalized particle age.
// Sampler WRAP is an inherited-default assumption (AddressX/Y not serialized).
// SRGB flags are explicit unresolved source-texture defaults, never inferred from names.
// Depth arguments MUST be original-equivalent unnormalized linear UE depth units. No constant-depth fallback.
struct EffectBoostInputs { vec2 uv; vec4 particleColor; vec4 dynamic4; float timeSeconds; vec3 cameraVectorTangent; vec4 vertexColor; };
struct EffectBoostParams { vec4 Color1; vec4 Color2; float X_Component; float Y_Component; float EdgeFadeOut; float TextureRotation; float DepthBiasAlpha; };
vec2 effectBoostRotateUV(vec2 uv, vec2 center, float angle) {
    vec2 p = uv-center; float c=cos(angle), s=sin(angle);
    return vec2(c*p.x-s*p.y, s*p.x+c*p.y)+center;
}
vec4 effectBoostSample(sampler2D image, vec2 uv, bool sourceSrgb) {
    vec4 result=texture(image,uv);
    if (sourceSrgb) result.rgb=mix(result.rgb/12.92,pow((result.rgb+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),result.rgb));
    return result;
}
float effectBoostDepthAlpha(float alpha, float bias, float biasScale, float sourceDepthUE, float destinationDepthUE) {
    // Epic UE3 DepthBiasBlendUsage equation; Alpha input retained as scalar opacity multiplier.
    float distance=destinationDepthUE-sourceDepthUE;
    float fadeDistance=(1.0-bias)*biasScale;
    if (distance<0.0) return 0.0;
    if (fadeDistance <= 0.0 || distance>fadeDistance) return alpha;
    return alpha*clamp(distance/fadeDistance,0.0,1.0);
}

EffectBoostParams effectBoostOutParams() {
    EffectBoostParams result;
    result.Color1 = vec4(1.0, 0.24005317688, 0.127138257027, 5.0);
    result.X_Component = 1.0;
    result.Y_Component = 1.0;
    result.EdgeFadeOut = 3.0;
    result.TextureRotation = 0.5;
    result.Color2 = vec4(1.0, 0.289628326893, 0.0, 20.0);
    result.DepthBiasAlpha = 0.8;
    return result;
}

EffectBoostParams effectBoostCenterParams() {
    EffectBoostParams result;
    result.Color1 = vec4(1.0, 0.211019575596, 0.0851098895073, 5.0);
    result.X_Component = 3.0;
    result.Y_Component = 1.0;
    result.EdgeFadeOut = 4.0;
    result.TextureRotation = 0.5;
    result.Color2 = vec4(1.0, 0.289628326893, 0.0, 20.0);
    result.DepthBiasAlpha = 0.8;
    return result;
}

vec3 effectBoostEmissive(EffectBoostInputs inputs, EffectBoostParams params, sampler2D colorNoise, sampler2D noiseRay, bool colorNoiseSrgb, bool noiseRaySrgb, float sourceDepthUE, float destinationDepthUE) {
    vec4 n20851 = params.Color1; // MaterialExpressionVectorParameter_1
    vec3 n19029 = (n20851).rgb * vec3((n20851).a); // MaterialExpressionMultiply_17
    vec4 n20852 = params.Color2; // MaterialExpressionVectorParameter_2
    vec3 n19024 = (n20852).rgb * vec3((n20852).a); // MaterialExpressionMultiply_2
    vec2 n20219 = inputs.uv * vec2(1.0, 1.0); // MaterialExpressionTextureCoordinate_2
    float n17250 = (n20219).r; // MaterialExpressionComponentMask_2
    float n19028 = n17250 * n17250; // MaterialExpressionMultiply_15
    vec3 n18273 = mix(n19029, n19024, n19028); // MaterialExpressionLinearInterpolate_1
    vec4 n18396 = inputs.particleColor; // MaterialExpressionMeshEmitterVertexColor_1
    vec3 n19037 = n18273 * (n18396).rgb; // MaterialExpressionMultiply_5
    return n19037;
}


float effectBoostOpacity(EffectBoostInputs inputs, EffectBoostParams params, sampler2D colorNoise, sampler2D noiseRay, bool colorNoiseSrgb, bool noiseRaySrgb, float sourceDepthUE, float destinationDepthUE) {
    vec2 n20218 = inputs.uv * vec2(1.0, 5.0); // MaterialExpressionTextureCoordinate_1
    float n19840 = params.X_Component; // MaterialExpressionScalarParameter_1
    float n19841 = params.Y_Component; // MaterialExpressionScalarParameter_2
    vec2 n17027 = vec2(n19840, n19841); // MaterialExpressionAppendVector_1
    vec2 n19035 = n20218 * n17027; // MaterialExpressionMultiply_22
    vec2 n20220 = inputs.uv * vec2(10.0, 8.0); // MaterialExpressionTextureCoordinate_3
    float n20777 = inputs.timeSeconds; // MaterialExpressionTime_1
    vec4 n18372 = inputs.dynamic4; // MaterialExpressionMeshEmitterDynamicParameter_1
    float n19041 = n20777 * (n18372).g; // MaterialExpressionMultiply_10
    vec2 n19396 = n20220 + n19041 * vec2(4.0, 0.0); // MaterialExpressionPanner_6
    vec4 n20555 = effectBoostSample(colorNoise, n19396, colorNoiseSrgb); // MaterialExpressionTextureSample_7
    vec2 n19397 = n20220 + n19041 * vec2(10.0, 0.0); // MaterialExpressionPanner_7
    vec4 n20556 = effectBoostSample(colorNoise, n19397, colorNoiseSrgb); // MaterialExpressionTextureSample_8
    float n16911 = (n20555).r + (n20556).r; // MaterialExpressionAdd_3
    float n17727 = 0.02; // MaterialExpressionConstant_7
    float n19025 = n16911 * n17727; // MaterialExpressionMultiply_11
    vec2 n16912 = n19035 + vec2(n19025); // MaterialExpressionAdd_5
    vec2 n19394 = n16912 + n19041 * vec2(4.0, -1.0); // MaterialExpressionPanner_1
    float n19843 = params.TextureRotation; // MaterialExpressionScalarParameter_4
    vec2 n19683 = effectBoostRotateUV(n19394, vec2(0.5, 0.0), n19843 * 3.141592); // MaterialExpressionRotator_1
    vec4 n20553 = effectBoostSample(noiseRay, n19683, noiseRaySrgb); // MaterialExpressionTextureSample_1
    vec3 n18087 = clamp((n20553).rgb, 0.0, 1.0); // MaterialExpressionConstantClamp_3
    vec2 n20221 = inputs.uv * vec2(1.5, 6.0); // MaterialExpressionTextureCoordinate_4
    vec2 n17028 = vec2(n19840, n19841); // MaterialExpressionAppendVector_3
    vec2 n19034 = n20221 * n17028; // MaterialExpressionMultiply_21
    vec2 n19395 = n19034 + n19041 * vec2(10.0, -1.0); // MaterialExpressionPanner_2
    vec2 n19684 = effectBoostRotateUV(n19395, vec2(0.5, 0.5), n19843 * 3.141592); // MaterialExpressionRotator_2
    vec4 n20554 = effectBoostSample(noiseRay, n19684, noiseRaySrgb); // MaterialExpressionTextureSample_2
    vec3 n18089 = clamp((n20554).rgb, 0.0, 1.0); // MaterialExpressionConstantClamp_6
    vec3 n16910 = n18087 + n18089; // MaterialExpressionAdd_2
    vec2 n20219 = inputs.uv * vec2(1.0, 1.0); // MaterialExpressionTextureCoordinate_2
    float n17249 = (n20219).r; // MaterialExpressionComponentMask_1
    float n19283 = 1.0 - n17249; // MaterialExpressionOneMinus_1
    float n17975 = (n19283 + -0.025) * 2.0; // MaterialExpressionConstantBiasScale_1
    float n18088 = clamp(n17975, 0.0, 1.0); // MaterialExpressionConstantClamp_4
    float n17250 = (n20219).r; // MaterialExpressionComponentMask_2
    float n19028 = n17250 * n17250; // MaterialExpressionMultiply_15
    float n19027 = n18088 * n19028; // MaterialExpressionMultiply_13
    vec3 n19039 = n16910 * vec3(n19027); // MaterialExpressionMultiply_7
    vec3 n19026 = n19039 * n19039; // MaterialExpressionMultiply_12
    vec3 n17058 = inputs.cameraVectorTangent; // MaterialExpressionCameraVector_1
    vec3 n17955 = vec3(0.0, 0.0, 1.0); // MaterialExpressionConstant3Vector_1
    float n18217 = dot(n17058, n17955); // MaterialExpressionDotProduct_1
    float n16685 = abs(n18217); // MaterialExpressionAbs_1
    float n19842 = params.EdgeFadeOut; // MaterialExpressionScalarParameter_3
    float n19598 = pow(n16685, n19842); // MaterialExpressionPower_1
    vec3 n19030 = n19026 * vec3(n19598); // MaterialExpressionMultiply_18
    vec4 n18396 = inputs.particleColor; // MaterialExpressionMeshEmitterVertexColor_1
    vec3 n19038 = n19030 * vec3((n18396).a); // MaterialExpressionMultiply_6
    float n19844 = params.DepthBiasAlpha; // MaterialExpressionScalarParameter_5
    float n18174 = effectBoostDepthAlpha((n19038).r, n19844, 1024.0, sourceDepthUE, destinationDepthUE); // MaterialExpressionDepthBiasedAlpha_1
    return n18174;
}


float effectBoostDistortion(EffectBoostInputs inputs, EffectBoostParams params, sampler2D colorNoise, sampler2D noiseRay, bool colorNoiseSrgb, bool noiseRaySrgb, float sourceDepthUE, float destinationDepthUE) {
    vec2 n20219 = inputs.uv * vec2(1.0, 1.0); // MaterialExpressionTextureCoordinate_2
    float n17249 = (n20219).r; // MaterialExpressionComponentMask_1
    float n19283 = 1.0 - n17249; // MaterialExpressionOneMinus_1
    float n17975 = (n19283 + -0.025) * 2.0; // MaterialExpressionConstantBiasScale_1
    float n18088 = clamp(n17975, 0.0, 1.0); // MaterialExpressionConstantClamp_4
    float n17250 = (n20219).r; // MaterialExpressionComponentMask_2
    float n19028 = n17250 * n17250; // MaterialExpressionMultiply_15
    float n19027 = n18088 * n19028; // MaterialExpressionMultiply_13
    vec3 n17058 = inputs.cameraVectorTangent; // MaterialExpressionCameraVector_1
    vec3 n17955 = vec3(0.0, 0.0, 1.0); // MaterialExpressionConstant3Vector_1
    float n18217 = dot(n17058, n17955); // MaterialExpressionDotProduct_1
    float n16685 = abs(n18217); // MaterialExpressionAbs_1
    float n19842 = params.EdgeFadeOut; // MaterialExpressionScalarParameter_3
    float n19598 = pow(n16685, n19842); // MaterialExpressionPower_1
    vec2 n20220 = inputs.uv * vec2(10.0, 8.0); // MaterialExpressionTextureCoordinate_3
    float n20777 = inputs.timeSeconds; // MaterialExpressionTime_1
    vec4 n18372 = inputs.dynamic4; // MaterialExpressionMeshEmitterDynamicParameter_1
    float n19041 = n20777 * (n18372).g; // MaterialExpressionMultiply_10
    vec2 n19396 = n20220 + n19041 * vec2(4.0, 0.0); // MaterialExpressionPanner_6
    vec4 n20555 = effectBoostSample(colorNoise, n19396, colorNoiseSrgb); // MaterialExpressionTextureSample_7
    vec2 n19397 = n20220 + n19041 * vec2(10.0, 0.0); // MaterialExpressionPanner_7
    vec4 n20556 = effectBoostSample(colorNoise, n19397, colorNoiseSrgb); // MaterialExpressionTextureSample_8
    float n16911 = (n20555).r + (n20556).r; // MaterialExpressionAdd_3
    float n18086 = clamp(n16911, 0.0, 1.0); // MaterialExpressionConstantClamp_2
    float n19040 = n19598 * n18086; // MaterialExpressionMultiply_9
    float n19033 = n19040 * (n18372).r; // MaterialExpressionMultiply_3
    vec4 n18396 = inputs.particleColor; // MaterialExpressionMeshEmitterVertexColor_1
    float n19036 = n19033 * (n18396).a; // MaterialExpressionMultiply_4
    float n19032 = n19598 * n19036; // MaterialExpressionMultiply_20
    float n19031 = n19027 * n19032; // MaterialExpressionMultiply_19
    float n19844 = params.DepthBiasAlpha; // MaterialExpressionScalarParameter_5
    float n18175 = effectBoostDepthAlpha(n19031, n19844, 1024.0, sourceDepthUE, destinationDepthUE); // MaterialExpressionDepthBiasedAlpha_2
    return n18175;
}


vec4 effectBoost_OutDynamic(float normalizedAge) { return vec4(mix(4.0, 1.0, normalizedAge), 2.0, 0.0, 0.0); }

vec4 effectBoost_CoreDynamic(float normalizedAge) { return vec4(mix(1.0, 1.0, normalizedAge), 2.0, 0.0, 0.0); }

vec4 effectBoost_CenterDynamic(float normalizedAge) { return vec4(mix(1.0, 0.0, normalizedAge), 2.0, 0.0, 0.0); }

// Spawn-time scale helpers use cooked LockedAxes results, then source SizeScale (.03,.03,.03).
// These scales are dimensionless. Mesh unit conversion and socket transform belong to the host.
vec3 effectBoost_OutScale() { return vec3(0.75,0.5,0.5)*0.03; }
vec3 effectBoost_CoreScale(vec2 random01) {
    vec2 size=mix(vec2(0.6,0.5),vec2(0.7,0.7),random01);
    return vec3(size.x,size.y,size.y)*0.03;
}
vec3 effectBoost_CenterScale() { return vec3(0.75)*0.03; }
// UE source-axis turns. Sample ONCE per particle; this is not a continuous rotation-rate curve.
vec3 effectBoostSpawnRotationTurns(float random01) { return vec3(mix(-1.0,1.0,random01),0.0,0.0); }
vec4 effectBoostParticleColor(float normalizedAge) { return vec4(1.0); }




// SOURCE_KNIFE_BEGIN: generated source graphs; existing generic operations remain intact.
float sourceHermite(float a,float b,float ta,float tb,float t) {
    return (2.0*t*t*t-3.0*t*t+1.0)*a+(t*t*t-2.0*t*t+t)*ta+(-2.0*t*t*t+3.0*t*t)*b+(t*t*t-t*t)*tb;
}
float knifeSphere(vec2 a,vec2 b,float radius,float hardness) {
    float d=length(a-b);
    if (hardness>=1.0) return d<radius?1.0:0.0;
    return clamp((1.0-d/max(radius,0.000001))/max(1.0-hardness,0.000001),0.0,1.0);
}
vec4 knifeSample(sampler2D image,vec2 uv,bool srgb,ivec2 address) {
    vec2 edge=0.5/vec2(textureSize(image,0));
    for(int i=0;i<2;i++) {
        if(address[i]==2)uv[i]=1.0-abs(mod(uv[i],2.0)-1.0);
        if(address[i]!=0)uv[i]=clamp(uv[i],edge[i],1.0-edge[i]);
    }
    vec4 result=texture(image,uv);
    if(srgb)result.rgb=mix(result.rgb/12.92,pow((result.rgb+0.055)/1.055,vec3(2.4)),step(vec3(0.04045),result.rgb));
    return result;
}
vec4 knifeAtlasSample(sampler2D image,bool srgb) {
    int frame=clamp(frameIndex,0,atlas.x*atlas.y-1);
    ivec2 cell=ivec2(frame%atlas.x,frame/atlas.x);
    vec4 result=knifeSample(image,(localUV+vec2(cell))/vec2(atlas),srgb,ivec2(0));
    if(frameMix>0.0 && frame+1<atlas.x*atlas.y) {
        ivec2 next=ivec2((frame+1)%atlas.x,(frame+1)/atlas.x);
        result=mix(result,knifeSample(image,(localUV+vec2(next))/vec2(atlas),srgb,ivec2(0)),frameMix);
    }
    return result;
}
vec3 knifeWorldToTangent(vec3 worldVector) {
    // Explicit coordinate-port choice: MC world axes stand in for source world axes.
    // UV derivatives preserve mirrored UV handedness without fabricating a mesh tangent stream.
    vec3 dpdx=dFdx(viewPosition),dpdy=dFdy(viewPosition);
    vec2 duvdx=dFdx(localUV),duvdy=dFdy(localUV);
    float det=duvdx.x*duvdy.y-duvdx.y*duvdy.x;
    if(abs(det)<0.00000001)return vec3(0.0); // Degenerate UV has no recoverable tangent frame.
    vec3 n=normalize(viewNormal);
    vec3 tangent=(dpdx*duvdy.y-dpdy*duvdx.y)/det;
    tangent-=n*dot(n,tangent);
    if(dot(tangent,tangent)<0.00000001)return vec3(0.0);
    tangent=normalize(tangent);
    vec3 sourceBitangent=(-dpdx*duvdy.x+dpdy*duvdx.x)/det;
    float handedness=dot(cross(n,tangent),sourceBitangent)<0.0?-1.0:1.0;
    vec3 bitangent=cross(n,tangent)*handedness;
    vec3 viewVector=mat3(ModelViewMat)*worldVector;
    return vec3(dot(viewVector,tangent),dot(viewVector,bitangent),dot(viewVector,n));
}
vec3 knifeRotateAboutAxis(vec3 position,vec3 pivot,vec3 axis,float angle) {
    if(dot(axis,axis)<0.00000001)return vec3(0.0);
    vec3 p=position-pivot;
    float c=cos(angle),s=sin(angle);
    // UE RotateAboutAxis returns a displacement; the source HueShift graph adds Position back.
    return p*c+cross(axis,p)*s+axis*dot(axis,p)*(1.0-c)-p;
}

void knifeMaterial100(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(6.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),0.0,0.0,0.0);
 vec4 n1 = sourceColor; // material node VertexColor
 vec4 n2 = knifeSample(Sampler0,localUV,true,ivec2(0,0)); // material node TextureSample
 vec3 n3 = ((n1).rgb*vec3((n2).g)); // material node Multiply
 float n4 = ((n2).g*(n1).a); // material node Multiply
 float n5 = 0.0010000000475; // material node ScalarParameter
 float n6 = destinationDepthUE; // material node SceneDepth
 float n7 = sourceDepthUE; // material node PixelDepth
 float n8 = (n6-n7); // material node Subtract
 float n9 = n5; // material node FunctionInput
 float n10 = (n8*n9); // material node Multiply
 float n11 = clamp(n10,0.0,1.0); // material node ConstantClamp
 float n12 = n11; // material node MaterialFunctionCall
 float n13 = (n4*n12); // material node Multiply
 vec4 n14 = dynamic4; // material node DynamicParameter
 float n15 = ((n2).g*(n14).r); // material node Multiply
 emissive=n3; opacity=n13; distortion=vec2(n15);
}

void knifeMaterial101(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(6.0,1.0,clamp((age-(0.0))/1.0,0.0,1.0)):1.0),0.0,0.0,0.0);
 vec4 n1 = sourceColor; // material node VertexColor
 vec4 n2 = knifeSample(Sampler0,localUV,true,ivec2(0,0)); // material node TextureSample
 vec3 n3 = ((n1).rgb*vec3((n2).g)); // material node Multiply
 float n4 = ((n2).g*(n1).a); // material node Multiply
 float n5 = 0.0010000000475; // material node ScalarParameter
 float n6 = destinationDepthUE; // material node SceneDepth
 float n7 = sourceDepthUE; // material node PixelDepth
 float n8 = (n6-n7); // material node Subtract
 float n9 = n5; // material node FunctionInput
 float n10 = (n8*n9); // material node Multiply
 float n11 = clamp(n10,0.0,1.0); // material node ConstantClamp
 float n12 = n11; // material node MaterialFunctionCall
 float n13 = (n4*n12); // material node Multiply
 vec4 n14 = dynamic4; // material node DynamicParameter
 float n15 = ((n2).g*(n14).r); // material node Multiply
 emissive=n3; opacity=n13; distortion=vec2(n15);
}

void knifeMaterial102(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 float n1 = 1.0; // material node ScalarParameter
 vec4 n2 = vec4(0.12724429369,0.315527558327,1.0,1.0); // material node VectorParameter
 float n3 = 0.0; // material node Constant
 float n4 = 0.10000000149; // material node ScalarParameter
 vec2 n5 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n6 = vec4(1.0,1.0,1.0,1.0); // material node Constant4Vector
 vec2 n7 = (n6).rg; // material node ComponentMask
 vec2 n8 = (n5*n7); // material node Multiply
 float n9 = 2.0; // material node Constant
 vec2 n10 = (n7/vec2(n9)); // material node Divide
 vec2 n11 = (n8-n10); // material node Subtract
 float n12 = 0.5; // material node Constant
 vec2 n13 = (n11*vec2(n12)); // material node Multiply
 float n14 = dot(n13,n13); // material node DotProduct
 float n15 = sqrt(n14); // material node SquareRoot
 float n16 = (n4/n15); // material node Divide
 float n17 = (n3+n16); // material node Add
 vec3 n18 = ((n2).rgb*vec3(n17)); // material node Multiply
 vec3 n19 = (vec3(n1)*n18); // material node Multiply
 vec4 n20 = sourceColor; // material node VertexColor
 vec3 n21 = (n19*(n20).rgb); // material node Multiply
 vec4 n22 = sourceColor; // material node VertexColor
 vec2 n23 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n24 = n23; // material node FunctionInput
 float n25 = 0.0; // material node Constant
 float n26 = n25; // material node FunctionInput
 float n27 = 0.0; // material node Constant
 float n28 = n27; // material node FunctionInput
 vec2 n29 = vec2(n26,n28); // material node AppendVector
 vec2 n30 = (n24+n29); // material node Add
 float n31 = 0.5; // material node Constant
 float n32 = 0.5; // material node Constant
 float n33 = n32; // material node FunctionInput
 float n34 = 0.0; // material node Constant
 float n35 = n34; // material node FunctionInput
 float n36 = knifeSphere(n30,vec2(n31),n33,n35); // material node SphereMask
 float n37 = n36; // material node MaterialFunctionCall
 float n38 = (n37*n37); // material node Multiply
 float n39 = (n38*n38); // material node Multiply
 float n40 = 1.0; // material node Constant
 float n41 = n40; // material node StaticSwitchParameter
 float n42 = (n39*n41); // material node Multiply
 float n43 = ((n22).a*n42); // material node Multiply
 float n44 = 0.10000000149; // material node ScalarParameter
 float n45 = destinationDepthUE; // material node SceneDepth
 float n46 = sourceDepthUE; // material node PixelDepth
 float n47 = (n45-n46); // material node Subtract
 float n48 = n44; // material node FunctionInput
 float n49 = (n47*n48); // material node Multiply
 float n50 = clamp(n49,0.0,1.0); // material node ConstantClamp
 float n51 = n50; // material node MaterialFunctionCall
 float n52 = (n43*n51); // material node Multiply
 emissive=n21; opacity=n52; distortion=vec2(0.0,0.0);
}

void knifeMaterial103(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec2 n1 = vec2(0.649999976158,1.0); // material node Constant2Vector
 vec2 n2 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n3 = (n1*n2); // material node Multiply
 vec2 n4 = (n3+FxTime*vec2(-0.125,0.0)); // material node Panner
 vec4 n5 = knifeSample(Sampler0,n4,true,ivec2(0,1)); // material node TextureSample
 vec2 n6 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n7 = vec2(0.800000011921,1.0); // material node Constant2Vector
 vec2 n8 = (n6*n7); // material node Multiply
 vec2 n9 = (n8+FxTime*vec2(0.0900000035763,0.0)); // material node Panner
 vec4 n10 = knifeSample(Sampler0,n9,true,ivec2(0,1)); // material node TextureSample
 vec3 n11 = ((n5).rgb+(n10).rgb); // material node Add
 float n12 = 4.0; // material node Constant
 vec4 n13 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n14 = (vec3(n12)*(n13).rgb); // material node Multiply
 vec3 n15 = (n11*n14); // material node Multiply
 emissive=n15; opacity=1.0; distortion=vec2(0.0,0.0);
}

void knifeMaterial104(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(1.998344,0.0,clamp((age-(4e-06))/0.999996,0.0,1.0)):0.0),(age<0.405706?sourceHermite(0.0,0.102165,0.0,0.308907970275,clamp((age-(-0.005949))/0.411655,0.0,1.0)):(age<1.002919?mix(0.102165,0.504422,clamp((age-(0.405706))/0.597213,0.0,1.0)):0.504422)),0.0,0.0);
 vec3 n1 = vec3(1.0,1.0,1.0); // material node Constant3Vector
 vec4 n2 = sourceColor; // material node VertexColor
 vec3 n3 = (n1*(n2).rgb); // material node Multiply
 vec4 n4 = sourceColor; // material node VertexColor
 vec2 n5 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n6 = 0.5; // material node Constant
 float n7 = knifeSphere(n5,vec2(n6),0.5,0.25); // material node SphereMask
 float n8 = (1.0-n7); // material node OneMinus
 float n9 = (1.0-n8); // material node OneMinus
 float n10 = 5.0; // material node Constant
 float n11 = pow(max(n9,0.0),n10); // material node Power
 float n12 = ((n4).a*n11); // material node Multiply
 float n13 = clamp(n12,0.0,1.0); // material node ConstantClamp
 emissive=n3; opacity=n13; distortion=vec2(0.0,0.0);
}

void knifeMaterial105(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(100.0,0.0,clamp((age-(0.8))/0.2,0.0,1.0)):0.0),0.0,0.0,0.0);
 vec4 n1 = sourceColor; // material node VertexColor
 vec4 n2 = sourceColor; // material node VertexColor
 vec2 n3 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n4 = vec4(0.5,0.5,0.0,1.0); // material node VectorParameter
 vec2 n5 = ((n4).rgb).rg; // material node ComponentMask
 float n6 = 0.5; // material node ScalarParameter
 float n7 = 0.0; // material node ScalarParameter
 float n8 = knifeSphere(n3,n5,n6,n7); // material node SphereMask
 float n9 = 2.5; // material node ScalarParameter
 float n10 = pow(max(n8,0.0),n9); // material node Power
 float n11 = 1.0; // material node ScalarParameter
 float n12 = (n10*n11); // material node Multiply
 vec2 n13 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n14 = 0.25; // material node ScalarParameter
 float n15 = 0.0; // material node ScalarParameter
 float n16 = knifeSphere(n13,n5,n14,n15); // material node SphereMask
 float n17 = 1.0; // material node ScalarParameter
 float n18 = (n16*n17); // material node Multiply
 float n19 = (n12-n18); // material node Subtract
 float n20 = clamp(n19,0.0,1.0); // material node ConstantClamp
 vec2 n21 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n22 = 0.34999999404; // material node ScalarParameter
 float n23 = 0.0; // material node ScalarParameter
 float n24 = knifeSphere(n21,n5,n22,n23); // material node SphereMask
 float n25 = 1.0; // material node Constant
 float n26 = pow(max(n24,0.0),n25); // material node Power
 float n27 = 3.0; // material node Constant
 float n28 = (n26*n27); // material node Multiply
 float n29 = (n16-n28); // material node Subtract
 float n30 = clamp(n29,0.0,1.0); // material node ConstantClamp
 float n31 = (n20+n30); // material node Add
 float n32 = clamp(n31,0.0,1.0); // material node ConstantClamp
 float n33 = 0.40000000596; // material node ScalarParameter
 float n34 = (n32*n33); // material node Multiply
 float n35 = ((n2).a*n34); // material node Multiply
 emissive=(n1).rgb; opacity=n35; distortion=vec2(0.0,0.0);
}

void knifeMaterial106(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(0.20000000298,0.5,10.0,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 1.0; // material node ScalarParameter
 float n4 = 4.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-1.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 float n52 = 1.0; // material node ScalarParameter
 vec2 n53 = effectBoostRotateUV(n51,vec2(0.5,0.5),n52*6.28000020981); // material node Rotator
 vec4 n54 = knifeSample(Sampler0,n53,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n55 = 1.5; // material node Constant
 float n56 = pow(max((n54).r,0.0),n55); // material node Power
 float n57 = 4.0; // material node Constant
 float n58 = pow(max((n54).r,0.0),n57); // material node Power
 float n59 = 128.0; // material node Constant
 float n60 = (n58*n59); // material node Multiply
 float n61 = (n56+n60); // material node Add
 vec3 n62 = ((n1).rgb*vec3(n61)); // material node Multiply
 vec2 n63 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n64 = (n63).g; // material node ComponentMask
 float n65 = (1.0-n64); // material node OneMinus
 float n66 = (n65*n64); // material node Multiply
 float n67 = 5.0; // material node Constant
 float n68 = (n66*n67); // material node Multiply
 float n69 = 4.0; // material node Constant
 float n70 = pow(max(n68,0.0),n69); // material node Power
 vec3 n71 = (n62*vec3(n70)); // material node Multiply
 float n72 = 0.300000011921; // material node Constant
 float n73 = FxTime; // material node Time
 float n74 = n72; // material node FunctionInput
 float n75 = (n73/n74); // material node Divide
 float n76 = 0.0; // material node Constant
 float n77 = n76; // material node FunctionInput
 float n78 = (n75+n77); // material node Add
 float n79 = sin(n78*6.28318530718); // material node Sine
 float n80 = (n79+1.0)*0.5; // material node ConstantBiasScale
 float n81 = n80; // material node MaterialFunctionCall
 vec3 n82 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n83 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n84 = dot(n82,n83); // material node DotProduct
 float n85 = abs(n84); // material node Abs
 float n86 = clamp(n85,0.0,1.0); // material node ConstantClamp
 float n87 = (n86*n86); // material node Multiply
 float n88 = (n81*n87); // material node Multiply
 float n89 = 1.0; // material node ScalarParameter
 float n90 = effectBoostDepthAlpha(n88,n89,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n71; opacity=n90; distortion=vec2(0.0,0.0);
}

void knifeMaterial107(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(0.20000000298,0.5,10.0,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 3.0; // material node ScalarParameter
 float n4 = 0.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-4.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 vec4 n52 = knifeSample(Sampler0,n51,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n53 = 1.5; // material node Constant
 float n54 = pow(max((n52).r,0.0),n53); // material node Power
 float n55 = 8.0; // material node Constant
 float n56 = pow(max((n52).r,0.0),n55); // material node Power
 float n57 = 64.0; // material node Constant
 float n58 = (n56*n57); // material node Multiply
 float n59 = (n54+n58); // material node Add
 vec3 n60 = ((n1).rgb*vec3(n59)); // material node Multiply
 float n61 = 0.10000000149; // material node Constant
 float n62 = FxTime; // material node Time
 float n63 = n61; // material node FunctionInput
 float n64 = (n62/n63); // material node Divide
 float n65 = 0.0; // material node Constant
 float n66 = n65; // material node FunctionInput
 float n67 = (n64+n66); // material node Add
 float n68 = sin(n67*6.28318530718); // material node Sine
 float n69 = (n68+1.0)*0.5; // material node ConstantBiasScale
 float n70 = n69; // material node MaterialFunctionCall
 vec3 n71 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n72 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n73 = dot(n71,n72); // material node DotProduct
 float n74 = abs(n73); // material node Abs
 float n75 = clamp(n74,0.0,1.0); // material node ConstantClamp
 float n76 = (n75*n75); // material node Multiply
 float n77 = (n70*n76); // material node Multiply
 float n78 = 1.0; // material node ScalarParameter
 float n79 = effectBoostDepthAlpha(n77,n78,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n60; opacity=n79; distortion=vec2(0.0,0.0);
}

void knifeMaterial108(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(1.0,5.0,clamp((age-(0.0))/1.0,0.0,1.0)):5.0),2.0,10.0,0.0);
 vec4 n1 = sourceColor; // material node VertexColor
 vec2 n2 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n3 = localUV*vec2(1.5,1.5); // material node TextureCoordinate
 vec2 n4 = (n3+FxTime*vec2(-1.5,0.0)); // material node Panner
 vec4 n5 = knifeSample(Sampler1,n4,true,ivec2(0,0)); // material node TextureSample
 vec2 n6 = ((n5).rgb).rg; // material node ComponentMask
 float n7 = 0.40000000596; // material node ScalarParameter
 vec2 n8 = (n6*vec2(n7)); // material node Multiply
 vec2 n9 = (n2+n8); // material node Add
 vec4 n10 = knifeSample(Sampler0,n9,true,ivec2(0,0)); // material node TextureSampleParameter2D
 vec4 n11 = dynamic4; // material node DynamicParameter
 vec3 n12 = pow(max((n10).rgb,vec3(0.0)),vec3((n11).g)); // material node Power
 vec4 n13 = dynamic4; // material node DynamicParameter
 vec3 n14 = (n12*vec3((n13).b)); // material node Multiply
 vec3 n15 = ((n1).rgb*n14); // material node Multiply
 vec4 n16 = dynamic4; // material node DynamicParameter
 vec3 n17 = pow(max((n10).rgb,vec3(0.0)),vec3((n16).r)); // material node Power
 float n18 = 0.019999999553; // material node Constant
 float n19 = destinationDepthUE; // material node SceneDepth
 float n20 = sourceDepthUE; // material node PixelDepth
 float n21 = (n19-n20); // material node Subtract
 float n22 = n18; // material node FunctionInput
 float n23 = (n21*n22); // material node Multiply
 float n24 = clamp(n23,0.0,1.0); // material node ConstantClamp
 float n25 = n24; // material node MaterialFunctionCall
 vec3 n26 = (n17*vec3(n25)); // material node Multiply
 emissive=n15; opacity=(n26).x; distortion=vec2(0.0,0.0);
}

void knifeMaterial109(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(0.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(0.0,1.0,0.0,0.0,clamp((age-(0.2))/0.8,0.0,1.0)):1.0),mix(1.0,1.0,particleSeed),(0.0<1.0?mix(1.0,0.0,clamp((0.0-(0.0))/1.0,0.0,1.0)):0.0));
 vec4 n1 = vec4(1.0,1.0,1.0,1.0); // material node VectorParameter
 vec4 n2 = sourceColor; // material node VertexColor
 vec3 n3 = ((n1).rgb*(n2).rgb); // material node Multiply
 vec4 n4 = knifeAtlasSample(Sampler0,true); // material node TextureSampleParameterSubUV
 vec3 n5 = (n4).rgb; // material node StaticSwitchParameter
 float n6 = 1.0; // material node ScalarParameter
 vec3 n7 = pow(max(n5,vec3(0.0)),vec3(n6)); // material node Power
 float n8 = 1.0; // material node ScalarParameter
 vec3 n9 = (n7*vec3(n8)); // material node Multiply
 vec3 n10 = clamp(n9,vec3(0.0),vec3(1.0)); // material node ConstantClamp
 vec4 n11 = sourceColor; // material node VertexColor
 vec3 n12 = (n10*vec3((n11).a)); // material node Multiply
 float n13 = 0.0500000007451; // material node ScalarParameter
 float n14 = destinationDepthUE; // material node SceneDepth
 float n15 = sourceDepthUE; // material node PixelDepth
 float n16 = (n14-n15); // material node Subtract
 float n17 = n13; // material node FunctionInput
 float n18 = (n16*n17); // material node Multiply
 float n19 = clamp(n18,0.0,1.0); // material node ConstantClamp
 float n20 = n19; // material node MaterialFunctionCall
 vec3 n21 = (n12*vec3(n20)); // material node Multiply
 emissive=n3; opacity=(n21).x; distortion=vec2(0.0,0.0);
}

void knifeMaterial110(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.5,0.0,0.0,0.0);
 float n1 = 1.0; // material node ScalarParameter
 vec4 n2 = vec4(0.280223488808,0.485022962093,1.0,1.0); // material node VectorParameter
 float n3 = 0.0; // material node Constant
 float n4 = 0.10000000149; // material node ScalarParameter
 vec2 n5 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n6 = vec4(1.0,1.0,1.0,1.0); // material node Constant4Vector
 vec2 n7 = (n6).rg; // material node ComponentMask
 vec2 n8 = (n5*n7); // material node Multiply
 float n9 = 2.0; // material node Constant
 vec2 n10 = (n7/vec2(n9)); // material node Divide
 vec2 n11 = (n8-n10); // material node Subtract
 float n12 = 0.5; // material node Constant
 vec2 n13 = (n11*vec2(n12)); // material node Multiply
 float n14 = dot(n13,n13); // material node DotProduct
 float n15 = sqrt(n14); // material node SquareRoot
 float n16 = (n4/n15); // material node Divide
 float n17 = (n3+n16); // material node Add
 vec3 n18 = ((n2).rgb*vec3(n17)); // material node Multiply
 vec3 n19 = (vec3(n1)*n18); // material node Multiply
 vec4 n20 = sourceColor; // material node VertexColor
 vec3 n21 = (n19*(n20).rgb); // material node Multiply
 vec4 n22 = sourceColor; // material node VertexColor
 vec2 n23 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n24 = n23; // material node FunctionInput
 float n25 = 0.0; // material node Constant
 float n26 = n25; // material node FunctionInput
 float n27 = 0.0; // material node Constant
 float n28 = n27; // material node FunctionInput
 vec2 n29 = vec2(n26,n28); // material node AppendVector
 vec2 n30 = (n24+n29); // material node Add
 float n31 = 0.5; // material node Constant
 float n32 = 0.5; // material node Constant
 float n33 = n32; // material node FunctionInput
 float n34 = 0.0; // material node Constant
 float n35 = n34; // material node FunctionInput
 float n36 = knifeSphere(n30,vec2(n31),n33,n35); // material node SphereMask
 float n37 = n36; // material node MaterialFunctionCall
 float n38 = (n37*n37); // material node Multiply
 float n39 = (n38*n38); // material node Multiply
 float n40 = 1.0; // material node Constant
 float n41 = n40; // material node StaticSwitchParameter
 float n42 = (n39*n41); // material node Multiply
 float n43 = ((n22).a*n42); // material node Multiply
 float n44 = 1.0; // material node ScalarParameter
 float n45 = destinationDepthUE; // material node SceneDepth
 float n46 = sourceDepthUE; // material node PixelDepth
 float n47 = (n45-n46); // material node Subtract
 float n48 = n44; // material node FunctionInput
 float n49 = (n47*n48); // material node Multiply
 float n50 = clamp(n49,0.0,1.0); // material node ConstantClamp
 float n51 = n50; // material node MaterialFunctionCall
 float n52 = (n43*n51); // material node Multiply
 emissive=n21; opacity=n52; distortion=vec2(0.0,0.0);
}

void knifeMaterial111(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 float n1 = 50.0; // material node ScalarParameter
 vec4 n2 = vec4(1.0,0.222032904625,0.0830715298653,1.0); // material node VectorParameter
 vec3 n3 = (vec3(n1)*(n2).rgb); // material node Multiply
 vec4 n4 = knifeAtlasSample(Sampler0,true); // material node TextureSampleParameterSubUV
 vec3 n5 = (n3*(n4).rgb); // material node Multiply
 vec4 n6 = dynamic4; // material node DynamicParameter
 vec3 n7 = pow(max(n5,vec3(0.0)),vec3((n6).r)); // material node Power
 vec4 n8 = sourceColor; // material node VertexColor
 vec3 n9 = (n7*(n8).rgb); // material node Multiply
 vec4 n10 = sourceColor; // material node VertexColor
 float n11 = 0.899999976158; // material node ScalarParameter
 float n12 = effectBoostDepthAlpha((n10).a,n11,100.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n9; opacity=n12; distortion=vec2(0.0,0.0);
}

void knifeMaterial112(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 float n1 = 1.0; // material node ScalarParameter
 vec4 n2 = vec4(0.280223488808,0.485022962093,1.0,1.0); // material node VectorParameter
 float n3 = 0.0; // material node Constant
 float n4 = 0.10000000149; // material node ScalarParameter
 vec2 n5 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n6 = vec4(1.0,1.0,1.0,1.0); // material node Constant4Vector
 vec2 n7 = (n6).rg; // material node ComponentMask
 vec2 n8 = (n5*n7); // material node Multiply
 float n9 = 2.0; // material node Constant
 vec2 n10 = (n7/vec2(n9)); // material node Divide
 vec2 n11 = (n8-n10); // material node Subtract
 float n12 = 0.5; // material node Constant
 vec2 n13 = (n11*vec2(n12)); // material node Multiply
 float n14 = dot(n13,n13); // material node DotProduct
 float n15 = sqrt(n14); // material node SquareRoot
 float n16 = (n4/n15); // material node Divide
 float n17 = (n3+n16); // material node Add
 vec3 n18 = ((n2).rgb*vec3(n17)); // material node Multiply
 vec3 n19 = (vec3(n1)*n18); // material node Multiply
 vec4 n20 = sourceColor; // material node VertexColor
 vec3 n21 = (n19*(n20).rgb); // material node Multiply
 vec4 n22 = sourceColor; // material node VertexColor
 vec2 n23 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n24 = n23; // material node FunctionInput
 float n25 = 0.0; // material node Constant
 float n26 = n25; // material node FunctionInput
 float n27 = 0.0; // material node Constant
 float n28 = n27; // material node FunctionInput
 vec2 n29 = vec2(n26,n28); // material node AppendVector
 vec2 n30 = (n24+n29); // material node Add
 float n31 = 0.5; // material node Constant
 float n32 = 0.5; // material node Constant
 float n33 = n32; // material node FunctionInput
 float n34 = 0.0; // material node Constant
 float n35 = n34; // material node FunctionInput
 float n36 = knifeSphere(n30,vec2(n31),n33,n35); // material node SphereMask
 float n37 = n36; // material node MaterialFunctionCall
 float n38 = (n37*n37); // material node Multiply
 float n39 = (n38*n38); // material node Multiply
 float n40 = 1.0; // material node Constant
 float n41 = n40; // material node StaticSwitchParameter
 float n42 = (n39*n41); // material node Multiply
 float n43 = ((n22).a*n42); // material node Multiply
 float n44 = 1.0; // material node ScalarParameter
 float n45 = destinationDepthUE; // material node SceneDepth
 float n46 = sourceDepthUE; // material node PixelDepth
 float n47 = (n45-n46); // material node Subtract
 float n48 = n44; // material node FunctionInput
 float n49 = (n47*n48); // material node Multiply
 float n50 = clamp(n49,0.0,1.0); // material node ConstantClamp
 float n51 = n50; // material node MaterialFunctionCall
 float n52 = (n43*n51); // material node Multiply
 emissive=n21; opacity=n52; distortion=vec2(0.0,0.0);
}

void knifeMaterial113(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(0.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(0.0,1.0,0.0,0.0,clamp((age-(0.2))/0.8,0.0,1.0)):1.0),mix(1.0,1.0,particleSeed),(0.0<1.0?mix(1.0,0.0,clamp((0.0-(0.0))/1.0,0.0,1.0)):0.0));
 float n1 = 1.0; // material node ScalarParameter
 vec4 n2 = vec4(1.0,0.761256217957,0.535860538483,1.0); // material node VectorParameter
 vec4 n3 = knifeSample(Sampler0,localUV,true,ivec2(0,0)); // material node TextureSampleParameter2D
 float n4 = 2.0; // material node ScalarParameter
 vec3 n5 = pow(max((n3).rgb,vec3(0.0)),vec3(n4)); // material node Power
 float n6 = 1.5; // material node Constant
 vec3 n7 = pow(max((n3).rgb,vec3(0.0)),vec3(n6)); // material node Power
 vec3 n8 = (n5+n7); // material node Add
 float n9 = 0.0; // material node ScalarParameter
 vec3 n10 = mix(n8,vec3(dot(n8,vec3(0.3,0.59,0.11))),n9); // material node Desaturation
 vec3 n11 = ((n2).rgb*n10); // material node Multiply
 vec3 n12 = (vec3(n1)*n11); // material node Multiply
 vec4 n13 = sourceColor; // material node VertexColor
 vec3 n14 = (n12*(n13).rgb); // material node Multiply
 vec4 n15 = sourceColor; // material node VertexColor
 float n16 = 0.0299999993294; // material node ScalarParameter
 float n17 = destinationDepthUE; // material node SceneDepth
 float n18 = sourceDepthUE; // material node PixelDepth
 float n19 = (n17-n18); // material node Subtract
 float n20 = n16; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = n22; // material node MaterialFunctionCall
 float n24 = ((n15).a*n23); // material node Multiply
 emissive=n14; opacity=n24; distortion=vec2(0.0,0.0);
}

void knifeMaterial114(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<0.457372?sourceHermite(-1.0,-0.342575,0.04447027956,0.578984013196,clamp((age-(0.0))/0.457372,0.0,1.0)):(age<1.216667?sourceHermite(-0.342575,1.010526,0.961186225435,2.39776629107,clamp((age-(0.457372))/0.759295,0.0,1.0)):1.010526)),(age<1.0?mix(1.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(-1.0,1.5,0.203591,4.845595,clamp((age-(0.0))/1.0,0.0,1.0)):1.5),mix(0.0,1.0,particleSeed));
 vec4 n1 = vec4(0.5,1.0,10.0,1.0); // material node VectorParameter
 vec4 n2 = vec4(0.5,1.0,10.0,1.0); // material node VectorParameter
 vec3 n3 = ((n1).rgb*vec3((n2).a)); // material node Multiply
 vec2 n4 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n5 = 0.0; // material node Constant
 vec4 n6 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n7 = vec2(n5,(n6).b); // material node AppendVector
 vec2 n8 = (n4+n7); // material node Add
 vec4 n9 = knifeSample(Sampler0,n8,true,ivec2(1,1)); // material node TextureSample
 float n10 = 6.0; // material node ScalarParameter
 float n11 = pow(max((n9).r,0.0),n10); // material node Power
 float n12 = 0.20000000298; // material node Constant
 float n13 = ((n9).r*n12); // material node Multiply
 float n14 = (n11+n13); // material node Add
 vec2 n15 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n16 = (n15).g; // material node ComponentMask
 float n17 = (1.0-n16); // material node OneMinus
 float n18 = 3.0; // material node Constant
 float n19 = pow(max(n17,0.0),n18); // material node Power
 float n20 = 80.0; // material node Constant
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = (n14*n22); // material node Multiply
 float n24 = clamp(n23,0.0,1.0); // material node ConstantClamp
 vec3 n25 = (n3*vec3(n24)); // material node Multiply
 vec4 n26 = vec4(0.10000000149,0.5,3.0,1.0); // material node VectorParameter
 vec4 n27 = vec4(0.10000000149,0.5,3.0,1.0); // material node VectorParameter
 vec3 n28 = ((n26).rgb*vec3((n27).a)); // material node Multiply
 vec2 n29 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n30 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n31 = vec2((n30).a,(n30).a); // material node AppendVector
 vec2 n32 = (n29+n31); // material node Add
 vec2 n33 = (n32+0.0*vec2(0.0,0.20000000298)); // material node Panner
 vec4 n34 = knifeSample(Sampler2,n33,true,ivec2(0,0)); // material node TextureSample
 float n35 = 0.5; // material node Constant
 float n36 = ((n34).r*n35); // material node Multiply
 vec2 n37 = localUV*vec2(1.20000004768,1.0); // material node TextureCoordinate
 float n38 = 1.0; // material node ScalarParameter
 vec2 n39 = (n37*vec2(n38)); // material node Multiply
 float n40 = (n39).g; // material node ComponentMask
 float n41 = FxTime; // material node Time
 float n42 = (n40+n41); // material node Add
 float n43 = 1.0; // material node ScalarParameter
 vec2 n44 = (vec2(n43)*n37); // material node Multiply
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (n16*n17); // material node Multiply
 float n47 = 4.0; // material node Constant
 float n48 = (n46*n47); // material node Multiply
 float n49 = 1.0; // material node Constant
 float n50 = pow(max(n48,0.0),n49); // material node Power
 float n51 = FxTime; // material node Time
 float n52 = -0.5; // material node ScalarParameter
 float n53 = (n51*n52); // material node Multiply
 float n54 = (n16+n53); // material node Add
 float n55 = 4.0; // material node ScalarParameter
 float n56 = (n54*n55); // material node Multiply
 float n57 = sin(n56*6.28318530718); // material node Sine
 float n58 = 0.0799999982119; // material node ScalarParameter
 float n59 = (n57*n58); // material node Multiply
 float n60 = (n50*n59); // material node Multiply
 float n61 = (n45+n60); // material node Add
 vec2 n62 = vec2(n42,n61); // material node AppendVector
 float n63 = 6.28318309784; // material node Constant
 vec2 n64 = effectBoostRotateUV(n62,vec2(0.5,0.5),n63*0.25); // material node Rotator
 vec2 n65 = (vec2(n36)+n64); // material node Add
 vec4 n66 = knifeSample(Sampler3,vec2(0.0),false,ivec2(1,1)); // material node TextureSample
 vec2 n67 = ((n66).rgb).rg; // material node ComponentMask
 float n68 = 0.20000000298; // material node Constant
 vec2 n69 = mix(n65,n67,vec2(n68)); // material node LinearInterpolate
 vec4 n70 = knifeSample(Sampler1,n69,true,ivec2(1,0)); // material node TextureSample
 float n71 = 1.0; // material node Constant
 float n72 = ((n70).r*n71); // material node Multiply
 float n73 = (n15).r; // material node ComponentMask
 float n74 = (1.0-n73); // material node OneMinus
 float n75 = (n73*n74); // material node Multiply
 float n76 = 2.0; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 6.0; // material node Constant
 float n79 = (n77*n78); // material node Multiply
 float n80 = (n19*n79); // material node Multiply
 float n81 = 5.0; // material node Constant
 float n82 = (n80*n81); // material node Multiply
 float n83 = clamp(n82,0.0,1.0); // material node ConstantClamp
 float n84 = (n72*n83); // material node Multiply
 vec2 n85 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n86 = 0.0; // material node Constant
 vec4 n87 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n88 = vec2(n86,(n87).r); // material node AppendVector
 vec2 n89 = (n85+n88); // material node Add
 vec4 n90 = knifeSample(Sampler4,n89,true,ivec2(1,1)); // material node TextureSample
 float n91 = (n84*(n90).g); // material node Multiply
 float n92 = clamp(n91,0.0,1.0); // material node ConstantClamp
 vec3 n93 = (n28*vec3(n92)); // material node Multiply
 vec3 n94 = (n25+n93); // material node Add
 vec4 n95 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n96 = (n94*(n95).rgb); // material node Multiply
 vec4 n97 = sourceColor; // material node MeshEmitterVertexColor
 float n98 = 0.899999976158; // material node Constant
 vec3 n99 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n100 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 vec3 n101 = n100; // material node FunctionInput
 float n102 = dot(n99,n101); // material node DotProduct
 float n103 = abs(n102); // material node Abs
 float n104 = n98; // material node FunctionInput
 float n105 = pow(max(n103,0.0),n104); // material node Power
 float n106 = n105; // material node MaterialFunctionCall
 float n107 = destinationDepthUE; // material node SceneDepth
 float n108 = sourceDepthUE; // material node PixelDepth
 float n109 = (n107-n108); // material node Subtract
 float n110 = n98; // material node FunctionInput
 float n111 = (n109*n110); // material node Multiply
 float n112 = clamp(n111,0.0,1.0); // material node ConstantClamp
 float n113 = n112; // material node MaterialFunctionCall
 float n114 = (n106*n113); // material node Multiply
 float n115 = ((n97).a*n114); // material node Multiply
 emissive=n96; opacity=n115; distortion=vec2(0.0,0.0);
}

void knifeMaterial115(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec2 n1 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n2 = 30.0; // material node Constant
 vec2 n3 = (n1*vec2(n2)); // material node Multiply
 float n4 = 0.019999999553; // material node Constant
 vec2 n5 = (n3*vec2(n4)); // material node Multiply
 vec2 n6 = (n5+FxTime*vec2(0.10000000149,0.10000000149)); // material node Panner
 vec4 n7 = knifeSample(Sampler0,n6,true,ivec2(0,0)); // material node TextureSample
 float n8 = 0.10000000149; // material node ScalarParameter
 float n9 = FxTime; // material node Time
 float n10 = (n8*n9); // material node Multiply
 float n11 = fract(n10); // material node Frac
 float n12 = 360.0; // material node Constant
 float n13 = (n11*n12); // material node Multiply
 vec3 n14 = vec3(1.0,1.0,1.0); // material node Constant3Vector
 vec3 n15 = knifeWorldToTangent(n14); // material node Transform
 vec3 n16 = (n15/max(length(n15),0.000001)); // material node Normalize
 float n17 = 0.0872600004077; // material node Constant
 float n18 = n13; // material node FunctionInput
 float n19 = (n17*n18); // material node Multiply
 vec4 n20 = vec4(n16,n19); // material node AppendVector
 float n21 = 0.0; // material node Constant
 vec3 n22 = (n7).rgb; // material node FunctionInput
 vec3 n23 = knifeRotateAboutAxis(n22,vec3(n21),(n20).xyz,(n20).w); // material node RotateAboutAxis
 vec3 n24 = (n23+n22); // material node Add
 vec3 n25 = abs(n24); // material node Abs
 vec3 n26 = n25; // material node MaterialFunctionCall
 vec3 n27 = clamp(n26,vec3(0.0),vec3(1.0)); // material node ConstantClamp
 float n28 = 0.25; // material node Constant
 vec2 n29 = localUV*vec2(0.5,0.5); // material node TextureCoordinate
 vec2 n30 = (vec2(n28)+n29); // material node Add
 float n31 = (n30).r; // material node ComponentMask
 float n32 = 0.25; // material node Constant
 vec2 n33 = (n29+vec2(n32)); // material node Add
 float n34 = (n33).g; // material node ComponentMask
 vec2 n35 = vec2(n31,n34); // material node AppendVector
 float n36 = 6.28000020981; // material node Constant
 vec2 n37 = effectBoostRotateUV(n35,vec2(0.5,0.5),n36*6.28000020981); // material node Rotator
 vec4 n38 = knifeSample(Sampler1,n37,true,ivec2(1,1)); // material node TextureSample
 vec3 n39 = (n27*(n38).rgb); // material node Multiply
 vec4 n40 = vec4(1.0,1.0,1.0,1.0); // material node VectorParameter
 vec3 n41 = (n39*(n40).rgb); // material node Multiply
 vec2 n42 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n43 = (n42).g; // material node ComponentMask
 float n44 = 2.0; // material node Constant
 float n45 = pow(max(n43,0.0),n44); // material node Power
 vec2 n46 = vec2(0.649999976158,1.0); // material node Constant2Vector
 vec2 n47 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n48 = (n46*n47); // material node Multiply
 vec2 n49 = (n48+FxTime*vec2(-0.125,0.0)); // material node Panner
 vec4 n50 = knifeSample(Sampler2,n49,true,ivec2(0,1)); // material node TextureSample
 vec2 n51 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n52 = vec2(0.800000011921,1.0); // material node Constant2Vector
 vec2 n53 = (n51*n52); // material node Multiply
 vec2 n54 = (n53+FxTime*vec2(0.0900000035763,0.0)); // material node Panner
 vec4 n55 = knifeSample(Sampler2,n54,true,ivec2(0,1)); // material node TextureSample
 vec3 n56 = ((n50).rgb+(n55).rgb); // material node Add
 vec3 n57 = (vec3(n45)*n56); // material node Multiply
 float n58 = 4.0; // material node Constant
 vec4 n59 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n60 = (vec3(n58)*(n59).rgb); // material node Multiply
 vec3 n61 = (n57*n60); // material node Multiply
 vec3 n62 = (n41*n61); // material node Multiply
 emissive=n62; opacity=1.0; distortion=vec2(0.0,0.0);
}

void knifeMaterial116(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(8.0,0.300000011921,6.0,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 1.0; // material node ScalarParameter
 float n4 = 4.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-1.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 float n52 = 1.0; // material node ScalarParameter
 vec2 n53 = effectBoostRotateUV(n51,vec2(0.5,0.5),n52*6.28000020981); // material node Rotator
 vec4 n54 = knifeSample(Sampler0,n53,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n55 = 1.5; // material node Constant
 float n56 = pow(max((n54).r,0.0),n55); // material node Power
 float n57 = 4.0; // material node Constant
 float n58 = pow(max((n54).r,0.0),n57); // material node Power
 float n59 = 128.0; // material node Constant
 float n60 = (n58*n59); // material node Multiply
 float n61 = (n56+n60); // material node Add
 vec3 n62 = ((n1).rgb*vec3(n61)); // material node Multiply
 vec2 n63 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n64 = (n63).g; // material node ComponentMask
 float n65 = (1.0-n64); // material node OneMinus
 float n66 = (n65*n64); // material node Multiply
 float n67 = 5.0; // material node Constant
 float n68 = (n66*n67); // material node Multiply
 float n69 = 4.0; // material node Constant
 float n70 = pow(max(n68,0.0),n69); // material node Power
 vec3 n71 = (n62*vec3(n70)); // material node Multiply
 float n72 = 0.300000011921; // material node Constant
 float n73 = FxTime; // material node Time
 float n74 = n72; // material node FunctionInput
 float n75 = (n73/n74); // material node Divide
 float n76 = 0.0; // material node Constant
 float n77 = n76; // material node FunctionInput
 float n78 = (n75+n77); // material node Add
 float n79 = sin(n78*6.28318530718); // material node Sine
 float n80 = (n79+1.0)*0.5; // material node ConstantBiasScale
 float n81 = n80; // material node MaterialFunctionCall
 vec3 n82 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n83 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n84 = dot(n82,n83); // material node DotProduct
 float n85 = abs(n84); // material node Abs
 float n86 = clamp(n85,0.0,1.0); // material node ConstantClamp
 float n87 = (n86*n86); // material node Multiply
 float n88 = (n81*n87); // material node Multiply
 float n89 = 1.0; // material node ScalarParameter
 float n90 = effectBoostDepthAlpha(n88,n89,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n71; opacity=n90; distortion=vec2(0.0,0.0);
}

void knifeMaterial117(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(8.0,0.300000011921,6.0,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 3.0; // material node ScalarParameter
 float n4 = 0.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-4.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 vec4 n52 = knifeSample(Sampler0,n51,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n53 = 1.5; // material node Constant
 float n54 = pow(max((n52).r,0.0),n53); // material node Power
 float n55 = 8.0; // material node Constant
 float n56 = pow(max((n52).r,0.0),n55); // material node Power
 float n57 = 64.0; // material node Constant
 float n58 = (n56*n57); // material node Multiply
 float n59 = (n54+n58); // material node Add
 vec3 n60 = ((n1).rgb*vec3(n59)); // material node Multiply
 float n61 = 0.10000000149; // material node Constant
 float n62 = FxTime; // material node Time
 float n63 = n61; // material node FunctionInput
 float n64 = (n62/n63); // material node Divide
 float n65 = 0.0; // material node Constant
 float n66 = n65; // material node FunctionInput
 float n67 = (n64+n66); // material node Add
 float n68 = sin(n67*6.28318530718); // material node Sine
 float n69 = (n68+1.0)*0.5; // material node ConstantBiasScale
 float n70 = n69; // material node MaterialFunctionCall
 vec3 n71 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n72 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n73 = dot(n71,n72); // material node DotProduct
 float n74 = abs(n73); // material node Abs
 float n75 = clamp(n74,0.0,1.0); // material node ConstantClamp
 float n76 = (n75*n75); // material node Multiply
 float n77 = (n70*n76); // material node Multiply
 float n78 = 1.0; // material node ScalarParameter
 float n79 = effectBoostDepthAlpha(n77,n78,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n60; opacity=n79; distortion=vec2(0.0,0.0);
}

void knifeMaterial118(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(20.0,2.5,0.20000000298,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 1.0; // material node ScalarParameter
 float n4 = 4.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-1.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 float n52 = 1.0; // material node ScalarParameter
 vec2 n53 = effectBoostRotateUV(n51,vec2(0.5,0.5),n52*6.28000020981); // material node Rotator
 vec4 n54 = knifeSample(Sampler0,n53,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n55 = 1.5; // material node Constant
 float n56 = pow(max((n54).r,0.0),n55); // material node Power
 float n57 = 4.0; // material node Constant
 float n58 = pow(max((n54).r,0.0),n57); // material node Power
 float n59 = 128.0; // material node Constant
 float n60 = (n58*n59); // material node Multiply
 float n61 = (n56+n60); // material node Add
 vec3 n62 = ((n1).rgb*vec3(n61)); // material node Multiply
 vec2 n63 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n64 = (n63).g; // material node ComponentMask
 float n65 = (1.0-n64); // material node OneMinus
 float n66 = (n65*n64); // material node Multiply
 float n67 = 5.0; // material node Constant
 float n68 = (n66*n67); // material node Multiply
 float n69 = 4.0; // material node Constant
 float n70 = pow(max(n68,0.0),n69); // material node Power
 vec3 n71 = (n62*vec3(n70)); // material node Multiply
 float n72 = 0.300000011921; // material node Constant
 float n73 = FxTime; // material node Time
 float n74 = n72; // material node FunctionInput
 float n75 = (n73/n74); // material node Divide
 float n76 = 0.0; // material node Constant
 float n77 = n76; // material node FunctionInput
 float n78 = (n75+n77); // material node Add
 float n79 = sin(n78*6.28318530718); // material node Sine
 float n80 = (n79+1.0)*0.5; // material node ConstantBiasScale
 float n81 = n80; // material node MaterialFunctionCall
 vec3 n82 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n83 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n84 = dot(n82,n83); // material node DotProduct
 float n85 = abs(n84); // material node Abs
 float n86 = clamp(n85,0.0,1.0); // material node ConstantClamp
 float n87 = (n86*n86); // material node Multiply
 float n88 = (n81*n87); // material node Multiply
 float n89 = 1.0; // material node ScalarParameter
 float n90 = effectBoostDepthAlpha(n88,n89,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n71; opacity=n90; distortion=vec2(0.0,0.0);
}

void knifeMaterial119(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(20.0,5.0,0.300000011921,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 3.0; // material node ScalarParameter
 float n4 = 0.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-4.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 vec4 n52 = knifeSample(Sampler0,n51,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n53 = 1.5; // material node Constant
 float n54 = pow(max((n52).r,0.0),n53); // material node Power
 float n55 = 8.0; // material node Constant
 float n56 = pow(max((n52).r,0.0),n55); // material node Power
 float n57 = 64.0; // material node Constant
 float n58 = (n56*n57); // material node Multiply
 float n59 = (n54+n58); // material node Add
 vec3 n60 = ((n1).rgb*vec3(n59)); // material node Multiply
 float n61 = 0.10000000149; // material node Constant
 float n62 = FxTime; // material node Time
 float n63 = n61; // material node FunctionInput
 float n64 = (n62/n63); // material node Divide
 float n65 = 0.0; // material node Constant
 float n66 = n65; // material node FunctionInput
 float n67 = (n64+n66); // material node Add
 float n68 = sin(n67*6.28318530718); // material node Sine
 float n69 = (n68+1.0)*0.5; // material node ConstantBiasScale
 float n70 = n69; // material node MaterialFunctionCall
 vec3 n71 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n72 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n73 = dot(n71,n72); // material node DotProduct
 float n74 = abs(n73); // material node Abs
 float n75 = clamp(n74,0.0,1.0); // material node ConstantClamp
 float n76 = (n75*n75); // material node Multiply
 float n77 = (n70*n76); // material node Multiply
 float n78 = 1.0; // material node ScalarParameter
 float n79 = effectBoostDepthAlpha(n77,n78,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n60; opacity=n79; distortion=vec2(0.0,0.0);
}

void knifeMaterial120(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(8.0,6.0,0.300000011921,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 1.0; // material node ScalarParameter
 float n4 = 4.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-1.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 float n52 = 1.0; // material node ScalarParameter
 vec2 n53 = effectBoostRotateUV(n51,vec2(0.5,0.5),n52*6.28000020981); // material node Rotator
 vec4 n54 = knifeSample(Sampler0,n53,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n55 = 1.5; // material node Constant
 float n56 = pow(max((n54).r,0.0),n55); // material node Power
 float n57 = 4.0; // material node Constant
 float n58 = pow(max((n54).r,0.0),n57); // material node Power
 float n59 = 128.0; // material node Constant
 float n60 = (n58*n59); // material node Multiply
 float n61 = (n56+n60); // material node Add
 vec3 n62 = ((n1).rgb*vec3(n61)); // material node Multiply
 vec2 n63 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n64 = (n63).g; // material node ComponentMask
 float n65 = (1.0-n64); // material node OneMinus
 float n66 = (n65*n64); // material node Multiply
 float n67 = 5.0; // material node Constant
 float n68 = (n66*n67); // material node Multiply
 float n69 = 4.0; // material node Constant
 float n70 = pow(max(n68,0.0),n69); // material node Power
 vec3 n71 = (n62*vec3(n70)); // material node Multiply
 float n72 = 0.300000011921; // material node Constant
 float n73 = FxTime; // material node Time
 float n74 = n72; // material node FunctionInput
 float n75 = (n73/n74); // material node Divide
 float n76 = 0.0; // material node Constant
 float n77 = n76; // material node FunctionInput
 float n78 = (n75+n77); // material node Add
 float n79 = sin(n78*6.28318530718); // material node Sine
 float n80 = (n79+1.0)*0.5; // material node ConstantBiasScale
 float n81 = n80; // material node MaterialFunctionCall
 vec3 n82 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n83 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n84 = dot(n82,n83); // material node DotProduct
 float n85 = abs(n84); // material node Abs
 float n86 = clamp(n85,0.0,1.0); // material node ConstantClamp
 float n87 = (n86*n86); // material node Multiply
 float n88 = (n81*n87); // material node Multiply
 float n89 = 1.0; // material node ScalarParameter
 float n90 = effectBoostDepthAlpha(n88,n89,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n71; opacity=n90; distortion=vec2(0.0,0.0);
}

void knifeMaterial121(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(8.0,6.0,0.300000011921,0.0); // material node VectorParameter
 float n2 = 4.0; // material node ScalarParameter
 float n3 = 3.0; // material node ScalarParameter
 float n4 = 0.0; // material node Constant
 float n5 = n4; // material node ComponentMask
 float n6 = 0.0; // material node Constant
 vec2 n7 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n8 = n7; // material node FunctionInput
 float n9 = n2; // material node FunctionInput
 float n10 = n3; // material node FunctionInput
 vec2 n11 = vec2(n9,n10); // material node AppendVector
 vec2 n12 = (n8/n11); // material node Divide
 float n13 = 1.0; // material node Constant
 float n14 = (n9*n10); // material node Multiply
 float n15 = (n13/n14); // material node Divide
 float n16 = n6; // material node FunctionInput
 float n17 = (n15*n16); // material node Multiply
 float n18 = FxTime; // material node Time
 float n19 = n18; // material node FunctionInput
 float n20 = n5; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = (n17+n21); // material node Add
 float n23 = (n22*n10); // material node Multiply
 float n24 = fract(n23); // material node Frac
 float n25 = (n9*n24); // material node Multiply
 float n26 = floor(n25); // material node Floor
 float n27 = fract(n22); // material node Frac
 float n28 = (n10*n27); // material node Multiply
 float n29 = floor(n28); // material node Floor
 vec2 n30 = vec2(n26,n29); // material node AppendVector
 float n31 = 1.0; // material node Constant
 float n32 = (n31/n9); // material node Divide
 float n33 = (n31/n10); // material node Divide
 vec2 n34 = vec2(n32,n33); // material node AppendVector
 vec2 n35 = (n30*n34); // material node Multiply
 vec2 n36 = (n12+n35); // material node Add
 vec2 n37 = n36; // material node MaterialFunctionCall
 vec2 n38 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n39 = (n38+FxTime*vec2(0.0,-4.0)); // material node Panner
 vec4 n40 = knifeSample(Sampler1,n39,true,ivec2(0,0)); // material node TextureSample
 vec2 n41 = ((n40).rgb).rg; // material node ComponentMask
 float n42 = 0.20000000298; // material node Constant
 vec2 n43 = (n41*vec2(n42)); // material node Multiply
 vec2 n44 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (1.0-n45); // material node OneMinus
 float n47 = (n46*n45); // material node Multiply
 float n48 = 1.0; // material node Constant
 float n49 = (n47*n48); // material node Multiply
 vec2 n50 = (n43*vec2(n49)); // material node Multiply
 vec2 n51 = (n37+n50); // material node Add
 vec4 n52 = knifeSample(Sampler0,n51,true,ivec2(1,1)); // material node TextureSampleParameter2D
 float n53 = 1.5; // material node Constant
 float n54 = pow(max((n52).r,0.0),n53); // material node Power
 float n55 = 8.0; // material node Constant
 float n56 = pow(max((n52).r,0.0),n55); // material node Power
 float n57 = 64.0; // material node Constant
 float n58 = (n56*n57); // material node Multiply
 float n59 = (n54+n58); // material node Add
 vec3 n60 = ((n1).rgb*vec3(n59)); // material node Multiply
 float n61 = 0.10000000149; // material node Constant
 float n62 = FxTime; // material node Time
 float n63 = n61; // material node FunctionInput
 float n64 = (n62/n63); // material node Divide
 float n65 = 0.0; // material node Constant
 float n66 = n65; // material node FunctionInput
 float n67 = (n64+n66); // material node Add
 float n68 = sin(n67*6.28318530718); // material node Sine
 float n69 = (n68+1.0)*0.5; // material node ConstantBiasScale
 float n70 = n69; // material node MaterialFunctionCall
 vec3 n71 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n72 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 float n73 = dot(n71,n72); // material node DotProduct
 float n74 = abs(n73); // material node Abs
 float n75 = clamp(n74,0.0,1.0); // material node ConstantClamp
 float n76 = (n75*n75); // material node Multiply
 float n77 = (n70*n76); // material node Multiply
 float n78 = 1.0; // material node ScalarParameter
 float n79 = effectBoostDepthAlpha(n77,n78,200.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n60; opacity=n79; distortion=vec2(0.0,0.0);
}

void knifeMaterial122(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.5,0.0,0.0,0.0);
 float n1 = 1.0; // material node ScalarParameter
 vec4 n2 = vec4(0.12724429369,0.315527558327,1.0,1.0); // material node VectorParameter
 float n3 = 0.0; // material node Constant
 float n4 = 0.10000000149; // material node ScalarParameter
 vec2 n5 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n6 = vec4(1.0,1.0,1.0,1.0); // material node Constant4Vector
 vec2 n7 = (n6).rg; // material node ComponentMask
 vec2 n8 = (n5*n7); // material node Multiply
 float n9 = 2.0; // material node Constant
 vec2 n10 = (n7/vec2(n9)); // material node Divide
 vec2 n11 = (n8-n10); // material node Subtract
 float n12 = 0.5; // material node Constant
 vec2 n13 = (n11*vec2(n12)); // material node Multiply
 float n14 = dot(n13,n13); // material node DotProduct
 float n15 = sqrt(n14); // material node SquareRoot
 float n16 = (n4/n15); // material node Divide
 float n17 = (n3+n16); // material node Add
 vec3 n18 = ((n2).rgb*vec3(n17)); // material node Multiply
 vec3 n19 = (vec3(n1)*n18); // material node Multiply
 vec4 n20 = sourceColor; // material node VertexColor
 vec3 n21 = (n19*(n20).rgb); // material node Multiply
 vec4 n22 = sourceColor; // material node VertexColor
 vec2 n23 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n24 = n23; // material node FunctionInput
 float n25 = 0.0; // material node Constant
 float n26 = n25; // material node FunctionInput
 float n27 = 0.0; // material node Constant
 float n28 = n27; // material node FunctionInput
 vec2 n29 = vec2(n26,n28); // material node AppendVector
 vec2 n30 = (n24+n29); // material node Add
 float n31 = 0.5; // material node Constant
 float n32 = 0.5; // material node Constant
 float n33 = n32; // material node FunctionInput
 float n34 = 0.0; // material node Constant
 float n35 = n34; // material node FunctionInput
 float n36 = knifeSphere(n30,vec2(n31),n33,n35); // material node SphereMask
 float n37 = n36; // material node MaterialFunctionCall
 float n38 = (n37*n37); // material node Multiply
 float n39 = (n38*n38); // material node Multiply
 float n40 = 1.0; // material node Constant
 float n41 = n40; // material node StaticSwitchParameter
 float n42 = (n39*n41); // material node Multiply
 float n43 = ((n22).a*n42); // material node Multiply
 float n44 = 0.10000000149; // material node ScalarParameter
 float n45 = destinationDepthUE; // material node SceneDepth
 float n46 = sourceDepthUE; // material node PixelDepth
 float n47 = (n45-n46); // material node Subtract
 float n48 = n44; // material node FunctionInput
 float n49 = (n47*n48); // material node Multiply
 float n50 = clamp(n49,0.0,1.0); // material node ConstantClamp
 float n51 = n50; // material node MaterialFunctionCall
 float n52 = (n43*n51); // material node Multiply
 emissive=n21; opacity=n52; distortion=vec2(0.0,0.0);
}

void knifeMaterial123(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<0.457372?sourceHermite(-1.0,-0.342575,0.04447027956,0.578984013196,clamp((age-(0.0))/0.457372,0.0,1.0)):(age<1.216667?sourceHermite(-0.342575,1.010526,0.961186225435,2.39776629107,clamp((age-(0.457372))/0.759295,0.0,1.0)):1.010526)),(age<1.0?mix(1.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(-1.0,1.5,0.203591,4.845595,clamp((age-(0.0))/1.0,0.0,1.0)):1.5),mix(0.0,1.0,particleSeed));
 vec4 n1 = vec4(0.5,1.0,10.0,1.0); // material node VectorParameter
 vec4 n2 = vec4(0.5,1.0,10.0,1.0); // material node VectorParameter
 vec3 n3 = ((n1).rgb*vec3((n2).a)); // material node Multiply
 vec2 n4 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n5 = 0.0; // material node Constant
 vec4 n6 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n7 = vec2(n5,(n6).b); // material node AppendVector
 vec2 n8 = (n4+n7); // material node Add
 vec4 n9 = knifeSample(Sampler0,n8,true,ivec2(1,1)); // material node TextureSample
 float n10 = 6.0; // material node ScalarParameter
 float n11 = pow(max((n9).r,0.0),n10); // material node Power
 float n12 = 0.20000000298; // material node Constant
 float n13 = ((n9).r*n12); // material node Multiply
 float n14 = (n11+n13); // material node Add
 vec2 n15 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n16 = (n15).g; // material node ComponentMask
 float n17 = (1.0-n16); // material node OneMinus
 float n18 = 3.0; // material node Constant
 float n19 = pow(max(n17,0.0),n18); // material node Power
 float n20 = 80.0; // material node Constant
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = (n14*n22); // material node Multiply
 float n24 = clamp(n23,0.0,1.0); // material node ConstantClamp
 vec3 n25 = (n3*vec3(n24)); // material node Multiply
 vec4 n26 = vec4(10.0,3.0,0.20000000298,1.0); // material node VectorParameter
 vec4 n27 = vec4(10.0,3.0,0.20000000298,1.0); // material node VectorParameter
 vec3 n28 = ((n26).rgb*vec3((n27).a)); // material node Multiply
 vec2 n29 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n30 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n31 = vec2((n30).a,(n30).a); // material node AppendVector
 vec2 n32 = (n29+n31); // material node Add
 vec2 n33 = (n32+0.0*vec2(0.0,0.20000000298)); // material node Panner
 vec4 n34 = knifeSample(Sampler2,n33,true,ivec2(0,0)); // material node TextureSample
 float n35 = 0.5; // material node Constant
 float n36 = ((n34).r*n35); // material node Multiply
 vec2 n37 = localUV*vec2(1.20000004768,1.0); // material node TextureCoordinate
 float n38 = 1.0; // material node ScalarParameter
 vec2 n39 = (n37*vec2(n38)); // material node Multiply
 float n40 = (n39).g; // material node ComponentMask
 float n41 = FxTime; // material node Time
 float n42 = (n40+n41); // material node Add
 float n43 = 1.0; // material node ScalarParameter
 vec2 n44 = (vec2(n43)*n37); // material node Multiply
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (n16*n17); // material node Multiply
 float n47 = 4.0; // material node Constant
 float n48 = (n46*n47); // material node Multiply
 float n49 = 1.0; // material node Constant
 float n50 = pow(max(n48,0.0),n49); // material node Power
 float n51 = FxTime; // material node Time
 float n52 = -0.5; // material node ScalarParameter
 float n53 = (n51*n52); // material node Multiply
 float n54 = (n16+n53); // material node Add
 float n55 = 4.0; // material node ScalarParameter
 float n56 = (n54*n55); // material node Multiply
 float n57 = sin(n56*6.28318530718); // material node Sine
 float n58 = 0.0799999982119; // material node ScalarParameter
 float n59 = (n57*n58); // material node Multiply
 float n60 = (n50*n59); // material node Multiply
 float n61 = (n45+n60); // material node Add
 vec2 n62 = vec2(n42,n61); // material node AppendVector
 float n63 = 6.28318309784; // material node Constant
 vec2 n64 = effectBoostRotateUV(n62,vec2(0.5,0.5),n63*0.25); // material node Rotator
 vec2 n65 = (vec2(n36)+n64); // material node Add
 vec4 n66 = knifeSample(Sampler3,vec2(0.0),false,ivec2(1,1)); // material node TextureSample
 vec2 n67 = ((n66).rgb).rg; // material node ComponentMask
 float n68 = 0.20000000298; // material node Constant
 vec2 n69 = mix(n65,n67,vec2(n68)); // material node LinearInterpolate
 vec4 n70 = knifeSample(Sampler1,n69,true,ivec2(1,0)); // material node TextureSample
 float n71 = 1.0; // material node Constant
 float n72 = ((n70).r*n71); // material node Multiply
 float n73 = (n15).r; // material node ComponentMask
 float n74 = (1.0-n73); // material node OneMinus
 float n75 = (n73*n74); // material node Multiply
 float n76 = 2.0; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 6.0; // material node Constant
 float n79 = (n77*n78); // material node Multiply
 float n80 = (n19*n79); // material node Multiply
 float n81 = 5.0; // material node Constant
 float n82 = (n80*n81); // material node Multiply
 float n83 = clamp(n82,0.0,1.0); // material node ConstantClamp
 float n84 = (n72*n83); // material node Multiply
 vec2 n85 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n86 = 0.0; // material node Constant
 vec4 n87 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n88 = vec2(n86,(n87).r); // material node AppendVector
 vec2 n89 = (n85+n88); // material node Add
 vec4 n90 = knifeSample(Sampler4,n89,true,ivec2(1,1)); // material node TextureSample
 float n91 = (n84*(n90).g); // material node Multiply
 float n92 = clamp(n91,0.0,1.0); // material node ConstantClamp
 vec3 n93 = (n28*vec3(n92)); // material node Multiply
 vec3 n94 = (n25+n93); // material node Add
 vec4 n95 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n96 = (n94*(n95).rgb); // material node Multiply
 vec4 n97 = sourceColor; // material node MeshEmitterVertexColor
 float n98 = 0.899999976158; // material node Constant
 vec3 n99 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n100 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 vec3 n101 = n100; // material node FunctionInput
 float n102 = dot(n99,n101); // material node DotProduct
 float n103 = abs(n102); // material node Abs
 float n104 = n98; // material node FunctionInput
 float n105 = pow(max(n103,0.0),n104); // material node Power
 float n106 = n105; // material node MaterialFunctionCall
 float n107 = destinationDepthUE; // material node SceneDepth
 float n108 = sourceDepthUE; // material node PixelDepth
 float n109 = (n107-n108); // material node Subtract
 float n110 = n98; // material node FunctionInput
 float n111 = (n109*n110); // material node Multiply
 float n112 = clamp(n111,0.0,1.0); // material node ConstantClamp
 float n113 = n112; // material node MaterialFunctionCall
 float n114 = (n106*n113); // material node Multiply
 float n115 = ((n97).a*n114); // material node Multiply
 emissive=n96; opacity=n115; distortion=vec2(0.0,0.0);
}

void knifeMaterial124(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<0.457372?sourceHermite(-1.0,-0.342575,0.04447027956,0.578984013196,clamp((age-(0.0))/0.457372,0.0,1.0)):(age<1.216667?sourceHermite(-0.342575,1.010526,0.961186225435,2.39776629107,clamp((age-(0.457372))/0.759295,0.0,1.0)):1.010526)),(age<1.0?mix(1.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(-1.0,1.5,0.203591,4.845595,clamp((age-(0.0))/1.0,0.0,1.0)):1.5),mix(0.0,1.0,particleSeed));
 vec4 n1 = vec4(10.0,1.0,2.0,1.0); // material node VectorParameter
 vec4 n2 = vec4(10.0,1.0,2.0,1.0); // material node VectorParameter
 vec3 n3 = ((n1).rgb*vec3((n2).a)); // material node Multiply
 vec2 n4 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n5 = 0.0; // material node Constant
 vec4 n6 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n7 = vec2(n5,(n6).b); // material node AppendVector
 vec2 n8 = (n4+n7); // material node Add
 vec4 n9 = knifeSample(Sampler0,n8,true,ivec2(1,1)); // material node TextureSample
 float n10 = 6.0; // material node ScalarParameter
 float n11 = pow(max((n9).r,0.0),n10); // material node Power
 float n12 = 0.20000000298; // material node Constant
 float n13 = ((n9).r*n12); // material node Multiply
 float n14 = (n11+n13); // material node Add
 vec2 n15 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n16 = (n15).g; // material node ComponentMask
 float n17 = (1.0-n16); // material node OneMinus
 float n18 = 3.0; // material node Constant
 float n19 = pow(max(n17,0.0),n18); // material node Power
 float n20 = 80.0; // material node Constant
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = (n14*n22); // material node Multiply
 float n24 = clamp(n23,0.0,1.0); // material node ConstantClamp
 vec3 n25 = (n3*vec3(n24)); // material node Multiply
 vec4 n26 = vec4(1.5,0.0500000007451,1.20000004768,1.0); // material node VectorParameter
 vec4 n27 = vec4(1.5,0.0500000007451,1.20000004768,1.0); // material node VectorParameter
 vec3 n28 = ((n26).rgb*vec3((n27).a)); // material node Multiply
 vec2 n29 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n30 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n31 = vec2((n30).a,(n30).a); // material node AppendVector
 vec2 n32 = (n29+n31); // material node Add
 vec2 n33 = (n32+0.0*vec2(0.0,0.20000000298)); // material node Panner
 vec4 n34 = knifeSample(Sampler2,n33,true,ivec2(0,0)); // material node TextureSample
 float n35 = 0.5; // material node Constant
 float n36 = ((n34).r*n35); // material node Multiply
 vec2 n37 = localUV*vec2(1.20000004768,1.0); // material node TextureCoordinate
 float n38 = 1.0; // material node ScalarParameter
 vec2 n39 = (n37*vec2(n38)); // material node Multiply
 float n40 = (n39).g; // material node ComponentMask
 float n41 = FxTime; // material node Time
 float n42 = (n40+n41); // material node Add
 float n43 = 1.0; // material node ScalarParameter
 vec2 n44 = (vec2(n43)*n37); // material node Multiply
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (n16*n17); // material node Multiply
 float n47 = 4.0; // material node Constant
 float n48 = (n46*n47); // material node Multiply
 float n49 = 1.0; // material node Constant
 float n50 = pow(max(n48,0.0),n49); // material node Power
 float n51 = FxTime; // material node Time
 float n52 = -0.5; // material node ScalarParameter
 float n53 = (n51*n52); // material node Multiply
 float n54 = (n16+n53); // material node Add
 float n55 = 4.0; // material node ScalarParameter
 float n56 = (n54*n55); // material node Multiply
 float n57 = sin(n56*6.28318530718); // material node Sine
 float n58 = 0.0799999982119; // material node ScalarParameter
 float n59 = (n57*n58); // material node Multiply
 float n60 = (n50*n59); // material node Multiply
 float n61 = (n45+n60); // material node Add
 vec2 n62 = vec2(n42,n61); // material node AppendVector
 float n63 = 6.28318309784; // material node Constant
 vec2 n64 = effectBoostRotateUV(n62,vec2(0.5,0.5),n63*0.25); // material node Rotator
 vec2 n65 = (vec2(n36)+n64); // material node Add
 vec4 n66 = knifeSample(Sampler3,vec2(0.0),false,ivec2(1,1)); // material node TextureSample
 vec2 n67 = ((n66).rgb).rg; // material node ComponentMask
 float n68 = 0.20000000298; // material node Constant
 vec2 n69 = mix(n65,n67,vec2(n68)); // material node LinearInterpolate
 vec4 n70 = knifeSample(Sampler1,n69,true,ivec2(1,0)); // material node TextureSample
 float n71 = 1.0; // material node Constant
 float n72 = ((n70).r*n71); // material node Multiply
 float n73 = (n15).r; // material node ComponentMask
 float n74 = (1.0-n73); // material node OneMinus
 float n75 = (n73*n74); // material node Multiply
 float n76 = 2.0; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 6.0; // material node Constant
 float n79 = (n77*n78); // material node Multiply
 float n80 = (n19*n79); // material node Multiply
 float n81 = 5.0; // material node Constant
 float n82 = (n80*n81); // material node Multiply
 float n83 = clamp(n82,0.0,1.0); // material node ConstantClamp
 float n84 = (n72*n83); // material node Multiply
 vec2 n85 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n86 = 0.0; // material node Constant
 vec4 n87 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n88 = vec2(n86,(n87).r); // material node AppendVector
 vec2 n89 = (n85+n88); // material node Add
 vec4 n90 = knifeSample(Sampler4,n89,true,ivec2(1,1)); // material node TextureSample
 float n91 = (n84*(n90).g); // material node Multiply
 float n92 = clamp(n91,0.0,1.0); // material node ConstantClamp
 vec3 n93 = (n28*vec3(n92)); // material node Multiply
 vec3 n94 = (n25+n93); // material node Add
 vec4 n95 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n96 = (n94*(n95).rgb); // material node Multiply
 vec4 n97 = sourceColor; // material node MeshEmitterVertexColor
 float n98 = 0.899999976158; // material node Constant
 vec3 n99 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n100 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 vec3 n101 = n100; // material node FunctionInput
 float n102 = dot(n99,n101); // material node DotProduct
 float n103 = abs(n102); // material node Abs
 float n104 = n98; // material node FunctionInput
 float n105 = pow(max(n103,0.0),n104); // material node Power
 float n106 = n105; // material node MaterialFunctionCall
 float n107 = destinationDepthUE; // material node SceneDepth
 float n108 = sourceDepthUE; // material node PixelDepth
 float n109 = (n107-n108); // material node Subtract
 float n110 = n98; // material node FunctionInput
 float n111 = (n109*n110); // material node Multiply
 float n112 = clamp(n111,0.0,1.0); // material node ConstantClamp
 float n113 = n112; // material node MaterialFunctionCall
 float n114 = (n106*n113); // material node Multiply
 float n115 = ((n97).a*n114); // material node Multiply
 emissive=n96; opacity=n115; distortion=vec2(0.0,0.0);
}

void knifeMaterial125(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<0.457372?sourceHermite(-1.0,-0.342575,0.04447027956,0.578984013196,clamp((age-(0.0))/0.457372,0.0,1.0)):(age<1.216667?sourceHermite(-0.342575,1.010526,0.961186225435,2.39776629107,clamp((age-(0.457372))/0.759295,0.0,1.0)):1.010526)),(age<1.0?mix(1.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(-1.0,1.5,0.203591,4.845595,clamp((age-(0.0))/1.0,0.0,1.0)):1.5),mix(0.0,1.0,particleSeed));
 vec4 n1 = vec4(1.0,2.0,10.0,1.0); // material node VectorParameter
 vec4 n2 = vec4(1.0,2.0,10.0,1.0); // material node VectorParameter
 vec3 n3 = ((n1).rgb*vec3((n2).a)); // material node Multiply
 vec2 n4 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n5 = 0.0; // material node Constant
 vec4 n6 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n7 = vec2(n5,(n6).b); // material node AppendVector
 vec2 n8 = (n4+n7); // material node Add
 vec4 n9 = knifeSample(Sampler0,n8,true,ivec2(1,1)); // material node TextureSample
 float n10 = 6.0; // material node ScalarParameter
 float n11 = pow(max((n9).r,0.0),n10); // material node Power
 float n12 = 0.20000000298; // material node Constant
 float n13 = ((n9).r*n12); // material node Multiply
 float n14 = (n11+n13); // material node Add
 vec2 n15 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n16 = (n15).g; // material node ComponentMask
 float n17 = (1.0-n16); // material node OneMinus
 float n18 = 3.0; // material node Constant
 float n19 = pow(max(n17,0.0),n18); // material node Power
 float n20 = 80.0; // material node Constant
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = (n14*n22); // material node Multiply
 float n24 = clamp(n23,0.0,1.0); // material node ConstantClamp
 vec3 n25 = (n3*vec3(n24)); // material node Multiply
 vec4 n26 = vec4(0.0,0.62032532692,1.0,1.0); // material node VectorParameter
 vec4 n27 = vec4(0.0,0.62032532692,1.0,1.0); // material node VectorParameter
 vec3 n28 = ((n26).rgb*vec3((n27).a)); // material node Multiply
 vec2 n29 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n30 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n31 = vec2((n30).a,(n30).a); // material node AppendVector
 vec2 n32 = (n29+n31); // material node Add
 vec2 n33 = (n32+0.0*vec2(0.0,0.20000000298)); // material node Panner
 vec4 n34 = knifeSample(Sampler2,n33,true,ivec2(0,0)); // material node TextureSample
 float n35 = 0.5; // material node Constant
 float n36 = ((n34).r*n35); // material node Multiply
 vec2 n37 = localUV*vec2(1.20000004768,1.0); // material node TextureCoordinate
 float n38 = 1.0; // material node ScalarParameter
 vec2 n39 = (n37*vec2(n38)); // material node Multiply
 float n40 = (n39).g; // material node ComponentMask
 float n41 = FxTime; // material node Time
 float n42 = (n40+n41); // material node Add
 float n43 = 1.0; // material node ScalarParameter
 vec2 n44 = (vec2(n43)*n37); // material node Multiply
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (n16*n17); // material node Multiply
 float n47 = 4.0; // material node Constant
 float n48 = (n46*n47); // material node Multiply
 float n49 = 1.0; // material node Constant
 float n50 = pow(max(n48,0.0),n49); // material node Power
 float n51 = FxTime; // material node Time
 float n52 = -0.5; // material node ScalarParameter
 float n53 = (n51*n52); // material node Multiply
 float n54 = (n16+n53); // material node Add
 float n55 = 4.0; // material node ScalarParameter
 float n56 = (n54*n55); // material node Multiply
 float n57 = sin(n56*6.28318530718); // material node Sine
 float n58 = 0.0799999982119; // material node ScalarParameter
 float n59 = (n57*n58); // material node Multiply
 float n60 = (n50*n59); // material node Multiply
 float n61 = (n45+n60); // material node Add
 vec2 n62 = vec2(n42,n61); // material node AppendVector
 float n63 = 6.28318309784; // material node Constant
 vec2 n64 = effectBoostRotateUV(n62,vec2(0.5,0.5),n63*0.25); // material node Rotator
 vec2 n65 = (vec2(n36)+n64); // material node Add
 vec4 n66 = knifeSample(Sampler3,vec2(0.0),false,ivec2(1,1)); // material node TextureSample
 vec2 n67 = ((n66).rgb).rg; // material node ComponentMask
 float n68 = 0.20000000298; // material node Constant
 vec2 n69 = mix(n65,n67,vec2(n68)); // material node LinearInterpolate
 vec4 n70 = knifeSample(Sampler1,n69,true,ivec2(1,0)); // material node TextureSample
 float n71 = 1.0; // material node Constant
 float n72 = ((n70).r*n71); // material node Multiply
 float n73 = (n15).r; // material node ComponentMask
 float n74 = (1.0-n73); // material node OneMinus
 float n75 = (n73*n74); // material node Multiply
 float n76 = 2.0; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 6.0; // material node Constant
 float n79 = (n77*n78); // material node Multiply
 float n80 = (n19*n79); // material node Multiply
 float n81 = 5.0; // material node Constant
 float n82 = (n80*n81); // material node Multiply
 float n83 = clamp(n82,0.0,1.0); // material node ConstantClamp
 float n84 = (n72*n83); // material node Multiply
 vec2 n85 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n86 = 0.0; // material node Constant
 vec4 n87 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n88 = vec2(n86,(n87).r); // material node AppendVector
 vec2 n89 = (n85+n88); // material node Add
 vec4 n90 = knifeSample(Sampler4,n89,true,ivec2(1,1)); // material node TextureSample
 float n91 = (n84*(n90).g); // material node Multiply
 float n92 = clamp(n91,0.0,1.0); // material node ConstantClamp
 vec3 n93 = (n28*vec3(n92)); // material node Multiply
 vec3 n94 = (n25+n93); // material node Add
 vec4 n95 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n96 = (n94*(n95).rgb); // material node Multiply
 vec4 n97 = sourceColor; // material node MeshEmitterVertexColor
 float n98 = 0.899999976158; // material node Constant
 vec3 n99 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n100 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 vec3 n101 = n100; // material node FunctionInput
 float n102 = dot(n99,n101); // material node DotProduct
 float n103 = abs(n102); // material node Abs
 float n104 = n98; // material node FunctionInput
 float n105 = pow(max(n103,0.0),n104); // material node Power
 float n106 = n105; // material node MaterialFunctionCall
 float n107 = destinationDepthUE; // material node SceneDepth
 float n108 = sourceDepthUE; // material node PixelDepth
 float n109 = (n107-n108); // material node Subtract
 float n110 = n98; // material node FunctionInput
 float n111 = (n109*n110); // material node Multiply
 float n112 = clamp(n111,0.0,1.0); // material node ConstantClamp
 float n113 = n112; // material node MaterialFunctionCall
 float n114 = (n106*n113); // material node Multiply
 float n115 = ((n97).a*n114); // material node Multiply
 emissive=n96; opacity=n115; distortion=vec2(0.0,0.0);
}

void knifeMaterial126(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<0.457372?sourceHermite(-1.0,-0.342575,0.04447027956,0.578984013196,clamp((age-(0.0))/0.457372,0.0,1.0)):(age<1.216667?sourceHermite(-0.342575,1.010526,0.961186225435,2.39776629107,clamp((age-(0.457372))/0.759295,0.0,1.0)):1.010526)),(age<1.0?mix(1.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(-1.0,1.5,0.203591,4.845595,clamp((age-(0.0))/1.0,0.0,1.0)):1.5),mix(0.0,1.0,particleSeed));
 vec4 n1 = vec4(1.0,10.0,2.0,1.0); // material node VectorParameter
 vec4 n2 = vec4(1.0,10.0,2.0,1.0); // material node VectorParameter
 vec3 n3 = ((n1).rgb*vec3((n2).a)); // material node Multiply
 vec2 n4 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n5 = 0.0; // material node Constant
 vec4 n6 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n7 = vec2(n5,(n6).b); // material node AppendVector
 vec2 n8 = (n4+n7); // material node Add
 vec4 n9 = knifeSample(Sampler0,n8,true,ivec2(1,1)); // material node TextureSample
 float n10 = 6.0; // material node ScalarParameter
 float n11 = pow(max((n9).r,0.0),n10); // material node Power
 float n12 = 0.20000000298; // material node Constant
 float n13 = ((n9).r*n12); // material node Multiply
 float n14 = (n11+n13); // material node Add
 vec2 n15 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n16 = (n15).g; // material node ComponentMask
 float n17 = (1.0-n16); // material node OneMinus
 float n18 = 3.0; // material node Constant
 float n19 = pow(max(n17,0.0),n18); // material node Power
 float n20 = 80.0; // material node Constant
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = (n14*n22); // material node Multiply
 float n24 = clamp(n23,0.0,1.0); // material node ConstantClamp
 vec3 n25 = (n3*vec3(n24)); // material node Multiply
 vec4 n26 = vec4(0.0,1.0,0.104285478592,1.0); // material node VectorParameter
 vec4 n27 = vec4(0.0,1.0,0.104285478592,1.0); // material node VectorParameter
 vec3 n28 = ((n26).rgb*vec3((n27).a)); // material node Multiply
 vec2 n29 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n30 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n31 = vec2((n30).a,(n30).a); // material node AppendVector
 vec2 n32 = (n29+n31); // material node Add
 vec2 n33 = (n32+0.0*vec2(0.0,0.20000000298)); // material node Panner
 vec4 n34 = knifeSample(Sampler2,n33,true,ivec2(0,0)); // material node TextureSample
 float n35 = 0.5; // material node Constant
 float n36 = ((n34).r*n35); // material node Multiply
 vec2 n37 = localUV*vec2(1.20000004768,1.0); // material node TextureCoordinate
 float n38 = 1.0; // material node ScalarParameter
 vec2 n39 = (n37*vec2(n38)); // material node Multiply
 float n40 = (n39).g; // material node ComponentMask
 float n41 = FxTime; // material node Time
 float n42 = (n40+n41); // material node Add
 float n43 = 1.0; // material node ScalarParameter
 vec2 n44 = (vec2(n43)*n37); // material node Multiply
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (n16*n17); // material node Multiply
 float n47 = 4.0; // material node Constant
 float n48 = (n46*n47); // material node Multiply
 float n49 = 1.0; // material node Constant
 float n50 = pow(max(n48,0.0),n49); // material node Power
 float n51 = FxTime; // material node Time
 float n52 = -0.5; // material node ScalarParameter
 float n53 = (n51*n52); // material node Multiply
 float n54 = (n16+n53); // material node Add
 float n55 = 4.0; // material node ScalarParameter
 float n56 = (n54*n55); // material node Multiply
 float n57 = sin(n56*6.28318530718); // material node Sine
 float n58 = 0.0799999982119; // material node ScalarParameter
 float n59 = (n57*n58); // material node Multiply
 float n60 = (n50*n59); // material node Multiply
 float n61 = (n45+n60); // material node Add
 vec2 n62 = vec2(n42,n61); // material node AppendVector
 float n63 = 6.28318309784; // material node Constant
 vec2 n64 = effectBoostRotateUV(n62,vec2(0.5,0.5),n63*0.25); // material node Rotator
 vec2 n65 = (vec2(n36)+n64); // material node Add
 vec4 n66 = knifeSample(Sampler3,vec2(0.0),false,ivec2(1,1)); // material node TextureSample
 vec2 n67 = ((n66).rgb).rg; // material node ComponentMask
 float n68 = 0.20000000298; // material node Constant
 vec2 n69 = mix(n65,n67,vec2(n68)); // material node LinearInterpolate
 vec4 n70 = knifeSample(Sampler1,n69,true,ivec2(1,0)); // material node TextureSample
 float n71 = 1.0; // material node Constant
 float n72 = ((n70).r*n71); // material node Multiply
 float n73 = (n15).r; // material node ComponentMask
 float n74 = (1.0-n73); // material node OneMinus
 float n75 = (n73*n74); // material node Multiply
 float n76 = 2.0; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 6.0; // material node Constant
 float n79 = (n77*n78); // material node Multiply
 float n80 = (n19*n79); // material node Multiply
 float n81 = 5.0; // material node Constant
 float n82 = (n80*n81); // material node Multiply
 float n83 = clamp(n82,0.0,1.0); // material node ConstantClamp
 float n84 = (n72*n83); // material node Multiply
 vec2 n85 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n86 = 0.0; // material node Constant
 vec4 n87 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n88 = vec2(n86,(n87).r); // material node AppendVector
 vec2 n89 = (n85+n88); // material node Add
 vec4 n90 = knifeSample(Sampler4,n89,true,ivec2(1,1)); // material node TextureSample
 float n91 = (n84*(n90).g); // material node Multiply
 float n92 = clamp(n91,0.0,1.0); // material node ConstantClamp
 vec3 n93 = (n28*vec3(n92)); // material node Multiply
 vec3 n94 = (n25+n93); // material node Add
 vec4 n95 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n96 = (n94*(n95).rgb); // material node Multiply
 vec4 n97 = sourceColor; // material node MeshEmitterVertexColor
 float n98 = 0.899999976158; // material node Constant
 vec3 n99 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n100 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 vec3 n101 = n100; // material node FunctionInput
 float n102 = dot(n99,n101); // material node DotProduct
 float n103 = abs(n102); // material node Abs
 float n104 = n98; // material node FunctionInput
 float n105 = pow(max(n103,0.0),n104); // material node Power
 float n106 = n105; // material node MaterialFunctionCall
 float n107 = destinationDepthUE; // material node SceneDepth
 float n108 = sourceDepthUE; // material node PixelDepth
 float n109 = (n107-n108); // material node Subtract
 float n110 = n98; // material node FunctionInput
 float n111 = (n109*n110); // material node Multiply
 float n112 = clamp(n111,0.0,1.0); // material node ConstantClamp
 float n113 = n112; // material node MaterialFunctionCall
 float n114 = (n106*n113); // material node Multiply
 float n115 = ((n97).a*n114); // material node Multiply
 emissive=n96; opacity=n115; distortion=vec2(0.0,0.0);
}

void knifeMaterial127(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<0.457372?sourceHermite(-1.0,-0.342575,0.04447027956,0.578984013196,clamp((age-(0.0))/0.457372,0.0,1.0)):(age<1.216667?sourceHermite(-0.342575,1.010526,0.961186225435,2.39776629107,clamp((age-(0.457372))/0.759295,0.0,1.0)):1.010526)),(age<1.0?mix(1.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(-1.0,1.5,0.203591,4.845595,clamp((age-(0.0))/1.0,0.0,1.0)):1.5),mix(0.0,1.0,particleSeed));
 vec4 n1 = vec4(0.5,1.0,10.0,1.0); // material node VectorParameter
 vec4 n2 = vec4(0.5,1.0,10.0,1.0); // material node VectorParameter
 vec3 n3 = ((n1).rgb*vec3((n2).a)); // material node Multiply
 vec2 n4 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n5 = 0.0; // material node Constant
 vec4 n6 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n7 = vec2(n5,(n6).b); // material node AppendVector
 vec2 n8 = (n4+n7); // material node Add
 vec4 n9 = knifeSample(Sampler0,n8,true,ivec2(1,1)); // material node TextureSample
 float n10 = 6.0; // material node ScalarParameter
 float n11 = pow(max((n9).r,0.0),n10); // material node Power
 float n12 = 0.20000000298; // material node Constant
 float n13 = ((n9).r*n12); // material node Multiply
 float n14 = (n11+n13); // material node Add
 vec2 n15 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n16 = (n15).g; // material node ComponentMask
 float n17 = (1.0-n16); // material node OneMinus
 float n18 = 3.0; // material node Constant
 float n19 = pow(max(n17,0.0),n18); // material node Power
 float n20 = 80.0; // material node Constant
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = (n14*n22); // material node Multiply
 float n24 = clamp(n23,0.0,1.0); // material node ConstantClamp
 vec3 n25 = (n3*vec3(n24)); // material node Multiply
 vec4 n26 = vec4(1.0,0.600000023842,0.0500000007451,1.0); // material node VectorParameter
 vec4 n27 = vec4(1.0,0.600000023842,0.0500000007451,1.0); // material node VectorParameter
 vec3 n28 = ((n26).rgb*vec3((n27).a)); // material node Multiply
 vec2 n29 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n30 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n31 = vec2((n30).a,(n30).a); // material node AppendVector
 vec2 n32 = (n29+n31); // material node Add
 vec2 n33 = (n32+0.0*vec2(0.0,0.20000000298)); // material node Panner
 vec4 n34 = knifeSample(Sampler2,n33,true,ivec2(0,0)); // material node TextureSample
 float n35 = 0.5; // material node Constant
 float n36 = ((n34).r*n35); // material node Multiply
 vec2 n37 = localUV*vec2(1.20000004768,1.0); // material node TextureCoordinate
 float n38 = 1.0; // material node ScalarParameter
 vec2 n39 = (n37*vec2(n38)); // material node Multiply
 float n40 = (n39).g; // material node ComponentMask
 float n41 = FxTime; // material node Time
 float n42 = (n40+n41); // material node Add
 float n43 = 1.0; // material node ScalarParameter
 vec2 n44 = (vec2(n43)*n37); // material node Multiply
 float n45 = (n44).r; // material node ComponentMask
 float n46 = (n16*n17); // material node Multiply
 float n47 = 4.0; // material node Constant
 float n48 = (n46*n47); // material node Multiply
 float n49 = 1.0; // material node Constant
 float n50 = pow(max(n48,0.0),n49); // material node Power
 float n51 = FxTime; // material node Time
 float n52 = -0.5; // material node ScalarParameter
 float n53 = (n51*n52); // material node Multiply
 float n54 = (n16+n53); // material node Add
 float n55 = 4.0; // material node ScalarParameter
 float n56 = (n54*n55); // material node Multiply
 float n57 = sin(n56*6.28318530718); // material node Sine
 float n58 = 0.0799999982119; // material node ScalarParameter
 float n59 = (n57*n58); // material node Multiply
 float n60 = (n50*n59); // material node Multiply
 float n61 = (n45+n60); // material node Add
 vec2 n62 = vec2(n42,n61); // material node AppendVector
 float n63 = 6.28318309784; // material node Constant
 vec2 n64 = effectBoostRotateUV(n62,vec2(0.5,0.5),n63*0.25); // material node Rotator
 vec2 n65 = (vec2(n36)+n64); // material node Add
 vec4 n66 = knifeSample(Sampler3,vec2(0.0),false,ivec2(1,1)); // material node TextureSample
 vec2 n67 = ((n66).rgb).rg; // material node ComponentMask
 float n68 = 0.20000000298; // material node Constant
 vec2 n69 = mix(n65,n67,vec2(n68)); // material node LinearInterpolate
 vec4 n70 = knifeSample(Sampler1,n69,true,ivec2(1,0)); // material node TextureSample
 float n71 = 1.0; // material node Constant
 float n72 = ((n70).r*n71); // material node Multiply
 float n73 = (n15).r; // material node ComponentMask
 float n74 = (1.0-n73); // material node OneMinus
 float n75 = (n73*n74); // material node Multiply
 float n76 = 2.0; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 6.0; // material node Constant
 float n79 = (n77*n78); // material node Multiply
 float n80 = (n19*n79); // material node Multiply
 float n81 = 5.0; // material node Constant
 float n82 = (n80*n81); // material node Multiply
 float n83 = clamp(n82,0.0,1.0); // material node ConstantClamp
 float n84 = (n72*n83); // material node Multiply
 vec2 n85 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n86 = 0.0; // material node Constant
 vec4 n87 = dynamic4; // material node MeshEmitterDynamicParameter
 vec2 n88 = vec2(n86,(n87).r); // material node AppendVector
 vec2 n89 = (n85+n88); // material node Add
 vec4 n90 = knifeSample(Sampler4,n89,true,ivec2(1,1)); // material node TextureSample
 float n91 = (n84*(n90).g); // material node Multiply
 float n92 = clamp(n91,0.0,1.0); // material node ConstantClamp
 vec3 n93 = (n28*vec3(n92)); // material node Multiply
 vec3 n94 = (n25+n93); // material node Add
 vec4 n95 = sourceColor; // material node MeshEmitterVertexColor
 vec3 n96 = (n94*(n95).rgb); // material node Multiply
 vec4 n97 = sourceColor; // material node MeshEmitterVertexColor
 float n98 = 0.899999976158; // material node Constant
 vec3 n99 = vec3(0.0,0.0,facing); // material node CameraVector
 vec3 n100 = vec3(0.0,0.0,1.0); // material node Constant3Vector
 vec3 n101 = n100; // material node FunctionInput
 float n102 = dot(n99,n101); // material node DotProduct
 float n103 = abs(n102); // material node Abs
 float n104 = n98; // material node FunctionInput
 float n105 = pow(max(n103,0.0),n104); // material node Power
 float n106 = n105; // material node MaterialFunctionCall
 float n107 = destinationDepthUE; // material node SceneDepth
 float n108 = sourceDepthUE; // material node PixelDepth
 float n109 = (n107-n108); // material node Subtract
 float n110 = n98; // material node FunctionInput
 float n111 = (n109*n110); // material node Multiply
 float n112 = clamp(n111,0.0,1.0); // material node ConstantClamp
 float n113 = n112; // material node MaterialFunctionCall
 float n114 = (n106*n113); // material node Multiply
 float n115 = ((n97).a*n114); // material node Multiply
 emissive=n96; opacity=n115; distortion=vec2(0.0,0.0);
}

// SOURCE_HELD_CORE_BEGIN
void knifeMaterial128(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(1.0,1.0,1.0,1.0); // material node VectorParameter
 vec4 n2 = sourceColor; // material node VertexColor
 vec3 n3 = ((n1).rgb*(n2).rgb); // material node Multiply
 vec2 n4 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n5 = 0.5; // material node Constant
 float n6 = knifeSphere(n4,vec2(n5),0.5,0.0); // material node SphereMask
 float n7 = (1.0-n6); // material node OneMinus
 float n8 = (1.0-n7); // material node OneMinus
 float n9 = 4.0; // material node ScalarParameter
 float n10 = pow(max(n8,0.0),n9); // material node Power
 vec4 n11 = sourceColor; // material node VertexColor
 float n12 = (n10*(n11).a); // material node Multiply
 float n13 = effectBoostDepthAlpha(n12,0.5,10.0,sourceDepthUE,destinationDepthUE); // material node DepthBiasedAlpha
 emissive=n3; opacity=n13; distortion=vec2(0.0,0.0);
}

void knifeMaterial129(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4((age<1.0?mix(0.0,0.0,clamp((age-(0.0))/1.0,0.0,1.0)):0.0),(age<1.0?sourceHermite(0.0,1.0,0.0,0.0,clamp((age-(0.2))/0.8,0.0,1.0)):1.0),mix(1.0,1.0,particleSeed),(0.0<1.0?mix(1.0,0.0,clamp((0.0-(0.0))/1.0,0.0,1.0)):0.0));
 float n1 = 1.0; // material node ScalarParameter
 vec4 n2 = vec4(1.0,0.761256217957,0.535860538483,1.0); // material node VectorParameter
 vec4 n3 = knifeSample(Sampler0,localUV,true,ivec2(0,0)); // material node TextureSampleParameter2D
 float n4 = 2.0; // material node ScalarParameter
 vec3 n5 = pow(max((n3).rgb,vec3(0.0)),vec3(n4)); // material node Power
 float n6 = 1.5; // material node Constant
 vec3 n7 = pow(max((n3).rgb,vec3(0.0)),vec3(n6)); // material node Power
 vec3 n8 = (n5+n7); // material node Add
 float n9 = 0.0; // material node ScalarParameter
 vec3 n10 = mix(n8,vec3(dot(n8,vec3(0.3,0.59,0.11))),n9); // material node Desaturation
 vec3 n11 = ((n2).rgb*n10); // material node Multiply
 vec3 n12 = (vec3(n1)*n11); // material node Multiply
 vec4 n13 = sourceColor; // material node VertexColor
 vec3 n14 = (n12*(n13).rgb); // material node Multiply
 vec4 n15 = sourceColor; // material node VertexColor
 float n16 = 0.0299999993294; // material node ScalarParameter
 float n17 = destinationDepthUE; // material node SceneDepth
 float n18 = sourceDepthUE; // material node PixelDepth
 float n19 = (n17-n18); // material node Subtract
 float n20 = n16; // material node FunctionInput
 float n21 = (n19*n20); // material node Multiply
 float n22 = clamp(n21,0.0,1.0); // material node ConstantClamp
 float n23 = n22; // material node MaterialFunctionCall
 float n24 = ((n15).a*n23); // material node Multiply
 emissive=n14; opacity=n24; distortion=vec2(0.0,0.0);
}

void knifeMaterial130(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(0.20000000298,0.40000000596,5.0,0.0); // material node VectorParameter
 vec2 n2 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n3 = (n2+FxTime*vec2(1.0,0.0)); // material node Panner
 vec4 n4 = knifeSample(Sampler1,n3,true,ivec2(0,0)); // material node TextureSample
 float n5 = 0.20000000298; // material node Constant
 float n6 = ((n4).r*n5); // material node Multiply
 vec2 n7 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 float n8 = 1.0; // material node Constant
 vec2 n9 = (n7-vec2(n8)); // material node Subtract
 vec2 n10 = n9; // material node FunctionInput
 float n11 = (n10).r; // material node ComponentMask
 float n12 = (n10).g; // material node ComponentMask
 float n13 = atan(n12,n11); // material node MaterialExpressionCustom
 float n14 = n13; // material node ComponentMask
 float n15 = 0.0; // material node Constant
 float n16 = 6.28318500519; // material node Constant
 float n17 = (n14/n16); // material node Divide
 float n18 = (n14+n16); // material node Add
 float n19 = (n18/n16); // material node Divide
 float n20 = (abs((n14-n15))<=1e-05?n17:((n14-n15)>0.0?n17:n19)); // material node MaterialExpressionIf
 float n21 = n20; // material node MaterialFunctionCall
 vec4 n22 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n23 = (n21*(n22).r); // material node Multiply
 vec2 n24 = (n10*n10); // material node Multiply
 float n25 = (n24).r; // material node ComponentMask
 float n26 = (n24).g; // material node ComponentMask
 float n27 = (n25+n26); // material node Add
 float n28 = sqrt(n27); // material node SquareRoot
 float n29 = n28; // material node MaterialFunctionCall
 vec4 n30 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n31 = (n29*(n30).g); // material node Multiply
 vec4 n32 = dynamic4; // material node DynamicParameter
 float n33 = pow(max(n31,0.0),(n32).g); // material node Power
 float n34 = 1.5; // material node ScalarParameter
 float n35 = FxTime; // material node Time
 float n36 = (n34*n35); // material node Multiply
 float n37 = (n33+n36); // material node Add
 vec2 n38 = vec2(n23,n37); // material node AppendVector
 vec2 n39 = (vec2(n6)+n38); // material node Add
 vec4 n40 = knifeSample(Sampler0,n39,true,ivec2(0,0)); // material node TextureSampleParameter2D
 float n41 = 5.0; // material node ScalarParameter
 float n42 = 3.0; // material node ScalarParameter
 vec3 n43 = pow(max((n40).rgb,vec3(0.0)),vec3(n42)); // material node Power
 vec3 n44 = (vec3(n41)*n43); // material node Multiply
 vec3 n45 = ((n40).rgb+n44); // material node Add
 vec2 n46 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n47 = (n46+FxTime*vec2(0.5,0.0)); // material node Panner
 vec4 n48 = knifeSample(Sampler1,n47,true,ivec2(0,0)); // material node TextureSample
 float n49 = 0.20000000298; // material node Constant
 float n50 = ((n48).r*n49); // material node Multiply
 vec2 n51 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 float n52 = 1.0; // material node Constant
 vec2 n53 = (n51-vec2(n52)); // material node Subtract
 vec2 n54 = n53; // material node FunctionInput
 float n55 = (n54).r; // material node ComponentMask
 float n56 = (n54).g; // material node ComponentMask
 float n57 = atan(n56,n55); // material node MaterialExpressionCustom
 float n58 = n57; // material node ComponentMask
 float n59 = 0.0; // material node Constant
 float n60 = 6.28318500519; // material node Constant
 float n61 = (n58/n60); // material node Divide
 float n62 = (n58+n60); // material node Add
 float n63 = (n62/n60); // material node Divide
 float n64 = (abs((n58-n59))<=1e-05?n61:((n58-n59)>0.0?n61:n63)); // material node MaterialExpressionIf
 float n65 = n64; // material node MaterialFunctionCall
 vec4 n66 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n67 = (n65*(n66).r); // material node Multiply
 vec2 n68 = (n54*n54); // material node Multiply
 float n69 = (n68).r; // material node ComponentMask
 float n70 = (n68).g; // material node ComponentMask
 float n71 = (n69+n70); // material node Add
 float n72 = sqrt(n71); // material node SquareRoot
 float n73 = n72; // material node MaterialFunctionCall
 vec4 n74 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n75 = (n73*(n74).g); // material node Multiply
 float n76 = 0.5; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 0.0; // material node Constant
 float n79 = (n77+n78); // material node Add
 vec2 n80 = vec2(n67,n79); // material node AppendVector
 vec2 n81 = (vec2(n50)+n80); // material node Add
 vec4 n82 = knifeSample(Sampler2,n81,true,ivec2(0,1)); // material node TextureSampleParameter2D
 vec3 n83 = (n45+(n82).rgb); // material node Add
 float n84 = 5.0; // material node Constant
 vec3 n85 = (n83*vec3(n84)); // material node Multiply
 float n86 = 1.0; // material node ScalarParameter
 vec3 n87 = pow(max(n85,vec3(0.0)),vec3(n86)); // material node Power
 vec3 n88 = ((n1).rgb*n87); // material node Multiply
 vec3 n89 = (n88*n87); // material node Multiply
 vec4 n90 = sourceColor; // material node VertexColor
 vec2 n91 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n92 = 0.5; // material node Constant
 float n93 = 0.5; // material node Constant
 float n94 = 0.0; // material node Constant
 float n95 = knifeSphere(n91,vec2(n92),n93,n94); // material node SphereMask
 vec2 n96 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n97 = vec4(2.0,2.0,0.0,1.0); // material node VectorParameter
 vec2 n98 = ((n97).rgb).rg; // material node ComponentMask
 vec2 n99 = (n96*n98); // material node Multiply
 vec4 n100 = dynamic4; // material node DynamicParameter
 float n101 = (n100).r; // material node ComponentMask
 float n102 = n101; // material node FunctionInput
 float n103 = 1.0; // material node MaterialExpressionStaticBool
 float n104 = n103; // material node FunctionInput
 vec2 n105 = n99; // material node FunctionInput
 vec4 n106 = knifeSample(Sampler3,n105,true,ivec2(0,0)); // material node MaterialExpressionTextureSample
 vec2 n107 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n108 = n107; // material node FunctionInput
 float n109 = 43758.5429688; // material node Constant
 float n110 = n109; // material node FunctionInput
 float n111 = fract(sin(dot(n108,vec2(12.9898,78.233)))*n110); // material node MaterialExpressionCustom
 float n112 = n111; // material node MaterialFunctionCall
 float n113 = (n104>0.5?(n106).r:n112); // material node MaterialExpressionStaticSwitch
 float n114 = clamp(n113,0.0,1.0); // material node ConstantClamp
 float n115 = 0.0; // material node Constant
 float n116 = (n114+n115); // material node Add
 float n117 = 1.0; // material node Constant
 float n118 = 0.0; // material node Constant
 float n119 = (abs((n102-n116))<=1e-05?n118:((n102-n116)>0.0?n117:n118)); // material node MaterialExpressionIf
 float n120 = n119; // material node MaterialFunctionCall
 float n121 = (n95*n120); // material node Multiply
 vec3 n122 = (vec3(n121)+(n82).rgb); // material node Add
 vec3 n123 = clamp(n122,vec3(0.0),vec3(1.0)); // material node ConstantClamp
 vec3 n124 = (vec3((n90).a)*n123); // material node Multiply
 emissive=n89; opacity=(n124).x; distortion=vec2(0.0,0.0);
}

void knifeMaterial131(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(6.0,0.0787258148193,5.0,0.0); // material node VectorParameter
 vec2 n2 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n3 = (n2+FxTime*vec2(1.0,0.0)); // material node Panner
 vec4 n4 = knifeSample(Sampler1,n3,true,ivec2(0,0)); // material node TextureSample
 float n5 = 0.20000000298; // material node Constant
 float n6 = ((n4).r*n5); // material node Multiply
 vec2 n7 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 float n8 = 1.0; // material node Constant
 vec2 n9 = (n7-vec2(n8)); // material node Subtract
 vec2 n10 = n9; // material node FunctionInput
 float n11 = (n10).r; // material node ComponentMask
 float n12 = (n10).g; // material node ComponentMask
 float n13 = atan(n12,n11); // material node MaterialExpressionCustom
 float n14 = n13; // material node ComponentMask
 float n15 = 0.0; // material node Constant
 float n16 = 6.28318500519; // material node Constant
 float n17 = (n14/n16); // material node Divide
 float n18 = (n14+n16); // material node Add
 float n19 = (n18/n16); // material node Divide
 float n20 = (abs((n14-n15))<=1e-05?n17:((n14-n15)>0.0?n17:n19)); // material node MaterialExpressionIf
 float n21 = n20; // material node MaterialFunctionCall
 vec4 n22 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n23 = (n21*(n22).r); // material node Multiply
 vec2 n24 = (n10*n10); // material node Multiply
 float n25 = (n24).r; // material node ComponentMask
 float n26 = (n24).g; // material node ComponentMask
 float n27 = (n25+n26); // material node Add
 float n28 = sqrt(n27); // material node SquareRoot
 float n29 = n28; // material node MaterialFunctionCall
 vec4 n30 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n31 = (n29*(n30).g); // material node Multiply
 vec4 n32 = dynamic4; // material node DynamicParameter
 float n33 = pow(max(n31,0.0),(n32).g); // material node Power
 float n34 = 1.5; // material node ScalarParameter
 float n35 = FxTime; // material node Time
 float n36 = (n34*n35); // material node Multiply
 float n37 = (n33+n36); // material node Add
 vec2 n38 = vec2(n23,n37); // material node AppendVector
 vec2 n39 = (vec2(n6)+n38); // material node Add
 vec4 n40 = knifeSample(Sampler0,n39,true,ivec2(0,0)); // material node TextureSampleParameter2D
 float n41 = 5.0; // material node ScalarParameter
 float n42 = 3.0; // material node ScalarParameter
 vec3 n43 = pow(max((n40).rgb,vec3(0.0)),vec3(n42)); // material node Power
 vec3 n44 = (vec3(n41)*n43); // material node Multiply
 vec3 n45 = ((n40).rgb+n44); // material node Add
 vec2 n46 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n47 = (n46+FxTime*vec2(0.5,0.0)); // material node Panner
 vec4 n48 = knifeSample(Sampler1,n47,true,ivec2(0,0)); // material node TextureSample
 float n49 = 0.20000000298; // material node Constant
 float n50 = ((n48).r*n49); // material node Multiply
 vec2 n51 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 float n52 = 1.0; // material node Constant
 vec2 n53 = (n51-vec2(n52)); // material node Subtract
 vec2 n54 = n53; // material node FunctionInput
 float n55 = (n54).r; // material node ComponentMask
 float n56 = (n54).g; // material node ComponentMask
 float n57 = atan(n56,n55); // material node MaterialExpressionCustom
 float n58 = n57; // material node ComponentMask
 float n59 = 0.0; // material node Constant
 float n60 = 6.28318500519; // material node Constant
 float n61 = (n58/n60); // material node Divide
 float n62 = (n58+n60); // material node Add
 float n63 = (n62/n60); // material node Divide
 float n64 = (abs((n58-n59))<=1e-05?n61:((n58-n59)>0.0?n61:n63)); // material node MaterialExpressionIf
 float n65 = n64; // material node MaterialFunctionCall
 vec4 n66 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n67 = (n65*(n66).r); // material node Multiply
 vec2 n68 = (n54*n54); // material node Multiply
 float n69 = (n68).r; // material node ComponentMask
 float n70 = (n68).g; // material node ComponentMask
 float n71 = (n69+n70); // material node Add
 float n72 = sqrt(n71); // material node SquareRoot
 float n73 = n72; // material node MaterialFunctionCall
 vec4 n74 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n75 = (n73*(n74).g); // material node Multiply
 float n76 = 0.5; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 0.0; // material node Constant
 float n79 = (n77+n78); // material node Add
 vec2 n80 = vec2(n67,n79); // material node AppendVector
 vec2 n81 = (vec2(n50)+n80); // material node Add
 vec4 n82 = knifeSample(Sampler2,n81,true,ivec2(0,1)); // material node TextureSampleParameter2D
 vec3 n83 = (n45+(n82).rgb); // material node Add
 float n84 = 5.0; // material node Constant
 vec3 n85 = (n83*vec3(n84)); // material node Multiply
 float n86 = 1.0; // material node ScalarParameter
 vec3 n87 = pow(max(n85,vec3(0.0)),vec3(n86)); // material node Power
 vec3 n88 = ((n1).rgb*n87); // material node Multiply
 vec3 n89 = (n88*n87); // material node Multiply
 vec4 n90 = sourceColor; // material node VertexColor
 vec2 n91 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n92 = 0.5; // material node Constant
 float n93 = 0.5; // material node Constant
 float n94 = 0.0; // material node Constant
 float n95 = knifeSphere(n91,vec2(n92),n93,n94); // material node SphereMask
 vec2 n96 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n97 = vec4(2.0,2.0,0.0,1.0); // material node VectorParameter
 vec2 n98 = ((n97).rgb).rg; // material node ComponentMask
 vec2 n99 = (n96*n98); // material node Multiply
 vec4 n100 = dynamic4; // material node DynamicParameter
 float n101 = (n100).r; // material node ComponentMask
 float n102 = n101; // material node FunctionInput
 float n103 = 1.0; // material node MaterialExpressionStaticBool
 float n104 = n103; // material node FunctionInput
 vec2 n105 = n99; // material node FunctionInput
 vec4 n106 = knifeSample(Sampler3,n105,true,ivec2(0,0)); // material node MaterialExpressionTextureSample
 vec2 n107 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n108 = n107; // material node FunctionInput
 float n109 = 43758.5429688; // material node Constant
 float n110 = n109; // material node FunctionInput
 float n111 = fract(sin(dot(n108,vec2(12.9898,78.233)))*n110); // material node MaterialExpressionCustom
 float n112 = n111; // material node MaterialFunctionCall
 float n113 = (n104>0.5?(n106).r:n112); // material node MaterialExpressionStaticSwitch
 float n114 = clamp(n113,0.0,1.0); // material node ConstantClamp
 float n115 = 0.0; // material node Constant
 float n116 = (n114+n115); // material node Add
 float n117 = 1.0; // material node Constant
 float n118 = 0.0; // material node Constant
 float n119 = (abs((n102-n116))<=1e-05?n118:((n102-n116)>0.0?n117:n118)); // material node MaterialExpressionIf
 float n120 = n119; // material node MaterialFunctionCall
 float n121 = (n95*n120); // material node Multiply
 vec3 n122 = (vec3(n121)+(n82).rgb); // material node Add
 vec3 n123 = clamp(n122,vec3(0.0),vec3(1.0)); // material node ConstantClamp
 vec3 n124 = (vec3((n90).a)*n123); // material node Multiply
 emissive=n89; opacity=(n124).x; distortion=vec2(0.0,0.0);
}

void knifeMaterial132(float sourceDepthUE,float destinationDepthUE,float facing,out vec3 emissive,out float opacity,out vec2 distortion) {
 vec4 dynamic4=vec4(1.0,1.0,1.0,1.0);
 vec4 n1 = vec4(20.0,5.0,0.300000011921,0.0); // material node VectorParameter
 vec2 n2 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n3 = (n2+FxTime*vec2(1.0,0.0)); // material node Panner
 vec4 n4 = knifeSample(Sampler1,n3,true,ivec2(0,0)); // material node TextureSample
 float n5 = 0.20000000298; // material node Constant
 float n6 = ((n4).r*n5); // material node Multiply
 vec2 n7 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 float n8 = 1.0; // material node Constant
 vec2 n9 = (n7-vec2(n8)); // material node Subtract
 vec2 n10 = n9; // material node FunctionInput
 float n11 = (n10).r; // material node ComponentMask
 float n12 = (n10).g; // material node ComponentMask
 float n13 = atan(n12,n11); // material node MaterialExpressionCustom
 float n14 = n13; // material node ComponentMask
 float n15 = 0.0; // material node Constant
 float n16 = 6.28318500519; // material node Constant
 float n17 = (n14/n16); // material node Divide
 float n18 = (n14+n16); // material node Add
 float n19 = (n18/n16); // material node Divide
 float n20 = (abs((n14-n15))<=1e-05?n17:((n14-n15)>0.0?n17:n19)); // material node MaterialExpressionIf
 float n21 = n20; // material node MaterialFunctionCall
 vec4 n22 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n23 = (n21*(n22).r); // material node Multiply
 vec2 n24 = (n10*n10); // material node Multiply
 float n25 = (n24).r; // material node ComponentMask
 float n26 = (n24).g; // material node ComponentMask
 float n27 = (n25+n26); // material node Add
 float n28 = sqrt(n27); // material node SquareRoot
 float n29 = n28; // material node MaterialFunctionCall
 vec4 n30 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n31 = (n29*(n30).g); // material node Multiply
 vec4 n32 = dynamic4; // material node DynamicParameter
 float n33 = pow(max(n31,0.0),(n32).g); // material node Power
 float n34 = 1.5; // material node ScalarParameter
 float n35 = FxTime; // material node Time
 float n36 = (n34*n35); // material node Multiply
 float n37 = (n33+n36); // material node Add
 vec2 n38 = vec2(n23,n37); // material node AppendVector
 vec2 n39 = (vec2(n6)+n38); // material node Add
 vec4 n40 = knifeSample(Sampler0,n39,true,ivec2(0,0)); // material node TextureSampleParameter2D
 float n41 = 5.0; // material node ScalarParameter
 float n42 = 3.0; // material node ScalarParameter
 vec3 n43 = pow(max((n40).rgb,vec3(0.0)),vec3(n42)); // material node Power
 vec3 n44 = (vec3(n41)*n43); // material node Multiply
 vec3 n45 = ((n40).rgb+n44); // material node Add
 vec2 n46 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n47 = (n46+FxTime*vec2(0.5,0.0)); // material node Panner
 vec4 n48 = knifeSample(Sampler1,n47,true,ivec2(0,0)); // material node TextureSample
 float n49 = 0.20000000298; // material node Constant
 float n50 = ((n48).r*n49); // material node Multiply
 vec2 n51 = localUV*vec2(2.0,2.0); // material node TextureCoordinate
 float n52 = 1.0; // material node Constant
 vec2 n53 = (n51-vec2(n52)); // material node Subtract
 vec2 n54 = n53; // material node FunctionInput
 float n55 = (n54).r; // material node ComponentMask
 float n56 = (n54).g; // material node ComponentMask
 float n57 = atan(n56,n55); // material node MaterialExpressionCustom
 float n58 = n57; // material node ComponentMask
 float n59 = 0.0; // material node Constant
 float n60 = 6.28318500519; // material node Constant
 float n61 = (n58/n60); // material node Divide
 float n62 = (n58+n60); // material node Add
 float n63 = (n62/n60); // material node Divide
 float n64 = (abs((n58-n59))<=1e-05?n61:((n58-n59)>0.0?n61:n63)); // material node MaterialExpressionIf
 float n65 = n64; // material node MaterialFunctionCall
 vec4 n66 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n67 = (n65*(n66).r); // material node Multiply
 vec2 n68 = (n54*n54); // material node Multiply
 float n69 = (n68).r; // material node ComponentMask
 float n70 = (n68).g; // material node ComponentMask
 float n71 = (n69+n70); // material node Add
 float n72 = sqrt(n71); // material node SquareRoot
 float n73 = n72; // material node MaterialFunctionCall
 vec4 n74 = vec4(1.5,1.0,0.0,1.0); // material node VectorParameter
 float n75 = (n73*(n74).g); // material node Multiply
 float n76 = 0.5; // material node Constant
 float n77 = pow(max(n75,0.0),n76); // material node Power
 float n78 = 0.0; // material node Constant
 float n79 = (n77+n78); // material node Add
 vec2 n80 = vec2(n67,n79); // material node AppendVector
 vec2 n81 = (vec2(n50)+n80); // material node Add
 vec4 n82 = knifeSample(Sampler2,n81,true,ivec2(0,1)); // material node TextureSampleParameter2D
 vec3 n83 = (n45+(n82).rgb); // material node Add
 float n84 = 5.0; // material node Constant
 vec3 n85 = (n83*vec3(n84)); // material node Multiply
 float n86 = 1.0; // material node ScalarParameter
 vec3 n87 = pow(max(n85,vec3(0.0)),vec3(n86)); // material node Power
 vec3 n88 = ((n1).rgb*n87); // material node Multiply
 vec3 n89 = (n88*n87); // material node Multiply
 vec4 n90 = sourceColor; // material node VertexColor
 vec2 n91 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 float n92 = 0.5; // material node Constant
 float n93 = 0.5; // material node Constant
 float n94 = 0.0; // material node Constant
 float n95 = knifeSphere(n91,vec2(n92),n93,n94); // material node SphereMask
 vec2 n96 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec4 n97 = vec4(2.0,2.0,0.0,1.0); // material node VectorParameter
 vec2 n98 = ((n97).rgb).rg; // material node ComponentMask
 vec2 n99 = (n96*n98); // material node Multiply
 vec4 n100 = dynamic4; // material node DynamicParameter
 float n101 = (n100).r; // material node ComponentMask
 float n102 = n101; // material node FunctionInput
 float n103 = 1.0; // material node MaterialExpressionStaticBool
 float n104 = n103; // material node FunctionInput
 vec2 n105 = n99; // material node FunctionInput
 vec4 n106 = knifeSample(Sampler3,n105,true,ivec2(0,0)); // material node MaterialExpressionTextureSample
 vec2 n107 = localUV*vec2(1.0,1.0); // material node TextureCoordinate
 vec2 n108 = n107; // material node FunctionInput
 float n109 = 43758.5429688; // material node Constant
 float n110 = n109; // material node FunctionInput
 float n111 = fract(sin(dot(n108,vec2(12.9898,78.233)))*n110); // material node MaterialExpressionCustom
 float n112 = n111; // material node MaterialFunctionCall
 float n113 = (n104>0.5?(n106).r:n112); // material node MaterialExpressionStaticSwitch
 float n114 = clamp(n113,0.0,1.0); // material node ConstantClamp
 float n115 = 0.0; // material node Constant
 float n116 = (n114+n115); // material node Add
 float n117 = 1.0; // material node Constant
 float n118 = 0.0; // material node Constant
 float n119 = (abs((n102-n116))<=1e-05?n118:((n102-n116)>0.0?n117:n118)); // material node MaterialExpressionIf
 float n120 = n119; // material node MaterialFunctionCall
 float n121 = (n95*n120); // material node Multiply
 vec3 n122 = (vec3(n121)+(n82).rgb); // material node Add
 vec3 n123 = clamp(n122,vec3(0.0),vec3(1.0)); // material node ConstantClamp
 vec3 n124 = (vec3((n90).a)*n123); // material node Multiply
 emissive=n89; opacity=(n124).x; distortion=vec2(0.0,0.0);
}

// SOURCE_HELD_CORE_END
// SOURCE_KNIFE_END
void main() {
    float sourceDepthUE = linearDepth(gl_FragCoord.z) / SourceUnit;
    float destinationDepthUE = linearDepth(texture(FxSceneDepth, gl_FragCoord.xy / FxViewport).r) / SourceUnit;
    float separation = destinationDepthUE - sourceDepthUE;
    float facing = abs(dot(viewNormal / max(length(viewNormal), 0.000001),
                           -viewPosition / max(length(viewPosition), 0.000001)));
    vec3 emissive = vec3(0.0);
    float opacity = 0.0;
    float distortion = 0.0;
    vec2 sourceDistortion = vec2(0.0);
    if (MaterialOperation == 0) {
        // MI_Spark_White_SubUV: texture scale 3, Dynamic.PowerValue = 2.
        emissive = pow(max(atlasSample().rgb * 3.0, vec3(0.0)), vec3(2.0)) * sourceColor.rgb;
        opacity = effectBoostDepthAlpha(sourceColor.a, 1.0, 100.0, sourceDepthUE, destinationDepthUE);
    } else if (MaterialOperation == 1) {
        emissive = sourceColor.rgb;
        opacity = atlasSample().r * sourceColor.a;
    } else if (MaterialOperation == 2) {
        // Correct FX_Amazon_Prologue instance, resolved from all 12 imported object paths.
        emissive = atlasSample().rgb * 50.0 * vec3(1.0, 0.2220329046, 0.0830715299) * sourceColor.rgb;
        opacity = effectBoostDepthAlpha(sourceColor.a, 0.9, 100.0, sourceDepthUE, destinationDepthUE);
    } else if (MaterialOperation == 3) {
        vec3 texel = atlasSample().rgb;
        emissive = (30.0 * pow(texel, vec3(4.0)) + texel) * vec3(1.0, 0.15004903, 0.063151538) * 10.0 * sourceColor.rgb;
        // Original MF_Dissolve_Blend, Dynamic.R default 1 candidate; panning input explicitly 0.
        float dissolve = clamp(sourceSample(Sampler1, localUV).r + 0.2, 0.0, 1.0);
        opacity = effectBoostDepthAlpha(facing * facing * dissolve * sourceColor.a, 0.9, 200.0, sourceDepthUE, destinationDepthUE);
    } else if (MaterialOperation == 4) {
        // Outer missing hardness follows original UDK class default 100%, not modern 80%.
        float inner = sphereMask(localUV, 0.49, 0.5);
        float ring = sphereMask(localUV, 0.5, 1.0) - inner * inner;
        emissive = ring * sourceColor.a * sourceColor.rgb;
        opacity = 1.0; // Original Opacity pin is disconnected.
        distortion = ring * sourceColor.a * mix(10.0, 0.0, age);
    } else if (MaterialOperation == 5) {
        // Correct FX_OilRig_WireFence instance. Use_Flicker inherited false is a candidate default.
        float radial = 0.1 / max(length((localUV - vec2(0.5)) * 0.5), 0.000001);
        emissive = vec3(1.0, 0.2353916168, 0.0964127183) * radial * sourceColor.rgb;
        opacity = sourceColor.a * pow(sphereMask(localUV, 0.5, 0.0), 4.0) * clamp(separation * 0.1, 0.0, 1.0);
    } else if (MaterialOperation >= 6 && MaterialOperation <= 8) {
        EffectBoostParams params = MaterialOperation == 8 ? effectBoostCenterParams() : effectBoostOutParams();
        vec4 dynamic4 = MaterialOperation == 6 ? effectBoost_OutDynamic(age) :
                MaterialOperation == 7 ? effectBoost_CoreDynamic(age) : effectBoost_CenterDynamic(age);
        // Only camera tangent Z participates in this source graph; normal dot camera supplies it.
        EffectBoostInputs inputs = EffectBoostInputs(localUV, sourceColor, dynamic4, FxTime, vec3(0.0, 0.0, facing), vec4(0.0));
        emissive = effectBoostEmissive(inputs, params, Sampler0, Sampler1, true, true, sourceDepthUE, destinationDepthUE);
        opacity = effectBoostOpacity(inputs, params, Sampler0, Sampler1, true, true, sourceDepthUE, destinationDepthUE);
        distortion = effectBoostDistortion(inputs, params, Sampler0, Sampler1, true, true, sourceDepthUE, destinationDepthUE);
    }
    // SOURCE_KNIFE_DISPATCH_BEGIN
    if (MaterialOperation == 100) knifeMaterial100(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 101) knifeMaterial101(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 102) knifeMaterial102(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 103) knifeMaterial103(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 104) knifeMaterial104(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 105) knifeMaterial105(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 106) knifeMaterial106(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 107) knifeMaterial107(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 108) knifeMaterial108(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 109) knifeMaterial109(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 110) knifeMaterial110(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 111) knifeMaterial111(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 112) knifeMaterial112(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 113) knifeMaterial113(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 114) knifeMaterial114(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 115) knifeMaterial115(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 116) knifeMaterial116(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 117) knifeMaterial117(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 118) knifeMaterial118(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 119) knifeMaterial119(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 120) knifeMaterial120(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 121) knifeMaterial121(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 122) knifeMaterial122(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 123) knifeMaterial123(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 124) knifeMaterial124(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 125) knifeMaterial125(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 126) knifeMaterial126(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 127) knifeMaterial127(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 128) knifeMaterial128(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 129) knifeMaterial129(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 130) knifeMaterial130(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 131) knifeMaterial131(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    else if (MaterialOperation == 132) knifeMaterial132(sourceDepthUE,destinationDepthUE,facing,emissive,opacity,sourceDistortion);
    // SOURCE_KNIFE_DISPATCH_END
    // Held-only occlusion: project this world-FX fragment back through the captured actual hand
    // matrices and depth range. Never compare raw depths from different projections.
    if (heldSource != 0 && FxHeldOcclusion > 0.0 && MaterialPass == 0) {
        vec4 handClip = FxToHandClip * vec4(viewPosition, 1.0);
        if (handClip.w > 0.0) {
            vec3 ndc = handClip.xyz / handClip.w;
            vec2 handUV = ndc.xy * 0.5 + 0.5;
            if (all(greaterThanEqual(handUV, vec2(0.0))) && all(lessThanEqual(handUV, vec2(1.0)))
                    && ndc.z >= -1.0 && ndc.z <= 1.0) {
                float particleDepth = mix(FxHandDepthRange.x, FxHandDepthRange.y, ndc.z*0.5+0.5);
                float surfaceDepth = texture(FxHeldDepth, handUV).r;
                if (particleDepth > surfaceDepth + 0.000001) opacity *= 1.0-FxHeldOcclusion;
            }
        }
    }
    if (MaterialPass == 1) {
        // Source scalar distortion broadcasts to XY. Host pixel-offset mapping is an explicit port assumption.
        vec2 offset = MaterialOperation >= 100 ? sourceDistortion : vec2(distortion);
        fragColor = vec4(max(offset, vec2(0.0)), max(-offset, vec2(0.0)));
    } else {
        fragColor = vec4(emissive, clamp(opacity, 0.0, 1.0));
    }
}
