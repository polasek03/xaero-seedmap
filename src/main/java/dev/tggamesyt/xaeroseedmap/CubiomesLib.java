package dev.tggamesyt.xaeroseedmap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Thin Java wrapper around the cubiomes native library.
 *
 * <p>The shared library is extracted from the jar resource
 * {@code /natives/<platform>/cubiomes.dll|so|dylib} into a temp file on first use.
 * If the resource is absent (library not built yet), {@link #isAvailable()} returns
 * {@code false} and callers fall back to the MC-based structure scan.
 */
public final class CubiomesLib {

    // -------------------------------------------------------------------------
    // Structure type constants — mirror cubiomes StructureType enum in finders.h
    // -------------------------------------------------------------------------

    public static final int DESERT_PYRAMID   =  1;
    public static final int JUNGLE_TEMPLE    =  2;
    public static final int SWAMP_HUT        =  3;
    public static final int IGLOO            =  4;
    public static final int VILLAGE          =  5;
    public static final int OCEAN_RUIN       =  6;
    public static final int SHIPWRECK        =  7;
    public static final int MONUMENT         =  8;
    public static final int MANSION          =  9;
    public static final int OUTPOST          = 10;
    public static final int RUINED_PORTAL    = 11;   // overworld
    public static final int RUINED_PORTAL_N  = 12;   // nether
    public static final int ANCIENT_CITY     = 13;
    public static final int TREASURE         = 14;
    public static final int FORTRESS         = 18;
    public static final int BASTION          = 19;
    public static final int END_CITY         = 20;
    public static final int TRAIL_RUINS      = 23;
    public static final int TRIAL_CHAMBERS   = 24;

    // -------------------------------------------------------------------------
    // Dimension constants — match cubiomes DIM_* and MC RegistryKey conventions
    // -------------------------------------------------------------------------

    public static final int DIM_OVERWORLD =  0;
    public static final int DIM_NETHER    = -1;
    public static final int DIM_END       =  1;

    // -------------------------------------------------------------------------
    // Library loading
    // -------------------------------------------------------------------------

    private static volatile boolean loaded    = false;
    private static volatile boolean available = false;

    /** @return true if the native library was successfully loaded. */
    public static boolean isAvailable() {
        if (!loaded) tryLoad();
        return available;
    }

    private static synchronized void tryLoad() {
        if (loaded) return;
        loaded = true;
        try {
            String libName   = getLibName();
            String platform  = getPlatform();
            String resource  = "/natives/" + platform + "/" + libName;

            InputStream is = CubiomesLib.class.getResourceAsStream(resource);
            if (is == null) return; // not bundled — cubiomes not built yet

            String suffix = libName.substring(libName.lastIndexOf('.'));
            Path tmp = Files.createTempFile("cubiomes_", suffix);
            tmp.toFile().deleteOnExit();

            try (is; OutputStream os = Files.newOutputStream(tmp)) {
                is.transferTo(os);
            }
            System.load(tmp.toAbsolutePath().toString());
            available = true;
        } catch (Throwable t) {
            // Silently degrade — caller uses MC-based scan instead
        }
    }

    private static String getPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "macos";
        return "linux";
    }

    private static String getLibName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "cubiomes.dll";
        if (os.contains("mac")) return "libcubiomes.dylib";
        return "libcubiomes.so";
    }

    // -------------------------------------------------------------------------
    // Native methods
    // -------------------------------------------------------------------------

    /**
     * Initialize the cubiomes generator for a given seed and dimension.
     * Must be called (and succeed) before {@link #findStructures}.
     *
     * @param seed      world seed
     * @param dimension {@link #DIM_OVERWORLD}, {@link #DIM_NETHER}, or {@link #DIM_END}
     * @return true on success
     */
    public static native boolean nativeInit(long seed, int dimension);

    /**
     * Initialize the cubiomes biome generator (separate from the structure generator).
     * Must be called from the biome-gen background thread before {@link #getBiomesGrid}.
     */
    public static native boolean nativeInitBiome(long seed, int dimension);

    /**
     * Generate a grid of biome IDs for the given block-coordinate range.
     * Call {@link #nativeInitBiome} first (biome-gen thread only).
     *
     * @param minBX   first sample block X
     * @param minBZ   first sample block Z
     * @param stepBX  block step in X per column
     * @param stepBZ  block step in Z per row
     * @param cols    number of columns
     * @param rows    number of rows
     * @param biomeY  Y in biome-space (16 = block y 64, surface)
     * @return int[] of biome IDs, length = cols * rows, row-major
     */
    public static native int[] getBiomesGrid(int minBX, int minBZ,
                                              int stepBX, int stepBZ,
                                              int cols, int rows,
                                              int biomeY);

    /**
     * Find structure positions whose chunk center lies within the given block range.
     *
     * <p>Internally calls {@code getStructurePos} for each candidate region cell,
     * then filters with {@code isViableStructurePos} (biome check). Capped at 512
     * results per call.
     *
     * @param structType one of the constants defined in this class
     * @param minBX      minimum block X of search area
     * @param minBZ      minimum block Z of search area
     * @param maxBX      maximum block X of search area
     * @param maxBZ      maximum block Z of search area
     * @return int[] of {@code {x1,z1, x2,z2, ...}} block coordinates (chunk center)
     */
    public static native int[] findStructures(int structType,
                                               int minBX, int minBZ,
                                               int maxBX, int maxBZ);

    private CubiomesLib() {}
}
