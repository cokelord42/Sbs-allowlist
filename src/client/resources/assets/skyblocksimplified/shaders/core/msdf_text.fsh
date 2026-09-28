#version 330

#moj_import <minecraft:dynamictransforms.glsl>

uniform sampler2D Sampler0;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

float median(float r, float g, float b) {
    return max(min(r, g), min(max(r, g), b));
}

void main() {
    vec3 msd = texture(Sampler0, texCoord0).rgb;
    float sd = median(msd.r, msd.g, msd.b);
    float screenPxDistance = (sd - 0.5) / max(fwidth(sd), 0.0001);
    float opacity = clamp(screenPxDistance + 0.5, 0.0, 1.0);
    vec4 color = vec4(vertexColor.rgb, vertexColor.a * opacity) * ColorModulator;
    if (color.a < 0.01) {
        discard;
    }
    fragColor = color;
}
