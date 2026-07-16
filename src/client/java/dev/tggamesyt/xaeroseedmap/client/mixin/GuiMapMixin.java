package dev.tggamesyt.xaeroseedmap.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.tggamesyt.xaeroseedmap.client.BiomeMapRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import xaero.map.gui.GuiMap;

@Mixin(value = GuiMap.class, remap = false)
public class GuiMapMixin {

    // Mojang renamed GuiGraphics to DrawContext in 26.1+
    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        double camX = mc.player.getX();
        double camZ = mc.player.getZ();

        if (BiomeMapRenderer.ensureTexture(mc, camX, camZ, 256, 256, 1.0)) {
            // Mojang renamed ResourceLocation to Identifier
            Identifier textureLoc = Identifier.parse("xaeroseedmap:dynamic/biome_map");
            
            graphics.blit(textureLoc, 50, 50, 0, 0, 256, 256, 256, 256);
        }
    }
}