package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class NativeEntityQueryParityTestSuite {
    @Test
    void actualJniMatchesJavaOrderedSelectionIncludingNaN() throws Exception {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        Method nativeQuery = Arrays.stream(NativeEntityQuery.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("nativeQuery")).findFirst().orElseThrow();
        nativeQuery.setAccessible(true);
        Random random = new Random(20261003);
        double[] values = {0, -0.0, 1, -1, 3, 1e200, Double.POSITIVE_INFINITY, Double.NaN};
        int[] allowed = {-1, Integer.MIN_VALUE, 2, 2, Integer.MAX_VALUE};
        for (int trial = 0; trial < 500; ++trial) {
            int n = 1 + random.nextInt(128), capacity = 1 + random.nextInt(n + 5);
            int[] ids = new int[n], types = new int[n];
            double[] positions = new double[n * 3], boxes = new double[n * 6];
            boolean[] alive = new boolean[n], spectator = new boolean[n];
            for (int i = 0; i < n; ++i) {
                ids[i] = i * 7 + 1; types[i] = allowed[random.nextInt(allowed.length)];
                positions[i * 3] = trial % 3 == 0 ? values[random.nextInt(values.length)] : random.nextInt(16);
                alive[i] = random.nextBoolean(); spectator[i] = random.nextBoolean();
            }
            int[] actual = new int[capacity], expected = new int[capacity];
            double[] ad = new double[capacity * 2], ed = new double[capacity * 2];
            NativeEntityQuery.PredicateKind predicate = NativeEntityQuery.PredicateKind.values()[trial % 5];
            int max = trial % 5 == 0 ? 0 : 1 + random.nextInt(n + 5);
            int[] filter = trial % 3 == 0 ? allowed : null;
            boolean sort = trial % 7 != 0;
            int want = NativeEntityQuery.javaQuery(0, 0, 0, 10, 10, 10,
                    ids, types, positions, boxes, alive, spectator, filter, predicate, ids[0], sort, max,
                    0, 0, 0, expected, ed);
            int[] beforeIds = ids.clone(), beforeTypes = types.clone(), beforeAllowed = allowed.clone();
            double[] beforePositions = positions.clone(), beforeBoxes = boxes.clone();
            boolean[] beforeAlive = alive.clone(), beforeSpectator = spectator.clone();
            int got = (int) nativeQuery.invoke(null, 0., 0., 0., 10., 10., 10.,
                    ids, types, positions, boxes, alive, spectator, filter, predicate.ordinal(), ids[0], sort, max,
                    0., 0., 0., actual, ad);
            assertEquals(want, got, "trial=" + trial);
            assertArrayEquals(Arrays.copyOf(expected, want), Arrays.copyOf(actual, got));
            if (sort) for (int i = 0; i < got; ++i) {
                assertEquals(Double.doubleToLongBits(ed[i]), Double.doubleToLongBits(ad[i]));
                assertEquals(ed[capacity + i], ad[capacity + i]);
            }
            assertArrayEquals(beforeIds, ids); assertArrayEquals(beforeTypes, types); assertArrayEquals(beforeAllowed, allowed);
            assertArrayEquals(beforePositions, positions); assertArrayEquals(beforeBoxes, boxes);
            assertArrayEquals(beforeAlive, alive); assertArrayEquals(beforeSpectator, spectator);
        }
    }

    @Test
    void reverseSnapshotTiesRetainFirstThenNextCandidate() {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        // GoalQuerySupport 逆序构造 snapshot；平距时较后 ordinal 对应原列表较前实体。
        var snapshots = new NativeEntityQuery.EntitySnapshot[3];
        for (int i = 0; i < 3; ++i) snapshots[i] = new NativeEntityQuery.EntitySnapshot(
                30 - i * 10, 1, 1, 0, 0, 0, 0, 0, 1, 1, 1, true, false);
        int[] result = NativeEntityQuery.query(0, 0, 0, 2, 2, 2, snapshots, null,
                NativeEntityQuery.PredicateKind.IS_ALIVE, -1, true, 3, 0, 0, 0);
        assertArrayEquals(new int[] {10, 20, 30}, result);
        assertEquals(20, Arrays.stream(result).filter(id -> id != 10).findFirst().orElseThrow());
    }
}
