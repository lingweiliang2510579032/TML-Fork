#version 150
uniform sampler2D Sampler0;
in vec2 uv;
out vec4 fragColor;
void main() {
    fragColor=texture(Sampler0,uv);
    if(fragColor.a<.5) discard;
}
