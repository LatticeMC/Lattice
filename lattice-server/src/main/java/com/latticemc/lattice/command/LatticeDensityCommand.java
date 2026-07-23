package com.latticemc.lattice.command;

import com.latticemc.lattice.nativelib.NativeDensityFunction;
import com.latticemc.lattice.nativelib.WorldgenProfiler;
import java.util.ArrayList;
import java.util.Collections;
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
    private static final List<String> DENSITY_ACTIONS = List.of(
            "status", "reset", "all", "fast", "profile", "enabled", "cell", "directCell", "directCellColumn",
            "shiftedNoise", "spline", "multipointSpline", "climateBatch", "stats", "profiling",
            "parity", "parityInterval", "surface", "heightmap", "worldgenProfiler", "worldgenHotLoops", "worldgenProfileStatus", "worldgenProfileReset");
    private static final List<String> BOOLEAN_VALUES = List.of("true", "false", "on", "off");
    private static final List<String> FULL_OPTIONS = List.of(
            "enabled", "cell", "directCell", "directCellColumn", "shiftedNoise", "spline",
            "multipointSpline", "climateBatch", "surface", "heightmap");
    private static final List<String> PROFILE_OPTIONS = List.of(
            "stats", "profiling", "parity");
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
            sendUsage(sender);
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
        if (args.length == 2 && "worldgenProfileStatus".equalsIgnoreCase(args[1])) {
            sender.sendMessage(WorldgenProfiler.status());
            return true;
        }
        if (args.length == 2 && "worldgenProfileReset".equalsIgnoreCase(args[1])) {
            WorldgenProfiler.reset();
            sender.sendMessage("Lattice worldgen profiler reset");
            return true;
        }
        if (args.length == 3) {
            if ("all".equalsIgnoreCase(args[1]) || "fast".equalsIgnoreCase(args[1]) || "profile".equalsIgnoreCase(args[1])) {
                Boolean value = parseBoolean(args[2]);
                if (value == null) {
                    sender.sendMessage("Expected true or false");
                    return true;
                }
                applyPreset(args[1], value.booleanValue());
                sender.sendMessage("Lattice density " + args[1] + '=' + value);
                return true;
            }
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
                if ("worldgenProfiler".equalsIgnoreCase(args[1])) {
                    WorldgenProfiler.setEnabled(value.booleanValue());
                } else if ("worldgenHotLoops".equalsIgnoreCase(args[1])) {
                    WorldgenProfiler.setHotLoopsEnabled(value.booleanValue());
                } else {
                    sender.sendMessage("Unknown density option: " + args[1]);
                    return true;
                }
            }
            sender.sendMessage("Lattice density " + args[1] + '=' + value);
            return true;
        }
        sendUsage(sender);
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (!testPermissionSilent(sender)) return Collections.emptyList();
        if (args.length == 1) return filter(List.of("density"), args[0]);
        if (args.length == 2 && "density".equalsIgnoreCase(args[0])) {
            return filter(DENSITY_ACTIONS, args[1]);
        }
        if (args.length == 3 && "density".equalsIgnoreCase(args[0])) {
            if ("parityInterval".equalsIgnoreCase(args[1])) return filter(List.of("1", "128", "1024", "4096"), args[2]);
            if (!"status".equalsIgnoreCase(args[1]) && !"reset".equalsIgnoreCase(args[1])) {
                return filter(BOOLEAN_VALUES, args[2]);
            }
        }
        return Collections.emptyList();
    }

    private static void applyPreset(String preset, boolean value) {
        if ("all".equalsIgnoreCase(preset)) {
            for (String option : FULL_OPTIONS) NativeDensityFunction.setOption(option, value);
            if (value) {
                for (String option : PROFILE_OPTIONS) NativeDensityFunction.setOption(option, false);
            }
        } else if ("fast".equalsIgnoreCase(preset)) {
            NativeDensityFunction.setOption("enabled", false);
            NativeDensityFunction.setOption("cell", false);
            NativeDensityFunction.setOption("directCell", false);
            NativeDensityFunction.setOption("directCellColumn", false);
            NativeDensityFunction.setOption("shiftedNoise", false);
            NativeDensityFunction.setOption("spline", false);
            NativeDensityFunction.setOption("multipointSpline", false);
            NativeDensityFunction.setOption("climateBatch", false);
            NativeDensityFunction.setOption("surface", value);
            NativeDensityFunction.setOption("heightmap", value);
            for (String option : PROFILE_OPTIONS) NativeDensityFunction.setOption(option, false);
        } else if ("profile".equalsIgnoreCase(preset)) {
            for (String option : PROFILE_OPTIONS) NativeDensityFunction.setOption(option, value);
        }
    }

    private static void sendUsage(CommandSender sender) {
        sender.sendMessage("Usage: /lattice density <status|reset|all|fast|profile|worldgenProfileStatus|worldgenProfileReset|option> [true|false]");
        sender.sendMessage("Options: " + String.join(", ", DENSITY_ACTIONS));
    }

    private static List<String> filter(List<String> values, String prefix) {
        if (prefix == null || prefix.isEmpty()) return values;
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String value : values) {
            if (value.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) out.add(value);
        }
        return out;
    }

    private static Boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value) || "on".equalsIgnoreCase(value) || "1".equals(value)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(value) || "off".equalsIgnoreCase(value) || "0".equals(value)) return Boolean.FALSE;
        return null;
    }
}
