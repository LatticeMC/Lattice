package com.latticemc.lattice.util;

import ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemLevel;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import io.papermc.paper.entity.activation.ActivationType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.AABB;

/**
 * Per-world entity activation accelerator for worlds with many players.
 *
 * <p>Adapted from Leaf's "optimize entity activation" patch.</p>
 *
 * <p>Original author: hayanesuru &lt;hayanesuru@outlook.jp&gt;<br>
 * Original license: MIT<br>
 * Source: https://github.com/Winds-Studio/Leaf/commit/1f36f102062075e770fb3e2aa53ea5301e3da888</p>
 */
public final class EntityActivationKdTree {
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("lattice.entityActivationKdTree", "false"));
    private static final ActivationType[] ACTIVATION_TYPES = ActivationType.values();
    private static final ServerPlayer[] EMPTY_PLAYERS = {};

    private final ObjectArrayList<Entity> entities = new ObjectArrayList<>();
    private final ObjectArrayList<Entity> hookEntities = new ObjectArrayList<>();
    private final ObjectArrayList<Entity> hookScratch = new ObjectArrayList<>();
    private final LongOpenHashSet chunks = new LongOpenHashSet();
    private final PlayerTree players = new PlayerTree();

    public static boolean isEnabled() {
        return ENABLED;
    }

    public void activateEntities(ServerLevel world) {
        final int miscActivationRange = world.spigotConfig.miscActivationRange;
        final int raiderActivationRange = world.spigotConfig.raiderActivationRange;
        final int animalActivationRange = world.spigotConfig.animalActivationRange;
        final int monsterActivationRange = world.spigotConfig.monsterActivationRange;
        final int waterActivationRange = world.spigotConfig.waterActivationRange;
        final int flyingActivationRange = world.spigotConfig.flyingMonsterActivationRange;
        final int villagerActivationRange = world.spigotConfig.villagerActivationRange;

        world.wakeupInactiveRemainingAnimals = Math.min(world.wakeupInactiveRemainingAnimals + 1, world.spigotConfig.wakeUpInactiveAnimals);
        world.wakeupInactiveRemainingVillagers = Math.min(world.wakeupInactiveRemainingVillagers + 1, world.spigotConfig.wakeUpInactiveVillagers);
        world.wakeupInactiveRemainingMonsters = Math.min(world.wakeupInactiveRemainingMonsters + 1, world.spigotConfig.wakeUpInactiveMonsters);
        world.wakeupInactiveRemainingFlying = Math.min(world.wakeupInactiveRemainingFlying + 1, world.spigotConfig.wakeUpInactiveFlying);

        int maxRange = Math.max(monsterActivationRange, animalActivationRange);
        maxRange = Math.max(maxRange, raiderActivationRange);
        maxRange = Math.max(maxRange, miscActivationRange);
        maxRange = Math.max(maxRange, flyingActivationRange);
        maxRange = Math.max(maxRange, waterActivationRange);
        maxRange = Math.max(maxRange, villagerActivationRange);
        maxRange = Math.min((world.spigotConfig.simulationDistance << 4) - 8, maxRange);

        final double[] ranges = new double[ACTIVATION_TYPES.length];
        ranges[ActivationType.WATER.ordinal()] = waterActivationRange;
        ranges[ActivationType.FLYING_MONSTER.ordinal()] = flyingActivationRange;
        ranges[ActivationType.VILLAGER.ordinal()] = villagerActivationRange;
        ranges[ActivationType.MONSTER.ordinal()] = monsterActivationRange;
        ranges[ActivationType.ANIMAL.ordinal()] = animalActivationRange;
        ranges[ActivationType.RAIDER.ordinal()] = raiderActivationRange;
        ranges[ActivationType.MISC.ordinal()] = miscActivationRange;

        final long currentTick = MinecraftServer.currentTick;
        final ServerPlayer[] players = world.players().toArray(EMPTY_PLAYERS);
        final double[] playerMinX = new double[players.length];
        final double[] playerMaxX = new double[players.length];
        final double[] playerMinZ = new double[players.length];
        final double[] playerMaxZ = new double[players.length];
        final double[] playerMinY = new double[players.length];
        final double[] playerMaxY = new double[players.length];
        int playerCount = 0;
        for (final ServerPlayer player : players) {
            player.activatedTick = currentTick;
            if (world.spigotConfig.ignoreSpectatorActivation && player.isSpectator()) {
                continue;
            }
            if (!world.purpurConfig.idleTimeoutTickNearbyEntities && player.isAfk()) {
                continue;
            }
            final AABB playerBox = player.getBoundingBox();
            playerMinX[playerCount] = playerBox.minX;
            playerMaxX[playerCount] = playerBox.maxX;
            playerMinZ[playerCount] = playerBox.minZ;
            playerMaxZ[playerCount] = playerBox.maxZ;
            playerMinY[playerCount] = playerBox.minY;
            playerMaxY[playerCount] = playerBox.maxY;
            players[playerCount++] = player;
        }

        try {
            this.players.build(playerMinX, playerMaxX, playerMinY, playerMaxY, playerMinZ, playerMaxZ, playerCount);
            this.collectCandidates(world, players, playerCount, maxRange);

            final boolean tickMarkers = world.paperConfig().entities.markers.tick;
            this.activateCollectedEntities(currentTick, tickMarkers, ranges, this.entities);
            this.activateCollectedEntities(currentTick, tickMarkers, ranges, this.hookEntities);
        } finally {
            this.entities.clear();
            this.chunks.clear();
            this.hookEntities.clear();
            this.hookScratch.clear();
        }
    }

    private void activateCollectedEntities(long currentTick, boolean tickMarkers, double[] ranges, ObjectArrayList<Entity> collected) {
        final Object[] rawEntities = collected.elements();
        for (int index = 0, size = collected.size(); index < size; index++) {
            final Entity entity = (Entity) rawEntities[index];
            if (!tickMarkers && entity instanceof Marker) {
                continue;
            }
            if (currentTick <= entity.activatedTick) {
                continue;
            }
            final double range = ranges[entity.activationType.ordinal()];
            final AABB entityBox = entity.getBoundingBox();
            if (entity.defaultActivationState || this.players.intersects(entityBox, range)) {
                entity.activatedTick = currentTick;
            }
        }
    }

    private void collectCandidates(ServerLevel world, ServerPlayer[] players, int playerCount, int maxRange) {
        final EntityLookup lookup = ((ChunkSystemLevel) world).moonrise$getEntityLookup();
        final double worldHeight = world.getHeight();
        for (int index = 0; index < playerCount; index++) {
            final AABB box = players[index].getBoundingBox().inflate(maxRange, worldHeight, maxRange);
            lookup.lattice$getEntitiesForActivation(box, this.chunks, this.entities);
            this.hookScratch.clear();
            ca.spottedleaf.moonrise.common.PlatformHooks.get().addToGetEntities(world, null, box, null, this.hookScratch);
            for (int hookIndex = 0, hookSize = this.hookScratch.size(); hookIndex < hookSize; hookIndex++) {
                final Entity entity = this.hookScratch.get(hookIndex);
                if (!containsIdentity(this.hookEntities, entity)) {
                    this.hookEntities.add(entity);
                }
            }
        }
    }

    private static boolean containsIdentity(ObjectArrayList<Entity> entities, Entity target) {
        for (int index = 0, size = entities.size(); index < size; index++) {
            if (entities.get(index) == target) {
                return true;
            }
        }
        return false;
    }

    private static final class PlayerTree {
        private static final int EMPTY = -1;

        private int[] indices = new int[0];
        private int[] right = new int[0];
        private byte[] axis = new byte[0];
        private double[] x = new double[0];
        private double[] z = new double[0];
        private int[] search = new int[0];
        private double[] playerMinX = new double[0];
        private double[] playerMaxX = new double[0];
        private double[] playerMinZ = new double[0];
        private double[] playerMaxZ = new double[0];
        private double[] playerMinY = new double[0];
        private double[] playerMaxY = new double[0];
        private int nodeCount;

        void build(double[] sourceMinX, double[] sourceMaxX, double[] sourceMinY, double[] sourceMaxY,
                   double[] sourceMinZ, double[] sourceMaxZ, int count) {
            this.nodeCount = 0;
            if (count == 0) {
                return;
            }
            this.ensureCapacity(count);
            for (int index = 0; index < count; index++) {
                this.indices[index] = index;
            }
            this.buildNode(sourceMinX, sourceMaxX, sourceMinY, sourceMaxY, sourceMinZ, sourceMaxZ, 0, count, 0);
        }

        boolean intersects(AABB target, double maximumDistance) {
            if (this.nodeCount == 0 || maximumDistance < 0.0) {
                return false;
            }
            if (this.search.length < this.nodeCount) {
                this.search = new int[this.nodeCount];
            }
            final int[] stack = this.search;
            int stackSize = 0;
            stack[stackSize++] = 0;
            while (stackSize != 0) {
                final int node = stack[--stackSize];
                if (target.maxX <= this.playerMinX[node] - maximumDistance
                        || target.minX >= this.playerMaxX[node] + maximumDistance
                        || target.maxZ <= this.playerMinZ[node] - maximumDistance
                        || target.minZ >= this.playerMaxZ[node] + maximumDistance) {
                    continue;
                }
                final int child = this.right[node];
                if (child == EMPTY) {
                    // Keep Paper's strict AABB intersection semantics at the matching player's leaf.
                    if (target.maxY > this.playerMinY[node] - maximumDistance
                            && target.minY < this.playerMaxY[node] + maximumDistance) {
                        return true;
                    }
                    continue;
                }

                final int left = node + 1;
                stack[stackSize++] = left;
                stack[stackSize++] = child;
            }
            return false;
        }

        private int buildNode(double[] sourceMinX, double[] sourceMaxX, double[] sourceMinY, double[] sourceMaxY,
                              double[] sourceMinZ, double[] sourceMaxZ, int start, int end, int depth) {
            final int node = this.nodeCount++;
            final int length = end - start;
            this.axis[node] = (byte) (depth & 1);
            if (length == 1) {
                final int point = this.indices[start];
                this.right[node] = EMPTY;
                this.x[node] = (sourceMinX[point] + sourceMaxX[point]) * 0.5;
                this.z[node] = (sourceMinZ[point] + sourceMaxZ[point]) * 0.5;
                this.playerMinX[node] = sourceMinX[point];
                this.playerMaxX[node] = sourceMaxX[point];
                this.playerMinZ[node] = sourceMinZ[point];
                this.playerMaxZ[node] = sourceMaxZ[point];
                this.playerMinY[node] = sourceMinY[point];
                this.playerMaxY[node] = sourceMaxY[point];
                return node;
            }

            final int median = start + (length - 1) / 2;
            this.select(sourceMinX, sourceMaxX, sourceMinZ, sourceMaxZ, start, end - 1, median, depth & 1);
            final int point = this.indices[median];
            this.x[node] = depth % 2 == 0 ? (sourceMinX[point] + sourceMaxX[point]) * 0.5 : (sourceMinZ[point] + sourceMaxZ[point]) * 0.5;
            final int left = this.buildNode(sourceMinX, sourceMaxX, sourceMinY, sourceMaxY, sourceMinZ, sourceMaxZ, start, median + 1, depth + 1);
            this.right[node] = this.buildNode(sourceMinX, sourceMaxX, sourceMinY, sourceMaxY, sourceMinZ, sourceMaxZ, median + 1, end, depth + 1);
            this.playerMinX[node] = Math.min(this.playerMinX[left], this.playerMinX[this.right[node]]);
            this.playerMaxX[node] = Math.max(this.playerMaxX[left], this.playerMaxX[this.right[node]]);
            this.playerMinZ[node] = Math.min(this.playerMinZ[left], this.playerMinZ[this.right[node]]);
            this.playerMaxZ[node] = Math.max(this.playerMaxZ[left], this.playerMaxZ[this.right[node]]);
            return node;
        }

        private void select(double[] sourceMinX, double[] sourceMaxX, double[] sourceMinZ, double[] sourceMaxZ,
                            int left, int right, int target, int axis) {
            while (left < right) {
                final double pivot = coordinate(sourceMinX, sourceMaxX, sourceMinZ, sourceMaxZ, this.indices[(left + right) >>> 1], axis);
                int lower = left;
                int upper = right;
                while (lower <= upper) {
                    while (coordinate(sourceMinX, sourceMaxX, sourceMinZ, sourceMaxZ, this.indices[lower], axis) < pivot) {
                        lower++;
                    }
                    while (coordinate(sourceMinX, sourceMaxX, sourceMinZ, sourceMaxZ, this.indices[upper], axis) > pivot) {
                        upper--;
                    }
                    if (lower <= upper) {
                        final int value = this.indices[lower];
                        this.indices[lower++] = this.indices[upper];
                        this.indices[upper--] = value;
                    }
                }
                if (target <= upper) {
                    right = upper;
                } else if (target >= lower) {
                    left = lower;
                } else {
                    return;
                }
            }
        }

        private static double coordinate(double[] sourceMinX, double[] sourceMaxX, double[] sourceMinZ, double[] sourceMaxZ,
                                         int point, int axis) {
            return axis == 0 ? (sourceMinX[point] + sourceMaxX[point]) * 0.5 : (sourceMinZ[point] + sourceMaxZ[point]) * 0.5;
        }

        private void ensureCapacity(int capacity) {
            if (this.indices.length >= capacity) {
                return;
            }
            final int nodeCapacity = capacity * 2 - 1;
            this.indices = new int[capacity];
            this.right = new int[nodeCapacity];
            this.axis = new byte[nodeCapacity];
            this.x = new double[nodeCapacity];
            this.z = new double[nodeCapacity];
            this.playerMinX = new double[nodeCapacity];
            this.playerMaxX = new double[nodeCapacity];
            this.playerMinZ = new double[nodeCapacity];
            this.playerMaxZ = new double[nodeCapacity];
            this.playerMinY = new double[nodeCapacity];
            this.playerMaxY = new double[nodeCapacity];
        }
    }
}
