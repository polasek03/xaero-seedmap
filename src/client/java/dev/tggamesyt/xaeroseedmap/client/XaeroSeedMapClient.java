package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.SeedState;
import dev.tggamesyt.xaeroseedmap.client.command.SetMapSeedCommand;
import dev.tggamesyt.xaeroseedmap.client.command.ShowStructureCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class XaeroSeedMapClient implements ClientModInitializer {

    public static final Identifier BIOME_TEX_ID =
            Identifier.of("xaeroseedmap", "dynamic/biome_map");

    private static final Pattern SEED_PATTERN =
            Pattern.compile("Seed: \\[(-?\\d+)]");

    @Override
    public void onInitializeClient() {
        // Client-side commands
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SetMapSeedCommand.register(dispatcher);
            ShowStructureCommand.register(dispatcher);
        });

        // Auto-detect seed on singleplayer world join; clear stale biome cache
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (client.getServer() != null) {
                try {
                    long seed = client.getServer()
                            .getWorld(client.world.getRegistryKey())
                            .getSeed();
                    SeedState.set(seed);
                    BiomeMapRenderer.clearCache();
                } catch (Exception ignored) {}
            }
        });

        // Parse /seed command output for multiplayer
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            Matcher m = SEED_PATTERN.matcher(text);
            if (m.find()) {
                try {
                    long seed = Long.parseLong(m.group(1));
                    SeedState.set(seed);
                    BiomeMapRenderer.clearCache();
                } catch (NumberFormatException ignored) {}
            }
        });
    }
}
