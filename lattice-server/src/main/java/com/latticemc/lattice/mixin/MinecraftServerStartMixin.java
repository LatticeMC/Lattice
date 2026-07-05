package com.latticemc.lattice.mixin;

import com.latticemc.lattice.bootstrap.LatticeBootstrap;
import com.latticemc.lattice.command.LatticeDensityCommand;
import java.util.function.BooleanSupplier;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerStartMixin {

    @Inject(method = "runServer", at = @At("HEAD"))
    private void lattice$logStartup(CallbackInfo ci) {
        LatticeBootstrap.onServerStart();
    }

    @Inject(method = "tickServer", at = @At("TAIL"))
    private void lattice$registerBukkitCommands(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        LatticeDensityCommand.registerBukkit();
    }
}
