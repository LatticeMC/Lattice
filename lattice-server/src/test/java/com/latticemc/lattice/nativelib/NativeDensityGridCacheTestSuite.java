package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import java.lang.ref.Reference;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.CubicSpline;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NativeDensityGridCacheTestSuite {
    @BeforeAll static void bootstrap() {
        System.setProperty("lattice.nativeDensityFunctionGrid", "true");
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LatticeNative.load();
        assertTrue(LatticeNative.isLoaded(), LatticeNative.failureReason());
    }

    @BeforeEach void reset() {
        NativeDensityFunction.setOption("enabled", true);
        NativeDensityFunction.setOption("gridCompileCache", true);
        NativeDensityFunction.setOption("stats", true);
        NativeDensityFunction.setOption("executionStats", false);
        NativeDensityFunction.resetStats();
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target instanceof Class<?> c ? c : target.getClass();
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target instanceof Class<?> ? null : target);
    }

    private static Object call(String name, Object... args) throws Exception {
        Method method = Arrays.stream(NativeDensityFunction.class.getDeclaredMethods())
            .filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private static long counter(String name) throws Exception {
        return ((LongAdder) field(NativeDensityFunction.class, name)).sum();
    }

    private static Object compile(DensityFunction function) throws Exception {
        return call("tryCompileGrid", function);
    }

    private static DensityFunction gradient() { return DensityFunctions.yClampedGradient(-64, 320, -1, 1); }

    private static DensityFunction chunkNode(String name, Object... args) throws Exception {
        var constructor = Class.forName("net.minecraft.world.level.levelgen.NoiseChunk$" + name).getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return (DensityFunction) constructor.newInstance(args);
    }

    @Test void rawFloatingBitsAndDagSharingArePartOfKey() {
        assertNotEquals(NativeDensityGridKey.create(DensityFunctions.constant(0.0)),
            NativeDensityGridKey.create(DensityFunctions.constant(-0.0)));
        assertNotEquals(NativeDensityGridKey.create(DensityFunctions.constant(Double.longBitsToDouble(0x7ff8000000000001L))),
            NativeDensityGridKey.create(DensityFunctions.constant(Double.longBitsToDouble(0x7ff8000000000002L))));
        DensityFunction shared = gradient();
        assertNotEquals(NativeDensityGridKey.create(DensityFunctions.add(shared, shared)),
            NativeDensityGridKey.create(DensityFunctions.add(gradient(), gradient())));
        assertEquals(NativeDensityGridKey.create(DensityFunctions.add(gradient(), gradient())),
            NativeDensityGridKey.create(DensityFunctions.add(gradient(), gradient())));
        assertNotEquals(NativeDensityGridKey.create(DensityFunctions.blendAlpha()),
            NativeDensityGridKey.create(DensityFunctions.blendOffset()));
    }

    @Test void unknownAndExternalStateNeverBecomeKeys() {
        assertNull(NativeDensityGridKey.create(mock(DensityFunction.class)));
        assertNull(NativeDensityGridKey.create(DensityFunctions.interpolated(gradient())));
        assertNull(NativeDensityGridKey.create(DensityFunctions.cacheAllInCell(gradient())));
        assertNull(NativeDensityGridKey.create(DensityFunctions.blendDensity(gradient())));
    }

    @Test void dynamicBlendIsRejectedBeforeMapAllCanHideIt() throws Exception {
        for (String name : List.of("BlendAlpha", "BlendOffset")) {
            DensityFunction dynamic = chunkNode(name, (Object) null);
            assertNull(NativeDensityGridKey.create(dynamic));
            assertNull(NativeDensityGridKey.create(chunkNode("Cache2D", dynamic)));
            assertNotNull(NativeDensityGridKey.create(dynamic.mapAll(value -> value)));
        }
        for (String name : List.of("CacheAllInCell", "NoiseInterpolator")) {
            var type = Class.forName("net.minecraft.world.level.levelgen.NoiseChunk$" + name).asSubclass(DensityFunction.class);
            assertNull(NativeDensityGridKey.create(mock(type)));
        }
    }

    @Test void wrapperStructureReusesWithoutSharingSourceStateOrNegativeCache() throws Exception {
        DensityFunction first = chunkNode("Cache2D", gradient());
        DensityFunction second = chunkNode("Cache2D", gradient());
        assertNotEquals(first, second);
        assertEquals(NativeDensityGridKey.create(first), NativeDensityGridKey.create(second));
        @SuppressWarnings("unchecked") Map<DensityFunction, Boolean> failures =
            (Map<DensityFunction, Boolean>) field(NativeDensityFunction.class, "FAILED_COMPILES");
        failures.put(first, Boolean.TRUE);
        assertNull(compile(first));
        Object compiled = compile(second);
        assertNotNull(compiled);
        assertSame(compiled, compile(chunkNode("Cache2D", gradient())));
        for (int y : new int[] {-64, 320, 128}) {
            double[] out = new double[1];
            assertTrue(NativeDensityFunction.tryFillGrid(out, second, 0, y, 0, 1, 1, 1, 0, 0, 1, 1, 1));
            assertEquals(gradient().compute(new DensityFunction.SinglePointContext(0, y, 0)), out[0]);
        }
    }

    @Test void splineKeyCopiesArraysAndPreservesFloatBits() {
        var coordinate = new DensityFunctions.Spline.Coordinate(Holder.direct(gradient()));
        float[] locations = {-1, 1};
        float[] derivatives = {0, 0};
        var values = List.<CubicSpline<DensityFunctions.Spline.Point, DensityFunctions.Spline.Coordinate>>of(
            new CubicSpline.Constant<>(-1), new CubicSpline.Constant<>(1));
        DensityFunction function = DensityFunctions.spline(new CubicSpline.Multipoint<>(coordinate, locations, values, derivatives, -1, 1));
        NativeDensityGridKey original = NativeDensityGridKey.create(function);
        assertNotNull(original);
        int hash = original.hashCode();
        derivatives[0] = -0.0f;
        assertNotEquals(original, NativeDensityGridKey.create(function));
        assertEquals(hash, original.hashCode());
        derivatives[0] = 0;
        assertEquals(original, NativeDensityGridKey.create(function));
        derivatives[0] = Float.intBitsToFloat(0x7fc00001);
        NativeDensityGridKey nan = NativeDensityGridKey.create(function);
        derivatives[0] = Float.intBitsToFloat(0x7fc00002);
        assertNotEquals(nan, NativeDensityGridKey.create(function));
    }

    @Test void hitEvictionAndEpochKeepRetainedArenaUsable() throws Exception {
        Object original = compile(gradient());
        assertNotNull(original);
        assertSame(original, compile(gradient()));
        assertEquals(1, counter("COMPILE_ATTEMPTS"));
        for (int i = 0; i < 65; i++) assertNotNull(compile(DensityFunctions.constant(i + 10)));
        Object cache = ((ThreadLocal<?>) field(NativeDensityFunction.class, "GRID_COMPILE_CACHE")).get();
        assertEquals(64, ((Map<?, ?>) field(cache, "entries")).size());
        assertEquals(2, counter("GRID_TEMPLATE_EVICTIONS"));
        assertNotSame(original, compile(gradient()));
        NativeDensityFunction.setOption("gridCompileCache", true);
        Object afterReset = compile(gradient());
        assertNotSame(original, afterReset);
        double[] out = new double[3];
        call("nativeEvaluateGrid", field(original, "handle"), field(original, "cacheHandle"),
            0., -64., 0., 1., 192., 1., 0, 0, 1, 3, 1, out);
        assertArrayEquals(new double[] {-1, 0, 1}, out);
        Reference.reachabilityFence(original);
    }

    @Test void threadOwnsItsCacheAndObservesForeignEpochReset() throws Exception {
        Object main = compile(gradient());
        try (var executor = Executors.newSingleThreadExecutor()) {
            Object other = executor.submit(() -> compile(gradient())).get();
            assertNotSame(main, other);
            assertNotEquals(field(main, "cacheHandle"), field(other, "cacheHandle"));
            NativeDensityFunction.setOption("gridCompileCache", true);
            Object next = executor.submit(() -> compile(gradient())).get();
            assertNotSame(other, next);
            assertSame(next, executor.submit(() -> compile(gradient())).get());
        }
    }

    @Test void epochChangeDuringMissDoesNotPublishOldCompilation() throws Exception {
        Object lock = field(NativeDensityFunction.class, "CACHE");
        var worker = new AtomicReference<Thread>();
        var started = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.Future<Object> pending;
            synchronized (lock) {
                pending = executor.submit(() -> {
                    worker.set(Thread.currentThread());
                    started.countDown();
                    Object compiled = compile(gradient());
                    Object cache = ((ThreadLocal<?>) field(NativeDensityFunction.class, "GRID_COMPILE_CACHE")).get();
                    return new Object[] {compiled, ((Map<?, ?>) field(cache, "entries")).size()};
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                boolean blockedAtCache = false;
                while (System.nanoTime() < deadline) {
                    var info = ManagementFactory.getThreadMXBean().getThreadInfo(worker.get().threadId());
                    if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                        && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(lock)) {
                        blockedAtCache = true;
                        break;
                    }
                    Thread.yield();
                }
                assertTrue(blockedAtCache, "worker must have captured its epoch before the reset");
                NativeDensityFunction.setOption("gridCompileCache", true);
            }
            Object[] result = (Object[]) pending.get();
            Object oldEpoch = result[0];
            assertNotNull(oldEpoch);
            assertEquals(0, result[1], "old result must not be published before any subsequent epoch synchronization");
            Object current = executor.submit(() -> compile(gradient())).get();
            assertNotSame(oldEpoch, current);
            assertSame(current, executor.submit(() -> compile(gradient())).get());
        }
    }

    @Test void preservesExistingFailureMapAndSentinel() throws Exception {
        // 保留既有 WeakHashMap 的 equals 语义，不改变原失败缓存契约。
        DensityFunction rejected = gradient();
        @SuppressWarnings("unchecked") Map<DensityFunction, Boolean> failures =
            (Map<DensityFunction, Boolean>) field(NativeDensityFunction.class, "FAILED_COMPILES");
        failures.put(rejected, Boolean.TRUE);
        assertNull(compile(rejected));
        assertNull(compile(rejected));
        assertEquals(0, counter("COMPILE_ATTEMPTS"));
        assertNull(compile(gradient()));
        assertNotNull(compile(DensityFunctions.constant(42)));
        assertEquals(1, counter("COMPILE_ATTEMPTS"));
    }

    @Test void statsWindowRetainsEvictedOwnersUntilSnapshot() throws Exception {
        NativeDensityFunction.setOption("executionStats", true);
        NativeDensityFunction.beginExecutionStatsSample();
        Object sample = ((ThreadLocal<?>) field(NativeDensityFunction.class, "EXECUTION_STATS_SAMPLE")).get();
        for (int i = 0; i < 70; i++) {
            double[] out = new double[1];
            assertTrue(NativeDensityFunction.tryFillGrid(out, DensityFunctions.constant(i + 10),
                0, 0, 0, 1, 1, 1, 0, 0, 1, 1, 1));
            assertEquals(i + 10., out[0]);
        }
        assertEquals(70, ((Map<?, ?>) field(sample, "owners")).size());
        NativeDensityFunction.setOption("gridCompileCache", true);
        assertEquals(70, ((Map<?, ?>) field(sample, "owners")).size());
        // 确定性验证强引用，无须依赖 System.gc 的时序。
        assertEquals(70, NativeDensityFunction.finishExecutionStatsSample().compiledGridCalls());
    }

    @Test void noiseIdentityIsStrongAndDifferentSamplersDoNotAlias() throws Exception {
        var params = Holder.direct(new NormalNoise.NoiseParameters(-3, 1.0));
        NormalNoise noise = NormalNoise.create(new LegacyRandomSource(1), params.value());
        DensityFunction source = DensityFunctions.noise(params);
        DensityFunction function = source.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction value) { return value; }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) {
                return new DensityFunction.NoiseHolder(value.noiseData(), noise);
            }
        });
        NativeDensityGridKey key = NativeDensityGridKey.create(function);
        assertNotNull(key);
        List<?> parts = (List<?>) field(key, "parts");
        Object identity = parts.stream().filter(p -> p.getClass().getSimpleName().equals("Identity")).findFirst().orElseThrow();
        assertSame(noise, field(identity, "value"));
        DensityFunction different = source.mapAll(new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction value) { return value; }
            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder value) {
                return new DensityFunction.NoiseHolder(value.noiseData(), NormalNoise.create(new LegacyRandomSource(2), params.value()));
            }
        });
        assertNotEquals(key, NativeDensityGridKey.create(different));
        assertNotNull(compile(function));
    }
}
