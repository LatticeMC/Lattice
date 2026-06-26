package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeOreVeinBlockStateFiller;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NoiseChunk.class)
public abstract class NoiseChunkMixin {
    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/OreVeinifier;create(Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/DensityFunction;Lnet/minecraft/world/level/levelgen/PositionalRandomFactory;)Lnet/minecraft/world/level/levelgen/NoiseChunk$BlockStateFiller;"
            )
    )
    private NoiseChunk.BlockStateFiller lattice$useNativeOreVeinFiller(DensityFunction veinToggle,
                                                                       DensityFunction veinRidged,
                                                                       DensityFunction veinGap,
                                                                       PositionalRandomFactory random) {
        final NoiseChunk.BlockStateFiller nativeFiller = NativeOreVeinBlockStateFiller.tryCreate(
                veinToggle, veinRidged, veinGap, random);
        return nativeFiller != null
                ? nativeFiller
                : OreVeinifierAccessor.lattice$create(veinToggle, veinRidged, veinGap, random);
    }
}
