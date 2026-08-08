package org.purpurmc.testplugin;

import com.destroystokyo.paper.entity.Pathfinder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.HeightMap;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class PathfinderBenchmarkCommand extends Command {
    private static final int TARGET_COUNT = 1;

    private final JavaPlugin plugin;
    private final List<Mob> mobs = new ArrayList<>();
    private Location[] targets = new Location[0];
    private BukkitTask task;
    private int mobCursor;
    private int targetCursor;
    private int requestsPerTick;
    private long ticks;
    private long calls;
    private long failures;
    private long totalNanos;

    PathfinderBenchmarkCommand(final JavaPlugin plugin) {
        super(
            "pathbench",
            "Runs a controlled synchronous pathfinder workload",
            "/pathbench <start|reset|status|stop>",
            Collections.emptyList()
        );
        this.plugin = plugin;
    }

    @Override
    public boolean execute(
        final CommandSender sender,
        final String commandLabel,
        final String[] args
    ) {
        if (args.length == 0) {
            sender.sendMessage(this.getUsage());
            return false;
        }

        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start" -> this.start(sender, args);
                case "reset" -> this.reset(sender);
                case "status" -> this.status(sender);
                case "stop" -> this.stop(sender);
                default -> {
                    sender.sendMessage(this.getUsage());
                    yield false;
                }
            };
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage("Pathbench error: " + exception.getMessage());
            return false;
        }
    }

    private boolean start(final CommandSender sender, final String[] args) {
        if (args.length < 5 || args.length > 9) {
            sender.sendMessage(
                "/pathbench start <mobType> <mobCount> <requestsPerTick> <targetRadius> [world] [x] [y] [z]"
            );
            return false;
        }

        final EntityType entityType;
        try {
            entityType = EntityType.valueOf(args[1].toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("unknown entity type: " + args[1]);
        }
        final int mobCount = positiveInt(args[2], "mobCount");
        final int requestedPerTick = positiveInt(args[3], "requestsPerTick");
        final int targetRadius = positiveInt(args[4], "targetRadius");
        if (mobCount > 4096) {
            throw new IllegalArgumentException("mobCount must be <= 4096");
        }
        if (requestedPerTick > 4096) {
            throw new IllegalArgumentException("requestsPerTick must be <= 4096");
        }

        final World world = args.length >= 6
            ? this.plugin.getServer().getWorld(args[5])
            : this.plugin.getServer().getWorlds().getFirst();
        if (world == null) {
            throw new IllegalArgumentException("unknown world: " + args[5]);
        }
        final Location spawn = world.getSpawnLocation().clone();
        if (args.length >= 9) {
            spawn.setX(number(args[6], "x"));
            spawn.setY(number(args[7], "y"));
            spawn.setZ(number(args[8], "z"));
        }

        this.stopInternal();
        this.requestsPerTick = requestedPerTick;
        this.targets = createTargets(spawn, targetRadius);

        for (int index = 0; index < mobCount; ++index) {
            final double angle = (2.0D * Math.PI * index) / mobCount;
            final Location mobLocation = spawn.clone().add(
                3.0D * Math.cos(angle),
                0.0D,
                3.0D * Math.sin(angle)
            );
            mobLocation.setY(world.getHighestBlockYAt(
                mobLocation,
                HeightMap.MOTION_BLOCKING_NO_LEAVES
            ) + 1.0D);
            final Entity entity = world.spawnEntity(
                mobLocation,
                entityType,
                CreatureSpawnEvent.SpawnReason.CUSTOM
            );
            if (!(entity instanceof Mob mob)) {
                entity.remove();
                this.stopInternal();
                throw new IllegalArgumentException(entityType + " is not a Mob");
            }
            mob.setAware(false);
            mob.setCollidable(false);
            mob.setInvulnerable(true);
            mob.setPersistent(true);
            mob.setRemoveWhenFarAway(false);
            mob.setSilent(true);
            this.mobs.add(mob);
        }

        this.resetCounters();
        this.task = this.plugin.getServer().getScheduler().runTaskTimer(
            this.plugin,
            this::runTick,
            1L,
            1L
        );
        sender.sendMessage(
            "Pathbench started: mobs=" + this.mobs.size()
                + " requestsPerTick=" + this.requestsPerTick
                + " targetRadius=" + targetRadius
        );
        return true;
    }

    private boolean reset(final CommandSender sender) {
        this.requireRunning();
        this.resetCounters();
        sender.sendMessage("Pathbench counters reset");
        return true;
    }

    private boolean status(final CommandSender sender) {
        sender.sendMessage(this.stats());
        return true;
    }

    private boolean stop(final CommandSender sender) {
        final String stats = this.stats();
        this.stopInternal();
        sender.sendMessage(stats + " stopped=true");
        return true;
    }

    private void runTick() {
        ++this.ticks;
        for (int index = 0; index < this.requestsPerTick; ++index) {
            if (this.mobs.isEmpty()) {
                return;
            }
            final Mob mob = this.mobs.get(this.mobCursor++ % this.mobs.size());
            final Location target = this.targets[this.targetCursor++ % this.targets.length];
            final long started = System.nanoTime();
            try {
                final Pathfinder.PathResult result = mob.getPathfinder().findPath(target);
                if (result == null) {
                    ++this.failures;
                }
            } catch (final RuntimeException exception) {
                ++this.failures;
            } finally {
                this.totalNanos += System.nanoTime() - started;
                ++this.calls;
            }
        }
    }

    private String stats() {
        final double averageMicros = this.calls == 0
            ? 0.0D
            : (this.totalNanos / 1_000.0D) / this.calls;
        return String.format(
            Locale.ROOT,
            "Pathbench stats: running=%s mobs=%d requestsPerTick=%d ticks=%d calls=%d failures=%d avgMicros=%.3f",
            this.task != null,
            this.mobs.size(),
            this.requestsPerTick,
            this.ticks,
            this.calls,
            this.failures,
            averageMicros
        );
    }

    private void requireRunning() {
        if (this.task == null) {
            throw new IllegalArgumentException("benchmark is not running");
        }
    }

    private void resetCounters() {
        this.ticks = 0L;
        this.calls = 0L;
        this.failures = 0L;
        this.totalNanos = 0L;
    }

    private void stopInternal() {
        if (this.task != null) {
            this.task.cancel();
            this.task = null;
        }
        for (final Mob mob : this.mobs) {
            if (mob.isValid()) {
                mob.remove();
            }
        }
        this.mobs.clear();
        this.targets = new Location[0];
        this.mobCursor = 0;
        this.targetCursor = 0;
        this.requestsPerTick = 0;
    }

    void shutdown() {
        this.stopInternal();
    }

    private static int positiveInt(final String value, final String name) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value);
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        if (parsed < 1) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return parsed;
    }

    private static double number(final String value, final String name) {
        try {
            return Double.parseDouble(value);
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    private static Location[] createTargets(final Location center, final int radius) {
        final Location[] result = new Location[TARGET_COUNT];
        for (int index = 0; index < result.length; ++index) {
            final double angle = (2.0D * Math.PI * index) / result.length;
            result[index] = center.clone().add(
                radius * Math.cos(angle),
                0.0D,
                radius * Math.sin(angle)
            );
            result[index].setY(center.getWorld().getHighestBlockYAt(
                result[index],
                HeightMap.MOTION_BLOCKING_NO_LEAVES
            ) + 1.0D);
        }
        return result;
    }
}
