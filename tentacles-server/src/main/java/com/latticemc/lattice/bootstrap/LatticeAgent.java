package com.latticemc.lattice.bootstrap;

import java.lang.instrument.Instrumentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

public final class LatticeAgent {

    private static final Logger LOGGER = LoggerFactory.getLogger("LatticeAgent");
    public static final String MIXIN_CONFIG = "mixin.lattice.json";

    private LatticeAgent() {}

    public static void premain(String agentArgs, Instrumentation inst) {
        LOGGER.info("Lattice agent: premain (Mixin bootstrap)");
        try {
            MixinBootstrap.init();
            Mixins.addConfiguration(MIXIN_CONFIG);
            MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.SERVER);
        } catch (Throwable t) {
            LOGGER.error("Lattice agent: Mixin bootstrap failed", t);
            return;
        }

        try {
            com.latticemc.lattice.nativelib.LatticeNative.ensureLoaded();
            LOGGER.info("Lattice agent: native library loaded={} reason={}",
                    com.latticemc.lattice.nativelib.LatticeNative.isLoaded(),
                    com.latticemc.lattice.nativelib.LatticeNative.failureReason());
        } catch (Throwable t) {
            LOGGER.warn("Lattice agent: native library load failed early", t);
        }

        LatticeBootstrap.onPremainComplete();
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        LOGGER.warn("Lattice agent: agentmain (dynamic attach)");
        premain(agentArgs, inst);
    }
}
