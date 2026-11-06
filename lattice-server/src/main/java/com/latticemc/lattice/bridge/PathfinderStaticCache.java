package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.NativePathfinder;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Hook target for block updates in the patched Minecraft sources. */
public final class PathfinderStaticCache {
    private PathfinderStaticCache() {}

    public static void invalidate(Level level, BlockPos pos) {
        PathfinderTickStateCache.invalidate(level, pos);
        long generation = level.lattice$pathfinderMirrorWorld().existingGeneration();
        if (generation != 0L) NativePathfinder.invalidateStateMirror(generation, pos.getX(), pos.getY(), pos.getZ());
    }
}
