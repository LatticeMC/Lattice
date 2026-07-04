package com.latticemc.lattice.command;

import com.latticemc.lattice.nativelib.NativeDensityFunction;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;

public final class LatticeDensityCommand extends Command {
    private static boolean registered;

    private LatticeDensityCommand() {
        super("lattice", "Lattice runtime controls", "/lattice density", List.of("latticecore"));
        this.setPermission("lattice.command");
    }

    public static void registerBukkit() {
        if (registered) return;
        try {
            Method method = Bukkit.getServer().getClass().getMethod("getCommandMap");
            CommandMap commandMap = (CommandMap) method.invoke(Bukkit.getServer());
            commandMap.register("lattice", new LatticeDensityCommand());
            registered = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Bukkit.getLogger().warning("[Lattice] Failed to register /lattice command: " + e.getMessage());
        }
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        if (!testPermission(sender)) return true;
        if (args.length < 1 || !"density".equalsIgnoreCase(args[0])) {
            sender.sendMessage("Usage: /lattice density <status|reset|option> [true|false]");
            return true;
        }
        if (args.length == 2 && "status".equalsIgnoreCase(args[1])) {
            sender.sendMessage(NativeDensityFunction.status());
            return true;
        }
        if (args.length == 2 && "reset".equalsIgnoreCase(args[1])) {
            NativeDensityFunction.resetStats();
            sender.sendMessage("Lattice density stats reset");
            return true;
        }
        if (args.length == 3) {
            Boolean value = parseBoolean(args[2]);
            if (value == null) {
                sender.sendMessage("Expected true or false");
                return true;
            }
            if (!NativeDensityFunction.setOption(args[1], value.booleanValue())) {
                sender.sendMessage("Unknown density option: " + args[1]);
                return true;
            }
            sender.sendMessage("Lattice density " + args[1] + '=' + value);
            return true;
        }
        sender.sendMessage("Usage: /lattice density <status|reset|enabled|cell|directCell|spline|multipointSpline|stats|profiling> [true|false]");
        return true;
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) || "0".equals(value)) return Boolean.FALSE;
        return null;
    }
}
