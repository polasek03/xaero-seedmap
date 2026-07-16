package dev.tggamesyt.xaeroseedmap.client.mixin;

import dev.tggamesyt.xaeroseedmap.client.BiomeMapRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.gui.GuiMap;

@Mixin(value = GuiMap.class, remap = false)
public abstract class GuiMapMixin {

    // Stolen variables directly from Xaero's Map Screen
    @Shadow private double cameraX;
    @Shadow private double cameraZ;
    @Shadow private double destScale; 

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        // Feed Xaero's camera data into our fast renderer
        if (BiomeMapRenderer.ensureTexture(mc, this.cameraX, this.cameraZ, 256, 256, this.destScale)) {
            Identifier textureLoc = Identifier.parse("xaeroseedmap:dynamic/biome_map");
            
            // Draw the 256x256 overlay perfectly centered over Xaero's map
            int screenW = mc.getWindow().getGuiScaledWidth();
            int screenH = mc.getWindow().getGuiScaledHeight();
            
            int drawX = (screenW / 2) - 128;
            int drawY = (screenH / 2) - 128;

            // Notice the alpha is rendered! (Missing chunks are transparent)
            graphics.blit(RenderPipelines.GUI_TEXTURED, textureLoc, drawX, drawY, 0, 0, 256, 256, 256, 256);
        }
    }
}