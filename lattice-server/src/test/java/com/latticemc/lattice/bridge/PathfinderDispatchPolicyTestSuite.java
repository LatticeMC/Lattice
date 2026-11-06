package com.latticemc.lattice.bridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Standalone replayable tests; run with assertions enabled or directly via main. */
public final class PathfinderDispatchPolicyTestSuite {
    @Test
    void replay() throws Exception {
        main(new String[0]);
    }

    public static void main(String[] args) throws Exception {
        shortPathRejectsEvenWithCoverage();
        lowVolumeRejects();
        missRejectsWhenSnapshotCostsMoreThanJava();
        lowNodeBudgetCanMakeCoveredHitWorthwhile();
        exactCoverageChangesHitMissEstimate();
        tickBudgetIsSharedAcrossThreads();
        oldTickCannotResetNewBudget();
        regionVolumePricesMaterialization();
        mirrorFailureCannotBypassBudget();
        missDoesNotReserveTwice();
        configuredCostsRejectHighVolumeShortMiss();
        failedCoveredSearchIncludesAlreadyPaidCost();
        saturatedCostsRejectRatherThanWrap();
        nearbyTargetRejectsDespiteDistantTarget();
        zeroBudgetDisablesMissEvenWithFreeSnapshot();
        System.out.println("PathfinderDispatchPolicyTestSuite: PASS");
    }

    private static void nearbyTargetRejectsDespiteDistantTarget() {
        PathfinderDispatchPolicy policy = PathfinderDispatchPolicy.configured();
        check(policy.decide(35_000, 49_086, 209, 40_000, false).useNative(), "review counterexample");
        int far = PathfinderDispatchPolicy.distanceBeyondReach(10, 20, 30, 219, 20, 30, 0);
        int near = PathfinderDispatchPolicy.distanceBeyondReach(10, 20, 30, 12, 20, 30, 2);
        check(!policy.decide(35_000, 49_086, Math.min(near, far), 40_000, false).useNative(),
                "any target in reach ends the Java search immediately");
        check(PathfinderDispatchPolicy.distanceBeyondReach(Integer.MIN_VALUE, 0, 0,
                Integer.MAX_VALUE, 0, 0, 0) == Integer.MAX_VALUE, "coordinate difference cannot wrap");
    }

    private static void zeroBudgetDisablesMissEvenWithFreeSnapshot() {
        PathfinderDispatchPolicy policy = policy(1_000, 10, 100, 0, 0);
        PathfinderDispatchPolicy.TickBudget budget = new PathfinderDispatchPolicy.TickBudget(0);
        check(!budget.tryReserve(1, 0), "zero is the explicit snapshot disable switch");
        check(policy.admit(100, 200, 100, 100, false, 1, budget) == null,
                "zero-cost miss must not bypass disable");
        PathfinderDispatchPolicy.SnapshotAdmission hit = policy.admit(100, 200, 100, 100, true, 1, budget);
        check(hit != null, "disable leaves covered searches available");
        check(!hit.allowSnapshotAfterMirrorFailure(), "failed covered search must honor disable");
        check(new PathfinderDispatchPolicy.TickBudget(1).tryReserve(1, 0),
                "zero estimate remains valid when upload is enabled");
    }

    private static void shortPathRejectsEvenWithCoverage() {
        PathfinderDispatchPolicy policy = policy(1_000, 0, 110_000, 10, 0);
        check(!policy.decide(20_000, 25_000, 10, 10_000, true).useNative(), "short covered path must reject");
    }

    private static void lowVolumeRejects() {
        PathfinderDispatchPolicy policy = policy(1, 0, 0, 0, 8_192);
        check(!policy.decide(8_191, 1, 100, 100, true).useNative(), "low-volume region must reject");
    }

    private static void missRejectsWhenSnapshotCostsMoreThanJava() {
        PathfinderDispatchPolicy policy = policy(10, 0, 10, 100, 0);
        PathfinderDispatchPolicy.Decision decision = policy.decide(20_000, 1_000, 100, 10_000, false);
        check(!decision.useNative(), "expensive miss must reject");
    }

    private static void lowNodeBudgetCanMakeCoveredHitWorthwhile() {
        PathfinderDispatchPolicy policy = policy(10_000, 0, 100, 100, 0);
        PathfinderDispatchPolicy.Decision decision = policy.decide(20_000, 25_000, 100, 1, true);
        check(decision.useNative() && decision.nodes() == 1, "node estimate must respect actual budget");
    }

    private static void exactCoverageChangesHitMissEstimate() {
        PathfinderDispatchPolicy policy = policy(100, 0, 100, 10, 0);
        check(policy.decide(20_000, 25_000, 20, 10_000, true).useNative(), "covered request should hit cost model");
        check(!policy.decide(20_000, 25_000, 20, 10_000, false).useNative(), "uncovered request must include snapshot cost");
    }

    private static void tickBudgetIsSharedAcrossThreads() throws Exception {
        PathfinderDispatchPolicy.TickBudget budget = new PathfinderDispatchPolicy.TickBudget(100);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        Thread[] workers = new Thread[8];
        for (int i = 0; i < workers.length; ++i) {
            workers[i] = new Thread(() -> {
                await(start);
                if (budget.tryReserve(4, 20)) accepted.incrementAndGet();
            });
            workers[i].start();
        }
        start.countDown();
        for (Thread worker : workers) worker.join();
        check(accepted.get() == 5, "shared budget must admit exactly its aggregate capacity");
    }

    private static void oldTickCannotResetNewBudget() {
        PathfinderDispatchPolicy.TickBudget budget = new PathfinderDispatchPolicy.TickBudget(100);
        check(budget.tryReserve(10, 80), "initial reservation");
        check(budget.tryReserve(11, 80), "new tick starts a fresh budget");
        check(!budget.tryReserve(10, 1), "stale tick must not be accepted after tick advance");
        check(!budget.tryReserve(11, 21), "stale tick must not reset or reuse current budget");
    }

    private static PathfinderDispatchPolicy policy(long javaRate, long nativeRate,
                                                   long nativeFixed, long snapshotRate,
                                                   long minimumVolume) {
        return new PathfinderDispatchPolicy(javaRate, nativeRate, nativeFixed, 0, snapshotRate, minimumVolume);
    }

    private static void regionVolumePricesMaterialization() {
        PathfinderDispatchPolicy policy = new PathfinderDispatchPolicy(1_000, 500, 100, 10, 1, 0);
        check(policy.decide(1_000, 2_000, 50, 1_000, true).useNative(), "small covered region");
        check(!policy.decide(100_000, 200_000, 50, 1_000, true).useNative(), "materialization grows with region");
    }

    private static void mirrorFailureCannotBypassBudget() {
        PathfinderDispatchPolicy policy = policy(1_000, 10, 100, 1, 0);
        PathfinderDispatchPolicy.TickBudget budget = new PathfinderDispatchPolicy.TickBudget(100);
        PathfinderDispatchPolicy.SnapshotAdmission admission = policy.admit(100, 200, 100, 100, true, 1, budget);
        check(admission != null, "covered hit initially admitted");
        AtomicInteger uploads = new AtomicInteger();
        if (admission.allowSnapshotAfterMirrorFailure()) uploads.incrementAndGet();
        check(uploads.get() == 0, "mock mirror failure cannot upload without budget");
        check(budget.tryReserve(1, 100), "denied upload consumes no budget");
    }

    private static void missDoesNotReserveTwice() {
        PathfinderDispatchPolicy policy = policy(1_000, 10, 100, 1, 0);
        PathfinderDispatchPolicy.TickBudget budget = new PathfinderDispatchPolicy.TickBudget(200);
        PathfinderDispatchPolicy.SnapshotAdmission admission = policy.admit(100, 200, 100, 100, false, 1, budget);
        check(admission != null, "affordable miss admitted");
        check(admission.allowSnapshotAfterMirrorFailure(), "same request reuses its reservation");
        check(admission.allowSnapshotAfterMirrorFailure(), "replay cannot claim twice");
        check(!budget.tryReserve(1, 1), "budget charged exactly once");
    }

    private static void configuredCostsRejectHighVolumeShortMiss() {
        PathfinderDispatchPolicy policy = PathfinderDispatchPolicy.configured();
        check(!policy.decide(65_536, 258L * 3 * 258, 10, 65_536, false).useNative(), "measured short miss");
        check(!policy.decide(54_000, 65_000, 100, 100, false).useNative(), "low search budget");
    }

    private static void failedCoveredSearchIncludesAlreadyPaidCost() {
        PathfinderDispatchPolicy policy = policy(1_000, 700, 100, 1, 0);
        PathfinderDispatchPolicy.TickBudget budget = new PathfinderDispatchPolicy.TickBudget(1_000);
        check(policy.decide(100, 200, 100, 100, false).useNative(), "fresh miss is affordable");
        PathfinderDispatchPolicy.SnapshotAdmission admission = policy.admit(100, 200, 100, 100, true, 1, budget);
        check(admission != null, "covered hit admitted");
        check(!admission.allowSnapshotAfterMirrorFailure(), "failed search plus retry costs more than Java");
        check(budget.tryReserve(1, 1_000), "denied fallback does not charge budget");
        check(policy.admit(100, 200, 100, 100, false, 2, budget) != null,
                "fresh miss must not inherit the nonexistent first search cost");
    }

    private static void saturatedCostsRejectRatherThanWrap() {
        PathfinderDispatchPolicy policy = policy(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, 0);
        check(!policy.decide(Long.MAX_VALUE, Long.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, false).useNative(),
                "overflow must not turn expensive work into a negative cost");
        PathfinderDispatchPolicy.TickBudget budget = new PathfinderDispatchPolicy.TickBudget(Long.MAX_VALUE);
        check(budget.tryReserve(1, Long.MAX_VALUE), "maximum reservation");
        check(!budget.tryReserve(1, 1), "addition cannot wrap budget");
        check(!budget.tryReserve(2, -1), "negative request rejected");
        check(budget.tryReserve(2, 0), "rejected request leaves next tick usable");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
