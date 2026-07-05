package com.latticemc.lattice.command;

import com.latticemc.lattice.nativelib.NativeDensityFunction;
import java.lang.reflect.Method;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LatticeDensityCommand extends Command {
    private static final Logger LOGGER = LoggerFactory.getLogger("Lattice");
    private static boolean registered;

    private LatticeDensityCommand() {
        super("lattice", "Lattice runtime controls", "/lattice density", List.of("latticecore"));
        this.setPermission("lattice.command");
    }

    public static void registerBukkit() {
        if (registered) return;
        try {
            Server server = Bukkit.getServer();
            if (server == null) return;
            Method method = server.getClass().getMethod("getCommandMap");
            CommandMap commandMap = (CommandMap) method.invoke(server);
            commandMap.register("lattice", new LatticeDensityCommand());
            registered = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("Failed to register /lattice command", e);
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
            if ("parityInterval".equalsIgnoreCase(args[1])) {
                try {
                    int value = Integer.parseInt(args[2]);
                    if (!NativeDensityFunction.setIntOption(args[1], value)) {
                        sender.sendMessage("Unknown density option: " + args[1]);
                        return true;
                    }
                    sender.sendMessage("Lattice density " + args[1] + '=' + Math.max(1, value));
                } catch (NumberFormatException e) {
                    sender.sendMessage("Expected integer >= 1");
                }
                return true;
            }
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
        sender.sendMessage("Usage: /lattice density <status|reset|enabled|cell|directCell|shiftedNoise|spline|multipointSpline|stats|profiling|parity|surface|heightmap> [true|false], or parityInterval <n>");
        return true;
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) || "0".equals(value)) return Boolean.FALSE;
        return null;
    }
}
