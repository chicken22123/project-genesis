#version 330

// A cinematic grade: exposure, contrast around mid-grey, saturation, and a
// soft vignette that darkens the corners.

uniform sampler2D InSampler;

in vec2 texCoord;

layout(std140) uniform ColorGradeConfig {
    float Exposure;
    float Contrast;
    float Saturation;
    float Vignette;
};

out vec4 fragColor;

void main() {
    vec3 color = texture(InSampler, texCoord).rgb * Exposure;
    color = (color - 0.5) * Contrast + 0.5;

    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, Saturation);

    vec2 fromCentre = texCoord - 0.5;
    float shade = 1.0 - Vignette * dot(fromCentre, fromCentre) * 2.0;
    color *= clamp(shade, 0.0, 1.0);

    fragColor = vec4(clamp(color, 0.0, 1.0), 1.0);
}
