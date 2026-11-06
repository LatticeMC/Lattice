package com.latticemc.lattice.bridge;

import com.latticemc.lattice.nativelib.NativePathfinder;

/** One lazy native registration owned by a Level, never by a global Level map. */
public final class PathfinderMirrorWorld implements AutoCloseable {
    interface Backend {
        long create();
        void retire(long generation);
    }

    private static final Backend NATIVE = new Backend() {
        public long create() { return NativePathfinder.createMirrorWorld(); }
        public void retire(long generation) { NativePathfinder.retireMirrorWorld(generation); }
    };

    private final Backend backend;
    private long generation;
    private boolean closed;

    public PathfinderMirrorWorld() { this(NATIVE); }

    PathfinderMirrorWorld(Backend backend) { this.backend = backend; }

    public synchronized long getOrCreate() {
        if (closed) return 0L;
        if (generation == 0L) generation = backend.create();
        return generation;
    }

    public synchronized long existingGeneration() { return closed ? 0L : generation; }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        long retired = generation;
        generation = 0L;
        if (retired != 0L) backend.retire(retired);
    }
}
