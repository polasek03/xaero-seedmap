package dev.tggamesyt.xaeroseedmap.client;

import com.mojang.blaze3d.platform.NativeImage;

import dev.tggamesyt.xaeroseedmap.SeedState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

public final class BiomeMapRenderer {

    private static DynamicTexture currentTexture = null;
    private static boolean dirty = true;
    private static ResourceKey<Level> lastDimension = null;
    
    private static final Identifier TEXTURE_LOC = Identifier.parse("xaeroseedmap:dynamic/biome_map");
    
    private BiomeMapRenderer() {}

    public static void invalidate() { dirty = true; }
    public static void clearCache() { dirty = true; }

    public static boolean ensureTexture(Minecraft mc, double camX, double camZ, int fboW, int fboH, double fboScale) {
        if (!SeedState.isKnown() || mc.level == null) return false;

        if (!mc.level.dimension().equals(lastDimension)) {
            lastDimension = mc.level.dimension();
            clearCache();
        }

        if (dirty) {
            int texW = 256;
            int texH = 256;
            
            NativeImage img = new NativeImage(texW, texH, false);

            for (int x = 0; x < texW; x++) {
                for (int z = 0; z < texH; z++) {
                    int worldX = (int) (camX + (x - texW / 2) * 16);
                    int worldZ = (int) (camZ + (z - texH / 2) * 16);
                    
                    var biomeHolder = mc.level.getBiome(new BlockPos(worldX, 64, worldZ));
                    int color = biomeHolder.value().getFoliageColor();
                    
                    int r = (color >> 16) & 0xFF;
                    int g = (color >> 8) & 0xFF;
                    int b = color & 0xFF;
                    int argb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    
                    // Mojang officially renamed this from setPixelRGBA in 26.2
                    img.setPixel(x, z, argb); 
                }
            }
            
            if (currentTexture != null) {
                currentTexture.close();
            }
            
            // Fixed the constructor error you were getting!
            currentTexture = new DynamicTexture(() -> "xaeroseedmap_biome", img);
            mc.getTextureManager().register(TEXTURE_LOC, currentTexture);
            
            dirty = false;
        }

        return currentTexture != null;
    }

    public static DynamicTexture getCurrentTexture() {
        return currentTexture;
    }
}