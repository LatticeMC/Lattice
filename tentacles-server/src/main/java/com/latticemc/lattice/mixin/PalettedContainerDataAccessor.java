package com.latticemc.lattice.mixin;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.chunk.Palette;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.world.level.chunk.PalettedContainer$Data")
public interface PalettedContainerDataAccessor<T> {
    @Accessor("storage")
    BitStorage lattice$getStorage();

    @Accessor("palette")
    Palette<T> lattice$getPalette();
}
