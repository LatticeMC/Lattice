package net.minecraft.world.level.pathfinder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Mob;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class WalkMaskTestSuite {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }
    private static class SetEvaluator extends WalkNodeEvaluator {}

    private record Result(PathType type, List<String> calls) {}
    private static Result evaluate(WalkNodeEvaluator evaluator, int seed, int width) {
        List<String> calls = new ArrayList<>();
        Mob mob = mock(Mob.class);
        when(mob.blockPosition()).thenAnswer(invocation -> { calls.add("position"); return new BlockPos(100, 100, 100); });
        when(mob.getPathfindingMalus(any())).thenAnswer(invocation -> {
            PathType type = invocation.getArgument(0); calls.add("malus:" + type);
            return type.getMalus();
        });
        PathfindingContext context = mock(PathfindingContext.class);
        PathType[] types = {PathType.WALKABLE, PathType.BLOCKED, PathType.WATER, PathType.DOOR_OPEN,
            PathType.DOOR_WOOD_CLOSED, PathType.RAIL, PathType.FENCE, PathType.COCOA};
        when(context.getPathTypeFromState(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
            int x = invocation.getArgument(0), y = invocation.getArgument(1), z = invocation.getArgument(2);
            calls.add(x + ":" + y + ":" + z);
            return types[Math.floorMod(seed + x * 7 + y * 11 + z * 13, types.length)];
        });
        evaluator.mob = mob; evaluator.entityWidth = width; evaluator.entityHeight = 2; evaluator.entityDepth = 3;
        evaluator.setCanOpenDoors((seed & 1) == 0); evaluator.setCanPassDoors((seed & 2) == 0);
        return new Result(evaluator.getPathTypeOfMob(context, -3, 4, 2, mob), calls);
    }

    @Test void maskSelectionPreservesSetResultsAndEveryGetterCall() {
        for (int seed = 0; seed < 32; seed++) for (int width : new int[]{0, 1, 2, 3}) {
            assertEquals(evaluate(new SetEvaluator(), seed, width), evaluate(new WalkNodeEvaluator(), seed, width));
        }
    }

    @Test void publicOverrideOrderAndChangingMalusRemainObservable() {
        WalkNodeEvaluator evaluator = new WalkNodeEvaluator() {
            @Override public Set<PathType> getPathTypeWithinMobBB(PathfindingContext context, int x, int y, int z) {
                return new LinkedHashSet<>(List.of(PathType.COCOA, PathType.WALKABLE));
            }
        };
        evaluator.entityWidth = 2;
        Mob mob = mock(Mob.class);
        assertEquals(PathType.WALKABLE, evaluator.getPathTypeOfMob(null, 0, 0, 0, mob));
        evaluator = new WalkNodeEvaluator() {
            @Override public Set<PathType> getPathTypeWithinMobBB(PathfindingContext context, int x, int y, int z) { return Set.of(PathType.WATER); }
        };
        evaluator.entityWidth = 2;
        when(mob.getPathfindingMalus(PathType.WATER)).thenReturn(0F, -2F);
        when(mob.getPathfindingMalus(PathType.BLOCKED)).thenReturn(-1F);
        assertEquals(PathType.BLOCKED, evaluator.getPathTypeOfMob(null, 0, 0, 0, mob));
        verify(mob, times(2)).getPathfindingMalus(PathType.WATER);
    }

    @Test void all24NeighbourOffsetsRetainOrderAndEarlyReturn() {
        List<BlockPos> expected = new ArrayList<>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) for (int z = -1; z <= 1; z++) {
            if (x != 0 || z != 0) expected.add(new BlockPos(x + 13, y - 5, z + 18));
        }
        PathType[] dangers = {PathType.DAMAGE_OTHER, PathType.DAMAGE_FIRE, PathType.LAVA, PathType.WATER, PathType.DAMAGE_CAUTIOUS};
        PathType[] results = {PathType.DANGER_OTHER, PathType.DANGER_FIRE, PathType.DANGER_FIRE, PathType.WATER_BORDER, PathType.DAMAGE_CAUTIOUS};
        for (int stop = 0; stop <= 24; stop++) for (int danger = 0; danger < dangers.length; danger++) {
            List<BlockPos> calls = new ArrayList<>(); int selected = stop; PathType hit = dangers[danger];
            PathfindingContext context = mock(PathfindingContext.class);
            when(context.getPathTypeFromState(anyInt(), anyInt(), anyInt())).thenAnswer(invocation -> {
                calls.add(new BlockPos(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
                return calls.size() - 1 == selected ? hit : PathType.WALKABLE;
            });
            assertEquals(stop == 24 ? PathType.WALKABLE : results[danger], WalkNodeEvaluator.checkNeighbourBlocks(context, 13, -5, 18, PathType.WALKABLE));
            assertEquals(expected.subList(0, Math.min(stop + 1, 24)), calls);
        }
    }
}
