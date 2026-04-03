package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.CubiomesLib;
import dev.tggamesyt.xaeroseedmap.SeedState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.World;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureSet;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.BiomeEffects;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.biome.source.util.MultiNoiseUtil;
import net.minecraft.world.gen.chunk.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.gen.chunk.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacement;
import net.minecraft.world.gen.chunk.placement.StructurePlacementCalculator;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Generates a per-chunk biome wallpaper texture and runs a cubiomes-style
 * algorithmic structure scan to populate {@link #structureResults}.
 *
 * <p>Biome generation is async (background thread). Structure scan runs on
 * the server thread using {@link StructurePlacementCalculator} — no chunk
 * loading required, results are purely seed-deterministic.
 */
public final class BiomeMapRenderer {

    private static final int BLOCKS_PER_PIXEL      = 16;   // 1 texel per MC chunk
    private static final int MAX_TEX_DIM           = 256;
    private static final double REGEN_THRESHOLD    = 8.0;  // blocks before biome regen
    private static final double STRUCT_THRESHOLD   = 64.0; // blocks before structure re-scan
    /** Structure sets whose placement spacing is below this are too dense to show usefully. */
    private static final int MIN_SPACING           = 8;
    /** Cap results per structure type so a huge world extent doesn't flood the overlay. */
    private static final int MAX_PER_STRUCTURE     = 64;

    // -------------------------------------------------------------------------
    // Dimension tracking
    // -------------------------------------------------------------------------

    private static RegistryKey<World> lastDimension = null;

    // -------------------------------------------------------------------------
    // Async biome texture
    // -------------------------------------------------------------------------

    private static NativeImageBackedTexture currentTexture = null;
    private static boolean dirty = true;
    private static double lastCamX = Double.MAX_VALUE, lastCamZ = Double.MAX_VALUE;
    private static double lastFboScale = -1;
    private static int lastFboW = -1, lastFboH = -1;

    private static final ExecutorService BIOME_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "xaeroseedmap-biome-gen");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean generationPending = new AtomicBoolean(false);
    private static volatile NativeImage pendingImage = null;

    // -------------------------------------------------------------------------
    // Per-chunk biome color cache  (survives zoom changes)
    // -------------------------------------------------------------------------

    private static final Map<Long, Integer> CHUNK_COLOR_CACHE = new ConcurrentHashMap<>();

    // -------------------------------------------------------------------------
    // Structure results — produced by cubiomes scan, consumed by IconOverlayRenderer
    // -------------------------------------------------------------------------

    public record StructureResult(String setKey, int spriteIndex, int worldX, int worldZ) {}

    public static volatile List<StructureResult> structureResults = Collections.emptyList();
    private static boolean structureSearchPending = false;
    private static double lastStructCamX = Double.MAX_VALUE, lastStructCamZ = Double.MAX_VALUE;

    // -------------------------------------------------------------------------
    // Sprite index constants  (row in structures.png, 22 px per row, top→bottom)
    // -------------------------------------------------------------------------

    private static final int SPR_WITCH_HUT        =  0;
    private static final int SPR_VILLAGE          =  1;
    private static final int SPR_TRIAL_CHAMBER    =  2;
    private static final int SPR_TRAIL_RUINS      =  3;
    private static final int SPR_STRONGHOLD       =  4;
    private static final int SPR_SHIPWRECK        =  7;
    private static final int SPR_RUINED_PORTAL    =  8;
    private static final int SPR_PILLAGER_OUTPOST = 10;
    private static final int SPR_BASTION          = 11;
    private static final int SPR_OCEAN_RUIN       = 13;
    private static final int SPR_OCEAN_TEMPLE     = 14;
    private static final int SPR_NETHER_FORTRESS  = 15;
    private static final int SPR_MINESHAFT        = 16;
    private static final int SPR_MANSION          = 17;
    private static final int SPR_JUNGLE_TEMPLE    = 19;
    private static final int SPR_IGLOO            = 20;
    private static final int SPR_DUNGEON          = 26;
    private static final int SPR_DESERT_TEMPLE    = 28;
    private static final int SPR_BURIED_TREASURE  = 30;
    private static final int SPR_ANCIENT_CITY     = 32;
    private static final int SPR_END_CITY         = 25;

    /** MC structure-set registry path → sprite index. */
    private static final Map<String, Integer> STRUCTURE_SET_TO_ICON;
    static {
        Map<String, Integer> m = new HashMap<>();
        m.put("swamp_huts",         SPR_WITCH_HUT);
        m.put("villages",           SPR_VILLAGE);
        m.put("trial_chambers",     SPR_TRIAL_CHAMBER);
        m.put("trail_ruins",        SPR_TRAIL_RUINS);
        m.put("strongholds",        SPR_STRONGHOLD);
        m.put("shipwrecks",         SPR_SHIPWRECK);
        m.put("ruined_portals",     SPR_RUINED_PORTAL);
        m.put("pillager_outposts",  SPR_PILLAGER_OUTPOST);
        m.put("bastion_remnants",   SPR_BASTION);
        m.put("ocean_ruins",        SPR_OCEAN_RUIN);
        m.put("ocean_monuments",    SPR_OCEAN_TEMPLE);
        m.put("nether_complexes",   SPR_NETHER_FORTRESS);
        m.put("mineshafts",         SPR_MINESHAFT);
        m.put("woodland_mansions",  SPR_MANSION);
        m.put("jungle_temples",     SPR_JUNGLE_TEMPLE);
        m.put("igloos",             SPR_IGLOO);
        m.put("dungeons",           SPR_DUNGEON);
        m.put("desert_pyramids",    SPR_DESERT_TEMPLE);
        m.put("buried_treasures",   SPR_BURIED_TREASURE);
        m.put("ancient_cities",     SPR_ANCIENT_CITY);
        m.put("end_cities",         SPR_END_CITY);
        STRUCTURE_SET_TO_ICON = Collections.unmodifiableMap(m);
    }

    /**
     * Maps structure-set registry path → cubiomes structure type constant.
     * Ruined portals are handled specially (overworld vs nether type differ).
     * Strongholds are absent — they use ConcentricRings and the MC path handles them.
     */
    private static final Map<String, Integer> STRUCTURE_TO_CUBIOMES;
    static {
        Map<String, Integer> m = new HashMap<>();
        // Overworld
        m.put("swamp_huts",         CubiomesLib.SWAMP_HUT);
        m.put("desert_pyramids",    CubiomesLib.DESERT_PYRAMID);
        m.put("jungle_temples",     CubiomesLib.JUNGLE_TEMPLE);
        m.put("igloos",             CubiomesLib.IGLOO);
        m.put("villages",           CubiomesLib.VILLAGE);
        m.put("ocean_ruins",        CubiomesLib.OCEAN_RUIN);
        m.put("shipwrecks",         CubiomesLib.SHIPWRECK);
        m.put("ocean_monuments",    CubiomesLib.MONUMENT);
        m.put("woodland_mansions",  CubiomesLib.MANSION);
        m.put("pillager_outposts",  CubiomesLib.OUTPOST);
        m.put("ancient_cities",     CubiomesLib.ANCIENT_CITY);
        m.put("buried_treasures",   CubiomesLib.TREASURE);
        m.put("trail_ruins",        CubiomesLib.TRAIL_RUINS);
        m.put("trial_chambers",     CubiomesLib.TRIAL_CHAMBERS);
        // Nether (ruined_portals mapped separately below by dimension)
        m.put("nether_complexes",   CubiomesLib.FORTRESS);
        m.put("bastion_remnants",   CubiomesLib.BASTION);
        // End
        m.put("end_cities",         CubiomesLib.END_CITY);
        STRUCTURE_TO_CUBIOMES = Collections.unmodifiableMap(m);
    }

    /** Tracks when cubiomes nativeInit needs to be re-called. */
    private static long lastCubiomesSeed     = Long.MIN_VALUE;
    private static int  lastCubiomesDim      = Integer.MIN_VALUE;
    private static long lastCubiomesBiomeSeed = Long.MIN_VALUE;
    private static int  lastCubiomesBiomeDim  = Integer.MIN_VALUE;

    // -------------------------------------------------------------------------
    // Cubiomes biome ID → ABGR color table  (index = cubiomes biome ID)
    // -------------------------------------------------------------------------

    private static final int[] CUBIOMES_BIOME_COLORS = buildBiomeColorTable();

    private static int[] buildBiomeColorTable() {
        int[] t = new int[256];
        java.util.Arrays.fill(t, rgbToAbgr(0x808080)); // default grey

        // Oceans
        t[0]  = darken(rgbToAbgr(0x1F65A6), 0.75f); // ocean
        t[10] = darken(rgbToAbgr(0x1A5C9E), 0.70f); // frozen_ocean
        t[24] = darken(rgbToAbgr(0x1F65A6), 0.62f); // deep_ocean
        t[44] = darken(rgbToAbgr(0x2E6EA4), 0.85f); // warm_ocean
        t[45] = darken(rgbToAbgr(0x2561A4), 0.80f); // lukewarm_ocean
        t[46] = darken(rgbToAbgr(0x225CB2), 0.80f); // cold_ocean
        t[47] = darken(rgbToAbgr(0x2E6EA4), 0.68f); // deep_warm_ocean
        t[48] = darken(rgbToAbgr(0x2561A4), 0.63f); // deep_lukewarm_ocean
        t[49] = darken(rgbToAbgr(0x225CB2), 0.63f); // deep_cold_ocean
        t[50] = darken(rgbToAbgr(0x1F65A6), 0.58f); // deep_frozen_ocean

        // Rivers
        t[7]  = darken(rgbToAbgr(0x3F76E4), 0.85f); // river
        t[11] = darken(rgbToAbgr(0x3F76E4), 0.80f); // frozen_river

        // Plains / grassland
        t[1]  = darken(rgbToAbgr(0x91BD59), 0.80f); // plains
        t[129]= darken(rgbToAbgr(0xA5C16C), 0.80f); // sunflower_plains

        // Desert
        t[2]  = darken(rgbToAbgr(0xBDB25F), 0.80f); // desert
        t[17] = darken(rgbToAbgr(0xBDB25F), 0.74f); // desert_hills
        t[130]= darken(rgbToAbgr(0xBDB25F), 0.84f); // desert_lakes

        // Mountains
        t[3]  = darken(rgbToAbgr(0x606B3E), 0.80f); // mountains
        t[20] = darken(rgbToAbgr(0x606B3E), 0.73f); // mountain_edge
        t[34] = darken(rgbToAbgr(0x505C43), 0.80f); // wooded_mountains
        t[131]= darken(rgbToAbgr(0x4E5C3E), 0.80f); // gravelly_mountains
        t[162]= darken(rgbToAbgr(0x424F36), 0.80f); // modified_gravelly_mountains

        // Forest
        t[4]  = darken(rgbToAbgr(0x507A32), 0.80f); // forest
        t[18] = darken(rgbToAbgr(0x395129), 0.80f); // wooded_hills
        t[132]= darken(rgbToAbgr(0x6DAA3B), 0.80f); // flower_forest
        t[27] = darken(rgbToAbgr(0x88BB67), 0.80f); // birch_forest
        t[28] = darken(rgbToAbgr(0x6DA84B), 0.80f); // birch_forest_hills
        t[155]= darken(rgbToAbgr(0x9DCE72), 0.80f); // tall_birch_forest
        t[156]= darken(rgbToAbgr(0x7EBE5A), 0.80f); // tall_birch_hills
        t[29] = darken(rgbToAbgr(0x29571B), 0.80f); // dark_forest
        t[157]= darken(rgbToAbgr(0x22501A), 0.80f); // dark_forest_hills

        // Taiga
        t[5]  = darken(rgbToAbgr(0x31554A), 0.80f); // taiga
        t[19] = darken(rgbToAbgr(0x243F36), 0.80f); // taiga_hills
        t[133]= darken(rgbToAbgr(0x285B4A), 0.80f); // taiga_mountains
        t[30] = darken(rgbToAbgr(0x31554A), 0.85f); // snowy_taiga
        t[31] = darken(rgbToAbgr(0x243F36), 0.85f); // snowy_taiga_hills
        t[158]= darken(rgbToAbgr(0x285B4A), 0.85f); // snowy_taiga_mountains
        t[32] = darken(rgbToAbgr(0x596651), 0.80f); // giant_tree_taiga
        t[33] = darken(rgbToAbgr(0x454F3E), 0.80f); // giant_tree_taiga_hills
        t[160]= darken(rgbToAbgr(0x6E7F65), 0.80f); // giant_spruce_taiga
        t[161]= darken(rgbToAbgr(0x5D6E55), 0.80f); // giant_spruce_taiga_hills

        // Snowy
        t[12] = rgbToAbgr(0xEEEEEE); // snowy_tundra
        t[13] = rgbToAbgr(0xDDDDDD); // snowy_mountains
        t[140]= rgbToAbgr(0xCCCCCC); // ice_spikes

        // Swamp
        t[6]  = darken(rgbToAbgr(0x4C763C), 0.80f); // swamp
        t[134]= darken(rgbToAbgr(0x5B6B4A), 0.80f); // swamp_hills

        // Jungle
        t[21] = darken(rgbToAbgr(0x59C93C), 0.80f); // jungle
        t[22] = darken(rgbToAbgr(0x47A326), 0.80f); // jungle_hills
        t[23] = darken(rgbToAbgr(0x64C73F), 0.80f); // jungle_edge
        t[149]= darken(rgbToAbgr(0x55C93C), 0.80f); // modified_jungle
        t[151]= darken(rgbToAbgr(0x68C73F), 0.80f); // modified_jungle_edge
        t[168]= darken(rgbToAbgr(0x47E01A), 0.80f); // bamboo_jungle
        t[169]= darken(rgbToAbgr(0x3DCE15), 0.80f); // bamboo_jungle_hills

        // Savanna
        t[35] = darken(rgbToAbgr(0xBFB755), 0.80f); // savanna
        t[36] = darken(rgbToAbgr(0xC4C15A), 0.80f); // savanna_plateau
        t[163]= darken(rgbToAbgr(0xE5E04A), 0.80f); // shattered_savanna
        t[164]= darken(rgbToAbgr(0xD9D355), 0.80f); // shattered_savanna_plateau

        // Badlands
        t[37] = rgbToAbgr(0xC77044); // badlands
        t[38] = rgbToAbgr(0xB86B40); // wooded_badlands
        t[39] = rgbToAbgr(0xAC6438); // badlands_plateau
        t[165]= rgbToAbgr(0xC44A3A); // eroded_badlands
        t[166]= rgbToAbgr(0xA86040); // modified_wooded_badlands
        t[167]= rgbToAbgr(0x9C5835); // modified_badlands_plateau

        // Beach / shore
        t[16] = rgbToAbgr(0xC9C98C); // beach
        t[26] = rgbToAbgr(0xC9C98C); // snowy_beach
        t[25] = rgbToAbgr(0x9A9A97); // stone_shore

        // Mushroom
        t[14] = darken(rgbToAbgr(0x2C4205), 0.80f); // mushroom_fields
        t[15] = darken(rgbToAbgr(0x2C4205), 0.78f); // mushroom_field_shore

        // Nether
        t[8]  = rgbToAbgr(0x6B2B2B); // nether_wastes
        t[170]= rgbToAbgr(0x4A6A8A); // soul_sand_valley
        t[171]= rgbToAbgr(0xCC2200); // crimson_forest
        t[172]= rgbToAbgr(0x1A8C8C); // warped_forest
        t[173]= rgbToAbgr(0x505050); // basalt_deltas

        // The End
        t[9]  = rgbToAbgr(0xC8C4A0); // the_end (main island)
        t[40] = rgbToAbgr(0x0D0D1A); // small_end_islands (void)
        t[41] = rgbToAbgr(0xB8B490); // end_midlands
        t[42] = rgbToAbgr(0xD6D2B0); // end_highlands
        t[43] = rgbToAbgr(0x9A9676); // end_barrens

        // 1.17 caves
        t[174]= rgbToAbgr(0x595959); // dripstone_caves
        t[175]= darken(rgbToAbgr(0x4CBF00), 0.80f); // lush_caves

        // 1.18 highlands
        t[177]= darken(rgbToAbgr(0x8DB360), 0.80f); // meadow
        t[178]= darken(rgbToAbgr(0x96AF79), 0.80f); // grove
        t[179]= rgbToAbgr(0xDDDDDD); // snowy_slopes
        t[180]= rgbToAbgr(0xEEEEEE); // jagged_peaks
        t[181]= rgbToAbgr(0xE8F0FF); // frozen_peaks
        t[182]= rgbToAbgr(0x9A9A9A); // stony_peaks

        // 1.19+
        t[183]= rgbToAbgr(0x0D0D1D); // deep_dark
        t[184]= darken(rgbToAbgr(0x567845), 0.80f); // mangrove_swamp

        // 1.20+
        t[185]= darken(rgbToAbgr(0xE8A0B0), 0.85f); // cherry_grove

        // 1.21+
        t[186]= rgbToAbgr(0xD0D0C0); // pale_garden

        return t;
    }

    private static int cubiomeBiomeColor(int id) {
        if (id < 0 || id >= CUBIOMES_BIOME_COLORS.length) return rgbToAbgr(0x808080);
        return CUBIOMES_BIOME_COLORS[id];
    }

    private BiomeMapRenderer() {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    public static void invalidate() { dirty = true; }

    /** Call on world join/leave to discard stale cached biome colours. */
    public static void clearCache() {
        CHUNK_COLOR_CACHE.clear();
        structureResults = Collections.emptyList();
        dirty = true;
        lastStructCamX   = Double.MAX_VALUE;
        lastStructCamZ   = Double.MAX_VALUE;
        lastCubiomesSeed      = Long.MIN_VALUE;
        lastCubiomesDim       = Integer.MIN_VALUE;
        lastCubiomesBiomeSeed = Long.MIN_VALUE;
        lastCubiomesBiomeDim  = Integer.MIN_VALUE;
    }

    /**
     * Must be called from the render thread each frame.
     * Uploads a completed async biome image if ready, kicks off a new generation
     * when the view changes, and schedules a structure scan when the camera moves.
     *
     * @return true if a valid biome texture is currently registered
     */
    public static boolean ensureTexture(MinecraftClient mc,
                                         double camX, double camZ,
                                         int fboW, int fboH,
                                         double fboScale) {
        if (!SeedState.isKnown()) return false;
        if (mc.getServer() == null) return false;

        ServerWorld world = mc.getServer().getWorld(mc.world.getRegistryKey());
        if (world == null) return false;

        // Clear stale cache when the player changes dimension
        RegistryKey<World> dim = world.getRegistryKey();
        if (!dim.equals(lastDimension)) {
            lastDimension = dim;
            clearCache();
        }

        // Upload completed background image if available
        NativeImage ready = pendingImage;
        if (ready != null) {
            pendingImage = null;
            uploadImage(mc, ready);
        }

        // View bounds (always computed so structure scan can use them)
        double worldExtentX = fboW / fboScale;
        double worldExtentZ = fboH / fboScale;
        double worldMinX    = camX - worldExtentX / 2.0;
        double worldMinZ    = camZ - worldExtentZ / 2.0;

        boolean camMoved  = Math.abs(camX - lastCamX) > REGEN_THRESHOLD
                         || Math.abs(camZ - lastCamZ) > REGEN_THRESHOLD;
        boolean scaleChg  = Math.abs(fboScale - lastFboScale) > 0.01;
        boolean sizeChg   = fboW != lastFboW || fboH != lastFboH;

        if ((dirty || camMoved || scaleChg || sizeChg) && !generationPending.get()) {
            ServerChunkManager cm      = (ServerChunkManager) world.getChunkManager();
            BiomeSource biomeSource    = cm.getChunkGenerator().getBiomeSource();
            NoiseConfig nc             = cm.getNoiseConfig();
            MultiNoiseUtil.MultiNoiseSampler sampler = nc.getMultiNoiseSampler();

            int texW = Math.min(MAX_TEX_DIM, Math.max(1, (int) Math.ceil(worldExtentX / BLOCKS_PER_PIXEL)));
            int texH = Math.min(MAX_TEX_DIM, Math.max(1, (int) Math.ceil(worldExtentZ / BLOCKS_PER_PIXEL)));

            final double minX = worldMinX, minZ = worldMinZ, extX = worldExtentX, extZ = worldExtentZ;
            final int tw = texW, th = texH;

            lastCamX = camX;   lastCamZ = camZ;
            lastFboScale = fboScale;
            lastFboW = fboW;   lastFboH = fboH;
            dirty = false;

            final int dimId = getDimensionId(world.getRegistryKey());
            generationPending.set(true);
            BIOME_EXECUTOR.submit(() -> {
                try {
                    pendingImage = generateBiomeImage(biomeSource, sampler, tw, th, minX, minZ, extX, extZ, dimId);
                } finally {
                    generationPending.set(false);
                }
            });
        }

        scheduleStructureSearch(world, camX, camZ, worldMinX, worldMinZ, worldExtentX, worldExtentZ);
        return currentTexture != null;
    }

    // -------------------------------------------------------------------------
    // Biome image generation (runs on BIOME_EXECUTOR)
    // -------------------------------------------------------------------------

    private static void uploadImage(MinecraftClient mc, NativeImage img) {
        if (currentTexture != null) {
            mc.getTextureManager().destroyTexture(XaeroSeedMapClient.BIOME_TEX_ID);
            currentTexture = null;
        }
        currentTexture = new NativeImageBackedTexture(() -> "xaeroseedmap_biome_map", img);
        mc.getTextureManager().registerTexture(XaeroSeedMapClient.BIOME_TEX_ID, currentTexture);
    }

    private static NativeImage generateBiomeImage(BiomeSource biomeSource,
                                                    MultiNoiseUtil.MultiNoiseSampler sampler,
                                                    int texW, int texH,
                                                    double worldMinX, double worldMinZ,
                                                    double worldExtentX, double worldExtentZ,
                                                    int dimId) {
        // Use cubiomes for fast, accurate biome colours when available
        if (CubiomesLib.isAvailable() && SeedState.isKnown()) {
            long seed = SeedState.get();
            if (seed != lastCubiomesBiomeSeed || dimId != lastCubiomesBiomeDim) {
                CubiomesLib.nativeInitBiome(seed, dimId);
                lastCubiomesBiomeSeed = seed;
                lastCubiomesBiomeDim  = dimId;
            }

            int stepBX = Math.max(1, (int) (worldExtentX / texW));
            int stepBZ = Math.max(1, (int) (worldExtentZ / texH));
            int startBX = (int) (worldMinX + stepBX * 0.5);
            int startBZ = (int) (worldMinZ + stepBZ * 0.5);

            int[] biomeIds = CubiomesLib.getBiomesGrid(
                    startBX, startBZ, stepBX, stepBZ, texW, texH, 16 /* biome y=16 → block y=64 */);

            NativeImage img = new NativeImage(texW, texH, false);
            for (int ty = 0; ty < texH; ty++) {
                for (int tx = 0; tx < texW; tx++) {
                    int idx = ty * texW + tx;
                    img.setColor(tx, ty, cubiomeBiomeColor(idx < biomeIds.length ? biomeIds[idx] : -1));
                }
            }
            return img;
        }

        // Fallback: MC BiomeSource
        NativeImage img = new NativeImage(texW, texH, false);
        for (int ty = 0; ty < texH; ty++) {
            for (int tx = 0; tx < texW; tx++) {
                double wx = worldMinX + (tx + 0.5) * (worldExtentX / texW);
                double wz = worldMinZ + (ty + 0.5) * (worldExtentZ / texH);
                int chunkX = (int) Math.floor(wx / 16);
                int chunkZ = (int) Math.floor(wz / 16);
                img.setColor(tx, ty, getChunkColor(biomeSource, sampler, chunkX, chunkZ));
            }
        }
        return img;
    }

    /** Returns cached ABGR colour for the chunk, sampling from BiomeSource on first call. */
    private static int getChunkColor(BiomeSource biomeSource,
                                      MultiNoiseUtil.MultiNoiseSampler sampler,
                                      int chunkX, int chunkZ) {
        long key = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
        return CHUNK_COLOR_CACHE.computeIfAbsent(key, k -> {
            int bx = chunkX * 16 + 8;
            int bz = chunkZ * 16 + 8;
            try {
                RegistryEntry<Biome> entry = biomeSource.getBiome(bx >> 2, 16, bz >> 2, sampler);
                return biomeColor(entry, bx, bz);
            } catch (Exception e) {
                return rgbToAbgr(0x888888);
            }
        });
    }

    private static int biomeColor(RegistryEntry<Biome> entry, int bx, int bz) {
        Biome b   = entry.value();
        BiomeEffects eff = b.getEffects();

        // Nether — specific colour per sub-biome
        if (entry.isIn(BiomeTags.IS_NETHER)) {
            Optional<RegistryKey<Biome>> keyOpt = entry.getKey();
            if (keyOpt.isPresent()) {
                return switch (keyOpt.get().getValue().getPath()) {
                    case "crimson_forest"    -> rgbToAbgr(0xCC2200);
                    case "warped_forest"     -> rgbToAbgr(0x1A8C8C);
                    case "soul_sand_valley"  -> rgbToAbgr(0x4A6A8A);
                    case "basalt_deltas"     -> rgbToAbgr(0x505050);
                    default                  -> rgbToAbgr(0x6B2B2B); // nether_wastes
                };
            }
            return rgbToAbgr(0x6B2B2B);
        }

        // End — differentiate islands from void
        if (entry.isIn(BiomeTags.IS_END)) {
            Optional<RegistryKey<Biome>> keyOpt = entry.getKey();
            if (keyOpt.isPresent()) {
                return switch (keyOpt.get().getValue().getPath()) {
                    case "end_highlands"     -> rgbToAbgr(0xD6D2B0);
                    case "end_midlands"      -> rgbToAbgr(0xB8B490);
                    case "end_barrens"       -> rgbToAbgr(0x9A9676);
                    case "small_end_islands" -> rgbToAbgr(0x0D0D1A);
                    default                  -> rgbToAbgr(0xC8C4A0); // the_end (main island)
                };
            }
            return rgbToAbgr(0xC8C4A0);
        }

        if (entry.isIn(BiomeTags.IS_OCEAN) || entry.isIn(BiomeTags.IS_DEEP_OCEAN)) {
            return darken(rgbToAbgr(eff.getWaterColor()), 0.75f);
        }
        if (entry.isIn(BiomeTags.IS_RIVER)) {
            return darken(rgbToAbgr(eff.getWaterColor()), 0.85f);
        }

        // Overworld: use grass colour (encodes temperature + rainfall)
        int grassRgb = b.getGrassColorAt((double) bx, (double) bz);
        return darken(rgbToAbgr(grassRgb), 0.80f);
    }

    // -------------------------------------------------------------------------
    // Cubiomes-style structure scan (runs on server thread)
    // -------------------------------------------------------------------------

    private static void scheduleStructureSearch(ServerWorld world,
                                                  double camX, double camZ,
                                                  double worldMinX, double worldMinZ,
                                                  double worldExtentX, double worldExtentZ) {
        if (structureSearchPending) return;
        if (Math.abs(camX - lastStructCamX) < STRUCT_THRESHOLD
                && Math.abs(camZ - lastStructCamZ) < STRUCT_THRESHOLD) return;

        structureSearchPending = true;
        lastStructCamX = camX;
        lastStructCamZ = camZ;

        final double minX = worldMinX, minZ = worldMinZ;
        final double extX = worldExtentX, extZ = worldExtentZ;

        world.getServer().execute(() -> {
            try {
                ServerChunkManager cm   = (ServerChunkManager) world.getChunkManager();
                StructurePlacementCalculator calc = cm.getStructurePlacementCalculator();
                calc.tryCalculate();

                long seed  = calc.getStructureSeed();
                int margin = 4;
                int minCX  = (int) Math.floor(minX / 16) - margin;
                int maxCX  = (int) Math.ceil((minX + extX) / 16) + margin;
                int minCZ  = (int) Math.floor(minZ / 16) - margin;
                int maxCZ  = (int) Math.ceil((minZ + extZ) / 16) + margin;
                int minBX  = minCX * 16;
                int maxBX  = maxCX * 16;
                int minBZ  = minCZ * 16;
                int maxBZ  = maxCZ * 16;

                List<StructureResult> found = new ArrayList<>();

                if (CubiomesLib.isAvailable()) {
                    // -------------------------------------------------------
                    // cubiomes path — accurate biome filtering, no false positives
                    // -------------------------------------------------------
                    int dimId = getDimensionId(world.getRegistryKey());

                    // Re-init only when seed or dimension changes
                    if (seed != lastCubiomesSeed || dimId != lastCubiomesDim) {
                        CubiomesLib.nativeInit(seed, dimId);
                        lastCubiomesSeed = seed;
                        lastCubiomesDim  = dimId;
                    }

                    // Iterate only structure sets valid for this dimension (MC already filters)
                    for (RegistryEntry<StructureSet> setEntry : calc.getStructureSets()) {
                        Optional<RegistryKey<StructureSet>> keyOpt = setEntry.getKey();
                        if (keyOpt.isEmpty()) continue;
                        String setKey = keyOpt.get().getValue().getPath();

                        Integer sprite = STRUCTURE_SET_TO_ICON.get(setKey);
                        if (sprite == null) continue;

                        // Strongholds handled separately below
                        if (setEntry.value().placement() instanceof ConcentricRingsStructurePlacement) continue;

                        // Determine cubiomes type — ruined portals differ by dimension
                        Integer cubiomesType;
                        if ("ruined_portals".equals(setKey)) {
                            cubiomesType = (dimId == CubiomesLib.DIM_NETHER)
                                    ? CubiomesLib.RUINED_PORTAL_N
                                    : CubiomesLib.RUINED_PORTAL;
                        } else {
                            cubiomesType = STRUCTURE_TO_CUBIOMES.get(setKey);
                        }
                        if (cubiomesType == null) continue;

                        int[] positions = CubiomesLib.findStructures(
                                cubiomesType, minBX, minBZ, maxBX, maxBZ);

                        for (int i = 0; i + 1 < positions.length; i += 2) {
                            found.add(new StructureResult(setKey, sprite,
                                    positions[i], positions[i + 1]));
                        }
                    }

                    // Strongholds always use the MC concentric-rings path
                    addStrongholds(calc, minCX, maxCX, minCZ, maxCZ, found);

                } else {
                    // -------------------------------------------------------
                    // Fallback: MC StructurePlacementCalculator-based scan
                    // -------------------------------------------------------
                    for (RegistryEntry<StructureSet> setEntry : calc.getStructureSets()) {
                        Optional<RegistryKey<StructureSet>> keyOpt = setEntry.getKey();
                        if (keyOpt.isEmpty()) continue;
                        String  path   = keyOpt.get().getValue().getPath();
                        Integer sprite = STRUCTURE_SET_TO_ICON.get(path);
                        if (sprite == null) continue;

                        StructurePlacement pl = setEntry.value().placement();

                        if (pl instanceof RandomSpreadStructurePlacement spread) {
                            int sp = spread.getSpacing();
                            if (sp < MIN_SPACING) continue;

                            int count = 0;
                            outer:
                            for (int rx = Math.floorDiv(minCX, sp) - 1;
                                 rx <= Math.floorDiv(maxCX, sp) + 1; rx++) {
                                for (int rz = Math.floorDiv(minCZ, sp) - 1;
                                     rz <= Math.floorDiv(maxCZ, sp) + 1; rz++) {
                                    ChunkPos start = spread.getStartChunk(seed, rx, rz);
                                    if (start.x < minCX || start.x > maxCX) continue;
                                    if (start.z < minCZ || start.z > maxCZ) continue;
                                    if (!calc.canGenerate(setEntry, start.x, start.z, 0)) continue;
                                    found.add(new StructureResult(path, sprite,
                                            start.x * 16 + 8, start.z * 16 + 8));
                                    if (++count >= MAX_PER_STRUCTURE) break outer;
                                }
                            }
                        } else if (pl instanceof ConcentricRingsStructurePlacement concentric) {
                            for (ChunkPos pos : calc.getPlacementPositions(concentric)) {
                                if (pos.x >= minCX && pos.x <= maxCX
                                        && pos.z >= minCZ && pos.z <= maxCZ) {
                                    found.add(new StructureResult(path, sprite,
                                            pos.x * 16 + 8, pos.z * 16 + 8));
                                }
                            }
                        }
                    }
                }

                structureResults = Collections.unmodifiableList(found);
                dirty = true;
            } finally {
                structureSearchPending = false;
            }
        });
    }

    private static void addStrongholds(StructurePlacementCalculator calc,
                                        int minCX, int maxCX, int minCZ, int maxCZ,
                                        List<StructureResult> found) {
        Integer sprite = STRUCTURE_SET_TO_ICON.get("strongholds");
        if (sprite == null) return;
        for (RegistryEntry<StructureSet> setEntry : calc.getStructureSets()) {
            Optional<RegistryKey<StructureSet>> keyOpt = setEntry.getKey();
            if (keyOpt.isEmpty()) continue;
            if (!"strongholds".equals(keyOpt.get().getValue().getPath())) continue;
            StructurePlacement pl = setEntry.value().placement();
            if (pl instanceof ConcentricRingsStructurePlacement concentric) {
                for (ChunkPos pos : calc.getPlacementPositions(concentric)) {
                    if (pos.x >= minCX && pos.x <= maxCX
                            && pos.z >= minCZ && pos.z <= maxCZ) {
                        found.add(new StructureResult("strongholds", sprite,
                                pos.x * 16 + 8, pos.z * 16 + 8));
                    }
                }
            }
            break;
        }
    }

    /** Maps Minecraft dimension RegistryKey to cubiomes DIM_* constant. */
    private static int getDimensionId(RegistryKey<World> dim) {
        if (dim.equals(World.NETHER)) return CubiomesLib.DIM_NETHER;
        if (dim.equals(World.END))    return CubiomesLib.DIM_END;
        return CubiomesLib.DIM_OVERWORLD;
    }

    // -------------------------------------------------------------------------
    // Colour utilities
    // -------------------------------------------------------------------------

    /** Packed RGB (0x00RRGGBB) → NativeImage ABGR (0xFFBBGGRR). */
    private static int rgbToAbgr(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8)  & 0xFF;
        int b =  rgb        & 0xFF;
        return (0xFF << 24) | (b << 16) | (g << 8) | r;
    }

    private static int darken(int abgr, float factor) {
        int r = (int) ((abgr        & 0xFF) * factor);
        int g = (int) ((abgr >>  8  & 0xFF) * factor);
        int b = (int) ((abgr >> 16  & 0xFF) * factor);
        int a = (abgr >> 24) & 0xFF;
        return (a << 24) | (b << 16) | (g << 8) | r;
    }
}
