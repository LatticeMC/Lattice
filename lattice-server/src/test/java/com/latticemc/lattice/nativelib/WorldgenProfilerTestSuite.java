package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;
import java.net.URLClassLoader;
import java.util.List;
import org.junit.jupiter.api.Test;

class WorldgenProfilerTestSuite {
    @Test
    void resetRetainsCachedProbeAndSnapshotsAreIndependent() throws Exception {
        String previous = System.getProperty("lattice.worldgenProfilerAvailable");
        System.setProperty("lattice.worldgenProfilerAvailable", "true");
        // 独立初始化 AVAILABLE，不依赖同一 test worker 中其它测试的加载顺序。
        try (var loader = new URLClassLoader(new java.net.URL[] {
                WorldgenProfiler.class.getProtectionDomain().getCodeSource().getLocation()
        }, ClassLoader.getPlatformClassLoader())) {
            var profiler = loader.loadClass(WorldgenProfiler.class.getName());
            assertEquals(true, profiler.getMethod("available").invoke(null));
            var reset = profiler.getMethod("reset");
            var snapshot = profiler.getMethod("snapshot");
            var cached = profiler.getField("NOISE_UPDATE_FOR_Y").get(null);
            var end = profiler.getMethod("end", cached.getClass(), long.class);
            reset.invoke(null);
            end.invoke(null, cached, System.nanoTime() - 1000);
            var before = (List<?>) snapshot.invoke(null);
            assertEquals(1L, count(before));
            reset.invoke(null);
            assertEquals(0L, count((List<?>) snapshot.invoke(null)));
            assertEquals(1L, count(before));
            end.invoke(null, cached, System.nanoTime() - 1000);
            assertEquals(1L, count((List<?>) snapshot.invoke(null)));
            assertThrows(UnsupportedOperationException.class, before::clear);
        } finally {
            if (previous == null) System.clearProperty("lattice.worldgenProfilerAvailable");
            else System.setProperty("lattice.worldgenProfilerAvailable", previous);
        }
    }

    private static long count(List<?> samples) throws Exception {
        for (Object sample : samples) {
            if (sample.getClass().getMethod("name").invoke(sample).equals("noise.updateForY")) {
                return (long) sample.getClass().getMethod("count").invoke(sample);
            }
        }
        throw new AssertionError("cached probe disappeared from snapshot");
    }
}
