#version 450 core

in vec3 vertColor;

// This is the output color of this pixel
out vec4 fragColor;

void main() {
    // RGBA — this makes every pixel of the triangle solid orange
    // Values are 0.0 to 1.0, not 0 to 255
    fragColor = vec4(vertColor, 1.0);
}