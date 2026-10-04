package ca.spottedleaf.moonrise.common.time;

import static ca.spottedleaf.moonrise.common.time.TickDataTestSupport.tick;
import static org.junit.jupiter.api.Assertions.*;

import ca.spottedleaf.concurrentutil.util.TimeUtil;
import ca.spottedleaf.moonrise.common.time.TickDataTestSupport.OriginalTickData;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TickDataTestSuite {
    private static final long FIRST = TimeUtil.DEADLINE_NOT_SET;

    private static final class Pair {
        final TickData actual;
        final OriginalTickData original;

        Pair(long interval) {
            actual = new TickData(interval);
            original = new OriginalTickData(interval);
        }

        void add(TickTime time) {
            original.addDataFrom(time);
            actual.addDataFrom(time);
        }

        void query(TickTime inProgress, long interval) {
            Double expected = original.getTPSAverage(inProgress, interval);
            Double result = actual.getTPSAverage(inProgress, interval);
            if (expected == null) {
                assertNull(result);
            } else {
                assertNotNull(result);
                assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(result));
            }
        }
    }

    @Test void emptyAndInProgressNeverPersistSamples() {
        Pair pair = new Pair(100);
        pair.query(null, 50);
        pair.query(tick(FIRST, 10, 20), 50);
        pair.query(tick(FIRST, 10, 80), 50);
        pair.query(null, 50);
        pair.add(tick(FIRST, 10, 20));
        pair.query(null, 50);
        pair.query(tick(10, 60, 5), 50);
        pair.query(null, 50);
        assertEquals(20_000_000., pair.actual.getTPSAverage(null, 50));
    }

    @Test void changingRateRecalculatesFirstTickAndPreservesOrdinaryTicks() {
        Pair pair = new Pair(1_000);
        pair.add(tick(FIRST, 100, 30));
        pair.add(tick(100, 140, 35));
        for (long interval : new long[] {50, 1, 100, 50, 0, -1, Long.MIN_VALUE, Long.MAX_VALUE, 50}) {
            pair.query(null, interval);
            pair.query(tick(FIRST, 180, 80), interval);
            pair.query(null, interval);
        }
        pair.add(tick(FIRST, 200, 70)); // 公开 TickData 接口允许多个无前序样本。
        pair.query(null, 50);
        pair.query(null, 100);
    }

    @Test void evictionBoundaryAndMultipleEvictionsMatchOriginal() {
        Pair pair = new Pair(100);
        pair.add(tick(FIRST, 0, 10));
        pair.query(null, 50);
        pair.add(tick(0, 110, 5)); // start - first.end == interval，旧样本仍在。
        pair.query(null, 50);
        assertEquals(2, pair.actual.generateTickReport(null, 115, 50).collectedTicks());
        pair.add(tick(110, 111, 5));
        pair.query(null, 50);
        assertEquals(2, pair.actual.generateTickReport(null, 116, 50).collectedTicks());
        pair.add(tick(111, 1_000, 10)); // 一次移除全部旧样本。
        pair.query(null, 50);
        assertEquals(1, pair.actual.generateTickReport(null, 1_010, 50).collectedTicks());
        pair.query(null, 20);
    }

    @Test void queriesCanStartLateAndPauseAcrossManyWrites() {
        Pair pair = new Pair(500);
        for (int i = 0; i < 100; i++) pair.add(tick(i * 50L, i * 50L + 50, 10));
        pair.query(null, 50);
        for (int i = 100; i < 1_000; i++) pair.add(tick(i * 50L, i * 50L + 50, 10));
        pair.query(null, 50);
        pair.query(null, 1);
    }

    @Test void timestampWrapAndModularSumOverflowKeepOriginalBits() {
        Pair wrap = new Pair(100);
        long previous = Long.MAX_VALUE - 80;
        for (int i = 0; i < 20; i++) {
            long start = previous + 20;
            wrap.add(tick(previous, start, 5));
            wrap.query(tick(start, start + 20, 5), 20);
            previous = start;
        }
        Pair overflow = new Pair(0);
        overflow.add(tick(Long.MIN_VALUE + 1, 0, 0));
        overflow.query(null, 0);
        overflow.add(tick(Long.MIN_VALUE + 1, 0, 0)); // 总和溢出为 -2。
        overflow.query(null, 0);
        assertEquals(2.0 / (-2.0 / 1.0E9), overflow.actual.getTPSAverage(null, 0));
        overflow.query(tick(-2, 0, 0), 0); // 总和为 0，保持 Infinity。
        overflow.add(tick(0, 1, 0)); // 溢出后的缓存减去全部旧样本。
        overflow.query(null, 0);
    }

    @Test void deterministicMixedOperationsAndMSPTRemainConsistent() {
        Random random = new Random(0xB3);
        Pair pair = new Pair(5_000);
        long previous = 0;
        long interval = 50;
        for (int i = 0; i < 20_000; i++) {
            long start = previous + random.nextInt(100);
            pair.add(tick(i % 127 == 0 ? FIRST : previous, start, random.nextInt(80)));
            previous = start;
            if (i % 31 == 0) interval = random.nextInt(200);
            if (i % 5 == 0) {
                pair.query(null, interval);
                pair.query(tick(i % 3 == 0 ? FIRST : start, start + 50, 40), interval);
            }
            if (i % 101 == 0) {
                long[] expected = pair.original.timeData.stream().mapToLong(TickTime::tickLength).toArray();
                TickData.MSPTData mspt = pair.actual.getMSPTData(null, interval);
                assertArrayEquals(expected, mspt.rawData());
                long total = 0;
                for (long length : expected) total += length;
                assertEquals((double) total / expected.length * 1.0E-6, mspt.avg());
                TickData.TickReportData report = pair.actual.generateTickReport(null, start + 80, interval);
                assertEquals(expected.length, report.collectedTicks());
                assertEquals(total, report.totalTimeTicking());
            }
        }
    }
}
