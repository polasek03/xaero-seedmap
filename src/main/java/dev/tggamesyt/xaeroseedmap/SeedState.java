package dev.tggamesyt.xaeroseedmap;

/**
 * Holds the world seed detected from /seed output or set via /setmapseed.
 * Thread-safe via volatile.
 */
public final class SeedState {
    private static volatile long seed = 0L;
    private static volatile boolean known = false;

    private SeedState() {}

    public static void set(long s) {
        seed = s;
        known = true;
    }

    public static long get() {
        return seed;
    }

    public static boolean isKnown() {
        return known;
    }

    public static void clear() {
        known = false;
    }
}
