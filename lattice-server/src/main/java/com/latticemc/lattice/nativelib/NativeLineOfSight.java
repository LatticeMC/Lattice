package com.latticemc.lattice.nativelib;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.block.state.BlockState;

public final class NativeLineOfSight {
    private static final double MIN_NATIVE_DISTANCE_SQ = 4.0D * 4.0D;
    private static final double MAX_LOS_DISTANCE_SQ = 128.0D * 128.0D;
    private static final int MAX_RAY_MASK_VOLUME = 32768;
    private static final int SECTION_SIZE = 16;
    private static final int SECTION_VOLUME = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
    private static final int MAX_SECTION_CACHE_ENTRIES = 2048;
    /** Startup-only switch bridged by {@code LatticeConfig}; a restart is required after changes. */
    private static final boolean REUSE_SECTION_LOOKUP = Boolean.parseBoolean(
            System.getProperty("lattice.nativeLosSectionLookupReuse", "true"));
    private static final Map<SectionKey, SectionMask> SECTION_MASK_CACHE = new ConcurrentHashMap<>();
    private static final ThreadLocal<RayMaskScratch> RAY_MASK_SCRATCH =
            ThreadLocal.withInitial(RayMaskScratch::new);
    private static final ClassValue<Boolean> USES_LIVING_ENTITY_LOS = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("hasLineOfSight", Entity.class).getDeclaringClass() == LivingEntity.class;
            } catch (ReflectiveOperationException e) {
                return Boolean.FALSE;
            }
        }
    };

    private NativeLineOfSight() {}

    public record SolidMask(
            byte[] data,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ
    ) {}

    private record SectionKey(int levelIdentity, ResourceKey<Level> dimension, int sectionX, int sectionY, int sectionZ) {}

    private record SectionMask(byte[] data) {}

    private static final class RayMaskScratch {
        private byte[] data = new byte[0];
        private boolean sectionLookupValid;
        private int sectionX;
        private int sectionY;
        private int sectionZ;
        private LevelChunkSection loadedSection;

        private void beginSectionLookup() {
            sectionLookupValid = false;
            loadedSection = null;
        }

        private LevelChunkSection loadedSection(Level level, int sectionX, int sectionY, int sectionZ) {
            if (!REUSE_SECTION_LOOKUP) {
                // Preserve the pre-optimization behavior for diagnostics and A/B runs:
                // each visited block performs its own loaded-section lookup.
                return getLoadedSection(level, sectionX, sectionY, sectionZ);
            }
            if (!sectionLookupValid || this.sectionX != sectionX || this.sectionY != sectionY || this.sectionZ != sectionZ) {
                this.sectionX = sectionX;
                this.sectionY = sectionY;
                this.sectionZ = sectionZ;
                loadedSection = getLoadedSection(level, sectionX, sectionY, sectionZ);
                sectionLookupValid = true;
            }
            return loadedSection;
        }

        private byte[] prepare(int volume) {
            if (data.length < volume) {
                data = new byte[volume];
            } else {
                Arrays.fill(data, 0, volume, (byte) 0);
            }
            return data;
        }
    }

    public static boolean isAvailable() {
        LatticeNative.ensureLoaded();
        return LatticeNative.isLoaded();
    }

    public static SolidMask computeSolidMask(Level level, int centerX, int centerY, int centerZ, int radius) {
        if (radius < 0) throw new IllegalArgumentException("negative radius");
        int minX = centerX - radius;
        int minY = Math.max(level.getMinY(), centerY - radius);
        int minZ = centerZ - radius;
        int sizeX = radius * 2 + 1;
        int sizeY = Math.min(level.getMaxY(), centerY + radius) - minY + 1;
        int sizeZ = radius * 2 + 1;
        if (volume(sizeX, sizeY, sizeZ) > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("mask region too large");
        }
        byte[] data = new byte[sizeX * sizeY * sizeZ];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int y = 0; y < sizeY; ++y) {
            for (int z = 0; z < sizeZ; ++z) {
                for (int x = 0; x < sizeX; ++x) {
                    pos.set(minX + x, minY + y, minZ + z);
                    if (isCollidableToLos(level, pos, level.getBlockState(pos))) {
                        data[index(x, y, z, sizeX, sizeZ)] = 1;
                    }
                }
            }
        }
        return new SolidMask(data, minX, minY, minZ, sizeX, sizeY, sizeZ);
    }

    public static boolean hasLineOfSight(double fromX, double fromY, double fromZ,
                                         double toX, double toY, double toZ,
                                         SolidMask mask) {
        validate(mask);
        if (LatticeNative.isLoaded()) {
            return nativeCheckSingle(fromX, fromY, fromZ, toX, toY, toZ,
                    mask.data, mask.regionMinX, mask.regionMinY, mask.regionMinZ,
                    mask.regionSizeX, mask.regionSizeY, mask.regionSizeZ);
        }
        LatticeNative.logFallbackOnce("native_los", "native line-of-sight unavailable");
        return javaHasLineOfSight(fromX, fromY, fromZ, toX, toY, toZ, mask);
    }

    public static boolean[] hasLineOfSightBatch(double[] fromX, double[] fromY, double[] fromZ,
                                                double[] toX, double[] toY, double[] toZ,
                                                SolidMask mask) {
        validate(mask);
        int count = validateBatch(fromX, fromY, fromZ, toX, toY, toZ);
        boolean[] results = new boolean[count];
        if (LatticeNative.isLoaded()) {
            nativeCheckBatch(fromX, fromY, fromZ, toX, toY, toZ,
                    mask.data, mask.regionMinX, mask.regionMinY, mask.regionMinZ,
                    mask.regionSizeX, mask.regionSizeY, mask.regionSizeZ, results);
            return results;
        }
        LatticeNative.logFallbackOnce("native_los", "native line-of-sight unavailable");
        for (int i = 0; i < count; ++i) {
            results[i] = javaHasLineOfSight(fromX[i], fromY[i], fromZ[i], toX[i], toY[i], toZ[i], mask);
        }
        return results;
    }

    public static Boolean tryHasLineOfSight(Mob mob, Entity entity) {
        if (entity.level() != mob.level()) return Boolean.FALSE;
        if (!USES_LIVING_ENTITY_LOS.get(mob.getClass())) return null;
        if (!isAvailable()) return null;

        double fromX = mob.getX();
        double fromY = mob.getEyeY();
        double fromZ = mob.getZ();
        double toX = entity.getX();
        double toY = entity.getEyeY();
        double toZ = entity.getZ();
        double distanceSq = distanceToSqr(fromX, fromY, fromZ, toX, toY, toZ);
        if (distanceSq > MAX_LOS_DISTANCE_SQ) return Boolean.FALSE;
        if (distanceSq < MIN_NATIVE_DISTANCE_SQ) return null;

        SolidMask mask = computeRaySolidMask(mob.level(), fromX, fromY, fromZ, toX, toY, toZ);
        if (mask == null) return null;
        return hasLineOfSight(fromX, fromY, fromZ, toX, toY, toZ, mask) ? Boolean.TRUE : null;
    }

    /**
     * Evaluates a group of LOS requests from one mob while sharing the Java
     * solid-mask construction and one native batch call.  The returned array
     * uses the same tri-state contract as {@link #tryHasLineOfSight(Mob,
     * Entity)}: {@code FALSE} is a final distance/level rejection,
     * {@code TRUE} is a native-proven visible ray, and {@code null} requires
     * the caller to preserve the vanilla/plugin fallback path.
     *
     * <p>The input order is retained and no state is cached across ticks.
     * Callers remain responsible for their own seen/unseen caches.</p>
     */
    public static Boolean[] tryHasLineOfSightBatch(Mob mob, List<? extends Entity> entities) {
        if (mob == null || entities == null) {
            throw new IllegalArgumentException("null mob or entities");
        }
        int count = entities.size();
        Boolean[] results = new Boolean[count];
        if (count == 0) {
            return results;
        }

        Level level = mob.level();
        boolean usesLivingEntityLos = USES_LIVING_ENTITY_LOS.get(mob.getClass());
        boolean nativeAvailable = usesLivingEntityLos && isAvailable();
        if (!nativeAvailable) {
            // Cross-world is a definitive vanilla result even when the native
            // path is unavailable; all same-world requests must fall through.
            for (int i = 0; i < count; ++i) {
                Entity entity = entities.get(i);
                if (entity == null) {
                    throw new IllegalArgumentException("null entity at index " + i);
                }
                results[i] = entity.level() != level ? Boolean.FALSE : null;
            }
            return results;
        }

        double fromX = mob.getX();
        double fromY = mob.getEyeY();
        double fromZ = mob.getZ();
        int candidateCount = 0;

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i < count; ++i) {
            Entity entity = entities.get(i);
            if (entity == null) {
                throw new IllegalArgumentException("null entity at index " + i);
            }
            if (entity.level() != level) {
                results[i] = Boolean.FALSE;
                continue;
            }
            double toX = entity.getX();
            double toY = entity.getEyeY();
            double toZ = entity.getZ();
            double distanceSq = distanceToSqr(fromX, fromY, fromZ, toX, toY, toZ);
            if (distanceSq > MAX_LOS_DISTANCE_SQ) {
                results[i] = Boolean.FALSE;
                continue;
            }
            if (distanceSq < MIN_NATIVE_DISTANCE_SQ) {
                continue;
            }

            minX = Math.min(minX, Math.min(Mth.floor(fromX), Mth.floor(toX)) - 1);
            minY = Math.min(minY, Math.max(level.getMinY(), Math.min(Mth.floor(fromY), Mth.floor(toY)) - 1));
            minZ = Math.min(minZ, Math.min(Mth.floor(fromZ), Mth.floor(toZ)) - 1);
            maxX = Math.max(maxX, Math.max(Mth.floor(fromX), Mth.floor(toX)) + 1);
            maxY = Math.max(maxY, Math.min(level.getMaxY(), Math.max(Mth.floor(fromY), Mth.floor(toY)) + 1));
            maxZ = Math.max(maxZ, Math.max(Mth.floor(fromZ), Mth.floor(toZ)) + 1);
            ++candidateCount;
        }
        if (candidateCount == 0) {
            return results;
        }

        long sizeXL = (long)maxX - minX + 1L;
        long sizeYL = (long)maxY - minY + 1L;
        long sizeZL = (long)maxZ - minZ + 1L;
        long maskVolume = volume(sizeXL, sizeYL, sizeZL);
        if (sizeXL <= 0L || sizeYL <= 0L || sizeZL <= 0L
                || sizeXL > Integer.MAX_VALUE || sizeYL > Integer.MAX_VALUE || sizeZL > Integer.MAX_VALUE
                || maskVolume > MAX_RAY_MASK_VOLUME) {
            return results;
        }

        int sizeX = (int)sizeXL;
        int sizeY = (int)sizeYL;
        int sizeZ = (int)sizeZL;
        int[] candidateIndices = new int[candidateCount];
        double[] fromXs = new double[candidateCount];
        double[] fromYs = new double[candidateCount];
        double[] fromZs = new double[candidateCount];
        double[] toXs = new double[candidateCount];
        double[] toYs = new double[candidateCount];
        double[] toZs = new double[candidateCount];
        int nativeIndex = 0;
        for (int i = 0; i < count; ++i) {
            Entity entity = entities.get(i);
            if (entity == null) {
                throw new IllegalArgumentException("null entity at index " + i);
            }
            if (entity.level() != level) continue;
            double toX = entity.getX();
            double toY = entity.getEyeY();
            double toZ = entity.getZ();
            double distanceSq = distanceToSqr(fromX, fromY, fromZ, toX, toY, toZ);
            if (distanceSq <= MIN_NATIVE_DISTANCE_SQ || distanceSq > MAX_LOS_DISTANCE_SQ) continue;
            candidateIndices[nativeIndex] = i;
            fromXs[nativeIndex] = fromX;
            fromYs[nativeIndex] = fromY;
            fromZs[nativeIndex] = fromZ;
            toXs[nativeIndex] = toX;
            toYs[nativeIndex] = toY;
            toZs[nativeIndex] = toZ;
            ++nativeIndex;
        }

        RayMaskScratch scratch = RAY_MASK_SCRATCH.get();
        scratch.beginSectionLookup();
        SolidMask mask = new SolidMask(scratch.prepare((int)maskVolume),
                minX, minY, minZ, sizeX, sizeY, sizeZ);
        for (int i = 0; i < nativeIndex; ++i) {
            fillRayMask(level, fromXs[i], fromYs[i], fromZs[i], toXs[i], toYs[i], toZs[i], mask, scratch);
        }
        boolean[] nativeResults = hasLineOfSightBatch(fromXs, fromYs, fromZs, toXs, toYs, toZs, mask);
        for (int i = 0; i < nativeIndex; ++i) {
            // A native false remains unresolved so the caller can run the
            // exact vanilla clip/plugin path, matching the single-ray API.
            if (nativeResults[i]) {
                results[candidateIndices[i]] = Boolean.TRUE;
            }
        }
        return results;
    }

    public static void invalidateSection(Level level, BlockPos pos) {
        // Section masks are retained across ticks; all runtime block mutation
        // paths call this hook after a successful Level#setBlock operation.
        SECTION_MASK_CACHE.remove(sectionKey(level, pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
    }

    private static SolidMask computeRaySolidMask(Level level,
                                                 double fromX, double fromY, double fromZ,
                                                 double toX, double toY, double toZ) {
        int minX = Math.min(Mth.floor(fromX), Mth.floor(toX)) - 1;
        int minY = Math.max(level.getMinY(), Math.min(Mth.floor(fromY), Mth.floor(toY)) - 1);
        int minZ = Math.min(Mth.floor(fromZ), Mth.floor(toZ)) - 1;
        int maxX = Math.max(Mth.floor(fromX), Mth.floor(toX)) + 1;
        int maxY = Math.min(level.getMaxY(), Math.max(Mth.floor(fromY), Mth.floor(toY)) + 1);
        int maxZ = Math.max(Mth.floor(fromZ), Mth.floor(toZ)) + 1;
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;
        if (sizeX <= 0 || sizeY <= 0 || sizeZ <= 0 || volume(sizeX, sizeY, sizeZ) > MAX_RAY_MASK_VOLUME) {
            return null;
        }

        int maskVolume = Math.toIntExact(volume(sizeX, sizeY, sizeZ));
        RayMaskScratch scratch = RAY_MASK_SCRATCH.get();
        scratch.beginSectionLookup();
        SolidMask mask = new SolidMask(scratch.prepare(maskVolume),
                minX, minY, minZ, sizeX, sizeY, sizeZ);
        fillRayMask(level, fromX, fromY, fromZ, toX, toY, toZ, mask, scratch);
        return mask;
    }

    private static void fillRayMask(Level level,
                                    double fromX, double fromY, double fromZ,
                                    double toX, double toY, double toZ,
                                    SolidMask mask,
                                    RayMaskScratch scratch) {
        double adjX = 1.0E-7D * (fromX - toX);
        double adjY = 1.0E-7D * (fromY - toY);
        double adjZ = 1.0E-7D * (fromZ - toZ);
        if (adjX == 0.0D && adjY == 0.0D && adjZ == 0.0D) return;

        double fromXAdj = fromX + adjX;
        double fromYAdj = fromY + adjY;
        double fromZAdj = fromZ + adjZ;
        double toXAdj = toX - adjX;
        double toYAdj = toY - adjY;
        double toZAdj = toZ - adjZ;

        int currX = Mth.floor(fromXAdj);
        int currY = Mth.floor(fromYAdj);
        int currZ = Mth.floor(fromZAdj);
        double diffX = toXAdj - fromXAdj;
        double diffY = toYAdj - fromYAdj;
        double diffZ = toZAdj - fromZAdj;
        int dx = (int)Math.signum(diffX);
        int dy = (int)Math.signum(diffY);
        int dz = (int)Math.signum(diffZ);
        double normalizedDiffX = diffX == 0.0D ? Double.MAX_VALUE : dx / diffX;
        double normalizedDiffY = diffY == 0.0D ? Double.MAX_VALUE : dy / diffY;
        double normalizedDiffZ = diffZ == 0.0D ? Double.MAX_VALUE : dz / diffZ;
        double normalizedCurrX = normalizedDiffX * (diffX > 0.0D ? 1.0D - Mth.frac(fromXAdj) : Mth.frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0D ? 1.0D - Mth.frac(fromYAdj) : Mth.frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0D ? 1.0D - Mth.frac(fromZAdj) : Mth.frac(fromZAdj));
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (;;) {
            setRayMaskBlock(level, pos, currX, currY, currZ, mask, scratch);
            if (normalizedCurrX > 1.0D && normalizedCurrY > 1.0D && normalizedCurrZ > 1.0D) return;
            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    private static void setRayMaskBlock(Level level, BlockPos.MutableBlockPos pos, int x, int y, int z,
                                         SolidMask mask, RayMaskScratch scratch) {
        if (x < mask.regionMinX || y < mask.regionMinY || z < mask.regionMinZ
                || x >= mask.regionMinX + mask.regionSizeX
                || y >= mask.regionMinY + mask.regionSizeY
                || z >= mask.regionMinZ + mask.regionSizeZ) {
            return;
        }
        pos.set(x, y, z);
        if (isSectionMaskSolid(level, pos, x, y, z, scratch)) {
            int localX = x - mask.regionMinX;
            int localY = y - mask.regionMinY;
            int localZ = z - mask.regionMinZ;
            mask.data[index(localX, localY, localZ, mask.regionSizeX, mask.regionSizeZ)] = 1;
        }
    }

    private static boolean javaHasLineOfSight(double fromX, double fromY, double fromZ,
                                              double toX, double toY, double toZ,
                                              SolidMask mask) {
        double[] fx = {fromX};
        double[] fy = {fromY};
        double[] fz = {fromZ};
        double[] tx = {toX};
        double[] ty = {toY};
        double[] tz = {toZ};
        return hasLineOfSightBatchJava(fx, fy, fz, tx, ty, tz, mask)[0];
    }

    private static boolean[] hasLineOfSightBatchJava(double[] fromX, double[] fromY, double[] fromZ,
                                                     double[] toX, double[] toY, double[] toZ,
                                                     SolidMask mask) {
        boolean[] results = new boolean[fromX.length];
        for (int i = 0; i < fromX.length; ++i) {
            results[i] = nativeLikeDda(fromX[i], fromY[i], fromZ[i], toX[i], toY[i], toZ[i], mask);
        }
        return results;
    }

    private static boolean nativeLikeDda(double fromX, double fromY, double fromZ,
                                         double toX, double toY, double toZ,
                                         SolidMask mask) {
        double adjX = 1.0E-7D * (fromX - toX);
        double adjY = 1.0E-7D * (fromY - toY);
        double adjZ = 1.0E-7D * (fromZ - toZ);
        if (adjX == 0.0D && adjY == 0.0D && adjZ == 0.0D) return true;
        double fromXAdj = fromX + adjX;
        double fromYAdj = fromY + adjY;
        double fromZAdj = fromZ + adjZ;
        double toXAdj = toX - adjX;
        double toYAdj = toY - adjY;
        double toZAdj = toZ - adjZ;
        int currX = Mth.floor(fromXAdj);
        int currY = Mth.floor(fromYAdj);
        int currZ = Mth.floor(fromZAdj);
        double diffX = toXAdj - fromXAdj;
        double diffY = toYAdj - fromYAdj;
        double diffZ = toZAdj - fromZAdj;
        int dx = (int)Math.signum(diffX);
        int dy = (int)Math.signum(diffY);
        int dz = (int)Math.signum(diffZ);
        double normalizedDiffX = diffX == 0.0D ? Double.MAX_VALUE : dx / diffX;
        double normalizedDiffY = diffY == 0.0D ? Double.MAX_VALUE : dy / diffY;
        double normalizedDiffZ = diffZ == 0.0D ? Double.MAX_VALUE : dz / diffZ;
        double normalizedCurrX = normalizedDiffX * (diffX > 0.0D ? 1.0D - Mth.frac(fromXAdj) : Mth.frac(fromXAdj));
        double normalizedCurrY = normalizedDiffY * (diffY > 0.0D ? 1.0D - Mth.frac(fromYAdj) : Mth.frac(fromYAdj));
        double normalizedCurrZ = normalizedDiffZ * (diffZ > 0.0D ? 1.0D - Mth.frac(fromZAdj) : Mth.frac(fromZAdj));
        for (;;) {
            if (currX < mask.regionMinX || currY < mask.regionMinY || currZ < mask.regionMinZ
                    || currX >= mask.regionMinX + mask.regionSizeX
                    || currY >= mask.regionMinY + mask.regionSizeY
                    || currZ >= mask.regionMinZ + mask.regionSizeZ) {
                return false;
            }
            int localX = currX - mask.regionMinX;
            int localY = currY - mask.regionMinY;
            int localZ = currZ - mask.regionMinZ;
            if (mask.data[index(localX, localY, localZ, mask.regionSizeX, mask.regionSizeZ)] != 0) return false;
            if (normalizedCurrX > 1.0D && normalizedCurrY > 1.0D && normalizedCurrZ > 1.0D) return true;
            if (normalizedCurrX < normalizedCurrY) {
                if (normalizedCurrX < normalizedCurrZ) {
                    currX += dx;
                    normalizedCurrX += normalizedDiffX;
                } else {
                    currZ += dz;
                    normalizedCurrZ += normalizedDiffZ;
                }
            } else if (normalizedCurrY < normalizedCurrZ) {
                currY += dy;
                normalizedCurrY += normalizedDiffY;
            } else {
                currZ += dz;
                normalizedCurrZ += normalizedDiffZ;
            }
        }
    }

    private static boolean isCollidableToLos(Level level, BlockPos pos, BlockState state) {
        // ClipContext.Block.COLLIDER delegates to the block's collision shape.
        // !isAir() is not equivalent: plants and other non-colliding blocks
        // must remain transparent to line of sight.
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    private static boolean isSectionMaskSolid(Level level, BlockPos.MutableBlockPos pos, int x, int y, int z,
                                              RayMaskScratch scratch) {
        int sectionX = x >> 4;
        int sectionY = y >> 4;
        int sectionZ = z >> 4;

        // A loaded all-air section is provably transparent for COLLIDER LOS.
        // Do not call getChunk()/getBlockState() here: those APIs may synchronously
        // load a chunk.  If the section is not already available, retain the
        // existing per-cell path below so vanilla loading/fallback semantics stay
        // unchanged.
        LevelChunkSection loadedSection = scratch.loadedSection(level, sectionX, sectionY, sectionZ);
        if (loadedSection != null && loadedSection.hasOnlyAir()) {
            return false;
        }

        SectionMask mask = getSectionMask(level, sectionX, sectionY, sectionZ);
        int localIndex = index(x & 15, y & 15, z & 15, SECTION_SIZE, SECTION_SIZE);
        byte value = mask.data[localIndex];
        if (value < 0) {
            pos.set(x, y, z);
            value = (byte)(isCollidableToLos(level, pos, level.getBlockState(pos)) ? 1 : 0);
            mask.data[localIndex] = value;
        }
        return value != 0;
    }

    private static LevelChunkSection getLoadedSection(Level level, int sectionX, int sectionY, int sectionZ) {
        // Level#getChunkIfLoadedImmediately is server-only in this mapping (it
        // casts to ServerLevel internally). Public mask helpers also accept a
        // generic Level, so retain the old path for client/test levels.
        if (!(level instanceof ServerLevel)) {
            return null;
        }
        ChunkAccess chunk = level.getChunkIfLoadedImmediately(sectionX, sectionZ);
        if (chunk == null || sectionY < chunk.getMinSectionY() || sectionY > chunk.getMaxSectionY()) {
            return null;
        }
        int sectionIndex = chunk.getSectionIndex(SectionPos.sectionToBlockCoord(sectionY));
        LevelChunkSection[] sections = chunk.getSections();
        return sectionIndex >= 0 && sectionIndex < sections.length ? sections[sectionIndex] : null;
    }

    private static SectionMask getSectionMask(Level level, int sectionX, int sectionY, int sectionZ) {
        SectionKey key = sectionKey(level, sectionX, sectionY, sectionZ);
        SectionMask cached = SECTION_MASK_CACHE.get(key);
        if (cached != null) return cached;

        SectionMask built = newSectionMask();
        SECTION_MASK_CACHE.put(key, built);
        if (SECTION_MASK_CACHE.size() > MAX_SECTION_CACHE_ENTRIES) {
            pruneSectionCache();
        }
        return built;
    }

    private static SectionMask newSectionMask() {
        byte[] data = new byte[SECTION_VOLUME];
        Arrays.fill(data, (byte)-1);
        return new SectionMask(data);
    }

    private static SectionKey sectionKey(Level level, int sectionX, int sectionY, int sectionZ) {
        return new SectionKey(System.identityHashCode(level), level.dimension(), sectionX, sectionY, sectionZ);
    }

    private static void pruneSectionCache() {
        if (SECTION_MASK_CACHE.size() > MAX_SECTION_CACHE_ENTRIES) {
            SECTION_MASK_CACHE.clear();
        }
    }

    private static int index(int x, int y, int z, int sizeX, int sizeZ) {
        return (y * sizeZ + z) * sizeX + x;
    }

    private static void validate(SolidMask mask) {
        if (mask == null || mask.data == null) throw new IllegalArgumentException("null mask");
        long needed = volume(mask.regionSizeX, mask.regionSizeY, mask.regionSizeZ);
        if (mask.regionSizeX <= 0 || mask.regionSizeY <= 0 || mask.regionSizeZ <= 0
                || needed > Integer.MAX_VALUE || mask.data.length < needed) {
            throw new IllegalArgumentException("invalid mask");
        }
    }

    private static int validateBatch(double[] fromX, double[] fromY, double[] fromZ,
                                     double[] toX, double[] toY, double[] toZ) {
        if (fromX == null || fromY == null || fromZ == null || toX == null || toY == null || toZ == null) {
            throw new IllegalArgumentException("null coordinate array");
        }
        int count = fromX.length;
        if (fromY.length != count || fromZ.length != count || toX.length != count || toY.length != count || toZ.length != count) {
            throw new IllegalArgumentException("coordinate array length mismatch: "
                    + Arrays.asList(fromX.length, fromY.length, fromZ.length, toX.length, toY.length, toZ.length));
        }
        return count;
    }

    private static double distanceToSqr(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
        double dx = toX - fromX;
        double dy = toY - fromY;
        double dz = toZ - fromZ;
        return dx * dx + dy * dy + dz * dz;
    }

    private static long volume(int sizeX, int sizeY, int sizeZ) {
        return (long)sizeX * (long)sizeY * (long)sizeZ;
    }

    private static long volume(long sizeX, long sizeY, long sizeZ) {
        return sizeX * sizeY * sizeZ;
    }

    private static native boolean nativeCheckSingle(
            double fromX, double fromY, double fromZ,
            double toX, double toY, double toZ,
            byte[] solidMask,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ);

    private static native void nativeCheckBatch(
            double[] fromX, double[] fromY, double[] fromZ,
            double[] toX, double[] toY, double[] toZ,
            byte[] solidMask,
            int regionMinX, int regionMinY, int regionMinZ,
            int regionSizeX, int regionSizeY, int regionSizeZ,
            boolean[] results);
}
