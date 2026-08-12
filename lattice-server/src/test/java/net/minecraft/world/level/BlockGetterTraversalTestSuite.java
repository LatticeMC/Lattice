package net.minecraft.world.level;

import static org.junit.jupiter.api.Assertions.assertEquals;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlockGetterTraversalTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preservesBaselineVisitsForStaticSmallTravelAndEarlyStop() {
        Vec3 travel = new Vec3(0.1, 0.1, 0.1);
        AABB boundingBox = new AABB(0.4, 0.4, 0.4, 0.9, 0.9, 0.9);

        assertTraversalMatchesBaseline(travel, boundingBox, Integer.MAX_VALUE);
        assertTraversalMatchesBaseline(travel, boundingBox, 1);
    }

    @Test
    void preservesBaselineVisitsAtNegativeTOneBoundary() {
        assertTraversalMatchesBaseline(new Vec3(-0.2, 0.0, -0.1), new AABB(5.0, 0.2, 0.4, 5.6, 0.8, 0.8), Integer.MAX_VALUE);
    }

    @Test
    void preservesBaselineVisitsAtZeroAxisIntegerPlane() {
        assertTraversalMatchesBaseline(new Vec3(0.1, 0.0, 0.0), new AABB(0.4, 0.0, 0.4, 0.9, 0.5, 0.9), Integer.MAX_VALUE);
    }

    @Test
    void preservesBaselineVisitsForMultiAxisTie() {
        assertTraversalMatchesBaseline(new Vec3(0.5, 0.5, 0.5), new AABB(1.0, 1.0, 1.0, 1.5, 1.5, 1.5), Integer.MAX_VALUE);
    }

    @Test
    void preservesOuterTraversalWhenVisitorReenters() {
        Vec3 travel = new Vec3(0.5, 0.5, 0.5);
        AABB boundingBox = new AABB(1.0, 1.0, 1.0, 1.5, 1.5, 1.5);
        List<Visit> expected = new ArrayList<>();
        boolean expectedResult = baselineForEachBlockIntersectedBetween(Vec3.ZERO, travel, boundingBox, visitor(expected, Integer.MAX_VALUE));
        List<Visit> actual = new ArrayList<>();
        boolean[] reentered = new boolean[1];
        boolean actualResult = BlockGetter.forEachBlockIntersectedBetween(Vec3.ZERO, travel, boundingBox, (pos, index) -> {
            actual.add(new Visit(pos.asLong(), index));
            if (!reentered[0]) {
                reentered[0] = true;
                BlockGetter.forEachBlockIntersectedBetween(Vec3.ZERO, new Vec3(-0.25, 0.25, 0.0), boundingBox, (ignoredPos, ignoredIndex) -> true);
            }

            return true;
        });

        assertEquals(expected, actual);
        assertEquals(expectedResult, actualResult);
    }

    private static void assertTraversalMatchesBaseline(Vec3 travel, AABB boundingBox, int stopAfter) {
        List<Visit> expected = new ArrayList<>();
        boolean expectedResult = baselineForEachBlockIntersectedBetween(Vec3.ZERO, travel, boundingBox, visitor(expected, stopAfter));
        List<Visit> actual = new ArrayList<>();
        boolean actualResult = BlockGetter.forEachBlockIntersectedBetween(Vec3.ZERO, travel, boundingBox, visitor(actual, stopAfter));

        assertEquals(expected, actual);
        assertEquals(expectedResult, actualResult);
    }

    private static BlockGetter.BlockStepVisitor visitor(List<Visit> visits, int stopAfter) {
        return (pos, index) -> {
            visits.add(new Visit(pos.asLong(), index));
            return visits.size() < stopAfter;
        };
    }

    private static boolean baselineForEachBlockIntersectedBetween(Vec3 from, Vec3 to, AABB boundingBox, BlockGetter.BlockStepVisitor visitor) {
        Vec3 travel = to.subtract(from);
        if (travel.lengthSqr() < Mth.square(1.0E-5F)) {
            for (BlockPos pos : BlockPos.betweenClosed(boundingBox)) {
                if (!visitor.visit(pos, 0)) {
                    return false;
                }
            }

            return true;
        }

        LongSet visited = new LongOpenHashSet();
        for (BlockPos pos : BlockPos.betweenCornersInDirection(boundingBox.move(travel.scale(-1.0)), travel)) {
            if (!visitor.visit(pos, 0)) {
                return false;
            }

            visited.add(pos.asLong());
        }

        int steps = baselineAddCollisionsAlongTravel(visited, travel, boundingBox, visitor);
        if (steps < 0) {
            return false;
        }

        for (BlockPos pos : BlockPos.betweenCornersInDirection(boundingBox, travel)) {
            if (visited.add(pos.asLong()) && !visitor.visit(pos, steps + 1)) {
                return false;
            }
        }

        return true;
    }

    private static int baselineAddCollisionsAlongTravel(LongSet output, Vec3 travel, AABB boundingBox, BlockGetter.BlockStepVisitor visitor) {
        double xsize = boundingBox.getXsize();
        double ysize = boundingBox.getYsize();
        double zsize = boundingBox.getZsize();
        Vec3i furthestCorner = getFurthestCorner(travel);
        Vec3 corner = boundingBox.getCenter();
        Vec3 end = new Vec3(
            corner.x() + xsize * 0.5 * furthestCorner.getX(),
            corner.y() + ysize * 0.5 * furthestCorner.getY(),
            corner.z() + zsize * 0.5 * furthestCorner.getZ()
        );
        Vec3 start = end.subtract(travel);
        int x = Mth.floor(start.x);
        int y = Mth.floor(start.y);
        int z = Mth.floor(start.z);
        int xDirection = Mth.sign(travel.x);
        int yDirection = Mth.sign(travel.y);
        int zDirection = Mth.sign(travel.z);
        double xStep = xDirection == 0 ? Double.MAX_VALUE : xDirection / travel.x;
        double yStep = yDirection == 0 ? Double.MAX_VALUE : yDirection / travel.y;
        double zStep = zDirection == 0 ? Double.MAX_VALUE : zDirection / travel.z;
        double xDistance = xStep * (xDirection > 0 ? 1.0 - Mth.frac(start.x) : Mth.frac(start.x));
        double yDistance = yStep * (yDirection > 0 ? 1.0 - Mth.frac(start.y) : Mth.frac(start.y));
        double zDistance = zStep * (zDirection > 0 ? 1.0 - Mth.frac(start.z) : Mth.frac(start.z));
        int steps = 0;

        while (xDistance <= 1.0 || yDistance <= 1.0 || zDistance <= 1.0) {
            if (xDistance < yDistance) {
                if (xDistance < zDistance) {
                    x += xDirection;
                    xDistance += xStep;
                } else {
                    z += zDirection;
                    zDistance += zStep;
                }
            } else if (yDistance < zDistance) {
                y += yDirection;
                yDistance += yStep;
            } else {
                z += zDirection;
                zDistance += zStep;
            }

            Optional<Vec3> intersection = AABB.clip(x, y, z, x + 1, y + 1, z + 1, start, end);
            if (intersection.isPresent()) {
                steps++;
                Vec3 point = intersection.get();
                double clampedX = Mth.clamp(point.x, x + 1.0E-5F, x + 1.0 - 1.0E-5F);
                double clampedY = Mth.clamp(point.y, y + 1.0E-5F, y + 1.0 - 1.0E-5F);
                double clampedZ = Mth.clamp(point.z, z + 1.0E-5F, z + 1.0 - 1.0E-5F);
                int endX = Mth.floor(clampedX - xsize * furthestCorner.getX());
                int endY = Mth.floor(clampedY - ysize * furthestCorner.getY());
                int endZ = Mth.floor(clampedZ - zsize * furthestCorner.getZ());
                int index = steps;

                for (BlockPos pos : BlockPos.betweenCornersInDirection(x, y, z, endX, endY, endZ, travel)) {
                    if (output.add(pos.asLong()) && !visitor.visit(pos, index)) {
                        return -1;
                    }
                }
            }
        }

        return steps;
    }

    private static Vec3i getFurthestCorner(Vec3 vector) {
        double x = Math.abs(Vec3.X_AXIS.dot(vector));
        double y = Math.abs(Vec3.Y_AXIS.dot(vector));
        double z = Math.abs(Vec3.Z_AXIS.dot(vector));
        int xDirection = vector.x >= 0.0 ? 1 : -1;
        int yDirection = vector.y >= 0.0 ? 1 : -1;
        int zDirection = vector.z >= 0.0 ? 1 : -1;
        if (x <= y && x <= z) {
            return new Vec3i(-xDirection, -zDirection, yDirection);
        }

        return y <= z ? new Vec3i(zDirection, -yDirection, -xDirection) : new Vec3i(-yDirection, xDirection, -zDirection);
    }

    private record Visit(long blockPos, int index) {
    }
}
