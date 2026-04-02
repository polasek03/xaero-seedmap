package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.SeedState;
import dev.tggamesyt.xaeroseedmap.client.command.SetMapSeedCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XaeroSeedMapClient implements ClientModInitializer {

    /** Identifier for the dynamically generated biome map texture. */
    public static final Identifier BIOME_TEX_ID =
            Identifier.of("xaeroseedmap", "dynamic/biome_map");

    /** Pattern matching the /seed command output: "Seed: [1234567890]" */
    private static final Pattern SEED_PATTERN =
            Pattern.compile("Seed: \\[(-?\\d+)]");

    @Override
    public void onInitializeClient() {
        // /setmapseed <seed> — manual seed input for multiplayer
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                SetMapSeedCommand.register(dispatcher));

        // Auto-detect seed when joining a singleplayer world
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.getServer() != null) {
                // Integrated server — we have direct access to the seed
                try {
                    long seed = client.getServer()
                            .getWorld(client.world.getRegistryKey())
                            .getSeed();
                    SeedState.set(seed);
                    BiomeMapRenderer.invalidate();
                } catch (Exception ignored) {}
            }
        });

        // Intercept /seed command chat output to capture seed in multiplayer
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            Matcher m = SEED_PATTERN.matcher(text);
            if (m.find()) {
                try {
                    long seed = Long.parseLong(m.group(1));
                    SeedState.set(seed);
                    BiomeMapRenderer.invalidate();
                } catch (NumberFormatException ignored) {}
            }
        });
    }
}
