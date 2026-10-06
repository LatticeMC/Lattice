package com.latticemc.lattice.world;

import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 无 IO 世界；Level、ServerChunkCache、fullChunks、LevelChunk 的读取均执行原实现。 */
final class FluidSectionTestSupport implements AutoCloseable {
    static final boolean COUNT = Boolean.getBoolean("lattice.fluidTestCount");
    static class World extends ServerLevel {
        ServerChunkCache source;
        long reads;
        private World() { super(null,null,null,null,null,null,false,0L,List.of(),false,null,null,null,null); }
        @Override public ServerChunkCache getChunkSource() { return source; }
        @Override public FluidState getFluidState(BlockPos pos) {
            if (COUNT) ++reads;
            return super.getFluidState(pos);
        }
    }
    static class ProbeEntity extends EntityClassLookupTestSuite.Base {
        boolean pushed, unloaded;
        @Override public boolean touchingUnloadedChunk() { return unloaded; }
        @Override public boolean isPushedByFluid() { return pushed; }
    }
    final World world = RandomTickTestSupport.allocate(World.class);
    final List<LevelChunk> chunks = new ArrayList<>();
    final List<Object[]> tags = new ArrayList<>();
    final Field tagsField;
    FluidSectionTestSupport() {
        // Level 的边界判断直接读取缓存字段，不能只覆写高度 getter。
        for(var entry:java.util.Map.of("minY",-16,"height",48,"maxY",31,"minSectionY",-1,"maxSectionY",1,"sectionsCount",3).entrySet())
            RandomTickTestSupport.field(world,net.minecraft.world.level.Level.class,entry.getKey(),entry.getValue());
        try {
            // Bootstrap 不加载数据包；仅在本夹具作用域绑定四个内置流体标签，close 恢复。
            tagsField = Holder.Reference.class.getDeclaredField("tags"); tagsField.setAccessible(true);
            for (Fluid fluid : new Fluid[] {Fluids.WATER, Fluids.FLOWING_WATER, Fluids.LAVA, Fluids.FLOWING_LAVA}) {
                Object holder=fluid.builtInRegistryHolder();
                tags.add(new Object[] {holder,tagsField.get(holder)});
                tagsField.set(holder,new it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet<>(List.of(
                    fluid==Fluids.WATER||fluid==Fluids.FLOWING_WATER?FluidTags.WATER:FluidTags.LAVA)));
            }
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        world.source=RandomTickTestSupport.allocate(ServerChunkCache.class);
        RandomTickTestSupport.field(world.source,ServerChunkCache.class,"fullChunks",new ConcurrentLong2ReferenceChainedHashTable<LevelChunk>());
        RandomTickTestSupport.field(world,ServerLevel.class,"chunkSource",world.source);
        for(int z=-1;z<=1;z++) for(int x=-1;x<=1;x++) {
            LevelChunk chunk=RandomTickTestSupport.allocate(LevelChunk.class);
            LevelChunkSection[] sections={section(),section(),section()};
            RandomTickTestSupport.field(chunk,ChunkAccess.class,"sections",sections);
            RandomTickTestSupport.field(chunk,ChunkAccess.class,"levelHeightAccessor",world);
            RandomTickTestSupport.field(chunk,ChunkAccess.class,"chunkPos",new ChunkPos(x,z));
            RandomTickTestSupport.field(chunk,ChunkAccess.class,"minSection",-1);
            RandomTickTestSupport.field(chunk,ChunkAccess.class,"maxSection",1);
            chunks.add(chunk);world.source.moonrise$setFullChunk(x,z,chunk);
        }
    }
    static LevelChunkSection section() {
        return new LevelChunkSection(new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
            Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY)),null);
    }
    LevelChunkSection[] sections(int x,int z) { return world.getChunk(x>>4,z>>4).getSections(); }
    void set(int x,int y,int z,BlockState state) { sections(x,z)[(y>>4)+1].setBlockState(x&15,y&15,z&15,state); }
    void layer(int y, BlockState state) {
        for(LevelChunk chunk:chunks) for(int z=0;z<16;z++) for(int x=0;x<16;x++)
            chunk.getSections()[(y>>4)+1].setBlockState(x,y&15,z,state);
    }
    ProbeEntity entity(AABB box,boolean pushed) {
        ProbeEntity entity=RandomTickTestSupport.allocate(ProbeEntity.class);
        RandomTickTestSupport.field(entity,Entity.class,"level",world);
        RandomTickTestSupport.field(entity,Entity.class,"bb",box);
        RandomTickTestSupport.field(entity,Entity.class,"deltaMovement",Vec3.ZERO);
        RandomTickTestSupport.field(entity,Entity.class,"posLock",new Object());
        entity.pushed=pushed;
        return entity;
    }
    @Override public void close() {
        try { for(Object[] saved:tags) tagsField.set(saved[0],saved[1]); }
        catch(IllegalAccessException error) { throw new AssertionError(error); }
    }
}
