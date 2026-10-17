package org.purpurmc.testplugin;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.bukkit.Chunk;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Bell;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Villager;
import org.bukkit.entity.memory.MemoryKey;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** 仅用于新建的专用验收世界，不修改村民的 POI 记忆或行为实现。 */
final class BrainPoiBenchmarkCommand extends Command implements Listener {
    static final int GROUPS = 25, PER_GROUP = 80, TARGET = GROUPS * PER_GROUP;
    private final JavaPlugin plugin;
    private final List<Villager> villagers = new ArrayList<>();
    private final List<Chunk> tickets = new ArrayList<>();
    private World world;
    private BukkitTask preparation;
    private String state = "idle", phase = "work", failure = "none";
    private int chunkCursor, groupCursor, spawnCursor, cancelledGolems, censusId;
    private long phaseStart;

    BrainPoiBenchmarkCommand(JavaPlugin plugin) {
        super("brainpoibench", "Builds an isolated real-POI workload", "/brainpoibench <prepare|status|census|phase work|meet|rest>", List.of());
        this.plugin = plugin;
    }

    static int originX(int group) { return -176 + group % 5 * 80; }
    static int originZ(int group) { return -176 + group / 5 * 80; }
    static int bedX(int slot) { return 2 + slot % 10 * 3; }
    static int bedZ(int slot) { return 2 + slot / 10 * 4; }
    static long timeForPhase(String name) {
        return switch (name) { case "work" -> 6000; case "meet" -> 10000; case "rest" -> 14000;
            default -> throw new IllegalArgumentException("Unknown phase: " + name); };
    }

    @Override public boolean execute(CommandSender sender, String label, String[] args) {
        try {
            if (args.length == 1 && args[0].equals("status")) sender.sendMessage(status());
            else if (args.length == 1 && args[0].equals("census") && state.equals("ready")) sender.sendMessage(census());
            else if (args.length == 2 && args[0].equals("phase") && state.equals("ready")) {
                long time = timeForPhase(args[1]);
                world.setTime(time); phase = args[1]; phaseStart = world.getGameTime();
                sender.sendMessage("phase=" + phase + " phaseStart=" + phaseStart);
            } else if (args.length == 1 && args[0].equals("prepare") && state.equals("idle")) {
                if (!Boolean.getBoolean("lattice.brainPoiBenchmark")) throw new IllegalStateException("Explicit fixture property required");
                world = plugin.getServer().getWorld("world");
                if (world == null || world.getWorldType() != org.bukkit.WorldType.FLAT
                    || !world.getEntitiesByClass(Villager.class).isEmpty()) throw new IllegalStateException("A fresh flat world with no villagers is required");
                world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
                world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
                world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
                world.setGameRule(GameRule.RANDOM_TICK_SPEED, 0);
                world.setStorm(false); world.setThundering(false); world.setTime(6000);
                state = "preparing";
                preparation = plugin.getServer().getScheduler().runTaskTimer(plugin, this::prepareTick, 1, 1);
                sender.sendMessage(status());
            } else { sender.sendMessage(getUsage() + " state=" + state); return false; }
            return true;
        } catch (Exception error) {
            failure = error.toString().replace(' ', '_');
            sender.sendMessage("failed " + error);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Brain POI fixture failed", error);
            return false;
        }
    }

    private void prepareTick() {
        try {
            // 24 x 24 chunks cover the layout, plus a one-chunk border.
            if (chunkCursor < 26 * 26) {
                for (int count = 0; count < 12 && chunkCursor < 26 * 26; count++, chunkCursor++) {
                    Chunk chunk = world.getChunkAt(-12 + chunkCursor % 26, -12 + chunkCursor / 26);
                    chunk.addPluginChunkTicket(plugin); tickets.add(chunk);
                }
                return;
            }
            if (groupCursor < GROUPS) { buildGroup(groupCursor++); return; }
            if (spawnCursor < TARGET) {
                for (int count = 0; count < 20 && spawnCursor < TARGET; count++, spawnCursor++) {
                    int group = spawnCursor / PER_GROUP, slot = spawnCursor % PER_GROUP;
                    Location position = new Location(world, originX(group) + bedX(slot) + 1.5, -60, originZ(group) + bedZ(slot) + 2.5);
                    Villager villager = world.spawn(position, Villager.class, CreatureSpawnEvent.SpawnReason.CUSTOM, entity -> {
                        entity.setAdult(); entity.setAgeLock(true); entity.setProfession(Villager.Profession.NONE);
                        entity.setVillagerLevel(1); entity.setVillagerExperience(0);
                        entity.setAI(true); entity.setPersistent(true); entity.setRemoveWhenFarAway(false); entity.setInvulnerable(true);
                    });
                    villagers.add(villager);
                }
                return;
            }
            phaseStart = world.getGameTime(); state = "ready"; preparation.cancel(); preparation = null;
        } catch (Exception error) {
            state = "failed"; failure = error.toString().replace(' ', '_');
            preparation.cancel(); preparation = null;
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Brain POI preparation failed", error);
        }
    }

    private void buildGroup(int group) {
        int ox = originX(group), oz = originZ(group);
        // Reject a non-flat or already populated footprint before any block is overwritten.
        for (int x = 0; x < 34; x++) for (int z = 0; z < 36; z++) {
            if (world.getBlockAt(ox + x, -61, oz + z).getType() != Material.GRASS_BLOCK
                || !world.getBlockAt(ox + x, -60, oz + z).isEmpty()
                || !world.getBlockAt(ox + x, -59, oz + z).isEmpty()) throw new IllegalStateException("Unexpected fixture terrain at group " + group);
        }
        for (int x = 0; x < 34; x++) for (int z = 0; z < 36; z++) {
            if (x == 0 || x == 33 || z == 0 || z == 35) for (int y = -60; y <= -59; y++)
                world.getBlockAt(ox + x, y, oz + z).setType(Material.GLASS, false);
        }
        for (int slot = 0; slot < PER_GROUP; slot++) {
            int x = ox + bedX(slot), z = oz + bedZ(slot);
            Bed foot = (Bed) Material.RED_BED.createBlockData(); foot.setFacing(BlockFace.SOUTH); foot.setPart(Bed.Part.FOOT);
            Bed head = (Bed) foot.clone(); head.setPart(Bed.Part.HEAD);
            world.getBlockAt(x, -60, z).setBlockData(foot, false);
            world.getBlockAt(x, -60, z + 1).setBlockData(head, false);
            world.getBlockAt(x, -60, z + 2).setType(Material.BARREL, false);
        }
        for (int z : new int[]{5, 17, 29}) {
            Bell bell = (Bell) Material.BELL.createBlockData(); bell.setAttachment(Bell.Attachment.FLOOR); bell.setFacing(BlockFace.NORTH);
            world.getBlockAt(ox + 31, -60, oz + z).setBlockData(bell, false);
        }
    }

    String status() {
        return "state=" + state + " phase=" + phase + " spawned=" + villagers.size() + " live="
            + villagers.stream().filter(Villager::isValid).count() + " players=" + plugin.getServer().getOnlinePlayers().size()
            + " tickets=" + tickets.size() + " tick=" + plugin.getServer().getCurrentTick()
            + " gameTime=" + (world == null ? 0 : world.getGameTime()) + " cancelledGolems=" + cancelledGolems + " failure=" + failure;
    }

    @EventHandler public void onSpawn(CreatureSpawnEvent event) {
        if (world != null && event.getEntity().getWorld().equals(world)
            && event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.VILLAGE_DEFENSE) { event.setCancelled(true); cancelledGolems++; }
    }

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static String key(Location location) { return location.getBlockX() + ":" + location.getBlockY() + ":" + location.getBlockZ(); }
    private static void add(Map<String, Long> values, String name) { values.merge(name, 1L, Long::sum); }

    private String census() throws Exception {
        Map<String, Long> values = new LinkedHashMap<>();
        for (String name : List.of("live", "adult", "ai", "inBounds", "entityTicking", "home", "job", "meeting", "invalidMemory",
            "duplicateHome", "duplicateJob", "potentialJob", "worked", "slept", "workedSincePhase", "sleptSincePhase", "sleeping",
            "meetWithin6", "nativeEnabled", "nativeNull", "activityWork", "activityMeet", "activityRest")) values.put(name, 0L);
        Map<String, Integer> references = new HashMap<>();
        Set<String> homes = new HashSet<>(), jobs = new HashSet<>();
        int[] groupNear = new int[GROUPS];
        List<String> rows = new ArrayList<>(); rows.add("uuid,group,home,job,meeting,worked,slept,sleeping,activity");
        for (int index = 0; index < villagers.size(); index++) {
            Villager villager = villagers.get(index); int group = index / PER_GROUP;
            if (!villager.isValid()) continue;
            add(values, "live"); if (villager.isAdult()) add(values, "adult"); if (villager.hasAI()) add(values, "ai");
            Location position = villager.getLocation();
            if (position.getX() > originX(group) && position.getX() < originX(group) + 33
                && position.getZ() > originZ(group) && position.getZ() < originZ(group) + 35) add(values, "inBounds");
            if (position.getChunk().getLoadLevel() == Chunk.LoadLevel.ENTITY_TICKING) add(values, "entityTicking");
            Location home = villager.getMemory(MemoryKey.HOME), job = villager.getMemory(MemoryKey.JOB_SITE), meeting = villager.getMemory(MemoryKey.MEETING_POINT);
            Location potential = villager.getMemory(MemoryKey.POTENTIAL_JOB_SITE);
            Location[] locations = {home, job, meeting}; String[] names = {"home", "job", "meeting"};
            for (int j = 0; j < locations.length; j++) if (locations[j] != null) {
                Location location = locations[j]; boolean valid = world.equals(location.getWorld());
                Material type = valid ? location.getBlock().getType() : Material.AIR;
                valid &= j == 0 ? type == Material.RED_BED && ((Bed) location.getBlock().getBlockData()).getPart() == Bed.Part.HEAD
                    : j == 1 ? type == Material.BARREL : type == Material.BELL;
                if (valid) { add(values, names[j]); references.merge(key(location), 1, Integer::sum); }
                else add(values, "invalidMemory");
            }
            if (home != null && !homes.add(key(home))) add(values, "duplicateHome");
            if (job != null && !jobs.add(key(job))) add(values, "duplicateJob");
            if (potential != null) { add(values, "potentialJob"); references.merge(key(potential), 1, Integer::sum); }
            Long worked = villager.getMemory(MemoryKey.LAST_WORKED_AT_POI), slept = villager.getMemory(MemoryKey.LAST_SLEPT);
            if (worked != null) { add(values, "worked"); if (worked >= phaseStart) add(values, "workedSincePhase"); }
            if (slept != null) { add(values, "slept"); if (slept >= phaseStart) add(values, "sleptSincePhase"); }
            if (villager.isSleeping()) add(values, "sleeping");
            if (meeting != null && world.equals(meeting.getWorld()) && position.distanceSquared(meeting) <= 36) { add(values, "meetWithin6"); groupNear[group]++; }
            Object handle = villager.getClass().getMethod("getHandle").invoke(villager);
            Object brain = handle.getClass().getMethod("getBrain").invoke(handle);
            Object plan = field(brain, "latticeNativeEligibilityPlan");
            if (plan == null) add(values, "nativeNull"); else if (field(plan, "batch") != null) add(values, "nativeEnabled");
            String activity = brain.getClass().getMethod("getActiveNonCoreActivity").invoke(brain).toString();
            if (activity.contains("work")) add(values, "activityWork"); if (activity.contains("meet")) add(values, "activityMeet"); if (activity.contains("rest")) add(values, "activityRest");
            rows.add(villager.getUniqueId() + "," + group + "," + (home == null ? "" : key(home)) + "," + (job == null ? "" : key(job))
                + "," + (meeting == null ? "" : key(meeting)) + "," + worked + "," + slept + "," + villager.isSleeping() + "," + activity);
        }
        values.put("meetGroups", (long) java.util.Arrays.stream(groupNear).filter(n -> n > 0).count());
        values.put("worldVillagers", (long) world.getEntitiesByClass(Villager.class).size());
        inspectPoi(values, references);
        Files.createDirectories(plugin.getDataFolder().toPath());
        Files.write(plugin.getDataFolder().toPath().resolve("brain-poi-census-" + (++censusId) + ".csv"), rows, StandardOpenOption.CREATE_NEW);
        StringBuilder result = new StringBuilder(status());
        values.forEach((name, value) -> result.append(' ').append(name).append('=').append(value));
        return result.toString();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void inspectPoi(Map<String, Long> values, Map<String, Integer> references) throws Exception {
        Object level = world.getClass().getMethod("getHandle").invoke(world);
        Object manager = level.getClass().getMethod("getPoiManager").invoke(level);
        Class<?> chunkPosClass = Class.forName("net.minecraft.world.level.ChunkPos");
        Class<? extends Enum> occupancyClass = (Class<? extends Enum>) Class.forName("net.minecraft.world.entity.ai.village.poi.PoiManager$Occupancy");
        Object any = Enum.valueOf(occupancyClass, "ANY");
        var getInChunk = manager.getClass().getMethod("getInChunk", Predicate.class, chunkPosClass, occupancyClass);
        long count = 0, capacity = 0, used = 0, mismatches = 0;
        Set<String> seen = new HashSet<>();
        for (Chunk chunk : tickets) {
            Object pos = chunkPosClass.getConstructor(int.class, int.class).newInstance(chunk.getX(), chunk.getZ());
            try (Stream<?> records = (Stream<?>) getInChunk.invoke(manager, (Predicate<Object>) ignored -> true, pos, any)) {
                for (Object record : records.toList()) {
                    Object block = record.getClass().getMethod("getPos").invoke(record);
                    int x = (int) block.getClass().getMethod("getX").invoke(block), y = (int) block.getClass().getMethod("getY").invoke(block), z = (int) block.getClass().getMethod("getZ").invoke(block);
                    String key = x + ":" + y + ":" + z;
                    Object holder = record.getClass().getMethod("getPoiType").invoke(record);
                    Object type = Class.forName("net.minecraft.core.Holder").getMethod("value").invoke(holder);
                    int max = (int) type.getClass().getMethod("maxTickets").invoke(type);
                    int free = (int) record.getClass().getMethod("getFreeTickets").invoke(record);
                    count++; capacity += max; used += max - free; seen.add(key);
                    if (max - free != references.getOrDefault(key, 0) || free < 0 || free > max) mismatches++;
                }
            }
        }
        for (String key : references.keySet()) if (!seen.contains(key)) mismatches++;
        values.put("poiRecords", count); values.put("poiCapacity", capacity); values.put("poiUsed", used); values.put("ticketMismatches", mismatches);
    }

    void shutdown() {
        if (preparation != null) { preparation.cancel(); preparation = null; }
        for (Chunk chunk : tickets) chunk.removePluginChunkTicket(plugin);
        tickets.clear();
    }
}
