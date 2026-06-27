package com.latticemc.lattice.nativelib;

import java.lang.ref.Cleaner;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

public final class NativeBeardifier implements AutoCloseable {
    private static final Cleaner CLEANER = Cleaner.create();

    private final long handle;
    private final Cleaner.Cleanable cleanable;
    private boolean closed = false;

    private NativeBeardifier(long handle) {
        this.handle = handle;
        this.cleanable = CLEANER.register(this, new Cleanup(handle));
    }

    public static boolean isAvailable() {
        return LatticeNative.isLoaded();
    }

    public static NativeBeardifier create(List<Beardifier.Rigid> pieces,
                                          List<JigsawJunction> junctions) {
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) return null;
        try {
            final int[] pieceInts = new int[pieces.size() * 8];
            for (int i = 0; i < pieces.size(); ++i) {
                final Beardifier.Rigid piece = pieces.get(i);
                final int off = i * 8;
                pieceInts[off] = piece.box().minX();
                pieceInts[off + 1] = piece.box().minY();
                pieceInts[off + 2] = piece.box().minZ();
                pieceInts[off + 3] = piece.box().maxX();
                pieceInts[off + 4] = piece.box().maxY();
                pieceInts[off + 5] = piece.box().maxZ();
                pieceInts[off + 6] = piece.terrainAdjustment().ordinal();
                pieceInts[off + 7] = piece.groundLevelDelta();
            }

            final int[] junctionInts = new int[junctions.size() * 3];
            for (int i = 0; i < junctions.size(); ++i) {
                final JigsawJunction junction = junctions.get(i);
                final int off = i * 3;
                junctionInts[off] = junction.getSourceX();
                junctionInts[off + 1] = junction.getSourceGroundY();
                junctionInts[off + 2] = junction.getSourceZ();
            }

            long h = nativeCreate(pieceInts, junctionInts);
            if (h == 0L) return null;
            return new NativeBeardifier(h);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    public double compute(int blockX, int blockY, int blockZ) {
        if (closed) throw new IllegalStateException("NativeBeardifier is closed");
        return nativeCompute(handle, blockX, blockY, blockZ);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        cleanable.clean();
    }

    private static native long nativeCreate(int[] pieceInts, int[] junctionInts);
    private static native void nativeDestroy(long handle);
    private static native double nativeCompute(long handle, int blockX, int blockY, int blockZ);

    private static final class Cleanup implements Runnable {
        private final long handle;

        private Cleanup(long handle) {
            this.handle = handle;
        }

        @Override
        public void run() {
            if (handle != 0L) nativeDestroy(handle);
        }
    }
}
