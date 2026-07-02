package com.latticemc.lattice.nativelib;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;

public final class NativeMaterialRules implements AutoCloseable {

    public static final int NO_MATCH = -1;
    public static final int BANDLANDS_SENTINEL = -2;
    private static final double[] EMPTY_DOUBLES = new double[0];
    private static final int[] EMPTY_INTS = new int[0];

    private final long handle;
    private final Map<String, Integer> noiseSlots = new HashMap<>();
    private boolean closed = false;

    public NativeMaterialRules() {
        LatticeNative.ensureLoaded();
        if (!LatticeNative.isLoaded()) {
            throw new IllegalStateException("NativeMaterialRules requires the lattice native library");
        }
        this.handle = nativeCreate();
        if (this.handle == 0L) {
            throw new IllegalStateException("nativeCreate returned 0");
        }
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public void setRootRule(int ruleRef) {
        checkOpen();
        nativeSetRootRule(handle, ruleRef);
    }

    public int evaluate(int[] ctxInts, double temperature, double surfaceNoise, double surfaceSecondaryNoise, double[] namedNoiseValues, byte[] ctxBools) {
        checkOpen();
        if (ctxInts == null || ctxBools == null) throw new IllegalArgumentException("null context array");
        if (ctxInts.length < 10 || ctxBools.length < 2) throw new IllegalArgumentException("context array too short");
        if (namedNoiseValues == null) namedNoiseValues = EMPTY_DOUBLES;
        return nativeEvaluate(handle, ctxInts, temperature, surfaceNoise, surfaceSecondaryNoise, namedNoiseValues, ctxBools);
    }

    public int[] evaluateBatch(int count,
                               int[] columnCtx,
                               double surfaceNoise,
                               double surfaceSecondaryNoise,
                               double[] namedNoiseValues,
                               byte[] columnBools,
                               int[] blockData) {
        checkOpen();
        if (count <= 0) return EMPTY_INTS;
        if (columnCtx == null || columnBools == null || blockData == null) throw new IllegalArgumentException("null batch context array");
        if (columnCtx.length < 6 || columnBools.length < 2) throw new IllegalArgumentException("batch context array too short");
        if (blockData.length < count * 5) throw new IllegalArgumentException("blockData array too short");
        if (namedNoiseValues == null) namedNoiseValues = EMPTY_DOUBLES;
        return nativeEvaluateBatch(handle, count, columnCtx, surfaceNoise, surfaceSecondaryNoise, namedNoiseValues, columnBools, blockData);
    }

    public int addCondHole() { checkOpen(); return nativeAddCondHole(handle); }
    public int addCondSteepSlope() { checkOpen(); return nativeAddCondSteepSlope(handle); }
    public int addCondTemperatureFrozen() { checkOpen(); return nativeAddCondTemperatureFrozen(handle); }
    public int addCondAbovePreliminarySurface() { checkOpen(); return nativeAddCondAbovePreliminarySurface(handle); }
    public int addCondNot(int innerCond) { checkOpen(); return nativeAddCondNot(handle, innerCond); }
    public int addCondAboveYWithSurface(int minY, int adjust) { checkOpen(); return nativeAddCondAboveYWithSurface(handle, minY, adjust); }
    public int addCondAboveYWithStoneDepth(int minY, int surfaceDepthMultiplier) { checkOpen(); return nativeAddCondAboveYWithStoneDepth(handle, minY, surfaceDepthMultiplier); }
    public int addCondStoneDepth(int offset, boolean adjustSurfaceDepth, int secondaryRange, boolean ceiling) {
        checkOpen();
        return nativeAddCondStoneDepth(handle, offset, adjustSurfaceDepth, secondaryRange, ceiling);
    }
    public int addCondVerticalGradient(int trueAtAndBelow, int falseAtAndAbove, RandomState randomState, Identifier randomName) {
        checkOpen();
        long[] seeds = positionalSeeds(randomState, randomName);
        return nativeAddCondVerticalGradient(handle, trueAtAndBelow, falseAtAndAbove, seeds[0], seeds[1]);
    }
    public int addCondNoiseThreshold(double lower, double upper) { checkOpen(); return nativeAddCondNoiseThreshold(handle, lower, upper); }
    public int addCondNamedNoiseThreshold(String noiseName, double lower, double upper) {
        checkOpen();
        int slot = noiseSlot(noiseName);
        return nativeAddCondNamedNoiseThreshold(handle, slot, lower, upper);
    }
    public int addCondSecondaryNoiseThreshold(double lower, double upper) { checkOpen(); return nativeAddCondSecondaryNoiseThreshold(handle, lower, upper); }
    public int addCondBiomeIs(int[] biomeIds) {
        checkOpen();
        if (biomeIds == null || biomeIds.length == 0) throw new IllegalArgumentException("biomeIds must be non-empty");
        return nativeAddCondBiomeIs(handle, biomeIds);
    }
    public int addCondWater(int offset, int surfaceDepthMultiplier, boolean addStoneDepth) {
        checkOpen();
        return nativeAddCondWater(handle, offset, surfaceDepthMultiplier, addStoneDepth);
    }
    public int addRuleBandlands() { checkOpen(); return nativeAddRuleBandlands(handle); }
    public int addRuleBlock(int blockIndex) { checkOpen(); return nativeAddRuleBlock(handle, blockIndex); }
    public int addRuleConditional(int condRef, int childRuleRef) { checkOpen(); return nativeAddRuleConditional(handle, condRef, childRuleRef); }
    public int addRuleSequence(int[] childRuleRefs) {
        checkOpen();
        if (childRuleRefs == null || childRuleRefs.length == 0) throw new IllegalArgumentException("childRuleRefs must be non-empty");
        return nativeAddRuleSequence(handle, childRuleRefs);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (handle != 0L) nativeDestroy(handle);
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("NativeMaterialRules is closed");
    }

    private static long[] positionalSeeds(RandomState randomState, Identifier randomName) {
        if (randomState == null || randomName == null) throw new IllegalArgumentException("randomState/randomName must be non-null");
        PositionalRandomFactory factory = randomState.getOrCreateRandomFactory(randomName);
        try {
            Class<?> impl = factory.getClass();
            Field lo = impl.getDeclaredField("seedLo");
            Field hi = impl.getDeclaredField("seedHi");
            lo.setAccessible(true);
            hi.setAccessible(true);
            return new long[] { lo.getLong(factory), hi.getLong(factory) };
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new IllegalStateException("PositionalRandomFactory implementation changed shape", e);
        }
    }

    private int noiseSlot(String noiseName) {
        Integer slot = noiseSlots.get(noiseName);
        if (slot != null) return slot.intValue();
        int next = noiseSlots.size();
        noiseSlots.put(noiseName, next);
        return next;
    }

    private static native long nativeCreate();
    private static native void nativeDestroy(long handle);
    private static native void nativeSetRootRule(long handle, int ruleRef);
    private static native int nativeEvaluate(long handle, int[] ctxInts, double temperature, double surfaceNoise, double surfaceSecondaryNoise, double[] namedNoiseValues, byte[] ctxBools);
    private static native int[] nativeEvaluateBatch(long handle, int count, int[] columnCtx, double surfaceNoise, double surfaceSecondaryNoise, double[] namedNoiseValues, byte[] columnBools, int[] blockData);
    private static native int nativeAddCondAboveYWithSurface(long h, int minY, int adjust);
    private static native int nativeAddCondAboveYWithStoneDepth(long h, int minY, int surfaceDepthMultiplier);
    private static native int nativeAddCondStoneDepth(long h, int offset, boolean adjustSurfaceDepth, int secondaryRange, boolean ceiling);
    private static native int nativeAddCondVerticalGradient(long h, int trueAtAndBelow, int falseAtAndAbove, long positionalSeedLo, long positionalSeedHi);
    private static native int nativeAddCondNoiseThreshold(long h, double lower, double upper);
    private static native int nativeAddCondNamedNoiseThreshold(long h, int noiseSlot, double lower, double upper);
    private static native int nativeAddCondSecondaryNoiseThreshold(long h, double lower, double upper);
    private static native int nativeAddCondHole(long h);
    private static native int nativeAddCondSteepSlope(long h);
    private static native int nativeAddCondTemperatureFrozen(long h);
    private static native int nativeAddCondBiomeIs(long h, int[] biomeIds);
    private static native int nativeAddCondWater(long h, int offset, int surfaceDepthMultiplier, boolean addStoneDepth);
    private static native int nativeAddCondAbovePreliminarySurface(long h);
    private static native int nativeAddCondNot(long h, int innerCond);
    private static native int nativeAddRuleBlock(long h, int blockIndex);
    private static native int nativeAddRuleConditional(long h, int condRef, int childRuleRef);
    private static native int nativeAddRuleSequence(long h, int[] childRuleRefs);
    private static native int nativeAddRuleBandlands(long h);
}
