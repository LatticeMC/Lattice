package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class NativeEntityQueryParityTestSuite {
    @Test
    void unsortedLimitIsIndependentOfOutputCapacity() throws Exception {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        for (int max : new int[] {0, 1, 2, 8}) {
            int expected = max == 1 ? 1 : max == 2 ? 2 : 3;
            for (boolean nativePath : new boolean[] {false, true}) {
                Object[] args = contractArgs(false, max, 4);
                assertEquals(expected, invokeQuery(nativePath, args));
                int[] ids = (int[]) args[20];
                double[] distances = (double[]) args[21];
                assertArrayEquals(Arrays.copyOf(new int[] {11, 22, 33}, expected), Arrays.copyOf(ids, expected));
                for (int i = 0; i < expected; ++i) assertEquals(0.0, distances[i]);
                for (int i = expected; i < ids.length; ++i) {
                    assertEquals(-1, ids[i]);
                    assertEquals(-1.0, distances[i]);
                }
            }
        }
    }

    @Test
    void zeroCapacityAndCapacityBelowLimitAreSafe() throws Exception {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        for (boolean nativePath : new boolean[] {false, true}) {
            for (boolean sorted : new boolean[] {false, true}) {
                assertEquals(0, invokeQuery(nativePath, contractArgs(sorted, 0, 0)));
                Object[] args = contractArgs(sorted, 8, 1);
                assertEquals(1, invokeQuery(nativePath, args));
                assertEquals(sorted ? 22 : 11, ((int[]) args[20])[0]);
            }
        }
    }

    @Test
    void malformedArraysFailBeforeWritingOutputInEitherPath() throws Exception {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        for (boolean nativePath : new boolean[] {false, true}) {
            for (int slot : new int[] {6, 7, 8, 9, 10, 11, 20, 21}) {
                Object[] args = contractArgs(true, 1, 4);
                int[] output = (int[]) args[20];
                args[slot] = null;
                assertThrows(IllegalArgumentException.class, () -> invokeQuery(nativePath, args), "null slot=" + slot);
                assertArrayEquals(new int[] {-1, -1, -1, -1}, output);
            }
            for (int slot : new int[] {7, 8, 9, 10, 11, 21}) {
                Object[] args = contractArgs(true, 1, 4);
                args[slot] = switch (slot) {
                    case 7 -> new int[2];
                    case 8 -> new double[8];
                    case 9 -> new double[17];
                    case 10, 11 -> new boolean[2];
                    default -> new double[7];
                };
                assertThrows(IllegalArgumentException.class, () -> invokeQuery(nativePath, args), "short slot=" + slot);
                assertArrayEquals(new int[] {-1, -1, -1, -1}, (int[]) args[20]);
            }
            Object[] args = contractArgs(false, -1, 4);
            assertThrows(IllegalArgumentException.class, () -> invokeQuery(nativePath, args));
            assertArrayEquals(new int[] {-1, -1, -1, -1}, (int[]) args[20]);
            Object[] optional = contractArgs(false, 1, 4);
            optional[21] = null;
            assertEquals(1, invokeQuery(nativePath, optional));
            Object[] shortOptional = contractArgs(false, 1, 4);
            shortOptional[21] = new double[3];
            assertThrows(IllegalArgumentException.class, () -> invokeQuery(nativePath, shortOptional));
        }
    }

    @Test
    void fullQueryAllowsPostFilteringBeyondTopOneAndOwnsItsResult() {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        var snapshots = new NativeEntityQuery.EntitySnapshot[3];
        for (int i = 0; i < 3; ++i) snapshots[i] = new NativeEntityQuery.EntitySnapshot(
                i + 1, 1, i + 1, 0, 0, 0, 0, 0, 1, 1, 1, true, false);
        int[] topOne = NativeEntityQuery.query(0, 0, 0, 10, 10, 10, snapshots, null,
                NativeEntityQuery.PredicateKind.NONE, -1, true, 1, 0, 0, 0);
        assertEquals(0, Arrays.stream(topOne).filter(id -> id == 3).count());
        int[] all = NativeEntityQuery.queryAll(0, 0, 0, 10, 10, 10, snapshots, null,
                NativeEntityQuery.PredicateKind.NONE, -1, true, 0, 0, 0);
        assertEquals(3, Arrays.stream(all).filter(id -> id == 3).findFirst().orElseThrow());
        NativeEntityQuery.queryAll(0, 0, 0, 10, 10, 10, snapshots, null,
                NativeEntityQuery.PredicateKind.NONE, -1, true, 9, 0, 0);
        assertArrayEquals(new int[] {1, 2, 3}, all);
    }

    private static Object[] contractArgs(boolean sorted, int max, int capacity) {
        int[] output = new int[capacity];
        double[] distances = new double[capacity * (sorted ? 2 : 1)];
        Arrays.fill(output, -1);
        Arrays.fill(distances, -1.0);
        return new Object[] {0., 0., 0., 10., 10., 10.,
                new int[] {11, 22, 33}, new int[] {1, 1, 1},
                new double[] {3, 0, 0, 1, 0, 0, 2, 0, 0}, new double[18],
                new boolean[] {true, true, true}, new boolean[3], null,
                NativeEntityQuery.PredicateKind.NONE, -1, sorted, max,
                0., 0., 0., output, distances};
    }

    private static int invokeQuery(boolean nativePath, Object[] args) throws Exception {
        String name = nativePath ? "nativeQuery" : "javaQuery";
        Method method = Arrays.stream(NativeEntityQuery.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        Object[] callArgs = args.clone();
        if (nativePath) callArgs[13] = ((NativeEntityQuery.PredicateKind) args[13]).ordinal();
        try {
            return (int) method.invoke(null, callArgs);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception cause) throw cause;
            throw e;
        }
    }

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
