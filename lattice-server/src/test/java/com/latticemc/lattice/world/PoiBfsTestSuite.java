package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.village.poi.*;
import org.junit.jupiter.api.Test;

public class PoiBfsTestSuite {
    @Test void generationSetMatchesHashSetAcrossGrowthCollisionsAndWrap() {
        var actual=new PoiBfsScratch.SeenSet();
        var expected=new HashSet<Long>();
        var random=new Random(4981);
        for(int pass=0;pass<20;pass++) {
            actual.clear();expected.clear();
            for(int i=0;i<20000;i++) {
                long key=i%4==0?i%100:i%4==1?random.nextLong():((long)i<<32);
                assertEquals(expected.add(key),actual.add(key));
                assertFalse(actual.add(key));
            }
            assertEquals(expected.size(),actual.size());
            assertEquals(expected.add(0L),actual.add(0));
            assertEquals(expected.add(Long.MIN_VALUE),actual.add(Long.MIN_VALUE));
        }
        actual=new PoiBfsScratch.SeenSet();
        assertTrue(actual.add(1L<<32));
        RandomTickTestSupport.field(actual,PoiBfsScratch.SeenSet.class,"generation",Integer.MAX_VALUE);
        assertTrue(actual.add(0));assertTrue(actual.add(Long.MAX_VALUE));
        actual.clear();assertTrue(actual.isEmpty());
        assertTrue(actual.add(0));assertTrue(actual.add(Long.MAX_VALUE));
        // MAX_VALUE -> 1 不能让最初第一代槽重新生效。
        assertTrue(actual.add(1L<<32));assertFalse(actual.add(1L<<32));
    }
    @Test void fifoWrapGrowthAndClear() {
        var q=new PoiBfsScratch.RetainedQueue();
        var expected=new ArrayDeque<Long>();
        var random=new Random(230);
        for(int i=0;i<100000;i++) {
            if(expected.isEmpty()||random.nextInt(3)!=0) {long n=random.nextLong();q.enqueue(n);expected.add(n);}
            else assertEquals(expected.remove().longValue(),q.dequeueLong());
        }
        int capacity=q.capacity();
        while(!expected.isEmpty())assertEquals(expected.remove().longValue(),q.dequeueLong());
        assertEquals(capacity,q.capacity());assertTrue(q.isEmpty());
        q.enqueue(0);q.clear();assertTrue(q.isEmpty());q.enqueue(Long.MIN_VALUE);assertEquals(Long.MIN_VALUE,q.dequeueLong());
        assertThrows(NoSuchElementException.class,q::dequeueLong);
    }
    @Test void scratchIsCleanReentrantAndBounded() {
        PoiBfsScratch first=PoiBfsScratch.acquire();
        first.seen.add(0);first.queue.enqueue(42);
        try(var nested=PoiBfsScratch.acquire()) {
            assertNotSame(first,nested);assertTrue(nested.seen.add(0));nested.queue.enqueue(7);
        }
        assertFalse(first.seen.add(0));assertEquals(42,first.queue.dequeueLong());first.close();
        try(var again=PoiBfsScratch.acquire()) {assertSame(first,again);assertTrue(again.seen.add(0));assertTrue(again.queue.isEmpty());}
        try(var huge=PoiBfsScratch.acquire()) {for(int i=0;i<20000;i++)huge.seen.add(i);}
        try(var after=PoiBfsScratch.acquire()) {assertNotSame(first,after);assertTrue(after.seen.isEmpty());}
        PoiBfsScratch largeQueue=PoiBfsScratch.acquire();
        for(int i=0;i<5000;i++)largeQueue.queue.enqueue(i);
        while(!largeQueue.queue.isEmpty())largeQueue.queue.dequeueLong();
        largeQueue.close();
        try(var after=PoiBfsScratch.acquire()) {assertNotSame(largeQueue,after);assertTrue(after.queue.isEmpty());}
    }
    @Test void threadsDoNotShareScratch() throws Exception {
        try(var outer=PoiBfsScratch.acquire()) {
            var other=new java.util.concurrent.atomic.AtomicReference<PoiBfsScratch>();
            Thread t=new Thread(()->{try(var scratch=PoiBfsScratch.acquire()){other.set(scratch);scratch.seen.add(123);}});
            t.start();t.join();assertNotSame(outer,other.get());assertTrue(outer.seen.isEmpty());
        }
    }
    @Test void actualSearchSurvivesExceptionAndNestedQueries() throws Exception {
        List<String> result=trace();
        // 冻结的优化前 f86d7cd 产物所生成的 66 条结果及完整存储/回调访问轨迹。
        String serialized=String.join("\n",result.stream().map(s->"POI_TRACE "+s).toList());
        String digest=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
            .digest(serialized.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(66,result.size());
        assertEquals("f22dde0114267c1158d1e759b863452abf275099c920e3322e17354581e7ed82",digest,
            ()->"BFS 轨迹改变：\n"+serialized);
        assertTrue(result.stream().anyMatch(s->s.startsWith("exception=true")));
        assertTrue(result.stream().anyMatch(s->s.startsWith("nested=")));
        assertTrue(result.stream().anyMatch(s->s.startsWith("nested=[0:A]|")));
        assertTrue(result.stream().anyMatch(s->s.startsWith("after=[0:A]|")));
        assertTrue(result.stream().anyMatch(s->s.startsWith("nested-exception=[0:A]|")));
    }
    static List<String> trace() {
        List<String> all=new ArrayList<>();
        for(boolean nearest:new boolean[]{false,true})for(boolean load:new boolean[]{false,true})
            for(var occupancy:PoiManager.Occupancy.values())for(int max:new int[]{1,3,20}) {
                var manager=PoiBfsTestSupport.manager();manager.trace=new ArrayList<>();
                PoiBfsTestSupport.add(manager,-16,0,0,PoiBfsTestSupport.TYPE,0);
                PoiBfsTestSupport.add(manager,16,0,0,PoiBfsTestSupport.TYPE,1);
                PoiBfsTestSupport.add(manager,0,-16,0,PoiBfsTestSupport.TYPE,2);
                PoiBfsTestSupport.add(manager,0,16,0,PoiBfsTestSupport.OTHER,3);
                PoiBfsTestSupport.add(manager,0,0,-16,PoiBfsTestSupport.TYPE,1);
                PoiBfsTestSupport.add(manager,0,0,16,PoiBfsTestSupport.TYPE,1);
                List<PoiRecord> output=new ArrayList<>();
                output.add(new PoiRecord(new BlockPos(99,0,99),PoiBfsTestSupport.TYPE,()->{}));
                PoiBfsTestSupport.query(manager,nearest,BlockPos.ZERO,48,2304,occupancy,load,max,
                    type->{manager.trace.add("type:"+(type==PoiBfsTestSupport.TYPE));return true;},
                    pos->{manager.trace.add("pos:"+pos.asLong());return pos.getZ()!=16;},output);
                all.add(nearest+"/"+load+"/"+occupancy+"/"+max+"="+PoiBfsTestSupport.positions(output)+"|"+manager.trace);
            }
        for(int radius:new int[]{0,1,16,48,96})for(int y:new int[]{-80,-64,0,319,400}) {
            var m=PoiBfsTestSupport.manager();m.trace=new ArrayList<>();
            var result=PoiBfsTestSupport.query(m,true,new BlockPos(-1,y,-1),radius,radius*radius,PoiManager.Occupancy.ANY,false,2,t->true,null,new ArrayList<>());
            all.add("empty/"+radius+"/"+y+"="+result.size()+"|"+m.trace);
        }
        var m=PoiBfsTestSupport.manager();PoiBfsTestSupport.add(m,0,0,0,PoiBfsTestSupport.TYPE,2);
        List<String> nested=new ArrayList<>();m.trace=nested;
        var result=PoiBfsTestSupport.query(m,true,BlockPos.ZERO,48,2304,PoiManager.Occupancy.ANY,false,3,t->true,p->{
            var inner=PoiBfsTestSupport.query(m,false,p,16,256,PoiManager.Occupancy.ANY,true,1,t->true,null,new ArrayList<>());
            nested.add("inner="+PoiBfsTestSupport.positions(inner));return true;
        },new ArrayList<>());
        all.add("nested="+PoiBfsTestSupport.positions(result)+"|"+nested);
        RuntimeException marker=new RuntimeException("marker");
        try {PoiBfsTestSupport.query(m,false,BlockPos.ZERO,48,2304,PoiManager.Occupancy.ANY,false,1,t->{throw marker;},null,new ArrayList<>());}
        catch(RuntimeException e){all.add("exception="+(e==marker));}
        m.trace=new ArrayList<>();
        result=PoiBfsTestSupport.query(m,true,BlockPos.ZERO,48,2304,PoiManager.Occupancy.HAS_SPACE,false,5,t->true,null,new ArrayList<>());
        all.add("after="+PoiBfsTestSupport.positions(result)+"|"+m.trace);
        m.trace=new ArrayList<>();
        result=PoiBfsTestSupport.query(m,false,BlockPos.ZERO,48,2304,PoiManager.Occupancy.ANY,false,1,t->true,p->{
            try {
                PoiBfsTestSupport.query(m,true,p,48,2304,PoiManager.Occupancy.ANY,true,5,t->true,inner->{throw marker;},new ArrayList<>());
                throw new AssertionError("nested query must throw");
            } catch(RuntimeException e) {assertSame(marker,e);}
            return true;
        },new ArrayList<>());
        all.add("nested-exception="+PoiBfsTestSupport.positions(result)+"|"+m.trace);
        var other=PoiBfsTestSupport.manager();
        PoiBfsTestSupport.add(other,1,0,1,PoiBfsTestSupport.TYPE,2);
        result=PoiBfsTestSupport.query(other,true,BlockPos.ZERO,16,256,PoiManager.Occupancy.ANY,false,5,t->true,null,new ArrayList<>());
        all.add("other-world="+PoiBfsTestSupport.positions(result));
        return all;
    }
    public static void main(String[] args) {
        System.out.println("SOURCE "+io.papermc.paper.util.PoiAccess.class.getProtectionDomain().getCodeSource().getLocation());
        trace().forEach(s->System.out.println("POI_TRACE "+s));
        org.apache.logging.log4j.LogManager.shutdown();
    }
}
