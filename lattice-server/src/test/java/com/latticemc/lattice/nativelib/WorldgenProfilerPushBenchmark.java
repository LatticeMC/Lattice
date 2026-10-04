package com.latticemc.lattice.nativelib;

import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.util.profiling.ProfilerFiller;

/** Measures eager profiler-name construction against the lazy inactive-profiler overload. */
public final class WorldgenProfilerPushBenchmark {
    private static volatile int blackhole;

    private WorldgenProfilerPushBenchmark() {}

    public static void main(String[] args) {
        int warmup = value(args, "--warmup=", 5);
        int samples = value(args, "--samples=", 11);
        int iterations = value(args, "--iterations=", 2_000_000);
        ProfilerFiller profiler = InactiveProfiler.INSTANCE;
        World world = new World();
        String dimension = "minecraft:overworld";

        for (int i = 0; i < warmup; ++i) {
            eager(profiler, world, dimension, iterations);
            lazy(profiler, world, dimension, iterations);
        }
        long[] eager = new long[samples];
        long[] lazy = new long[samples];
        for (int i = 0; i < samples; ++i) {
            eager[i] = timed(() -> eager(profiler, world, dimension, iterations));
            lazy[i] = timed(() -> lazy(profiler, world, dimension, iterations));
        }
        java.util.Arrays.sort(eager);
        java.util.Arrays.sort(lazy);
        System.out.printf(Locale.ROOT, "profiler-push benchmark profiler=InactiveProfiler iterations=%d samples=%d eager-p50-ns=%.3f lazy-p50-ns=%.3f speedup=%.3f blackhole=%d%n",
            iterations, samples, eager[samples / 2] / (double)iterations, lazy[samples / 2] / (double)iterations,
            eager[samples / 2] / (double)lazy[samples / 2], blackhole);
    }

    private static long timed(Runnable action) {
        long start = System.nanoTime();
        action.run();
        return System.nanoTime() - start;
    }

    private static void eager(ProfilerFiller profiler, World world, String dimension, int iterations) {
        int checksum = blackhole;
        for (int i = 0; i < iterations; ++i) {
            String name = world.toString() + " " + dimension;
            profiler.push(name);
            profiler.pop();
            checksum = 31 * checksum + name.length();
        }
        blackhole = checksum;
    }

    private static void lazy(ProfilerFiller profiler, World world, String dimension, int iterations) {
        int checksum = blackhole;
        for (int i = 0; i < iterations; ++i) {
            Supplier<String> name = () -> world + " " + dimension;
            profiler.push(name);
            profiler.pop();
            checksum = 31 * checksum + i;
        }
        blackhole = checksum;
    }

    private static int value(String[] args, String prefix, int fallback) {
        for (String arg : args) if (arg.startsWith(prefix)) return Integer.parseInt(arg.substring(prefix.length()));
        return fallback;
    }

    private static final class World {
        @Override
        public String toString() {
            return "ServerLevel[minecraft:overworld]";
        }
    }
}
