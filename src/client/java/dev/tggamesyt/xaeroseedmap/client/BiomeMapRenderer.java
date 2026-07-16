package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.SeedState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import com.mojang.blaze3d.platform.NativeImage;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BiomeMapRenderer {

    private static DynamicTexture currentTexture = null;
    private static volatile boolean dirty = true;
    private static ResourceKey<Level> lastDimension = null;
    private static final Identifier TEXTURE_LOC = Identifier.parse("xaeroseedmap:dynamic/biome_map");
    
    // Camera Tracking
    private static double lastCamX = 0;
    private static double lastCamZ = 0;
    private static double lastScale = 0;

    // Multithreading & RAM Caching (Limits to available CPU cores)
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
    private static final ConcurrentHashMap<Long, int[]> chunkCache = new ConcurrentHashMap<>();
    private static final Set<Long> pendingChunks = ConcurrentHashMap.newKeySet();
    
    private BiomeMapRenderer() {}

    public static void invalidate() { dirty = true; }
    
    public static void clearCache() { 
        dirty = true; 
        chunkCache.clear();
        pendingChunks.clear();
    }

    public static boolean ensureTexture(Minecraft mc, double camX, double camZ, int texW, int texH, double scale) {
        if (!SeedState.isKnown() || mc.level == null) return false;

        // Reset if we change dimensions
        if (!mc.level.dimension().equals(lastDimension)) {
            lastDimension = mc.level.dimension();
            clearCache();
        }

        // Check if Xaero's map was dragged or zoomed
        if (Math.abs(camX - lastCamX) > 0.5 || Math.abs(camZ - lastCamZ) > 0.5 || scale != lastScale) {
            dirty = true;
            lastCamX = camX;
            lastCamZ = camZ;
            lastScale = scale;
        }

        if (dirty) {
            NativeImage img = new NativeImage(texW, texH, true);
            double blocksPerPixel = 1.0 / scale;

            // Instantly build the image from RAM Cache
            for (int x = 0; x < texW; x++) {
                for (int z = 0; z < texH; z++) {
                    int worldX = (int) (camX + (x - texW / 2) * blocksPerPixel);
                    int worldZ = (int) (camZ + (z - texH / 2) * blocksPerPixel);
                    
                    int chunkX = worldX >> 4;
                    int chunkZ = worldZ >> 4;
                    long chunkPos = ChunkPos.pack(chunkX, chunkZ);
                    
                    int[] colors = chunkCache.get(chunkPos);
                    
                    if (colors != null) {
                        // Read from RAM instantly
                        int localX = worldX & 15;
                        int localZ = worldZ & 15;
                        img.setPixel(x, z, colors[localZ * 16 + localX]);
                    } else {
                        // Chunk missing! Queue it for the background thread and leave pixel transparent
                        queueChunkAsync(mc, chunkX, chunkZ);
                    }
                }
            }
            
            if (currentTexture != null) currentTexture.close();
            currentTexture = new DynamicTexture(() -> "xaeroseedmap_biome", img);
            mc.getTextureManager().register(TEXTURE_LOC, currentTexture);
            
            dirty = false;
        }

        return currentTexture != null;
    }

    private static void queueChunkAsync(Minecraft mc, int chunkX, int chunkZ) {
        long pos = ChunkPos.pack(chunkX, chunkZ);
        if (pendingChunks.contains(pos)) return;
        
        // Prevent OutOfMemory errors if you fly around too fast
        if (chunkCache.size() > 5000) chunkCache.clear();

        pendingChunks.add(pos);
        
        EXECUTOR.submit(() -> {
            int[] chunkColors = new int[256];
            long seed = SeedState.get();
            
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = (chunkX * 16) + x;
                    int worldZ = (chunkZ * 16) + z;
                    
                    // THIS IS WHERE YOU CALL CUBIOMES LATER.
                    // For now, it safely simulates a random biome-like pattern purely mathematically 
                    // so it doesn't crash the Minecraft client by accessing chunks asynchronously.
                    chunkColors[z * 16 + x] = mockBiomeMath(seed, worldX, worldZ);
                }
            }
            
            chunkCache.put(pos, chunkColors);
            pendingChunks.remove(pos);
            dirty = true; // Tell the UI to redraw now that new data arrived
        });
    }

    // A temporary standalone math function so you can test panning, zooming, and threading
    // perfectly before you import a real seed library!
    private static int mockBiomeMath(long seed, int worldX, int worldZ) {
        long hash = seed ^ (worldX / 64) ^ (worldZ / 64);
        hash = (hash * 0x5DEECE66DL + 0xBL) & ((1L << 48) - 1);
        int val = (int)(hash >> 16) & 3;
        
        int r=0, g=0, b=0;
        if (val == 0) { r = 34; g = 139; b = 34; } // Forest Green
        else if (val == 1) { r = 237; g = 201; b = 175; } // Desert Sand
        else if (val == 2) { r = 100; g = 149; b = 237; } // Ocean Blue
        else { r = 139; g = 137; b = 137; } // Mountain Gray
        
        return (0xFF << 24) | (r << 16) | (g << 8) | b; 
    }
}