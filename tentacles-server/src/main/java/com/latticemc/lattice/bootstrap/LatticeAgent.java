package com.latticemc.lattice.bootstrap;

import java.lang.instrument.Instrumentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java agent entry point. Not required for mixin application (done at compile time),
 * but provides Instrumentation access for diagnostics and future use.
 */
public final class LatticeAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("LatticeAgent");
    private static volatile Instrumentation savedInstrumentation;

    private LatticeAgent() {}

    public static Instrumentation getInstrumentation() {
        return savedInstrumentation;
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        savedInstrumentation = inst;
        LOGGER.info("Lattice agent: premain - instrumentation acquired");
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        savedInstrumentation = inst;
        LOGGER.info("Lattice agent: agentmain - instrumentation acquired (dynamic attach)");
    }
}
