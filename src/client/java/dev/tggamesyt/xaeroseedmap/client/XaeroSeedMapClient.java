package dev.tggamesyt.xaeroseedmap.client;

import dev.tggamesyt.xaeroseedmap.client.command.SetMapSeedCommand;
import dev.tggamesyt.xaeroseedmap.client.command.ShowStructureCommand;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;

public class XaeroSeedMapClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            SetMapSeedCommand.register(dispatcher);
            ShowStructureCommand.register(dispatcher);
        });
    }
}