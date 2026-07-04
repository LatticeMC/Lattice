package com.latticemc.lattice.command;

import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class LatticeDensityCommand {
    private LatticeDensityCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("lattice")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("density")
                        .then(Commands.literal("status")
                                .executes(LatticeDensityCommand::status))
                        .then(Commands.literal("reset")
                                .executes(LatticeDensityCommand::reset))
                        .then(toggle("enabled"))
                        .then(toggle("cell"))
                        .then(toggle("directCell"))
                        .then(toggle("spline"))
                        .then(toggle("multipointSpline"))
                        .then(toggle("stats"))
                        .then(toggle("profiling"))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> toggle(String option) {
        return Commands.literal(option)
                .then(Commands.argument("value", BoolArgumentType.bool())
                        .executes(context -> set(context, option, BoolArgumentType.getBool(context, "value"))));
    }

    private static int set(CommandContext<CommandSourceStack> context, String option, boolean value) {
        if (!NativeDensityFunction.setOption(option, value)) {
            context.getSource().sendFailure(Component.literal("Unknown density option: " + option));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Lattice density " + option + '=' + value), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        context.getSource().sendSuccess(() -> Component.literal(NativeDensityFunction.status()), false);
        return 1;
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        NativeDensityFunction.resetStats();
        context.getSource().sendSuccess(() -> Component.literal("Lattice density stats reset"), false);
        return 1;
    }
}
