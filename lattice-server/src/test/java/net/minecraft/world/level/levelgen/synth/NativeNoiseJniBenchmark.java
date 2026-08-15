package net.minecraft.world.level.levelgen.synth;

import com.latticemc.lattice.bridge.NativeInterpolatedNoiseAccess;
import com.latticemc.lattice.bridge.NativeNormalNoiseAccess;
import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeDoublePerlinNoise;
import com.latticemc.lattice.nativelib.NativeInterpolatedNoise;
import com.latticemc.lattice.nativelib.NativeScalarNoiseControl;
import com.latticemc.lattice.nativelib.NativeSimplexNoise;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;

/** Diagnostic benchmark for existing scalar JNI noise entry points. */
public final class NativeNoiseJniBenchmark {
    private static final double PARITY_TOLERANCE = 1.0E-6;
    private static volatile double blackhole;

    private NativeNoiseJniBenchmark() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) {
            throw new IllegalStateException("Lattice native library is unavailable");
        }

        double[] x = new double[options.maxCount()];
        double[] y = new double[x.length];
        double[] z = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            x[i] = -8192.25 + i * 0.371;
            y[i] = -64.5 + i * 0.413;
            z[i] = 4096.75 - i * 0.437;
        }

        ImprovedNoise improved = new ImprovedNoise(RandomSource.create(0x1A2B3C4DL));
        PerlinNoise perlin = PerlinNoise.create(RandomSource.create(0x22334455L), -7,
            1.0, 1.0, 1.0, 0.0, 1.0, 1.0, 1.0, 1.0);
        NormalNoise normal = NormalNoise.create(RandomSource.create(0x33445566L), -7,
            1.0, 1.0, 1.0, 0.0, 1.0, 1.0, 1.0, 1.0);
        NativeDoublePerlinNoise nativeDouble = ((NativeNormalNoiseAccess) normal).lattice$getNativeDoublePerlinNoise();

        BlendedNoise blended = new BlendedNoise(RandomSource.create(0x44556677L), 1.0, 1.0, 80.0, 160.0, 8.0);
        NativeInterpolatedNoise nativeInterpolated = ((NativeInterpolatedNoiseAccess) blended).lattice$getNativeInterpolatedNoise();
        SimplexPair simplex = createSimplexPair(0x55667788L);
        if (nativeDouble == null || nativeInterpolated == null || simplex.nativeNoise() == null) {
            throw new IllegalStateException("One or more native noise handles could not be created");
        }

        List<Operation> operations = List.of(
            toggleOperation("improved-perlin", options.targetPoints(),
                (index) -> improved.noise(x[index], y[index], z[index])),
            toggleOperation("octave-perlin", options.targetPoints(),
                (index) -> perlin.getValue(x[index], y[index], z[index])),
            toggleOperation("normal-noise", options.targetPoints(),
                (index) -> normal.getValue(x[index], y[index], z[index])),
            directOperation("double-perlin-direct", options.targetPoints(),
                () -> NativeScalarNoiseControl.setPerlinEnabled(false),
                (index) -> normal.getValue(x[index], y[index], z[index]),
                (index) -> nativeDouble.sample(x[index], y[index], z[index])),
            toggleOperation("blended-per-octave", options.heavyTargetPoints(),
                (index) -> blended.compute(context(x[index], y[index], z[index]))),
            directOperation("interpolated-direct", options.heavyTargetPoints(),
                () -> NativeScalarNoiseControl.setPerlinEnabled(false),
                (index) -> blended.compute(context(x[index], y[index], z[index])),
                (index) -> nativeInterpolated.sample((int) x[index], (int) y[index], (int) z[index])),
            directOperation("simplex-2d-direct", options.targetPoints(),
                () -> {},
                (index) -> simplex.javaNoise().getValue(x[index], y[index]),
                (index) -> simplex.nativeNoise().sample2d(x[index], y[index])),
            directOperation("simplex-3d-direct", options.targetPoints(),
                () -> {},
                (index) -> simplex.javaNoise().getValue(x[index], y[index], z[index]),
                (index) -> simplex.nativeNoise().sample3d(x[index], y[index], z[index]))
        );

        System.out.printf(Locale.ROOT, "Native scalar-noise JNI benchmark%n");
        System.out.printf(Locale.ROOT, "cpu=%s java=%s warmup=%d samples=%d counts=%s%n",
            LatticeNative.cpuSummary(), System.getProperty("java.version"), options.warmup(), options.samples(), options.counts());
        System.out.printf(Locale.ROOT,
            "operation,count,java-p50-ns,java-p95-ns,native-p50-ns,native-p95-ns,speedup-p50,speedup-p95,bitwise,max-abs-error%n");

        boolean parityPassed = true;
        try {
            for (Operation operation : operations) {
                for (int count : options.counts()) {
                    Result result = run(operation, count, options);
                    parityPassed &= result.maxAbsError() <= PARITY_TOLERANCE;
                    System.out.printf(Locale.ROOT,
                        "%s,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f,%s,%.17e%n",
                        operation.name(), count,
                        result.javaStats().p50(), result.javaStats().p95(),
                        result.nativeStats().p50(), result.nativeStats().p95(),
                        result.javaStats().p50() / result.nativeStats().p50(),
                        result.javaStats().p95() / result.nativeStats().p95(),
                        result.bitwise() ? "yes" : "no", result.maxAbsError());
                }
            }
        } finally {
            NativeScalarNoiseControl.setPerlinEnabled(false);
        }
        System.out.printf(Locale.ROOT, "result=%s blackhole=%.17g%n", parityPassed ? "success" : "parity-failure", blackhole);
        if (!parityPassed) {
            throw new IllegalStateException("native scalar-noise parity exceeded " + PARITY_TOLERANCE);
        }
    }

    private static Operation toggleOperation(String name, int targetPoints, Sample sample) {
        return directOperation(name, targetPoints,
            () -> NativeScalarNoiseControl.setPerlinEnabled(false), sample,
            sample,
            () -> NativeScalarNoiseControl.setPerlinEnabled(true));
    }

    private static Operation directOperation(String name, int targetPoints, Runnable selectJava,
                                             Sample javaSample, Sample nativeSample) {
        return directOperation(name, targetPoints, selectJava, javaSample, nativeSample, () -> {});
    }

    private static Operation directOperation(String name, int targetPoints, Runnable selectJava,
                                             Sample javaSample, Sample nativeSample, Runnable selectNative) {
        return new Operation(name, targetPoints, selectJava, selectNative, javaSample, nativeSample);
    }

    private static Result run(Operation operation, int count, Options options) {
        boolean bitwise = true;
        double maxAbsError = 0.0;
        operation.selectJava().run();
        double[] javaValues = new double[count];
        for (int i = 0; i < count; i++) javaValues[i] = operation.javaSample().sample(i);
        operation.selectNative().run();
        for (int i = 0; i < count; i++) {
            double nativeValue = operation.nativeSample().sample(i);
            bitwise &= Double.doubleToRawLongBits(javaValues[i]) == Double.doubleToRawLongBits(nativeValue);
            maxAbsError = Math.max(maxAbsError, Math.abs(javaValues[i] - nativeValue));
        }

        int iterations = Math.max(1, operation.targetPoints() / count);
        for (int warmup = 0; warmup < options.warmup(); warmup++) {
            measureOnce(operation.selectJava(), operation.javaSample(), count, iterations);
            measureOnce(operation.selectNative(), operation.nativeSample(), count, iterations);
        }

        long[] javaTimings = new long[options.samples()];
        long[] nativeTimings = new long[options.samples()];
        for (int sample = 0; sample < options.samples(); sample++) {
            if ((sample & 1) == 0) {
                javaTimings[sample] = measureOnce(operation.selectJava(), operation.javaSample(), count, iterations);
                nativeTimings[sample] = measureOnce(operation.selectNative(), operation.nativeSample(), count, iterations);
            } else {
                nativeTimings[sample] = measureOnce(operation.selectNative(), operation.nativeSample(), count, iterations);
                javaTimings[sample] = measureOnce(operation.selectJava(), operation.javaSample(), count, iterations);
            }
        }
        return new Result(stats(javaTimings, count, iterations), stats(nativeTimings, count, iterations), bitwise, maxAbsError);
    }

    private static long measureOnce(Runnable selector, Sample sample, int count, int iterations) {
        selector.run();
        double checksum = 0.0;
        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; iteration++) {
            for (int i = 0; i < count; i++) checksum += sample.sample(i);
        }
        long elapsed = System.nanoTime() - start;
        blackhole += checksum;
        return elapsed;
    }

    private static Stats stats(long[] timings, int count, int iterations) {
        Arrays.sort(timings);
        int p50Index = timings.length / 2;
        int p95Index = Math.min(timings.length - 1, (int) Math.ceil(timings.length * 0.95) - 1);
        double denominator = (double) count * iterations;
        return new Stats(timings[p50Index] / denominator, timings[p95Index] / denominator);
    }

    private static DensityFunction.SinglePointContext context(double x, double y, double z) {
        return new DensityFunction.SinglePointContext((int) x, (int) y, (int) z);
    }

    private static SimplexPair createSimplexPair(long seed) {
        SimplexNoise javaNoise = new SimplexNoise(RandomSource.create(seed));
        RandomSource random = RandomSource.create(seed);
        double originX = random.nextDouble() * 256.0;
        double originY = random.nextDouble() * 256.0;
        double originZ = random.nextDouble() * 256.0;
        int[] permutation = new int[256];
        for (int i = 0; i < permutation.length; i++) permutation[i] = i;
        for (int i = 0; i < permutation.length; i++) {
            int randomIndex = random.nextInt(256 - i);
            int value = permutation[i];
            permutation[i] = permutation[i + randomIndex];
            permutation[i + randomIndex] = value;
        }
        return new SimplexPair(javaNoise, NativeSimplexNoise.tryCreate(permutation, originX, originY, originZ));
    }

    @FunctionalInterface
    private interface Sample {
        double sample(int index);
    }

    private record Operation(String name, int targetPoints, Runnable selectJava, Runnable selectNative,
                             Sample javaSample, Sample nativeSample) {
    }

    private record Stats(double p50, double p95) {
    }

    private record Result(Stats javaStats, Stats nativeStats, boolean bitwise, double maxAbsError) {
    }

    private record SimplexPair(SimplexNoise javaNoise, NativeSimplexNoise nativeNoise) {
    }

    private record Options(int warmup, int samples, int targetPoints, int heavyTargetPoints, List<Integer> counts) {
        private static Options parse(String[] args) {
            int warmup = 5;
            int samples = 15;
            int targetPoints = 100000;
            int heavyTargetPoints = 5000;
            List<Integer> counts = List.of(1, 8, 49, 245, 2048);
            for (String argument : args) {
                if (argument.startsWith("--warmup=")) warmup = Integer.parseInt(argument.substring(9));
                else if (argument.startsWith("--samples=")) samples = Integer.parseInt(argument.substring(10));
                else if (argument.startsWith("--target-points=")) targetPoints = Integer.parseInt(argument.substring(16));
                else if (argument.startsWith("--heavy-target-points=")) heavyTargetPoints = Integer.parseInt(argument.substring(22));
                else if (argument.startsWith("--counts=")) {
                    List<Integer> parsed = new ArrayList<>();
                    for (String value : argument.substring(9).split(",")) parsed.add(Integer.parseInt(value));
                    counts = List.copyOf(parsed);
                } else throw new IllegalArgumentException("unknown benchmark argument: " + argument);
            }
            if (warmup < 0 || samples < 1 || targetPoints < 1 || heavyTargetPoints < 1 || counts.stream().anyMatch(value -> value < 1)) {
                throw new IllegalArgumentException("benchmark values must be positive (warmup may be zero)");
            }
            return new Options(warmup, samples, targetPoints, heavyTargetPoints, counts);
        }

        private int maxCount() {
            return this.counts.stream().mapToInt(Integer::intValue).max().orElseThrow();
        }
    }
}
