package dev.tggamesyt.xaeroseedmap.client.mixin;

import dev.tggamesyt.xaeroseedmap.client.BiomeMapRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Injecting into Mojang's native Screen guarantees the method signature is correct
@Mixin(Screen.class)
public class ScreenMixin {

    @Inject(method = "extractRenderState", at = @At("HEAD"))
    private void onExtractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        
        // Use a string check so the compiler doesn't demand Xaero's library!
        if (this.getClass().getName().equals("xaero.map.gui.GuiMap")) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            // Cast to our safe accessor
            GuiMapAccessor map = (GuiMapAccessor) this;
            double camX = map.getCameraX();
            double camZ = map.getCameraZ();
            double scale = map.getDestScale();

            if (BiomeMapRenderer.ensureTexture(mc, camX, camZ, 256, 256, scale)) {
                Identifier textureLoc = Identifier.parse("xaeroseedmap:dynamic/biome_map");
                
                int screenW = mc.getWindow().getGuiScaledWidth();
                int screenH = mc.getWindow().getGuiScaledHeight();
                int drawX = (screenW / 2) - 128;
                int drawY = (screenH / 2) - 128;

                graphics.blit(RenderPipelines.GUI_TEXTURED, textureLoc, drawX, drawY, 0, 0, 256, 256, 256, 256);
            }
        }
    }
}