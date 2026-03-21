#version 450 core

out vec4 fragColor;

void main() {
    // White with slight transparency — blends naturally against any background.
    // The alpha value only takes effect when GL_BLEND is enabled by the caller.
    fragColor = vec4(1.0, 1.0, 1.0, 0.85);
}