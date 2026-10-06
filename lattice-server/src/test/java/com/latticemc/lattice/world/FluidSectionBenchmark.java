package com.latticemc.lattice.world;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.WaterFluid;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 生产扫描基线与测试高度原型分开计时；完整扫描已无实验分支。 */
public final class FluidSectionBenchmark {
    private record Scenario(String name,int y,int kind,boolean push,boolean cross,boolean heightOnly) { }
    private static volatile long sink;
    static class FallbackWater extends WaterFluid.Source {
        @Override public float getHeight(FluidState state,net.minecraft.world.level.BlockGetter world,BlockPos pos) { return .375F; }
    }
    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        var allocations=(ThreadMXBean)ManagementFactory.getThreadMXBean();
        allocations.setThreadAllocatedMemoryEnabled(true);
        boolean optimized=Boolean.getBoolean("lattice.fluidSectionHeight");
        if(FluidSectionTestSupport.COUNT) throw new IllegalStateException("计时须关闭 lattice.fluidTestCount");
        System.out.println("SOURCE "+Entity.class.getProtectionDomain().getCodeSource().getLocation());
        System.out.println("生产扫描始终为基线；lattice.fluidSectionHeight 仅选择 height* 场景中的测试原型");
        Scenario[] scenes={
            new Scenario("dry",0,0,true,false,false),new Scenario("shallow",0,1,true,false,false),
            new Scenario("deep",0,2,true,false,false),new Scenario("lava",0,3,true,false,false),
            new Scenario("boundary",15,2,true,false,false),new Scenario("cross",0,2,true,true,false),
            new Scenario("notPushed",0,2,false,false,false),new Scenario("worldTop",31,1,true,false,false),
            new Scenario("heightShallow",0,1,false,false,true),new Scenario("heightDeep",0,2,false,false,true),
            new Scenario("heightBoundary",15,2,false,false,true),new Scenario("heightTop",31,1,false,false,true),
            new Scenario("heightCustom",0,4,false,false,true)
        };
        for(Scenario scene:scenes) try(var f=new FluidSectionTestSupport()) {
            var block=scene.kind==0?Blocks.AIR:scene.kind==3?Blocks.LAVA:Blocks.WATER;
            f.layer(scene.y,block.defaultBlockState());
            if(scene.kind==2) f.layer(scene.y+1,block.defaultBlockState());
            if(scene.kind>0 && scene.kind<4) f.set(9,scene.y,8,block.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,4));
            FluidSectionTestSupport.ProbeEntity[] entities=new FluidSectionTestSupport.ProbeEntity[32];
            BlockPos[] positions=new BlockPos[32];
            for(int i=0;i<entities.length;i++) {
                double x=scene.cross?15.5:(i%3-1)*16+8;
                double z=scene.cross?15.5:(i/3%3-1)*16+8;
                entities[i]=f.entity(new AABB(x,scene.y,z,x+1,scene.y+1.8,z+1),scene.push);
                positions[i]=BlockPos.containing(x,scene.y,z);
            }
            var arrays=new net.minecraft.world.level.chunk.LevelChunkSection[positions.length][];
            for(int i=0;i<positions.length;i++) arrays[i]=f.sections(positions[i].getX(),positions[i].getZ());
            var state=scene.kind==4?new FluidState(RandomTickTestSupport.allocate(FallbackWater.class),new it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<>(),null)
                :scene.kind==3?Fluids.LAVA.defaultFluidState():Fluids.WATER.defaultFluidState();
            int count=scene.heightOnly?300000:30000;
            for(int i=0;i<10;i++) run(f,entities,positions,arrays,state,scene,optimized,count);
            double[] ns=new double[15],bytes=new double[15];
            for(int i=0;i<15;i++) {
                long before=allocations.getThreadAllocatedBytes(Thread.currentThread().threadId());
                long start=System.nanoTime();
                long checksum=run(f,entities,positions,arrays,state,scene,optimized,count);
                ns[i]=(double)(System.nanoTime()-start)/count;
                bytes[i]=(double)(allocations.getThreadAllocatedBytes(Thread.currentThread().threadId())-before)/count;
                System.out.printf("FLUID_SAMPLE name=%s sample=%d ns=%.6f bytes=%.6f checksum=%d%n",scene.name,i,ns[i],bytes[i],checksum);
            }
            Arrays.sort(ns);Arrays.sort(bytes);
            System.out.printf("FLUID_RESULT name=%s ns=%.6f bytes=%.6f%n",scene.name,ns[7],bytes[7]);
        }
        org.apache.logging.log4j.LogManager.shutdown();
    }
    private static long run(FluidSectionTestSupport f,FluidSectionTestSupport.ProbeEntity[] entities,
                            BlockPos[] positions,net.minecraft.world.level.chunk.LevelChunkSection[][] arrays,
                            FluidState state,Scenario s,boolean optimized,int count) {
        long checksum=0;
        var tag=s.kind==3?FluidTags.LAVA:FluidTags.WATER;
        // 高度微基准复用测量前取得的数组；真实扫描自己计入获取数组的成本。
        for(int i=0;i<count;i++) {
            int index=i&31;
            if(s.heightOnly) {
                float h=optimized?FluidSectionHeight.getHeight(state,f.world,positions[index],arrays[index],-1)
                    :state.getHeight(f.world,positions[index]);
                checksum+=Float.floatToRawIntBits(h);
            } else {
                var entity=entities[index];
                entity.setDeltaMovement(Vec3.ZERO);
                if(entity.updateFluidHeightAndDoFluidPushing(tag,.014)) ++checksum;
                checksum+=Double.doubleToRawLongBits(entity.getFluidHeight(tag));
                checksum^=Double.doubleToRawLongBits(entity.getDeltaMovement().x);
            }
        }
        sink=checksum;
        return checksum;
    }
}
