package com.latticemc.lattice.world;

import java.lang.reflect.Field;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.*;
import sun.misc.Unsafe;

/** 仅测试夹具：无世界生成/IO，仍执行真实 chunk/section/palette 方法。 */
public final class RandomTickTestSupport {
    private static final Unsafe UNSAFE;
    static long callbacks;
    static long checksum;
    static final BlockState PROBE;
    static {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try { Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);UNSAFE=(Unsafe)f.get(null); }
        catch(ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        // 注册表已冻结；只在测试内复制石头的基础字段，不注册新方块或修改全局注册表。
        ProbeBlock block=allocate(ProbeBlock.class);
        try {
            for(Class<?> type=Block.class;type!=Object.class;type=type.getSuperclass()) {
                for(Field field:type.getDeclaredFields()) {
                    if(java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                    field.setAccessible(true);field.set(block,field.get(Blocks.STONE));
                }
            }
        } catch(ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
        PROBE=new BlockState(block,new it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<>(),null);
        PROBE.initCache();
    }
    private static final class ProbeBlock extends Block {
        ProbeBlock() { super(BlockBehaviour.Properties.of().randomTicks().setId(ResourceKey.create(Registries.BLOCK,Identifier.parse("lattice:random_tick_probe")))); }
        @Override protected boolean isRandomlyTicking(BlockState state) { return true; }
        @Override protected void randomTick(BlockState state,ServerLevel world,BlockPos pos,RandomSource random) {
            callbacks++; checksum+=pos.asLong() ^ random.nextInt();
        }
    }
    public static <T> T allocate(Class<T> type) {
        try { return type.cast(UNSAFE.allocateInstance(type)); } catch(InstantiationException e) { throw new AssertionError(e); }
    }
    public static void field(Object instance,Class<?> declaring,String name,Object value) {
        try { Field f=declaring.getDeclaredField(name);f.setAccessible(true);f.set(instance,value); }
        catch(ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static LevelChunk chunk(int x,int z,int sections,int count) {
        LevelChunk chunk=allocate(LevelChunk.class);
        LevelChunkSection[] data=new LevelChunkSection[sections];
        for(int n=0;n<sections;n++) {
            var states=new PalettedContainer<>(Blocks.AIR.defaultBlockState(),Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            data[n]=new LevelChunkSection(states,null);
            for(int i=0;i<count;i++) data[n].setBlockState(i&15,(i>>>8)&15,(i>>>4)&15,PROBE);
        }
        field(chunk,ChunkAccess.class,"sections",data);field(chunk,ChunkAccess.class,"chunkPos",new ChunkPos(x,z));
        return chunk;
    }
}
