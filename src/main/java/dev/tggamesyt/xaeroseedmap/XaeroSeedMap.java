package dev.tggamesyt.xaeroseedmap;

import net.fabricmc.api.ModInitializer;

public class XaeroSeedMap implements ModInitializer {
    public static final String MOD_ID = "xaeroseedmap";

    @Override
    public void onInitialize() {
        // Automatically load the seed from the config file when the mod initializes
        SeedManager.load();
    }
}
