#version 330

// Motion blur by accumulation: each frame is mixed with the blended frame
// before it, kept in a persistent target, so movement leaves a soft trail.

uniform sampler2D InSampler;
uniform sampler2D PrevSampler;

in vec2 texCoord;

layout(std140) uniform MotionBlurConfig {
    float Strength;
};

out vec4 fragColor;

void main() {
    vec3 current = texture(InSampler, texCoord).rgb;
    vec3 previous = texture(PrevSampler, texCoord).rgb;
    fragColor = vec4(mix(current, previous, Strength), 1.0);
}
