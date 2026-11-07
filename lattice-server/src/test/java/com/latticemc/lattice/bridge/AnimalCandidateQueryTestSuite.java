package com.latticemc.lattice.bridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.latticemc.lattice.nativelib.LatticeNative;
import com.latticemc.lattice.nativelib.NativeEntityQuery;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.fox.Fox;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AnimalCandidateQueryTestSuite {
    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void lowCountOrTypeHitRateScansOnceWithoutSnapshotWork() {
        for (boolean prey : new boolean[] {false, true}) {
            for (boolean available : new boolean[] {false, true}) {
                for (int count : new int[] {0, 1, 7, 8, 32}) {
                    for (int percent : new int[] {0, 50, 100}) {
                        int matches = count * percent / 100;
                        if (available && matches >= 8) continue;
                        Fixture f = new Fixture(count, matches);
                        try (var nativeQuery = mockStatic(NativeEntityQuery.class)) {
                            nativeQuery.when(NativeEntityQuery::isAvailable).thenReturn(available);
                            LivingEntity result = f.find(prey, f.foxPredicate());
                            assertSame(matches == 0 ? null : f.candidates.get(matches - 1), result);
                            f.verifyOneScan();
                            nativeQuery.verify(NativeEntityQuery::isAvailable);
                            nativeQuery.verifyNoMoreInteractions();
                            for (LivingEntity candidate : f.candidates) verify(candidate, never()).getBoundingBox();
                        }
                    }
                }
            }
        }
    }

    @Test
    void fallbackFinishesPredicatesBeforeDistancesAndPreservesSideEffects() {
        for (boolean prey : new boolean[] {false, true}) {
            Fixture f = new Fixture(3, 3);
            try (var nativeQuery = mockStatic(NativeEntityQuery.class)) {
                nativeQuery.when(NativeEntityQuery::isAvailable).thenReturn(true);
                LivingEntity result = f.find(prey, entity -> {
                    f.events.add("predicate-" + entity.getId());
                    if (entity == f.candidates.get(2)) f.distances.put(f.candidates.get(0), 0.0);
                    return true;
                });
                assertSame(f.candidates.get(0), result);
                assertEquals(List.of("predicate-1", "predicate-2", "predicate-3",
                        "distance-1", "distance-2", "distance-3"), f.events);
                f.verifyOneScan();
            }
        }
    }

    @Test
    void fallbackShortCircuitsSelfDeadSpectatorAndRetainsRangeTieNaNRules() {
        for (boolean available : new boolean[] {false, true}) {
            Fixture f = new Fixture(6, 6);
            f.candidates.set(0, f.self);
            when(f.candidates.get(1).isAlive()).thenReturn(false);
            when(f.candidates.get(2).isSpectator()).thenReturn(true);
            f.distances.put(f.candidates.get(3), Double.NaN);
            f.distances.put(f.candidates.get(4), 100.0);
            f.distances.put(f.candidates.get(5), 100.0);
            try (var nativeQuery = mockStatic(NativeEntityQuery.class)) {
                nativeQuery.when(NativeEntityQuery::isAvailable).thenReturn(available);
                assertSame(f.candidates.get(5), f.find(false, entity -> {
                    f.events.add("predicate-" + entity.getId());
                    return true;
                }));
                assertEquals(List.of("predicate-4", "predicate-5", "predicate-6",
                        "distance-4", "distance-5", "distance-6"), f.events);
                f.verifyOneScan();
            }
        }
    }

    @Test
    void predicateFailurePropagatesWithoutDistanceReadsOrRescan() {
        for (boolean prey : new boolean[] {false, true}) {
            for (boolean available : new boolean[] {false, true}) {
                Fixture f = new Fixture(3, 3);
                RuntimeException failure = new IllegalStateException("predicate failed");
                try (var nativeQuery = mockStatic(NativeEntityQuery.class)) {
                    nativeQuery.when(NativeEntityQuery::isAvailable).thenReturn(available);
                    RuntimeException actual = assertThrows(RuntimeException.class, () -> f.find(prey, entity -> {
                        if (entity == f.candidates.get(1)) throw failure;
                        return true;
                    }));
                    assertSame(failure, actual);
                    assertTrue(f.events.isEmpty());
                    f.verifyOneScan();
                    nativeQuery.verify(NativeEntityQuery::isAvailable);
                    nativeQuery.verifyNoMoreInteractions();
                }
            }
        }
    }

    @Test
    void highTypeHitPathUsesFullQueryAndExactSnapshotCount() {
        for (boolean prey : new boolean[] {false, true}) {
            for (int count : new int[] {8, 32}) {
                for (int percent : new int[] {50, 100}) {
                    int matches = count * percent / 100;
                    if (matches < 8) continue;
                    Fixture f = new Fixture(count, matches);
                    try (var nativeQuery = mockStatic(NativeEntityQuery.class)) {
                        nativeQuery.when(NativeEntityQuery::isAvailable).thenReturn(true);
                        nativeQuery.when(() -> NativeEntityQuery.queryAll(
                                anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyDouble(),
                                any(), isNull(), eq(NativeEntityQuery.PredicateKind.IS_ALIVE_NOT_SELF_NOT_SPEC),
                                eq(1000), eq(true), eq(0.0), eq(0.0), eq(0.0))).thenAnswer(call -> {
                            NativeEntityQuery.EntitySnapshot[] snapshots = call.getArgument(6);
                            assertEquals(matches, snapshots.length);
                            int[] ids = new int[matches];
                            for (int i = 0; i < matches; ++i) ids[i] = snapshots[matches - i - 1].id();
                            return ids;
                        });
                        assertSame(f.candidates.get(matches - 1), f.find(prey, f.foxPredicate()));
                        assertEquals(List.of("distance-" + matches, "predicate-" + matches), f.events);
                        for (int i = 0; i < count; ++i) {
                            verify(f.candidates.get(i), times(i < matches ? 1 : 0)).getBoundingBox();
                        }
                        f.verifyOneScan();
                    }
                }
            }
        }
    }

    @Test
    void actualJniFullQueryMatchesJavaAfterRejectingNearestTie() {
        assertTrue(NativeEntityQuery.isAvailable(), LatticeNative.failureReason());
        for (boolean prey : new boolean[] {false, true}) {
            Fixture f = new Fixture(32, 16);
            Predicate<LivingEntity> predicate = entity -> entity instanceof Fox && entity.getId() != 16;
            LivingEntity expected;
            try (var nativeQuery = mockStatic(NativeEntityQuery.class)) {
                nativeQuery.when(NativeEntityQuery::isAvailable).thenReturn(false);
                expected = f.find(prey, predicate);
            }
            clearInvocations(f.level);
            assertSame(f.candidates.get(14), expected);
            assertSame(expected, f.find(prey, predicate));
            f.verifyOneScan();
        }
    }

    private static final class Fixture {
        final Mob self = mock(Mob.class);
        final ServerLevel level = mock(ServerLevel.class);
        final List<LivingEntity> candidates = new ArrayList<>();
        final Map<LivingEntity, Double> distances = new HashMap<>();
        final List<String> events = new ArrayList<>();

        Fixture(int count, int matchingTypes) {
            when(self.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 1, 1, 1));
            when(self.getId()).thenReturn(1000);
            when(self.distanceToSqr(any(LivingEntity.class))).thenAnswer(call -> {
                LivingEntity entity = call.getArgument(0);
                events.add("distance-" + entity.getId());
                return distances.get(entity);
            });
            for (int i = 0; i < count; ++i) {
                LivingEntity entity = i < matchingTypes ? mock(Fox.class) : mock(LivingEntity.class);
                when(entity.getId()).thenReturn(i + 1);
                when(entity.isAlive()).thenReturn(true);
                when(entity.getBoundingBox()).thenReturn(new AABB(0, 0, 0, 2, 1, 1));
                doReturn(i < matchingTypes ? EntityType.FOX : EntityType.CHICKEN).when(entity).getType();
                when(entity.getX()).thenReturn(1.0);
                candidates.add(entity);
                distances.put(entity, 1.0);
                clearInvocations(entity);
            }
            when(level.getEntitiesOfClass(eq(LivingEntity.class), any(AABB.class), any())).thenAnswer(call -> {
                Predicate<LivingEntity> filter = call.getArgument(2);
                return candidates.stream().filter(filter).toList();
            });
        }

        Predicate<LivingEntity> foxPredicate() {
            return entity -> {
                events.add("predicate-" + entity.getId());
                return entity instanceof Fox;
            };
        }

        LivingEntity find(boolean prey, Predicate<LivingEntity> predicate) {
            return prey
                    ? PredatoryAnimalAiSupport.findNearestPrey(self, level, 10, predicate, Fox.class)
                    : HerbivoreAiSupport.findNearestThreat(self, level, 10, predicate, Fox.class);
        }

        void verifyOneScan() {
            verify(level, times(1)).getEntitiesOfClass(eq(LivingEntity.class), any(AABB.class), any());
        }
    }
}
