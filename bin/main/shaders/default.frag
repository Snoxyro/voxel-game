#version 450 core

in vec3 vertColor;
in vec3 fragTexCoord;   // xy = UV in tile units, z = texture array layer index
in float vertLight;     // gamma-mapped light level from vertex shader

// The GL_TEXTURE_2D_ARRAY bound to texture unit 0.
uniform sampler2DArray texArray;

// When false (e.g. for the block highlight wireframe), skip texture sampling and
// output vertColor directly. Avoids a separate shader for untextured geometry.
uniform bool useTexture;

// Lifts the minimum light level — controls the brightness slider in settings.
// 0.0 = default (no floor, caves are dark). Up to ~0.3 at maximum brightness.
// Applied as an additive lift on the gamma-mapped light value, so it never
// affects the relative brightness difference between lit and dark areas.
// Set from GameSettings when the settings screen saves. Defaults to 0.0 (GLSL
// guarantees uninitialised uniforms are zero).
uniform float u_brightnessFloor;

out vec4 fragColor;

void main() {
    // For untextured geometry (block highlight, player boxes), their VAOs do not
    // set attribute 4, so vertLight arrives as 0.0. Bypassing light for useTexture=false
    // keeps those renderers unaffected by the lighting system.
    float light = useTexture
        ? clamp(vertLight + u_brightnessFloor, 0.0, 1.0)
        : 1.0;

    if (useTexture) {
        // Sample the tile at the given UV (GL_REPEAT handles tiling) and layer.
        // Multiply by vertColor (AO + directional shading) and light level.
        vec4 texSample = texture(texArray, fragTexCoord);
        fragColor = vec4(texSample.rgb * vertColor * light, texSample.a);
    } else {
        fragColor = vec4(vertColor, 1.0);
    }
}