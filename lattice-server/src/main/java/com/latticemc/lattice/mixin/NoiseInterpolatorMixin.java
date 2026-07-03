package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.NativeNoiseInterpolatorAccess;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseChunk.NoiseInterpolator.class)
public abstract class NoiseInterpolatorMixin implements NativeNoiseInterpolatorAccess {
    @Shadow double[][] slice0;
    @Shadow double[][] slice1;
    @Unique private int lattice$nativeSlot = -1;

    @Override
    public void lattice$setNativeSlot(int slot) {
        this.lattice$nativeSlot = slot;
    }

    @Override
    public int lattice$nativeSlot() {
        return this.lattice$nativeSlot;
    }

    @Override
    public double[][] lattice$slice0() {
        return this.slice0;
    }

    @Override
    public double[][] lattice$slice1() {
        return this.slice1;
    }

    @Override
    public void lattice$selectCellYZ(int y, int z) {
        this.lattice$invokeSelectCellYZ(y, z);
    }

    @Invoker("selectCellYZ")
    protected abstract void lattice$invokeSelectCellYZ(int y, int z);
}
