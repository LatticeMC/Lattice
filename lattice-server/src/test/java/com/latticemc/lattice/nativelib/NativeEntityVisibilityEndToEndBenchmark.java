package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import java.util.Locale;

/**
 * Compares the current native coarse prefilter plus downstream tracker decision against calling
 * the downstream decision for every nearby player. This models ChunkMap.TrackedEntity.updatePlayer
 * range and policy ordering, including its short-circuit before broadcast/chunk/visibility gates.
 */
public final class NativeEntityVisibilityEndToEndBenchmark {
    private static final int[] SUPPORTED_ENTITIES = {512, 2048, 8192};
    private static final int[] SUPPORTED_PLAYERS = {16, 30, 64};
    private static volatile long blackhole;

    private NativeEntityVisibilityEndToEndBenchmark() {}

    public static void main(String[] args) {
        final Config config = Config.parse(args);
        Locale.setDefault(Locale.ROOT);
        if (!NativeEntityVisibility.isAvailable()) {
            throw new IllegalStateException("Entity visibility native unavailable: " + LatticeNative.failureReason());
        }

        System.out.printf("Entity visibility end-to-end decision benchmark cpu=%s warmup=%d samples=%d iterations=%s entities=%s players=%s%n",
            LatticeNative.cpuSummary(), config.warmup, config.samples,
            config.iterations == 0 ? "adaptive" : config.iterations,
            Arrays.toString(config.entities), Arrays.toString(config.players));
        System.out.println("paths=java-full-updatePlayer,native-prefilter+full-updatePlayer; policy gates run only after the same range short-circuit as ChunkMap.updatePlayer");
        System.out.println("N,P,distribution,seen,iterations,coarse-pass%,full-checks-avoided,java-p50-us,java-p95-us,native-total-p50-us,native-total-p95-us,speedup-p50,speedup-p95");

        for (int entityCount : config.entities) {
            for (int playerCount : config.players) {
                if (playerCount > entityCount) continue;
                for (Distribution distribution : Distribution.values()) {
                    for (boolean initiallySeen : new boolean[]{false, true}) {
                        final Fixture fixture = Fixture.create(entityCount, playerCount, distribution, initiallySeen);
                        verifyParity(fixture);
                        final int iterations = config.iterations == 0
                            ? Math.max(1, Math.min(32, 200_000 / (entityCount * playerCount)))
                            : config.iterations;
                        final Timings timings = measure(fixture, config.warmup, config.samples, iterations);
                        final double speedP50 = timings.java.p50 / timings.nativePath.p50;
                        final double speedP95 = timings.java.p95 / timings.nativePath.p95;
                        System.out.printf(Locale.ROOT, "%d,%d,%s,%s,%d,%.1f,%d,%.3f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                            entityCount, playerCount, distribution.label, initiallySeen ? "all-seen" : "empty", iterations,
                            fixture.coarsePassPercent(), fixture.expectedSkippedFullChecks(),
                            timings.java.p50 / 1_000.0, timings.java.p95 / 1_000.0,
                            timings.nativePath.p50 / 1_000.0, timings.nativePath.p95 / 1_000.0,
                            speedP50, speedP95);
                    }
                }
            }
        }
        System.out.println("result=success blackhole=" + blackhole);
    }

    private static void verifyParity(Fixture fixture) {
        final State javaState = fixture.newState();
        final State nativeState = fixture.newState();
        final long javaChecksum = javaFullPath(fixture, javaState);
        final long nativeChecksum = nativePrefilterPath(fixture, nativeState);
        if (!Arrays.equals(javaState.visible, nativeState.visible)) {
            throw new AssertionError("end-to-end tracker decision mismatch N=" + fixture.entityCount
                + " P=" + fixture.playerCount + " distribution=" + fixture.distribution);
        }
    }

    private static Timings measure(Fixture fixture, int warmup, int samples, int iterations) {
        final State javaState = fixture.newState();
        final State nativeState = fixture.newState();
        for (int i = 0; i < warmup; ++i) {
            run(javaState, fixture, false, iterations);
            run(nativeState, fixture, true, iterations);
        }
        final long[] javaSamples = new long[samples];
        final long[] nativeSamples = new long[samples];
        for (int i = 0; i < samples; ++i) {
            if ((i & 1) == 0) {
                javaSamples[i] = timed(javaState, fixture, false, iterations);
                nativeSamples[i] = timed(nativeState, fixture, true, iterations);
            } else {
                nativeSamples[i] = timed(nativeState, fixture, true, iterations);
                javaSamples[i] = timed(javaState, fixture, false, iterations);
            }
        }
        return new Timings(stats(javaSamples, iterations), stats(nativeSamples, iterations));
    }

    private static long timed(State state, Fixture fixture, boolean prefilter, int iterations) {
        long start = System.nanoTime();
        run(state, fixture, prefilter, iterations);
        return System.nanoTime() - start;
    }

    private static void run(State state, Fixture fixture, boolean prefilter, int iterations) {
        long checksum = blackhole;
        for (int i = 0; i < iterations; ++i) {
            state.reset();
            checksum += prefilter
                ? nativePrefilterPath(fixture, state)
                : javaFullPath(fixture, state);
        }
        blackhole = checksum;
    }

    private static long javaFullPath(Fixture fixture, State state) {
        long checksum = 1L;
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            for (int player = 0; player < fixture.playerCount; ++player) {
                checksum = 31L * checksum + updatePlayer(fixture, state, entity, player);
            }
        }
        return checksum;
    }

    private static long nativePrefilterPath(Fixture fixture, State state) {
        long checksum = 1L;
        final int rowLongs = fixture.rowLongs;
        for (int entity = 0; entity < fixture.entityCount; ++entity) {
            final double[] entityXyz = {fixture.entityX[entity], 0.0D, fixture.entityZ[entity]};
            final double[] entityRangeSq = {fixture.rangeSq[entity]};
            final double[] playerXyz = new double[fixture.playerCount * 3];
            for (int player = 0; player < fixture.playerCount; ++player) {
                int base = player * 3;
                playerXyz[base] = fixture.playerX[player];
                playerXyz[base + 1] = 0.0D;
                playerXyz[base + 2] = fixture.playerZ[player];
            }
            final long[] visibility = new long[rowLongs];
            NativeEntityVisibility.scan(entityXyz, entityRangeSq, 1, playerXyz, fixture.playerCount, visibility);

            boolean allZero = true;
            boolean allOne = true;
            for (int row = 0; row < rowLongs; ++row) {
                int remaining = fixture.playerCount - (row << 6);
                long validMask = remaining >= 64 ? -1L : (1L << remaining) - 1L;
                long bits = visibility[row] & validMask;
                allZero &= bits == 0L;
                allOne &= bits == validMask;
            }
            if (!(allZero && state.seenCount[entity] == 0)) {
                for (int player = 0; player < fixture.playerCount; ++player) {
                    boolean coarseVisible = ((visibility[player >>> 6] >>> (player & 63)) & 1L) != 0L;
                    if (coarseVisible || allOne) {
                        checksum = 31L * checksum + updatePlayer(fixture, state, entity, player);
                    } else if (state.visible[entity * fixture.playerCount + player]) {
                        state.apply(entity, player, false);
                        checksum = 31L * checksum - 1L;
                    }
                }
            }
        }
        return checksum;
    }

    /** Mirrors the ordered predicates in ChunkMap.TrackedEntity.updatePlayer. */
    private static int updatePlayer(Fixture fixture, State state, int entity, int player) {
        double dx = fixture.playerX[player] - fixture.entityX[entity];
        double dz = fixture.playerZ[player] - fixture.entityZ[entity];
        double range = Math.min(fixture.range[entity], fixture.playerViewRange[player]);
        boolean visible = dx * dx + dz * dz <= range * range;
        if (visible && fixture.trackingRangeYEnabled && fixture.rangeY[player] != -1.0D) {
            double dy = fixture.playerY[player] - fixture.entityY[entity];
            visible = dy * dy <= fixture.rangeY[player] * fixture.rangeY[player];
        }
        if (visible) {
            visible = fixture.broadcastAllowed[player] && fixture.chunkTracked[player] && fixture.canSee[player];
        }
        return state.apply(entity, player, visible);
    }

    private static Stats stats(long[] samples, int iterations) {
        Arrays.sort(samples);
        return new Stats(percentile(samples, 0.50D) / iterations, percentile(samples, 0.95D) / iterations);
    }

    private static long percentile(long[] values, double percentile) {
        int index = (int)Math.ceil(values.length * percentile) - 1;
        return values[Math.max(0, Math.min(values.length - 1, index))];
    }

    private enum Distribution {
        NEAR("near"), FAR("far"), MIXED("mixed");
        private final String label;
        Distribution(String label) { this.label = label; }
    }

    private static final class Fixture {
        final int entityCount, playerCount, rowLongs;
        final Distribution distribution;
        final boolean trackingRangeYEnabled = true;
        final boolean initiallySeen;
        final double[] entityX, entityY, entityZ, range, rangeSq;
        final double[] playerX, playerY, playerZ, playerViewRange, rangeY;
        final boolean[] broadcastAllowed, chunkTracked, canSee;

        private Fixture(int entityCount, int playerCount, Distribution distribution,
                        double[] entityX, double[] entityY, double[] entityZ, double[] range,
                        double[] playerX, double[] playerY, double[] playerZ, double[] playerViewRange,
                        double[] rangeY, boolean[] broadcastAllowed, boolean[] chunkTracked, boolean[] canSee,
                        boolean initiallySeen) {
            this.entityCount = entityCount; this.playerCount = playerCount;
            this.rowLongs = NativeEntityVisibility.rowLongs(playerCount); this.distribution = distribution;
            this.entityX = entityX; this.entityY = entityY; this.entityZ = entityZ; this.range = range;
            this.rangeSq = Arrays.stream(range).map(value -> value * value).toArray();
            this.playerX = playerX; this.playerY = playerY; this.playerZ = playerZ;
            this.playerViewRange = playerViewRange; this.rangeY = rangeY;
            this.broadcastAllowed = broadcastAllowed; this.chunkTracked = chunkTracked; this.canSee = canSee;
            this.initiallySeen = initiallySeen;
        }

        static Fixture create(int entities, int players, Distribution distribution, boolean initiallySeen) {
            double[] entityX = new double[entities], entityY = new double[entities], entityZ = new double[entities], range = new double[entities];
            for (int e = 0; e < entities; ++e) {
                entityX[e] = (e % 5) * 0.125D; entityZ[e] = (e % 7) * -0.125D;
                range[e] = switch (e & 3) { case 0 -> 16.0D; case 1 -> 32.0D; case 2 -> 48.0D; default -> 64.0D; };
            }
            double[] playerX = new double[players], playerY = new double[players], playerZ = new double[players];
            double[] playerView = new double[players], rangeY = new double[players];
            boolean[] broadcast = new boolean[players], tracked = new boolean[players], canSee = new boolean[players];
            for (int p = 0; p < players; ++p) {
                playerX[p] = switch (distribution) {
                    case NEAR -> (p % 9 - 4) * 0.75D;
                    case FAR -> 512.0D + p * 8.0D;
                    case MIXED -> (p & 1) == 0 ? 8.0D : 128.0D;
                };
                playerZ[p] = p % 5 * 0.25D;
                playerY[p] = (p % 7 - 3) * 12.0D;
                playerView[p] = 16.0D * (1 + p % 4);
                rangeY[p] = (p & 1) == 0 ? 32.0D : -1.0D;
                broadcast[p] = (p % 11) != 0;
                tracked[p] = (p % 13) != 0;
                canSee[p] = (p % 17) != 0;
            }
            return new Fixture(entities, players, distribution, entityX, entityY, entityZ, range,
                playerX, playerY, playerZ, playerView, rangeY, broadcast, tracked, canSee, initiallySeen);
        }

        State newState() { return new State(entityCount, playerCount, initiallySeen); }
        double coarsePassPercent() { return distribution == Distribution.NEAR ? 100.0D : distribution == Distribution.FAR ? 0.0D : 50.0D; }
        long expectedSkippedFullChecks() { return Math.round(entityCount * playerCount * (1.0D - coarsePassPercent() / 100.0D)); }
    }

    private static final class State {
        final boolean[] visible;
        final int[] seenCount;
        private final boolean initiallySeen;
        private final int playerCount;

        State(int entityCount, int playerCount, boolean initiallySeen) {
            this.visible = new boolean[entityCount * playerCount];
            this.seenCount = new int[entityCount];
            this.initiallySeen = initiallySeen;
            this.playerCount = playerCount;
            reset();
        }

        void reset() {
            Arrays.fill(visible, initiallySeen);
            Arrays.fill(seenCount, initiallySeen ? playerCount : 0);
        }

        int apply(int entity, int player, boolean next) {
            int index = entity * playerCount + player;
            boolean previous = visible[index];
            if (previous != next) seenCount[entity] += next ? 1 : -1;
            visible[index] = next;
            return previous == next ? 0 : next ? 1 : -1;
        }
    }

    private record Stats(double p50, double p95) {}
    private record Timings(Stats java, Stats nativePath) {}

    private record Config(int warmup, int samples, int iterations, int[] entities, int[] players) {
        static Config parse(String[] args) {
            int warmup = 4, samples = 11, iterations = 0;
            int[] entities = SUPPORTED_ENTITIES, players = SUPPORTED_PLAYERS;
            for (String arg : args) {
                if (arg.startsWith("--warmup=")) warmup = Integer.parseInt(arg.substring(9));
                else if (arg.startsWith("--samples=")) samples = Integer.parseInt(arg.substring(10));
                else if (arg.startsWith("--iterations=")) iterations = Integer.parseInt(arg.substring(13));
                else if (arg.startsWith("--entities=")) entities = parseSelection(arg.substring(11), SUPPORTED_ENTITIES);
                else if (arg.startsWith("--players=")) players = parseSelection(arg.substring(10), SUPPORTED_PLAYERS);
                else throw new IllegalArgumentException("unknown argument: " + arg);
            }
            if (warmup < 0 || samples < 3 || iterations < 0) throw new IllegalArgumentException("invalid benchmark options");
            return new Config(warmup, samples, iterations, entities, players);
        }

        private static int[] parseSelection(String input, int[] supported) {
            return Arrays.stream(input.split(",")).mapToInt(Integer::parseInt).peek(value -> {
                if (Arrays.stream(supported).noneMatch(candidate -> candidate == value)) throw new IllegalArgumentException("unsupported size: " + value);
            }).toArray();
        }
    }
}
