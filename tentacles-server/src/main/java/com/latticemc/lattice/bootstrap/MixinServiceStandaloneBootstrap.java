package com.latticemc.lattice.bootstrap;

import org.spongepowered.asm.service.IMixinServiceBootstrap;

public final class MixinServiceStandaloneBootstrap implements IMixinServiceBootstrap {

    @Override
    public String getName() {
        return "Standalone";
    }

    @Override
    public String getServiceClassName() {
        return "com.latticemc.lattice.bootstrap.MixinServiceStandalone";
    }

    @Override
    public void bootstrap() {
        // No special bootstrap needed for standalone service
    }
}
