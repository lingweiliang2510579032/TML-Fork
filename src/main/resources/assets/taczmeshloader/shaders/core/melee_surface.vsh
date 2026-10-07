#version 150
in vec3 Position;
in vec3 Normal;
in vec2 UV0;
uniform mat4 ModelViewMat;
uniform mat4 ProjMat;
out vec2 uv;
out vec3 positionView;
out vec3 normalView;
void main() {
    vec4 p=ModelViewMat*vec4(Position,1.0);
    gl_Position=ProjMat*p;
    positionView=p.xyz;
    normalView=transpose(inverse(mat3(ModelViewMat)))*Normal;
    uv=UV0;
}
