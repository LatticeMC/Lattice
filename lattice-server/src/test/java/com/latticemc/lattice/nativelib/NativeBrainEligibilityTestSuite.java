package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativeBrainEligibilityTestSuite {
    @Test
    void javaEvaluatorMatchesBrainMemoryStatusSemanticsAcrossWordBoundary() {
        NativeBrainEligibility.Batch batch = new NativeBrainEligibility.Batch(
                2, 65,
                new long[] {bit(1) | bit(3), bit(0), bit(1) | bit(3), 0L},
                new long[] {bit(1), bit(0), bit(3), 0L},
                new int[] {0, 0, 1, 1},
                new int[] {0, 2, 3, 5, 6},
                new int[] {1, 3, 64, 1, 3, 64},
                new byte[] {
                        NativeBrainEligibility.VALUE_PRESENT,
                        NativeBrainEligibility.VALUE_ABSENT,
                        NativeBrainEligibility.VALUE_PRESENT,
                        NativeBrainEligibility.VALUE_PRESENT,
                        NativeBrainEligibility.VALUE_ABSENT,
                        NativeBrainEligibility.REGISTERED,
                });

        assertArrayEquals(new boolean[] {true, true, false, false}, NativeBrainEligibility.javaEvaluate(batch));
    }

    @Test
    void presentWithoutRegistrationDoesNotSatisfyAnyMemoryStatus() {
        NativeBrainEligibility.Batch batch = new NativeBrainEligibility.Batch(
                1, 1,
                new long[] {0L},
                new long[] {bit(0)},
                new int[] {0, 0, 0},
                new int[] {0, 1, 2, 3},
                new int[] {0, 0, 0},
                new byte[] {
                        NativeBrainEligibility.VALUE_PRESENT,
                        NativeBrainEligibility.VALUE_ABSENT,
                        NativeBrainEligibility.REGISTERED,
                });

        assertArrayEquals(new boolean[] {false, false, false}, NativeBrainEligibility.javaEvaluate(batch));
    }

    @Test
    void thresholdGateKeepsSmallWorkOnJavaAndEvaluateMatchesReference() {
        int threshold = NativeBrainEligibility.minimumRequirementCount();
        assertFalse(NativeBrainEligibility.shouldUseNative(0, threshold));
        assertFalse(NativeBrainEligibility.shouldUseNative(1, threshold - 1));
        assertFalse(NativeBrainEligibility.shouldUseNative(1, threshold));
        assertFalse(NativeBrainEligibility.shouldUseNative(255, threshold, 2));
        assertTrue(NativeBrainEligibility.shouldUseNative(256, threshold, 2));
        assertFalse(NativeBrainEligibility.shouldUseNative(256, threshold, 1));
        assertTrue(NativeBrainEligibility.shouldUseNative(512, threshold, 1));

        NativeBrainEligibility.Batch smallBatch = new NativeBrainEligibility.Batch(
                1, 1,
                new long[] {bit(0)}, new long[] {0L},
                new int[] {0}, new int[] {0, 1}, new int[] {0},
                new byte[] {NativeBrainEligibility.VALUE_ABSENT});
        assertArrayEquals(NativeBrainEligibility.javaEvaluate(smallBatch), NativeBrainEligibility.evaluate(smallBatch));
    }

    @Test
    void invalidCsrAndMemoryIdsFailBeforeAnyNativeAttempt() {
        NativeBrainEligibility.Batch invalidOffsets = new NativeBrainEligibility.Batch(
                1, 1,
                new long[] {0L}, new long[] {0L},
                new int[] {0}, new int[] {1, 1}, new int[] {0},
                new byte[] {NativeBrainEligibility.VALUE_ABSENT});
        assertThrows(IllegalArgumentException.class, () -> NativeBrainEligibility.evaluate(invalidOffsets));

        NativeBrainEligibility.Batch invalidMemory = new NativeBrainEligibility.Batch(
                1, 1,
                new long[] {0L}, new long[] {0L},
                new int[] {0}, new int[] {0, 1}, new int[] {1},
                new byte[] {NativeBrainEligibility.VALUE_ABSENT});
        assertThrows(IllegalArgumentException.class, () -> NativeBrainEligibility.javaEvaluate(invalidMemory));
    }

    @Test
    void packedMasksPreserveCsrSemantics() {
        NativeBrainEligibility.Batch batch = new NativeBrainEligibility.Batch(
                2, 65,
                new long[] {bit(1) | bit(3), bit(0), bit(1) | bit(3), 0L},
                new long[] {bit(1), bit(0), bit(3), 0L},
                new int[] {0, 0, 1, 1},
                new int[] {0, 2, 3, 5, 6},
                new int[] {1, 3, 64, 1, 3, 64},
                new byte[] {
                        NativeBrainEligibility.VALUE_PRESENT,
                        NativeBrainEligibility.VALUE_ABSENT,
                        NativeBrainEligibility.VALUE_PRESENT,
                        NativeBrainEligibility.VALUE_PRESENT,
                        NativeBrainEligibility.VALUE_ABSENT,
                        NativeBrainEligibility.REGISTERED,
                });
        NativeBrainEligibility.PackedBatch packed = NativeBrainEligibility.pack(batch);
        assertArrayEquals(NativeBrainEligibility.javaEvaluate(batch), NativeBrainEligibility.javaEvaluatePacked(packed));
        assertArrayEquals(NativeBrainEligibility.javaEvaluatePacked(packed), NativeBrainEligibility.evaluate(packed));
    }

    @Test
    void preparedPackedBitmapMatchesValidatedPath() {
        NativeBrainEligibility.PackedBatch packed = new NativeBrainEligibility.PackedBatch(
                1, 1, 1,
                new long[] {bit(0)}, new long[] {bit(0)}, new int[] {0},
                new long[] {bit(0)}, new long[] {bit(0)}, new long[] {0L});
        long[] validatedOutput = {-1L};
        long[] preparedOutput = {-1L};

        NativeBrainEligibility.evaluatePackedInto(packed, validatedOutput);
        NativeBrainEligibility.evaluatePreparedPackedInto(packed, preparedOutput);

        assertArrayEquals(validatedOutput, preparedOutput);
    }

    @Test
    void packedBitmapOutputPreservesBoundaryBitsAndClearsTail() {
        int behaviorCount = 65;
        int[] entities = new int[behaviorCount];
        long[] requiredRegistered = new long[behaviorCount];
        long[] requiredPresent = new long[behaviorCount];
        long[] requiredAbsent = new long[behaviorCount];
        for (int behavior = 0; behavior < behaviorCount; ++behavior) {
            entities[behavior] = behavior == 64 ? 1 : 0;
            requiredRegistered[behavior] = bit(behavior == 64 ? 0 : 63);
            requiredPresent[behavior] = requiredRegistered[behavior];
        }
        NativeBrainEligibility.PackedBatch packed = new NativeBrainEligibility.PackedBatch(
                2, 1, behaviorCount,
                new long[] {bit(63), bit(0)}, new long[] {bit(63), bit(0)}, entities,
                requiredRegistered, requiredPresent, requiredAbsent);
        long[] output = {-1L, -1L, -1L};

        NativeBrainEligibility.evaluatePackedInto(packed, output);

        boolean[] expected = NativeBrainEligibility.javaEvaluatePacked(packed);
        for (int behavior = 0; behavior < behaviorCount; ++behavior) {
            assertTrue(expected[behavior]);
        }
        assertTrue((output[0] & (1L << 63)) != 0L);
        assertTrue((output[1] & 1L) != 0L);
        assertTrue((output[1] & ~1L) == 0L);
        assertTrue(output[2] == -1L);
    }

    @Test
    void packedBitmapOutputValidatesCapacityAtPublicBoundary() {
        NativeBrainEligibility.PackedBatch oneBehavior = new NativeBrainEligibility.PackedBatch(
                1, 1, 0, new long[] {0L}, new long[] {0L}, new int[] {0},
                new long[] {0L}, new long[] {0L}, new long[] {0L});
        assertThrows(IllegalArgumentException.class,
                () -> NativeBrainEligibility.evaluatePackedInto(oneBehavior, new long[0]));
    }

    private static long bit(int index) {
        return 1L << index;
    }
}
