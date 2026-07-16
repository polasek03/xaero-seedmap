package dev.tggamesyt.xaeroseedmap.client.mixin;

import dev.tggamesyt.xaeroseedmap.client.BiomeMapRenderer;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;

// Fixed: Using the actual class instead of a string target
@Mixin(value = GuiMap.class, remap = false)
public class GuiMapMixin {

    // Fixed: The first parameter must be GuiGraphics in modern Minecraft
    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (BiomeMapRenderer.getCurrentTexture() != null) {
            // Define the ResourceLocation where our texture is stored
            ResourceLocation textureLoc = ResourceLocation.parse("xaeroseedmap:dynamic/biome_map");
            
            // Fixed: Use GuiGraphics to draw the texture
            graphics.blit(textureLoc, 50, 50, 0, 0, 256, 256);
        }
    }
}