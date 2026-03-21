#version 450 core

// 'layout location = 0' means this variable receives data from slot 0 of the VAO
// We'll tell the VAO that slot 0 contains our vertex positions
layout (location = 0) in vec3 position;

void main() {
    // gl_Position is a built-in GLSL output — it's the final clip-space position
    // of this vertex. vec4 is a 4-component vector; the 1.0 in the w component
    // is required by the math but not important to understand yet.
    gl_Position = vec4(position, 1.0);
}