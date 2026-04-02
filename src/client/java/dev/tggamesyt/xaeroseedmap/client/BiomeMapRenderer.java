package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.SeedState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.StructureTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeEffects;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.structure.Structure;

import java.util.*;

/**
 * Generates a per-chunk biome preview texture sized to exactly cover the current
 * Xaero FBO view (same world extent, lower pixel resolution: 1 pixel per MC chunk).
 *
 * This texture is used as the "wallpaper" that shows up under unexplored/black areas
 * in Xaero's map. Each pixel = one MC chunk (16×16 blocks), giving a chunkbase-style
 * coloured grid at zoom-in and a biome map at zoom-out.
 *
 * Structure icons (coloured circles) are baked into the same texture.
 */
public final class BiomeMapRenderer {

    /** Pixels per MC chunk (16 blocks). Lower = faster but blockier. */
    private static final int BLOCKS_PER_PIXEL = 16;
    /** Max biome texture dimension (caps CPU work). */
    private static final int MAX_TEX_DIM = 256;
    /** Re-render when camera moves more than this many blocks. */
    private static final double REGEN_THRESHOLD = 8.0;
    /** Re-search structures when camera moves more than this many blocks. */
    private static final double STRUCT_REGEN_THRESHOLD = 128.0;
    /** Structure search radius in chunks. */
    private static final int STRUCT_SEARCH_RADIUS = 24;

    private static NativeImageBackedTexture currentTexture = null;
    private static boolean dirty = true;

    private static double lastCamX = Double.MAX_VALUE;
    private static double lastCamZ = Double.MAX_VALUE;
    private static double lastFboScale = -1;
    private static int lastFboW = -1, lastFboH = -1;

    // Structure cache — populated from the server thread, read on render thread
    private static volatile Map<String, BlockPos> structureCache = Collections.emptyMap();
    private static boolean structureSearchPending = false;
    private static double lastStructCamX = Double.MAX_VALUE;
    private static double lastStructCamZ = Double.MAX_VALUE;

    private BiomeMapRenderer() {}

    /** Mark the current texture as stale so it regenerates next frame. */
    public static void invalidate() {
        dirty = true;
    }

    /**
     * Ensures the biome map texture exists and is up-to-date for the given view.
     * Must be called from the render thread.
     *
     * @return true if a valid texture is now registered at {@link XaeroSeedMapClient#BIOME_TEX_ID}
     */
    public static boolean ensureTexture(MinecraftClient mc,
                                        double camX, double camZ,
                                        int fboW, int fboH,
                                        double fboScale) {
        if (!SeedState.isKnown()) return false;
        if (mc.getServer() == null) return false; // singleplayer only for now

        ServerWorld world = mc.getServer().getWorld(mc.world.getRegistryKey());
        if (world == null) return false;

        boolean camMoved = Math.abs(camX - lastCamX) > REGEN_THRESHOLD
                || Math.abs(camZ - lastCamZ) > REGEN_THRESHOLD;
        boolean scaleChanged = Math.abs(fboScale - lastFboScale) > 0.01;
        boolean sizeChanged = fboW != lastFboW || fboH != lastFboH;

        if (!dirty && !camMoved && !scaleChanged && !sizeChanged && currentTexture != null) {
            // Kick off structure search if needed (async, server thread)
            scheduleStructureSearch(world, camX, camZ);
            return true;
        }

        // World extent covered by the FBO in blocks
        double worldExtentX = fboW / fboScale;
        double worldExtentZ = fboH / fboScale;
        double worldMinX = camX - worldExtentX / 2.0;
        double worldMinZ = camZ - worldExtentZ / 2.0;

        // Biome texture dimensions: 1px per chunk, capped
        int texW = Math.min(MAX_TEX_DIM, Math.max(1, (int) Math.ceil(worldExtentX / BLOCKS_PER_PIXEL)));
        int texH = Math.min(MAX_TEX_DIM, Math.max(1, (int) Math.ceil(worldExtentZ / BLOCKS_PER_PIXEL)));

        // Build the biome image
        NativeImage img = generateBiomeImage(world, texW, texH, worldMinX, worldMinZ, worldExtentX, worldExtentZ);

        // Bake structure icons into the image
        paintStructureIcons(img, texW, texH, worldMinX, worldMinZ, worldExtentX, worldExtentZ);

        // Upload to GPU
        if (currentTexture != null) {
            mc.getTextureManager().destroyTexture(XaeroSeedMapClient.BIOME_TEX_ID);
            currentTexture = null;
        }
        currentTexture = new NativeImageBackedTexture(() -> "xaeroseedmap_biome_map", img);
        mc.getTextureManager().registerTexture(XaeroSeedMapClient.BIOME_TEX_ID, currentTexture);

        lastCamX = camX;
        lastCamZ = camZ;
        lastFboScale = fboScale;
        lastFboW = fboW;
        lastFboH = fboH;
        dirty = false;

        scheduleStructureSearch(world, camX, camZ);
        return true;
    }

    // -------------------------------------------------------------------------
    // Biome image generation
    // -------------------------------------------------------------------------

    private static NativeImage generateBiomeImage(ServerWorld world,
                                                   int texW, int texH,
                                                   double worldMinX, double worldMinZ,
                                                   double worldExtentX, double worldExtentZ) {
        ServerChunkManager chunkManager = (ServerChunkManager) world.getChunkManager();
        BiomeSource biomeSource = chunkManager.getChunkGenerator().getBiomeSource();
        NoiseConfig noiseConfig = chunkManager.getNoiseConfig();
        MultiNoiseUtil.MultiNoiseSampler sampler = noiseConfig.getMultiNoiseSampler();

        NativeImage img = new NativeImage(texW, texH, false);

        for (int ty = 0; ty < texH; ty++) {
            for (int tx = 0; tx < texW; tx++) {
                // Centre-of-pixel world coordinate
                double wx = worldMinX + (tx + 0.5) * (worldExtentX / texW);
                double wz = worldMinZ + (ty + 0.5) * (worldExtentZ / texH);

                int bx = (int) Math.floor(wx);
                int bz = (int) Math.floor(wz);

                int color;
                try {
                    // bx>>2 converts block coord to "quart" coord (÷4) as expected by getBiome
                    RegistryEntry<Biome> biomeEntry = biomeSource.getBiome(bx >> 2, 16, bz >> 2, sampler);
                    color = biomeColor(biomeEntry, bx, bz);
                } catch (Exception e) {
                    color = rgbToAbgr(0x888888); // grey fallback
                }
                img.setColor(tx, ty, color);
            }
        }

        return img;
    }

    private static int biomeColor(RegistryEntry<Biome> entry, int bx, int bz) {
        Biome b = entry.value();
        BiomeEffects eff = b.getEffects();

        if (entry.isIn(BiomeTags.IS_OCEAN) || entry.isIn(BiomeTags.IS_DEEP_OCEAN)) {
            return darken(rgbToAbgr(eff.getWaterColor()), 0.75f);
        }
        if (entry.isIn(BiomeTags.IS_RIVER)) {
            return darken(rgbToAbgr(eff.getWaterColor()), 0.85f);
        }
        if (entry.isIn(BiomeTags.IS_NETHER)) {
            return rgbToAbgr(0x8B2020); // dark red
        }
        if (entry.isIn(BiomeTags.IS_END)) {
            return rgbToAbgr(0x7A6B8A); // muted purple
        }

        // For all other biomes: use grass colour (encodes temperature + rainfall)
        int grassRgb = b.getGrassColorAt((double) bx, (double) bz);
        return darken(rgbToAbgr(grassRgb), 0.80f);
    }

    // -------------------------------------------------------------------------
    // Structure icon rendering (baked into biome NativeImage)
    // -------------------------------------------------------------------------

    private record StructureEntry(String key, TagKey<Structure> tag, int rgb) {}

    private static final List<StructureEntry> STRUCTURES = List.of(
        new StructureEntry("village",          StructureTags.VILLAGE,                    0x00CC00),
        new StructureEntry("stronghold",       StructureTags.EYE_OF_ENDER_LOCATED,       0xFF2222),
        new StructureEntry("monument",         StructureTags.ON_OCEAN_EXPLORER_MAPS,     0x00AAFF),
        new StructureEntry("mansion",          StructureTags.ON_WOODLAND_EXPLORER_MAPS,  0x7700BB),
        new StructureEntry("mineshaft",        StructureTags.MINESHAFT,                  0x8B4513),
        new StructureEntry("shipwreck",        StructureTags.SHIPWRECK,                  0xC8A020),
        new StructureEntry("ruined_portal",    StructureTags.RUINED_PORTAL,              0x8800CC),
        new StructureEntry("ocean_ruin",       StructureTags.OCEAN_RUIN,                 0x228888)
    );

    private static void scheduleStructureSearch(ServerWorld world, double camX, double camZ) {
        if (structureSearchPending) return;
        if (Math.abs(camX - lastStructCamX) < STRUCT_REGEN_THRESHOLD
                && Math.abs(camZ - lastStructCamZ) < STRUCT_REGEN_THRESHOLD) return;

        structureSearchPending = true;
        lastStructCamX = camX;
        lastStructCamZ = camZ;

        BlockPos center = new BlockPos((int) camX, 64, (int) camZ);

        // Run on server thread — ServerWorld.locateStructure must be server-thread-safe
        world.getServer().execute(() -> {
            Map<String, BlockPos> found = new HashMap<>();
            for (StructureEntry entry : STRUCTURES) {
                try {
                    BlockPos pos = world.locateStructure(entry.tag(), center, STRUCT_SEARCH_RADIUS, false);
                    if (pos != null) found.put(entry.key(), pos);
                } catch (Exception ignored) {}
            }
            structureCache = Collections.unmodifiableMap(found);
            structureSearchPending = false;
            // Mark dirty so next render bakes the new icons
            dirty = true;
        });
    }

    private static void paintStructureIcons(NativeImage img,
                                             int texW, int texH,
                                             double worldMinX, double worldMinZ,
                                             double worldExtentX, double worldExtentZ) {
        Map<String, BlockPos> cache = structureCache;
        if (cache.isEmpty()) return;

        for (StructureEntry entry : STRUCTURES) {
            BlockPos pos = cache.get(entry.key());
            if (pos == null) continue;

            int tx = (int) ((pos.getX() - worldMinX) / worldExtentX * texW);
            int ty = (int) ((pos.getZ() - worldMinZ) / worldExtentZ * texH);

            if (tx < 0 || tx >= texW || ty < 0 || ty >= texH) continue;

            int fillColor = rgbToAbgr(entry.rgb());
            int outlineColor = 0xFF000000; // opaque black in ABGR

            // Draw black outline (radius 4), then fill (radius 3)
            drawCircle(img, tx, ty, texW, texH, 4, outlineColor);
            drawCircle(img, tx, ty, texW, texH, 3, fillColor);
        }
    }

    private static void drawCircle(NativeImage img, int cx, int cy,
                                    int texW, int texH, int radius, int colorAbgr) {
        int r2 = radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (dx * dx + dy * dy <= r2) {
                    int px = cx + dx, py = cy + dy;
                    if (px >= 0 && px < texW && py >= 0 && py < texH) {
                        img.setColor(px, py, colorAbgr);
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Colour utilities
    // -------------------------------------------------------------------------

    /**
     * Convert standard packed RGB int (0x00RRGGBB) to NativeImage ABGR int
     * (0xFFBBGGRR) with full opacity.
     */
    private static int rgbToAbgr(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return (0xFF << 24) | (b << 16) | (g << 8) | r;
    }

    /** Multiply brightness by factor (0..1). */
    private static int darken(int abgr, float factor) {
        int r = (int) (((abgr) & 0xFF) * factor);
        int g = (int) (((abgr >> 8) & 0xFF) * factor);
        int b = (int) (((abgr >> 16) & 0xFF) * factor);
        int a = (abgr >> 24) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
