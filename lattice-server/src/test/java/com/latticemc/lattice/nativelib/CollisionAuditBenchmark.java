package com.latticemc.lattice.nativelib;

import ca.spottedleaf.moonrise.patches.collisions.CollisionUtil;
import com.google.gson.GsonBuilder;
import com.sun.management.ThreadMXBean;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import net.minecraft.world.phys.AABB;

public final class CollisionAuditBenchmark {
    static final long SEED = 20261107L;
    static final int[] COUNTS = {1, 4, 7, 8, 32, 128};
    private static final String[] SINGLE_ARMS = {"production-route", "encoded-native", "java-list"};
    private static final String[] SWEEP_ARMS =
            {"flatten-native", "wrapper-native", "encoded-native", "java-sweep", "legacy-wrapper"};
    private static volatile long sink;

    private CollisionAuditBenchmark() {}

    public static void main(String[] args) throws Throwable {
        if (!NativeCollisionSweep.isAvailable()) throw new IllegalStateException(LatticeNative.failureReason());
        if (LatticeNative.VERIFY) throw new IllegalStateException("Disable VERIFY for timing");
        int warmup = Integer.getInteger("collisionAudit.warmup", 32);
        int samples = Integer.getInteger("collisionAudit.samples", 21);
        int rounds = Integer.getInteger("collisionAudit.rounds", 1024);
        if (warmup < 0 || samples < 1 || rounds < 1) throw new IllegalArgumentException("Invalid sample counts");
        Path output = Path.of(System.getProperty("collisionAudit.output")).toAbsolutePath();
        if (!Files.isDirectory(output.getParent()) || Files.exists(output)) {
            throw new IllegalArgumentException("Output requires an existing parent and a new filename: " + output);
        }
        ThreadMXBean bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemorySupported()) throw new IllegalStateException("Allocation counter unavailable");
        bean.setThreadAllocatedMemoryEnabled(true);
        long threadId = Thread.currentThread().threadId();
        MethodHandle rawSweep = rawSweep();
        ArrayList<Sample> measurements = new ArrayList<>();
        int scenarios = 0;
        for (boolean sweep : new boolean[] {false, true}) {
            String[] arms = sweep ? SWEEP_ARMS : SINGLE_ARMS;
            for (int count : COUNTS) {
                for (int axis = 0; axis < 3; ++axis) {
                    for (int sign : new int[] {-1, 1}) {
                        Fixture fixture = new Fixture(count, axis, sign);
                        for (int sample = -warmup; sample < samples; ++sample) {
                            int first = Math.floorMod(sample + warmup, arms.length);
                            for (int offset = 0; offset < arms.length; ++offset) {
                                int arm = (first + offset) % arms.length;
                                long allocationStart = bean.getThreadAllocatedBytes(threadId);
                                if (allocationStart < 0) throw new IllegalStateException("Allocation counter unavailable");
                                long before = System.nanoTime();
                                long checksum = 0;
                                for (int round = 0; round < rounds; ++round) {
                                    double value = sweep ? fixture.sweep(arm, rawSweep, round % 3) : fixture.single(arm);
                                    checksum += Double.doubleToLongBits(value);
                                }
                                long elapsed = System.nanoTime() - before;
                                long allocated = bean.getThreadAllocatedBytes(threadId) - allocationStart;
                                sink = checksum;
                                fixture.verifyInputs();
                                long expectedChecksum = 0;
                                for (int round = 0; round < rounds; ++round) {
                                    double expected = sweep ? fixture.expectedSweep[round % 3] : fixture.expectedSingle;
                                    expectedChecksum += Double.doubleToLongBits(expected);
                                }
                                if (checksum != expectedChecksum) throw new AssertionError("Batch checksum mismatch");
                                if (sweep) fixture.verifySweep(arm);
                                else if (Double.doubleToLongBits(fixture.lastSingle)
                                        != Double.doubleToLongBits(fixture.expectedSingle)) {
                                    throw new AssertionError("Single-axis oracle mismatch");
                                }
                                if (elapsed < 0 || allocated < 0) throw new AssertionError("Invalid counter delta");
                                if (sample >= 0) {
                                    measurements.add(new Sample(sweep ? "sweep" : "single", count, axis, sign,
                                            fixture.swapAxes, arms[arm], sample, rounds, elapsed, allocated, checksum));
                                }
                            }
                        }
                        ++scenarios;
                    }
                }
            }
        }
        Report report = new Report(System.getProperty("java.version"), LatticeNative.cpuSummary(),
                ProcessHandle.current().pid(), SEED, warmup, samples, rounds, scenarios, "passed", measurements);
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(report),
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("collision-audit-complete scenarios=" + scenarios + " parity=passed output=" + output);
    }

    static MethodHandle rawSweep() throws ReflectiveOperationException {
        return MethodHandles.privateLookupIn(NativeCollisionSweep.class, MethodHandles.lookup()).unreflect(
                NativeCollisionSweep.class.getDeclaredMethod("nativeAdjustMovement",
                        double[].class, double[].class, double[].class, int.class));
    }

    static final class Fixture {
        final int axis;
        final List<AABB> boxes;
        final AABB movingBox = new AABB(-0.375, -1.25, 0.625, 0.5, 0.25, 1.125);
        final double[] moving = {-0.375, -1.25, 0.625, 0.5, 0.25, 1.125};
        final double[] originalMoving = moving.clone();
        final double[] desired;
        final double[] movement = new double[3];
        final double[] obstacles;
        final double[] originalObstacles;
        final double[] rawMoving;
        final double[] originalRawMoving;
        final double[] rawObstacles;
        final double[] rawMovement = new double[3];
        final double[] originalRawObstacles;
        final boolean swapAxes;
        final double expectedSingle;
        final double[] expectedSweep;
        double lastSingle;

        Fixture(int count, int axis, int sign) {
            this.axis = axis;
            desired = new double[] {sign * 0.375, sign * 0.25, sign * 0.125};
            desired[axis] = sign * 1.5;
            swapAxes = Math.abs(desired[0]) < Math.abs(desired[2]);
            Random random = new Random(SEED + count * 31L + axis * 7L + sign);
            ArrayList<AABB> input = new ArrayList<>();
            for (int i = 0; i < count; ++i) {
                double x = random.nextInt(9) - 4;
                double y = random.nextInt(9) - 4;
                double z = random.nextInt(9) - 4;
                input.add(new AABB(x, y, z, x + 0.5, y + 0.75, z + 0.5));
            }
            double[] min = {moving[0] - 0.5, moving[1] - 0.5, moving[2] - 0.5};
            double[] max = {moving[3] + 0.5, moving[4] + 0.5, moving[5] + 0.5};
            min[axis] = sign > 0 ? moving[axis + 3] + 0.25 : moving[axis] - 0.75;
            max[axis] = sign > 0 ? moving[axis + 3] + 0.75 : moving[axis] - 0.25;
            input.set(count - 1, new AABB(min[0], min[1], min[2], max[0], max[1], max[2]));
            boxes = List.copyOf(input);
            obstacles = NativeCollisionSweep.flattenAabbs(boxes);
            originalObstacles = obstacles.clone();
            rawMoving = swapAxes ? remap(moving) : moving.clone();
            originalRawMoving = rawMoving.clone();
            rawObstacles = swapAxes ? remap(obstacles) : obstacles.clone();
            originalRawObstacles = rawObstacles.clone();
            expectedSingle = oracleSingle(axis, movingBox, desired[axis], boxes);
            expectedSweep = oracleSweep(movingBox, desired, boxes);
        }

        double single(int arm) {
            lastSingle = switch (arm) {
                case 0 -> switch (axis) {
                    case 0 -> CollisionUtil.performAABBCollisionsX(movingBox, desired[axis], boxes);
                    case 1 -> CollisionUtil.performAABBCollisionsY(movingBox, desired[axis], boxes);
                    default -> CollisionUtil.performAABBCollisionsZ(movingBox, desired[axis], boxes);
                };
                case 1 -> NativeCollisionSweep.calcMaxOffset(axis, moving, desired[axis], obstacles, boxes.size());
                case 2 -> oracleSingle(axis, movingBox, desired[axis], boxes);
                default -> throw new IllegalArgumentException("Unknown single arm");
            };
            return lastSingle;
        }

        double sweep(int arm, MethodHandle rawSweep, int component) throws Throwable {
            System.arraycopy(desired, 0, movement, 0, 3);
            if (arm == 2) {
                rawMovement[0] = desired[swapAxes ? 2 : 0];
                rawMovement[1] = desired[1];
                rawMovement[2] = desired[swapAxes ? 0 : 2];
                rawSweep.invokeExact(rawMoving, rawMovement, rawObstacles, boxes.size());
                return rawMovement[swapAxes ? 2 - component : component];
            }
            if (arm == 0 || arm == 1) {
                double[] encoded = arm == 0 ? NativeCollisionSweep.flattenAabbs(boxes) : obstacles;
                NativeCollisionSweep.adjustMovement(moving, movement, encoded, boxes.size());
            } else if (arm == 3) {
                NativeCollisionSweep.javaAdjustMovementVanilla(moving, movement, obstacles, boxes.size());
            } else if (arm == 4) {
                legacyAdjustMovement(rawSweep, moving, movement, obstacles, boxes.size());
            } else {
                throw new IllegalArgumentException("Unknown sweep arm");
            }
            return movement[component];
        }

        void verifySweep(int arm) {
            for (int component = 0; component < 3; ++component) {
                double actual = arm == 2 ? rawMovement[swapAxes ? 2 - component : component] : movement[component];
                if (Double.doubleToLongBits(actual) != Double.doubleToLongBits(expectedSweep[component])) {
                    throw new AssertionError("Sweep oracle mismatch");
                }
            }
        }

        void verifyInputs() {
            if (!Arrays.equals(moving, originalMoving)
                    || !Arrays.equals(rawMoving, originalRawMoving)
                    || !Arrays.equals(obstacles, originalObstacles)
                    || !Arrays.equals(rawObstacles, originalRawObstacles)) {
                throw new AssertionError("Collision input changed");
            }
        }
    }

    static double oracleSingle(int axis, AABB moving, double desired, List<AABB> boxes) {
        int count = boxes.size();
        switch (axis) {
            case 0 -> {
                for (int i = 0; i < count; ++i) desired = CollisionUtil.collideX(boxes.get(i), moving, desired);
            }
            case 1 -> {
                for (int i = 0; i < count; ++i) desired = CollisionUtil.collideY(boxes.get(i), moving, desired);
            }
            case 2 -> {
                for (int i = 0; i < count; ++i) desired = CollisionUtil.collideZ(boxes.get(i), moving, desired);
            }
            default -> throw new IllegalArgumentException("Invalid axis");
        }
        return desired;
    }

    static double[] oracleSweep(AABB moving, double[] desired, List<AABB> boxes) {
        double[] result = desired.clone();
        int[] order = Math.abs(desired[0]) < Math.abs(desired[2]) ? new int[] {1, 2, 0} : new int[] {1, 0, 2};
        for (int axis : order) {
            result[axis] = oracleSingle(axis, moving, result[axis], boxes);
            moving = moving.move(axis == 0 ? result[axis] : 0, axis == 1 ? result[axis] : 0,
                    axis == 2 ? result[axis] : 0);
        }
        return result;
    }

    static void legacyAdjustMovement(MethodHandle rawSweep, double[] moving, double[] movement,
                                     double[] obstacles, int count) throws Throwable {
        if (moving == null || movement == null) throw new IllegalArgumentException("null moving/movement array");
        if (moving.length < 6 || movement.length < 3) throw new IllegalArgumentException("moving/movement too short");
        if (count < 0) throw new IllegalArgumentException("negative obstacle count");
        if (count > 0 && (obstacles == null || obstacles.length < count * 6)) {
            throw new IllegalArgumentException("obstacles too short");
        }
        if (Math.abs(movement[0]) >= Math.abs(movement[2])) {
            rawSweep.invokeExact(moving, movement, obstacles, count);
            return;
        }
        double[] remappedMoving = remap(moving);
        double[] remappedMovement = {movement[2], movement[1], movement[0]};
        double[] remappedObstacles = remap(obstacles, count * 6);
        rawSweep.invokeExact(remappedMoving, remappedMovement, remappedObstacles, count);
        movement[0] = remappedMovement[2];
        movement[1] = remappedMovement[1];
        movement[2] = remappedMovement[0];
    }

    private static double[] remap(double[] boxes) {
        return remap(boxes, boxes.length);
    }

    private static double[] remap(double[] boxes, int length) {
        double[] result = new double[length];
        for (int base = 0; base < length; base += 6) {
            result[base] = boxes[base + 2];
            result[base + 1] = boxes[base + 1];
            result[base + 2] = boxes[base];
            result[base + 3] = boxes[base + 5];
            result[base + 4] = boxes[base + 4];
            result[base + 5] = boxes[base + 3];
        }
        return result;
    }

    private record Sample(String mode, int count, int axis, int sign, boolean swapAxes, String path,
                          int sample, int rounds, long elapsedNs, long allocatedBytes, long checksum) {}

    private record Report(String java, String cpu, long pid, long seed, int warmup, int samples, int rounds,
                          int scenarios, String parity, List<Sample> measurements) {}
}
