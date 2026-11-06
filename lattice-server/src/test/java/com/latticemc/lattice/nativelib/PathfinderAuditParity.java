package com.latticemc.lattice.nativelib;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;

/** Untimed sidecar: reuse benchmark fixtures, compare each output before its buffer is reused. */
public final class PathfinderAuditParity {
    private static final Class<?> FIXTURE = PathfinderSearchBenchmark.class;

    public static void main(String[] args) throws Exception {
        if (!NativePathfinder.isAvailable()) throw new IllegalStateException("native pathfinder unavailable");
        Method search = PathFinder.class.getDeclaredMethod(
                "findPath", Node.class, List.class, float.class, int.class, float.class);
        search.setAccessible(true);
        field(FIXTURE, "vanillaFindPath").set(null, search);
        // Fail explicitly on an older fixture rather than running a different matrix.
        if (field(FIXTURE, "SNAPSHOT_WORLD_KEY").getType() != long.class) {
            throw new IllegalStateException("audit requires the generation-aware snapshot benchmark fixture");
        }
        try {
            for (String mode : new String[]{"masks", "arithmetic", "lifecycle"}) {
                for (boolean candidate : new boolean[]{false, true}) {
                    PathfinderOptimizationBenchmark.nativeSelectMasks(mode.equals("masks") && candidate);
                    PathfinderOptimizationBenchmark.nativeSelectArithmetic(mode.equals("arithmetic") && candidate);
                    PathfinderOptimizationBenchmark.nativeSelectLifecycle(mode.equals("lifecycle") && !candidate);
                    for (int size : new int[]{32, 64, 128, 256}) {
                        for (boolean shortPath : new boolean[]{true, false}) {
                            for (String world : new String[]{"serpentine", "village"}) {
                                checkScene(mode, candidate, size, shortPath, world);
                            }
                        }
                    }
                }
            }
        } finally {
            PathfinderOptimizationBenchmark.nativeSelectMasks(false);
            PathfinderOptimizationBenchmark.nativeSelectArithmetic(false);
            PathfinderOptimizationBenchmark.nativeSelectLifecycle(false);
            NativePathfinder.setCardinalFastPathEnabledForBenchmark(true);
        }
        System.out.println("PATHFIND_SIDECAR_COMPLETE checks=960");
    }

    private static void checkScene(String mode, boolean candidate, int size,
                                   boolean shortPath, String world) throws Exception {
        int startX = shortPath ? 2 : 0, startZ = shortPath ? 2 : 1;
        int targetX = shortPath ? 5 : size - 1, targetZ = shortPath ? 2 : size - 2;
        int maxNodes = size * size;
        float range = 100000.0F;
        Class<?> inputsClass = Class.forName(FIXTURE.getName() + "$NativeInputs");
        Class<?> implClass = Class.forName(FIXTURE.getName() + "$Impl");
        final Object inputs;
        final Path vanilla;
        if (world.equals("serpentine")) {
            boolean[] grid = (boolean[]) method(FIXTURE, shortPath ? "buildOpenGrid" : "buildGrid",
                    int.class, int.class).invoke(null, size, size);
            inputs = constructor(inputsClass, boolean[].class, int.class, int.class, int.class, boolean.class)
                    .newInstance(grid, size, size, maxNodes, true);
            vanilla = (Path) method(FIXTURE, "vanillaPath", boolean[].class, int.class, int.class,
                    int.class, int.class, int.class, int.class, int.class, float.class)
                    .invoke(null, grid, size, size, startX, startZ, targetX, targetZ, maxNodes, range);
        } else {
            byte[] types = (byte[]) method(FIXTURE, shortPath ? "buildOpenPathTypes" : "buildVillageGrid",
                    int.class, int.class).invoke(null, size, size);
            inputs = constructor(inputsClass, byte[].class, float[].class, int.class, int.class,
                    int.class, boolean.class).newInstance(types, new float[types.length], size, size, maxNodes, true);
            Class<?> evaluatorClass = Class.forName(FIXTURE.getName() + "$VillageNodeEvaluator");
            Object evaluator = constructor(evaluatorClass, byte[].class, int.class, int.class, float[].class,
                    boolean.class, boolean.class, boolean.class, float.class, float.class)
                    .newInstance(types, size, size, NativePathfinder.pathfindingMalusFor(PathType.values()),
                            false, false, false, 0.6F, 1.0F);
            vanilla = (Path) method(FIXTURE, "vanillaPathVillage", evaluatorClass, int.class, int.class,
                    int.class, int.class, int.class, float.class)
                    .invoke(null, evaluator, startX, startZ, targetX, targetZ, maxNodes, range);
        }
        Method call = method(FIXTURE, "callNativeResult", implClass, inputsClass, int.class, int.class,
                int.class, int.class, int.class, int.class, int.class, float.class);
        Field key = field(FIXTURE, "SNAPSHOT_WORLD_KEY");
        long previous = key.getLong(null);
        long generation = NativePathfinder.createMirrorWorld();
        key.setLong(null, generation);
        try {
            List<?> implementations = Arrays.asList(implClass.getEnumConstants());
            for (boolean reverse : new boolean[]{false, true}) {
                for (Object impl : reverse ? implementations.reversed() : implementations) {
                    if (((Enum<?>) impl).name().equals("VANILLA")) continue;
                    NativePathfinder.PathResult result = (NativePathfinder.PathResult) call.invoke(
                            null, impl, inputs, size, size, startX, startZ, targetX, targetZ, maxNodes, range);
                    long checksum = verify(vanilla, result);
                    System.out.printf("PATHFIND_SIDECAR mode=%s variant=%s size=%d length=%s world=%s "
                                    + "reverse=%s impl=%s nodes=%d checksum=%d%n",
                            mode, candidate ? "candidate" : "baseline", size, shortPath ? "short" : "long",
                            world, reverse, impl, result.length(), checksum);
                }
            }
        } finally {
            NativePathfinder.retireMirrorWorld(generation);
            key.setLong(null, previous);
        }
    }

    static long verify(Path vanilla, NativePathfinder.PathResult actual) {
        if (vanilla == null || actual == null || vanilla.canReach() != actual.reachedTarget()
                || vanilla.getNodeCount() != actual.length() || actual.targetIndex() != 0) {
            throw new AssertionError("sidecar path header mismatch");
        }
        long checksum = 0xcbf29ce484222325L;
        for (int i = 0; i < actual.length(); i++) {
            Node expected = vanilla.getNode(i);
            if (expected.x != actual.x(i) || expected.y != actual.y(i) || expected.z != actual.z(i)) {
                throw new AssertionError("sidecar coordinate mismatch at " + i);
            }
            checksum = (checksum ^ actual.x(i)) * 0x100000001b3L;
            checksum = (checksum ^ actual.y(i)) * 0x100000001b3L;
            checksum = (checksum ^ actual.z(i)) * 0x100000001b3L;
        }
        return checksum;
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws Exception {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Constructor<?> constructor(Class<?> type, Class<?>... parameters) throws Exception {
        Constructor<?> constructor = type.getDeclaredConstructor(parameters);
        constructor.setAccessible(true);
        return constructor;
    }

    private static Field field(Class<?> type, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
