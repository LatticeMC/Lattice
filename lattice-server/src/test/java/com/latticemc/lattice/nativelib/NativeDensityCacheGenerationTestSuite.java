package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class NativeDensityCacheGenerationTestSuite {
    private static Object call(String name, Object... args) throws Exception {
        Method method = Arrays.stream(NativeDensityFunction.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        try { return method.invoke(null, args); }
        catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception cause) throw cause;
            throw error;
        }
    }

    @Test
    void bindingsSurviveFullClearAndCellEpochsButNotTemporaryUnbind() throws Exception {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        long arena = (long) call("nativeCreate"), cache = 0;
        try {
            int root = (int) call("nativeAddCacheAllInCellValue", arena);
            call("nativeSetRoot", arena, root);
            cache = (long) call("nativeCreateCache", arena);
            double[] values = {11, 12, 21, 22};
            call("nativeBindCacheAllInCellArrays", cache, new double[][] {values});
            call("nativeClearCache", cache);
            double[] out = new double[4];
            call("nativeEvaluateInterpolatedColumn", arena, cache, 0., 0., 0., 0, 0, 1, 2, 1, 2, true, null, out);
            assertArrayEquals(values, out);
            values[0] = 31;
            double[] cell = new double[2];
            call("nativeEvaluateCell", arena, cache, 0., 1., 0., 0, 0, 1, 2, null, cell);
            assertArrayEquals(new double[] {31, 12}, cell);
            call("nativeBindCacheAllInCellArrays", cache, null);
            call("nativeEvaluateCell", arena, cache, 0., 1., 0., 0, 0, 1, 2, null, cell);
            assertArrayEquals(new double[] {0, 0}, cell);
            final long cacheHandle = cache;
            assertThrows(IllegalArgumentException.class, () -> call("nativeEvaluateInterpolatedCell",
                    arena, cacheHandle, 0., 1., 0., 0, 0, 0, 0, 1, 2, new double[][] {values}, new double[1]));
            call("nativeEvaluateInterpolatedCell", arena, cache, 0., 1., 0., 0, 0, 0, 0, 1, 2,
                    new double[][] {null}, cell);
            assertArrayEquals(new double[] {0, 0}, cell);
        } finally {
            if (cache != 0) call("nativeDestroyCache", cache);
            call("nativeDestroy", arena);
        }
    }
}
