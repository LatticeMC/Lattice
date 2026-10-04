package org.purpurmc.testplugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Villager;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Fixed-shape entity workload: a large zombie population plus a handful of
 * invulnerable villagers that teleport at a fixed interval, spread over a
 * square of plugin-ticketed (force-loaded) chunks.
 *
 * <p>Defaults match the standard scenario: 2000 zombies, 5 villagers, a 48x48
 * chunk square, one teleport per second, and an automatic cleanup once the hold
 * window elapses. Natural mob spawning and the daylight cycle are pinned for
 * the duration of the run so the entity count stays controlled and zombies do
 * not burn away mid-measurement; both game rules are restored on cleanup.</p>
 *
 * <p>This command only creates Bukkit entities. Test clients ("bots") must
 * connect through the normal network protocol, e.g.
 * {@code ActivationBenchBotRunner --bots 16}; this class never creates a
 * {@code ServerPlayer} itself.</p>
 */
final class EntityStressCommand extends Command {
    private static final int DEFAULT_ZOMBIES = 2000;
    private static final int DEFAULT_VILLAGERS = 5;
    private static final int DEFAULT_CHUNK_SIDE = 48;
    private static final int DEFAULT_HOLD_SECONDS = 300;
    private static final int DEFAULT_TELEPORT_TICKS = 20;

    private static final int MAX_ENTITIES = 100_000;
    private static final int MAX_CHUNK_SIDE = 128;
    private static final int MAX_HOLD_SECONDS = 86_400;
    private static final int PREPARE_BATCH = 48;
    private static final long TICKS_PER_SECOND = 20L;

    private static final int PHASE_CHUNKS = 0;
    private static final int PHASE_ZOMBIES = 1;
    private static final int PHASE_VILLAGERS = 2;

    private final JavaPlugin plugin;
    private final List<Entity> entities = new ArrayList<>();
    private final List<Villager> villagers = new ArrayList<>();
    private final List<Chunk> ticketedChunks = new ArrayList<>();
    private final Set<Long> ticketedChunkKeys = new HashSet<>();

    private BukkitTask prepareTask;
    private BukkitTask teleportTask;
    private BukkitTask finishTask;

    private World world;
    private int chunkSide;
    private int minChunkX;
    private int minChunkZ;
    private int targetZombies;
    private int targetVillagers;
    private int teleportTicks;
    private long holdSeconds;
    private int spawnedZombies;
    private int spawnedVillagers;

    private int phase;
    private int phaseIndex;

    private boolean prepared;
    private boolean running;
    private long startedAtNanos;

    private boolean restoreMobSpawning;
    private boolean restoreDaylightCycle;
    private int restoreMaxEntityCramming;

    EntityStressCommand(final JavaPlugin plugin) {
        super(
            "entitystress",
            "Runs a fixed-shape entity workload and cleans up automatically",
            "/entitystress <start|status|stop> "
                + "[zombies=2000] [villagers=5] [chunkSide=48] [holdSeconds=300] [teleportTicks=20]",
            Collections.emptyList()
        );
        this.plugin = plugin;
    }

    @Override
    public boolean execute(final CommandSender sender, final String commandLabel, final String[] args) {
        if (args.length == 0) {
            sender.sendMessage(this.getUsage());
            return false;
        }
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start" -> this.start(sender, args);
                case "status" -> this.status(sender);
                case "stop" -> this.stop(sender);
                default -> {
                    sender.sendMessage(this.getUsage());
                    yield false;
                }
            };
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage("Entitystress error: " + exception.getMessage());
            return false;
        }
    }

    private boolean start(final CommandSender sender, final String[] args) {
        if (args.length > 6) {
            sender.sendMessage(this.getUsage());
            return false;
        }
        final int zombies = args.length >= 2 ? positiveInt(args[1], "zombies") : DEFAULT_ZOMBIES;
        final int requestedVillagers = args.length >= 3 ? positiveInt(args[2], "villagers") : DEFAULT_VILLAGERS;
        final int side = args.length >= 4 ? positiveInt(args[3], "chunkSide") : DEFAULT_CHUNK_SIDE;
        final long hold = args.length >= 5 ? positiveLong(args[4], "holdSeconds") : DEFAULT_HOLD_SECONDS;
        final int teleport = args.length >= 6 ? positiveInt(args[5], "teleportTicks") : DEFAULT_TELEPORT_TICKS;

        if (zombies > MAX_ENTITIES || requestedVillagers > MAX_ENTITIES) {
            throw new IllegalArgumentException("entity counts must be <= " + MAX_ENTITIES);
        }
        if (side > MAX_CHUNK_SIDE) {
            throw new IllegalArgumentException("chunkSide must be <= " + MAX_CHUNK_SIDE);
        }
        if (hold > MAX_HOLD_SECONDS) {
            throw new IllegalArgumentException("holdSeconds must be <= " + MAX_HOLD_SECONDS);
        }

        this.stopInternal();

        this.world = this.plugin.getServer().getWorlds().getFirst();
        this.targetZombies = zombies;
        this.targetVillagers = requestedVillagers;
        this.chunkSide = side;
        this.holdSeconds = hold;
        this.teleportTicks = teleport;
        this.spawnedZombies = 0;
        this.spawnedVillagers = 0;
        this.phase = PHASE_CHUNKS;
        this.phaseIndex = 0;
        this.prepared = false;
        this.running = false;

        final Location spawn = this.world.getSpawnLocation();
        final int half = side / 2;
        this.minChunkX = (spawn.getBlockX() >> 4) - half;
        this.minChunkZ = (spawn.getBlockZ() >> 4) - half;

        this.pinGameRules();

        sender.sendMessage(
            "Entitystress preparing: zombies=" + zombies
                + " villagers=" + requestedVillagers
                + " chunkSquare=" + side + "x" + side + " (" + ((long) side * side) + " chunks)"
                + " holdSeconds=" + hold
                + " teleportTicks=" + teleport
                + " world=" + this.world.getName()
        );

        this.prepareTask = this.plugin.getServer().getScheduler()
            .runTaskTimer(this.plugin, this::prepareStep, 2L, 2L);
        return true;
    }

    private void prepareStep() {
        if (this.prepareTask == null) {
            return;
        }
        for (int batch = 0; batch < PREPARE_BATCH; batch++) {
            switch (this.phase) {
                case PHASE_CHUNKS -> {
                    final long total = (long) this.chunkSide * this.chunkSide;
                    if (this.phaseIndex >= total) {
                        this.phase = PHASE_ZOMBIES;
                        this.phaseIndex = 0;
                        continue;
                    }
                    this.ticketChunk(this.phaseIndex);
                    this.phaseIndex++;
                }
                case PHASE_ZOMBIES -> {
                    if (this.phaseIndex >= this.targetZombies) {
                        this.phase = PHASE_VILLAGERS;
                        this.phaseIndex = 0;
                        continue;
                    }
                    this.spawnZombie(this.phaseIndex);
                    this.phaseIndex++;
                }
                default -> {
                    if (this.phaseIndex >= this.targetVillagers) {
                        this.finishPreparation();
                        return;
                    }
                    this.spawnVillager();
                    this.phaseIndex++;
                }
            }
        }
    }

    private void finishPreparation() {
        this.prepareTask.cancel();
        this.prepareTask = null;
        this.prepared = true;
        this.running = true;
        this.startedAtNanos = System.nanoTime();

        final long holdTicks = this.holdSeconds * TICKS_PER_SECOND;
        this.teleportTask = this.plugin.getServer().getScheduler()
            .runTaskTimer(this.plugin, this::teleportVillagers, this.teleportTicks, this.teleportTicks);
        this.finishTask = this.plugin.getServer().getScheduler()
            .runTaskLater(this.plugin, this::finish, holdTicks);

        this.plugin.getLogger().info(
            "Entitystress started: " + this.describe()
        );
        this.plugin.getServer().broadcastMessage(
            "Entitystress started: zombies=" + this.spawnedZombies
                + " villagers=" + this.spawnedVillagers
                + " chunks=" + this.ticketedChunks.size()
                + " holdSeconds=" + this.holdSeconds
        );
    }

    private void teleportVillagers() {
        if (!this.running) {
            return;
        }
        for (final Villager villager : this.villagers) {
            if (!villager.isValid()) {
                continue;
            }
            final Location target = this.randomLocation();
            try {
                villager.teleport(target);
            } catch (final RuntimeException exception) {
                this.plugin.getLogger().log(Level.WARNING, "Entitystress could not teleport a villager", exception);
            }
        }
    }

    private void finish() {
        this.plugin.getLogger().info("Entitystress finished: " + this.describe());
        this.stopInternal();
    }

    private boolean status(final CommandSender sender) {
        sender.sendMessage("Entitystress " + this.describe());
        return true;
    }

    private boolean stop(final CommandSender sender) {
        this.stopInternal();
        sender.sendMessage("Entitystress stopped and cleaned up");
        return true;
    }

    void shutdown() {
        this.stopInternal();
    }

    // ------------------------------------------------------------ preparation

    private void ticketChunk(final int index) {
        final int cx = this.minChunkX + (index % this.chunkSide);
        final int cz = this.minChunkZ + (index / this.chunkSide);
        final Chunk chunk = this.world.getChunkAt(cx, cz);
        final long key = (((long) chunk.getX()) << 32) ^ (chunk.getZ() & 0xffffffffL);
        if (this.ticketedChunkKeys.add(key)) {
            // Tickets are keyed by plugin. Only remove a ticket this workload
            // actually added, preserving an already-registered one.
            if (chunk.addPluginChunkTicket(this.plugin)) {
                this.ticketedChunks.add(chunk);
            }
        }
    }

    private void spawnZombie(final int index) {
        final Location location = this.locationFor(index);
        final Entity entity = this.world.spawnEntity(
            location, EntityType.ZOMBIE, CreatureSpawnEvent.SpawnReason.CUSTOM
        );
        if (!(entity instanceof Zombie zombie)) {
            entity.remove();
            throw new IllegalStateException("ZOMBIE spawn did not create a Zombie");
        }
        // Keep the population stable for the whole hold window: no despawn,
        // and immune to every damage source (cramming, falls, drowning) so the
        // entity count cannot drift while the measurement is running.
        zombie.setRemoveWhenFarAway(false);
        zombie.setInvulnerable(true);
        this.entities.add(zombie);
        this.spawnedZombies++;
    }

    private void spawnVillager() {
        final Location location = this.randomLocation();
        final Entity entity = this.world.spawnEntity(
            location, EntityType.VILLAGER, CreatureSpawnEvent.SpawnReason.CUSTOM
        );
        if (!(entity instanceof Villager villager)) {
            entity.remove();
            throw new IllegalStateException("VILLAGER spawn did not create a Villager");
        }
        villager.setInvulnerable(true);
        villager.setRemoveWhenFarAway(false);
        this.entities.add(villager);
        this.villagers.add(villager);
        this.spawnedVillagers++;
    }

    private Location locationFor(final int index) {
        // Spread deterministically across the chunk square, one entity per
        // chunk before wrapping around, so the load is even rather than
        // clustered by spawn order.
        final int chunkCount = this.chunkSide * this.chunkSide;
        final int ci = index % chunkCount;
        final int cx = this.minChunkX + (ci % this.chunkSide);
        final int cz = this.minChunkZ + (ci / this.chunkSide);
        return this.locationInChunk(cx, cz);
    }

    private Location randomLocation() {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final int cx = this.minChunkX + random.nextInt(this.chunkSide);
        final int cz = this.minChunkZ + random.nextInt(this.chunkSide);
        return this.locationInChunk(cx, cz);
    }

    private Location locationInChunk(final int cx, final int cz) {
        final ThreadLocalRandom random = ThreadLocalRandom.current();
        final double x = (cx << 4) + random.nextDouble() * 16.0D;
        final double z = (cz << 4) + random.nextDouble() * 16.0D;
        final Location location = new Location(this.world, x, 0.0D, z);
        location.setY(this.world.getHighestBlockYAt(location) + 1);
        return location;
    }

    private void pinGameRules() {
        final Boolean mobSpawning = this.world.getGameRuleValue(GameRule.DO_MOB_SPAWNING);
        final Boolean daylight = this.world.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE);
        final Integer cramming = this.world.getGameRuleValue(GameRule.MAX_ENTITY_CRAMMING);
        this.restoreMobSpawning = mobSpawning == null || mobSpawning;
        this.restoreDaylightCycle = daylight == null || daylight;
        this.restoreMaxEntityCramming = cramming == null ? 24 : cramming;

        // No natural spawns: the entity count must be exactly what we created.
        this.world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        // Freeze at night so zombies survive the whole hold window.
        this.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        // Zombies path toward players and villagers and clump; the default
        // cramming limit of 24 then grinds the population down mid-run.
        this.world.setGameRule(GameRule.MAX_ENTITY_CRAMMING, 0);
        this.world.setTime(18_000L);
        this.world.setStorm(false);
        this.world.setThundering(false);
    }

    private void restoreGameRules() {
        if (this.world == null) {
            return;
        }
        this.world.setGameRule(GameRule.DO_MOB_SPAWNING, this.restoreMobSpawning);
        this.world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, this.restoreDaylightCycle);
        this.world.setGameRule(GameRule.MAX_ENTITY_CRAMMING, this.restoreMaxEntityCramming);
    }

    // ---------------------------------------------------------------- cleanup

    private void stopInternal() {
        if (this.prepareTask != null) {
            this.prepareTask.cancel();
            this.prepareTask = null;
        }
        if (this.teleportTask != null) {
            this.teleportTask.cancel();
            this.teleportTask = null;
        }
        if (this.finishTask != null) {
            this.finishTask.cancel();
            this.finishTask = null;
        }

        for (final Entity entity : this.entities) {
            try {
                if (entity.isValid()) {
                    entity.remove();
                }
            } catch (final RuntimeException exception) {
                this.plugin.getLogger().log(Level.WARNING, "Entitystress could not remove a workload entity", exception);
            }
        }
        this.entities.clear();
        this.villagers.clear();

        for (final Chunk chunk : this.ticketedChunks) {
            try {
                chunk.removePluginChunkTicket(this.plugin);
            } catch (final RuntimeException exception) {
                this.plugin.getLogger().log(Level.WARNING, "Entitystress could not remove a chunk ticket", exception);
            }
        }
        this.ticketedChunks.clear();
        this.ticketedChunkKeys.clear();

        this.restoreGameRules();

        this.world = null;
        this.prepared = false;
        this.running = false;
        this.spawnedZombies = 0;
        this.spawnedVillagers = 0;
        this.phase = PHASE_CHUNKS;
        this.phaseIndex = 0;
    }

    // ----------------------------------------------------------------- report

    private String describe() {
        final String phaseName = this.prepareTask != null
            ? "preparing(" + this.phaseName() + " " + this.phaseIndex + ")"
            : this.prepared
                ? this.running ? "running" : "prepared"
                : "stopped";
        final double elapsed = this.running
            ? (System.nanoTime() - this.startedAtNanos) / 1_000_000_000.0D
            : 0.0D;
        return "phase=" + phaseName
            + " targetZombies=" + this.targetZombies
            + " spawnedZombies=" + this.spawnedZombies
            + " targetVillagers=" + this.targetVillagers
            + " spawnedVillagers=" + this.spawnedVillagers
            + " live=" + this.liveCount()
            + " worldEntities=" + (this.world == null ? 0 : this.world.getEntityCount())
            + " chunkSquare=" + this.chunkSide + "x" + this.chunkSide
            + " tickets=" + this.ticketedChunks.size()
            + " loadedChunks=" + this.loadedTicketedChunks()
            + " playersOnline=" + this.plugin.getServer().getOnlinePlayers().size()
            + " elapsedSeconds=" + String.format(Locale.ROOT, "%.3f", elapsed);
    }

    private String phaseName() {
        return switch (this.phase) {
            case PHASE_CHUNKS -> "chunks";
            case PHASE_ZOMBIES -> "zombies";
            default -> "villagers";
        };
    }

    private int loadedTicketedChunks() {
        int loaded = 0;
        for (final Chunk chunk : this.ticketedChunks) {
            if (chunk.isLoaded()) {
                loaded++;
            }
        }
        return loaded;
    }

    private int liveCount() {
        int live = 0;
        for (final Entity entity : this.entities) {
            if (entity.isValid()) {
                live++;
            }
        }
        return live;
    }

    // ------------------------------------------------------------------ parse

    private static int positiveInt(final String value, final String name) {
        final int parsed = parseInt(value, name);
        if (parsed <= 0) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return parsed;
    }

    private static long positiveLong(final String value, final String name) {
        final long parsed = parseLong(value, name);
        if (parsed <= 0L) {
            throw new IllegalArgumentException(name + " must be > 0");
        }
        return parsed;
    }

    private static int parseInt(final String value, final String name) {
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer: " + value);
        }
    }

    private static long parseLong(final String value, final String name) {
        try {
            return Long.parseLong(value);
        } catch (final NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be an integer: " + value);
        }
    }
}
