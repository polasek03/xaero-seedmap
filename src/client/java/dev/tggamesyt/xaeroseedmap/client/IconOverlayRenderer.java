package dev.tggamesyt.xaeroseedmap.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * Generates a screen-space icon overlay texture at FBO resolution.
 * Icons are drawn at a fixed pixel size regardless of zoom, and are
 * composited above the map (both explored and unexplored areas).
 */
public final class IconOverlayRenderer {

    public static final Identifier ICON_OVERLAY_TEX_ID =
            Identifier.of("xaeroseedmap", "dynamic/icon_overlay");

    /** Size each icon is rendered at in screen pixels (scaled from 22×22 source). */
    private static final int ICON_SIZE = 16;

    private static NativeImageBackedTexture currentTexture = null;
    private static int lastFboW = -1, lastFboH = -1;
    private static double lastCamX = Double.MAX_VALUE, lastCamZ = Double.MAX_VALUE;
    private static double lastFboScale = -1;
    private static List<BiomeMapRenderer.StructureResult> lastStructures = Collections.emptyList();

    private static volatile NativeImage spriteSheet = null;
    private static volatile boolean spriteSheetLoaded = false;

    private IconOverlayRenderer() {}

    /**
     * Ensures the icon overlay texture is up-to-date for the given view.
     * Must be called from the render thread; runs synchronously (fast).
     */
    public static boolean ensureTexture(MinecraftClient mc,
                                         double camX, double camZ,
                                         int fboW, int fboH,
                                         double fboScale,
                                         List<BiomeMapRenderer.StructureResult> structures) {
        boolean changed =
                Math.abs(camX - lastCamX) > 8.0 ||
                Math.abs(camZ - lastCamZ) > 8.0 ||
                Math.abs(fboScale - lastFboScale) > 0.01 ||
                fboW != lastFboW || fboH != lastFboH ||
                structures != lastStructures;   // reference change → cache was refreshed

        if (!changed && currentTexture != null) return true;

        lastCamX = camX;   lastCamZ = camZ;
        lastFboScale = fboScale;
        lastFboW = fboW;   lastFboH = fboH;
        lastStructures = structures;

        NativeImage img = buildOverlay(camX, camZ, fboW, fboH, fboScale, structures);

        if (currentTexture != null) {
            mc.getTextureManager().destroyTexture(ICON_OVERLAY_TEX_ID);
            currentTexture = null;
        }
        currentTexture = new NativeImageBackedTexture(() -> "xaeroseedmap_icon_overlay", img);
        mc.getTextureManager().registerTexture(ICON_OVERLAY_TEX_ID, currentTexture);
        return true;
    }

    private static NativeImage buildOverlay(double camX, double camZ,
                                             int fboW, int fboH, double fboScale,
                                             List<BiomeMapRenderer.StructureResult> structures) {
        // Zeroed NativeImage → all pixels have alpha=0 (transparent)
        NativeImage img = new NativeImage(fboW, fboH, false);
        if (structures.isEmpty()) return img;

        NativeImage sheet = getSpriteSheet();
        if (sheet == null) return img;

        double worldExtentX = fboW / fboScale;
        double worldExtentZ = fboH / fboScale;

        for (BiomeMapRenderer.StructureResult s : structures) {
            if (!StructureVisibility.isVisible(s.setKey())) continue;

            // World → screen position (centre of FBO = camera position)
            int sx = (int) Math.round((s.worldX() - camX) / worldExtentX * fboW + fboW * 0.5);
            int sz = (int) Math.round((s.worldZ() - camZ) / worldExtentZ * fboH + fboH * 0.5);

            drawScaledSprite(img, sheet, sx, sz, fboW, fboH, s.spriteIndex());
        }
        return img;
    }

    /** Scale the 22×22 sprite at {@code spriteIndex} down to ICON_SIZE×ICON_SIZE and blit. */
    private static void drawScaledSprite(NativeImage target, NativeImage sheet,
                                          int cx, int cy, int targetW, int targetH,
                                          int spriteIndex) {
        int half   = ICON_SIZE / 2;
        int sheetY = spriteIndex * 22;

        for (int dy = 0; dy < ICON_SIZE; dy++) {
            for (int dx = 0; dx < ICON_SIZE; dx++) {
                int px = cx - half + dx;
                int py = cy - half + dy;
                if (px < 0 || px >= targetW || py < 0 || py >= targetH) continue;

                // Nearest-neighbour scale 16 → 22
                int sx = dx * 22 / ICON_SIZE;
                int sy = sheetY + dy * 22 / ICON_SIZE;
                int argb = sheet.getColorArgb(sx, sy);
                if ((argb >>> 24) == 0) continue;   // skip transparent source pixels
                target.setColorArgb(px, py, argb);
            }
        }
    }

    private static NativeImage getSpriteSheet() {
        if (!spriteSheetLoaded) {
            synchronized (IconOverlayRenderer.class) {
                if (!spriteSheetLoaded) {
                    spriteSheetLoaded = true;
                    try (InputStream is = IconOverlayRenderer.class.getResourceAsStream(
                            "/assets/xaeroseedmap/textures/icons/structures.png")) {
                        if (is != null) spriteSheet = NativeImage.read(is);
                    } catch (Exception ignored) {}
                }
            }
        }
        return spriteSheet;
    }
}
