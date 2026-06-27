package com.latticemc.lattice.mixin;

import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Beardifier.class)
public interface BeardifierAccessor {
    @Accessor("pieces")
    List<Beardifier.Rigid> lattice$pieces();

    @Accessor("junctions")
    List<JigsawJunction> lattice$junctions();
}
