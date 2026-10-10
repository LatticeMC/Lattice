package net.minecraft.world.entity.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.common.collect.ImmutableList;
import com.mojang.serialization.JsonOps;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.ExpirableValue;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 同一个入口也在冻结的优化前 server jar 上运行，避免只验证新实现自身。 */
public class BrainMemoryIndexTestSuite {
    private static Method forget;

    @BeforeAll
    static void bootstrap() throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        forget = Brain.class.getDeclaredMethod("forgetOutdatedMemories");
        forget.setAccessible(true);
    }

    private static Brain<LivingEntity> brain(List<? extends MemoryModuleType<?>> types) {
        return new Brain<>(types, List.of(), ImmutableList.of(), () -> Brain.codec(types, List.of()));
    }

    private static void forget(Brain<?> brain) throws Exception {
        forget.invoke(brain);
    }

    @Test
    void matchesReferenceMapUnderRandomMemoryWrites() throws Exception {
        List<MemoryModuleType<Integer>> types = new ArrayList<>();
        for (int i = 0; i < 145; i++) types.add(new MemoryModuleType<>(Optional.empty()));
        Brain<LivingEntity> brain = brain(types);
        Reference2ReferenceOpenHashMap<MemoryModuleType<?>, Optional<ExpirableValue<Integer>>> oracle = new Reference2ReferenceOpenHashMap<>();
        for (var type : types) oracle.put(type, Optional.empty());
        oracle.trim();
        Random random = new Random(123456789L);
        long checksum = 0L;
        for (int step = 0; step < 4000; step++) {
            var type = types.get(random.nextInt(types.size()));
            int value = random.nextInt(1000);
            switch (random.nextInt(5)) {
                case 0 -> { brain.eraseMemory(type); oracle.put(type, Optional.empty()); }
                case 1 -> { brain.setMemory(type, value); oracle.put(type, Optional.of(ExpirableValue.of(value))); }
                case 2 -> {
                    long ttl = random.nextInt(6) - 1;
                    brain.setMemoryWithExpiry(type, value, ttl);
                    oracle.put(type, Optional.of(ExpirableValue.of(value, ttl)));
                }
                case 3 -> {
                    forget(brain);
                    for (var entry : oracle.entrySet()) {
                        if (entry.getValue().isPresent()) {
                            var expirable = entry.getValue().get();
                            if (expirable.hasExpired()) entry.setValue(Optional.empty());
                            expirable.tick();
                        }
                    }
                }
                case 4 -> {
                    if (step % 79 == 0) {
                        brain.clearMemories();
                        oracle.replaceAll((key, old) -> Optional.empty());
                    }
                }
            }
            for (var key : types) {
                var expected = oracle.get(key);
                assertEquals(expected.map(ExpirableValue::getValue), brain.getMemory(key));
                assertEquals(expected.map(ExpirableValue::getTimeToLive).orElse(0L), brain.getTimeUntilExpiry(key));
                assertEquals(expected.isPresent(), brain.checkMemory(key, MemoryStatus.VALUE_PRESENT));
                assertEquals(expected.isEmpty(), brain.checkMemory(key, MemoryStatus.VALUE_ABSENT));
                assertTrue(brain.checkMemory(key, MemoryStatus.REGISTERED));
                checksum = checksum * 31 + brain.getMemory(key).orElse(-1);
            }
        }
        System.out.println("BRAIN_TRACE random=" + checksum);
    }

    @Test
    void preservesTtlBoundariesAndSharedValues() throws Exception {
        var a = MemoryModuleType.LIKED_NOTEBLOCK_COOLDOWN_TICKS;
        var b = MemoryModuleType.ITEM_PICKUP_COOLDOWN_TICKS;
        for (long ttl : new long[] {Long.MIN_VALUE, -1, 0, 1, 2, Long.MAX_VALUE - 1, Long.MAX_VALUE}) {
            Brain<LivingEntity> brain = brain(List.of(a, b));
            var shared = ExpirableValue.of(7, ttl);
            brain.setMemoryInternal(a, Optional.of(shared));
            brain.setMemoryInternal(b, Optional.of(shared));
            var oracle = new Reference2ReferenceOpenHashMap<MemoryModuleType<?>, Optional<ExpirableValue<Integer>>>();
            var reference = ExpirableValue.of(7, ttl);
            oracle.put(a, Optional.of(reference)); oracle.put(b, Optional.of(reference)); oracle.trim();
            for (int i = 0; i < 4; i++) {
                forget(brain);
                for (var entry : oracle.entrySet()) {
                    if (entry.getValue().isPresent()) {
                        var current = entry.getValue().get();
                        if (current.hasExpired()) entry.setValue(Optional.empty());
                        current.tick();
                    }
                }
                for (var type : List.of(a, b)) {
                    assertEquals(oracle.get(type).map(ExpirableValue::getValue), brain.getMemory(type));
                    assertEquals(oracle.get(type).map(ExpirableValue::getTimeToLive).orElse(0L), brain.getTimeUntilExpiry(type));
                }
                assertEquals(reference.getTimeToLive(), shared.getTimeToLive());
            }
        }
        System.out.println("BRAIN_TRACE ttl=7-boundaries-shared");
    }

    @Test
    void preservesLiveDebugMapAndEveryMutationRoute() throws Exception {
        var a = MemoryModuleType.LIKED_NOTEBLOCK_COOLDOWN_TICKS;
        var b = MemoryModuleType.ITEM_PICKUP_COOLDOWN_TICKS;
        Brain<LivingEntity> brain = brain(List.of(a, b));
        brain.setMemoryWithExpiry(a, 1, 2);
        Map<MemoryModuleType<?>, Optional<? extends ExpirableValue<?>>> map = brain.getMemories();
        assertSame(map, brain.getMemories());
        map.compute(a, (key, old) -> Optional.of(ExpirableValue.of(9, 1)));
        forget(brain);
        assertEquals(0, brain.getTimeUntilExpiry(a)); assertEquals(Optional.of(9), brain.getMemory(a));
        map.entrySet().stream().filter(e -> e.getKey() == b).findFirst().orElseThrow().setValue(Optional.of(ExpirableValue.of(8)));
        assertEquals(Optional.of(8), brain.getMemory(b));
        map.replaceAll((key, old) -> Optional.empty());
        assertEquals(Optional.empty(), brain.getMemory(b));
        map.keySet().remove(a);
        assertNull(brain.getMemoryInternal(a));
        assertThrows(IllegalStateException.class, () -> brain.getMemory(a));
        assertThrows(NullPointerException.class, () -> brain.getTimeUntilExpiry(a));
        brain.setMemory(a, 12); assertFalse(map.containsKey(a));
        map.put(a, Optional.of(ExpirableValue.of(13)));
        assertEquals(Optional.of(13), brain.getMemory(a));
        map.values().removeIf(Optional::isEmpty);
        assertFalse(brain.checkMemory(b, MemoryStatus.REGISTERED));
        var iterator = map.entrySet().iterator(); iterator.next(); iterator.remove();
        assertTrue(map.isEmpty());
        map.put(b, null);
        assertFalse(brain.checkMemory(b, MemoryStatus.REGISTERED));
        map.clear();
        System.out.println("BRAIN_TRACE debug=live-map-views");
    }

    @Test
    void preservesCodecCopyAndEmptyCollections() throws Exception {
        var type = MemoryModuleType.LIKED_NOTEBLOCK_COOLDOWN_TICKS;
        Brain<LivingEntity> original = brain(List.of(type, MemoryModuleType.NEAREST_PLAYERS));
        original.setMemoryWithExpiry(type, 21, 3);
        Brain<LivingEntity> copy = original.copyWithoutBehaviors();
        forget(copy);
        assertEquals(2, original.getTimeUntilExpiry(type));
        original.setMemory(MemoryModuleType.NEAREST_PLAYERS, List.of());
        assertFalse(original.hasMemoryValue(MemoryModuleType.NEAREST_PLAYERS));
        var json = original.serializeStart(JsonOps.INSTANCE).getOrThrow();
        Brain<LivingEntity> decoded = Brain.<LivingEntity>codec(List.of(type), List.of()).parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(Optional.of(21), decoded.getMemory(type));
        assertEquals(2, decoded.getTimeUntilExpiry(type));
        original.clearMemories();
        assertTrue(original.checkMemory(type, MemoryStatus.REGISTERED));
        assertFalse(original.hasMemoryValue(type));
        assertEquals(Optional.of(21), copy.getMemory(type));
        System.out.println("BRAIN_TRACE codec=" + json);
    }

    @Test
    void handlesNewTypesAndNullKeysWithoutChangingRegistration() throws Exception {
        var existing = new MemoryModuleType<Integer>(Optional.empty());
        Brain<LivingEntity> brain = brain(List.of(existing));
        var later = new MemoryModuleType<Integer>(Optional.empty());
        brain.setMemory(later, 4);
        assertNull(brain.getMemoryInternal(later));
        assertFalse(brain.checkMemory(later, MemoryStatus.REGISTERED));
        var map = brain.getMemories();
        map.put(later, Optional.of(ExpirableValue.of(5, 0)));
        forget(brain);
        assertEquals(Optional.empty(), brain.getMemory(later));
        List<MemoryModuleType<?>> withNull = new ArrayList<>();
        withNull.add(existing); withNull.add(null);
        Brain<LivingEntity> nullBrain = brain(withNull);
        nullBrain.setMemoryWithExpiry(null, 6, 1);
        forget(nullBrain);
        assertEquals(Optional.of(6), nullBrain.getMemory(null));
        forget(nullBrain);
        assertEquals(Optional.empty(), nullBrain.getMemory(null));
        System.out.println("BRAIN_TRACE registration=later-and-null");
    }

    @Test
    void enablesRequestedRouteAndPermanentlyDisablesEscapedMap() throws Exception {
        Brain<LivingEntity> brain = brain(List.of(MemoryModuleType.LIKED_NOTEBLOCK_COOLDOWN_TICKS));
        var field = Brain.class.getDeclaredField("memories"); field.setAccessible(true);
        Object map = field.get(brain);
        if (!map.getClass().getSimpleName().equals("BrainMemoryMap")) return; // frozen oracle
        var enabled = map.getClass().getMethod("hasExpiryIndex");
        assertEquals(Boolean.getBoolean("lattice.brainExpiryIndex"), enabled.invoke(map));
        var slots = map.getClass().getDeclaredField("slotsById"); slots.setAccessible(true);
        assertEquals(Boolean.getBoolean("lattice.brainMemoryIndex") || Boolean.getBoolean("lattice.brainExpiryIndex"), slots.get(map) != null);
        brain.getMemories();
        map.getClass().getMethod("initializeIndex").invoke(map);
        assertEquals(false, enabled.invoke(map));
        assertNull(slots.get(map));
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesSensorRegistrationAndSameTickBehaviorReads() {
        var type = MemoryModuleType.LIKED_NOTEBLOCK_COOLDOWN_TICKS;
        List<String> events = new ArrayList<>();
        var level = mock(net.minecraft.server.level.ServerLevel.class);
        var entity = mock(LivingEntity.class);
        var config = mock(io.papermc.paper.configuration.WorldConfiguration.class);
        var rates = mock(io.papermc.paper.configuration.WorldConfiguration.TickRates.class);
        rates.sensor = com.google.common.collect.HashBasedTable.create();
        config.tickRates = rates;
        when(level.paperConfig()).thenReturn(config);
        var sensor = new net.minecraft.world.entity.ai.sensing.Sensor<LivingEntity>(1) {
            @Override public java.util.Set<MemoryModuleType<?>> requires() { return java.util.Set.of(type); }
            @Override protected void doTick(net.minecraft.server.level.ServerLevel world, LivingEntity owner) {
                var brain = owner.getBrain();
                events.add("sensor:" + world.getGameTime() + ":" + brain.getMemory(type).orElse(-1));
                if (world.getGameTime() % 5 == 0) brain.setMemoryWithExpiry(type, (int) world.getGameTime(), 2);
            }
        };
        var sensorType = (net.minecraft.world.entity.ai.sensing.SensorType<net.minecraft.world.entity.ai.sensing.Sensor<LivingEntity>>)
                mock(net.minecraft.world.entity.ai.sensing.SensorType.class);
        when(sensorType.create()).thenReturn(sensor);
        Brain<LivingEntity> brain = new Brain<>(List.of(), List.of(sensorType), ImmutableList.of(), () -> null);
        doReturn(brain).when(entity).getBrain();
        class Control implements net.minecraft.world.entity.ai.behavior.BehaviorControl<LivingEntity> {
            private final String name;
            private net.minecraft.world.entity.ai.behavior.Behavior.Status status = net.minecraft.world.entity.ai.behavior.Behavior.Status.STOPPED;
            Control(String name) { this.name = name; }
            @Override public net.minecraft.world.entity.ai.behavior.Behavior.Status getStatus() { return status; }
            @Override public boolean tryStart(net.minecraft.server.level.ServerLevel world, LivingEntity owner, long time) {
                events.add("try:" + name + ":" + time + ":" + brain.getMemory(type).orElse(-1));
                if (!brain.hasMemoryValue(type)) return false;
                status = net.minecraft.world.entity.ai.behavior.Behavior.Status.RUNNING;
                return true;
            }
            @Override public void tickOrStop(net.minecraft.server.level.ServerLevel world, LivingEntity owner, long time) {
                events.add("tick:" + name + ":" + time);
                if (!brain.hasMemoryValue(type)) doStop(world, owner, time);
            }
            @Override public void doStop(net.minecraft.server.level.ServerLevel world, LivingEntity owner, long time) {
                status = net.minecraft.world.entity.ai.behavior.Behavior.Status.STOPPED;
                events.add("stop:" + name + ":" + time);
            }
            @Override public String debugString() { return name; }
        }
        brain.addActivity(net.minecraft.world.entity.schedule.Activity.IDLE, 0, ImmutableList.of(new Control("first"), new Control("second")));
        brain.useDefaultActivity();
        assertTrue(brain.checkMemory(type, MemoryStatus.REGISTERED));
        for (long time = 0; time < 30; time++) {
            when(level.getGameTime()).thenReturn(time);
            brain.tick(level, entity);
        }
        assertEquals(List.of("sensor:0:-1", "try:first:0:0", "try:second:0:0", "tick:first:0", "tick:second:0"), events.subList(0,5));
        assertTrue(events.contains("sensor:3:-1"));
        System.out.println("BRAIN_TRACE sensor-behavior=" + String.join(";", events));
    }

    @Test
    void preservesVirtualExpirableCallbacksAndBrainOverrides() throws Exception {
        var type = MemoryModuleType.LIKED_NOTEBLOCK_COOLDOWN_TICKS;
        Brain<LivingEntity> brain = brain(List.of(type));
        List<String> events = new ArrayList<>();
        ExpirableValue<Integer> value = new ExpirableValue<>(1, Long.MAX_VALUE) {
            @Override public boolean hasExpired() { events.add("expired"); return false; }
            @Override public void tick() { events.add("tick"); brain.getMemories().clear(); }
        };
        brain.setMemoryInternal(type, Optional.of(value));
        forget(brain);
        assertEquals(List.of("expired", "tick"), events);
        Brain<LivingEntity> derived = new Brain<>(List.of(type), List.of(), ImmutableList.of(), () -> null) {
            @Override public <U> void eraseMemory(MemoryModuleType<U> key) {
                events.add("erase");
                this.getMemories().clear();
                super.eraseMemory(key);
            }
        };
        derived.setMemoryWithExpiry(type, 3, 0); forget(derived);
        assertEquals(List.of("expired", "tick", "erase"), events);
        System.out.println("BRAIN_TRACE virtual=" + events);
    }

    public static void main(String[] args) throws Exception {
        bootstrap();
        var suite = new BrainMemoryIndexTestSuite();
        suite.matchesReferenceMapUnderRandomMemoryWrites();
        suite.preservesTtlBoundariesAndSharedValues();
        suite.preservesLiveDebugMapAndEveryMutationRoute();
        suite.preservesCodecCopyAndEmptyCollections();
        suite.handlesNewTypesAndNullKeysWithoutChangingRegistration();
        suite.enablesRequestedRouteAndPermanentlyDisablesEscapedMap();
        suite.preservesSensorRegistrationAndSameTickBehaviorReads();
        suite.preservesVirtualExpirableCallbacksAndBrainOverrides();
    }
}
