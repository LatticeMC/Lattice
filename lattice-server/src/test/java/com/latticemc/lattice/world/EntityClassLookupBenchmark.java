package com.latticemc.lattice.world;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.world.entity.Entity;

/** 每 JVM 一个模式；真实 Class 查询，包括空集合、miss 回退和维护成本。 */
public final class EntityClassLookupBenchmark {
    private record Scenario(String name, int classes, int entities, int mutation) { }
    private static volatile long sink;
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var allocation = (ThreadMXBean)ManagementFactory.getThreadMXBean();
        allocation.setThreadAllocatedMemoryEnabled(true);
        System.out.println("SOURCE " + ChunkEntitySlices.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("MODE " + Boolean.getBoolean("lattice.entityClassLookup"));
        Scenario[] scenarios = {
            new Scenario("empty1",1,0,0), new Scenario("empty2",2,0,0),
            new Scenario("empty3",3,0,0), new Scenario("empty4",4,0,0),
            new Scenario("empty8",8,0,0), new Scenario("one3",3,1,0),
            new Scenario("dense3",3,16,0), new Scenario("churn3",3,1,1),
            new Scenario("cold3",3,0,2), new Scenario("random8",8,0,3)
        };
        for (Scenario s:scenarios) {
            ChunkEntitySlices[] slices = new ChunkEntitySlices[256];
            for(int n=0;n<slices.length;n++) {
                slices[n]=EntityClassLookupTestSuite.slice(n);
                for(int c=0;c<s.classes;c++) {
                    for(int e=0;e<s.entities;e++) slices[n].addEntity(EntityClassLookupTestSuite.entity(
                        (Class<? extends EntityClassLookupTestSuite.Base>)EntityClassLookupTestSuite.TYPES[c],c*100+e+1,0),0);
                    EntityClassLookupTestSuite.query(slices[n],EntityClassLookupTestSuite.TYPES[c]);
                }
            }
            int queries=s.mutation==2?10000:200000;
            for(int i=0;i<10;i++) run(slices,s,queries);
            double[] ns=new double[15], bytes=new double[15];
            for(int i=0;i<15;i++) {
                long before=allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start=System.nanoTime();
                long count=run(slices,s,queries);
                ns[i]=(double)(System.nanoTime()-start)/queries;
                bytes[i]=(double)(allocation.getThreadAllocatedBytes(Thread.currentThread().threadId())-before)/queries;
                System.out.printf("CLASS_SAMPLE name=%s sample=%d ns=%.6f bytes=%.6f checksum=%d%n",s.name,i,ns[i],bytes[i],count);
            }
            Arrays.sort(ns);Arrays.sort(bytes);
            System.out.printf("CLASS_RESULT name=%s ns=%.6f bytes=%.6f%n",s.name,ns[7],bytes[7]);
        }
        org.apache.logging.log4j.LogManager.shutdown();
    }
    private static long run(ChunkEntitySlices[] slices, Scenario s, int queries) {
        var result=new ArrayList<Entity>(64);
        var change=EntityClassLookupTestSuite.entity(EntityClassLookupTestSuite.A.class,999,0);
        long count=0;
        int random=230;
        for(int i=0;i<queries;i++) {
            int c=(i>>>8)%s.classes;
            if(s.mutation==3) {random^=random<<13;random^=random>>>17;random^=random<<5;c=random&7;}
            ChunkEntitySlices slice=s.mutation==2?EntityClassLookupTestSuite.slice(i):slices[i&255];
            if(s.mutation==1&&(i&63)==0) slice.addEntity(change,0);
            result.clear();
            if((i&1)==0) slice.getEntities(EntityClassLookupTestSuite.TYPES[c],null,EntityClassLookupTestSuite.ALL,result,null);
            else if(slice.getEntities(EntityClassLookupTestSuite.TYPES[c],null,EntityClassLookupTestSuite.ALL,result,null,8)) ++count;
            count+=result.size();
            if(!result.isEmpty()) count+=result.getFirst().getId();
            if(s.mutation==1&&(i&63)==0) slice.removeEntity(change,0);
        }
        sink=count;
        return count;
    }
}
