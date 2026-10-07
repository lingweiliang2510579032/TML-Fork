#version 150
uniform sampler2D Sampler0;
uniform sampler2D Sampler1;
uniform sampler2D Sampler2;
uniform sampler2D Sampler3;
uniform vec3 ColorB;
uniform vec3 ColorR;
uniform vec3 ColorFlash;
uniform vec4 Emission; // source global, interval, pulse, seconds
uniform vec4 Pbr; // source roughness multiply/power/metallic, adaptation exposure
uniform float Specular;
uniform int ToneMap;
uniform vec2 Lighting; // bounded ambient and directional radiance
in vec2 uv;
in vec3 positionView;
in vec3 normalView;
out vec4 fragColor;
const float PI=3.14159265359;
vec3 srgbToLinear(vec3 c) { return mix(c/12.92,pow((c+.055)/1.055,vec3(2.4)),greaterThan(c,vec3(.04045))); }
vec3 linearToSrgb(vec3 c) { return mix(c*12.92,1.055*pow(max(c,vec3(0)),vec3(1.0/2.4))-.055,greaterThan(c,vec3(.0031308))); }
void main() {
    // Original texture AddressX is mirrored, AddressY wraps. Preserve shared geometry UVs.
    vec2 sampleUv=vec2(1.0-abs(mod(uv.x,2.0)-1.0),fract(uv.y));
    vec4 d=texture(Sampler0,sampleUv);
    if(d.a<.1) discard;
    vec4 s=texture(Sampler2,sampleUv);
    vec4 encoded=texture(Sampler1,sampleUv);
    vec2 xy=2.0*vec2(encoded.a,encoded.g)-1.0;
    if(mod(floor(uv.x),2.0)>0.5) xy.x=-xy.x;
    vec3 mapN=vec3(xy,sqrt(max(0.0,1.0-dot(xy,xy))));
    vec3 n=normalize(normalView);
    vec3 dp1=dFdx(positionView),dp2=dFdy(positionView);
    vec2 dt1=dFdx(uv),dt2=dFdy(uv);
    // UV V is inverted by the existing source-to-Bedrock loader; derive orientation from actual UVs.
    vec3 q1=cross(dp2,n),q2=cross(n,dp1);
    vec3 tangent=q1*dt1.x+q2*dt2.x, bitangent=q1*dt1.y+q2*dt2.y;
    float tangentScale=inversesqrt(max(max(dot(tangent,tangent),dot(bitangent,bitangent)),1e-12));
    n=normalize(mat3(tangent*tangentScale,bitangent*tangentScale,n)*mapN);
    if(!gl_FrontFacing) n=-n;
    vec3 v=normalize(-positionView);
    vec3 l=normalize(vec3(-.35,.65,.68)); // bounded camera-space studio key, explicit MC adapter
    vec3 h=normalize(l+v);
    float ndl=max(dot(n,l),0.0),ndv=max(dot(n,v),.001),ndh=max(dot(n,h),0.0),vdh=max(dot(v,h),0.0);
    float rough=clamp(pow(max(s.g*Pbr.x,0.0),Pbr.y),.08,1.0);
    float metal=clamp(s.r*Pbr.z,0.0,1.0);
    vec3 base=srgbToLinear(d.rgb)*s.a; // source AO multiplies linear diffuse exactly once
    vec3 f0=mix(vec3(.08*Specular),base,metal);
    vec3 f=f0+(1.0-f0)*pow(1.0-vdh,5.0);
    float a=rough*rough,a2=a*a;
    float denom=ndh*ndh*(a2-1.0)+1.0;
    float distribution=a2/(PI*denom*denom);
    float k=(rough+1.0)*(rough+1.0)/8.0;
    float geom=(ndl/(ndl*(1.0-k)+k))*(ndv/(ndv*(1.0-k)+k));
    vec3 spec=distribution*geom*f/max(4.0*ndl*ndv,.001);
    vec3 diffuse=(1.0-f)*(1.0-metal)*base/PI;
    vec3 reflected=(diffuse+spec)*ndl*Lighting.y+(base*(1.0-metal)+f0)*Lighting.x;
    vec3 em=srgbToLinear(texture(Sampler3,sampleUv).rgb); // unserialized sRGB=true compatible class default
    // Source Pulse==0 is explicitly 1, including sin==0; color parameter alpha is not multiplied.
    float flash=Emission.z==0.0?1.0:clamp(pow(max(sin(2.0*PI*Emission.w*Emission.y),0.0),Emission.z),0.0,1.0);
    vec3 emission=Emission.x*(em.b*ColorB+em.r*ColorR+em.g*ColorFlash*flash);
    vec3 hdr=max((reflected+emission)*Pbr.w,vec3(0));
    // Only this weapon's surface is tone mapped. Max-channel compression retains source RGB hue.
    vec3 mapped=hdr/(1.0+max(max(hdr.r,hdr.g),hdr.b));
    if(ToneMap==1) {
        // Explicit blue-skin adapter only: retain nonemissive PBR and other skins verbatim.
        // Source EM.B supplies a continuous highlight mask; source RGB/global stay unchanged.
        float bladeHighlight=smoothstep(0.03,0.35,em.b);
        // Narkowicz 2016 per-channel filmic fit (CC0/MIT), not a claim of source exposure.
        vec3 shoulder=clamp((hdr*(2.51*hdr+0.03))/(hdr*(2.43*hdr+0.59)+0.14),0.0,1.0);
        mapped=mix(mapped,shoulder,bladeHighlight);
    }
    fragColor=vec4(linearToSrgb(mapped),1.0);
}
