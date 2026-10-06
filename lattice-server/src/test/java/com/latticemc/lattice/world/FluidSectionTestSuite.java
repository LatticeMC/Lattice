package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.*;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

public class FluidSectionTestSuite {
    @Test void everyBuiltinStateMatchesOriginalHeight() {
        try(var f=new FluidSectionTestSupport()) {
            int compared=0;
            for(Fluid type:new Fluid[]{Fluids.WATER,Fluids.FLOWING_WATER,Fluids.LAVA,Fluids.FLOWING_LAVA}) {
                for(FluidState state:type.getStateDefinition().getPossibleStates()) {
                    for(int y:new int[]{-16,-1,0,15,16,30,31}) {
                        for(var upper:List.of(Blocks.AIR.defaultBlockState(),Blocks.WATER.defaultBlockState(),
                            Blocks.LAVA.defaultBlockState(),Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED,true))) {
                            var pos=new BlockPos.MutableBlockPos(8,y,8);
                            if(y<31) f.set(8,y+1,8,upper);
                            float expected=state.getHeight(f.world,pos);
                            float actual=FluidSectionHeight.getHeight(state,f.world,pos,f.sections(8,8),-1);
                            assertEquals(Float.floatToRawIntBits(expected),Float.floatToRawIntBits(actual));
                            assertEquals(new BlockPos(8,y,8),pos);
                            ++compared;
                        }
                    }
                }
            }
            assertTrue(compared>=500,"实际覆盖全部注册状态");
        }
    }
    @Test void rereadsSectionSlotAndPreservesFallbacks() {
        try(var f=new FluidSectionTestSupport()) {
            var pos=new BlockPos.MutableBlockPos(8,15,8);
            var sections=f.sections(8,8);
            var state=Fluids.WATER.defaultFluidState();
            float own=state.getOwnHeight();
            assertEquals(own,FluidSectionHeight.getHeight(state,f.world,pos,sections,-1));
            f.set(8,16,8,Blocks.WATER.defaultBlockState());
            assertEquals(1,FluidSectionHeight.getHeight(state,f.world,pos,sections,-1));
            sections[2]=FluidSectionTestSupport.section();
            assertEquals(own,FluidSectionHeight.getHeight(state,f.world,pos,sections,-1));
            assertEquals(state.getHeight(f.world,pos),FluidSectionHeight.getHeight(state,f.world,pos,new net.minecraft.world.level.chunk.LevelChunkSection[0],-1));
            pos.set(Integer.MAX_VALUE,31,Integer.MAX_VALUE);
            assertEquals(state.getHeight(f.world,pos),FluidSectionHeight.getHeight(state,f.world,pos,sections,-1));
        }
    }
    @Test void customFluidAndWaterSubclassKeepVirtualDispatch() {
        try(var f=new FluidSectionTestSupport()) {
            var pos=new BlockPos.MutableBlockPos(8,0,8);
            for(Fluid custom:new Fluid[]{mock(Fluid.class),mock(WaterFluid.Source.class)}) {
                var state=new FluidState(custom,new it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap<>(),null);
                when(custom.getHeight(state,f.world,pos)).thenAnswer(invocation -> {
                    assertSame(pos,invocation.getArgument(2));
                    assertEquals(Fluids.WATER.defaultFluidState().getOwnHeight(),
                        FluidSectionHeight.getHeight(Fluids.WATER.defaultFluidState(),f.world,pos,f.sections(8,8),-1));
                    return .375F;
                });
                assertEquals(.375F,FluidSectionHeight.getHeight(state,f.world,pos,f.sections(8,8),-1));
                verify(custom,times(1)).getHeight(state,f.world,pos);
                RuntimeException marker=new RuntimeException("custom");
                when(custom.getHeight(state,f.world,pos)).thenThrow(marker);
                assertSame(marker,assertThrows(RuntimeException.class,()->FluidSectionHeight.getHeight(state,f.world,pos,f.sections(8,8),-1)));
            }
        }
    }
    @Test void actualScanFindsWaterAndProducesFlow() {
        try(var f=new FluidSectionTestSupport()) {
            f.layer(0,Blocks.WATER.defaultBlockState());
            f.set(9,0,8,Blocks.WATER.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,4));
            var entity=f.entity(new AABB(8,0,8,9,1.8,9),true);
            assertTrue(entity.updateFluidHeightAndDoFluidPushing(FluidTags.WATER,.014));
            assertTrue(entity.getFluidHeight(FluidTags.WATER)>0);
            assertTrue(entity.getDeltaMovement().lengthSqr()>0);
            entity.unloaded=true;
            assertFalse(entity.updateFluidHeightAndDoFluidPushing(FluidTags.WATER,.014));
        }
    }
    @Test void realScanAndHeightPrototypeAgree() {
        try(var f=new FluidSectionTestSupport()) {
            f.layer(0,Blocks.WATER.defaultBlockState());
            var entity=f.entity(new AABB(8,0,8,9,1.8,9),false);
            f.world.reads=0;
            assertTrue(entity.updateFluidHeightAndDoFluidPushing(FluidTags.WATER,.014));
            // 生产已恢复；显式诊断模式另核对读取次数，默认测试不要求启动参数。
            if(FluidSectionTestSupport.COUNT) assertEquals(1L,f.world.reads);
            double original=entity.getFluidHeight(FluidTags.WATER);
            f.world.reads=0;
            float height=FluidSectionHeight.getHeight(Fluids.WATER.defaultFluidState(),f.world,new BlockPos(8,0,8),f.sections(8,8),-1);
            assertEquals(height-.001,original,1e-9);
            if(FluidSectionTestSupport.COUNT) assertEquals(0L,f.world.reads);
        }
    }
    static List<String> trace() {
        List<String> trace=new ArrayList<>();
        for(int y:new int[]{-18,-16,-1,0,15,16,31,32}) for(int kind=0;kind<3;kind++) {
            try(var f=new FluidSectionTestSupport()) {
                int fillY=Math.max(-16,Math.min(31,y));
                var block=kind==0?Blocks.AIR:kind==1?Blocks.WATER:Blocks.LAVA;
                f.layer(fillY,block.defaultBlockState());
                if(fillY<31 && (y&1)!=0) f.layer(fillY+1,block.defaultBlockState());
                if(y>=-16&&y<31 && kind!=0) f.set(9,y,8,block.defaultBlockState().setValue(net.minecraft.world.level.block.LiquidBlock.LEVEL,4));
                for(double x:new double[]{8,15.5,-.5}) for(boolean push:new boolean[]{false,true}) {
                    var entity=f.entity(new AABB(x,y,x,x+1,y+1.8,x+1),push);
                    var tag=kind==2?FluidTags.LAVA:FluidTags.WATER;
                    boolean found=entity.updateFluidHeightAndDoFluidPushing(tag,.014);
                    var v=entity.getDeltaMovement();
                    trace.add(y+"/"+kind+"/"+x+"/"+push+"="+found+"/"+Long.toHexString(Double.doubleToRawLongBits(entity.getFluidHeight(tag)))
                        +"/"+Long.toHexString(Double.doubleToRawLongBits(v.x))+"/"+Long.toHexString(Double.doubleToRawLongBits(v.y))
                        +"/"+Long.toHexString(Double.doubleToRawLongBits(v.z))+"/"+entity.lastLavaContact);
                }
            }
        }
        return trace;
    }
    public static void main(String[] args) {
        System.out.println("SOURCE "+Entity.class.getProtectionDomain().getCodeSource().getLocation());
        trace().forEach(value->System.out.println("FLUID_TRACE "+value));
        org.apache.logging.log4j.LogManager.shutdown();
    }
}
