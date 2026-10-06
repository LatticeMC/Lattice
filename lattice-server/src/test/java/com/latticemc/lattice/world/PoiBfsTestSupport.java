package com.latticemc.lattice.world;

import io.papermc.paper.util.PoiAccess;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.*;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.*;
import net.minecraft.world.level.Level;

/** Mock 边界仅为 POI section 存储/IO；被测 BFS、记录过滤及排序均为真实方法。 */
final class PoiBfsTestSupport {
    static final Holder<PoiType> TYPE = Holder.direct(new PoiType(Set.of(), 2, 16));
    static final Holder<PoiType> OTHER = Holder.direct(new PoiType(Set.of(), 3, 16));
    static class Manager extends PoiManager {
        Long2ObjectOpenHashMap<Optional<PoiSection>> data;
        List<String> trace;
        private Manager() { super(null,null,null,false,null,null,null); }
        @Override public Optional<PoiSection> get(long key) {
            if(trace!=null)trace.add("get:"+key);
            return data.get(key);
        }
        @Override public Optional<PoiSection> getOrLoad(long key) {
            if(trace!=null)trace.add("load:"+key);
            return data.get(key);
        }
    }
    static Manager manager() {
        var world=RandomTickTestSupport.allocate(FluidSectionTestSupport.World.class);
        for(var e:Map.of("minY",-64,"height",384,"maxY",319,"minSectionY",-4,"maxSectionY",19,"sectionsCount",24).entrySet())
            RandomTickTestSupport.field(world,Level.class,e.getKey(),e.getValue());
        Manager manager=RandomTickTestSupport.allocate(Manager.class);
        RandomTickTestSupport.field(manager,PoiManager.class,"world",world);
        manager.data=new Long2ObjectOpenHashMap<>();
        manager.data.defaultReturnValue(Optional.empty());
        return manager;
    }
    static PoiRecord add(Manager manager,int x,int y,int z,Holder<PoiType> type,int free) {
        var pos=new BlockPos(x,y,z);
        long key=SectionPos.asLong(pos);
        Optional<PoiSection> section=manager.data.get(key);
        if(section==null||section.isEmpty()) {
            section=Optional.of(new PoiSection(()->{}));manager.data.put(key,section);
        }
        PoiRecord record=new PoiRecord(pos,type,()->{});
        RandomTickTestSupport.field(record,PoiRecord.class,"freeTickets",free);
        section.get().getData().computeIfAbsent(type,k->new LinkedHashSet<>()).add(record);
        return record;
    }
    static List<PoiRecord> query(Manager manager,boolean nearest,BlockPos pos,int radius,double limit,
                                PoiManager.Occupancy occupancy,boolean load,int max,
                                Predicate<Holder<PoiType>> type,Predicate<BlockPos> predicate,List<PoiRecord> result) {
        if(nearest) PoiAccess.findNearestPoiRecords(manager,type,predicate,pos,radius,limit,occupancy,load,max,result);
        else PoiAccess.findClosestPoiDataRecords(manager,type,
            predicate==null?(BiPredicate<Holder<PoiType>,BlockPos>)null:(t,p)->predicate.test(p),pos,radius,limit,occupancy,load,result);
        return result;
    }
    static List<String> positions(List<PoiRecord> records) {
        return records.stream().map(r->r.getPos().asLong()+":"+(r.getPoiType()==TYPE?"A":"B")).toList();
    }
}
