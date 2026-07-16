package dev.tggamesyt.xaeroseedmap.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;

import dev.tggamesyt.xaeroseedmap.SeedState;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;

/* import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal; */

public class SetMapSeedCommand {
    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            literal("setmapseed")
                .then(argument("seed", LongArgumentType.longArg())
                    .executes(ctx -> {
                        long seed = LongArgumentType.getLong(ctx, "seed");
                        SeedState.set(seed);
                        dev.tggamesyt.xaeroseedmap.client.BiomeMapRenderer.clearCache();
                        ctx.getSource().sendFeedback(Component.literal("XaeroSeedMap seed set to: " + seed));
                        return 1;
                    })
                )
        );
    }
}