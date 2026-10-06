package com.latticemc.lattice.util;

import static org.junit.jupiter.api.Assertions.*;
import static com.latticemc.lattice.world.RandomTickTestSupport.*;
import ca.spottedleaf.concurrentutil.map.ConcurrentLong2ReferenceChainedHashTable;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

class ChunkLookupIntegrationTestSuite {
    @Test void actualHolderLookupSeesAsynchronousCreationAndInvalidation() throws Exception {
        ChunkHolderManager manager=allocate(ChunkHolderManager.class);
        var source=new ConcurrentLong2ReferenceChainedHashTable<NewChunkHolder>();
        var reads=new java.util.concurrent.atomic.AtomicInteger();
        var cache=new ChunkCache<NewChunkHolder>(Thread.currentThread(), key -> { reads.incrementAndGet(); return source.get(key); });
        field(manager,ChunkHolderManager.class,"chunkHolders",source);
        field(manager,ChunkHolderManager.class,"lattice$holderCache",cache);
        manager.lattice$beginTick();
        assertNull(manager.getChunkHolder(-1,2));
        NewChunkHolder holder=allocate(NewChunkHolder.class);long key=ChunkPos.asLong(-1,2);
        ChunkCacheTestSuite.runWorker(()->source.put(key,holder));
        assertSame(holder,manager.getChunkHolder(key));assertSame(holder,manager.getChunkHolder(-1,2));
        assertEquals(ChunkCache.ENABLED ? 2 : 0,reads.get(),"实际入口应按开关使用缓存或直接查询");
        cache.invalidate(key);source.remove(key);assertNull(manager.getChunkHolder(key));
        NewChunkHolder replacement=allocate(NewChunkHolder.class);
        source.put(key,replacement);
        assertSame(replacement,manager.getChunkHolder(key));
        manager.lattice$endTick();
    }

    @Test void removalHookAndRandomSchedulerBranchesArePresent() throws Exception {
        ClassNode holder=new ClassNode();new ClassReader(ChunkHolderManager.class.getName()).accept(holder,0);
        var remove=holder.methods.stream().filter(m->m.name.equals("removeChunkHolder")).findFirst().orElseThrow();
        int invalidate=-1,delete=-1,index=0;
        for(var instruction:remove.instructions) {
            if(instruction instanceof MethodInsnNode call) {
                if(call.owner.endsWith("/ChunkCache")&&call.name.equals("invalidate"))invalidate=index;
                if(call.owner.endsWith("/ConcurrentLong2ReferenceChainedHashTable")&&call.name.equals("remove"))delete=index;
            }index++;
        }
        assertTrue(invalidate>=0 && delete>invalidate);
        ClassNode server=new ClassNode();new ClassReader(ServerChunkCache.class.getName()).accept(server,0);
        long exact=server.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
            .filter(i->i instanceof MethodInsnNode c && c.owner.endsWith("/RandomTickSystem")&&c.name.equals("tick")).count();
        long original=server.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
            .filter(i->i instanceof MethodInsnNode c && c.name.equals("iterateTickingChunksFaster")).count();
        assertEquals(1,exact);assertEquals(1,original);
    }
}
