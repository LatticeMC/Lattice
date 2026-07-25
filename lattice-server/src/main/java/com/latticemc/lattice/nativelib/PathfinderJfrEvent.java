package com.latticemc.lattice.nativelib;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.EventType;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;
import jdk.jfr.Timespan;

@Name(PathfinderJfrEvent.NAME)
@Label("Lattice Pathfinder Request")
@Category({"Lattice", "Pathfinding"})
@Description("A native pathfinding attempt including Java path-type snapshot preparation")
@Enabled(true)
@StackTrace(false)
@Threshold("1 ms")
public final class PathfinderJfrEvent extends Event {
    public static final String NAME = "com.latticemc.lattice.PathfinderRequest";

    @Label("Total Time")
    @Timespan(Timespan.NANOSECONDS)
    public long totalNanos;

    @Label("Target Count")
    public int targetCount;

    @Label("Snapshot Precompute Time")
    @Timespan(Timespan.NANOSECONDS)
    public long precomputeNanos;

    @Label("Native Time")
    @Timespan(Timespan.NANOSECONDS)
    public long nativeNanos;

    @Label("Maximum Range")
    public float maxRange;

    @Label("Native Result Accepted")
    public boolean nativeAccepted;

    @Label("Target Reached")
    public boolean targetReached;

    @Label("Path Length")
    public int pathLength;

    public static PathfinderJfrEvent begin(int targetCount, float maxRange) {
        if (!EventTypeHolder.TYPE.isEnabled()) return null;
        PathfinderJfrEvent event = new PathfinderJfrEvent();
        event.targetCount = targetCount;
        event.maxRange = maxRange;
        event.begin();
        return event;
    }

    public void recordPrecompute(long nanos) {
        this.precomputeNanos = Math.max(0L, nanos);
    }

    public void recordNative(long nanos) {
        this.nativeNanos = Math.max(0L, nanos);
    }

    public void finish(
            long totalNanos,
            boolean nativeAccepted,
            boolean targetReached,
            int pathLength) {
        this.totalNanos = Math.max(0L, totalNanos);
        this.nativeAccepted = nativeAccepted;
        this.targetReached = targetReached;
        this.pathLength = pathLength;
        this.end();
        this.commit();
    }

    private static final class EventTypeHolder {
        private static final EventType TYPE = EventType.getEventType(PathfinderJfrEvent.class);
    }
}
