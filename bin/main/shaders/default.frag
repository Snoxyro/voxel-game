#version 450 core

in vec3 vertColor;

// xy = UV in tile units, z = texture array layer index.
// GL_REPEAT wraps the UV coords — greedy-merged quads tile correctly with no extra math.
in vec3 fragTexCoord;

// The GL_TEXTURE_2D_ARRAY bound to texture unit 0.
uniform sampler2DArray texArray;

// When false (e.g. for the block highlight wireframe), skip texture sampling and
// output vertColor directly. Avoids a separate shader for untextured geometry.
uniform bool useTexture;

out vec4 fragColor;

void main() {
    if (useTexture) {
        // Sample the tile at the given UV (GL_REPEAT handles tiling) and layer.
        // Multiply by vertColor to apply per-vertex AO darkening and directional shading.
        vec4 texSample = texture(texArray, fragTexCoord);
        fragColor = vec4(texSample.rgb * vertColor, texSample.a);
    } else {
        fragColor = vec4(vertColor, 1.0);
    }
}
