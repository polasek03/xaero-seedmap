package dev.tggamesyt.xaeroseedmap.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import dev.tggamesyt.xaeroseedmap.client.StructureVisibility;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.Text;

import java.util.List;

public final class ShowStructureCommand {

    private static final List<String> KEYS = List.of(
        "swamp_huts", "villages", "trial_chambers", "trail_ruins", "strongholds",
        "shipwrecks", "ruined_portals", "pillager_outposts", "bastion_remnants",
        "ocean_ruins", "ocean_monuments", "nether_complexes", "mineshafts",
        "woodland_mansions", "jungle_temples", "igloos", "dungeons",
        "desert_pyramids", "buried_treasures", "ancient_cities", "end_cities"
    );

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
            ClientCommandManager.literal("showstructureonmap")
                .then(ClientCommandManager.argument("structure", StringArgumentType.word())
                    .suggests((ctx, builder) -> {
                        KEYS.forEach(builder::suggest);
                        return builder.buildFuture();
                    })
                    .then(ClientCommandManager.argument("visible", BoolArgumentType.bool())
                        .executes(ctx -> {
                            String key    = StringArgumentType.getString(ctx, "structure");
                            boolean show  = BoolArgumentType.getBool(ctx, "visible");
                            StructureVisibility.setVisible(key, show);
                            ctx.getSource().sendFeedback(
                                Text.literal(key + " icons: " + (show ? "shown" : "hidden")));
                            return 1;
                        })
                    )
                )
        );
    }
}
