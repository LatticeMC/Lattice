package com.latticemc.lattice.nativelib;

import java.util.Objects;

/**
 * Evaluates only the memory predicates used to decide whether a Brain behavior
 * may start. This class never invokes a sensor or behavior and never writes a
 * memory, POI, navigation target, or other world state.
 *
 * <p>The caller owns the primitive arrays for the duration of the call. The
 * bit planes are entity-major: each entity has {@code memoryWordCount()} words
 * in both planes. Requirements are CSR encoded and output order is identical
 * to {@link Batch#behaviorEntityIndices()}.</p>
 */
public final class NativeBrainEligibility {
    public static final byte VALUE_PRESENT = 0;
    public static final byte VALUE_ABSENT = 1;
    public static final byte REGISTERED = 2;

    private static final int EXPECTED_NATIVE_ABI = 2;
    private static final int MINIMUM_REQUIREMENT_COUNT = Math.max(1,
            Integer.getInteger("lattice.nativeBrainEligibility.minRequirements", 64));
    private static final int MINIMUM_BEHAVIOR_COUNT = Math.max(1,
            Integer.getInteger("lattice.nativeBrainEligibility.minBehaviors", 256));
    private static final int MINIMUM_WORD_CHECKS = Math.max(1,
            Integer.getInteger("lattice.nativeBrainEligibility.minWordChecks", 512));
    private static volatile boolean nativeChecked;
    private static volatile boolean nativeCompatible;
    private static volatile boolean nativeDisabled;
    private static final ThreadLocal<long[]> NATIVE_OUTPUT_BITS = ThreadLocal.withInitial(() -> new long[0]);

    private NativeBrainEligibility() {}

    /**
     * A tick-local, side-effect-free memory requirement batch. A behavior with
     * no requirements is eligible. Present bits without their registered bit
     * are deliberately treated as absent, matching {@code Brain.checkMemory}.
     */
    public record Batch(
            int entityCount,
            int memoryTypeCount,
            long[] registeredMemoryBits,
            long[] presentMemoryBits,
            int[] behaviorEntityIndices,
            int[] requirementOffsets,
            int[] requirementMemoryIds,
            byte[] requirementStatuses) {

        public int behaviorCount() {
            return this.behaviorEntityIndices.length;
        }

        public int memoryWordCount() {
            return wordsForMemoryTypes(this.memoryTypeCount);
        }

        public int requirementCount() {
            return this.requirementMemoryIds.length;
        }
    }

    /** Immutable requirements compressed into behavior-major word masks. */
    public record PackedBatch(
            int entityCount,
            int memoryWordCount,
            int requirementCount,
            long[] registeredMemoryBits,
            long[] presentMemoryBits,
            int[] behaviorEntityIndices,
            long[] requiredRegisteredBits,
            long[] requiredPresentBits,
            long[] requiredAbsentBits) {

        public int behaviorCount() {
            return behaviorEntityIndices.length;
        }
    }

    /** Materializes static behavior requirements once; runtime state stays in the bit planes. */
    public static PackedBatch pack(Batch batch) {
        validate(batch);
        int behaviorCount = batch.behaviorCount();
        int words = batch.memoryWordCount();
        long[] requiredRegistered = new long[behaviorCount * words];
        long[] requiredPresent = new long[behaviorCount * words];
        long[] requiredAbsent = new long[behaviorCount * words];
        for (int behavior = 0; behavior < behaviorCount; ++behavior) {
            int maskBase = behavior * words;
            for (int requirement = batch.requirementOffsets()[behavior];
                 requirement < batch.requirementOffsets()[behavior + 1];
                 ++requirement) {
                int memoryId = batch.requirementMemoryIds()[requirement];
                int word = memoryId >>> 6;
                long bit = 1L << (memoryId & 63);
                switch (batch.requirementStatuses()[requirement]) {
                    case VALUE_PRESENT -> {
                        requiredRegistered[maskBase + word] |= bit;
                        requiredPresent[maskBase + word] |= bit;
                    }
                    case VALUE_ABSENT -> {
                        requiredRegistered[maskBase + word] |= bit;
                        requiredAbsent[maskBase + word] |= bit;
                    }
                    case REGISTERED -> requiredRegistered[maskBase + word] |= bit;
                    default -> throw new IllegalArgumentException("invalid Brain eligibility memory requirement");
                }
            }
        }
        return new PackedBatch(batch.entityCount(), words, batch.requirementCount(),
                batch.registeredMemoryBits(), batch.presentMemoryBits(), batch.behaviorEntityIndices(),
                requiredRegistered, requiredPresent, requiredAbsent);
    }

    public static boolean[] javaEvaluatePacked(PackedBatch batch) {
        validatePacked(batch);
        return javaEvaluatePackedUnchecked(batch);
    }

    public static boolean[] evaluate(PackedBatch batch) {
        validatePacked(batch);
        if (!shouldUseNative(batch.behaviorCount(), batch.requirementCount(), batch.memoryWordCount())) {
            return javaEvaluatePackedUnchecked(batch);
        }
        long[] output = reusableOutputBits(batch.behaviorCount());
        evaluatePackedIntoValidated(batch, output);
        return decodeBits(output, batch.behaviorCount());
    }

    /**
     * Evaluates a packed batch into an output bitmap. Bit {@code behavior}
     * corresponds to the behavior at that index; unused tail bits are zero.
     * This validates both inputs on every call and is suitable for callers
     * that need an allocation-free result representation. Only the required
     * {@code ceil(behaviorCount / 64)} words are written.
     */
    public static void evaluatePackedInto(PackedBatch batch, long[] outputEligibleBits) {
        validatePacked(batch);
        validateOutputBits(batch.behaviorCount(), outputEligibleBits);
        evaluatePackedIntoValidated(batch, outputEligibleBits);
    }

    /**
     * Evaluates a prepared packed batch into a prepared output bitmap without
     * repeating structural validation. Callers must have already validated the
     * batch and output capacity, and must not replace or structurally mutate
     * their arrays while this plan is live. The registered-memory and
     * present-memory bitmap contents may be refreshed before each call; array
     * structure and output capacity must remain unchanged.
     *
     * <p>This retains the normal gate, native-unavailable fallback, and native
     * exception fallback semantics through {@link #evaluatePackedIntoValidated}.</p>
     */
    public static void evaluatePreparedPackedInto(PackedBatch batch, long[] outputEligibleBits) {
        evaluatePackedIntoValidated(batch, outputEligibleBits);
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return isNativeUsable();
    }

    /**
     * Returns whether a fully described batch is large enough to justify one
     * JNI crossing. Callers that have not materialized bit planes yet can use
     * this gate from their behavior and requirement counts.
     */
    public static boolean shouldUseNative(int behaviorCount, int requirementCount) {
        return shouldUseNative(behaviorCount, requirementCount, 1);
    }

    /**
     * Selects Native only when the evaluator has enough behavior/word work to
     * amortize the JNI crossing. The requirement count remains a cheap guard
     * for CSR callers; packed callers are governed by the word-work threshold.
     */
    public static boolean shouldUseNative(int behaviorCount, int requirementCount, int memoryWordCount) {
        if (behaviorCount < 0 || requirementCount < 0 || memoryWordCount < 0) {
            throw new IllegalArgumentException("negative Brain eligibility work count");
        }
        if (behaviorCount == 0 || requirementCount < MINIMUM_REQUIREMENT_COUNT) return false;
        return behaviorCount >= MINIMUM_BEHAVIOR_COUNT
                && (long) behaviorCount * memoryWordCount >= MINIMUM_WORD_CHECKS;
    }

    public static int minimumRequirementCount() {
        return MINIMUM_REQUIREMENT_COUNT;
    }

    /**
     * Selects Java for small batches and for every unavailable native state.
     * A native exception permanently disables this module for the process and
     * retries the same immutable input through the Java evaluator.
     */
    public static boolean[] evaluate(Batch batch) {
        validate(batch);
        if (!shouldUseNative(batch.behaviorCount(), batch.requirementCount(), batch.memoryWordCount())) {
            return javaEvaluateUnchecked(batch);
        }

        LatticeNative.ensureLoaded();
        if (!isNativeUsable()) {
            return javaEvaluateUnchecked(batch);
        }

        try {
            long[] output = reusableOutputBits(batch.behaviorCount());
            nativeEvaluate(
                    batch.entityCount(), batch.memoryTypeCount(), batch.memoryWordCount(),
                    batch.registeredMemoryBits(), batch.presentMemoryBits(),
                    batch.behaviorEntityIndices(), batch.requirementOffsets(),
                    batch.requirementMemoryIds(), batch.requirementStatuses(), output);
            return decodeBits(output, batch.behaviorCount());
        } catch (UnsatisfiedLinkError | RuntimeException exception) {
            disableNative(exception);
            return javaEvaluateUnchecked(batch);
        }
    }

    /** Java reference implementation for parity tests and native fallback. */
    public static boolean[] javaEvaluate(Batch batch) {
        validate(batch);
        return javaEvaluateUnchecked(batch);
    }

    private static boolean[] javaEvaluateUnchecked(Batch batch) {
        boolean[] output = new boolean[batch.behaviorCount()];
        int wordsPerEntity = batch.memoryWordCount();
        for (int behavior = 0; behavior < batch.behaviorCount(); ++behavior) {
            int entity = batch.behaviorEntityIndices()[behavior];
            int wordBase = entity * wordsPerEntity;
            boolean eligible = true;
            for (int requirement = batch.requirementOffsets()[behavior];
                 requirement < batch.requirementOffsets()[behavior + 1];
                 ++requirement) {
                int memoryId = batch.requirementMemoryIds()[requirement];
                long bit = 1L << (memoryId & 63);
                int word = wordBase + (memoryId >>> 6);
                boolean registered = (batch.registeredMemoryBits()[word] & bit) != 0L;
                boolean present = (batch.presentMemoryBits()[word] & bit) != 0L;
                if (!matches(batch.requirementStatuses()[requirement], registered, present)) {
                    eligible = false;
                    break;
                }
            }
            output[behavior] = eligible;
        }
        return output;
    }

    private static boolean[] javaEvaluatePackedUnchecked(PackedBatch batch) {
        boolean[] output = new boolean[batch.behaviorCount()];
        int words = batch.memoryWordCount();
        for (int behavior = 0; behavior < batch.behaviorCount(); ++behavior) {
            int entityBase = batch.behaviorEntityIndices()[behavior] * words;
            int maskBase = behavior * words;
            boolean eligible = true;
            for (int word = 0; word < words; ++word) {
                long registered = batch.registeredMemoryBits()[entityBase + word];
                long present = batch.presentMemoryBits()[entityBase + word];
                long requiredRegistered = batch.requiredRegisteredBits()[maskBase + word];
                long requiredPresent = batch.requiredPresentBits()[maskBase + word];
                long requiredAbsent = batch.requiredAbsentBits()[maskBase + word];
                if ((registered & requiredRegistered) != requiredRegistered
                        || (present & requiredPresent) != requiredPresent
                        || (registered & requiredAbsent) != requiredAbsent
                        || (present & requiredAbsent) != 0L) {
                    eligible = false;
                    break;
                }
            }
            output[behavior] = eligible;
        }
        return output;
    }

    private static void javaEvaluatePackedIntoUnchecked(PackedBatch batch, long[] outputEligibleBits) {
        int outputWords = wordsForBehaviors(batch.behaviorCount());
        java.util.Arrays.fill(outputEligibleBits, 0, outputWords, 0L);
        int words = batch.memoryWordCount();
        for (int behavior = 0; behavior < batch.behaviorCount(); ++behavior) {
            int entityBase = batch.behaviorEntityIndices()[behavior] * words;
            int maskBase = behavior * words;
            boolean eligible = true;
            for (int word = 0; word < words; ++word) {
                long registered = batch.registeredMemoryBits()[entityBase + word];
                long present = batch.presentMemoryBits()[entityBase + word];
                long requiredRegistered = batch.requiredRegisteredBits()[maskBase + word];
                long requiredPresent = batch.requiredPresentBits()[maskBase + word];
                long requiredAbsent = batch.requiredAbsentBits()[maskBase + word];
                if ((registered & requiredRegistered) != requiredRegistered
                        || (present & requiredPresent) != requiredPresent
                        || (registered & requiredAbsent) != requiredAbsent
                        || (present & requiredAbsent) != 0L) {
                    eligible = false;
                    break;
                }
            }
            if (eligible) {
                outputEligibleBits[behavior >>> 6] |= 1L << (behavior & 63);
            }
        }
    }

    private static boolean matches(byte status, boolean registered, boolean present) {
        return switch (status) {
            case VALUE_PRESENT -> registered && present;
            case VALUE_ABSENT -> registered && !present;
            case REGISTERED -> registered;
            default -> false;
        };
    }

    private static void evaluatePackedIntoValidated(PackedBatch batch, long[] outputEligibleBits) {
        if (!shouldUseNative(batch.behaviorCount(), batch.requirementCount(), batch.memoryWordCount())) {
            javaEvaluatePackedIntoUnchecked(batch, outputEligibleBits);
            return;
        }
        LatticeNative.ensureLoaded();
        if (!isNativeUsable()) {
            javaEvaluatePackedIntoUnchecked(batch, outputEligibleBits);
            return;
        }
        try {
            nativeEvaluatePackedInto(batch.entityCount(), batch.memoryWordCount(), batch.behaviorCount(),
                    batch.registeredMemoryBits(), batch.presentMemoryBits(), batch.behaviorEntityIndices(),
                    batch.requiredRegisteredBits(), batch.requiredPresentBits(), batch.requiredAbsentBits(), outputEligibleBits);
        } catch (UnsatisfiedLinkError | RuntimeException exception) {
            disableNative(exception);
            javaEvaluatePackedIntoUnchecked(batch, outputEligibleBits);
        }
    }

    private static long[] reusableOutputBits(int behaviorCount) {
        int outputWords = wordsForBehaviors(behaviorCount);
        long[] output = NATIVE_OUTPUT_BITS.get();
        if (output.length < outputWords) {
            output = new long[outputWords];
            NATIVE_OUTPUT_BITS.set(output);
        }
        return output;
    }

    private static void validateOutputBits(int behaviorCount, long[] outputEligibleBits) {
        Objects.requireNonNull(outputEligibleBits, "outputEligibleBits");
        if (outputEligibleBits.length < wordsForBehaviors(behaviorCount)) {
            throw new IllegalArgumentException("Brain eligibility output bitmap length mismatch");
        }
    }

    private static boolean[] decodeBits(long[] output, int behaviorCount) {
        boolean[] decoded = new boolean[behaviorCount];
        for (int behavior = 0; behavior < behaviorCount; ++behavior) {
            decoded[behavior] = (output[behavior >>> 6] & (1L << (behavior & 63))) != 0L;
        }
        return decoded;
    }

    private static void validate(Batch batch) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(batch.registeredMemoryBits(), "registeredMemoryBits");
        Objects.requireNonNull(batch.presentMemoryBits(), "presentMemoryBits");
        Objects.requireNonNull(batch.behaviorEntityIndices(), "behaviorEntityIndices");
        Objects.requireNonNull(batch.requirementOffsets(), "requirementOffsets");
        Objects.requireNonNull(batch.requirementMemoryIds(), "requirementMemoryIds");
        Objects.requireNonNull(batch.requirementStatuses(), "requirementStatuses");
        if (batch.entityCount() < 0 || batch.memoryTypeCount() < 0) {
            throw new IllegalArgumentException("negative Brain eligibility count");
        }
        int wordCount = batch.memoryWordCount();
        final int expectedBitWords;
        try {
            expectedBitWords = Math.multiplyExact(batch.entityCount(), wordCount);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Brain eligibility bitset size overflow", exception);
        }
        if (batch.registeredMemoryBits().length != expectedBitWords
                || batch.presentMemoryBits().length != expectedBitWords) {
            throw new IllegalArgumentException("Brain eligibility bitset length mismatch");
        }
        final int expectedOffsetCount;
        try {
            expectedOffsetCount = Math.addExact(batch.behaviorCount(), 1);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Brain eligibility behavior count overflow", exception);
        }
        if (batch.requirementOffsets().length != expectedOffsetCount
                || batch.requirementStatuses().length != batch.requirementCount()) {
            throw new IllegalArgumentException("Brain eligibility CSR length mismatch");
        }
        if (batch.requirementOffsets()[0] != 0
                || batch.requirementOffsets()[batch.behaviorCount()] != batch.requirementCount()) {
            throw new IllegalArgumentException("Brain eligibility CSR boundary mismatch");
        }
        for (int behavior = 0; behavior < batch.behaviorCount(); ++behavior) {
            int entity = batch.behaviorEntityIndices()[behavior];
            int begin = batch.requirementOffsets()[behavior];
            int end = batch.requirementOffsets()[behavior + 1];
            if (entity < 0 || entity >= batch.entityCount()
                    || begin < 0 || end < begin || end > batch.requirementCount()) {
                throw new IllegalArgumentException("invalid Brain eligibility behavior CSR entry");
            }
        }
        for (int requirement = 0; requirement < batch.requirementCount(); ++requirement) {
            int memoryId = batch.requirementMemoryIds()[requirement];
            byte status = batch.requirementStatuses()[requirement];
            if (memoryId < 0 || memoryId >= batch.memoryTypeCount()
                    || status < VALUE_PRESENT || status > REGISTERED) {
                throw new IllegalArgumentException("invalid Brain eligibility memory requirement");
            }
        }
    }

    private static void validatePacked(PackedBatch batch) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(batch.registeredMemoryBits(), "registeredMemoryBits");
        Objects.requireNonNull(batch.presentMemoryBits(), "presentMemoryBits");
        Objects.requireNonNull(batch.behaviorEntityIndices(), "behaviorEntityIndices");
        Objects.requireNonNull(batch.requiredRegisteredBits(), "requiredRegisteredBits");
        Objects.requireNonNull(batch.requiredPresentBits(), "requiredPresentBits");
        Objects.requireNonNull(batch.requiredAbsentBits(), "requiredAbsentBits");
        if (batch.entityCount() < 0 || batch.memoryWordCount() < 0 || batch.requirementCount() < 0) {
            throw new IllegalArgumentException("negative Brain eligibility packed count");
        }
        int entityWords = Math.multiplyExact(batch.entityCount(), batch.memoryWordCount());
        int behaviorWords = Math.multiplyExact(batch.behaviorCount(), batch.memoryWordCount());
        if (batch.registeredMemoryBits().length != entityWords || batch.presentMemoryBits().length != entityWords
                || batch.requiredRegisteredBits().length != behaviorWords
                || batch.requiredPresentBits().length != behaviorWords
                || batch.requiredAbsentBits().length != behaviorWords) {
            throw new IllegalArgumentException("Brain eligibility packed array length mismatch");
        }
        for (int entity : batch.behaviorEntityIndices()) {
            if (entity < 0 || entity >= batch.entityCount()) {
                throw new IllegalArgumentException("invalid Brain eligibility packed entity index");
            }
        }
    }

    private static int wordsForMemoryTypes(int memoryTypeCount) {
        if (memoryTypeCount < 0) {
            throw new IllegalArgumentException("negative Brain eligibility memory type count");
        }
        return (int)(((long)memoryTypeCount + 63L) >>> 6);
    }

    private static int wordsForBehaviors(int behaviorCount) {
        return (int)(((long)behaviorCount + 63L) >>> 6);
    }

    private static boolean isNativeUsable() {
        if (!LatticeNative.isLoaded() || nativeDisabled) return false;
        if (nativeChecked) return nativeCompatible;
        synchronized (NativeBrainEligibility.class) {
            if (nativeChecked) return nativeCompatible;
            try {
                int actual = nativeAbiVersion();
                nativeCompatible = actual == EXPECTED_NATIVE_ABI;
                if (!nativeCompatible) {
                    LatticeNative.logFallbackOnce("brain_eligibility",
                            "native ABI mismatch: expected " + EXPECTED_NATIVE_ABI + ", got " + actual);
                }
            } catch (UnsatisfiedLinkError | RuntimeException exception) {
                nativeCompatible = false;
                LatticeNative.logFallbackOnce("brain_eligibility",
                        "native ABI unavailable: " + exception.getMessage());
            }
            nativeChecked = true;
            return nativeCompatible;
        }
    }

    private static void disableNative(Throwable throwable) {
        nativeDisabled = true;
        LatticeNative.logFallbackOnce("brain_eligibility", throwable.getMessage());
    }

    private static native int nativeAbiVersion();

    private static native void nativeEvaluate(
            int entityCount,
            int memoryTypeCount,
            int memoryWordCount,
            long[] registeredMemoryBits,
            long[] presentMemoryBits,
            int[] behaviorEntityIndices,
            int[] requirementOffsets,
            int[] requirementMemoryIds,
            byte[] requirementStatuses,
            long[] outputEligibleBits);

    private static native void nativeEvaluatePackedInto(
            int entityCount,
            int memoryWordCount,
            int behaviorCount,
            long[] registeredMemoryBits,
            long[] presentMemoryBits,
            int[] behaviorEntityIndices,
            long[] requiredRegisteredBits,
            long[] requiredPresentBits,
            long[] requiredAbsentBits,
            long[] outputEligibleBits);
}
