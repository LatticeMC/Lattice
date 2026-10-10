package net.minecraft.world.entity.ai.behavior;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.datafixers.util.Pair;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import org.junit.jupiter.api.Test;

public class GateScanBoundaryTestSuite {
    private static class Child implements BehaviorControl<LivingEntity> {
        Behavior.Status status = Behavior.Status.RUNNING;
        Runnable onTick = () -> {};
        Runnable onStatus = () -> {};
        public Behavior.Status getStatus() { onStatus.run(); return status; }
        public boolean tryStart(ServerLevel level, LivingEntity entity, long time) { status = Behavior.Status.RUNNING; return true; }
        public void tickOrStop(ServerLevel level, LivingEntity entity, long time) { onTick.run(); }
        public void doStop(ServerLevel level, LivingEntity entity, long time) { status = Behavior.Status.STOPPED; }
        public String debugString() { return "boundary"; }
    }

    @Test void laterChildCanStopEarlierChildBeforeFinalScan() {
        Child first = new Child(), last = new Child();
        GateBehavior<LivingEntity> gate = new GateBehavior<>(Map.of(), Set.of(), GateBehavior.OrderPolicy.ORDERED,
            GateBehavior.RunningPolicy.TRY_ALL, List.of(Pair.of(first, 1), Pair.of(last, 1)));
        assertTrue(gate.tryStart(null, null, 0));
        last.onTick = () -> { first.status = Behavior.Status.STOPPED; last.status = Behavior.Status.STOPPED; };
        gate.tickOrStop(null, null, 1);
        assertEquals(Behavior.Status.STOPPED, gate.getStatus());
    }

    @Test void finalStreamScanDetectsReentrantShuffleEvenWhenPredicateMatches() {
        Child child = new Child();
        GateBehavior<LivingEntity> gate = new GateBehavior<>(Map.of(), Set.of(), GateBehavior.OrderPolicy.SHUFFLED,
            GateBehavior.RunningPolicy.RUN_ONE, List.of(Pair.of(child, 1)));
        assertTrue(gate.tryStart(null, null, 0));
        child.onTick = () -> child.onStatus = () -> {
            child.onStatus = () -> {};
            gate.tryStart(null, null, 1);
        };
        assertThrows(ConcurrentModificationException.class, () -> gate.tickOrStop(null, null, 1));
    }
}
