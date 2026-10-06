package com.latticemc.lattice.world;

import static org.junit.jupiter.api.Assertions.*;

import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.ChunkEntitySlices;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

/** 调用真实 slice 及实体维护方法；世界/实体构造与被测索引无关，使用无 IO 夹具。 */
public class EntityClassLookupTestSuite {
    static class Base extends Entity {
        int testId;
        Base() { super(EntityType.PIG, null); }
        @Override public int getId() { return testId; }
        @Override public EntityType<?> getType() { return EntityType.PIG; }
        @Override protected void defineSynchedData(SynchedEntityData.Builder builder) { }
        @Override public boolean hurtServer(ServerLevel world, DamageSource source, float amount) { return false; }
        @Override protected void readAdditionalSaveData(ValueInput input) { }
        @Override protected void addAdditionalSaveData(ValueOutput output) { }
    }
    static class A extends Base { }
    static class AA extends A { }
    static class B extends Base { }
    static class C extends Base { }
    static class D extends Base { }
    static class E extends Base { }
    static class F extends Base { }
    static class G extends Base { }
    static final AABB ALL = new AABB(-32, -64, -32, 32, 64, 32);
    static final Class<? extends Entity>[] TYPES = new Class[] {
        A.class, B.class, C.class, D.class, E.class, F.class, G.class, AA.class
    };
    static ChunkEntitySlices slice(int x) {
        return new ChunkEntitySlices(null, x, 0, FullChunkStatus.ENTITY_TICKING, null, -4, 3);
    }
    static <T extends Base> T entity(Class<T> type, int id, int y) {
        T entity = RandomTickTestSupport.allocate(type);
        entity.testId = id;
        RandomTickTestSupport.field(entity, Entity.class, "bb", new AABB(0, y, 0, 1, y + 1, 1));
        return entity;
    }
    static List<Integer> ids(List<? extends Entity> entities) {
        return entities.stream().map(Entity::getId).toList();
    }
    static List<Entity> query(ChunkEntitySlices slice, Class<? extends Entity> type) {
        List<Entity> result = new ArrayList<>();
        slice.getEntities(type, null, ALL, result, null);
        return result;
    }
    static List<String> trace() {
        List<String> trace = new ArrayList<>();
        ChunkEntitySlices s = slice(0), other = slice(1);
        Base a = entity(A.class, 1, 0), aa = entity(AA.class, 2, 18), b = entity(B.class, 3, 0);
        trace.add("empty=" + ids(query(s, A.class)));
        s.addEntity(a, 0); s.addEntity(aa, 1); s.addEntity(b, 0);
        for (Class<? extends Entity> type : new Class[] {Entity.class, Base.class, A.class, AA.class, B.class, C.class}) {
            trace.add(type.getSimpleName() + "=" + ids(query(s, type)));
        }
        for (int max : new int[] {0, 1, 2, 5}) {
            List<Entity> result = new ArrayList<>(List.of(b));
            List<Integer> called = new ArrayList<>();
            boolean full = s.getEntities(Base.class, b, ALL, result, e -> {called.add(e.getId()); return true;}, max);
            trace.add("limited/" + max + "=" + full + "/" + ids(result) + "/" + called);
        }
        List<Entity> filtered = new ArrayList<>();
        s.getEntities(Base.class, b, new AABB(-2, -2, -2, 2, 2, 2), filtered, e -> true);
        trace.add("box=" + ids(filtered));
        List<Integer> calls = new ArrayList<>();
        List<Entity> nested = new ArrayList<>();
        s.getEntities(Base.class, null, ALL, nested, e -> {
            calls.add(e.getId());
            for (Class<? extends Entity> type : TYPES) query(s, type);
            return e.getId() != 3;
        });
        trace.add("nested=" + ids(nested) + "/" + calls);
        RuntimeException marker = new RuntimeException("marker");
        try { s.getEntities(A.class, null, ALL, new ArrayList<>(), e -> {throw marker;}); }
        catch (RuntimeException error) { trace.add("exception=" + (error == marker)); }
        s.removeEntity(a, 0); s.removeEntity(aa, 1);
        trace.add("removed=" + ids(query(s, A.class)));
        s.addEntity(a, -1);
        trace.add("readd=" + ids(query(s, A.class)));
        s.removeEntity(a, -1); other.addEntity(a, 0);
        trace.add("move=" + ids(query(s, A.class)) + "/" + ids(query(other, A.class)));
        ChunkEntitySlices replacement = slice(0);
        replacement.addEntity(aa, 1);
        trace.add("replace=" + ids(query(replacement, A.class)));
        // 原扫描以入口长度为上界；predicate 内的维护不能被新入口改变。
        ChunkEntitySlices changing = slice(2);
        Base c = entity(A.class, 4, 0), d = entity(A.class, 5, 0), e = entity(A.class, 6, 0);
        changing.addEntity(c, 0); changing.addEntity(d, 0);
        List<Entity> changed = new ArrayList<>();
        changing.getEntities(A.class, null, ALL, changed, value -> {
            if (value == c) { changing.removeEntity(d, 0); changing.addEntity(e, 0); query(changing, B.class); }
            return true;
        });
        trace.add("mutation=" + ids(changed) + "/" + ids(query(changing, A.class)));
        return trace;
    }
    @Test void actualCollectionSemantics() {
        List<String> trace = trace();
        assertTrue(trace.contains("empty=[]"));
        assertTrue(trace.contains("A=[1, 2]"));
        assertTrue(trace.contains("AA=[2]"));
        assertTrue(trace.contains("nested=[1, 2]/[1, 3, 2]"));
        assertTrue(trace.contains("exception=true"));
        assertTrue(trace.contains("removed=[]"));
        assertTrue(trace.contains("move=[]/[1]"));
        assertTrue(trace.contains("mutation=[4, 6]/[4, 6]"));
    }
    @Test void repeatedAndConcurrentReadOnlyQueries() throws Exception {
        ChunkEntitySlices s = slice(0);
        for (int i=0;i<TYPES.length;i++) s.addEntity(entity((Class<? extends Base>)TYPES[i], i+1, 0), 0);
        List<List<Integer>> expected = new ArrayList<>();
        for (Class<? extends Entity> type:TYPES) expected.add(ids(query(s,type)));
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = () -> {
            try {
                for (int i=0;i<20000;i++) assertEquals(expected.get(i&7), ids(query(s,TYPES[i&7])));
            } catch (Throwable error) { failure.compareAndSet(null,error); }
        };
        Thread first=new Thread(task), second=new Thread(task);
        first.start(); second.start(); first.join(); second.join();
        if (failure.get()!=null) throw new AssertionError(failure.get());
    }
    public static void main(String[] args) {
        System.out.println("SOURCE " + ChunkEntitySlices.class.getProtectionDomain().getCodeSource().getLocation());
        trace().forEach(value -> System.out.println("CLASS_TRACE " + value));
        org.apache.logging.log4j.LogManager.shutdown();
    }
}
