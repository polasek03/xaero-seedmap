#version 150

uniform sampler2D InSampler;
uniform sampler2D WallpaperSampler;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    vec4 mapColor = texture(InSampler, texCoord);

    // Replace pixels that are very dark: pure black = unexplored, or dark hover-highlight
    // blended over unexplored black. Real terrain always has at least some colour.
    float maxChannel = max(mapColor.r, max(mapColor.g, mapColor.b));
    if (maxChannel < 0.1 && mapColor.a > 0.5) {
        fragColor = vec4(texture(WallpaperSampler, texCoord).rgb, 1.0);
    } else {
        fragColor = vec4(mapColor.rgb, 1.0);
    }
}
