package net.minecraft.world.entity;

import com.sun.management.ThreadMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.EntitySpeedTestSupport.OriginalEntity;
import net.minecraft.world.entity.EntitySpeedTestSupport.SpeedEntity;

/** 单线程、预分配位置的真实 Entity 速度入口基准；不是服务端 MSPT 基准。 */
public final class EntitySpeedBenchmark {
    private static volatile SpeedEntity published;
    private static volatile double sink;
    private static final ThreadMXBean ALLOCATIONS = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Locale.setDefault(Locale.ROOT);
        if (!ALLOCATIONS.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        ALLOCATIONS.setThreadAllocatedMemoryEnabled(true);
        int iterations = Integer.getInteger("lattice.speedBenchIterations", 1_000_000);
        int warmup = Integer.getInteger("lattice.speedBenchWarmup", 12);
        int samples = Integer.getInteger("lattice.speedBenchSamples", 15);
        boolean reverse = Boolean.getBoolean("lattice.speedBenchReverse");
        Vec3[] positions = new Vec3[1024];
        for (int i = 0; i < positions.length; i++) positions[i] = new Vec3(i * 0.13, i % 7, -(i % 31) * 0.17);
        System.out.printf("ENTITY_SPEED java=%s warmup=%d samples=%d iterations=%d reverse=%s%n",
            System.getProperty("java.version"), warmup, samples, iterations, reverse);
        for (int readEvery : new int[] {0, 128, 1}) {
            SpeedEntity original = new OriginalEntity();
            SpeedEntity optimized = new SpeedEntity();
            for (int i = 0; i < warmup; i++) {
                run(original, positions, iterations, readEvery);
                run(optimized, positions, iterations, readEvery);
            }
            double[][] nanos = new double[2][samples];
            double[][] bytes = new double[2][samples];
            for (int i = 0; i < samples; i++) {
                for (int order = 0; order < 2; order++) {
                    int mode = (order + i + (reverse ? 1 : 0)) & 1;
                    Measurement result = run(mode == 0 ? original : optimized, positions, iterations, readEvery);
                    nanos[mode][i] = (double) result.nanos / iterations;
                    bytes[mode][i] = (double) result.bytes / iterations;
                }
            }
            for (int mode = 0; mode < 2; mode++) {
                Arrays.sort(nanos[mode]);
                Arrays.sort(bytes[mode]);
                System.out.printf("SPEED_RESULT readEvery=%d mode=%s ns-per-sample=%.6f bytes-per-sample=%.6f%n",
                    readEvery, mode == 0 ? "original" : "optimized", nanos[mode][samples / 2], bytes[mode][samples / 2]);
            }
        }
        if (published == null || Double.isNaN(sink)) throw new AssertionError("Invalid benchmark result");
    }

    private static Measurement run(SpeedEntity entity, Vec3[] positions, int iterations, int readEvery) {
        // 发布实体，禁止把整个实例当作不逃逸的临时对象进行标量替换。
        published = entity;
        long thread = Thread.currentThread().threadId();
        long beforeBytes = ALLOCATIONS.getThreadAllocatedBytes(thread);
        long beforeTime = System.nanoTime();
        double sum = 0;
        for (int i = 0; i < iterations; i++) {
            entity.suppliedPosition = positions[i & 1023];
            entity.sample();
            if (readEvery != 0 && (i & (readEvery - 1)) == 0) {
                Vec3 speed = entity.getKnownSpeed();
                sum += speed.x + speed.y + speed.z;
                if (entity.getKnownSpeed() != speed) throw new AssertionError("Unstable sampled vector identity");
            }
        }
        long nanos = System.nanoTime() - beforeTime;
        long bytes = ALLOCATIONS.getThreadAllocatedBytes(thread) - beforeBytes;
        sink = sum + (entity.hasMovedHorizontallyRecently() ? 1 : 0);
        return new Measurement(nanos, bytes);
    }

    private record Measurement(long nanos, long bytes) { }
}
