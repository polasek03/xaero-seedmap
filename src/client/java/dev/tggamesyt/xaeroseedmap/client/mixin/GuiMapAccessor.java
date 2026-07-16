package dev.tggamesyt.xaeroseedmap.client.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import xaero.map.gui.GuiMap;

// By using an interface and @Accessor, we avoid method signature crashes entirely!
@Mixin(targets = "xaero.map.gui.GuiMap", remap = false)
public interface GuiMapAccessor {
    
    @Accessor("cameraX")
    double getCameraX();

    @Accessor("cameraZ")
    double getCameraZ();

    @Accessor("destScale")
    double getDestScale();
}