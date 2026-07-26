package com.latticemc.lattice.nativelib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.Target;
import org.junit.jupiter.api.Test;

class NativePathfinderSearchParityTestSuite {
    private static final int SIZE_X = 7;
    private static final int SIZE_Z = 5;

    @Test
    void matchesVanillaSearchOrderAroundSymmetricObstacle() throws Exception {
        boolean[] passable = new boolean[SIZE_X * SIZE_Z];
        java.util.Arrays.fill(passable, true);
        for (int z = 1; z < 4; ++z) passable[index(3, z)] = false;

        Path vanilla = vanillaPath(passable);
        NativePathfinder.PathResult nativeResult = nativePath(passable);

        assertNotNull(vanilla);
        assertNotNull(nativeResult);
        assertEquals(vanilla.canReach(), nativeResult.reachedTarget());
        assertEquals(vanilla.getNodeCount(), nativeResult.length());
        for (int i = 0; i < vanilla.getNodeCount(); ++i) {
            Node node = vanilla.getNode(i);
            assertEquals(node.x, nativeResult.x(i), "x[" + i + "]");
            assertEquals(node.y, nativeResult.y(i), "y[" + i + "]");
            assertEquals(node.z, nativeResult.z(i), "z[" + i + "]");
        }
    }

    private static Path vanillaPath(boolean[] passable) throws Exception {
        GridNodeEvaluator evaluator = new GridNodeEvaluator(passable);
        PathFinder pathFinder = new PathFinder(evaluator, SIZE_X * SIZE_Z);
        Node start = evaluator.node(0, 2);
        Target target = new Target(6, 0, 2);
        Method findPath = PathFinder.class.getDeclaredMethod(
                "findPath", Node.class, List.class, float.class, int.class, float.class);
        findPath.setAccessible(true);
        return (Path)findPath.invoke(pathFinder, start, List.of(Map.entry(target, new BlockPos(6, 0, 2))), 64.0F, 0, 1.0F);
    }

    private static NativePathfinder.PathResult nativePath(boolean[] passable) {
        byte[] pathTypes = new byte[passable.length];
        for (int i = 0; i < passable.length; ++i) {
            pathTypes[i] = (byte)(passable[i] ? PathType.WALKABLE.ordinal() : PathType.BLOCKED.ordinal());
        }
        int maxVisitedNodes = SIZE_X * SIZE_Z;
        return NativePathfinder.findPath(
                pathTypes,
                0, 0, 0, SIZE_X, 1, SIZE_Z,
                0, 0, 2,
                new int[]{6}, new int[]{0}, new int[]{2}, 1,
                64.0F, maxVisitedNodes, 0,
                1, 1, 1.0F, 3,
                NativePathfinder.pathfindingMalusFor(PathType.values()),
                new int[3 + maxVisitedNodes * 3]);
    }

    private static int index(int x, int z) {
        return z * SIZE_X + x;
    }

    private static final class GridNodeEvaluator extends NodeEvaluator {
        private final boolean[] passable;
        private final Node[] cardinals = new Node[4];

        private GridNodeEvaluator(boolean[] passable) {
            this.passable = passable;
        }

        private Node node(int x, int z) {
            return this.getNode(x, 0, z);
        }

        @Override
        public Node getStart() {
            return this.node(0, 2);
        }

        @Override
        public Target getTarget(double x, double y, double z) {
            return new Target((int)x, (int)y, (int)z);
        }

        @Override
        public int getNeighbors(Node[] output, Node node) {
            int count = 0;
            int cardinalIndex = 0;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                Node neighbor = this.passable(node.x + direction.getStepX(), node.z + direction.getStepZ());
                this.cardinals[cardinalIndex++] = neighbor;
                if (neighbor != null && !neighbor.closed) output[count++] = neighbor;
            }
            for (int direction = 0; direction < 4; ++direction) {
                Node first = this.cardinals[direction];
                Node second = this.cardinals[(direction + 1) & 3];
                if (first == null || second == null) continue;
                int x = first.x + second.x - node.x;
                int z = first.z + second.z - node.z;
                Node diagonal = this.passable(x, z);
                if (diagonal != null && !diagonal.closed) output[count++] = diagonal;
            }
            return count;
        }

        private Node passable(int x, int z) {
            if (x < 0 || x >= SIZE_X || z < 0 || z >= SIZE_Z || !this.passable[index(x, z)]) return null;
            Node node = this.node(x, z);
            node.type = PathType.WALKABLE;
            node.costMalus = 0.0F;
            return node;
        }

        @Override
        public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
            return PathType.WALKABLE;
        }

        @Override
        public PathType getPathType(PathfindingContext context, int x, int y, int z) {
            return PathType.WALKABLE;
        }
    }
}
