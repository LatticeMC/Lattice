package com.latticemc.lattice.bridge;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.level.chunk.EmptyLevelChunk;
import sun.misc.Unsafe;

/** 只在构造 region 时使用 Mock；计时期间执行真实 region 查询和 BlockState 方法。 */
final class PathfinderCacheTestSupport {
    static {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    static final BlockState[] STATES = {
        Blocks.AIR.defaultBlockState(), Blocks.STONE.defaultBlockState(),
        Blocks.STONE_SLAB.defaultBlockState(), Blocks.WATER.defaultBlockState()
    };

    final StateChunk chunk;
    final Level level = mock(Level.class);
    final PathNavigationRegion region;

    PathfinderCacheTestSupport() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            // 不创建真实世界、生成器或 IO；只分配覆写了查询方法的测试区块。
            chunk = (StateChunk) ((Unsafe) field.get(null)).allocateInstance(StateChunk.class);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
        chunk.state = Blocks.STONE.defaultBlockState();
        ChunkSource source = mock(ChunkSource.class);
        when(level.getChunkSource()).thenReturn(source);
        when(source.getChunkNow(anyInt(), anyInt())).thenReturn(chunk);
        region = new PathNavigationRegion(level, new BlockPos(-32, -64, -32), new BlockPos(32767, 319, 31));
    }

    void saturate(PathfinderTickStateCache cache) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int section = 0; section < 512; section++) cache.descriptorAt(region, pos.set(section << 4, 0, 0));
    }

    static final class StateChunk extends EmptyLevelChunk {
        BlockState state;
        boolean patterned;
        long reads;

        private StateChunk() { super(null, new ChunkPos(0, 0), null); }

        @Override public boolean isYSpaceEmpty(int startY, int endY) { return false; }

        @Override public BlockState getBlockState(BlockPos pos) {
            reads++;
            return patterned ? STATES[(pos.getX() ^ pos.getY() ^ pos.getZ()) & 3] : state;
        }
    }
}
