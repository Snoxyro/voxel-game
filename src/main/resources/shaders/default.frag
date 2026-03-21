#version 450 core

// This is the output color of this pixel
out vec4 fragColor;

void main() {
    // RGBA — this makes every pixel of the triangle solid orange
    // Values are 0.0 to 1.0, not 0 to 255
    fragColor = vec4(1.0, 0.5, 0.0, 1.0);
}