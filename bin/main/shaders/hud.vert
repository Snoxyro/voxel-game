#version 450 core

layout (location = 0) in vec2 position;

// No uniforms, no transforms — HUD vertices are already in NDC.
// NDC: (0,0) is screen centre, (-1,-1) bottom-left, (1,1) top-right.
// W=1.0 means no perspective divide — the position is used as-is.
void main() {
    gl_Position = vec4(position, 0.0, 1.0);
}