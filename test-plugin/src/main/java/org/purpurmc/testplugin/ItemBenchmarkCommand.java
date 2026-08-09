package org.purpurmc.testplugin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Directional;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

final class ItemBenchmarkCommand extends Command {
    private final JavaPlugin plugin;
    private final List<Item> items = new ArrayList<>();
    private final Map<Block, BlockState> changedBlocks = new LinkedHashMap<>();
    private final List<Chunk> loadedChunks = new ArrayList<>();
    private BukkitTask task;
    private BukkitTask pulseTask;
    private Location origin;
    private int target;
    private int perTick;
    private int spawned;
    private long ticks;
    private long measureStart;
    private String layout;

    ItemBenchmarkCommand(final JavaPlugin plugin) {
        super("itembench", "Runs a controlled large ItemEntity workload",
            "/itembench <start|status|stop> ...", Collections.emptyList());
        this.plugin = plugin;
    }

    @Override
    public boolean execute(final CommandSender sender, final String label, final String[] args) {
        if (args.length == 0) {
            sender.sendMessage(this.getUsage());
            return false;
        }
        try {
            return switch (args[0].toLowerCase(Locale.ROOT)) {
                case "start" -> start(sender, args);
                case "status" -> status(sender);
                case "stop" -> stop(sender);
                default -> { sender.sendMessage(this.getUsage()); yield false; }
            };
        } catch (final IllegalArgumentException exception) {
            sender.sendMessage("Itembench error: " + exception.getMessage());
            return false;
        }
    }

    private boolean start(final CommandSender sender, final String[] args) {
        if (args.length < 3 || args.length > 8) {
            sender.sendMessage("/itembench start <count> <perTick> [compact|spread|hopper-single|hopper-array|piston-array] [world] [x] [y] [z]");
            return false;
        }
        final int count = positiveInt(args[1], "count");
        final int batch = positiveInt(args[2], "perTick");
        if (count > 100_000 || batch > 10_000) throw new IllegalArgumentException("count <= 100000 and perTick <= 10000");
        final String requestedLayout = args.length >= 4 ? args[3].toLowerCase(Locale.ROOT) : "compact";
        if (!List.of("compact", "spread", "hopper-single", "hopper-array", "piston-array").contains(requestedLayout)) {
            throw new IllegalArgumentException("unknown scenario: " + requestedLayout);
        }
        final World world = args.length >= 5 ? plugin.getServer().getWorld(args[4]) : plugin.getServer().getWorlds().getFirst();
        if (world == null) throw new IllegalArgumentException("unknown world: " + args[4]);
        final Location location = world.getSpawnLocation().clone();
        if (args.length == 8) {
            location.setX(number(args[5], "x")); location.setY(number(args[6], "y")); location.setZ(number(args[7], "z"));
        } else if (args.length != 3 && args.length != 4 && args.length != 5) {
            throw new IllegalArgumentException("coordinates require world x y z");
        }
        stopInternal();
        this.origin = location;
        this.target = count;
        this.perTick = batch;
        this.layout = requestedLayout;
        this.spawned = 0;
        this.ticks = 0;
        this.measureStart = 0L;
        final Chunk benchmarkChunk = location.getChunk();
        benchmarkChunk.addPluginChunkTicket(plugin);
        this.loadedChunks.add(benchmarkChunk);
        this.buildScenario();
        this.task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        sender.sendMessage("Itembench starting: target=" + count + " perTick=" + batch + " layout=" + requestedLayout);
        return true;
    }

    private void tick() {
        ticks++;
        final int end = Math.min(target, spawned + perTick);
        while (spawned < end) {
            final int index = spawned++;
            final Location location = locationFor(index);
            // Full stacks cannot merge with each other, keeping the requested
            // ItemEntity count stable without adding synthetic per-item NBT.
            final Item item = origin.getWorld().dropItem(location, new ItemStack(Material.STONE, 64));
            item.setPickupDelay(Integer.MAX_VALUE);
            item.setUnlimitedLifetime(true);
            item.setCanMobPickup(false);
            items.add(item);
        }
        if (spawned >= target && measureStart == 0L) measureStart = System.nanoTime();
    }

    private Location locationFor(final int index) {
        if (layout.equals("compact")) {
            final double baseX = (origin.getBlockX() >> 4) * 16 + 8.0D;
            final double baseZ = (origin.getBlockZ() >> 4) * 16 + 8.0D;
            return new Location(origin.getWorld(), baseX + (index % 317) * 0.002D, origin.getY(),
                baseZ + ((index / 317) % 317) * 0.002D);
        }
        if (layout.equals("spread")) {
            final int side = 316;
            final int cell = index % (side * side);
            return origin.clone().add(cell % side, 0.0D, cell / side);
        }
        if (layout.equals("hopper-single")) return origin.clone().add(0.5D, 1.25D, 0.5D);
        final int cell = index & 255;
        return origin.clone().add((cell & 15) + 0.5D, 1.25D, (cell >>> 4) + 0.5D);
    }

    private void buildScenario() {
        if (layout.equals("hopper-single")) {
            setBlock(origin.getBlock(), Material.HOPPER);
        } else if (layout.equals("hopper-array")) {
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) setBlock(origin.clone().add(x, 0, z).getBlock(), Material.HOPPER);
        } else if (layout.equals("piston-array")) {
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                final Block piston = origin.clone().add(x, 0, z).getBlock();
                remember(piston);
                final Directional data = (Directional) Material.PISTON.createBlockData();
                data.setFacing(BlockFace.UP);
                piston.setBlockData(data, false);
                remember(piston.getRelative(BlockFace.DOWN));
            }
            this.pulseTask = plugin.getServer().getScheduler().runTaskTimer(plugin, new Runnable() {
                private boolean powered;
                @Override public void run() {
                    powered = !powered;
                    for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                        origin.getBlock().getRelative(x, -1, z).setType(powered ? Material.REDSTONE_BLOCK : Material.AIR, false);
                    }
                }
            }, 2L, 4L);
        }
    }

    private void setBlock(final Block block, final Material material) {
        remember(block); block.setType(material, false);
    }

    private void remember(final Block block) {
        changedBlocks.computeIfAbsent(block, ignored -> block.getState());
    }

    private boolean status(final CommandSender sender) {
        final String phase = task == null ? "stopped" : spawned < target ? "spawning" : "measuring";
        final Item first = items.isEmpty() ? null : items.get(0);
        sender.sendMessage("Itembench status: phase=" + phase + " target=" + target + " spawned=" + spawned
            + " live=" + liveCount() + " ticks=" + ticks + " layout=" + layout
            + " structures=" + changedBlocks.size()
            + " measuredSeconds=" + (measureStart == 0L ? 0.0D : (System.nanoTime() - measureStart) / 1_000_000_000.0D)
            + (first == null ? "" : " firstValid=" + first.isValid() + " firstDead=" + first.isDead()
                + " firstWorld=" + (first.getWorld() == null ? "null" : first.getWorld().getName())
                + " firstY=" + first.getLocation().getY()));
        return true;
    }

    private boolean stop(final CommandSender sender) {
        final String result = "Itembench stopped: spawned=" + spawned + " live=" + liveCount();
        stopInternal(); sender.sendMessage(result); return true;
    }

    private int liveCount() {
        int live = 0; for (final Item item : items) if (item.isValid()) live++; return live;
    }

    private void stopInternal() {
        if (task != null) { task.cancel(); task = null; }
        if (pulseTask != null) { pulseTask.cancel(); pulseTask = null; }
        for (final Entity item : items) if (item.isValid()) item.remove();
        items.clear();
        final List<BlockState> states = new ArrayList<>(changedBlocks.values());
        Collections.reverse(states);
        for (final BlockState state : states) state.update(true, false);
        changedBlocks.clear(); origin = null; target = perTick = spawned = 0; ticks = 0; measureStart = 0L; layout = null;
        for (final Chunk chunk : loadedChunks) chunk.removePluginChunkTicket(plugin);
        loadedChunks.clear();
    }

    void shutdown() { stopInternal(); }

    private static int positiveInt(final String value, final String name) {
        try { int result = Integer.parseInt(value); if (result > 0) return result; } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(name + " must be positive");
    }

    private static double number(final String value, final String name) {
        try { return Double.parseDouble(value); } catch (NumberFormatException exception) { throw new IllegalArgumentException(name + " must be numeric"); }
    }
}
