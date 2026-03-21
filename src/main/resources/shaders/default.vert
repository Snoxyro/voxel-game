#version 450 core

layout (location = 0) in vec3 position;

// Uniforms — set from Java once per frame before drawing.
// 'uniform' means the value is the same for every vertex in a draw call.
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;

void main() {
    // Apply view (camera position/orientation), then projection (perspective + aspect ratio).
    // Order matters: transformations are applied right-to-left.
    gl_Position = projectionMatrix * viewMatrix * vec4(position, 1.0);
}