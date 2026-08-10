package com.latticemc.lattice.util.collection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BrainCollectionTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void activityBitSetTracksDirtyStateAndRegistryOrder() {
        ActivityBitSet set = new ActivityBitSet();
        assertTrue(set.consumeDirty());
        assertFalse(set.consumeDirty());

        set.add(Activity.WORK);
        set.add(Activity.CORE);
        assertTrue(set.consumeDirty());
        assertFalse(set.consumeDirty());
        assertEquals(List.of(Activity.CORE, Activity.WORK), toList(set));

        set.remove(Activity.CORE);
        assertTrue(set.consumeDirty());
        assertFalse(set.contains(Activity.CORE));
        assertEquals(List.of(Activity.WORK), toList(set));
    }

    @Test
    void activityArrayMapPreservesMapSemanticsAndInsertionOrder() {
        ActivityArrayMap<String> map = new ActivityArrayMap<>();
        assertEquals(null, map.put(Activity.WORK, "work"));
        assertEquals(null, map.put(Activity.CORE, "core"));
        assertEquals("work", map.get(Activity.WORK));
        assertEquals("core", map.getValue(Activity.CORE.id));
        assertEquals(List.of(Activity.WORK, Activity.CORE), map.keySet().stream().toList());

        map.computeIfAbsent(Activity.IDLE, ignored -> "idle");
        assertEquals("idle", map.get(Activity.IDLE));
        Map.Entry<Activity, String> entry = map.entrySet().iterator().next();
        entry.setValue("updated");
        assertEquals("updated", map.get(Activity.WORK));
        map.put(Activity.WORK, "live");
        assertEquals("live", entry.getValue());
        assertThrows(NullPointerException.class, () -> map.put(Activity.PLAY, null));

        assertEquals("core", map.remove(Activity.CORE));
        assertFalse(map.containsKey(Activity.CORE));
    }

    @Test
    void activityArrayMapViewsIterateWithoutEntryObjects() {
        ActivityArrayMap<String> map = new ActivityArrayMap<>();
        map.put(Activity.WORK, "work");
        map.put(Activity.CORE, "core");
        map.put(Activity.IDLE, "idle");

        assertSame(map.keySet(), map.keySet());
        assertSame(map.values(), map.values());
        assertEquals(List.of(Activity.WORK, Activity.CORE, Activity.IDLE), toList(map.keySet()));
        assertEquals(List.of("work", "core", "idle"), toList(map.values()));
        assertTrue(map.keySet().contains(Activity.CORE));
        assertFalse(map.keySet().contains(Activity.PLAY));

        java.util.Iterator<String> values = map.values().iterator();
        values.next();
        values.remove();
        assertFalse(map.containsKey(Activity.WORK));
        assertEquals(List.of(Activity.CORE, Activity.IDLE), toList(map.keySet()));

        assertTrue(map.keySet().remove(Activity.CORE));
        assertFalse(map.keySet().remove(Activity.CORE));
        assertEquals(List.of("idle"), toList(map.values()));
    }

    @Test
    void behaviorControlArraySetPresizesWithoutGrowthCopies() {
        BehaviorControl<LivingEntity> first = mock(BehaviorControl.class);
        BehaviorControl<LivingEntity> second = mock(BehaviorControl.class);
        BehaviorControlArraySet<LivingEntity> set = new BehaviorControlArraySet<>(2);
        BehaviorControl<? super LivingEntity>[] raw = set.raw();
        assertEquals(2, raw.length);
        assertTrue(set.add(first));
        assertTrue(set.add(second));
        assertSame(raw, set.raw());

        assertEquals(0, new BehaviorControlArraySet<LivingEntity>(0).raw().length);
        assertEquals(0, new BehaviorControlArraySet<LivingEntity>(-1).raw().length);
    }

    @Test
    void behaviorControlArraySetTracksRunningGroupsWithoutChangingOrder() {
        BehaviorControl<LivingEntity> first = mock(BehaviorControl.class);
        BehaviorControl<LivingEntity> second = mock(BehaviorControl.class);
        BehaviorControlArraySet<LivingEntity> set = new BehaviorControlArraySet<>();
        assertTrue(set.add(first));
        assertTrue(set.add(second));
        assertFalse(set.add(first));
        assertSame(first, set.raw()[0]);
        assertSame(second, set.raw()[1]);
        assertFalse(set.hasRunning());

        set.incrementRunning();
        set.incrementRunning();
        assertTrue(set.hasRunning());
        set.decrementRunning();
        set.decrementRunning();
        assertFalse(set.hasRunning());

        assertTrue(set.remove(first));
        assertSame(second, set.raw()[0]);
        assertEquals(1, set.size());
    }

    @Test
    void removingRunningBehaviorsMaintainsTheGroupCounter() {
        BehaviorControl<LivingEntity> first = mock(BehaviorControl.class);
        BehaviorControl<LivingEntity> second = mock(BehaviorControl.class);
        when(first.getStatus()).thenReturn(net.minecraft.world.entity.ai.behavior.Behavior.Status.RUNNING);
        when(second.getStatus()).thenReturn(net.minecraft.world.entity.ai.behavior.Behavior.Status.RUNNING);
        BehaviorControlArraySet<LivingEntity> set = new BehaviorControlArraySet<>();
        set.add(first);
        set.add(second);
        set.incrementRunning();
        set.incrementRunning();

        assertTrue(set.remove(first));
        assertTrue(set.hasRunning());
        java.util.Iterator<BehaviorControl<? super LivingEntity>> iterator = set.iterator();
        assertSame(second, iterator.next());
        iterator.remove();
        assertFalse(set.hasRunning());
        assertTrue(set.isEmpty());
    }

    private static <T> List<T> toList(Iterable<T> values) {
        List<T> result = new ArrayList<>();
        for (T value : values) {
            result.add(value);
        }
        return result;
    }
}
