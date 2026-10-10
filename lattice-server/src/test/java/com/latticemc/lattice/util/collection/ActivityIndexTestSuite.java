package com.latticemc.lattice.util.collection;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ActivityIndexTestSuite {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void randomMutationsAndViewRemovalMatchInsertionOrderedMap() {
        ActivityArrayMap<Integer> actual = new ActivityArrayMap<>();
        Map<Activity, Integer> expected = new LinkedHashMap<>();
        Random random = new Random(428731);
        for (int step = 0; step < 10000; step++) {
            Activity key = ActivityRegistryIndex.byId(random.nextInt(ActivityRegistryIndex.SIZE));
            int value = random.nextInt(8);
            switch (random.nextInt(8)) {
                case 0, 1, 2 -> assertEquals(expected.put(key, value), actual.put(key, value));
                case 3 -> assertEquals(expected.remove(key), actual.remove(key));
                case 4 -> assertEquals(expected.keySet().remove(key), actual.keySet().remove(key));
                case 5 -> assertEquals(expected.values().remove(value), actual.values().remove(value));
                case 6 -> {
                    if (!expected.isEmpty()) {
                        var a = actual.entrySet().iterator(); var e = expected.entrySet().iterator();
                        int skip = random.nextInt(expected.size());
                        for (int i = 0; i <= skip; i++) assertEquals(e.next(), a.next());
                        a.remove(); e.remove();
                    }
                }
                case 7 -> { if (random.nextInt(10) == 0) { actual.clear(); expected.clear(); } }
            }
            assertEquals(expected, actual);
            assertEquals(new ArrayList<>(expected.keySet()), new ArrayList<>(actual.keySet()));
            assertEquals(new ArrayList<>(expected.values()), new ArrayList<>(actual.values()));
            for (int id = 0; id < ActivityRegistryIndex.SIZE; id++) assertEquals(expected.get(ActivityRegistryIndex.byId(id)), actual.getValue(id));
            for (int invalid : new int[]{-1, 32, Integer.MIN_VALUE, Integer.MAX_VALUE}) assertNull(actual.getValue(invalid));
        }
    }

    @Test void savedEntriesFollowRemoveAndReinsert() {
        ActivityArrayMap<Integer> map = new ActivityArrayMap<>();
        map.put(Activity.CORE, 1); map.put(Activity.IDLE, 2); map.put(Activity.WORK, 3);
        var entry = map.entrySet().iterator().next();
        map.keySet().remove(Activity.CORE); assertNull(entry.getValue());
        assertNull(entry.setValue(4)); assertEquals(4, entry.getValue());
        assertEquals(java.util.List.of(Activity.IDLE, Activity.WORK, Activity.CORE), new ArrayList<>(map.keySet()));
        map.values().clear(); assertTrue(map.isEmpty());
    }
}
