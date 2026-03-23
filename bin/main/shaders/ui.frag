#version 450 core

in vec2 vTexCoord;
in vec4 vColor;

out vec4 fragColor;

uniform sampler2D uTexture;

void main() {
    // Multiply sampled texture color by the per-vertex tint.
    // For solid rects: uTexture is a 1x1 white pixel, so result = vColor.
    // For text: uTexture is the glyph atlas, vColor tints the glyph.
    fragColor = texture(uTexture, vTexCoord) * vColor;
}