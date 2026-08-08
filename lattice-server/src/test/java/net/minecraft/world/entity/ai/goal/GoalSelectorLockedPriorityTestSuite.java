package net.minecraft.world.entity.ai.goal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.EnumSet;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GoalSelectorLockedPriorityTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void priorityMaskMatchesBaselineForReplacementAndDisabledFlags() throws Exception {
        assertSelectorsMatch(new RecordingGoal[] {
            new RecordingGoal(Goal.Flag.MOVE, true, true),
            new RecordingGoal(Goal.Flag.MOVE, true, true),
            new RecordingGoal(Goal.Flag.LOOK, true, false)
        }, new int[] {5, 1, 0}, false);

        assertSelectorsMatch(new RecordingGoal[] {
            new RecordingGoal(Goal.Flag.MOVE, false, true),
            new RecordingGoal(Goal.Flag.MOVE, true, true),
            new RecordingGoal(Goal.Flag.TARGET, true, true)
        }, new int[] {5, 1, 0}, false);

        assertSelectorsMatch(new RecordingGoal[] {
            new RecordingGoal(Goal.Flag.MOVE, true, true),
            new RecordingGoal(Goal.Flag.MOVE, true, true)
        }, new int[] {1, 1}, true);
    }

    private static void assertSelectorsMatch(RecordingGoal[] prototype, int[] priorities, boolean disableMove) throws Exception {
        RecordingGoal[] baselineGoals = copyOf(prototype);
        RecordingGoal[] fastGoals = copyOf(prototype);
        GoalSelector baseline = selectorWith(baselineGoals, priorities, disableMove);
        GoalSelector fast = selectorWith(fastGoals, priorities, disableMove);

        Method baselineMethod = GoalSelector.class.getDeclaredMethod("tickOriginal");
        baselineMethod.setAccessible(true);
        baselineMethod.invoke(baseline);
        Method fastMethod = GoalSelector.class.getDeclaredMethod("tickLockedPriorityFastPath");
        fastMethod.setAccessible(true);
        fastMethod.invoke(fast);

        for (int i = 0; i < prototype.length; i++) {
            assertEquals(baselineGoals[i].starts, fastGoals[i].starts, "starts " + i);
            assertEquals(baselineGoals[i].stops, fastGoals[i].stops, "stops " + i);
            assertEquals(baselineGoals[i].canUseCalls, fastGoals[i].canUseCalls, "canUse " + i);
        }
    }

    private static GoalSelector selectorWith(RecordingGoal[] goals, int[] priorities, boolean disableMove) {
        GoalSelector selector = new GoalSelector();
        for (int i = 0; i < goals.length; i++) {
            selector.addGoal(priorities[i], goals[i]);
        }
        if (disableMove) {
            selector.disableControlFlag(Goal.Flag.MOVE);
        }
        return selector;
    }

    private static RecordingGoal[] copyOf(RecordingGoal[] source) {
        RecordingGoal[] copy = new RecordingGoal[source.length];
        for (int i = 0; i < source.length; i++) {
            copy[i] = new RecordingGoal(source[i].flag, source[i].interruptable, source[i].canUse);
        }
        return copy;
    }

    private static final class RecordingGoal extends Goal {
        private final Goal.Flag flag;
        private final boolean interruptable;
        private final boolean canUse;
        private int starts;
        private int stops;
        private int canUseCalls;

        private RecordingGoal(Goal.Flag flag, boolean interruptable, boolean canUse) {
            this.flag = flag;
            this.interruptable = interruptable;
            this.canUse = canUse;
            this.setFlags(EnumSet.of(flag));
        }

        @Override
        public boolean canUse() {
            this.canUseCalls++;
            return this.canUse;
        }

        @Override
        public boolean canContinueToUse() {
            return true;
        }

        @Override
        public boolean isInterruptable() {
            return this.interruptable;
        }

        @Override
        public void start() {
            this.starts++;
        }

        @Override
        public void stop() {
            this.stops++;
        }
    }
}
