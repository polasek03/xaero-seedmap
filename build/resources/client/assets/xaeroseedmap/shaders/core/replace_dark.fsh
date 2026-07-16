#version 150

uniform sampler2D InSampler;
uniform sampler2D WallpaperSampler;
uniform sampler2D IconsSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 mapColor = texture(InSampler, texCoord);

    float maxChannel = max(mapColor.r, max(mapColor.g, mapColor.b));
    vec4 base;
    if (maxChannel < 0.1 && mapColor.a > 0.5) {
        base = vec4(texture(WallpaperSampler, texCoord).rgb, 1.0);
    } else {
        base = vec4(mapColor.rgb, 1.0);
    }

    // Composite structure icons on top (always visible, above explored and unexplored areas)
    vec4 icon = texture(IconsSampler, texCoord);
    fragColor = vec4(mix(base.rgb, icon.rgb, icon.a), 1.0);
}
