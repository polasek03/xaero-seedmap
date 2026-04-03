package dev.tggamesyt.xaeroseedmap.client.mixin;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.tggamesyt.xaeroseedmap.client.BiomeMapRenderer;
import dev.tggamesyt.xaeroseedmap.client.IconOverlayRenderer;
import dev.tggamesyt.xaeroseedmap.client.XaeroSeedMapClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import xaero.map.graphics.ImprovedFramebuffer;
import xaero.map.gui.GuiMap;

import java.util.List;
import java.util.OptionalInt;

/**
 * Intercepts Xaero's render just before it restores the default framebuffer.
 *
 * Single-pass post-process (replace_dark.fsh) handles three things:
 *   InSampler       — Xaero's rendered map FBO
 *   WallpaperSampler — seed-based per-chunk biome colour texture
 *   IconsSampler     — screen-space icon overlay (fixed pixel size, above all map tiles)
 */
@Mixin(GuiMap.class)
public class GuiMapMixin {

    @Shadow(remap = false) private double cameraX;
    @Shadow(remap = false) private double cameraZ;
    @Shadow(remap = false) private double scale;

    private static final RenderPipeline REPLACE_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder()
                    .withLocation(Identifier.of("xaeroseedmap", "pipeline/replace_dark"))
                    .withVertexShader("core/blit_screen")
                    .withFragmentShader(Identifier.of("xaeroseedmap", "core/replace_dark"))
                    .withSampler("InSampler")
                    .withSampler("WallpaperSampler")
                    .withSampler("IconsSampler")
                    .withoutBlend()
                    .withDepthWrite(false)
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withColorWrite(true, true)
                    .withVertexFormat(VertexFormats.POSITION, VertexFormat.DrawMode.QUADS)
                    .build()
    );

    /** Ping-pong FBO — lazily created / resized to match Xaero's FBO. */
    private static SimpleFramebuffer tempFbo = null;

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lxaero/map/graphics/ImprovedFramebuffer;bindDefaultFramebuffer(Lnet/minecraft/client/MinecraftClient;)V"
            )
    )
    private void redirectBindDefaultFramebuffer(ImprovedFramebuffer mapFbo, MinecraftClient mc) {
        if (mc.getTextureManager() != null) {
            double fboScale = (scale >= 1.0) ? Math.max(1.0, Math.floor(scale)) : scale;
            int fboW = mapFbo.textureWidth;
            int fboH = mapFbo.textureHeight;

            // --- Biome wallpaper (async) ---
            boolean biomeReady = BiomeMapRenderer.ensureTexture(mc, cameraX, cameraZ, fboW, fboH, fboScale);

            // --- Icon overlay (fast, render thread) ---
            List<BiomeMapRenderer.StructureResult> structures = BiomeMapRenderer.structureResults;
            IconOverlayRenderer.ensureTexture(mc, cameraX, cameraZ, fboW, fboH, fboScale, structures);

            if (biomeReady) {
                AbstractTexture biomeTex = mc.getTextureManager().getTexture(XaeroSeedMapClient.BIOME_TEX_ID);
                GpuTextureView biomeView = biomeTex != null ? biomeTex.getGlTextureView() : null;

                AbstractTexture iconTex = mc.getTextureManager().getTexture(IconOverlayRenderer.ICON_OVERLAY_TEX_ID);
                GpuTextureView iconView = iconTex != null ? iconTex.getGlTextureView() : null;

                if (biomeView != null && iconView != null) {
                    // Resize / create temp FBO
                    if (tempFbo == null || tempFbo.textureWidth != fboW || tempFbo.textureHeight != fboH) {
                        if (tempFbo != null) tempFbo.delete();
                        tempFbo = new SimpleFramebuffer("xaeroseedmap_temp", fboW, fboH, false);
                    }

                    var seqBuf      = RenderSystem.getSequentialBuffer(VertexFormat.DrawMode.QUADS);
                    var indexBuffer = seqBuf.getIndexBuffer(6);
                    var vertexBuffer = RenderSystem.getQuadVertexBuffer();

                    // Post-process: mapFbo + biome wallpaper + icon overlay → tempFbo
                    var renderPass = RenderSystem.getDevice().createCommandEncoder()
                            .createRenderPass(
                                    () -> "xaeroseedmap_replace_dark",
                                    tempFbo.getColorAttachmentView(),
                                    OptionalInt.empty()
                            );
                    try {
                        renderPass.setPipeline(REPLACE_PIPELINE);
                        RenderSystem.bindDefaultUniforms(renderPass);
                        renderPass.setVertexBuffer(0, vertexBuffer);
                        renderPass.setIndexBuffer(indexBuffer, seqBuf.getIndexType());
                        renderPass.bindSampler("InSampler",        mapFbo.getColorAttachmentView());
                        renderPass.bindSampler("WallpaperSampler", biomeView);
                        renderPass.bindSampler("IconsSampler",     iconView);
                        renderPass.drawIndexed(0, 0, 6, 1);
                    } finally {
                        renderPass.close();
                    }

                    // Copy result back into Xaero's FBO
                    tempFbo.drawBlit(mapFbo.getColorAttachmentView());
                }
            }
        }

        mapFbo.bindDefaultFramebuffer(mc);
    }
}
