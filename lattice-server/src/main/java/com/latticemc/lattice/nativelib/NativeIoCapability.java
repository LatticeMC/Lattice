package com.latticemc.lattice.nativelib;

import java.util.Locale;

/**
 * Describes the native I/O backend selected by the current platform and startup properties.
 *
 * <p>This is deliberately capability-only scaffolding. No native I/O backend is built or
 * connected to Netty or RegionFile yet, so every selected backend remains unavailable and the
 * existing Java I/O paths retain full ownership of I/O, buffer lifetimes, and completion order.</p>
 */
public final class NativeIoCapability {
    public enum Backend {
        NONE,
        IOCP,
        IO_URING,
        KQUEUE
    }

    public record State(Backend backend, boolean requested, boolean available) {
        public boolean fallsBackToJava() {
            return !available;
        }
    }

    private NativeIoCapability() {
    }

    public static State current() {
        return resolve(
                System.getProperty("os.name", ""),
                Boolean.getBoolean("lattice.nativeIocp"),
                Boolean.getBoolean("lattice.nativeIoUring"),
                Boolean.getBoolean("lattice.nativeKqueue"));
    }

    static State resolve(String osName, boolean iocpRequested, boolean ioUringRequested, boolean kqueueRequested) {
        String normalizedOsName = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (normalizedOsName.startsWith("windows")) {
            return unavailable(Backend.IOCP, iocpRequested);
        }
        if (normalizedOsName.contains("linux")) {
            return unavailable(Backend.IO_URING, ioUringRequested);
        }
        if (normalizedOsName.contains("mac") || normalizedOsName.contains("darwin")) {
            return unavailable(Backend.KQUEUE, kqueueRequested);
        }
        return unavailable(Backend.NONE, false);
    }

    private static State unavailable(Backend backend, boolean requested) {
        // Availability becomes true only when a native implementation with equivalent I/O
        // ownership and completion semantics is added and independently verified.
        return new State(backend, requested, false);
    }
}
