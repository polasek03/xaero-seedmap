package dev.tggamesyt.xaeroseedmap.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import dev.tggamesyt.xaeroseedmap.SeedState;
import dev.tggamesyt.xaeroseedmap.client.BiomeMapRenderer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

public class SetMapSeedCommand {

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("setmapseed")
                .then(ClientCommandManager.argument("seed", LongArgumentType.longArg())
                    .executes(ctx -> {
                        long seed = LongArgumentType.getLong(ctx, "seed");
                        SeedState.set(seed);
                        BiomeMapRenderer.invalidate();
                        ctx.getSource().sendFeedback(Text.literal(
                            "[XaeroSeedMap] Seed set to " + seed + ". Biome map will update."));
                        return 1;
                    }))
        );
    }
}
