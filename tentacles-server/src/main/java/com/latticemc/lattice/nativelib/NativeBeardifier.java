package com.latticemc.lattice.nativelib;

import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

public final class NativeBeardifier {
    private NativeBeardifier() {}

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static double compute(List<Beardifier.Rigid> pieces,
                                 List<JigsawJunction> junctions,
                                 int blockX, int blockY, int blockZ) {
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) {
            throw new IllegalStateException("NativeBeardifier requires the lattice native library");
        }
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

        return nativeCompute(pieceInts, junctionInts, blockX, blockY, blockZ);
    }

    private static native double nativeCompute(int[] pieceInts,
                                               int[] junctionInts,
                                               int blockX, int blockY, int blockZ);
}
