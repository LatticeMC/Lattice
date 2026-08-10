package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import java.util.Locale;

/**
 * Evaluator-only benchmark for the live Brain prepared packed-plan path.
 * Inputs are materialized once and the reused output bitmap is prevalidated.
 * This excludes one-time plan materialization and per-tick present-bit refresh;
 * every prepared-into iteration still crosses JNI.
 */
public final class NativeBrainEligibilityBenchmark {
    private static final int[] REQUIREMENT_COUNTS = {8, 16, 32, 64, 128, 256};
    private static final int[] REQUIREMENTS_PER_BEHAVIOR = {1, 2, 4};
    private static volatile int blackhole;

    private NativeBrainEligibilityBenchmark() {}

    public static void main(String[] args) {
        Config config = Config.parse(args);
        Locale.setDefault(Locale.ROOT);

        if (!NativeBrainEligibility.isAvailable()) {
            throw new IllegalStateException("Brain eligibility native unavailable: " + LatticeNative.failureReason());
        }
        if (NativeBrainEligibility.minimumRequirementCount() != 1) {
            throw new IllegalStateException("benchmark must run with native threshold 1, got "
                    + NativeBrainEligibility.minimumRequirementCount());
        }

        System.out.printf("Brain eligibility evaluator-only benchmark%n");
        System.out.printf("cpu=%s warmup=%d samples=%d iterations=%s%n",
                LatticeNative.cpuSummary(), config.warmupRounds, config.sampleCount,
                config.iterations > 0 ? Integer.toString(config.iterations) : "adaptive");
        System.out.printf("%-7s %-5s %-5s %-9s %-10s %-5s %-12s %-12s %-12s %-12s %-12s %-12s %-9s %-9s %-10s%n",
                "mode", "req", "rpb", "behaviors", "iterations", "gate", "java-p50", "java-p95",
                "jni-full-p50", "jni-full-p95", "prepared-p50", "prepared-p95",
                "full-x", "prepared-x", "candidate");

        int firstCandidate = -1;
        for (int requirementCount : REQUIREMENT_COUNTS) {
            boolean sawGateOnLayout = false;
            boolean allGateOnLayoutsCandidate = true;
            for (int requirementsPerBehavior : REQUIREMENTS_PER_BEHAVIOR) {
                if (requirementCount % requirementsPerBehavior != 0) continue;
                NativeBrainEligibility.Batch batch = createBatch(requirementCount, requirementsPerBehavior);
                NativeBrainEligibility.PackedBatch packed = NativeBrainEligibility.pack(batch);
                assertPackedParity(batch, packed);
                boolean nativeGate = NativeBrainEligibility.shouldUseNative(
                        packed.behaviorCount(), packed.requirementCount(), packed.memoryWordCount());

                int iterations = config.iterations > 0
                        ? config.iterations
                        : Math.max(4_000, Math.min(100_000, 4_000_000 / requirementCount));
                int outputWords = wordsForBehaviors(packed.behaviorCount());
                long[] nativeOutput = new long[outputWords];
                NativeBrainEligibility.evaluatePackedInto(packed, nativeOutput);
                Evaluator javaPacked = () -> Arrays.hashCode(NativeBrainEligibility.javaEvaluatePacked(packed));
                Evaluator fullJni = () -> Arrays.hashCode(NativeBrainEligibility.evaluate(packed));
                Evaluator preparedJni = () -> {
                    NativeBrainEligibility.evaluatePreparedPackedInto(packed, nativeOutput);
                    return Arrays.hashCode(nativeOutput);
                };
                warmup(javaPacked, fullJni, preparedJni, config.warmupRounds, iterations);
                Result packedResult = measure(javaPacked, fullJni, preparedJni, config.sampleCount, iterations);
                boolean candidate = nativeGate && packedResult.preparedJniP50 < packedResult.javaP50
                        && packedResult.preparedJniP95 < packedResult.javaP95;
                if (nativeGate) {
                    sawGateOnLayout = true;
                    allGateOnLayoutsCandidate &= candidate;
                }
                printRow("packed", requirementCount, requirementsPerBehavior, batch.behaviorCount(), iterations,
                        nativeGate, packedResult);
            }
            if (sawGateOnLayout && allGateOnLayoutsCandidate && firstCandidate < 0) {
                firstCandidate = requirementCount;
            }
        }

        System.out.printf("blackhole=%d%n", blackhole);
        if (firstCandidate < 0) {
            System.out.println("result=no prepared-into evaluator-only candidate across tested gate-on layouts; "
                    + "this excludes present-bit refresh and plan materialization");
        } else {
            System.out.printf("result=first prepared-into evaluator-only candidate across every tested gate-on layout at %d requirements; "
                    + "present-bit refresh and plan materialization are excluded%n", firstCandidate);
        }
    }

    private static void printRow(String mode, int requirementCount, int requirementsPerBehavior,
                                 int behaviorCount, int iterations, boolean nativeGate, Result result) {
        boolean candidate = nativeGate && result.preparedJniP50 < result.javaP50
                && result.preparedJniP95 < result.javaP95;
        System.out.printf("%-7s %-5d %-5d %-9d %-10d %-5s %-12.1f %-12.1f %-12.1f %-12.1f %-12.1f %-12.1f %-9.3f %-9.3f %-10s%n",
                mode, requirementCount, requirementsPerBehavior, behaviorCount, iterations,
                nativeGate ? "on" : "off",
                result.javaP50, result.javaP95, result.fullJniP50, result.fullJniP95,
                result.preparedJniP50, result.preparedJniP95,
                result.javaP50 / result.fullJniP50, result.javaP50 / result.preparedJniP50,
                candidate ? "yes" : "no");
    }

    private static Result measure(Evaluator javaEvaluator, Evaluator fullJniEvaluator, Evaluator preparedJniEvaluator,
                                  int samples, int iterations) {
        double[] javaTimes = new double[samples];
        double[] fullJniTimes = new double[samples];
        double[] preparedJniTimes = new double[samples];
        for (int sample = 0; sample < samples; ++sample) {
            switch (sample % 3) {
            case 0 -> {
                javaTimes[sample] = measureOne(javaEvaluator, iterations);
                fullJniTimes[sample] = measureOne(fullJniEvaluator, iterations);
                preparedJniTimes[sample] = measureOne(preparedJniEvaluator, iterations);
            }
            case 1 -> {
                preparedJniTimes[sample] = measureOne(preparedJniEvaluator, iterations);
                javaTimes[sample] = measureOne(javaEvaluator, iterations);
                fullJniTimes[sample] = measureOne(fullJniEvaluator, iterations);
            }
            default -> {
                fullJniTimes[sample] = measureOne(fullJniEvaluator, iterations);
                preparedJniTimes[sample] = measureOne(preparedJniEvaluator, iterations);
                javaTimes[sample] = measureOne(javaEvaluator, iterations);
            }
            }
            if (!NativeBrainEligibility.isAvailable()) {
                throw new IllegalStateException("native evaluator fell back during measurement");
            }
        }
        return new Result(percentile(javaTimes, 0.50), percentile(javaTimes, 0.95),
                percentile(fullJniTimes, 0.50), percentile(fullJniTimes, 0.95),
                percentile(preparedJniTimes, 0.50), percentile(preparedJniTimes, 0.95));
    }

    private static double measureOne(Evaluator evaluator, int iterations) {
        int checksum = blackhole;
        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; ++iteration) {
            checksum = 31 * checksum + evaluator.evaluate();
        }
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;
        return (double)elapsed / iterations;
    }

    private static void warmup(Evaluator javaEvaluator, Evaluator fullJniEvaluator, Evaluator preparedJniEvaluator,
                               int rounds, int iterations) {
        int warmupIterations = Math.max(1_000, iterations / 4);
        for (int round = 0; round < rounds; ++round) {
            measureOne(javaEvaluator, warmupIterations);
            measureOne(fullJniEvaluator, warmupIterations);
            measureOne(preparedJniEvaluator, warmupIterations);
        }
    }

    private static void assertPackedParity(NativeBrainEligibility.Batch batch,
                                           NativeBrainEligibility.PackedBatch packed) {
        boolean[] expected = NativeBrainEligibility.javaEvaluate(batch);
        boolean[] packedJava = NativeBrainEligibility.javaEvaluatePacked(packed);
        boolean[] packedNative = NativeBrainEligibility.evaluate(packed);
        if (!Arrays.equals(expected, packedJava) || !Arrays.equals(packedJava, packedNative)) {
            throw new AssertionError("packed Java/native Brain eligibility mismatch");
        }
        long[] into = new long[wordsForBehaviors(packed.behaviorCount())];
        NativeBrainEligibility.evaluatePackedInto(packed, into);
        for (int behavior = 0; behavior < packed.behaviorCount(); ++behavior) {
            boolean actual = (into[behavior >>> 6] & (1L << (behavior & 63))) != 0L;
            if (actual != packedJava[behavior]) {
                throw new AssertionError("packed JNI bitmap mismatch at behavior " + behavior);
            }
        }
    }

    private static NativeBrainEligibility.Batch createBatch(int requirementCount, int requirementsPerBehavior) {
        int behaviorCount = requirementCount / requirementsPerBehavior;
        int entityCount = Math.min(32, behaviorCount);
        int memoryTypeCount = 128;
        int wordsPerEntity = 2;
        long[] registered = new long[entityCount * wordsPerEntity];
        long[] present = new long[registered.length];
        Arrays.fill(registered, -1L);
        for (int entity = 0; entity < entityCount; ++entity) {
            present[entity * wordsPerEntity] = 0x5555555555555555L ^ ((long)entity * 0x0101010101010101L);
            present[entity * wordsPerEntity + 1] = 0xAAAAAAAAAAAAAAAAL ^ ((long)entity * 0x1010101010101010L);
        }

        int[] behaviorEntities = new int[behaviorCount];
        int[] offsets = new int[behaviorCount + 1];
        int[] memoryIds = new int[requirementCount];
        byte[] statuses = new byte[requirementCount];
        int requirement = 0;
        for (int behavior = 0; behavior < behaviorCount; ++behavior) {
            behaviorEntities[behavior] = behavior % entityCount;
            offsets[behavior] = requirement;
            for (int local = 0; local < requirementsPerBehavior; ++local) {
                memoryIds[requirement] = Math.floorMod(behavior * 17 + local * 29, memoryTypeCount);
                statuses[requirement] = (byte)Math.floorMod(behavior + local, 3);
                ++requirement;
            }
        }
        offsets[behaviorCount] = requirementCount;
        return new NativeBrainEligibility.Batch(entityCount, memoryTypeCount, registered, present,
                behaviorEntities, offsets, memoryIds, statuses);
    }

    private static double percentile(double[] values, double percentile) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int)Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static int wordsForBehaviors(int behaviorCount) {
        return (int)(((long)behaviorCount + 63L) >>> 6);
    }

    @FunctionalInterface
    private interface Evaluator {
        int evaluate();
    }

    private record Result(double javaP50, double javaP95,
                          double fullJniP50, double fullJniP95,
                          double preparedJniP50, double preparedJniP95) {}

    private record Config(int warmupRounds, int sampleCount, int iterations) {
        private static Config parse(String[] args) {
            int warmup = 4;
            int samples = 12;
            int iterations = 0;
            for (String argument : args) {
                if (argument.startsWith("--warmup=")) warmup = positive(argument, "--warmup=");
                else if (argument.startsWith("--samples=")) samples = positive(argument, "--samples=");
                else if (argument.startsWith("--iterations=")) iterations = positive(argument, "--iterations=");
                else throw new IllegalArgumentException("unknown benchmark argument: " + argument);
            }
            return new Config(warmup, samples, iterations);
        }

        private static int positive(String argument, String prefix) {
            int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " must be positive");
            return value;
        }
    }
}
