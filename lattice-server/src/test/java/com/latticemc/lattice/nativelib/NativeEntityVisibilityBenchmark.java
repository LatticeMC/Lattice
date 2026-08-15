package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import java.util.Locale;

/**
 * Measures the complete tracked-entity coarse-visibility wrapper, not merely the native SIMD loop.
 *
 * <p>The five timed paths model the current tracker order: every entity visits every player in
 * index order and either updates an unseen player or removes a no-longer-visible tracked player.
 * The local-batch variant intentionally only models one {@code TrackedChunk}; it does not gather
 * entities from the world or change production tracking behaviour.</p>
 */
public final class NativeEntityVisibilityBenchmark {
    private static final int[] ENTITY_COUNTS = {1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1024, 2048};
    private static final int[] PLAYER_COUNTS = {1, 2, 4, 8, 16, 32, 64};
    private static final SpatialCase[] SPATIAL_CASES = SpatialCase.values();
    private static final SeenCase[] SEEN_CASES = SeenCase.values();
    private static volatile int blackhole;

    private NativeEntityVisibilityBenchmark() {}

    public static void main(final String[] args) {
        final Config config = Config.parse(args);
        Locale.setDefault(Locale.ROOT);
        if (!NativeEntityVisibility.isAvailable()) {
            throw new IllegalStateException("Entity visibility native unavailable: " + LatticeNative.failureReason());
        }

        System.out.println("Entity visibility complete-wrapper benchmark");
        System.out.println("matrix=measure only P<=N combinations");
        System.out.printf("cpu=%s warmup=%d samples=%d iterations=%s%n", LatticeNative.cpuSummary(),
            config.warmupRounds, config.sampleCount,
            config.iterations > 0 ? Integer.toString(config.iterations) : "adaptive");
        System.out.println("java-direct performs the tracker distance/replay loop without JNI arrays; "
            + "single-jni exactly models the current N=1 wrapper; batch-jni models one local TrackedChunk; "
            + "reused-batch-jni reuses per-fixture scratch arrays after one-time capacity allocation; "
            + "reused-sparse-batch-jni adds bitmap-row replay special cases.");
        System.out.println("For java-direct, scan-p50 is the combined scalar distance/replay loop because the original path has no bitmap phase.");
        System.out.printf("%-4s %-4s %-14s %-12s %-7s %-17s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-10s %-8s%n",
            "N", "P", "space", "seen", "iters", "path", "p50-ns", "p95-ns", "pair-p50", "pair-p95",
            "prep-p50", "scan-p50", "replay-p50", "jni/call", "array/call", "temp-B", "speed-p50", "speed-p95", "gate");

        for (final int entityCount : ENTITY_COUNTS) {
            for (final int playerCount : PLAYER_COUNTS) {
                // Keep only matrix cells where the player count does not exceed the entity count.
                if (playerCount > entityCount) continue;
                final GateSummary gates = new GateSummary(entityCount, playerCount);
                for (final SpatialCase spatialCase : SPATIAL_CASES) {
                    for (final SeenCase seenCase : SEEN_CASES) {
                        final Fixture fixture = Fixture.create(entityCount, playerCount, spatialCase, seenCase);
                        assertParity(fixture);
                        final int iterations = config.iterations > 0 ? config.iterations : adaptiveIterations(entityCount, playerCount);
                        final Result result = measure(fixture, config.warmupRounds, config.sampleCount, iterations);
                        printRow(fixture, iterations, "java-direct", result.javaDirect, result.javaDirect,
                            0, 0, 0L, entityCount * playerCount, "baseline");
                        final boolean singleCandidate = isCandidate(result.singleJni, result.javaDirect);
                        final boolean batchCandidate = isCandidate(result.batchJni, result.javaDirect);
                        final boolean reusedCandidate = isCandidate(result.reusedBatchJni, result.javaDirect);
                        final boolean sparseCandidate = isCandidate(result.reusedSparseBatchJni, result.javaDirect);
                        gates.record(singleCandidate, batchCandidate, reusedCandidate, sparseCandidate);
                        printRow(fixture, iterations, "single-jni", result.singleJni, result.javaDirect,
                            entityCount, entityCount * 4, singleTemporaryPayloadBytes(entityCount, playerCount), entityCount * playerCount,
                            singleCandidate ? "yes" : "no");
                        printRow(fixture, iterations, "batch-jni", result.batchJni, result.javaDirect,
                            1, 4, batchTemporaryPayloadBytes(entityCount, playerCount), entityCount * playerCount,
                            batchCandidate ? "yes" : "no");
                        printRow(fixture, iterations, "reused-batch-jni", result.reusedBatchJni, result.javaDirect,
                            1, 0, 0L, entityCount * playerCount, reusedCandidate ? "yes" : "no");
                        printRow(fixture, iterations, "reused-sparse-batch-jni", result.reusedSparseBatchJni, result.javaDirect,
                            1, 0, 0L, entityCount * playerCount, sparseCandidate ? "yes" : "no");
                    }
                }
                gates.printRecommendation();
            }
        }
        System.out.printf("blackhole=%d%n", blackhole);
        System.out.println("result=gate recommendations require every spatial/seenBy case to be at least 10% faster at p50 and no slower at p95; "
            + "reused-batch and sparse candidates are emitted as stable (N,P) lines; this benchmark deliberately makes no production-path change.");
    }

    private static void assertParity(final Fixture fixture) {
        final ReplayState directState = fixture.newState();
        final int directChecksum = javaDirect(fixture, directState);
        final long[] directBits = directVisibilityBits(fixture);

        final BatchInput oracleInput = materializeBatch(fixture);
        final long[] javaBits = new long[fixture.entityCount * fixture.rowLongs];
        NativeEntityVisibility.javaScan(oracleInput.entityXyz, oracleInput.entityRangeSq, fixture.entityCount,
            oracleInput.playerXyz, fixture.playerCount, javaBits);
        final ReplayState javaScanState = fixture.newState();
        final int javaScanChecksum = replayBitmap(fixture, javaBits, javaScanState);

        final ReplayState singleState = fixture.newState();
        final long[] singleBits = new long[javaBits.length];
        final int singleChecksum = currentSingleJni(fixture, singleState, null, singleBits);
        final ReplayState batchState = fixture.newState();
        final long[] batchBits = new long[javaBits.length];
        final int batchChecksum = localBatchJni(fixture, batchState, null, batchBits);
        final ReplayState reusedBatchState = fixture.newState();
        final long[] reusedBatchBits = new long[javaBits.length];
        final int reusedBatchChecksum = reusedBatchJni(fixture, reusedBatchState, new ReusableBatchScratch(fixture), null, reusedBatchBits);
        final ReplayState reusedSparseBatchState = fixture.newState();
        final long[] reusedSparseBatchBits = new long[javaBits.length];
        final int reusedSparseBatchChecksum = reusedSparseBatchJni(fixture, reusedSparseBatchState,
            new ReusableBatchScratch(fixture), null, reusedSparseBatchBits);
        if (directChecksum != javaScanChecksum || directChecksum != singleChecksum || directChecksum != batchChecksum
            || directChecksum != reusedBatchChecksum || directChecksum != reusedSparseBatchChecksum
            || !Arrays.equals(directBits, javaBits)
            || !Arrays.equals(javaBits, singleBits) || !Arrays.equals(javaBits, batchBits)
            || !Arrays.equals(javaBits, reusedBatchBits) || !Arrays.equals(javaBits, reusedSparseBatchBits)
            || !directState.matches(javaScanState)
            || !directState.matches(singleState) || !directState.matches(batchState)
            || !directState.matches(reusedBatchState) || !directState.matches(reusedSparseBatchState)) {
            throw new AssertionError("visibility replay mismatch N=" + fixture.entityCount + " P=" + fixture.playerCount
                + " spatial=" + fixture.spatialCase + " seen=" + fixture.seenCase);
        }
    }

    private static Result measure(final Fixture fixture, final int warmupRounds, final int samples, final int iterations) {
        final ReplayState directState = fixture.newState();
        final ReplayState singleState = fixture.newState();
        final ReplayState batchState = fixture.newState();
        final ReplayState reusedBatchState = fixture.newState();
        final ReplayState reusedSparseBatchState = fixture.newState();
        final ReusableBatchScratch reusedScratch = new ReusableBatchScratch(fixture);
        final ReusableBatchScratch reusedSparseScratch = new ReusableBatchScratch(fixture);
        warmup(fixture, directState, singleState, batchState, reusedBatchState, reusedSparseBatchState,
            reusedScratch, reusedSparseScratch, warmupRounds, iterations);

        final SampleSet direct = new SampleSet(samples);
        final SampleSet single = new SampleSet(samples);
        final SampleSet batch = new SampleSet(samples);
        final SampleSet reusedBatch = new SampleSet(samples);
        final SampleSet reusedSparseBatch = new SampleSet(samples);
        for (int sample = 0; sample < samples; ++sample) {
            switch (sample % 5) {
                case 0 -> {
                    direct.add(measureJavaDirect(fixture, directState, iterations));
                    single.add(measureSingle(fixture, singleState, iterations));
                    batch.add(measureBatch(fixture, batchState, iterations));
                    reusedBatch.add(measureReusedBatch(fixture, reusedBatchState, reusedScratch, iterations));
                    reusedSparseBatch.add(measureReusedSparseBatch(fixture, reusedSparseBatchState, reusedSparseScratch, iterations));
                }
                case 1 -> {
                    single.add(measureSingle(fixture, singleState, iterations));
                    batch.add(measureBatch(fixture, batchState, iterations));
                    reusedBatch.add(measureReusedBatch(fixture, reusedBatchState, reusedScratch, iterations));
                    reusedSparseBatch.add(measureReusedSparseBatch(fixture, reusedSparseBatchState, reusedSparseScratch, iterations));
                    direct.add(measureJavaDirect(fixture, directState, iterations));
                }
                case 2 -> {
                    batch.add(measureBatch(fixture, batchState, iterations));
                    reusedBatch.add(measureReusedBatch(fixture, reusedBatchState, reusedScratch, iterations));
                    reusedSparseBatch.add(measureReusedSparseBatch(fixture, reusedSparseBatchState, reusedSparseScratch, iterations));
                    direct.add(measureJavaDirect(fixture, directState, iterations));
                    single.add(measureSingle(fixture, singleState, iterations));
                }
                case 3 -> {
                    reusedBatch.add(measureReusedBatch(fixture, reusedBatchState, reusedScratch, iterations));
                    reusedSparseBatch.add(measureReusedSparseBatch(fixture, reusedSparseBatchState, reusedSparseScratch, iterations));
                    direct.add(measureJavaDirect(fixture, directState, iterations));
                    single.add(measureSingle(fixture, singleState, iterations));
                    batch.add(measureBatch(fixture, batchState, iterations));
                }
                default -> {
                    reusedSparseBatch.add(measureReusedSparseBatch(fixture, reusedSparseBatchState, reusedSparseScratch, iterations));
                    direct.add(measureJavaDirect(fixture, directState, iterations));
                    single.add(measureSingle(fixture, singleState, iterations));
                    batch.add(measureBatch(fixture, batchState, iterations));
                    reusedBatch.add(measureReusedBatch(fixture, reusedBatchState, reusedScratch, iterations));
                }
            }
        }
        return new Result(direct.result(), single.result(), batch.result(), reusedBatch.result(), reusedSparseBatch.result());
    }

    private static void warmup(final Fixture fixture, final ReplayState directState, final ReplayState singleState,
                               final ReplayState batchState, final ReplayState reusedBatchState,
                               final ReplayState reusedSparseBatchState, final ReusableBatchScratch reusedScratch,
                               final ReusableBatchScratch reusedSparseScratch, final int rounds, final int iterations) {
        final int warmupIterations = Math.max(8, iterations / 5);
        for (int round = 0; round < rounds; ++round) {
            measureJavaDirect(fixture, directState, warmupIterations);
            measureSingle(fixture, singleState, warmupIterations);
            measureBatch(fixture, batchState, warmupIterations);
            measureReusedBatch(fixture, reusedBatchState, reusedScratch, warmupIterations);
            measureReusedSparseBatch(fixture, reusedSparseBatchState, reusedSparseScratch, warmupIterations);
        }
    }

    private static PhaseTimes measureJavaDirect(final Fixture fixture, final ReplayState state, final int iterations) {
        final PhaseTimes times = new PhaseTimes();
        int checksum = blackhole;
        final long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + javaDirect(fixture, state);
        }
        times.total = System.nanoTime() - start;
        times.prepare = 0L;
        times.scan = times.total;
        blackhole = checksum;
        return times.divide(iterations);
    }

    private static PhaseTimes measureSingle(final Fixture fixture, final ReplayState state, final int iterations) {
        int checksum = blackhole;
        final long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + currentSingleJni(fixture, state, null, null);
        }
        final long totalElapsed = System.nanoTime() - start;
        final PhaseTimes times = profileSinglePhases(fixture, state, iterations);
        times.total = totalElapsed / iterations;
        blackhole = checksum;
        return times.dividePhases(iterations);
    }

    private static PhaseTimes measureBatch(final Fixture fixture, final ReplayState state, final int iterations) {
        int checksum = blackhole;
        final long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + localBatchJni(fixture, state, null, null);
        }
        final long totalElapsed = System.nanoTime() - start;
        final PhaseTimes times = profileBatchPhases(fixture, state, iterations);
        times.total = totalElapsed / iterations;
        blackhole = checksum;
        return times.dividePhases(iterations);
    }

    private static PhaseTimes measureReusedBatch(final Fixture fixture, final ReplayState state,
                                                 final ReusableBatchScratch scratch, final int iterations) {
        int checksum = blackhole;
        final long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + reusedBatchJni(fixture, state, scratch, null, null);
        }
        final long totalElapsed = System.nanoTime() - start;
        final PhaseTimes times = profileReusedBatchPhases(fixture, state, scratch, iterations);
        times.total = totalElapsed / iterations;
        blackhole = checksum;
        return times.dividePhases(iterations);
    }

    private static PhaseTimes measureReusedSparseBatch(final Fixture fixture, final ReplayState state,
                                                       final ReusableBatchScratch scratch, final int iterations) {
        int checksum = blackhole;
        final long start = System.nanoTime();
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + reusedSparseBatchJni(fixture, state, scratch, null, null);
        }
        final long totalElapsed = System.nanoTime() - start;
        final PhaseTimes times = profileReusedSparseBatchPhases(fixture, state, scratch, iterations);
        times.total = totalElapsed / iterations;
        blackhole = checksum;
        return times.dividePhases(iterations);
    }

    private static PhaseTimes profileSinglePhases(final Fixture fixture, final ReplayState state, final int iterations) {
        final PhaseTimes times = new PhaseTimes();
        int checksum = blackhole;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + currentSingleJni(fixture, state, times, null);
        }
        blackhole = checksum;
        return times;
    }

    private static PhaseTimes profileBatchPhases(final Fixture fixture, final ReplayState state, final int iterations) {
        final PhaseTimes times = new PhaseTimes();
        int checksum = blackhole;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + localBatchJni(fixture, state, times, null);
        }
        blackhole = checksum;
        return times;
    }

    private static PhaseTimes profileReusedBatchPhases(final Fixture fixture, final ReplayState state,
                                                       final ReusableBatchScratch scratch, final int iterations) {
        final PhaseTimes times = new PhaseTimes();
        int checksum = blackhole;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + reusedBatchJni(fixture, state, scratch, times, null);
        }
        blackhole = checksum;
        return times;
    }

    private static PhaseTimes profileReusedSparseBatchPhases(final Fixture fixture, final ReplayState state,
                                                             final ReusableBatchScratch scratch, final int iterations) {
        final PhaseTimes times = new PhaseTimes();
        int checksum = blackhole;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            state.reset();
            checksum = 31 * checksum + reusedSparseBatchJni(fixture, state, scratch, times, null);
        }
        blackhole = checksum;
        return times;
    }

    private static int javaDirect(final Fixture fixture, final ReplayState state) {
        int checksum = 1;
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            final double entityX = fixture.entityX[entity];
            final double entityZ = fixture.entityZ[entity];
            final double rangeSq = fixture.rangeSq[entity];
            for (int player = 0; player < fixture.playerCount; ++player) {
                final double dx = fixture.playerX[player] - entityX;
                final double dz = fixture.playerZ[player] - entityZ;
                checksum = 31 * checksum + state.apply(entity, player, dx * dx + dz * dz <= rangeSq);
            }
        }
        return checksum;
    }

    private static long[] directVisibilityBits(final Fixture fixture) {
        final long[] visibility = new long[fixture.entityCount * fixture.rowLongs];
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            for (int player = 0; player < fixture.playerCount; ++player) {
                final double dx = fixture.playerX[player] - fixture.entityX[entity];
                final double dz = fixture.playerZ[player] - fixture.entityZ[entity];
                if (dx * dx + dz * dz <= fixture.rangeSq[entity]) {
                    visibility[entity * fixture.rowLongs + (player >>> 6)] |= 1L << (player & 63);
                }
            }
        }
        return visibility;
    }

    /** Strictly mirrors 0007's N=1 shape: per entity allocate/fill all four arrays, enter JNI, then replay. */
    private static int currentSingleJni(final Fixture fixture, final ReplayState state, final PhaseTimes times,
                                        final long[] parityVisibility) {
        int checksum = 1;
        final int rowLongs = fixture.rowLongs;
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            final long prepareStart = times == null ? 0L : System.nanoTime();
            final double[] entityXyz = {fixture.entityX[entity], 0.0D, fixture.entityZ[entity]};
            final double[] entityRangeSq = {fixture.rangeSq[entity]};
            final double[] playerXyz = new double[fixture.playerCount * 3];
            for (int player = 0; player < fixture.playerCount; ++player) {
                final int index = player * 3;
                playerXyz[index] = fixture.playerX[player];
                playerXyz[index + 1] = 0.0D;
                playerXyz[index + 2] = fixture.playerZ[player];
            }
            final long[] visibility = new long[rowLongs];
            if (times != null) times.prepare += System.nanoTime() - prepareStart;
            final long scanStart = times == null ? 0L : System.nanoTime();
            NativeEntityVisibility.scan(entityXyz, entityRangeSq, 1, playerXyz, fixture.playerCount, visibility);
            if (times != null) times.scan += System.nanoTime() - scanStart;
            if (parityVisibility != null) {
                System.arraycopy(visibility, 0, parityVisibility, entity * rowLongs, rowLongs);
            }
            final long replayStart = times == null ? 0L : System.nanoTime();
            for (int player = 0; player < fixture.playerCount; ++player) {
                final boolean visible = (visibility[player >>> 6] & (1L << (player & 63))) != 0L;
                checksum = 31 * checksum + state.apply(entity, player, visible);
            }
            if (times != null) times.replay += System.nanoTime() - replayStart;
        }
        return checksum;
    }

    /** One local TrackedChunk-sized materialisation, one JNI call, original entity/player replay order. */
    private static int localBatchJni(final Fixture fixture, final ReplayState state, final PhaseTimes times,
                                     final long[] parityVisibility) {
        final long prepareStart = times == null ? 0L : System.nanoTime();
        final BatchInput input = materializeBatch(fixture);
        final long[] visibility = new long[fixture.entityCount * fixture.rowLongs];
        if (times != null) times.prepare += System.nanoTime() - prepareStart;
        final long scanStart = times == null ? 0L : System.nanoTime();
        NativeEntityVisibility.scan(input.entityXyz, input.entityRangeSq, fixture.entityCount,
            input.playerXyz, fixture.playerCount, visibility);
        if (times != null) times.scan += System.nanoTime() - scanStart;
        if (parityVisibility != null) {
            System.arraycopy(visibility, 0, parityVisibility, 0, visibility.length);
        }
        final long replayStart = times == null ? 0L : System.nanoTime();
        final int checksum = replayBitmap(fixture, visibility, state);
        if (times != null) times.replay += System.nanoTime() - replayStart;
        return checksum;
    }

    /** Reuses one per-fixture scratch allocation; only logical ranges are filled and cleared per call. */
    private static int reusedBatchJni(final Fixture fixture, final ReplayState state,
                                      final ReusableBatchScratch scratch, final PhaseTimes times,
                                      final long[] parityVisibility) {
        final long prepareStart = times == null ? 0L : System.nanoTime();
        scratch.fill(fixture);
        if (times != null) times.prepare += System.nanoTime() - prepareStart;
        final long scanStart = times == null ? 0L : System.nanoTime();
        NativeEntityVisibility.scan(scratch.entityXyz, scratch.entityRangeSq, fixture.entityCount,
            scratch.playerXyz, fixture.playerCount, scratch.visibility);
        if (times != null) times.scan += System.nanoTime() - scanStart;
        if (parityVisibility != null) {
            System.arraycopy(scratch.visibility, 0, parityVisibility, 0, fixture.entityCount * fixture.rowLongs);
        }
        final long replayStart = times == null ? 0L : System.nanoTime();
        final int checksum = replayBitmap(fixture, scratch.visibility, state);
        if (times != null) times.replay += System.nanoTime() - replayStart;
        return checksum;
    }

    /** Reuses scratch and applies conservative bitmap-row replay shortcuts without changing order. */
    private static int reusedSparseBatchJni(final Fixture fixture, final ReplayState state,
                                            final ReusableBatchScratch scratch, final PhaseTimes times,
                                            final long[] parityVisibility) {
        final long prepareStart = times == null ? 0L : System.nanoTime();
        scratch.fill(fixture);
        if (times != null) times.prepare += System.nanoTime() - prepareStart;
        final long scanStart = times == null ? 0L : System.nanoTime();
        NativeEntityVisibility.scan(scratch.entityXyz, scratch.entityRangeSq, fixture.entityCount,
            scratch.playerXyz, fixture.playerCount, scratch.visibility);
        if (times != null) times.scan += System.nanoTime() - scanStart;
        if (parityVisibility != null) {
            System.arraycopy(scratch.visibility, 0, parityVisibility, 0, fixture.entityCount * fixture.rowLongs);
        }
        final long replayStart = times == null ? 0L : System.nanoTime();
        final int checksum = replaySparseBitmap(fixture, scratch.visibility, state);
        if (times != null) times.replay += System.nanoTime() - replayStart;
        return checksum;
    }

    private static BatchInput materializeBatch(final Fixture fixture) {
        final double[] entityXyz = new double[fixture.entityCount * 3];
        final double[] entityRangeSq = new double[fixture.entityCount];
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            final int index = entity * 3;
            entityXyz[index] = fixture.entityX[entity];
            entityXyz[index + 1] = 0.0D;
            entityXyz[index + 2] = fixture.entityZ[entity];
            entityRangeSq[entity] = fixture.rangeSq[entity];
        }
        final double[] playerXyz = new double[fixture.playerCount * 3];
        for (int player = 0; player < fixture.playerCount; ++player) {
            final int index = player * 3;
            playerXyz[index] = fixture.playerX[player];
            playerXyz[index + 1] = 0.0D;
            playerXyz[index + 2] = fixture.playerZ[player];
        }
        return new BatchInput(entityXyz, entityRangeSq, playerXyz);
    }

    private static int replayBitmap(final Fixture fixture, final long[] visibility, final ReplayState state) {
        int checksum = 1;
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            final int rowBase = entity * fixture.rowLongs;
            for (int player = 0; player < fixture.playerCount; ++player) {
                final boolean visible = (visibility[rowBase + (player >>> 6)] & (1L << (player & 63))) != 0L;
                checksum = 31 * checksum + state.apply(entity, player, visible);
            }
        }
        return checksum;
    }

    private static int replaySparseBitmap(final Fixture fixture, final long[] visibility, final ReplayState state) {
        int checksum = 1;
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            final int rowBase = entity * fixture.rowLongs;
            boolean allZero = true;
            boolean allOne = true;
            for (int row = 0; row < fixture.rowLongs; ++row) {
                final long mask = validRowMask(fixture.playerCount, row);
                final long bits = visibility[rowBase + row] & mask;
                allZero &= bits == 0L;
                allOne &= bits == mask;
            }
            if (allZero && fixture.initialSeenEmpty[entity]) {
                checksum = advanceEmptyReplay(checksum, fixture.playerCount);
                continue;
            }
            if (allOne) {
                for (int player = 0; player < fixture.playerCount; ++player) {
                    checksum = 31 * checksum + state.apply(entity, player, true);
                }
                continue;
            }
            for (int player = 0; player < fixture.playerCount; ++player) {
                final boolean visible = (visibility[rowBase + (player >>> 6)] & (1L << (player & 63))) != 0L;
                checksum = 31 * checksum + state.apply(entity, player, visible);
            }
        }
        return checksum;
    }

    private static long validRowMask(final int playerCount, final int row) {
        final int remaining = playerCount - (row << 6);
        if (remaining >= 64) return -1L;
        return (1L << remaining) - 1L;
    }

    private static int advanceEmptyReplay(int checksum, int playerCount) {
        int factor = 31;
        int count = playerCount;
        while (count != 0) {
            if ((count & 1) != 0) checksum *= factor;
            factor *= factor;
            count >>>= 1;
        }
        return checksum;
    }

    private static boolean isCandidate(final PathResult candidate, final PathResult javaDirect) {
        return candidate.totalP50 <= javaDirect.totalP50 * 0.90D && candidate.totalP95 <= javaDirect.totalP95;
    }

    private static void printRow(final Fixture fixture, final int iterations, final String path,
                                 final PathResult result, final PathResult javaDirect, final int jniCalls,
                                 final int steadyArrayAllocations, final long temporaryPayloadBytes,
                                 final int pairs, final String gate) {
        System.out.printf(Locale.ROOT,
            "%-4d %-4d %-14s %-12s %-7d %-17s %-10.1f %-10.1f %-10.3f %-10.3f %-10.1f %-10.1f %-10.1f %-10d %-10d %-10d %-10.3f %-10.3f %-8s%n",
            fixture.entityCount, fixture.playerCount, fixture.spatialCase.label, fixture.seenCase.label, iterations,
            path, result.totalP50, result.totalP95, result.totalP50 / pairs, result.totalP95 / pairs,
            result.prepareP50, result.scanP50, result.replayP50, jniCalls, steadyArrayAllocations, temporaryPayloadBytes,
            javaDirect.totalP50 / result.totalP50, javaDirect.totalP95 / result.totalP95,
            gate);
    }

    private static int adaptiveIterations(final int entityCount, final int playerCount) {
        final int work = entityCount * playerCount;
        return Math.max(32, Math.min(12_000, 12_000 / Math.max(1, work)));
    }

    private static long singleTemporaryPayloadBytes(final int entityCount, final int playerCount) {
        return (long)entityCount * (4L * Double.BYTES + 3L * playerCount * Double.BYTES
            + (long)NativeEntityVisibility.rowLongs(playerCount) * Long.BYTES);
    }

    private static long batchTemporaryPayloadBytes(final int entityCount, final int playerCount) {
        return ((long)entityCount * 4L + (long)playerCount * 3L) * Double.BYTES
            + (long)entityCount * NativeEntityVisibility.rowLongs(playerCount) * Long.BYTES;
    }

    private enum SpatialCase {
        ALL_NEAR("all-near"), ALL_FAR("all-far"), MIXED_BOUNDARY("mixed-boundary");

        private final String label;

        SpatialCase(final String label) {
            this.label = label;
        }
    }

    private enum SeenCase {
        EMPTY("empty"), PARTIAL("partial");

        private final String label;

        SeenCase(final String label) {
            this.label = label;
        }
    }

    private static final class Fixture {
        private final int entityCount;
        private final int playerCount;
        private final int rowLongs;
        private final SpatialCase spatialCase;
        private final SeenCase seenCase;
        private final double[] entityX;
        private final double[] entityZ;
        private final double[] rangeSq;
        private final double[] playerX;
        private final double[] playerZ;
        private final boolean[] initialSeen;
        private final boolean[] initialSeenEmpty;

        private Fixture(final int entityCount, final int playerCount, final SpatialCase spatialCase, final SeenCase seenCase,
                        final double[] entityX, final double[] entityZ, final double[] rangeSq,
                        final double[] playerX, final double[] playerZ, final boolean[] initialSeen,
                        final boolean[] initialSeenEmpty) {
            this.entityCount = entityCount;
            this.playerCount = playerCount;
            this.rowLongs = NativeEntityVisibility.rowLongs(playerCount);
            this.spatialCase = spatialCase;
            this.seenCase = seenCase;
            this.entityX = entityX;
            this.entityZ = entityZ;
            this.rangeSq = rangeSq;
            this.playerX = playerX;
            this.playerZ = playerZ;
            this.initialSeen = initialSeen;
            this.initialSeenEmpty = initialSeenEmpty;
        }

        private static Fixture create(final int entityCount, final int playerCount,
                                      final SpatialCase spatialCase, final SeenCase seenCase) {
            final double[] entityX = new double[entityCount];
            final double[] entityZ = new double[entityCount];
            final double[] rangeSq = new double[entityCount];
            final double[] playerX = new double[playerCount];
            final double[] playerZ = new double[playerCount];
            for (int entity = 0; entity < entityCount; ++entity) {
                entityX[entity] = (entity % 7) * 0.125D;
                entityZ[entity] = (entity % 11) * -0.125D;
                final double range = switch (entity & 3) {
                    case 0 -> 16.0D;
                    case 1 -> 32.0D;
                    case 2 -> 48.0D;
                    default -> 64.0D;
                };
                rangeSq[entity] = range * range;
            }
            for (int player = 0; player < playerCount; ++player) {
                final int lane = player % 9;
                switch (spatialCase) {
                    case ALL_NEAR -> {
                        playerX[player] = (lane - 4) * 0.75D;
                        playerZ[player] = (player % 5 - 2) * 0.75D;
                    }
                    case ALL_FAR -> {
                        playerX[player] = 1_024.0D + lane * 8.0D;
                        playerZ[player] = -1_024.0D - (player % 5) * 8.0D;
                    }
                    case MIXED_BOUNDARY -> {
                        final double range = switch (player & 3) {
                            case 0 -> 15.999D;
                            case 1 -> 16.001D;
                            case 2 -> 47.999D;
                            default -> 64.001D;
                        };
                        playerX[player] = range;
                        playerZ[player] = (player & 1) == 0 ? 0.0D : 0.125D;
                    }
                }
            }
            final boolean[] initialSeen = new boolean[entityCount * playerCount];
            if (seenCase == SeenCase.PARTIAL) {
                for (int entity = 0; entity < entityCount; ++entity) {
                    for (int player = 0; player < playerCount; ++player) {
                        initialSeen[entity * playerCount + player] = ((entity * 17 + player * 31) & 3) == 0;
                    }
                }
            }
            final boolean[] initialSeenEmpty = new boolean[entityCount];
            Arrays.fill(initialSeenEmpty, true);
            if (seenCase == SeenCase.PARTIAL) {
                for (int entity = 0; entity < entityCount; ++entity) {
                    for (int player = 0; player < playerCount; ++player) {
                        if (initialSeen[entity * playerCount + player]) {
                            initialSeenEmpty[entity] = false;
                            break;
                        }
                    }
                }
            }
            return new Fixture(entityCount, playerCount, spatialCase, seenCase, entityX, entityZ, rangeSq,
                playerX, playerZ, initialSeen, initialSeenEmpty);
        }

        private ReplayState newState() {
            return new ReplayState(initialSeen, playerCount);
        }
    }

    private static final class ReplayState {
        private final boolean[] initial;
        private final boolean[] seen;
        private final byte[] decisions;
        private final int playerCount;

        private ReplayState(final boolean[] initial, final int playerCount) {
            this.initial = initial;
            this.seen = initial.clone();
            this.decisions = new byte[initial.length];
            this.playerCount = playerCount;
        }

        private void reset() {
            System.arraycopy(initial, 0, seen, 0, seen.length);
            Arrays.fill(decisions, (byte)0);
        }

        private int apply(final int entity, final int player, final boolean visible) {
            final int index = entity * playerCount + player;
            if (visible) {
                seen[index] = true;
                decisions[index] = 1; // updatePlayer: add-or-refresh tracked player
                return 1;
            }
            if (seen[index]) {
                seen[index] = false;
                decisions[index] = -1; // removePlayer: no longer in the coarse range
                return -1;
            }
            decisions[index] = 0;
            return 0;
        }

        private boolean matches(final ReplayState other) {
            return Arrays.equals(seen, other.seen) && Arrays.equals(decisions, other.decisions);
        }
    }

    private record BatchInput(double[] entityXyz, double[] entityRangeSq, double[] playerXyz) {}

    private static final class ReusableBatchScratch {
        private final double[] entityXyz;
        private final double[] entityRangeSq;
        private final double[] playerXyz;
        private final long[] visibility;

        private ReusableBatchScratch(final Fixture fixture) {
            this.entityXyz = new double[fixture.entityCount * 3];
            this.entityRangeSq = new double[fixture.entityCount];
            this.playerXyz = new double[fixture.playerCount * 3];
            this.visibility = new long[fixture.entityCount * fixture.rowLongs];
        }

        private void fill(final Fixture fixture) {
            for (int entity = 0; entity < fixture.entityCount; ++entity) {
                final int index = entity * 3;
                this.entityXyz[index] = fixture.entityX[entity];
                this.entityXyz[index + 1] = 0.0D;
                this.entityXyz[index + 2] = fixture.entityZ[entity];
                this.entityRangeSq[entity] = fixture.rangeSq[entity];
            }
            for (int player = 0; player < fixture.playerCount; ++player) {
                final int index = player * 3;
                this.playerXyz[index] = fixture.playerX[player];
                this.playerXyz[index + 1] = 0.0D;
                this.playerXyz[index + 2] = fixture.playerZ[player];
            }
            Arrays.fill(this.visibility, 0, fixture.entityCount * fixture.rowLongs, 0L);
        }
    }

    private static final class PhaseTimes {
        private long total;
        private long prepare;
        private long scan;
        private long replay;

        private PhaseTimes divide(final int divisor) {
            final PhaseTimes divided = new PhaseTimes();
            divided.total = total / divisor;
            divided.prepare = prepare / divisor;
            divided.scan = scan / divisor;
            divided.replay = replay / divisor;
            return divided;
        }

        private PhaseTimes dividePhases(final int divisor) {
            final PhaseTimes divided = divide(divisor);
            divided.total = total;
            return divided;
        }
    }

    private static final class SampleSet {
        private final double[] total;
        private final double[] prepare;
        private final double[] scan;
        private final double[] replay;
        private int index;

        private SampleSet(final int samples) {
            this.total = new double[samples];
            this.prepare = new double[samples];
            this.scan = new double[samples];
            this.replay = new double[samples];
        }

        private void add(final PhaseTimes times) {
            total[index] = times.total;
            prepare[index] = times.prepare;
            scan[index] = times.scan;
            replay[index] = times.replay;
            ++index;
        }

        private PathResult result() {
            if (index != total.length) throw new IllegalStateException("incomplete sample set");
            return new PathResult(percentile(total, 0.50D), percentile(total, 0.95D),
                percentile(prepare, 0.50D), percentile(scan, 0.50D), percentile(replay, 0.50D));
        }
    }

    private static double percentile(final double[] values, final double percentile) {
        final double[] sorted = values.clone();
        Arrays.sort(sorted);
        final int index = (int)Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))];
    }

    private static final class GateSummary {
        private final int entityCount;
        private final int playerCount;
        private boolean allSingle = true;
        private boolean allBatch = true;
        private boolean allReusedBatch = true;
        private boolean allReusedSparseBatch = true;

        private GateSummary(final int entityCount, final int playerCount) {
            this.entityCount = entityCount;
            this.playerCount = playerCount;
        }

        private void record(final boolean singleCandidate, final boolean batchCandidate,
                            final boolean reusedBatchCandidate, final boolean sparseCandidate) {
            allSingle &= singleCandidate;
            allBatch &= batchCandidate;
            allReusedBatch &= reusedBatchCandidate;
            allReusedSparseBatch &= sparseCandidate;
        }

        private void printRecommendation() {
            if (entityCount == 1) {
                System.out.printf("gate-summary current-single P=%d recommendation=%s%n", playerCount,
                    allSingle ? "candidate" : "no-gate");
            }
            System.out.printf("gate-summary local-batch N*P=%d (%d*%d) recommendation=%s%n",
                entityCount * playerCount, entityCount, playerCount, allBatch ? "candidate" : "no-gate");
            System.out.printf("reused-gate-%s N=%d P=%d%n",
                allReusedBatch ? "candidate" : "no-gate", entityCount, playerCount);
            System.out.printf("reused-sparse-gate-%s N=%d P=%d%n",
                allReusedSparseBatch ? "candidate" : "no-gate", entityCount, playerCount);
        }
    }

    private record PathResult(double totalP50, double totalP95, double prepareP50, double scanP50, double replayP50) {}

    private record Result(PathResult javaDirect, PathResult singleJni, PathResult batchJni,
                          PathResult reusedBatchJni, PathResult reusedSparseBatchJni) {}

    private record Config(int warmupRounds, int sampleCount, int iterations) {
        private static Config parse(final String[] args) {
            int warmup = 4;
            int samples = 9;
            int iterations = 0;
            for (final String argument : args) {
                if (argument.startsWith("--warmup=")) warmup = positive(argument, "--warmup=");
                else if (argument.startsWith("--samples=")) samples = positive(argument, "--samples=");
                else if (argument.startsWith("--iterations=")) iterations = nonNegative(argument, "--iterations=");
                else throw new IllegalArgumentException("unknown benchmark argument: " + argument);
            }
            return new Config(warmup, samples, iterations);
        }

        private static int positive(final String argument, final String prefix) {
            final int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value <= 0) throw new IllegalArgumentException(prefix + " must be positive");
            return value;
        }

        private static int nonNegative(final String argument, final String prefix) {
            final int value = Integer.parseInt(argument.substring(prefix.length()));
            if (value < 0) throw new IllegalArgumentException(prefix + " must be non-negative");
            return value;
        }
    }
}
