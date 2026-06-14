package com.latticemc.lattice.mixin;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativePaletteOps;
import java.util.function.IntConsumer;
import net.minecraft.util.SimpleBitStorage;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SimpleBitStorage.class)
public abstract class PackedIntegerArrayMixin {

    @Shadow @Final private long[] data;
    @Shadow @Final private int elementBits;
    @Shadow @Final private int size;

    @Inject(method = "writePaletteIndices([I)V", at = @At("HEAD"), cancellable = true)
    private void lattice$writePaletteIndices(int[] out, CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        NativePaletteOps.bulkGet(this.data, this.elementBits, 0L, out, 0, this.size);
        ci.cancel();
    }

    @Inject(method = "unpack([I)V", at = @At("HEAD"), cancellable = true)
    private void lattice$unpack(int[] out, CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        NativePaletteOps.bulkGet(this.data, this.elementBits, 0L, out, 0, this.size);
        ci.cancel();
    }

    @Inject(method = "getAll(Ljava/util/function/IntConsumer;)V", at = @At("HEAD"), cancellable = true)
    private void lattice$getAll(IntConsumer consumer, CallbackInfo ci) {
        if (!LatticeNative.isLoaded()) return;
        int[] decoded = new int[this.size];
        NativePaletteOps.bulkGet(this.data, this.elementBits, 0L, decoded, 0, this.size);
        for (int value : decoded) {
            consumer.accept(value);
        }
        ci.cancel();
    }
}
