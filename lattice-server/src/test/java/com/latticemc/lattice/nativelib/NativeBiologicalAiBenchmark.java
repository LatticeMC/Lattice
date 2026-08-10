package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import java.util.Locale;

public final class NativeBiologicalAiBenchmark {
    private static final int[] STIMULUS_COUNTS = {0, 1, 2, 4, 8, 16, 32, 64, 128, 256, 512};
    private static volatile int blackhole;

    private NativeBiologicalAiBenchmark() {}

    public static void main(String[] args) {
        Config config = Config.parse(args);
        Locale.setDefault(Locale.ROOT);
        if (!NativeBiologicalAi.isAvailable()) {
            throw new IllegalStateException("biological AI native unavailable");
        }

        System.out.printf("Biological AI full-wrapper benchmark%n");
        System.out.printf("cpu=%s warmup=%d samples=%d iterations=%s%n",
                LatticeNative.cpuSummary(), config.warmupRounds, config.sampleCount,
                config.iterations > 0 ? Integer.toString(config.iterations) : "adaptive");
        System.out.printf("%-9s %-10s %-12s %-12s %-12s %-12s %-9s %-10s%n",
                "stimuli", "iterations", "java-p50", "java-p95",
                "native-p50", "native-p95", "speedup", "candidate");

        int firstCandidate = -1;
        for (int stimulusCount : STIMULUS_COUNTS) {
            NativeBiologicalAi.Stimulus[] stimuli = createStimuli(stimulusCount);
            NativeBiologicalAi.Decision expected = javaDecision(stimuli);
            NativeBiologicalAi.Decision actual = nativeDecision(stimuli);
            if (!expected.equals(actual)) {
                throw new AssertionError("Java/native mismatch at " + stimulusCount
                        + " stimuli: " + expected + " != " + actual);
            }

            int iterations = config.iterations > 0
                    ? config.iterations
                    : Math.max(2_000, Math.min(100_000, 1_000_000 / Math.max(1, stimulusCount)));
            Evaluator javaEvaluator = () -> hash(javaDecision(stimuli));
            Evaluator nativeEvaluator = () -> hash(nativeDecision(stimuli));
            warmup(javaEvaluator, nativeEvaluator, config.warmupRounds, iterations);
            Result result = measure(javaEvaluator, nativeEvaluator, config.sampleCount, iterations);
            boolean candidate = result.nativeP50 < result.javaP50 && result.nativeP95 < result.javaP95;
            if (candidate && firstCandidate < 0) firstCandidate = stimulusCount;
            System.out.printf("%-9d %-10d %-12.1f %-12.1f %-12.1f %-12.1f %-9.3f %-10s%n",
                    stimulusCount, iterations, result.javaP50, result.javaP95,
                    result.nativeP50, result.nativeP95, result.javaP50 / result.nativeP50,
                    candidate ? "yes" : "no");
        }

        System.out.printf("blackhole=%d%n", blackhole);
        System.out.printf("result=%s%n", firstCandidate < 0
                ? "no full-wrapper native break-even"
                : "first full-wrapper candidate at " + firstCandidate + " stimuli");
    }

    private static NativeBiologicalAi.Decision javaDecision(NativeBiologicalAi.Stimulus[] stimuli) {
        return NativeBiologicalAi.javaDecide(
                0.83F, 0.47F, 0.72F, 1.75F,
                false, true, true, 0.18F,
                true, true, true, stimuli, BiologicalAiProfiles.WOLF);
    }

    private static NativeBiologicalAi.Decision nativeDecision(NativeBiologicalAi.Stimulus[] stimuli) {
        return NativeBiologicalAi.nativeDecideWrapper(
                0.83F, 0.47F, 0.72F, 1.75F,
                false, true, true, 0.18F,
                true, true, true, stimuli, BiologicalAiProfiles.WOLF);
    }

    private static NativeBiologicalAi.Stimulus[] createStimuli(int count) {
        NativeBiologicalAi.Stimulus[] stimuli = new NativeBiologicalAi.Stimulus[count];
        NativeBiologicalAi.StimulusKind[] kinds = NativeBiologicalAi.StimulusKind.values();
        for (int index = 0; index < count; ++index) {
            stimuli[index] = new NativeBiologicalAi.Stimulus(
                    kinds[index & 3], 2.0F + index * 0.25F,
                    0.2F + (index % 7) * 0.1F, true, (index & 1) == 0);
        }
        return stimuli;
    }

    private static int hash(NativeBiologicalAi.Decision decision) {
        int value = 31 * decision.action().ordinal() + decision.stimulusIndex();
        value = 31 * value + Float.floatToIntBits(decision.urgency());
        value = 31 * value + Float.floatToIntBits(decision.moveSpeed());
        return 31 * value + Float.floatToIntBits(decision.desiredRange());
    }

    private static void warmup(Evaluator javaEvaluator, Evaluator nativeEvaluator,
                               int rounds, int iterations) {
        int warmupIterations = Math.max(500, iterations / 5);
        for (int round = 0; round < rounds; ++round) {
            measureOne(javaEvaluator, warmupIterations);
            measureOne(nativeEvaluator, warmupIterations);
        }
    }

    private static Result measure(Evaluator javaEvaluator, Evaluator nativeEvaluator,
                                  int samples, int iterations) {
        double[] javaTimes = new double[samples];
        double[] nativeTimes = new double[samples];
        for (int sample = 0; sample < samples; ++sample) {
            if ((sample & 1) == 0) {
                javaTimes[sample] = measureOne(javaEvaluator, iterations);
                nativeTimes[sample] = measureOne(nativeEvaluator, iterations);
            } else {
                nativeTimes[sample] = measureOne(nativeEvaluator, iterations);
                javaTimes[sample] = measureOne(javaEvaluator, iterations);
            }
        }
        return new Result(percentile(javaTimes, 0.50), percentile(javaTimes, 0.95),
                percentile(nativeTimes, 0.50), percentile(nativeTimes, 0.95));
    }

    private static double measureOne(Evaluator evaluator, int iterations) {
        int checksum = blackhole;
        long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; ++iteration) {
            checksum = 31 * checksum + evaluator.evaluate();
        }
        long elapsed = System.nanoTime() - start;
        blackhole = checksum;
        return (double) elapsed / iterations;
    }

    private static double percentile(double[] values, double percentile) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int index = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    @FunctionalInterface
    private interface Evaluator {
        int evaluate();
    }

    private record Result(double javaP50, double javaP95, double nativeP50, double nativeP95) {}

    private record Config(int warmupRounds, int sampleCount, int iterations) {
        private static Config parse(String[] args) {
            int warmup = 5;
            int samples = 15;
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
