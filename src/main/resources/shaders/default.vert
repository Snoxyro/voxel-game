#version 450 core

layout (location = 0) in vec3 position;
layout (location = 1) in vec3 color;

// UV in tile units (0→quad_width, 0→quad_height).
// GL_REPEAT on the texture array handles tiling — no fract() needed in the shader.
layout (location = 2) in vec2 texCoord;

// Texture array layer index (0=grass_top, 1=grass_side, 2=dirt, 3=stone, ...).
// Passed as float because vertex attributes cannot be integers in a float VBO.
layout (location = 3) in float texLayer;

// Gamma-mapped light level in [0.0, 1.0]: pow(0.8, 15 - level).
// Combines skylight and block light — whichever is higher wins at mesh-build time.
// Only set for terrain chunks. For untextured geometry (highlight, player boxes),
// this attribute is unbound and reads as 0.0 — handled in the fragment shader.
layout (location = 4) in float lightLevel;

// Uniforms — set from Java once per frame before drawing.
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

out vec3 vertColor;
out vec3 fragTexCoord;  // xy = UV in tile units, z = layer index
out float vertLight;

void main() {
    vertColor    = color;
    fragTexCoord = vec3(texCoord, texLayer);
    vertLight    = lightLevel;
    gl_Position  = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
}