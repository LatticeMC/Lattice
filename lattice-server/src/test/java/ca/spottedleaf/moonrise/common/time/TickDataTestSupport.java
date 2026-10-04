package ca.spottedleaf.moonrise.common.time;

import java.util.ArrayDeque;

final class TickDataTestSupport {
    private TickDataTestSupport() {}

    static TickTime tick(long previous, long start, long length) {
        return new TickTime(previous, start, start, 0, start + length, 0, 0, 0, false);
    }

    // B3 前的 TickData.addDataFrom/getTPSAverage，作为测试和基准的独立扫描 oracle。
    static final class OriginalTickData {
        private final long interval;
        final ArrayDeque<TickTime> timeData = new ArrayDeque<>();

        OriginalTickData(long interval) {
            this.interval = interval;
        }

        void addDataFrom(TickTime time) {
            final long start = time.tickStart();
            TickTime first;
            while ((first = this.timeData.peekFirst()) != null) {
                if ((start - first.tickEnd()) <= this.interval) {
                    break;
                }
                this.timeData.pollFirst();
            }
            this.timeData.add(time);
        }

        Double getTPSAverage(TickTime inProgress, long tickInterval) {
            if (this.timeData.isEmpty() && inProgress == null) {
                return null;
            }
            long totalTimeBetweenTicks = 0L;
            int collectedTicks = this.timeData.size();
            if (inProgress != null) {
                ++collectedTicks;
                totalTimeBetweenTicks += inProgress.differenceFromLastTick(tickInterval);
            }
            for (final TickTime time : this.timeData) {
                totalTimeBetweenTicks += time.differenceFromLastTick(tickInterval);
            }
            return Double.valueOf((double)collectedTicks / ((double)totalTimeBetweenTicks / 1.0E9));
        }
    }
}
