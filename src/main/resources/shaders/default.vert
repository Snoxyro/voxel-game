#version 450 core

layout (location = 0) in vec3 position;
layout (location = 1) in vec3 color;

// Uniforms — set from Java once per frame before drawing.
// 'uniform' means the value is the same for every vertex in a draw call.
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

// 'out' variables are interpolated across the triangle surface
// and received as 'in' variables in the fragment shader
out vec3 vertColor;

void main() {
    vertColor = color;
    // Right to left: model moves chunk to world position,
    // view positions world relative to camera,
    // projection applies perspective.
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
}