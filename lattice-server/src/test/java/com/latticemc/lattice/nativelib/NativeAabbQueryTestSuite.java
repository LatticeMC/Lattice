package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class NativeAabbQueryTestSuite {
    @Test
    void soaScanMatchesTheAoSReferenceAcrossBitmapWords() {
        final int entityCount = 130;
        final int queryCount = 3;
        double[] queries = {
            0.0, 0.0, 0.0, 1.0, 1.0, 1.0,
            15.0, 0.0, 0.0, 16.0, 1.0, 1.0,
            130.0, 0.0, 0.0, 131.0, 1.0, 1.0,
        };
        double[] aos = new double[entityCount * NativeAabbQuery.AABB_STRIDE];
        double[] soa = new double[aos.length];
        for (int entity = 0; entity < entityCount; ++entity) {
            int base = entity * NativeAabbQuery.AABB_STRIDE;
            aos[base] = entity;
            aos[base + 1] = 0.0;
            aos[base + 2] = 0.0;
            aos[base + 3] = entity + 0.5;
            aos[base + 4] = 1.0;
            aos[base + 5] = 1.0;
            for (int component = 0; component < NativeAabbQuery.AABB_STRIDE; ++component) {
                soa[component * entityCount + entity] = aos[base + component];
            }
        }

        long[] expected = new long[queryCount * NativeAabbQuery.rowLongs(entityCount)];
        long[] actual = new long[expected.length];
        NativeAabbQuery.javaScan(queries, queryCount, aos, entityCount, expected);
        NativeAabbQuery.javaScanSoa(queries, queryCount, soa, entityCount, actual);

        assertArrayEquals(expected, actual);

        int stride = entityCount + 7;
        double[] strided = new double[stride * NativeAabbQuery.AABB_STRIDE];
        for (int entity = 0; entity < entityCount; ++entity) {
            for (int component = 0; component < NativeAabbQuery.AABB_STRIDE; ++component) {
                strided[component * stride + entity] = aos[entity * NativeAabbQuery.AABB_STRIDE + component];
            }
        }
        NativeAabbQuery.javaScanSoa(queries, queryCount, strided, entityCount, stride, actual);
        assertArrayEquals(expected, actual);
    }
}
