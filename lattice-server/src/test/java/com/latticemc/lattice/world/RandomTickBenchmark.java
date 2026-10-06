package com.latticemc.lattice.world;

import ca.spottedleaf.moonrise.common.util.SimpleThreadUnsafeRandom;
import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.*;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

/**
 * 实际选择器与原 optimiseRandomTick 循环对照，执行真实 palette/BlockState/轻量 ProbeBlock 回调。
 * 天气档只测同量随机试验，不模拟降雪世界写入；不代表整服 MSPT。
 */
public final class RandomTickBenchmark {
    private static final ThreadMXBean ALLOC=(ThreadMXBean)ManagementFactory.getThreadMXBean();
    private static volatile long sink;
    private record Scenario(int chunks,int activeSections,int count,int speed,boolean weather) {}
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);ALLOC.setThreadAllocatedMemoryEnabled(true);
        boolean reverse=Boolean.getBoolean("lattice.benchReverse");
        String modeName=System.getProperty("lattice.randomBenchMode","exact");
        if(!modeName.equals("original")&&!modeName.equals("exact"))throw new IllegalArgumentException(modeName);
        int mode=modeName.equals("original")?0:1;
        Scenario[] scenarios={
            new Scenario(1,0,0,3,false),new Scenario(1,1,1,3,false),new Scenario(1,4,64,3,false),new Scenario(1,24,4096,3,false),
            new Scenario(64,0,0,3,false),new Scenario(64,1,1,3,false),new Scenario(64,4,64,3,false),new Scenario(64,24,4096,3,false),
            new Scenario(1024,0,0,3,false),new Scenario(1024,1,1,3,false),new Scenario(1024,4,64,3,false),new Scenario(1024,24,4096,3,false),
            new Scenario(64,4,64,0,false),new Scenario(64,4,64,16,false),new Scenario(64,4,64,3,true)
        };
        System.out.printf("RANDOM_BENCH java=%s mode=%s reverse=%s sections=24 warmup=8 samples=15 selectionSeed=230%n",System.getProperty("java.version"),modeName,reverse);
        for(int order=0;order<scenarios.length;order++) {
            Scenario s=scenarios[reverse?scenarios.length-1-order:order];
            LevelChunk template=RandomTickTestSupport.chunk(0,0,24,s.activeSections,s.count);LevelChunk[] chunks=new LevelChunk[s.chunks];
            // 静态场景共享只读sections，真实回调仍经过各chunk坐标；不含建表成本。
            for(int i=0;i<chunks.length;i++) {
                chunks[i]=RandomTickTestSupport.chunk(i%32,i/32,0,0);
                RandomTickTestSupport.field(chunks[i],ChunkAccess.class,"sections",template.getSections());
                RandomTickTestSupport.field(chunks[i],LevelChunk.class,"lattice$randomTickingSections",template.lattice$getRandomTickingSections());
            }
            if(s.activeSections>0&&chunks[0].lattice$getRandomTickingSections()==0L)throw new AssertionError("Missing section mask");
            int iterations=Math.max(64,Math.min(20000,1_000_000/Math.max(1,s.chunks*Math.max(1,s.activeSections)*Math.max(1,s.speed*s.count/4096))));
            SimpleThreadUnsafeRandom random=new SimpleThreadUnsafeRandom(230);
            RandomTickSystem system=new RandomTickSystem(new XoroshiroRandomSource(230));
            for(int n=0;n<8;n++)run(mode,system,chunks,s,random,iterations);
            double[] ns=new double[15],bytes=new double[15];long calls=0,weatherCalls=0;
            for(int n=0;n<15;n++) {
                Measurement m=run(mode,system,chunks,s,random,iterations);
                ns[n]=(double)m.nanos/iterations;bytes[n]=(double)m.bytes/iterations;calls+=m.calls;weatherCalls+=m.weather;
                System.out.printf("RANDOM_SAMPLE chunks=%d activeSections=%d count=%d speed=%d weather=%s mode=%s sample=%d iterations=%d ns=%.6f bytes=%.6f calls=%d%n",s.chunks,s.activeSections,s.count,s.speed,s.weather,modeName,n,iterations,ns[n],bytes[n],m.calls);
            }
            Arrays.sort(ns);Arrays.sort(bytes);
            System.out.printf("RANDOM_RESULT chunks=%d activeSections=%d count=%d speed=%d weather=%s mode=%s ns=%.6f bytes=%.6f callbacks=%.9f precipitation=%.9f ticks=%d%n",s.chunks,s.activeSections,s.count,s.speed,s.weather,modeName,ns[7],bytes[7],(double)calls/(iterations*15L),(double)weatherCalls/(iterations*15L),iterations*15L);
        }
    }
    private static Measurement run(int mode,RandomTickSystem system,LevelChunk[] chunks,Scenario s,SimpleThreadUnsafeRandom random,int iterations) {
        long thread=Thread.currentThread().threadId(),beforeCalls=RandomTickTestSupport.callbacks,beforeBytes=ALLOC.getThreadAllocatedBytes(thread),start=System.nanoTime(),weather=0;
        for(int tick=0;tick<iterations;tick++) {
            if(mode==0) {
                for(LevelChunk chunk:chunks) {
                    if(s.weather)for(int trial=0;trial<s.speed;trial++)if(random.nextInt(48)==0)weather++;
                    original(chunk,s.speed,random);
                }
            } else {
                for(LevelChunk chunk:chunks) {
                    if(s.weather)for(int trial=0;trial<s.speed;trial++)if(random.nextInt(48)==0)weather++;
                    if(s.speed>0)system.tickChunkSections(null,chunk,s.speed,-4,random,false);
                }
            }
        }
        long nanos=System.nanoTime()-start,bytes=ALLOC.getThreadAllocatedBytes(thread)-beforeBytes,calls=RandomTickTestSupport.callbacks-beforeCalls;
        sink=RandomTickTestSupport.checksum+weather;return new Measurement(nanos,bytes,calls,weather);
    }
    // 从 ServerLevel.optimiseRandomTick 保留原扫描、每轮刷新size、随机源及不可变位置构造。
    private static void original(LevelChunk chunk,int speed,SimpleThreadUnsafeRandom random) {
        if(speed<=0)return;
        var sections=chunk.getSections();var pos=chunk.getPos();int x=pos.x<<4,z=pos.z<<4;
        for(int n=0;n<sections.length;n++) {
            var section=sections[n];if(!section.isRandomlyTickingBlocks())continue;
            var list=section.moonrise$getTickingBlockList();
            for(int j=0;j<speed;j++) {
                int count=list.size(),index=random.nextInt()&4095;if(index>=count)continue;
                int location=list.getRaw(index)&65535;
                var state=section.states.get(location);
                BlockPos point=new BlockPos((location&15)|x,((location>>>8)&15)|((n-4)<<4),((location>>>4)&15)|z);
                state.randomTick(null,point,random);
            }
        }
    }
    private record Measurement(long nanos,long bytes,long calls,long weather) {}
}
