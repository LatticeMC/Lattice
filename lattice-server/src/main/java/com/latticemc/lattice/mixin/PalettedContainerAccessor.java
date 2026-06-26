package com.latticemc.lattice.mixin;

import net.minecraft.world.level.chunk.PalettedContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PalettedContainer.class)
public interface PalettedContainerAccessor<T> {
    @Accessor("data")
    Object lattice$getData();
}
