package com.latticemc.lattice.mixin;

import com.latticemc.lattice.bootstrap.NativeZlibStreams;
import com.latticemc.lattice.nativelib.LatticeNative;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(RegionFileVersion.class)
public abstract class RegionFileMixin {

    @Shadow @org.spongepowered.asm.mixin.Final private int id;

    private static final int LATTICE$ZLIB_ID = 2;

    @Inject(method = "wrap(Ljava/io/OutputStream;)Ljava/io/OutputStream;",
            at = @At("HEAD"),
            cancellable = true)
    private void lattice$wrapOutput(OutputStream sink, CallbackInfoReturnable<OutputStream> cir) {
        if (this.id != LATTICE$ZLIB_ID) return;
        if (!LatticeNative.isLoaded()) return;
        cir.setReturnValue(NativeZlibStreams.deflater(sink));
    }

    @Inject(method = "wrap(Ljava/io/InputStream;)Ljava/io/InputStream;",
            at = @At("HEAD"),
            cancellable = true)
    private void lattice$wrapInput(InputStream source, CallbackInfoReturnable<InputStream> cir) throws IOException {
        if (this.id != LATTICE$ZLIB_ID) return;
        if (!LatticeNative.isLoaded()) return;
        cir.setReturnValue(NativeZlibStreams.inflater(source));
    }
}
