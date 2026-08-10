package org.purpurmc.testplugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Controlled EntityActivationRange workload for comparing the regular and KD-tree paths.
 *
 * <p>The benchmark only creates actual Bukkit entities. Test clients must connect through
 * the normal network protocol; this class never creates a {@code ServerPlayer} itself.</p>
 */
final class EntityActivationBenchmarkCommand extends Command {
    private static final int MAX_ENTITIES = 100_000;
    private static final int MAX_REGIONS = 100;
    private static final int SPAWN_BATCH_SIZE = 512;
    private static final int REGION_STRIDE = 512;
    private static final String DEFAULT_BOT_PREFIX = "LatticeActBot";

    private final JavaPlugin plugin;
    private final List<Entity> entities = new ArrayList<>();
    private final List<Chunk> ticketedChunks = new ArrayList<>();
    private final Set<Long> ticketedChunkKeys = new HashSet<>();

    private BukkitTask preparationTask;
    private Location origin;
    private Location[] regionAnchors = new Location[0];
    private int target;
    private int spawned;
    private int regions;
    private String layout;
    private String botPrefix;
    private long startedAtNanos;
    private boolean prepared;
    private boolean running;

    EntityActivationBenchmarkCommand(final JavaPlugin plugin) {
        super(
            "activationbench",
            "Prepares a controlled entity-activation workload",
            "/activationbench <prepare|start|status|stop> ...",
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
                case "prepare" -> this.prepare(sender, args);
                case "start" -> this.start(sender);
                case "status" -> this.status(sender);
                case "stop" -> this.stop(sender);
                default -> {
                    sender.sendMessage(this.getUsage());
                    yield false;
                }
            };
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage("Activationbench error: " + exception.getMessage());
            return false;
        }
    }

    private boolean prepare(final CommandSender sender, final String[] args) {
        if (args.length != 4 && args.length != 5 && args.length != 8) {
            sender.sendMessage(
                "/activationbench prepare <count> <overlap|disjoint> <regions 1-100> [world] [x] [y] [z]"
            );
            return false;
        }

        final int count = positiveInt(args[1], "count");
        if (count > MAX_ENTITIES) {
            throw new IllegalArgumentException("count must be <= " + MAX_ENTITIES);
        }
        final String requestedLayout = args[2].toLowerCase(Locale.ROOT);
        if (!requestedLayout.equals("overlap") && !requestedLayout.equals("disjoint")) {
            throw new IllegalArgumentException("layout must be overlap or disjoint");
        }
        final int requestedRegions = positiveInt(args[3], "regions");
        if (requestedRegions > MAX_REGIONS) {
            throw new IllegalArgumentException("regions must be <= " + MAX_REGIONS);
        }

        final World world = args.length >= 5
            ? this.plugin.getServer().getWorld(args[4])
            : this.plugin.getServer().getWorlds().getFirst();
        if (world == null) {
            throw new IllegalArgumentException("unknown world: " + args[4]);
        }
        final Location requestedOrigin = world.getSpawnLocation().clone();
        if (args.length == 8) {
            requestedOrigin.setX(number(args[5], "x"));
            requestedOrigin.setY(number(args[6], "y"));
            requestedOrigin.setZ(number(args[7], "z"));
        }

        this.stopInternal();
        try {
            this.origin = requestedOrigin;
            this.target = count;
            this.regions = requestedRegions;
            this.layout = requestedLayout;
            this.botPrefix = System.getProperty("lattice.activationBenchBotPrefix", DEFAULT_BOT_PREFIX);
            if (!this.botPrefix.matches("[A-Za-z0-9_]{1,14}")) {
                throw new IllegalArgumentException("lattice.activationBenchBotPrefix must match [A-Za-z0-9_]{1,14}");
            }
            this.regionAnchors = this.createRegionAnchors(world, requestedOrigin, requestedLayout, requestedRegions);
            this.preparationTask = this.plugin.getServer().getScheduler().runTaskTimer(
                this.plugin,
                this::prepareTick,
                1L,
                1L
            );
        } catch (final RuntimeException exception) {
            this.stopInternal();
            throw exception;
        }

        sender.sendMessage(
            "Activationbench preparing: target=" + this.target
                + " layout=" + this.layout
                + " regions=" + this.regions
                + " botPrefix=" + this.botPrefix
                + " entityType=ARMOR_STAND(marker)"
        );
        sender.sendMessage("Run /activationbench status until phase=prepared, then connect bots and run /activationbench start.");
        return true;
    }

    private void prepareTick() {
        try {
            final int end = Math.min(this.target, this.spawned + SPAWN_BATCH_SIZE);
            while (this.spawned < end) {
                final int index = this.spawned++;
                this.spawnEntity(index);
            }
            if (this.spawned == this.target) {
                this.preparationTask.cancel();
                this.preparationTask = null;
                this.prepared = true;
                this.plugin.getLogger().info(() -> "Activationbench prepared: " + this.describe());
            }
        } catch (final Throwable failure) {
            this.plugin.getLogger().log(Level.SEVERE, "Activationbench preparation failed; cleaning up", failure);
            this.stopInternal();
        }
    }

    private boolean start(final CommandSender sender) {
        if (!this.prepared || this.preparationTask != null) {
            throw new IllegalArgumentException("benchmark is not prepared yet");
        }
        if (this.running) {
            throw new IllegalArgumentException("benchmark is already running");
        }
        if (!this.hasExpectedBots()) {
            throw new IllegalArgumentException(
                "requires exactly one online bot for every slot " + this.botPrefix + "1.." + this.botPrefix + this.regions
                    + " (online=" + this.onlineBots() + ", uniqueSlots=" + this.uniqueOnlineBotSlots() + ')'
            );
        }
        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            final int index = this.botIndex(player.getName());
            if (index >= 0 && index < this.regions) {
                this.assignBot(player, index);
            }
        }
        this.running = true;
        this.startedAtNanos = System.nanoTime();
        sender.sendMessage("Activationbench started: " + this.describe());
        return true;
    }

    private boolean status(final CommandSender sender) {
        sender.sendMessage("Activationbench status: " + this.describe());
        if (this.regionAnchors.length != 0) {
            sender.sendMessage("Activationbench anchors: " + this.describeAnchors());
        }
        return true;
    }

    private boolean stop(final CommandSender sender) {
        final String result = "Activationbench stopped: target=" + this.target + " live=" + this.liveCount();
        this.stopInternal();
        sender.sendMessage(result);
        return true;
    }

    void onPlayerJoin(final PlayerJoinEvent event) {
        if (this.regionAnchors.length == 0 || (!this.prepared && this.preparationTask == null)) {
            return;
        }
        final int index = this.botIndex(event.getPlayer().getName());
        if (index < 0 || index >= this.regions) {
            return;
        }
        this.assignBot(event.getPlayer(), index);
    }

    private void assignBot(final Player player, final int index) {
        final Location anchor = this.regionAnchors[this.layout.equals("overlap") ? 0 : index].clone();
        if (this.layout.equals("overlap")) {
            anchor.add((index & 3) * 0.35D, 0.0D, (index >>> 2) * 0.35D);
        }
        player.setAllowFlight(true);
        player.teleport(anchor);
        player.sendMessage(
            "Activationbench assigned slot=" + (index + 1) + "/" + this.regions
                + " layout=" + this.layout
                + " x=" + format(anchor.getX())
                + " y=" + format(anchor.getY())
                + " z=" + format(anchor.getZ())
        );
    }

    void shutdown() {
        this.stopInternal();
    }

    private void spawnEntity(final int index) {
        final Location location = this.locationFor(index);
        this.ensureChunkTicket(location);
        final Entity entity = location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND, CreatureSpawnEvent.SpawnReason.CUSTOM);
        if (!(entity instanceof ArmorStand stand)) {
            entity.remove();
            throw new IllegalStateException("ARMOR_STAND spawn did not create an ArmorStand");
        }
        stand.setMarker(true);
        stand.setVisible(false);
        stand.setCollidable(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSilent(true);
        this.entities.add(stand);
    }

    private Location locationFor(final int index) {
        final int group = this.layout.equals("overlap") ? 0 : index % this.regions;
        final int localIndex = this.layout.equals("overlap") ? index : index / this.regions;
        final Location anchor = this.regionAnchors[group];
        final int column = localIndex & 31;
        final int row = (localIndex >>> 5) & 31;
        // Marker armor stands have no collision volume, so repeated positions
        // keep every configured entity inside one activation AABB without
        // turning the benchmark into an entity-collision workload.
        return anchor.clone().add(column + 0.5D, 1.0D, row + 0.5D);
    }

    private Location[] createRegionAnchors(
        final World world,
        final Location requestedOrigin,
        final String requestedLayout,
        final int requestedRegions
    ) {
        final int anchorCount = requestedLayout.equals("overlap") ? 1 : requestedRegions;
        final Location[] anchors = new Location[anchorCount];
        for (int index = 0; index < anchorCount; index++) {
            final int gridX = index & 3;
            final int gridZ = index >>> 2;
            final double x = requestedOrigin.getX() + (requestedLayout.equals("disjoint") ? gridX * REGION_STRIDE : 0.0D);
            final double z = requestedOrigin.getZ() + (requestedLayout.equals("disjoint") ? gridZ * REGION_STRIDE : 0.0D);
            final Location anchor = new Location(world, x, requestedOrigin.getY(), z);
            this.ensureChunkTicket(anchor);
            final int surfaceY = world.getHighestBlockYAt(anchor);
            anchor.setY(Math.max(requestedOrigin.getBlockY(), surfaceY + 1));
            anchors[index] = anchor;
        }
        return anchors;
    }

    private void ensureChunkTicket(final Location location) {
        final Chunk chunk = location.getChunk();
        final long key = (((long) chunk.getX()) << 32) ^ (chunk.getZ() & 0xffffffffL);
        if (this.ticketedChunkKeys.add(key)) {
            // Tickets are keyed by plugin. Only remove a ticket that this
            // workload actually added, preserving an already-registered one.
            if (chunk.addPluginChunkTicket(this.plugin)) {
                this.ticketedChunks.add(chunk);
            }
        }
    }

    private String describe() {
        final String phase = this.preparationTask != null
            ? "preparing"
            : this.prepared
                ? this.running ? "running" : "prepared"
                : "stopped";
        final double elapsedSeconds = this.running ? (System.nanoTime() - this.startedAtNanos) / 1_000_000_000.0D : 0.0D;
        return "phase=" + phase
            + " target=" + this.target
            + " spawned=" + this.spawned
            + " live=" + this.liveCount()
            + " layout=" + this.layout
            + " regions=" + this.regions
            + " tickets=" + this.ticketedChunks.size()
            + " botPrefix=" + this.botPrefix
            + " botsOnline=" + this.onlineBots()
            + " elapsedSeconds=" + String.format(Locale.ROOT, "%.3f", elapsedSeconds);
    }

    private String describeAnchors() {
        final StringBuilder result = new StringBuilder();
        for (int index = 0; index < this.regionAnchors.length; index++) {
            if (index != 0) {
                result.append("; ");
            }
            final Location anchor = this.regionAnchors[index];
            result.append(index + 1)
                .append('=')
                .append(anchor.getWorld().getName())
                .append('@')
                .append(format(anchor.getX()))
                .append(',')
                .append(format(anchor.getY()))
                .append(',')
                .append(format(anchor.getZ()));
        }
        return result.toString();
    }

    private int onlineBots() {
        if (this.origin == null) {
            return 0;
        }
        int result = 0;
        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            if (this.botIndex(player.getName()) >= 0) {
                result++;
            }
        }
        return result;
    }

    private int botIndex(final String name) {
        if (this.botPrefix == null || !name.startsWith(this.botPrefix)) {
            return -1;
        }
        final String suffix = name.substring(this.botPrefix.length());
        if (suffix.length() > 1 && suffix.charAt(0) == '0') {
            return -1;
        }
        try {
            final int parsed = Integer.parseInt(suffix);
            return parsed >= 1 && parsed <= this.regions ? parsed - 1 : -1;
        } catch (final NumberFormatException ignored) {
            return -1;
        }
    }

    private int liveCount() {
        int result = 0;
        for (final Entity entity : this.entities) {
            if (entity.isValid()) {
                result++;
            }
        }
        return result;
    }

    private boolean hasExpectedBots() {
        return this.onlineBots() == this.regions && this.uniqueOnlineBotSlots() == this.regions;
    }

    private int uniqueOnlineBotSlots() {
        if (this.origin == null || this.regions == 0) {
            return 0;
        }
        final boolean[] slots = new boolean[this.regions];
        int result = 0;
        for (final Player player : this.plugin.getServer().getOnlinePlayers()) {
            final int index = this.botIndex(player.getName());
            if (index >= 0 && !slots[index]) {
                slots[index] = true;
                result++;
            }
        }
        return result;
    }

    private void stopInternal() {
        if (this.preparationTask != null) {
            this.preparationTask.cancel();
            this.preparationTask = null;
        }
        for (final Entity entity : this.entities) {
            try {
                if (entity.isValid()) {
                    entity.remove();
                }
            } catch (final RuntimeException exception) {
                this.plugin.getLogger().log(Level.WARNING, "Activationbench could not remove benchmark entity", exception);
            }
        }
        this.entities.clear();
        for (final Chunk chunk : this.ticketedChunks) {
            try {
                chunk.removePluginChunkTicket(this.plugin);
            } catch (final RuntimeException exception) {
                this.plugin.getLogger().log(Level.WARNING, "Activationbench could not remove a chunk ticket", exception);
            }
        }
        this.ticketedChunks.clear();
        this.ticketedChunkKeys.clear();
        this.origin = null;
        this.regionAnchors = new Location[0];
        this.target = 0;
        this.spawned = 0;
        this.regions = 0;
        this.layout = null;
        this.botPrefix = null;
        this.startedAtNanos = 0L;
        this.prepared = false;
        this.running = false;
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
            final double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed)) {
                return parsed;
            }
        } catch (final NumberFormatException exception) {
            // Fall through to the shared error below.
        }
        throw new IllegalArgumentException(name + " must be a finite number");
    }

    private static String format(final double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
