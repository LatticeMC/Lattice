package com.latticemc.lattice.nativelib;

/**
 * Same-process ABBA harness. The existing benchmark owns scenes, parity and measurements;
 * this driver only selects isolated native variants and region sizes.
 */
public final class PathfinderOptimizationBenchmark {
    static native void nativeSelectMasks(boolean fused);
    static native void nativeSelectArithmetic(boolean optimized);
    static native void nativeSelectLifecycle(boolean legacy);

    public static void main(String[] args) throws Exception {
        if (!NativePathfinder.isAvailable()) throw new IllegalStateException("native pathfinder unavailable");
        String mode = System.getProperty("lattice.pathAuditMode", "masks");
        if (!mode.equals("masks") && !mode.equals("arithmetic") && !mode.equals("lifecycle")) {
            throw new IllegalArgumentException("unsupported audit mode: " + mode);
        }
        int rounds = Integer.getInteger("lattice.pathAuditRounds", 1);
        if (rounds <= 0) throw new IllegalArgumentException("rounds must be positive");
        try {
            for (String text : System.getProperty("lattice.pathAuditSizes", "32,64,128,256").split(",")) {
                int size = Integer.parseInt(text.trim());
                if (size <= 0) throw new IllegalArgumentException("size must be positive");
                System.setProperty("lattice.pathSizeX", Integer.toString(size));
                System.setProperty("lattice.pathSizeZ", Integer.toString(size));
                for (int round = 0; round < rounds; round++) {
                    for (int step = 0; step < 4; step++) {
                        boolean candidate = step == 1 || step == 2;
                        nativeSelectMasks(mode.equals("masks") && candidate);
                        nativeSelectArithmetic(mode.equals("arithmetic") && candidate);
                        nativeSelectLifecycle(mode.equals("lifecycle") && !candidate);
                        System.out.printf("PATHFIND_AB mode=%s variant=%s size=%d round=%d step=%d%n",
                                mode, candidate ? "candidate" : "baseline", size, round, step);
                        PathfinderSearchBenchmark.main(args);
                    }
                }
            }
        } finally {
            nativeSelectMasks(false);
            nativeSelectArithmetic(false);
            nativeSelectLifecycle(false);
        }
    }
}
