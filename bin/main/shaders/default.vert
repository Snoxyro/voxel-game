#version 450 core

layout (location = 0) in vec3 position;
layout (location = 1) in vec3 color;

// UV in tile units (0→quad_width, 0→quad_height).
// GL_REPEAT on the texture array handles tiling — no fract() needed in the shader.
layout (location = 2) in vec2 texCoord;

// Texture array layer index (0=grass_top, 1=grass_side, 2=dirt, 3=stone, ...).
// Passed as float because vertex attributes cannot be integers in a float VBO.
layout (location = 3) in float texLayer;

// Uniforms — set from Java once per frame before drawing.
uniform mat4 projectionMatrix;
uniform mat4 viewMatrix;
uniform mat4 modelMatrix;

out vec3 vertColor;
out vec3 fragTexCoord;  // xy = UV in tile units, z = layer index

void main() {
    vertColor    = color;
    fragTexCoord = vec3(texCoord, texLayer);
    gl_Position  = projectionMatrix * viewMatrix * modelMatrix * vec4(position, 1.0);
}
