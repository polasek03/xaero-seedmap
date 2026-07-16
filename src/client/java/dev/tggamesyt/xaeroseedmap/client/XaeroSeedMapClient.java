package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.SeedState;
import dev.tggamesyt.xaeroseedmap.client.command.SetMapSeedCommand;
import dev.tggamesyt.xaeroseedmap.client.command.ShowStructureCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.resources.Identifier;

public class XaeroSeedMapClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SetMapSeedCommand.register(dispatcher);
            ShowStructureCommand.register(dispatcher);
        });

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            SeedState.clear();
            BiomeMapRenderer.clearCache();
        });
        // No API registration needed here anymore!
    }
}
