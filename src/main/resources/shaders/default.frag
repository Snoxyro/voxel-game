#version 450 core

in vec3 vertColor;
in vec3 fragTexCoord;   // xy = UV in tile units, z = texture array layer index
in vec2 vertLight;     // gamma-mapped light level from vertex shader

// The GL_TEXTURE_2D_ARRAY bound to texture unit 0.
uniform sampler2DArray texArray;

// When false (e.g. for the block highlight wireframe), skip texture sampling and
// output vertColor directly. Avoids a separate shader for untextured geometry.
uniform bool useTexture;

// Lifts the minimum light level — controls the brightness slider in settings.
// 0.0 = default (no floor, caves are dark). Up to ~0.3 at maximum brightness.
// Applied as an additive lift on the gamma-mapped light value, so it never
// affects the relative brightness difference between lit and dark areas.
uniform float u_brightnessFloor;

// Scales overall scene brightness for the day/night cycle.
// 1.0 = full daylight, ~0.15 = deepest night. Applied as a final multiplier
// on the lit fragment colour so it dims the whole scene uniformly without
// affecting the relative difference between lit and shadowed areas.
// Set each frame from ClientWorld.getAmbientFactor() in GameLoop.render().
uniform float u_ambientFactor;

out vec4 fragColor;

void main() {
    float light = 1.0;

    if (useTexture) {
        // Apply ambient darkening ONLY to sky light, then take the highest light source available
        float combinedLight = max(vertLight.x * u_ambientFactor, vertLight.y);
        light = clamp(combinedLight + u_brightnessFloor, 0.0, 1.0);
    }

    if (useTexture) {
        vec4 texSample = texture(texArray, fragTexCoord);
        fragColor = vec4(texSample.rgb * vertColor * light, texSample.a);
    } else {
        fragColor = vec4(vertColor, 1.0);
    }
}