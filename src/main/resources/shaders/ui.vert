#version 450 core

// Per-vertex inputs: screen-space position, atlas UV, and a tint color.
// The tint color lets drawRect use a white texture multiplied by any RGBA.
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
layout(location = 2) in vec4 aColor;

out vec2 vTexCoord;
out vec4 vColor;

// Orthographic matrix rebuilt each time the window resizes.
// No view matrix — UI has no camera.
uniform mat4 uProjection;

void main() {
    gl_Position = uProjection * vec4(aPosition, 0.0, 1.0);
    vTexCoord   = aTexCoord;
    vColor      = aColor;
}