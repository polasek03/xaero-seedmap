package dev.tggamesyt.xaeroseedmap.client;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.mojang.blaze3d.platform.NativeImage;

import dev.tggamesyt.xaeroseedmap.SeedState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

public final class BiomeMapRenderer {

    private static DynamicTexture currentTexture = null;
    private static volatile boolean dirty = true;
    private static ResourceKey<Level> lastDimension = null;
    private static final Identifier TEXTURE_LOC = Identifier.parse("xaeroseedmap:dynamic/biome_map");
    
    private static double lastCamX = 0;
    private static double lastCamZ = 0;
    private static double lastScale = 0;
    private static int lastTexW = 0;
    private static int lastTexH = 0;

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors() - 2));
    private static final ConcurrentHashMap<Long, int[]> chunkCache = new ConcurrentHashMap<>();
    private static final Set<Long> pendingChunks = ConcurrentHashMap.newKeySet();
    
    // --- VANILLA BIOME GENERATOR VARIABLES ---
    private static Climate.Sampler climateSampler = null;
    private static MultiNoiseBiomeSource biomeSource = null;
    private static long currentLoadedSeed = -1;

    private BiomeMapRenderer() {}

    public static void invalidate() { dirty = true; }
    
    public static void clearCache() { 
        dirty = true; 
        chunkCache.clear();
        pendingChunks.clear();
    }

    public static boolean ensureTexture(Minecraft mc, double camX, double camZ, int texW, int texH, double scale) {
        if (!SeedState.isKnown() || mc.level == null) return false;

        long seed = SeedState.get();

        if (!mc.level.dimension().equals(lastDimension) || currentLoadedSeed != seed || biomeSource == null) {
            lastDimension = mc.level.dimension();
            setupVanillaBiomeGenerator(seed); // We no longer pass 'mc' because we don't care about the server's registry!
            clearCache();
        }

        if (Math.abs(camX - lastCamX) > 0.5 || Math.abs(camZ - lastCamZ) > 0.5 || scale != lastScale || texW != lastTexW || texH != lastTexH) {
            dirty = true;
            lastCamX = camX;
            lastCamZ = camZ;
            lastScale = scale;
            lastTexW = texW;
            lastTexH = texH;
        }

        if (dirty) {
            NativeImage img = new NativeImage(texW, texH, true);
            double blocksPerPixel = 1.0 / scale;

            for (int x = 0; x < texW; x++) {
                for (int z = 0; z < texH; z++) {
                    int worldX = (int) (camX + (x - texW / 2.0) * blocksPerPixel);
                    int worldZ = (int) (camZ + (z - texH / 2.0) * blocksPerPixel);
                    
                    int chunkX = worldX >> 4;
                    int chunkZ = worldZ >> 4;
                    
                    long chunkPos = ChunkPos.pack(chunkX, chunkZ); 
                    int[] colors = chunkCache.get(chunkPos);
                    
                    if (colors != null) {
                        int localX = worldX & 15;
                        int localZ = worldZ & 15;
                        img.setPixel(x, z, colors[localZ * 16 + localX]);
                    } else {
                        queueChunkAsync(chunkX, chunkZ);
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

    /**
     * LOCAL VANILLA GENERATOR:
     * This forces the client to load the default Minecraft world-generation rules from its own files,
     * entirely bypassing the Realms server restrictions.
     */
    private static void setupVanillaBiomeGenerator(long seed) {
        try {
            // Load the default, hardcoded Vanilla datapacks into the client's RAM
            HolderLookup.Provider localRegistries = VanillaRegistries.createLookup();
            
            // Build the 3D Noise Router using the local Vanilla rules and your seed
            RandomState randomState = RandomState.create(localRegistries, NoiseGeneratorSettings.OVERWORLD, seed);
            climateSampler = randomState.sampler();
            
            // Build the Biome Source
            var paramLists = localRegistries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST);
            biomeSource = MultiNoiseBiomeSource.createFromPreset(
                paramLists.getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD)
            );
            
            currentLoadedSeed = seed;
            System.out.println("[XaeroSeedMap] Successfully built Local Vanilla Generator for seed: " + seed);
        } catch (Exception e) {
            System.err.println("[XaeroSeedMap] Failed to build Local Vanilla Generator: " + e.getMessage());
        }
    }

    private static void queueChunkAsync(int chunkX, int chunkZ) {
        long pos = ChunkPos.pack(chunkX, chunkZ); 
        if (pendingChunks.contains(pos)) return;
        
        if (chunkCache.size() > 10000) chunkCache.clear();
        pendingChunks.add(pos);
        
        EXECUTOR.submit(() -> {
            int[] chunkColors = new int[256];
            
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int worldX = (chunkX * 16) + x;
                    int worldZ = (chunkZ * 16) + z;
                    
                    // We only use the REAL biome color now!
                    chunkColors[z * 16 + x] = getRealBiomeColor(worldX, worldZ);
                }
            }
            
            chunkCache.put(pos, chunkColors);
            pendingChunks.remove(pos);
            dirty = true; 
        });
    }

    private static int getRealBiomeColor(int worldX, int worldZ) {
        if (biomeSource == null || climateSampler == null) return 0x00000000; 
        
        // Minecraft's 3D noise calculates biomes in 4x4x4 block chunks (QuartPos)
        int quartX = worldX >> 2;
        int quartY = 64 >> 2; // Surface level Y
        int quartZ = worldZ >> 2;
        
        Holder<Biome> biomeHolder = biomeSource.getNoiseBiome(quartX, quartY, quartZ, climateSampler);
        Biome biome = biomeHolder.value();
        
        int color = biome.getFoliageColor();
        String name = "";
        if (biomeHolder.unwrapKey().isPresent()) {
            name = biomeHolder.unwrapKey().get().toString();
        }
        
        // Apply map-accurate colors based on the biome type string
        if (name.contains("ocean") || name.contains("river") || name.contains("water") || name.contains("swamp")) {
            color = biome.getWaterColor();
        } else if (name.contains("desert") || name.contains("badlands") || name.contains("beach")) {
            color = 0xebd592; 
        } else if (name.contains("ice") || name.contains("snow") || name.contains("frozen")) {
            color = 0xffffff; 
        } else if (color == 0 || name.contains("plains")) {
            color = biome.getGrassColor(worldX, worldZ);
        }
        
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        return (0xFF << 24) | (r << 16) | (g << 8) | b;
    }
}