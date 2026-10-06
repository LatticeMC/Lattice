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
    @Test void actualHolderLookupSeesAsynchronousCreationReplacementAndRemoval() throws Exception {
        ChunkHolderManager manager = allocate(ChunkHolderManager.class);
        var source = new ConcurrentLong2ReferenceChainedHashTable<NewChunkHolder>();
        field(manager, ChunkHolderManager.class, "chunkHolders", source);
        long key = ChunkPos.asLong(-1, 2);
        assertNull(manager.getChunkHolder(key));
        assertNull(manager.getChunkHolder(-1, 2));

        NewChunkHolder holder = allocate(NewChunkHolder.class);
        ChunkCacheTestSuite.runWorker(() -> source.put(key, holder));
        assertSame(holder, manager.getChunkHolder(key));
        assertSame(holder, manager.getChunkHolder(-1, 2));

        NewChunkHolder replacement = allocate(NewChunkHolder.class);
        ChunkCacheTestSuite.runWorker(() -> source.put(key, replacement));
        assertSame(replacement, manager.getChunkHolder(key));
        assertSame(replacement, manager.getChunkHolder(-1, 2));

        ChunkCacheTestSuite.runWorker(() -> source.remove(key));
        assertNull(manager.getChunkHolder(key));
        assertNull(manager.getChunkHolder(-1, 2));
    }

    @Test void cacheHooksAreAbsentAndRandomSchedulerBranchesRemain() throws Exception {
        ClassNode holder = new ClassNode();
        new ClassReader(ChunkHolderManager.class.getName()).accept(holder, 0);
        ClassNode server = new ClassNode();
        new ClassReader(ServerChunkCache.class.getName()).accept(server, 0);
        for (ClassNode node : new ClassNode[] {holder, server}) {
            assertTrue(node.fields.stream().noneMatch(f -> f.desc.contains("/ChunkCache;") || f.name.equals("lattice$holderCache")));
            assertTrue(node.methods.stream().noneMatch(m -> m.name.equals("lattice$beginTick") || m.name.equals("lattice$endTick")));
            for (var method : node.methods) {
                for (var instruction : method.instructions) {
                    if (instruction instanceof MethodInsnNode call) {
                        assertFalse(call.owner.endsWith("/ChunkCache"), "生产代码不得调用已退役缓存");
                    }
                }
            }
        }
        long exact=server.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
            .filter(i->i instanceof MethodInsnNode c && c.owner.endsWith("/RandomTickSystem")&&c.name.equals("tick")).count();
        long original=server.methods.stream().flatMap(m->java.util.stream.StreamSupport.stream(m.instructions.spliterator(),false))
            .filter(i->i instanceof MethodInsnNode c && c.name.equals("iterateTickingChunksFaster")).count();
        assertEquals(1,exact);assertEquals(1,original);
    }
}
