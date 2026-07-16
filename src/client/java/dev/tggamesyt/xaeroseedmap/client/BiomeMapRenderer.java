package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.SeedState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
//import net.minecraft.world.level.biome.BiomeSource;
//import net.minecraft.core.registries.Registries;
import com.mojang.blaze3d.platform.NativeImage;

public final class BiomeMapRenderer {

    private static DynamicTexture currentTexture = null;
    private static boolean dirty = true;
    private static ResourceKey<Level> lastDimension = null;
    
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
                    
                    // Accessing biome via public API
                    var biomeHolder = mc.level.getBiome(new net.minecraft.core.BlockPos(worldX, 64, worldZ));
                    
                    // Get a stable color property from the biome effects
                    int color = biomeHolder.value().getGrassColor(worldX, worldZ);
                    
                    // Use a direct buffer write to avoid missing symbol errors
                    img.setPixel(x, z, color | 0xFF000000);
                }
            }
            
            if (currentTexture != null) currentTexture.close();
            currentTexture = new DynamicTexture(() -> "xaeroseedmap_biome", img);
            currentTexture.upload();

            // This registers your custom map image so GuiGraphics can find it
            Minecraft.getInstance().getTextureManager().register(
                net.minecraft.resources.ResourceLocation.parse("xaeroseedmap:dynamic/biome_map"), 
                currentTexture
            );
            
            dirty = false;
        }

        return currentTexture != null;
    }

    public static DynamicTexture getCurrentTexture() {
        return currentTexture;
    }
}