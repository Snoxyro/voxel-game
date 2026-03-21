#version 450 core

layout (location = 0) in vec3 position;

// Uniforms — set from Java once per frame before drawing.
// 'uniform' means the value is the same for every vertex in a draw call.
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

void main() {
    // Right to left: model moves chunk to world position,
    // view positions world relative to camera,
    // projection applies perspective.
    gl_Position = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
}