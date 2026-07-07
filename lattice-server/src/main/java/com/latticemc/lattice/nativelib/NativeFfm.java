package com.latticemc.lattice.nativelib;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

public final class NativeFfm {
    public static final ValueLayout.OfLong C_LONG = ValueLayout.JAVA_LONG;
    public static final ValueLayout.OfInt C_INT = ValueLayout.JAVA_INT;
    public static final ValueLayout.OfDouble C_DOUBLE = ValueLayout.JAVA_DOUBLE;
    public static final ValueLayout.OfFloat C_FLOAT = ValueLayout.JAVA_FLOAT;
    public static final ValueLayout.OfByte C_BYTE = ValueLayout.JAVA_BYTE;
    public static final ValueLayout.OfBoolean C_BOOL = ValueLayout.JAVA_BOOLEAN;

    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lattice.ffm", "true"));
    private static final Linker LINKER;
    private static final SymbolLookup LOOKUP;
    private static final boolean AVAILABLE;

    static {
        Linker linker = null;
        SymbolLookup lookup = null;
        boolean available = false;
        if (ENABLED) {
            try {
                linker = Linker.nativeLinker();
                lookup = SymbolLookup.loaderLookup().or(linker.defaultLookup());
                available = true;
            } catch (Throwable ignored) {
                available = false;
            }
        }
        LINKER = linker;
        LOOKUP = lookup;
        AVAILABLE = available;
    }

    private NativeFfm() {}

    public static boolean available() {
        return AVAILABLE && LatticeNative.isLoaded();
    }

    public static MethodHandle downcall(String symbol, FunctionDescriptor descriptor) {
        if (!available()) return null;
        try {
            SymbolLookup lookup = SymbolLookup.loaderLookup().or(LINKER.defaultLookup());
            Optional<MemorySegment> address = lookup.find(symbol);
            return address.map(segment -> LINKER.downcallHandle(segment, descriptor)).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

}
