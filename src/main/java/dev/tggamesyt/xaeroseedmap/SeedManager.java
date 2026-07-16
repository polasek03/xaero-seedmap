package dev.tggamesyt.xaeroseedmap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class SeedManager {
    private static final Path CONFIG_FILE = Paths.get("config", "xaeroseedmap_seed.txt");

    public static void load() {
        if (Files.exists(CONFIG_FILE)) {
            try {
                String content = Files.readString(CONFIG_FILE).trim();
                SeedState.set(Long.parseLong(content));
                System.out.println("[XaeroSeedMap] Loaded saved seed: " + content);
            } catch (Exception e) {
                System.err.println("[XaeroSeedMap] Failed to load map seed.");
            }
        }
    }

    public static void save(long seed) {
        try {
            Files.createDirectories(CONFIG_FILE.getParent());
            Files.writeString(CONFIG_FILE, String.valueOf(seed));
        } catch (Exception e) {
            System.err.println("[XaeroSeedMap] Failed to save map seed.");
        }
    }
}
