package net.minecraft.world.entity.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.common.collect.Table;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import com.latticemc.lattice.nativelib.NativeBrainEligibility;
import io.papermc.paper.configuration.WorldConfiguration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.schedule.Activity;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BrainOptimizedCollectionTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void preservesStartOrderAndInvalidatesActiveGroupCache() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(100L);
        LivingEntity entity = mock(LivingEntity.class);
        Brain<LivingEntity> brain = new Brain<>(List.of(), List.of(), ImmutableList.of(), () -> null);
        RecordingBehavior first = new RecordingBehavior("first");
        RecordingBehavior second = new RecordingBehavior("second");
        first.allowStart = true;
        second.allowStart = true;

        brain.setCoreActivities(Set.of(Activity.CORE));
        brain.addActivity(Activity.CORE, ImmutableList.of(Pair.of(0, first)));
        brain.useDefaultActivity();
        brain.tick(level, entity);
        assertEquals(List.of("start:first", "tick:first"), first.events);

        brain.addActivity(Activity.CORE, ImmutableList.of(Pair.of(0, second)));
        brain.clearMemories();
        brain.tick(level, entity);
        assertEquals(List.of("start:second", "tick:second"), second.events);
        assertEquals(2, first.tickCount);
        assertEquals(List.of(first, second), brain.getRunningBehaviors());

        first.stopOnTick = true;
        brain.tick(level, entity);
        assertEquals(1, first.stopCount);
        assertEquals(List.of(second), brain.getRunningBehaviors());
    }

    @Test
    void rebuildsEligibilityPlanBeforeUsingBehaviorsFromANewActivity() {
        LiveContext context = liveContext();
        List<LiveBehavior> idleBehaviors = liveBehaviors(256, MemoryStatus.VALUE_ABSENT);
        List<LiveBehavior> workBehaviors = liveBehaviors(256, MemoryStatus.VALUE_PRESENT);
        assertNativeCandidate(idleBehaviors.size());

        context.brain.addActivity(Activity.IDLE, 0, ImmutableList.copyOf(idleBehaviors));
        context.brain.addActivity(Activity.WORK, 0, ImmutableList.copyOf(workBehaviors));
        context.brain.setDefaultActivity(Activity.IDLE);
        context.brain.useDefaultActivity();
        context.brain.tick(context.level, context.entity);

        context.brain.setActiveActivityToFirstValid(List.of(Activity.WORK));
        context.brain.tick(context.level, context.entity);

        assertEquals(0, startCount(workBehaviors));
        assertEquals(0, extraConditionCount(workBehaviors));
    }

    @Test
    void fallsBackToCurrentMemoryAfterAnEarlierBehaviorStarts() {
        LiveContext context = liveContext();
        List<LiveBehavior> behaviors = liveBehaviors(256, MemoryStatus.VALUE_ABSENT);
        LiveBehavior first = behaviors.getFirst();
        LiveBehavior second = new LiveBehavior("second", MemoryStatus.VALUE_PRESENT);
        behaviors.set(1, second);
        first.onStart = () -> context.brain.setMemory(MemoryModuleType.ATTACK_TARGET, context.entity);
        assertNativeCandidate(behaviors.size());

        context.brain.addActivity(Activity.IDLE, 0, ImmutableList.copyOf(behaviors));
        context.brain.setDefaultActivity(Activity.IDLE);
        context.brain.useDefaultActivity();
        context.brain.tick(context.level, context.entity);

        assertEquals(1, first.starts);
        assertEquals(1, second.starts);
        assertEquals(0, startCount(behaviors.subList(2, behaviors.size())));
    }

    @Test
    void keepsBelowThresholdBehaviorStartsOnTheirOriginalPath() {
        LiveContext context = liveContext();
        List<LiveBehavior> behaviors = liveBehaviors(255, MemoryStatus.VALUE_PRESENT);
        assertFalse(isNativeCandidate(behaviors.size()));
        context.brain.addActivity(Activity.IDLE, 0, ImmutableList.copyOf(behaviors));
        context.brain.setDefaultActivity(Activity.IDLE);
        context.brain.useDefaultActivity();
        context.brain.tick(context.level, context.entity);

        assertEquals(0, startCount(behaviors));
        assertEquals(0, extraConditionCount(behaviors));
    }

    @Test
    void skipsConsecutiveNativeNegativeBehaviorsWithoutTryingToStartThem() {
        LiveContext context = liveContext();
        context.brain.setMemory(MemoryModuleType.ATTACK_TARGET, context.entity);
        List<LiveBehavior> behaviors = liveBehaviors(256, MemoryStatus.VALUE_ABSENT);
        assertNativeCandidate(behaviors.size());

        context.brain.addActivity(Activity.IDLE, 0, ImmutableList.copyOf(behaviors));
        context.brain.setDefaultActivity(Activity.IDLE);
        context.brain.useDefaultActivity();
        context.brain.tick(context.level, context.entity);

        assertEquals(0, startCount(behaviors));
        assertEquals(0, extraConditionCount(behaviors));
    }

    @Test
    void refreshesRegisteredMemoryBitsAfterDirectMemoryMapMutation() {
        LiveContext context = liveContext();
        List<LiveBehavior> behaviors = liveBehaviors(256, MemoryStatus.VALUE_ABSENT);
        behaviors.forEach(behavior -> behavior.extraConditionsMet = false);
        assertNativeCandidate(behaviors.size());

        context.brain.addActivity(Activity.IDLE, 0, ImmutableList.copyOf(behaviors));
        context.brain.setDefaultActivity(Activity.IDLE);
        context.brain.useDefaultActivity();
        context.brain.tick(context.level, context.entity);

        assertEquals(0, startCount(behaviors));
        assertEquals(256, extraConditionCount(behaviors));

        context.brain.getMemories().remove(MemoryModuleType.ATTACK_TARGET);
        context.brain.tick(context.level, context.entity);

        assertEquals(0, startCount(behaviors));
        assertEquals(256, extraConditionCount(behaviors));
    }

    @Test
    void fallsBackToJavaAfterNativeEligibleBehaviorFailsExtraConditions() {
        LiveContext context = liveContext();
        List<LiveBehavior> behaviors = liveBehaviors(256, MemoryStatus.VALUE_ABSENT);
        LiveBehavior first = behaviors.getFirst();
        LiveBehavior second = behaviors.get(1);
        first.extraConditionsMet = false;
        assertNativeCandidate(behaviors.size());

        context.brain.addActivity(Activity.IDLE, 0, ImmutableList.copyOf(behaviors));
        context.brain.setDefaultActivity(Activity.IDLE);
        context.brain.useDefaultActivity();
        context.brain.tick(context.level, context.entity);

        assertEquals(0, first.starts);
        assertEquals(1, first.extraConditionChecks);
        assertEquals(0, first.memoryChecks);
        assertEquals(1, second.starts);
        assertEquals(1, second.extraConditionChecks);
        assertEquals(1, second.memoryChecks);
    }

    @Test
    void freezesBehaviorEntryConditionAtConstruction() {
        LiveContext context = liveContext();
        Map<MemoryModuleType<?>, MemoryStatus> entryCondition = new HashMap<>();
        entryCondition.put(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT);
        Behavior<LivingEntity> behavior = new Behavior<>(entryCondition) {};

        entryCondition.put(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT);

        assertEquals(Map.of(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_ABSENT), behavior.latticeEntryCondition());
        assertThrows(UnsupportedOperationException.class,
                () -> behavior.latticeEntryCondition().put(MemoryModuleType.ATTACK_TARGET, MemoryStatus.VALUE_PRESENT));
        assertTrue(behavior.tryStart(context.level, context.entity, 100L));
    }

    @Test
    void keepsOneShotOutsideTheNativeEligibilityBatch() {
        LiveContext context = liveContext();
        context.brain.setMemory(MemoryModuleType.ATTACK_TARGET, context.entity);
        List<LiveBehavior> behaviors = liveBehaviors(256, MemoryStatus.VALUE_ABSENT);
        AtomicInteger oneShotCalls = new AtomicInteger();
        OneShot<LivingEntity> oneShot = new OneShot<>() {
            @Override
            public boolean trigger(ServerLevel level, LivingEntity entity, long gameTime) {
                oneShotCalls.incrementAndGet();
                return true;
            }
        };
        List<BehaviorControl<? super LivingEntity>> controls = new ArrayList<>(behaviors);
        controls.add(oneShot);
        assertNativeCandidate(behaviors.size());

        context.brain.addActivity(Activity.IDLE, 0, ImmutableList.copyOf(controls));
        context.brain.setDefaultActivity(Activity.IDLE);
        context.brain.useDefaultActivity();
        context.brain.tick(context.level, context.entity);

        assertEquals(0, startCount(behaviors));
        assertEquals(1, oneShotCalls.get());
    }

    private static void assertNativeCandidate(int behaviorCount) {
        assertTrue(isNativeCandidate(behaviorCount));
    }

    private static boolean isNativeCandidate(int behaviorCount) {
        int memoryWords = (BuiltInRegistries.MEMORY_MODULE_TYPE.size() + 63) >>> 6;
        return NativeBrainEligibility.shouldUseNative(behaviorCount, behaviorCount, memoryWords);
    }

    private static LiveContext liveContext() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.getGameTime()).thenReturn(100L);
        when(level.getRandom()).thenReturn(RandomSource.create());
        WorldConfiguration configuration = mock(WorldConfiguration.class);
        WorldConfiguration.TickRates tickRates = mock(WorldConfiguration.TickRates.class);
        tickRates.behavior = mock(Table.class);
        configuration.tickRates = tickRates;
        when(level.paperConfig()).thenReturn(configuration);

        Brain<LivingEntity> brain = new Brain<>(List.of(MemoryModuleType.ATTACK_TARGET), List.of(), ImmutableList.of(), () -> null);
        LivingEntity entity = mock(LivingEntity.class);
        doReturn(brain).when(entity).getBrain();
        return new LiveContext(level, entity, brain);
    }

    private static List<LiveBehavior> liveBehaviors(int count, MemoryStatus status) {
        List<LiveBehavior> behaviors = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            behaviors.add(new LiveBehavior("live-" + index, status));
        }
        return behaviors;
    }

    private static int startCount(List<LiveBehavior> behaviors) {
        return behaviors.stream().mapToInt(behavior -> behavior.starts).sum();
    }

    private static int extraConditionCount(List<LiveBehavior> behaviors) {
        return behaviors.stream().mapToInt(behavior -> behavior.extraConditionChecks).sum();
    }

    private record LiveContext(ServerLevel level, LivingEntity entity, Brain<LivingEntity> brain) {
    }

    private static final class LiveBehavior extends Behavior<LivingEntity> {
        private final String name;
        private int starts;
        private int extraConditionChecks;
        private int memoryChecks;
        private boolean extraConditionsMet = true;
        private Runnable onStart = () -> {};

        private LiveBehavior(String name, MemoryStatus status) {
            super(Map.of(MemoryModuleType.ATTACK_TARGET, status));
            this.name = name;
        }

        @Override
        protected boolean checkExtraStartConditions(ServerLevel level, LivingEntity entity) {
            this.extraConditionChecks++;
            return this.extraConditionsMet;
        }

        @Override
        protected boolean hasRequiredMemories(LivingEntity entity) {
            this.memoryChecks++;
            return super.hasRequiredMemories(entity);
        }

        @Override
        protected void start(ServerLevel level, LivingEntity entity, long gameTime) {
            this.starts++;
            this.onStart.run();
        }

        @Override
        public String debugString() {
            return this.name;
        }
    }

    private static final class RecordingBehavior implements BehaviorControl<LivingEntity> {
        private final List<String> events = new java.util.ArrayList<>();
        private final String name;
        private Behavior.Status status = Behavior.Status.STOPPED;
        private boolean allowStart;
        private boolean stopOnTick;
        private int tickCount;
        private int stopCount;

        private RecordingBehavior(String name) {
            this.name = name;
        }

        @Override
        public Behavior.Status getStatus() {
            return this.status;
        }

        @Override
        public boolean tryStart(ServerLevel level, LivingEntity entity, long gameTime) {
            if (!this.allowStart || this.status != Behavior.Status.STOPPED) {
                return false;
            }
            this.status = Behavior.Status.RUNNING;
            this.events.add("start:" + this.name);
            return true;
        }

        @Override
        public void tickOrStop(ServerLevel level, LivingEntity entity, long gameTime) {
            this.tickCount++;
            this.events.add("tick:" + this.name);
            if (this.stopOnTick) {
                this.status = Behavior.Status.STOPPED;
                this.stopCount++;
            }
        }

        @Override
        public void doStop(ServerLevel level, LivingEntity entity, long gameTime) {
            this.status = Behavior.Status.STOPPED;
            this.stopCount++;
        }

        @Override
        public String debugString() {
            return this.name;
        }
    }
}
