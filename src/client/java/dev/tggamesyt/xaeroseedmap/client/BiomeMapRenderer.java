package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.SeedState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import com.mojang.blaze3d.platform.NativeImage;

public final class BiomeMapRenderer {

    private static DynamicTexture currentTexture = null;
    private static boolean dirty = true;
    private static ResourceKey<Level> lastDimension = null;
    private static final Identifier TEXTURE_LOC = Identifier.parse("xaeroseedmap:dynamic/biome_map");
    
    private BiomeMapRenderer() {}

    public static void invalidate() { dirty = true; }
    public static void clearCache() { dirty = true; }

    public static boolean ensureTexture(Minecraft mc, double camX, double camZ, int fboW, int fboH, double scale) {
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
                    // We now factor in Xaero's zoom scale so the map zooms correctly!
                    double blocksPerPixel = 1.0 / scale;
                    int worldX = (int) (camX + (x - texW / 2) * blocksPerPixel);
                    int worldZ = (int) (camZ + (z - texH / 2) * blocksPerPixel);
                    
                    // Call our simulated biome logic
                    int color = getSimulatedBiomeColor(mc, SeedState.get(), worldX, worldZ);
                    
                    int r = (color >> 16) & 0xFF;
                    int g = (color >> 8) & 0xFF;
                    int b = color & 0xFF;
                    int argb = (0xFF << 24) | (r << 16) | (g << 8) | b;
                    
                    img.setPixel(x, z, argb); 
                }
            }
            
            if (currentTexture != null) currentTexture.close();
            
            currentTexture = new DynamicTexture(() -> "xaeroseedmap_biome", img);
            mc.getTextureManager().register(TEXTURE_LOC, currentTexture);
            dirty = false; // Note: You'll want to set dirty=true when dragging the map in the future
        }

        return currentTexture != null;
    }

    /**
     * THE SEED ENGINE:
     * This is where your seed actually does the work.
     */
    private static int getSimulatedBiomeColor(Minecraft mc, long seed, int worldX, int worldZ) {
        // -------------------------------------------------------------
        // WARNING FOR REALMS:
        // Right now, this STILL falls back to asking the client for the chunk 
        // because Vanilla Minecraft does not let you generate 1.21 biomes 
        // mathematically on the client natively without the server's datapacks.
        //
        // To make this work on UNEXPLORED Realms chunks, you MUST import the 
        // "Cubiomes" Java library into your build.gradle, and change this logic to:
        //
        // CubiomesGenerator gen = new CubiomesGenerator(seed);
        // Biome biome = gen.getBiomeAt(worldX, worldZ);
        // return getHexColorForBiome(biome);
        // -------------------------------------------------------------
        
        // Temporary fallback just to render something:
        var biomeHolder = mc.level.getBiome(new BlockPos(worldX, 64, worldZ));
        return biomeHolder.value().getFoliageColor();
    }
}